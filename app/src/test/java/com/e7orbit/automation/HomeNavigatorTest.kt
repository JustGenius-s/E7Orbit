package com.e7orbit.automation

import com.e7orbit.model.GameLocation
import com.e7orbit.model.GestureResult
import com.e7orbit.model.MatchResult
import com.e7orbit.model.ScreenFrame
import com.e7orbit.model.ScreenPoint
import com.e7orbit.model.ScreenRect
import com.e7orbit.model.VisualAction
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeNavigatorTest {
    @Test
    fun opensMenuAndReturnsHomeFromUnknownPage() = runTest {
        val vision = FakeGlobalUiVision(
            locations = listOf(
                GameLocation.UNKNOWN,
                GameLocation.LOBBY,
                GameLocation.LOBBY,
            ),
        )
        val gateway = FakeHomeGateway()
        val navigator = HomeNavigator(
            vision = vision,
            clock = FakeHomeClock(),
        )

        navigator.ensureHome(
            session = testSession(
                gateway = gateway,
                uiStateSource = vision.uiStateSource,
                clock = FakeHomeClock(),
            ),
            onStatus = {},
        )

        assertEquals(
            listOf(
                VisualAction.RETURN_TO_LOBBY,
                VisualAction.OPEN_MENU,
                VisualAction.RETURN_TO_LOBBY,
            ),
            vision.actions,
        )
        assertEquals(2, gateway.taps)
    }

    @Test
    fun exposesOnlyGlobalNavigationHealth() {
        val health = VisionHealth(
            openCvReady = true,
            loadedTemplates = 20,
            requiredTemplates = 4,
            missingTemplateIds = listOf("global_lobby_anchor"),
        )
        val navigator = HomeNavigator(
            vision = FakeGlobalUiVision(
                locations = emptyList(),
                health = health,
            ),
        )

        assertEquals(health, navigator.health())
    }

    @Test
    fun propagatesCaptureCancellation() = runTest {
        val navigator = HomeNavigator(
            vision = FakeGlobalUiVision(locations = emptyList()),
            clock = FakeHomeClock(),
        )
        var cancelled = false

        try {
            navigator.ensureHome(
                session = testSession(
                    gateway = FakeHomeGateway(
                        captureError = CancellationException("stopped"),
                    ),
                    uiStateSource = TestGameUiStateSource(),
                    clock = FakeHomeClock(),
                ),
                onStatus = {},
            )
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertEquals(true, cancelled)
    }

    @Test
    fun workflowReconcilesCancelledMenuGestureByReobservingUi() = runTest {
        val vision = FakeGlobalUiVision(
            locations = listOf(
                GameLocation.UNKNOWN,
                GameLocation.LOBBY,
                GameLocation.LOBBY,
            ),
        )
        val gateway = FakeHomeGateway(
            tapResults = listOf(
                GestureResult.CANCELLED,
                GestureResult.COMPLETED,
            ),
        )
        val clock = FakeHomeClock()
        val session = AutomationSession(
            gateway = gateway,
            uiStateSource = vision.uiStateSource,
            clock = clock,
            awaitRunPermission = {},
            onDiagnostic = { _, _ -> },
        )

        HomeNavigator(vision = vision, clock = clock).ensureHome(
            session = session,
            onStatus = {},
        )

        val checkpoints = session.checkpointHistory()
        assertEquals(2, gateway.taps)
        assertTrue(
            checkpoints.any {
                it.workflowId == "home_navigation" &&
                    it.stepId == "menu.open_if_needed" &&
                    it.state == WorkflowCheckpointState.RECOVERED
            },
        )
        assertEquals(GestureOutcome.COMPLETED, session.latestGestureReceipt()?.outcome)
    }

    private class FakeGlobalUiVision(
        locations: List<GameLocation>,
        private val health: VisionHealth = VisionHealth(
            openCvReady = true,
            loadedTemplates = 4,
            requiredTemplates = 4,
            missingTemplateIds = emptyList(),
        ),
    ) : GlobalUiVision {
        val uiStateSource = TestGameUiStateSource(
            pages = locations.map { location ->
                if (location == GameLocation.LOBBY) GameUiPage.LOBBY else GameUiPage.GAME_PAGE
            },
            fallbackPage = GameUiPage.LOBBY,
        )
        val actions = mutableListOf<VisualAction>()
        private var returnHomeChecks = 0

        override fun navigationHealth(): VisionHealth = health

        override suspend fun detectLocation(frame: ScreenFrame): GameLocation =
            GameLocation.LOBBY

        override suspend fun findAction(
            frame: ScreenFrame,
            action: VisualAction,
        ): MatchResult {
            actions += action
            val matched = when (action) {
                VisualAction.RETURN_TO_LOBBY -> {
                    returnHomeChecks += 1
                    returnHomeChecks > 1
                }

                VisualAction.OPEN_MENU -> true
                else -> false
            }
            return MatchResult(
                matched = matched,
                confidence = if (matched) 1.0 else 0.0,
                bounds = if (matched) ScreenRect(100, 100, 200, 200) else null,
            )
        }
    }

    private class FakeHomeGateway(
        private val captureError: Throwable? = null,
        tapResults: List<GestureResult> = emptyList(),
    ) : ScreenGateway {
        private val tapResults = ArrayDeque(tapResults)
        var taps = 0

        override suspend fun capture(): ScreenFrame {
            captureError?.let { throw it }
            return ScreenFrame(
                bitmap = null,
                width = 1920,
                height = 1080,
                capturedAtElapsedMs = 0L,
                sequence = 0L,
            )
        }

        override suspend fun tap(point: ScreenPoint): GestureResult {
            taps += 1
            return tapResults.pollFirst() ?: GestureResult.COMPLETED
        }

        override suspend fun swipe(
            from: ScreenPoint,
            to: ScreenPoint,
            durationMs: Long,
        ): GestureResult = GestureResult.COMPLETED
    }

    private class FakeHomeClock : AutomationClock {
        private var now = 0L

        override fun elapsedRealtime(): Long = now

        override suspend fun delay(durationMs: Long) {
            now += durationMs.coerceAtLeast(1L)
        }
    }
}
