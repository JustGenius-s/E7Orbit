package com.e7orbit.automation

import com.e7orbit.model.GestureResult
import com.e7orbit.model.MatchResult
import com.e7orbit.model.PurchaseTarget
import com.e7orbit.model.RunConfig
import com.e7orbit.model.ScreenFrame
import com.e7orbit.model.ScreenPoint
import com.e7orbit.model.ScreenRect
import com.e7orbit.model.ShopAction
import com.e7orbit.model.ShopPage
import java.util.ArrayDeque
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeNavigatorTest {
    @Test
    fun opensMenuAndReturnsHomeFromUnknownPage() = runTest {
        val vision = FakeHomeVision(
            pages = listOf(
                ShopPage.UNKNOWN,
                ShopPage.LOBBY,
                ShopPage.LOBBY,
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
                ShopAction.RETURN_HOME,
                ShopAction.OPEN_MAIN_MENU,
                ShopAction.RETURN_HOME,
            ),
            vision.actions,
        )
        assertEquals(2, gateway.taps)
    }

    private class FakeHomeVision(
        pages: List<ShopPage>,
    ) : ShopVision {
        private val pages = ArrayDeque(pages)
        val actions = mutableListOf<ShopAction>()
        private var returnHomeChecks = 0

        override fun health(): VisionHealth = VisionHealth(
            openCvReady = true,
            loadedTemplates = 2,
            requiredTemplates = 2,
            missingTemplateIds = emptyList(),
        )

        override suspend fun detectPage(frame: ScreenFrame): ShopPage =
            pages.pollFirst() ?: ShopPage.LOBBY

        override suspend fun findTargets(
            frame: ScreenFrame,
            config: RunConfig,
        ): List<PurchaseTarget> = emptyList()

        override suspend fun verifyPurchase(
            frame: ScreenFrame,
            target: PurchaseTarget,
        ): MatchResult = MatchResult(matched = false)

        override suspend fun findAction(
            frame: ScreenFrame,
            action: ShopAction,
        ): MatchResult {
            actions += action
            val matched = when (action) {
                ShopAction.RETURN_HOME -> {
                    returnHomeChecks += 1
                    returnHomeChecks > 1
                }

                ShopAction.OPEN_MAIN_MENU -> true
                else -> false
            }
            return MatchResult(
                matched = matched,
                confidence = if (matched) 1.0 else 0.0,
                bounds = if (matched) ScreenRect(100, 100, 200, 200) else null,
            )
        }
    }

    private class FakeHomeGateway : ScreenGateway {
        var taps = 0

        override suspend fun capture(): ScreenFrame = ScreenFrame(
            bitmap = null,
            width = 1920,
            height = 1080,
            capturedAtElapsedMs = 0L,
            sequence = 0L,
        )

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
