package com.e7orbit.automation

import java.util.ArrayDeque
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
internal class TestGameUiStateSource(
    pages: List<GameUiPage> = emptyList(),
    private val fallbackPage: GameUiPage = GameUiPage.GAME_PAGE,
) : GameUiStateSource {
    private val pages = ArrayDeque(pages)
    private var sequence = 0L
    private val updates = MutableStateFlow(snapshot(fallbackPage))

    override val state: StateFlow<GameUiSnapshot> = object : StateFlow<GameUiSnapshot> {
        override val replayCache: List<GameUiSnapshot>
            get() = listOf(value)

        override val value: GameUiSnapshot
            get() = nextSnapshot()

        override suspend fun collect(collector: FlowCollector<GameUiSnapshot>): Nothing =
            updates.collect(collector)
    }

    override suspend fun awaitAllowed(
        contract: TaskUiContract,
        timeoutMs: Long,
    ): GameUiSnapshot {
        while (pages.isNotEmpty()) {
            val snapshot = nextSnapshot()
            if (contract.accepts(snapshot)) return snapshot
        }
        val snapshot = snapshot(fallbackPage)
        if (contract.accepts(snapshot)) return snapshot
        throw UiStateMismatchException(contract, snapshot)
    }

    private fun nextSnapshot(): GameUiSnapshot = snapshot(
        pages.pollFirst() ?: fallbackPage,
    ).also { updates.value = it }

    private fun snapshot(page: GameUiPage): GameUiSnapshot = GameUiSnapshot(
        page = page,
        candidatePage = page,
        confidence = if (page == GameUiPage.UNKNOWN) 0.0 else 1.0,
        stableFrames = GameUiSnapshot.REQUIRED_STABLE_FRAMES,
        frameSequence = ++sequence,
        observedAtElapsedMs = sequence,
        status = UiMonitorStatus.OBSERVING,
    )
}

internal fun testSession(
    gateway: ScreenGateway,
    uiStateSource: GameUiStateSource = TestGameUiStateSource(),
    clock: AutomationClock,
    awaitRunPermission: suspend () -> Unit = {},
): AutomationSession = AutomationSession(
    gateway = gateway,
    uiStateSource = uiStateSource,
    clock = clock,
    awaitRunPermission = awaitRunPermission,
    onDiagnostic = { _, _ -> },
)
