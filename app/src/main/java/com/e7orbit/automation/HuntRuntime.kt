package com.e7orbit.automation

import com.e7orbit.data.DiagnosticStore
import com.e7orbit.data.SettingsRepository
import com.e7orbit.logging.NoOpOrbitLogger
import com.e7orbit.logging.OrbitLogger
import com.e7orbit.model.HuntConfig
import com.e7orbit.model.HuntPhase
import com.e7orbit.model.HuntStats
import com.e7orbit.model.HuntStatus
import com.e7orbit.model.HuntStopReason
import com.e7orbit.model.ScreenFrame
import com.e7orbit.vision.VisionConfig
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun interface HuntRuntimePersistence {
    suspend fun saveConfig(config: HuntConfig)
}

internal fun interface HuntRuntimeDiagnosticSink {
    suspend fun save(frame: ScreenFrame, reason: String)
}

private class RepositoryHuntRuntimePersistence(
    private val repository: SettingsRepository,
) : HuntRuntimePersistence {
    override suspend fun saveConfig(config: HuntConfig) = repository.saveHuntConfig(config)
}

private class StoreHuntRuntimeDiagnosticSink(
    private val store: DiagnosticStore,
) : HuntRuntimeDiagnosticSink {
    override suspend fun save(frame: ScreenFrame, reason: String) {
        store.save(frame, reason)
    }
}

class HuntRuntime internal constructor(
    private val vision: HuntVision,
    private val visionConfig: VisionConfig,
    private val persistence: HuntRuntimePersistence,
    private val diagnosticSink: HuntRuntimeDiagnosticSink,
    private val logger: OrbitLogger = NoOpOrbitLogger,
    private val captureReady: () -> Boolean = { true },
    private val clock: AutomationClock = SystemAutomationClock,
    private val runCoordinator: AutomationRunCoordinator? = null,
    private val sharedSessionManager: AutomationSessionManager? = null,
    private val homeNavigator: HomeNavigator? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    constructor(
        vision: HuntVision,
        visionConfig: VisionConfig,
        settingsRepository: SettingsRepository,
        diagnosticStore: DiagnosticStore,
        logger: OrbitLogger = NoOpOrbitLogger,
        captureReady: () -> Boolean = { true },
        clock: AutomationClock = SystemAutomationClock,
        runCoordinator: AutomationRunCoordinator? = null,
        sessionManager: AutomationSessionManager? = null,
        homeNavigator: HomeNavigator? = null,
    ) : this(
        vision = vision,
        visionConfig = visionConfig,
        persistence = RepositoryHuntRuntimePersistence(settingsRepository),
        diagnosticSink = StoreHuntRuntimeDiagnosticSink(diagnosticStore),
        logger = logger,
        captureReady = captureReady,
        clock = clock,
        runCoordinator = runCoordinator,
        sharedSessionManager = sessionManager,
        homeNavigator = homeNavigator,
        dispatcher = Dispatchers.Default,
    )

    private class ActiveRun(
        val managedSession: ManagedAutomationSession,
        val job: Job,
    ) {
        val lease: RunLease
            get() = managedSession.lease
        val stopRequested = AtomicBoolean(false)
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val startMutex = Mutex()
    private val sessionManager = sharedSessionManager ?: AutomationSessionManager(
        runCoordinator ?: AutomationRunCoordinator(),
    )
    private val gatewayRef = AtomicReference<ScreenGateway?>()
    private val sessionGateway = SwitchingScreenGateway(gatewayRef::get)
    private val activeRunRef = AtomicReference<ActiveRun?>()
    private val paused = MutableStateFlow(false)
    private val _status = MutableStateFlow(
        HuntStatus(templatesReady = templatesReady()),
    )
    val status: StateFlow<HuntStatus> = _status.asStateFlow()

    private var phaseBeforePause = HuntPhase.IDLE

    fun attachGateway(gateway: ScreenGateway) {
        gatewayRef.set(gateway)
        _status.value = _status.value.copy(serviceReady = true)
    }

    fun detachGateway(gateway: ScreenGateway) {
        if (!gatewayRef.compareAndSet(gateway, null)) return
        _status.value = _status.value.copy(serviceReady = false)
        if (_status.value.isRunning || _status.value.phase == HuntPhase.PAUSED) {
            stopWithReason(
                reason = HuntStopReason.SERVICE_UNAVAILABLE,
                message = "无障碍服务已断开",
                phase = HuntPhase.ERROR,
            )
        }
    }

    suspend fun start(config: HuntConfig) {
        startMutex.withLock {
            activeRunRef.get()?.let { activeRun ->
                if (!_status.value.isTerminal) return
                activeRun.job.join()
                completeRun(activeRun)
            }
            val normalized = config.normalized()
            val gateway = gatewayRef.get()
            val health = vision.health()
            val navigationHealth = homeNavigator?.health()
            when {
                gateway == null -> {
                    rejectStart(
                        normalized,
                        HuntStopReason.SERVICE_UNAVAILABLE,
                        "请先启用 E7 Orbit 无障碍服务",
                    )
                    return
                }

                !captureReady() -> {
                    rejectStart(
                        normalized,
                        HuntStopReason.SCREENSHOT_FAILED,
                        "请先授权屏幕捕获",
                    )
                    return
                }

                !health.isReady || navigationHealth?.isReady == false -> {
                    rejectStart(
                        normalized,
                        HuntStopReason.TEMPLATES_MISSING,
                        "讨伐识图模板未就绪：${missingTemplates().joinToString()}",
                    )
                    return
                }
            }
            val managedSession = sessionManager.tryOpen(
                kind = AutomationKind.HUNT,
                gateway = sessionGateway,
                clock = clock,
                awaitRunPermission = { paused.first { isPaused -> !isPaused } },
                onDiagnostic = { frame, reason -> diagnosticSink.save(frame, reason) },
                logger = logger,
            )
            if (managedSession == null) {
                rejectStart(
                    normalized,
                    HuntStopReason.INVALID_CONFIGURATION,
                    "其他自动化正在运行",
                )
                return
            }

            paused.value = false
            _status.value = HuntStatus(
                phase = HuntPhase.WAITING_FOR_LOBBY,
                config = normalized,
                stats = HuntStats(startedAtElapsedMs = clock.elapsedRealtime()),
                message = "正在定位游戏主页",
                serviceReady = true,
                templatesReady = templatesReady(),
            )
            val job = scope.launch(start = CoroutineStart.LAZY) {
                runSession(
                    config = normalized,
                    session = managedSession.session,
                    lease = managedSession.lease,
                )
            }
            val activeRun = ActiveRun(managedSession = managedSession, job = job)
            job.invokeOnCompletion { completeRun(activeRun) }
            if (!activeRunRef.compareAndSet(null, activeRun)) {
                job.cancel()
                managedSession.close()
                error("Hunt run was installed concurrently")
            }
            job.start()
        }
    }

    fun pause() {
        val current = _status.value
        if (!current.isRunning) return
        phaseBeforePause = current.phase
        paused.value = true
        _status.value = current.copy(
            phase = HuntPhase.PAUSED,
            message = "已暂停，不会执行点击",
        )
    }

    fun resume() {
        val current = _status.value
        if (current.phase != HuntPhase.PAUSED) return
        paused.value = false
        _status.value = current.copy(
            phase = phaseBeforePause.takeUnless { it == HuntPhase.IDLE }
                ?: HuntPhase.WAITING_FOR_LOBBY,
            message = "继续运行",
        )
    }

    fun stop() {
        val currentPhase = _status.value.phase
            .takeUnless { it == HuntPhase.PAUSED }
            ?: phaseBeforePause
        val uncertainMessage = when (currentPhase) {
            HuntPhase.STARTING_BATTLE,
            HuntPhase.WAITING_FOR_BATTLE_CONTROLS,
            -> "已停止；讨伐可能已经开始并消耗行动力，请检查游戏"
            HuntPhase.CONFIRMING_DELEGATION,
            HuntPhase.MANAGED_IN_LOBBY,
            -> "已停止；游戏内托管可能仍在运行，请立即检查"
            else -> null
        }
        stopWithReason(
            reason = if (uncertainMessage == null) {
                HuntStopReason.USER_STOPPED
            } else {
                HuntStopReason.UNCERTAIN_EFFECT
            },
            message = uncertainMessage ?: "已由用户停止",
            phase = HuntPhase.COMPLETED,
        )
    }

    fun restart() {
        val current = _status.value
        if (!current.isTerminal) return
        val runToAwait = activeRunRef.get()
        scope.launch {
            awaitRunCompletion(runToAwait)
            start(current.config)
        }
    }

    fun dismissTerminalStatus() {
        val current = _status.value
        if (!current.isTerminal) return
        _status.value = HuntStatus(
            config = current.config,
            serviceReady = gatewayRef.get() != null,
            templatesReady = templatesReady(),
        )
    }

    fun refreshHealth() {
        _status.value = _status.value.copy(
            serviceReady = gatewayRef.get() != null,
            templatesReady = templatesReady(),
        )
    }

    fun shutdown() {
        activeRunRef.get()?.let { activeRun ->
            activeRun.stopRequested.set(true)
            activeRun.job.cancel()
        }
        scope.cancel()
    }

    private suspend fun runSession(
        config: HuntConfig,
        session: AutomationSession,
        lease: RunLease,
    ) {
        try {
            persistence.saveConfig(config)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            logger.error("hunt.runtime.config.save_failed", error)
            publishIfCurrent(lease) { current ->
                current.copy(
                    phase = HuntPhase.ERROR,
                    message = "保存自动讨伐设置失败：${error.message.orEmpty()}",
                    stopReason = HuntStopReason.INTERNAL_ERROR,
                )
            }
            return
        }

        currentCoroutineContext().ensureActive()
        runMachine(
            config = config,
            session = session,
            lease = lease,
        )
    }

    private suspend fun runMachine(
        config: HuntConfig,
        session: AutomationSession,
        lease: RunLease,
    ) {
        val machine = HuntStateMachine(
            vision = vision,
            visionConfig = visionConfig,
            clock = clock,
            logger = logger,
            homeNavigator = homeNavigator,
        )
        try {
            val result = machine.run(
                config = config,
                session = session,
                onStatus = { phase, stats, message, confidence ->
                    publishIfCurrent(lease) { current ->
                        current.copy(
                            phase = if (paused.value) HuntPhase.PAUSED else phase,
                            stats = stats,
                            message = message,
                            lastConfidence = confidence,
                        )
                    }
                },
            )
            publishIfCurrent(lease) { current ->
                current.copy(
                    phase = if (result.successful) HuntPhase.COMPLETED else HuntPhase.ERROR,
                    stats = result.stats,
                    message = result.message,
                    stopReason = result.reason,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            logger.error("hunt.runtime.failed", error, "phase" to _status.value.phase)
            publishIfCurrent(lease) { current ->
                current.copy(
                    phase = HuntPhase.ERROR,
                    stats = current.stats.copy(
                        finishedAtElapsedMs = clock.elapsedRealtime(),
                    ),
                    message = "自动讨伐异常：${error.message.orEmpty()}",
                    stopReason = HuntStopReason.INTERNAL_ERROR,
                )
            }
        }
    }

    private fun rejectStart(
        config: HuntConfig,
        reason: HuntStopReason,
        message: String,
    ) {
        _status.value = _status.value.copy(
            phase = HuntPhase.ERROR,
            config = config,
            message = message,
            stopReason = reason,
            serviceReady = gatewayRef.get() != null,
            templatesReady = templatesReady(),
        )
    }

    private fun templatesReady(): Boolean =
        vision.health().isReady && (homeNavigator?.health()?.isReady ?: true)

    private fun missingTemplates(): List<String> = buildList {
        addAll(vision.health().missingTemplateIds)
        homeNavigator?.health()?.missingTemplateIds?.let(::addAll)
    }.distinct()

    private fun stopWithReason(
        reason: HuntStopReason,
        message: String,
        phase: HuntPhase,
    ) {
        activeRunRef.get()?.let { activeRun ->
            activeRun.stopRequested.set(true)
            activeRun.job.cancel(CancellationException(message))
        }
        paused.value = false
        _status.value = _status.value.copy(
            phase = phase,
            stats = _status.value.stats.copy(
                finishedAtElapsedMs = clock.elapsedRealtime(),
            ),
            message = message,
            stopReason = reason,
        )
    }

    private fun canPublish(lease: RunLease): Boolean {
        val activeRun = activeRunRef.get()
        return activeRun?.lease == lease && !activeRun.stopRequested.get()
    }

    private inline fun publishIfCurrent(
        lease: RunLease,
        update: (HuntStatus) -> HuntStatus,
    ) {
        if (canPublish(lease)) {
            _status.value = update(_status.value)
        }
    }

    private suspend fun awaitRunCompletion(activeRun: ActiveRun?) {
        activeRun ?: return
        activeRun.job.join()
        completeRun(activeRun)
    }

    private fun completeRun(activeRun: ActiveRun) {
        reconcileInterruptedGesture(activeRun)
        val released = activeRun.managedSession.release()
        val removed = activeRunRef.compareAndSet(activeRun, null)
        logger.debug(
            "hunt.runtime.run.completed",
            "lease" to activeRun.lease.token,
            "removed" to removed,
            "released" to released,
        )
    }

    private fun reconcileInterruptedGesture(activeRun: ActiveRun) {
        if (!activeRun.stopRequested.get() || activeRunRef.get() !== activeRun) return
        val receipt = activeRun.managedSession.session.latestGestureReceipt()
            ?.takeIf(GestureReceipt::effectMayBeUncertain)
            ?: return
        val current = _status.value
        if (current.stopReason == HuntStopReason.UNCERTAIN_EFFECT) return
        _status.value = current.copy(
            stopReason = HuntStopReason.UNCERTAIN_EFFECT,
            message = "${current.message}；操作 ${receipt.operationId} 被中断后效果不确定，请核对游戏状态",
        )
    }
}
