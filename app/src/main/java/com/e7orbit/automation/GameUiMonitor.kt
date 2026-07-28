package com.e7orbit.automation

import com.e7orbit.model.GameLocation
import com.e7orbit.model.HuntPage
import com.e7orbit.model.ScreenFrame
import com.e7orbit.model.ShopPage
import com.e7orbit.model.VisualAction
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
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
import kotlinx.coroutines.withTimeoutOrNull

interface GameUiRecognizer {
    fun health(): VisionHealth
    suspend fun recognize(frame: ScreenFrame): UiRecognition
}

class CompositeGameUiRecognizer(
    private val shopVision: ShopVision,
    private val huntVision: HuntVision,
    private val globalVision: GlobalUiVision,
) : GameUiRecognizer {
    override fun health(): VisionHealth {
        val health = listOf(
            shopVision.health(),
            huntVision.health(),
            globalVision.navigationHealth(),
        )
        val missing = health.flatMap(VisionHealth::missingTemplateIds).distinct()
        return VisionHealth(
            openCvReady = health.all(VisionHealth::openCvReady),
            loadedTemplates = health.sumOf(VisionHealth::loadedTemplates),
            requiredTemplates = health.sumOf(VisionHealth::requiredTemplates),
            missingTemplateIds = missing,
        )
    }

    override suspend fun recognize(frame: ScreenFrame): UiRecognition {
        val shopPage = shopVision.detectPage(frame)
        if (shopPage !in setOf(ShopPage.UNKNOWN, ShopPage.LOBBY)) {
            return UiRecognition(shopPage.toGameUiPage(), MATCHED_CONFIDENCE)
        }

        val huntPage = huntVision.detectPage(frame)
        if (huntPage !in setOf(HuntPage.UNKNOWN, HuntPage.LOBBY)) {
            return UiRecognition(huntPage.toGameUiPage(), MATCHED_CONFIDENCE)
        }
        if (shopPage == ShopPage.LOBBY || huntPage == HuntPage.LOBBY) {
            return UiRecognition(GameUiPage.LOBBY, MATCHED_CONFIDENCE)
        }
        if (globalVision.detectLocation(frame) == GameLocation.LOBBY) {
            return UiRecognition(GameUiPage.LOBBY, MATCHED_CONFIDENCE)
        }

        val navigable = globalVision.findAction(frame, VisualAction.RETURN_TO_LOBBY).matched ||
            globalVision.findAction(frame, VisualAction.OPEN_MENU).matched
        return UiRecognition(
            page = if (navigable) GameUiPage.GAME_PAGE else GameUiPage.UNKNOWN,
            confidence = if (navigable) MATCHED_CONFIDENCE else 0.0,
        )
    }

    private companion object {
        const val MATCHED_CONFIDENCE = 1.0
    }
}

class GameUiMonitor(
    private val recognizer: GameUiRecognizer,
    private val clock: AutomationClock = SystemAutomationClock,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val activePollIntervalMs: Long = 350L,
    private val idlePollIntervalMs: Long = 1_000L,
) : GameUiStateSource {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val gateway = AtomicReference<ScreenGateway?>()
    private val stabilizer = UiStateStabilizer()
    private val _state = MutableStateFlow(GameUiSnapshot())
    private var monitorJob: Job? = null

    override val state: StateFlow<GameUiSnapshot> = _state.asStateFlow()

    init {
        require(activePollIntervalMs > 0L) { "activePollIntervalMs 必须大于 0" }
        require(idlePollIntervalMs > 0L) { "idlePollIntervalMs 必须大于 0" }
    }

    fun health(): VisionHealth = recognizer.health()

    fun attachGateway(screenGateway: ScreenGateway) {
        gateway.set(screenGateway)
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch { monitorLoop() }
    }

    fun detachGateway(screenGateway: ScreenGateway) {
        if (!gateway.compareAndSet(screenGateway, null)) return
        stabilizer.reset()
        _state.value = GameUiSnapshot(status = UiMonitorStatus.DETACHED)
    }

    override suspend fun awaitAllowed(
        contract: TaskUiContract,
        timeoutMs: Long,
    ): GameUiSnapshot {
        require(timeoutMs > 0L) { "timeoutMs 必须大于 0" }
        state.value.takeIf(contract::accepts)?.let { return it }
        return withTimeoutOrNull(timeoutMs) {
            state.first(contract::accepts)
        } ?: throw UiStateMismatchException(contract, state.value)
    }

    fun shutdown() {
        monitorJob?.cancel()
        scope.cancel()
        gateway.set(null)
        stabilizer.reset()
        _state.value = GameUiSnapshot(status = UiMonitorStatus.DETACHED)
    }

    private suspend fun monitorLoop() {
        while (true) {
            val currentGateway = gateway.get()
            if (currentGateway == null) {
                stabilizer.reset()
                _state.value = GameUiSnapshot(status = UiMonitorStatus.DETACHED)
                clock.delay(idlePollIntervalMs)
                continue
            }
            if (!currentGateway.isTargetAppForeground()) {
                stabilizer.reset()
                _state.value = GameUiSnapshot(status = UiMonitorStatus.WAITING_FOR_GAME)
                clock.delay(idlePollIntervalMs)
                continue
            }

            try {
                currentGateway.capture().use { frame ->
                    val recognition = recognizer.recognize(frame)
                    if (gateway.get() !== currentGateway) continue
                    _state.value = stabilizer.reduce(
                        recognition = recognition,
                        frameSequence = frame.sequence,
                        observedAtElapsedMs = clock.elapsedRealtime(),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                stabilizer.reset()
                _state.value = GameUiSnapshot(
                    observedAtElapsedMs = clock.elapsedRealtime(),
                    status = UiMonitorStatus.CAPTURE_UNAVAILABLE,
                )
            }
            clock.delay(activePollIntervalMs)
        }
    }
}

class ActionGuard(
    private val uiStateSource: GameUiStateSource,
) {
    suspend fun requireAllowed(
        contract: TaskUiContract,
        operationId: String,
    ): GameUiSnapshot = try {
        val current = uiStateSource.state.value
        if (current.isStable && !contract.accepts(current)) {
            throw UiStateMismatchException(contract, current)
        }
        current.takeIf(contract::accepts) ?: uiStateSource.awaitAllowed(contract)
    } catch (error: UiStateMismatchException) {
        throw OperationExecutionException(
            ExecutionFailure(
                kind = ExecutionFailureKind.UI_STATE_MISMATCH,
                operationId = operationId,
                message = error.message ?: "当前 UI 状态不允许执行 $operationId",
                cause = error,
            ),
        )
    }
}
