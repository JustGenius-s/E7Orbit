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

class HuntRuntime(
    private val vision: HuntVision,
    private val visionConfig: VisionConfig,
    private val settingsRepository: SettingsRepository,
    private val diagnosticStore: DiagnosticStore,
    private val logger: OrbitLogger = NoOpOrbitLogger,
    private val captureReady: () -> Boolean = { true },
    private val clock: AutomationClock = SystemAutomationClock,
    private val runCoordinator: AutomationRunCoordinator? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val startMutex = Mutex()
    private val gatewayRef = AtomicReference<ScreenGateway?>()
    private val paused = MutableStateFlow(false)
    private val _status = MutableStateFlow(
        HuntStatus(templatesReady = vision.health().isReady),
    )
    val status: StateFlow<HuntStatus> = _status.asStateFlow()

    private var runJob: Job? = null
    private var phaseBeforePause = HuntPhase.IDLE

    fun attachGateway(gateway: ScreenGateway) {
        gatewayRef.set(gateway)
        _status.value = _status.value.copy(serviceReady = true)
    }

    fun detachGateway(gateway: ScreenGateway) {
        gatewayRef.compareAndSet(gateway, null)
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
            if (runJob?.isActive == true) return
            val normalized = config.normalized()
            val gateway = gatewayRef.get()
            val health = vision.health()
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

                !health.isReady -> {
                    rejectStart(
                        normalized,
                        HuntStopReason.TEMPLATES_MISSING,
                        "讨伐识图模板未就绪：${health.missingTemplateIds.joinToString()}",
                    )
                    return
                }
            }
            if (runCoordinator?.tryAcquire(AutomationKind.HUNT) == false) {
                rejectStart(
                    normalized,
                    HuntStopReason.INVALID_CONFIGURATION,
                    "其他自动化正在运行",
                )
                return
            }

            settingsRepository.saveHuntConfig(normalized)
            paused.value = false
            _status.value = HuntStatus(
                phase = HuntPhase.WAITING_FOR_LOBBY,
                config = normalized,
                stats = HuntStats(startedAtElapsedMs = clock.elapsedRealtime()),
                message = "等待游戏大厅",
                serviceReady = true,
                templatesReady = true,
            )
            runJob = scope.launch { runMachine(normalized, gateway) }
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
        stopWithReason(
            reason = HuntStopReason.USER_STOPPED,
            message = "已由用户停止",
            phase = HuntPhase.COMPLETED,
        )
    }

    fun restart() {
        val current = _status.value
        if (!current.isTerminal) return
        scope.launch { start(current.config) }
    }

    fun dismissTerminalStatus() {
        val current = _status.value
        if (!current.isTerminal) return
        _status.value = HuntStatus(
            config = current.config,
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
        runJob?.cancel()
        runCoordinator?.release(AutomationKind.HUNT)
        scope.cancel()
    }

    private suspend fun runMachine(
        config: HuntConfig,
        gateway: ScreenGateway,
    ) {
        val machine = HuntStateMachine(
            vision = vision,
            visionConfig = visionConfig,
            clock = clock,
            logger = logger,
        )
        try {
            val result = machine.run(
                config = config,
                gateway = gateway,
                awaitRunPermission = { paused.first { isPaused -> !isPaused } },
                onStatus = { phase, stats, message, confidence ->
                    _status.value = _status.value.copy(
                        phase = if (paused.value) HuntPhase.PAUSED else phase,
                        stats = stats,
                        message = message,
                        lastConfidence = confidence,
                    )
                },
                onDiagnostic = { frame, reason ->
                    diagnosticStore.save(frame, reason)
                },
            )
            _status.value = _status.value.copy(
                phase = if (result.successful) HuntPhase.COMPLETED else HuntPhase.ERROR,
                stats = result.stats,
                message = result.message,
                stopReason = result.reason,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            logger.error("hunt.runtime.failed", error, "phase" to _status.value.phase)
            _status.value = _status.value.copy(
                phase = HuntPhase.ERROR,
                stats = _status.value.stats.copy(
                    finishedAtElapsedMs = clock.elapsedRealtime(),
                ),
                message = "自动讨伐异常：${error.message.orEmpty()}",
                stopReason = HuntStopReason.INTERNAL_ERROR,
            )
        } finally {
            runJob = null
            runCoordinator?.release(AutomationKind.HUNT)
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
            templatesReady = vision.health().isReady,
        )
    }

    private fun stopWithReason(
        reason: HuntStopReason,
        message: String,
        phase: HuntPhase,
    ) {
        runJob?.cancel(CancellationException(message))
        runJob = null
        paused.value = false
        runCoordinator?.release(AutomationKind.HUNT)
        _status.value = _status.value.copy(
            phase = phase,
            stats = _status.value.stats.copy(
                finishedAtElapsedMs = clock.elapsedRealtime(),
            ),
            message = message,
            stopReason = reason,
        )
    }
}
