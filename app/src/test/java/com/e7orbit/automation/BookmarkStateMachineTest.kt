package com.e7orbit.automation

import com.e7orbit.model.GestureResult
import com.e7orbit.model.ItemType
import com.e7orbit.model.MatchResult
import com.e7orbit.model.PurchaseTarget
import com.e7orbit.model.RunConfig
import com.e7orbit.model.ScreenFrame
import com.e7orbit.model.ScreenPoint
import com.e7orbit.model.ScreenRect
import com.e7orbit.model.ShopAction
import com.e7orbit.model.ShopPage
import com.e7orbit.model.StopReason
import com.e7orbit.vision.PointConfig
import com.e7orbit.vision.VisionConfig
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkStateMachineTest {
    @Test
    fun completesOneRefreshWhenNoTargetExists() = runTest {
        val vision = FakeVision(
            pages = listOf(
                ShopPage.SHOP,
                ShopPage.SHOP,
                ShopPage.SHOP,
                ShopPage.SHOP,
                ShopPage.REFRESH_CONFIRMATION,
                ShopPage.SHOP,
                ShopPage.SHOP,
            ),
            targets = listOf(emptyList(), emptyList()),
        )
        val machine = machine(vision)

        val result = machine.run(
            config = RunConfig(maxRefreshes = 1),
            gateway = FakeGateway(),
            awaitRunPermission = {},
            onStatus = { _, _, _, _ -> },
            onDiagnostic = { _, _ -> },
        )

        assertTrue(result.successful)
        assertEquals(StopReason.REFRESH_LIMIT_REACHED, result.reason)
        assertEquals(1, result.stats.completedRefreshes)
        assertEquals(1, result.stats.shopPagesScanned)
        assertEquals(0L, result.stats.goldSpent)
    }

    @Test
    fun verifiesAndCountsPurchaseBeforeRefreshing() = runTest {
        val covenant = PurchaseTarget(
            type = ItemType.COVENANT_BOOKMARK,
            itemBounds = ScreenRect(500, 200, 620, 300),
            purchaseButton = ScreenPoint(1700, 250),
            confidence = 0.98,
            rowIndex = 2,
        )
        val vision = FakeVision(
            pages = listOf(
                ShopPage.SHOP,
                ShopPage.SHOP,
                ShopPage.SHOP,
                ShopPage.PURCHASE_CONFIRMATION,
                ShopPage.SHOP,
                ShopPage.SHOP,
                ShopPage.REFRESH_CONFIRMATION,
                ShopPage.SHOP,
                ShopPage.SHOP,
            ),
            targets = listOf(
                listOf(covenant),
                listOf(covenant),
                emptyList(),
            ),
        )
        val gateway = FakeGateway()

        val result = machine(vision).run(
            config = RunConfig(maxRefreshes = 1),
            gateway = gateway,
            awaitRunPermission = {},
            onStatus = { _, _, _, _ -> },
            onDiagnostic = { _, _ -> },
        )

        assertTrue(result.successful)
        assertEquals(1, result.stats.covenantBookmarksBought)
        assertEquals(1, result.stats.shopPagesScanned)
        assertEquals(184_000L, result.stats.goldSpent)
        assertEquals(100.0, result.stats.covenantRatePercent, 0.001)
        assertTrue(gateway.taps >= 3)
        assertEquals(1, gateway.swipes)
    }

    @Test
    fun stopsSafelyWhenResourcesAreInsufficient() = runTest {
        val vision = FakeVision(
            pages = listOf(
                ShopPage.SHOP,
                ShopPage.SHOP,
                ShopPage.RESOURCE_INSUFFICIENT,
            ),
            targets = emptyList(),
        )

        val result = machine(vision).run(
            config = RunConfig(maxRefreshes = 10),
            gateway = FakeGateway(),
            awaitRunPermission = {},
            onStatus = { _, _, _, _ -> },
            onDiagnostic = { _, _ -> },
        )

        assertFalse(result.successful)
        assertEquals(StopReason.RESOURCE_INSUFFICIENT, result.reason)
        assertEquals(0, result.stats.completedRefreshes)
    }

    @Test
    fun stopsOnUnknownPageBeforeAnyGesture() = runTest {
        val gateway = FakeGateway()
        val vision = FakeVision(
            pages = listOf(
                ShopPage.SHOP,
                ShopPage.SHOP,
                ShopPage.UNKNOWN,
            ),
            targets = emptyList(),
        )

        val result = machine(vision).run(
            config = RunConfig(maxRefreshes = 10),
            gateway = gateway,
            awaitRunPermission = {},
            onStatus = { _, _, _, _ -> },
            onDiagnostic = { _, _ -> },
        )

        assertFalse(result.successful)
        assertEquals(StopReason.UNKNOWN_PAGE, result.reason)
        assertEquals(0, gateway.taps)
        assertEquals(0, gateway.swipes)
    }

    @Test
    fun cancellationAtRunGatePreventsFollowingGestures() = runTest {
        val gateway = FakeGateway()
        val vision = FakeVision(
            pages = listOf(ShopPage.SHOP, ShopPage.SHOP),
            targets = emptyList(),
        )
        var gateCalls = 0

        var cancelled = false
        try {
            machine(vision).run(
                config = RunConfig(maxRefreshes = 10),
                gateway = gateway,
                awaitRunPermission = {
                    gateCalls += 1
                    if (gateCalls >= 3) throw CancellationException("paused job cancelled")
                },
                onStatus = { _, _, _, _ -> },
                onDiagnostic = { _, _ -> },
            )
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertEquals(0, gateway.taps)
        assertEquals(0, gateway.swipes)
    }

    private fun machine(vision: ShopVision) = BookmarkStateMachine(
        vision = vision,
        visionConfig = VisionConfig(
            referenceWidth = 1920,
            referenceHeight = 1080,
            purchaseButtonX = 1700,
            scrollFrom = PointConfig(1350, 820),
            scrollTo = PointConfig(1350, 300),
            templates = emptyList(),
        ),
        clock = FakeClock(),
    )
}

private class FakeGateway : ScreenGateway {
    var captures = 0
    var taps = 0
    var swipes = 0

    override suspend fun capture(): ScreenFrame = ScreenFrame(
        bitmap = null,
        width = 1920,
        height = 1080,
        capturedAtElapsedMs = captures.toLong(),
        sequence = (++captures).toLong(),
    )

    override suspend fun tap(point: ScreenPoint): GestureResult {
        taps += 1
        return GestureResult.COMPLETED
    }

    override suspend fun swipe(
        from: ScreenPoint,
        to: ScreenPoint,
        durationMs: Long,
    ): GestureResult {
        swipes += 1
        return GestureResult.COMPLETED
    }
}

private class FakeVision(
    pages: List<ShopPage>,
    targets: List<List<PurchaseTarget>>,
) : ShopVision {
    private val pages = ArrayDeque(pages)
    private val targets = ArrayDeque(targets)

    override fun health(): VisionHealth = VisionHealth(
        openCvReady = true,
        loadedTemplates = 1,
        requiredTemplates = 1,
        missingTemplateIds = emptyList(),
    )

    override suspend fun detectPage(frame: ScreenFrame): ShopPage =
        pages.pollFirst() ?: ShopPage.SHOP

    override suspend fun findTargets(
        frame: ScreenFrame,
        config: RunConfig,
    ): List<PurchaseTarget> = targets.pollFirst().orEmpty()

    override suspend fun verifyPurchase(
        frame: ScreenFrame,
        target: PurchaseTarget,
    ): MatchResult = MatchResult(
        matched = true,
        confidence = 0.99,
        bounds = ScreenRect(1000, 700, 1200, 800),
    )

    override suspend fun findAction(
        frame: ScreenFrame,
        action: ShopAction,
    ): MatchResult = MatchResult(
        matched = true,
        confidence = 0.99,
        bounds = ScreenRect(1000, 700, 1200, 800),
    )
}

private class FakeClock : AutomationClock {
    private var now = 1L

    override fun elapsedRealtime(): Long = now

    override suspend fun delay(durationMs: Long) {
        now += durationMs.coerceAtLeast(1L)
    }
}
