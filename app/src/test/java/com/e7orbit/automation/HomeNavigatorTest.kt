package com.e7orbit.automation

import com.e7orbit.model.GameLocation
import com.e7orbit.model.GestureResult
import com.e7orbit.model.GlobalAction
import com.e7orbit.model.MatchResult
import com.e7orbit.model.ScreenFrame
import com.e7orbit.model.ScreenPoint
import com.e7orbit.model.ScreenRect
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
            gateway = gateway,
            awaitRunPermission = {},
            onStatus = {},
            onDiagnostic = { _, _ -> },
        )

        assertEquals(
            listOf(
                GlobalAction.RETURN_TO_LOBBY,
                GlobalAction.OPEN_MENU,
                GlobalAction.RETURN_TO_LOBBY,
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
                gateway = FakeHomeGateway(
                    captureError = CancellationException("stopped"),
                ),
                awaitRunPermission = {},
                onStatus = {},
                onDiagnostic = { _, _ -> },
            )
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertEquals(true, cancelled)
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
        private val locations = ArrayDeque(locations)
        val actions = mutableListOf<GlobalAction>()
        private var returnHomeChecks = 0

        override fun navigationHealth(): VisionHealth = health

        override suspend fun detectLocation(frame: ScreenFrame): GameLocation =
            locations.pollFirst() ?: GameLocation.LOBBY

        override suspend fun findGlobalAction(
            frame: ScreenFrame,
            action: GlobalAction,
        ): MatchResult {
            actions += action
            val matched = when (action) {
                GlobalAction.RETURN_TO_LOBBY -> {
                    returnHomeChecks += 1
                    returnHomeChecks > 1
                }

                GlobalAction.OPEN_MENU -> true
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
    ) : ScreenGateway {
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
            return GestureResult.COMPLETED
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
