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
import com.e7orbit.model.StopReason
import com.e7orbit.vision.VisionConfig
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AutomationRuntime(
    private val vision: ShopVision,
    private val visionConfig: VisionConfig,
    private val settingsRepository: SettingsRepository,
    private val diagnosticStore: DiagnosticStore,
    private val logger: OrbitLogger = NoOpOrbitLogger,
    private val captureReady: () -> Boolean = { true },
    private val clock: AutomationClock = SystemAutomationClock,
    private val runCoordinator: AutomationRunCoordinator? = null,
) : AutomationController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val startMutex = Mutex()
    private val gatewayRef = AtomicReference<ScreenGateway?>()
    private val paused = MutableStateFlow(false)
    private val _status = MutableStateFlow(
        AutomationStatus(
            templatesReady = vision.health().isReady,
        ),
    )
    override val status: StateFlow<AutomationStatus> = _status.asStateFlow()

    private var runJob: Job? = null
    private var phaseBeforePause = AutomationPhase.IDLE

    fun attachGateway(gateway: ScreenGateway) {
        gatewayRef.set(gateway)
        _status.value = _status.value.copy(serviceReady = true)
        logger.info("runtime.gateway.attached")
    }

    fun detachGateway(gateway: ScreenGateway) {
        gatewayRef.compareAndSet(gateway, null)
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
            if (runJob?.isActive == true) return
            val normalized = config.normalized()
            val gateway = gatewayRef.get()
            val health = vision.health()
            logger.info(
                "runtime.start.requested",
                "covenant" to normalized.buyCovenantBookmarks,
                "mystic" to normalized.buyMysticMedals,
                "maxRefreshes" to normalized.maxRefreshes,
                "threshold" to normalized.matchThreshold,
                "gatewayReady" to (gateway != null),
                "captureReady" to captureReady(),
                "templatesReady" to health.isReady,
                "missingTemplates" to health.missingTemplateIds.joinToString(),
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

                !health.isReady -> {
                    rejectStart(
                        normalized,
                        StopReason.TEMPLATES_MISSING,
                        "识图模板未就绪：${vision.health().missingTemplateIds.joinToString()}",
                    )
                    return
                }
            }
            if (runCoordinator?.tryAcquire(AutomationKind.SHOP) == false) {
                rejectStart(
                    normalized,
                    StopReason.INVALID_CONFIGURATION,
                    "其他自动化正在运行",
                )
                return
            }

            settingsRepository.saveConfig(normalized)
            paused.value = false
            _status.value = AutomationStatus(
                phase = AutomationPhase.WAITING_FOR_SHOP,
                config = normalized,
                stats = RunStats(startedAtElapsedMs = clock.elapsedRealtime()),
                message = "等待主页或秘密商店",
                serviceReady = true,
                templatesReady = vision.health().isReady,
            )
            runJob = scope.launch {
                runMachine(normalized, gateway)
            }
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
        stopWithReason(
            reason = StopReason.USER_STOPPED,
            message = "已由用户停止",
            phase = AutomationPhase.COMPLETED,
        )
    }

    fun restart() {
        val current = _status.value
        if (!current.isTerminal) return
        logger.info("runtime.restart.requested", "reason" to current.stopReason)
        scope.launch { start(current.config) }
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
            templatesReady = vision.health().isReady,
        )
    }

    fun refreshHealth() {
        _status.value = _status.value.copy(
            serviceReady = gatewayRef.get() != null,
            templatesReady = vision.health().isReady,
        )
    }

    fun shutdown() {
        logger.info("runtime.shutdown")
        runJob?.cancel()
        runCoordinator?.release(AutomationKind.SHOP)
        scope.cancel()
    }

    private suspend fun runMachine(
        config: RunConfig,
        gateway: ScreenGateway,
    ) {
        val machine = BookmarkStateMachine(
            vision = vision,
            visionConfig = visionConfig,
            clock = clock,
            logger = logger,
        )
        try {
            val result = machine.run(
                config = config,
                gateway = gateway,
                awaitRunPermission = {
                    paused.first { isPaused -> !isPaused }
                },
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
                    _status.value = _status.value.copy(
                        phase = if (paused.value) AutomationPhase.PAUSED else phase,
                        stats = stats,
                        message = message,
                        lastConfidence = confidence,
                    )
                },
                onDiagnostic = { frame, reason ->
                    val file = diagnosticStore.save(frame, reason)
                    logger.info(
                        "diagnostic.saved",
                        "reason" to reason,
                        "sequence" to frame.sequence,
                        "file" to file.absolutePath,
                    )
                },
            )
            val terminalPhase = if (result.successful) {
                AutomationPhase.COMPLETED
            } else {
                AutomationPhase.ERROR
            }
            _status.value = _status.value.copy(
                phase = terminalPhase,
                stats = result.stats,
                message = result.message,
                stopReason = result.reason,
            )
            logger.info(
                "runtime.finished",
                "successful" to result.successful,
                "reason" to result.reason,
                "message" to result.message,
                "refreshes" to result.stats.completedRefreshes,
            )
            persistSummary(result.stats, result.reason)
        } catch (cancelled: CancellationException) {
            logger.warn("runtime.cancelled", "message" to cancelled.message)
            throw cancelled
        } catch (error: Throwable) {
            logger.error("runtime.failed", error, "phase" to _status.value.phase)
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
        } finally {
            runJob = null
            runCoordinator?.release(AutomationKind.SHOP)
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
            templatesReady = vision.health().isReady,
        )
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
        runJob?.cancel(CancellationException(message))
        runJob = null
        runCoordinator?.release(AutomationKind.SHOP)
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
        settingsRepository.saveSummary(
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
}
