package com.e7orbit.automation

import com.e7orbit.data.DiagnosticStore
import com.e7orbit.logging.NoOpOrbitLogger
import com.e7orbit.logging.OrbitLogger
import com.e7orbit.model.AutomationPhase
import com.e7orbit.model.AutomationStatus
import com.e7orbit.model.HuntConfig
import com.e7orbit.model.HuntPhase
import com.e7orbit.model.HuntStats
import com.e7orbit.model.HuntStatus
import com.e7orbit.model.HuntStopReason
import com.e7orbit.model.RunConfig
import com.e7orbit.model.RunStats
import com.e7orbit.model.RunSummary
import com.e7orbit.model.ScreenFrame
import com.e7orbit.model.StopReason
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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

internal fun interface TaskDiagnosticSink {
    suspend fun save(frame: ScreenFrame, reason: String): String?
}

private class StoreTaskDiagnosticSink(
    private val store: DiagnosticStore,
) : TaskDiagnosticSink {
    override suspend fun save(frame: ScreenFrame, reason: String): String =
        store.save(frame, reason).absolutePath
}

class TaskCoordinator internal constructor(
    private val shopRunner: ShopTaskRunner,
    private val huntRunner: HuntTaskRunner,
    private val uiMonitor: GameUiMonitor,
    private val diagnosticSink: TaskDiagnosticSink,
    private val checkpointStore: WorkflowCheckpointStore = InMemoryWorkflowCheckpointStore(),
    private val logger: OrbitLogger = NoOpOrbitLogger,
    private val captureReady: () -> Boolean = { true },
    private val clock: AutomationClock = SystemAutomationClock,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    constructor(
        shopRunner: ShopTaskRunner,
        huntRunner: HuntTaskRunner,
        uiMonitor: GameUiMonitor,
        diagnosticStore: DiagnosticStore,
        checkpointStore: WorkflowCheckpointStore = InMemoryWorkflowCheckpointStore(),
        logger: OrbitLogger = NoOpOrbitLogger,
        captureReady: () -> Boolean = { true },
        clock: AutomationClock = SystemAutomationClock,
    ) : this(
        shopRunner = shopRunner,
        huntRunner = huntRunner,
        uiMonitor = uiMonitor,
        diagnosticSink = StoreTaskDiagnosticSink(diagnosticStore),
        checkpointStore = checkpointStore,
        logger = logger,
        captureReady = captureReady,
        clock = clock,
        dispatcher = Dispatchers.Default,
    )

    private class ActiveTask(
        val token: Long,
        val kind: TaskKind,
        val session: AutomationSession,
    ) {
        lateinit var job: Job
        val stopRequested = AtomicBoolean(false)
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val startMutex = Mutex()
    private val nextToken = AtomicLong(0L)
    private val gatewayRef = AtomicReference<ScreenGateway?>()
    private val sessionGateway = SwitchingScreenGateway(gatewayRef::get)
    private val activeRef = AtomicReference<ActiveTask?>()
    private val paused = MutableStateFlow(false)
    private val _activeTask = MutableStateFlow<TaskKind?>(null)
    private val _shopStatus = MutableStateFlow(
        AutomationStatus(templatesReady = shopRunner.health().isReady),
    )
    private val _huntStatus = MutableStateFlow(
        HuntStatus(templatesReady = huntRunner.health().isReady),
    )

    val shopStatus: StateFlow<AutomationStatus> = _shopStatus.asStateFlow()
    val huntStatus: StateFlow<HuntStatus> = _huntStatus.asStateFlow()
    val activeTask: StateFlow<TaskKind?> = _activeTask.asStateFlow()
    val uiState: StateFlow<GameUiSnapshot> = uiMonitor.state

    private var shopPhaseBeforePause = AutomationPhase.IDLE
    private var huntPhaseBeforePause = HuntPhase.IDLE

    fun attachGateway(gateway: ScreenGateway) {
        gatewayRef.set(gateway)
        uiMonitor.attachGateway(gateway)
        refreshHealth()
        logger.info("task.gateway.attached")
    }

    fun detachGateway(gateway: ScreenGateway) {
        if (!gatewayRef.compareAndSet(gateway, null)) {
            logger.debug("task.gateway.detach_ignored")
            return
        }
        uiMonitor.detachGateway(gateway)
        refreshHealth()
        when (activeRef.get()?.kind) {
            TaskKind.SHOP -> stopShop(
                reason = StopReason.SERVICE_UNAVAILABLE,
                message = "无障碍服务已断开",
                phase = AutomationPhase.ERROR,
            )

            TaskKind.HUNT -> stopHunt(
                reason = HuntStopReason.SERVICE_UNAVAILABLE,
                message = "无障碍服务已断开",
                phase = HuntPhase.ERROR,
            )

            null -> Unit
        }
    }

    suspend fun startShop(config: RunConfig) {
        startMutex.withLock {
            val normalized = config.normalized()
            if (!prepareSlot()) {
                if (activeRef.get()?.kind != TaskKind.SHOP) {
                    rejectShop(
                        normalized,
                        StopReason.INVALID_CONFIGURATION,
                        "其他自动化正在运行",
                    )
                }
                return
            }
            val gateway = gatewayRef.get()
            val health = shopRunner.health()
            when {
                !normalized.hasPurchaseTarget -> rejectShop(
                    normalized,
                    StopReason.INVALID_CONFIGURATION,
                    "至少选择一种购买目标",
                )

                gateway == null -> rejectShop(
                    normalized,
                    StopReason.SERVICE_UNAVAILABLE,
                    "请先启用 E7 Orbit 无障碍服务",
                )

                !captureReady() -> rejectShop(
                    normalized,
                    StopReason.SCREENSHOT_FAILED,
                    "请先授权屏幕捕获",
                )

                !health.isReady -> rejectShop(
                    normalized,
                    StopReason.TEMPLATES_MISSING,
                    "识图模板未就绪：${health.missingTemplateIds.joinToString()}",
                )

                else -> launchShop(normalized)
            }
        }
    }

    suspend fun startHunt(config: HuntConfig) {
        startMutex.withLock {
            val normalized = config.normalized()
            if (!prepareSlot()) {
                if (activeRef.get()?.kind != TaskKind.HUNT) {
                    rejectHunt(
                        normalized,
                        HuntStopReason.INVALID_CONFIGURATION,
                        "其他自动化正在运行",
                    )
                }
                return
            }
            val gateway = gatewayRef.get()
            val health = huntRunner.health()
            when {
                gateway == null -> rejectHunt(
                    normalized,
                    HuntStopReason.SERVICE_UNAVAILABLE,
                    "请先启用 E7 Orbit 无障碍服务",
                )

                !captureReady() -> rejectHunt(
                    normalized,
                    HuntStopReason.SCREENSHOT_FAILED,
                    "请先授权屏幕捕获",
                )

                !health.isReady -> rejectHunt(
                    normalized,
                    HuntStopReason.TEMPLATES_MISSING,
                    "讨伐识图模板未就绪：${health.missingTemplateIds.joinToString()}",
                )

                else -> launchHunt(normalized)
            }
        }
    }

    fun pause(kind: TaskKind) {
        val active = activeRef.get()?.takeIf { it.kind == kind } ?: return
        when (kind) {
            TaskKind.SHOP -> {
                val current = _shopStatus.value
                if (!current.isRunning) return
                shopPhaseBeforePause = current.phase
                _shopStatus.value = current.copy(
                    phase = AutomationPhase.PAUSED,
                    message = "已暂停，不会执行点击",
                )
            }

            TaskKind.HUNT -> {
                val current = _huntStatus.value
                if (!current.isRunning) return
                huntPhaseBeforePause = current.phase
                _huntStatus.value = current.copy(
                    phase = HuntPhase.PAUSED,
                    message = "已暂停，不会执行点击",
                )
            }
        }
        paused.value = true
        logger.info("task.paused", "task" to active.kind)
    }

    fun resume(kind: TaskKind) {
        activeRef.get()?.takeIf { it.kind == kind } ?: return
        when (kind) {
            TaskKind.SHOP -> {
                val current = _shopStatus.value
                if (current.phase != AutomationPhase.PAUSED) return
                _shopStatus.value = current.copy(
                    phase = shopPhaseBeforePause.takeUnless { it == AutomationPhase.IDLE }
                        ?: AutomationPhase.WAITING_FOR_SHOP,
                    message = "继续运行",
                )
            }

            TaskKind.HUNT -> {
                val current = _huntStatus.value
                if (current.phase != HuntPhase.PAUSED) return
                _huntStatus.value = current.copy(
                    phase = huntPhaseBeforePause.takeUnless { it == HuntPhase.IDLE }
                        ?: HuntPhase.WAITING_FOR_LOBBY,
                    message = "继续运行",
                )
            }
        }
        paused.value = false
        logger.info("task.resumed", "task" to kind)
    }

    fun stop(kind: TaskKind) {
        if (activeRef.get()?.kind != kind) return
        when (kind) {
            TaskKind.SHOP -> {
                val currentPhase = _shopStatus.value.phase
                    .takeUnless { it == AutomationPhase.PAUSED }
                    ?: shopPhaseBeforePause
                val uncertainMessage = when (currentPhase) {
                    AutomationPhase.VERIFYING_PURCHASE ->
                        "已停止；购买确认可能刚刚生效，请核对物品与金币"
                    AutomationPhase.REFRESHING,
                    AutomationPhase.WAITING_FOR_REFRESH,
                    -> "已停止；刷新确认可能刚刚生效，请核对天空石与商店页面"
                    else -> null
                }
                stopShop(
                    reason = if (uncertainMessage == null) {
                        StopReason.USER_STOPPED
                    } else {
                        StopReason.UNCERTAIN_EFFECT
                    },
                    message = uncertainMessage ?: "已由用户停止",
                    phase = AutomationPhase.COMPLETED,
                )
            }

            TaskKind.HUNT -> {
                val currentPhase = _huntStatus.value.phase
                    .takeUnless { it == HuntPhase.PAUSED }
                    ?: huntPhaseBeforePause
                val uncertainMessage = when (currentPhase) {
                    HuntPhase.STARTING_BATTLE,
                    HuntPhase.WAITING_FOR_BATTLE_CONTROLS,
                    -> "已停止；讨伐可能已经开始并消耗行动力，请检查游戏"
                    HuntPhase.CONFIRMING_DELEGATION,
                    HuntPhase.MANAGED_IN_LOBBY,
                    -> "已停止；游戏内托管可能仍在运行，请立即检查"
                    else -> null
                }
                stopHunt(
                    reason = if (uncertainMessage == null) {
                        HuntStopReason.USER_STOPPED
                    } else {
                        HuntStopReason.UNCERTAIN_EFFECT
                    },
                    message = uncertainMessage ?: "已由用户停止",
                    phase = HuntPhase.COMPLETED,
                )
            }
        }
    }

    fun restart(kind: TaskKind) {
        val active = activeRef.get()
        when (kind) {
            TaskKind.SHOP -> {
                val current = _shopStatus.value
                if (!current.isTerminal) return
                scope.launch {
                    awaitTaskCompletion(active?.takeIf { it.kind == kind })
                    startShop(current.config)
                }
            }

            TaskKind.HUNT -> {
                val current = _huntStatus.value
                if (!current.isTerminal) return
                scope.launch {
                    awaitTaskCompletion(active?.takeIf { it.kind == kind })
                    startHunt(current.config)
                }
            }
        }
    }

    fun dismiss(kind: TaskKind) {
        when (kind) {
            TaskKind.SHOP -> {
                val current = _shopStatus.value
                if (!current.isTerminal) return
                _shopStatus.value = AutomationStatus(
                    config = current.config,
                    serviceReady = gatewayRef.get() != null,
                    templatesReady = shopRunner.health().isReady,
                )
            }

            TaskKind.HUNT -> {
                val current = _huntStatus.value
                if (!current.isTerminal) return
                _huntStatus.value = HuntStatus(
                    config = current.config,
                    serviceReady = gatewayRef.get() != null,
                    templatesReady = huntRunner.health().isReady,
                )
            }
        }
    }

    fun refreshHealth() {
        val serviceReady = gatewayRef.get() != null
        _shopStatus.value = _shopStatus.value.copy(
            serviceReady = serviceReady,
            templatesReady = shopRunner.health().isReady,
        )
        _huntStatus.value = _huntStatus.value.copy(
            serviceReady = serviceReady,
            templatesReady = huntRunner.health().isReady,
        )
    }

    fun shutdown() {
        activeRef.get()?.let { active ->
            active.stopRequested.set(true)
            active.job.cancel()
        }
        uiMonitor.shutdown()
        scope.cancel()
    }

    private suspend fun prepareSlot(): Boolean {
        val active = activeRef.get() ?: return true
        val terminal = when (active.kind) {
            TaskKind.SHOP -> _shopStatus.value.isTerminal
            TaskKind.HUNT -> _huntStatus.value.isTerminal
        }
        if (terminal) {
            active.job.join()
            completeTask(active)
            return activeRef.get() == null
        }
        return false
    }

    private fun launchShop(config: RunConfig) {
        paused.value = false
        val session = newSession()
        session.updateUiContract(shopRunner.uiContract(AutomationPhase.WAITING_FOR_SHOP))
        _shopStatus.value = AutomationStatus(
            phase = AutomationPhase.WAITING_FOR_SHOP,
            config = config,
            stats = RunStats(startedAtElapsedMs = clock.elapsedRealtime()),
            message = "正在定位游戏主页",
            serviceReady = true,
            templatesReady = true,
        )
        installTask(TaskKind.SHOP, session) { active -> runShop(active, config) }
    }

    private fun launchHunt(config: HuntConfig) {
        paused.value = false
        val session = newSession()
        session.updateUiContract(huntRunner.uiContract(HuntPhase.WAITING_FOR_LOBBY))
        _huntStatus.value = HuntStatus(
            phase = HuntPhase.WAITING_FOR_LOBBY,
            config = config,
            stats = HuntStats(startedAtElapsedMs = clock.elapsedRealtime()),
            message = "正在定位游戏主页",
            serviceReady = true,
            templatesReady = true,
        )
        installTask(TaskKind.HUNT, session) { active -> runHunt(active, config) }
    }

    private fun installTask(
        kind: TaskKind,
        session: AutomationSession,
        run: suspend (ActiveTask) -> Unit,
    ) {
        val active = ActiveTask(
            token = nextToken.incrementAndGet(),
            kind = kind,
            session = session,
        )
        active.job = scope.launch(start = CoroutineStart.LAZY) { run(active) }
        active.job.invokeOnCompletion { completeTask(active) }
        check(activeRef.compareAndSet(null, active)) { "Task was installed concurrently" }
        _activeTask.value = kind
        active.job.start()
        logger.info("task.started", "task" to kind, "token" to active.token)
    }

    private fun newSession(): AutomationSession = AutomationSession(
        gateway = sessionGateway,
        uiStateSource = uiMonitor,
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
        checkpointStore = checkpointStore,
    )

    private suspend fun runShop(active: ActiveTask, config: RunConfig) {
        if (!awaitGame(active, shop = true)) return
        try {
            shopRunner.prepare(config)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            logger.error("task.shop.config.save_failed", error)
            publishShop(active) { current ->
                current.copy(
                    phase = AutomationPhase.ERROR,
                    message = "保存自动刷新设置失败：${error.message.orEmpty()}",
                    stopReason = StopReason.INTERNAL_ERROR,
                )
            }
            return
        }
        currentCoroutineContext().ensureActive()
        try {
            val result = shopRunner.run(config, active.session) { phase, stats, message, confidence ->
                active.session.updateUiContract(shopRunner.uiContract(phase))
                publishShop(active) { current ->
                    current.copy(
                        phase = if (paused.value) AutomationPhase.PAUSED else phase,
                        stats = stats,
                        message = message,
                        lastConfidence = confidence,
                    )
                }
            }
            publishShop(active) { current ->
                current.copy(
                    phase = if (result.successful) {
                        AutomationPhase.COMPLETED
                    } else {
                        AutomationPhase.ERROR
                    },
                    stats = result.stats,
                    message = result.message,
                    stopReason = result.reason,
                )
            }
            if (canPublish(active)) saveShopSummary(result.stats, result.reason)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            logger.error("task.shop.failed", error)
            val finalStats = _shopStatus.value.stats.copy(
                finishedAtElapsedMs = clock.elapsedRealtime(),
            )
            publishShop(active) { current ->
                current.copy(
                    phase = AutomationPhase.ERROR,
                    stats = finalStats,
                    message = "运行异常：${error.message.orEmpty()}",
                    stopReason = StopReason.INTERNAL_ERROR,
                )
            }
            if (canPublish(active)) saveShopSummary(finalStats, StopReason.INTERNAL_ERROR)
        }
    }

    private suspend fun runHunt(active: ActiveTask, config: HuntConfig) {
        if (!awaitGame(active, shop = false)) return
        try {
            huntRunner.prepare(config)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            logger.error("task.hunt.config.save_failed", error)
            publishHunt(active) { current ->
                current.copy(
                    phase = HuntPhase.ERROR,
                    message = "保存自动讨伐设置失败：${error.message.orEmpty()}",
                    stopReason = HuntStopReason.INTERNAL_ERROR,
                )
            }
            return
        }
        currentCoroutineContext().ensureActive()
        try {
            val result = huntRunner.run(config, active.session) { phase, stats, message, confidence ->
                active.session.updateUiContract(huntRunner.uiContract(phase))
                publishHunt(active) { current ->
                    current.copy(
                        phase = if (paused.value) HuntPhase.PAUSED else phase,
                        stats = stats,
                        message = message,
                        lastConfidence = confidence,
                    )
                }
            }
            publishHunt(active) { current ->
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
            logger.error("task.hunt.failed", error)
            publishHunt(active) { current ->
                current.copy(
                    phase = HuntPhase.ERROR,
                    stats = current.stats.copy(finishedAtElapsedMs = clock.elapsedRealtime()),
                    message = "自动讨伐异常：${error.message.orEmpty()}",
                    stopReason = HuntStopReason.INTERNAL_ERROR,
                )
            }
        }
    }

    private suspend fun awaitGame(active: ActiveTask, shop: Boolean): Boolean {
        val available = try {
            active.session.gateway.awaitTargetApp(GAME_LAUNCH_TIMEOUT_MS)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            logger.error("task.game.await_failed", error, "task" to active.kind)
            publishGameUnavailable(active, shop, "等待游戏启动失败：${error.message.orEmpty()}")
            return false
        }
        if (available) return true
        publishGameUnavailable(active, shop, "等待游戏启动超时，请确认国服客户端可以正常打开")
        return false
    }

    private fun publishGameUnavailable(active: ActiveTask, shop: Boolean, message: String) {
        if (shop) {
            publishShop(active) { current ->
                current.copy(
                    phase = AutomationPhase.ERROR,
                    message = message,
                    stopReason = StopReason.TIMEOUT,
                )
            }
        } else {
            publishHunt(active) { current ->
                current.copy(
                    phase = HuntPhase.ERROR,
                    message = message,
                    stopReason = HuntStopReason.TIMEOUT,
                )
            }
        }
    }

    private fun stopShop(reason: StopReason, message: String, phase: AutomationPhase) {
        val active = activeRef.get()?.takeIf { it.kind == TaskKind.SHOP }?.also { active ->
            active.stopRequested.set(true)
        }
        paused.value = false
        val finalStats = _shopStatus.value.stats.copy(
            finishedAtElapsedMs = clock.elapsedRealtime(),
        )
        _shopStatus.value = _shopStatus.value.copy(
            phase = phase,
            stats = finalStats,
            message = message,
            stopReason = reason,
        )
        active?.job?.cancel(CancellationException(message))
        active?.let(::reconcileInterruptedGesture)
        scope.launch {
            active?.job?.join()
            val persistedReason = if (
                active?.session?.latestGestureReceipt()?.effectMayBeUncertain == true
            ) {
                StopReason.UNCERTAIN_EFFECT
            } else {
                reason
            }
            runCatching { saveShopSummary(finalStats, persistedReason) }
                .onFailure { error -> logger.error("task.shop.summary.save_failed", error) }
        }
    }

    private fun stopHunt(reason: HuntStopReason, message: String, phase: HuntPhase) {
        val active = activeRef.get()?.takeIf { it.kind == TaskKind.HUNT }?.also { active ->
            active.stopRequested.set(true)
        }
        paused.value = false
        _huntStatus.value = _huntStatus.value.copy(
            phase = phase,
            stats = _huntStatus.value.stats.copy(finishedAtElapsedMs = clock.elapsedRealtime()),
            message = message,
            stopReason = reason,
        )
        active?.job?.cancel(CancellationException(message))
        active?.let(::reconcileInterruptedGesture)
    }

    private fun rejectShop(config: RunConfig, reason: StopReason, message: String) {
        _shopStatus.value = _shopStatus.value.copy(
            phase = AutomationPhase.ERROR,
            config = config,
            message = message,
            stopReason = reason,
            serviceReady = gatewayRef.get() != null,
            templatesReady = shopRunner.health().isReady,
        )
    }

    private fun rejectHunt(config: HuntConfig, reason: HuntStopReason, message: String) {
        _huntStatus.value = _huntStatus.value.copy(
            phase = HuntPhase.ERROR,
            config = config,
            message = message,
            stopReason = reason,
            serviceReady = gatewayRef.get() != null,
            templatesReady = huntRunner.health().isReady,
        )
    }

    private suspend fun saveShopSummary(stats: RunStats, reason: StopReason) {
        shopRunner.saveSummary(
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

    private fun canPublish(active: ActiveTask): Boolean =
        activeRef.get() === active && !active.stopRequested.get()

    private inline fun publishShop(
        active: ActiveTask,
        update: (AutomationStatus) -> AutomationStatus,
    ) {
        if (canPublish(active)) _shopStatus.value = update(_shopStatus.value)
    }

    private inline fun publishHunt(
        active: ActiveTask,
        update: (HuntStatus) -> HuntStatus,
    ) {
        if (canPublish(active)) _huntStatus.value = update(_huntStatus.value)
    }

    private suspend fun awaitTaskCompletion(active: ActiveTask?) {
        active ?: return
        active.job.join()
        completeTask(active)
    }

    private fun completeTask(active: ActiveTask) {
        reconcileInterruptedGesture(active)
        if (!activeRef.compareAndSet(active, null)) return
        _activeTask.value = null
        logger.debug("task.completed", "task" to active.kind, "token" to active.token)
    }

    private fun reconcileInterruptedGesture(active: ActiveTask) {
        if (!active.stopRequested.get() || activeRef.get() !== active) return
        val receipt = active.session.latestGestureReceipt()
            ?.takeIf(GestureReceipt::effectMayBeUncertain)
            ?: return
        when (active.kind) {
            TaskKind.SHOP -> {
                val current = _shopStatus.value
                if (current.stopReason == StopReason.UNCERTAIN_EFFECT) return
                _shopStatus.value = current.copy(
                    stopReason = StopReason.UNCERTAIN_EFFECT,
                    message = "${current.message}；操作 ${receipt.operationId} 被中断后效果不确定，请核对游戏状态",
                )
            }

            TaskKind.HUNT -> {
                val current = _huntStatus.value
                if (current.stopReason == HuntStopReason.UNCERTAIN_EFFECT) return
                _huntStatus.value = current.copy(
                    stopReason = HuntStopReason.UNCERTAIN_EFFECT,
                    message = "${current.message}；操作 ${receipt.operationId} 被中断后效果不确定，请核对游戏状态",
                )
            }
        }
    }

    private companion object {
        const val GAME_LAUNCH_TIMEOUT_MS = 20_000L
    }
}
