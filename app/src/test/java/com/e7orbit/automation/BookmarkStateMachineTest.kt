package com.e7orbit.automation

import com.e7orbit.model.GestureResult
import com.e7orbit.model.ItemType
import com.e7orbit.model.MatchResult
import com.e7orbit.model.PurchaseTarget
import com.e7orbit.model.RunConfig
import com.e7orbit.model.ScreenFrame
import com.e7orbit.model.ScreenPoint
import com.e7orbit.model.ScreenRect
import com.e7orbit.model.ShopPage
import com.e7orbit.model.StopReason
import com.e7orbit.model.VisualAction
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
    fun entersSecretShopFromLobbyBeforeRefreshing() = runTest {
        val vision = FakeVision(
            pages = listOf(
                ShopPage.LOBBY,
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
        val gateway = FakeGateway()

        val result = runMachine(
            vision = vision,
            config = RunConfig(maxRefreshes = 1),
            gateway = gateway,
        )

        assertTrue(result.successful)
        assertEquals(VisualAction.OPEN_SECRET_SHOP, vision.actions.first())
        assertEquals(3, gateway.taps)
        assertEquals(1, gateway.swipes)
    }

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
        val session = AutomationSession(
            gateway = FakeGateway(),
            uiStateSource = vision.uiStateSource,
            clock = FakeClock(),
            awaitRunPermission = {},
            onDiagnostic = { _, _ -> },
        )

        val result = machine.run(
            config = RunConfig(maxRefreshes = 1),
            session = session,
            onStatus = { _, _, _, _ -> },
        )
        val checkpoints = session.checkpointHistory()

        assertTrue(result.successful)
        assertEquals(StopReason.REFRESH_LIMIT_REACHED, result.reason)
        assertEquals(1, result.stats.completedRefreshes)
        assertEquals(1, result.stats.shopPagesScanned)
        assertEquals(0L, result.stats.goldSpent)
        assertTrue(checkpoints.any { it.workflowId == "bookmark_entry" })
        assertTrue(
            checkpoints.any {
                it.workflowId == "bookmark_refresh" &&
                    it.stepId == "refresh.confirm" &&
                    it.runKey == "refresh-1" &&
                    it.state == WorkflowCheckpointState.SUCCEEDED
            },
        )
    }

    @Test
    fun retriesGestureCancelledByUserInput() = runTest {
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
        val gateway = FakeGateway(
            swipeResults = listOf(GestureResult.CANCELLED, GestureResult.COMPLETED),
        )

        val result = runMachine(
            vision = vision,
            config = RunConfig(maxRefreshes = 1),
            gateway = gateway,
        )

        assertTrue(result.successful)
        assertEquals(2, gateway.swipes)
        assertEquals(1, result.stats.completedRefreshes)
    }

    @Test
    fun doesNotRetryCancelledRefreshConfirmation() = runTest {
        val vision = FakeVision(
            pages = listOf(
                ShopPage.SHOP,
                ShopPage.SHOP,
                ShopPage.SHOP,
                ShopPage.SHOP,
                ShopPage.REFRESH_CONFIRMATION,
            ),
            targets = listOf(emptyList(), emptyList()),
        )
        val gateway = FakeGateway(
            tapResults = listOf(
                GestureResult.COMPLETED,
                GestureResult.CANCELLED,
                GestureResult.COMPLETED,
            ),
        )

        val result = runMachine(
            vision = vision,
            config = RunConfig(maxRefreshes = 1),
            gateway = gateway,
        )

        assertFalse(result.successful)
        assertEquals(StopReason.UNCERTAIN_EFFECT, result.reason)
        assertEquals(2, gateway.taps)
        assertEquals(0, result.stats.completedRefreshes)
    }

    @Test
    fun treatsUnconfirmedCompletedRefreshAsUncertainEffect() = runTest {
        val vision = FakeVision(
            pages = listOf(
                ShopPage.SHOP,
                ShopPage.SHOP,
                ShopPage.SHOP,
                ShopPage.SHOP,
                ShopPage.REFRESH_CONFIRMATION,
            ),
            targets = listOf(emptyList(), emptyList()),
            fallbackPage = ShopPage.REFRESH_CONFIRMATION,
        )
        val gateway = FakeGateway()

        val result = runMachine(
            vision = vision,
            config = RunConfig(maxRefreshes = 1),
            gateway = gateway,
        )

        assertFalse(result.successful)
        assertEquals(StopReason.UNCERTAIN_EFFECT, result.reason)
        assertTrue(result.message.contains("天空石可能已消耗"))
        assertEquals(2, gateway.taps)
    }

    @Test
    fun verifiesAndCountsPurchaseBeforeRefreshing() = runTest {
        val covenant = PurchaseTarget(
            type = ItemType.COVENANT_BOOKMARK,
            itemBounds = ScreenRect(500, 200, 620, 300),
            purchaseButtonBounds = ScreenRect(1600, 200, 1800, 300),
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

        val result = runMachine(
            vision = vision,
            config = RunConfig(maxRefreshes = 1),
            gateway = gateway,
        )

        assertTrue(result.successful)
        assertEquals(1, result.stats.covenantBookmarksBought)
        assertEquals(5, result.stats.covenantBookmarksGained)
        assertEquals(1, result.stats.shopPagesScanned)
        assertEquals(184_000L, result.stats.goldSpent)
        assertEquals(100.0, result.stats.covenantRatePercent, 0.001)
        assertTrue(gateway.taps >= 3)
        assertEquals(1, gateway.swipes)
    }

    @Test
    fun doesNotRetryCancelledPurchaseConfirmation() = runTest {
        val covenant = PurchaseTarget(
            type = ItemType.COVENANT_BOOKMARK,
            itemBounds = ScreenRect(500, 200, 620, 300),
            purchaseButtonBounds = ScreenRect(1600, 200, 1800, 300),
            confidence = 0.98,
            rowIndex = 2,
        )
        val vision = FakeVision(
            pages = listOf(
                ShopPage.SHOP,
                ShopPage.SHOP,
                ShopPage.SHOP,
                ShopPage.PURCHASE_CONFIRMATION,
            ),
            targets = listOf(listOf(covenant), listOf(covenant)),
        )
        val gateway = FakeGateway(
            tapResults = listOf(
                GestureResult.COMPLETED,
                GestureResult.CANCELLED,
                GestureResult.COMPLETED,
            ),
        )

        val result = runMachine(
            vision = vision,
            config = RunConfig(maxRefreshes = 1),
            gateway = gateway,
        )

        assertFalse(result.successful)
        assertEquals(StopReason.UNCERTAIN_EFFECT, result.reason)
        assertEquals(2, gateway.taps)
        assertEquals(0, result.stats.covenantBookmarksBought)
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

        val result = runMachine(
            vision = vision,
            config = RunConfig(maxRefreshes = 10),
            gateway = FakeGateway(),
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

        val result = runMachine(
            vision = vision,
            config = RunConfig(maxRefreshes = 10),
            gateway = gateway,
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
            runMachine(
                vision = vision,
                config = RunConfig(maxRefreshes = 10),
                gateway = gateway,
                awaitRunPermission = {
                    gateCalls += 1
                    if (gateCalls >= 3) throw CancellationException("paused job cancelled")
                },
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

    private suspend fun runMachine(
        vision: FakeVision,
        config: RunConfig,
        gateway: ScreenGateway,
        awaitRunPermission: suspend () -> Unit = {},
    ): MachineResult = machine(vision).run(
        config = config,
        session = testSession(
            gateway = gateway,
            uiStateSource = vision.uiStateSource,
            clock = FakeClock(),
            awaitRunPermission = awaitRunPermission,
        ),
        onStatus = { _, _, _, _ -> },
    )
}

private class FakeGateway(
    tapResults: List<GestureResult> = emptyList(),
    swipeResults: List<GestureResult> = emptyList(),
) : ScreenGateway {
    private val tapResults = ArrayDeque(tapResults)
    private val swipeResults = ArrayDeque(swipeResults)
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
        return tapResults.pollFirst() ?: GestureResult.COMPLETED
    }

    override suspend fun swipe(
        from: ScreenPoint,
        to: ScreenPoint,
        durationMs: Long,
    ): GestureResult {
        swipes += 1
        return swipeResults.pollFirst() ?: GestureResult.COMPLETED
    }
}

private class FakeVision(
    pages: List<ShopPage>,
    targets: List<List<PurchaseTarget>>,
    private val fallbackPage: ShopPage = ShopPage.SHOP,
) : ShopVision {
    val uiStateSource = TestGameUiStateSource(
        pages = pages.map(ShopPage::toGameUiPage),
        fallbackPage = fallbackPage.toGameUiPage(),
    )
    private val targets = ArrayDeque(targets)
    val actions = mutableListOf<VisualAction>()

    override fun health(): VisionHealth = VisionHealth(
        openCvReady = true,
        loadedTemplates = 1,
        requiredTemplates = 1,
        missingTemplateIds = emptyList(),
    )

    override suspend fun detectPage(frame: ScreenFrame): ShopPage =
        fallbackPage

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
        action: VisualAction,
    ): MatchResult {
        actions += action
        return MatchResult(
            matched = true,
            confidence = 0.99,
            bounds = ScreenRect(1000, 700, 1200, 800),
        )
    }
}

private class FakeClock : AutomationClock {
    private var now = 1L

    override fun elapsedRealtime(): Long = now

    override suspend fun delay(durationMs: Long) {
        now += durationMs.coerceAtLeast(1L)
    }
}
