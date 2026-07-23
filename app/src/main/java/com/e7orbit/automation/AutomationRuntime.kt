package com.e7orbit.automation

import com.e7orbit.data.DiagnosticStore
import com.e7orbit.data.SettingsRepository
import com.e7orbit.logging.NoOpOrbitLogger
import com.e7orbit.logging.OrbitLogger
import com.e7orbit.model.AutomationPhase
import com.e7orbit.model.AutomationStatus
import com.e7orbit.model.RunConfig
import com.e7orbit.model.RunStats
import com.e7orbit.model.RunSummary
import com.e7orbit.model.ScreenFrame
import com.e7orbit.model.StopReason
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

internal interface ShopRuntimePersistence {
    suspend fun saveConfig(config: RunConfig)
    suspend fun saveSummary(summary: RunSummary)
}

internal fun interface ShopRuntimeDiagnosticSink {
    suspend fun save(frame: ScreenFrame, reason: String): String?
}

private class RepositoryShopRuntimePersistence(
    private val repository: SettingsRepository,
) : ShopRuntimePersistence {
    override suspend fun saveConfig(config: RunConfig) = repository.saveConfig(config)

    override suspend fun saveSummary(summary: RunSummary) = repository.saveSummary(summary)
}

private class StoreShopRuntimeDiagnosticSink(
    private val store: DiagnosticStore,
) : ShopRuntimeDiagnosticSink {
    override suspend fun save(frame: ScreenFrame, reason: String): String =
        store.save(frame, reason).absolutePath
}

class AutomationRuntime internal constructor(
    private val vision: ShopVision,
    private val visionConfig: VisionConfig,
    private val persistence: ShopRuntimePersistence,
    private val diagnosticSink: ShopRuntimeDiagnosticSink,
    private val logger: OrbitLogger = NoOpOrbitLogger,
    private val captureReady: () -> Boolean = { true },
    private val clock: AutomationClock = SystemAutomationClock,
    private val runCoordinator: AutomationRunCoordinator? = null,
    private val sharedSessionManager: AutomationSessionManager? = null,
    private val homeNavigator: HomeNavigator? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AutomationController {
    constructor(
        vision: ShopVision,
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
        persistence = RepositoryShopRuntimePersistence(settingsRepository),
        diagnosticSink = StoreShopRuntimeDiagnosticSink(diagnosticStore),
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
        AutomationStatus(
            templatesReady = templatesReady(),
        ),
    )
    override val status: StateFlow<AutomationStatus> = _status.asStateFlow()

    private var phaseBeforePause = AutomationPhase.IDLE

    fun attachGateway(gateway: ScreenGateway) {
        gatewayRef.set(gateway)
        _status.value = _status.value.copy(serviceReady = true)
        logger.info("runtime.gateway.attached")
    }

    fun detachGateway(gateway: ScreenGateway) {
        if (!gatewayRef.compareAndSet(gateway, null)) {
            logger.debug("runtime.gateway.detach_ignored")
            return
        }
        _status.value = _status.value.copy(serviceReady = false)
        logger.warn("runtime.gateway.detached", "phase" to _status.value.phase)
        if (_status.value.isRunning || _status.value.phase == AutomationPhase.PAUSED) {
            stopWithReason(
                reason = StopReason.SERVICE_UNAVAILABLE,
                message = "无障碍服务已断开",
                phase = AutomationPhase.ERROR,
            )
        }
    }

    override suspend fun start(config: RunConfig) {
        startMutex.withLock {
            activeRunRef.get()?.let { activeRun ->
                if (!_status.value.isTerminal) return
                activeRun.job.join()
                completeRun(activeRun)
            }
            val normalized = config.normalized()
            val gateway = gatewayRef.get()
            val templatesReady = templatesReady()
            val missingTemplates = missingTemplates()
            logger.info(
                "runtime.start.requested",
                "covenant" to normalized.buyCovenantBookmarks,
                "mystic" to normalized.buyMysticMedals,
                "maxRefreshes" to normalized.maxRefreshes,
                "threshold" to normalized.matchThreshold,
                "gatewayReady" to (gateway != null),
                "captureReady" to captureReady(),
                "templatesReady" to templatesReady,
                "missingTemplates" to missingTemplates.joinToString(),
            )

            when {
                !normalized.hasPurchaseTarget -> {
                    rejectStart(
                        normalized,
                        StopReason.INVALID_CONFIGURATION,
                        "至少选择一种购买目标",
                    )
                    return
                }

                gateway == null -> {
                    rejectStart(
                        normalized,
                        StopReason.SERVICE_UNAVAILABLE,
                        "请先启用 E7 Orbit 无障碍服务",
                    )
                    return
                }

                !captureReady() -> {
                    rejectStart(
                        normalized,
                        StopReason.SCREENSHOT_FAILED,
                        "请先授权屏幕捕获",
                    )
                    return
                }

                !templatesReady -> {
                    rejectStart(
                        normalized,
                        StopReason.TEMPLATES_MISSING,
                        "识图模板未就绪：${missingTemplates.joinToString()}",
                    )
                    return
                }
            }
            val managedSession = sessionManager.tryOpen(
                kind = AutomationKind.SHOP,
                gateway = sessionGateway,
                clock = clock,
                awaitRunPermission = { paused.first { isPaused -> !isPaused } },
                onDiagnostic = { frame, reason ->
                    val file = diagnosticSink.save(frame, reason)
                    logger.info(
                        "diagnostic.saved",
                        "reason" to reason,
                        "sequence" to frame.sequence,
                        "file" to file.orEmpty(),
                    )
                },
                logger = logger,
            )
            if (managedSession == null) {
                rejectStart(
                    normalized,
                    StopReason.INVALID_CONFIGURATION,
                    "其他自动化正在运行",
                )
                return
            }

            paused.value = false
            _status.value = AutomationStatus(
                phase = AutomationPhase.WAITING_FOR_SHOP,
                config = normalized,
                stats = RunStats(startedAtElapsedMs = clock.elapsedRealtime()),
                message = "正在定位游戏主页",
                serviceReady = true,
                templatesReady = templatesReady,
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
                error("Automation run was installed concurrently")
            }
            job.start()
            logger.info("runtime.start.accepted")
        }
    }

    override fun pause() {
        val current = _status.value
        if (!current.isRunning) return
        phaseBeforePause = current.phase
        paused.value = true
        logger.info("runtime.paused", "phase" to current.phase)
        _status.value = current.copy(
            phase = AutomationPhase.PAUSED,
            message = "已暂停，不会执行点击",
        )
    }

    override fun resume() {
        val current = _status.value
        if (current.phase != AutomationPhase.PAUSED) return
        paused.value = false
        logger.info("runtime.resumed", "resumePhase" to phaseBeforePause)
        _status.value = current.copy(
            phase = phaseBeforePause.takeUnless { it == AutomationPhase.IDLE }
                ?: AutomationPhase.WAITING_FOR_SHOP,
            message = "继续运行",
        )
    }

    override fun stop() {
        logger.info("runtime.stop.requested", "phase" to _status.value.phase)
        val currentPhase = _status.value.phase
            .takeUnless { it == AutomationPhase.PAUSED }
            ?: phaseBeforePause
        val uncertainMessage = when (currentPhase) {
            AutomationPhase.VERIFYING_PURCHASE ->
                "已停止；购买确认可能刚刚生效，请核对物品与金币"
            AutomationPhase.REFRESHING,
            AutomationPhase.WAITING_FOR_REFRESH,
            -> "已停止；刷新确认可能刚刚生效，请核对天空石与商店页面"
            else -> null
        }
        stopWithReason(
            reason = if (uncertainMessage == null) {
                StopReason.USER_STOPPED
            } else {
                StopReason.UNCERTAIN_EFFECT
            },
            message = uncertainMessage ?: "已由用户停止",
            phase = AutomationPhase.COMPLETED,
        )
    }

    fun restart() {
        val current = _status.value
        if (!current.isTerminal) return
        val runToAwait = activeRunRef.get()
        logger.info("runtime.restart.requested", "reason" to current.stopReason)
        scope.launch {
            awaitRunCompletion(runToAwait)
            start(current.config)
        }
    }

    fun dismissTerminalStatus() {
        val current = _status.value
        if (!current.isTerminal) return
        logger.info("runtime.terminal.dismissed", "reason" to current.stopReason)
        _status.value = AutomationStatus(
            phase = AutomationPhase.IDLE,
            config = current.config,
            message = "尚未运行",
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
        logger.info("runtime.shutdown")
        activeRunRef.get()?.let { activeRun ->
            activeRun.stopRequested.set(true)
            activeRun.job.cancel()
        }
        scope.cancel()
    }

    private suspend fun runSession(
        config: RunConfig,
        session: AutomationSession,
        lease: RunLease,
    ) {
        publishIfCurrent(lease) { current ->
            current.copy(message = "正在等待游戏进入横屏")
        }
        if (!session.gateway.awaitTargetApp(GAME_LAUNCH_TIMEOUT_MS)) {
            publishIfCurrent(lease) { current ->
                current.copy(
                    phase = AutomationPhase.ERROR,
                    message = "等待游戏启动超时，请确认国服客户端可以正常打开",
                    stopReason = StopReason.TIMEOUT,
                )
            }
            return
        }

        try {
            persistence.saveConfig(config)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            logger.error("runtime.config.save_failed", error)
            publishIfCurrent(lease) { current ->
                current.copy(
                    phase = AutomationPhase.ERROR,
                    message = "保存自动刷新设置失败：${error.message.orEmpty()}",
                    stopReason = StopReason.INTERNAL_ERROR,
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
        config: RunConfig,
        session: AutomationSession,
        lease: RunLease,
    ) {
        val machine = BookmarkStateMachine(
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
                    logger.debug(
                        "runtime.status",
                        "phase" to phase,
                        "refreshes" to stats.completedRefreshes,
                        "pagesScanned" to stats.shopPagesScanned,
                        "covenant" to stats.covenantBookmarksBought,
                        "mystic" to stats.mysticMedalsBought,
                        "goldSpent" to stats.goldSpent,
                        "confidence" to confidence,
                        "message" to message,
                    )
                    publishIfCurrent(lease) { current ->
                        current.copy(
                            phase = if (paused.value) AutomationPhase.PAUSED else phase,
                            stats = stats,
                            message = message,
                            lastConfidence = confidence,
                        )
                    }
                },
            )
            val terminalPhase = if (result.successful) {
                AutomationPhase.COMPLETED
            } else {
                AutomationPhase.ERROR
            }
            publishIfCurrent(lease) { current ->
                current.copy(
                    phase = terminalPhase,
                    stats = result.stats,
                    message = result.message,
                    stopReason = result.reason,
                )
            }
            logger.info(
                "runtime.finished",
                "successful" to result.successful,
                "reason" to result.reason,
                "message" to result.message,
                "refreshes" to result.stats.completedRefreshes,
            )
            if (canPublish(lease)) {
                persistSummary(result.stats, result.reason)
            }
        } catch (cancelled: CancellationException) {
            logger.warn("runtime.cancelled", "message" to cancelled.message)
            throw cancelled
        } catch (error: Throwable) {
            logger.error("runtime.failed", error, "phase" to _status.value.phase)
            if (canPublish(lease)) {
                val finalStats = _status.value.stats.copy(
                    finishedAtElapsedMs = clock.elapsedRealtime(),
                )
                _status.value = _status.value.copy(
                    phase = AutomationPhase.ERROR,
                    stats = finalStats,
                    message = "运行异常：${error.message.orEmpty()}",
                    stopReason = StopReason.INTERNAL_ERROR,
                )
                persistSummary(finalStats, StopReason.INTERNAL_ERROR)
            }
        }
    }

    private fun rejectStart(
        config: RunConfig,
        reason: StopReason,
        message: String,
    ) {
        logger.warn(
            "runtime.start.rejected",
            "reason" to reason,
            "message" to message,
        )
        _status.value = _status.value.copy(
            phase = AutomationPhase.ERROR,
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

    private companion object {
        const val GAME_LAUNCH_TIMEOUT_MS = 20_000L
    }

    private fun stopWithReason(
        reason: StopReason,
        message: String,
        phase: AutomationPhase,
    ) {
        logger.warn(
            "runtime.stopped",
            "reason" to reason,
            "phase" to phase,
            "message" to message,
        )
        activeRunRef.get()?.let { activeRun ->
            activeRun.stopRequested.set(true)
            activeRun.job.cancel(CancellationException(message))
        }
        paused.value = false
        val finalStats = _status.value.stats.copy(
            finishedAtElapsedMs = clock.elapsedRealtime(),
        )
        _status.value = _status.value.copy(
            phase = phase,
            stats = finalStats,
            message = message,
            stopReason = reason,
        )
        scope.launch { persistSummary(finalStats, reason) }
    }

    private suspend fun persistSummary(
        stats: RunStats,
        reason: StopReason,
    ) {
        persistence.saveSummary(
            RunSummary(
                completedRefreshes = stats.completedRefreshes,
                shopPagesScanned = stats.shopPagesScanned,
                covenantBookmarksBought = stats.covenantBookmarksBought,
                mysticMedalsBought = stats.mysticMedalsBought,
                goldSpent = stats.goldSpent,
                elapsedMs = stats.elapsedMs,
                stopReason = reason.name,
                completedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    private fun canPublish(lease: RunLease): Boolean {
        val activeRun = activeRunRef.get()
        return activeRun?.lease == lease && !activeRun.stopRequested.get()
    }

    private inline fun publishIfCurrent(
        lease: RunLease,
        update: (AutomationStatus) -> AutomationStatus,
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
            "runtime.run.completed",
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
        if (current.stopReason == StopReason.UNCERTAIN_EFFECT) return
        _status.value = current.copy(
            stopReason = StopReason.UNCERTAIN_EFFECT,
            message = "${current.message}；操作 ${receipt.operationId} 被中断后效果不确定，请核对游戏状态",
        )
    }
}
