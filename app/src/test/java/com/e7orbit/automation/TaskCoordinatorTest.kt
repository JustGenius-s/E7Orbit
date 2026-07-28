package com.e7orbit.automation

import com.e7orbit.model.AutomationPhase
import com.e7orbit.model.GestureResult
import com.e7orbit.model.HuntConfig
import com.e7orbit.model.HuntDungeon
import com.e7orbit.model.HuntPage
import com.e7orbit.model.HuntPhase
import com.e7orbit.model.HuntStopReason
import com.e7orbit.model.ItemType
import com.e7orbit.model.MatchResult
import com.e7orbit.model.PurchaseTarget
import com.e7orbit.model.RunConfig
import com.e7orbit.model.RunSummary
import com.e7orbit.model.ScreenFrame
import com.e7orbit.model.ScreenPoint
import com.e7orbit.model.ScreenRect
import com.e7orbit.model.ShopPage
import com.e7orbit.model.StopReason
import com.e7orbit.model.VisualAction
import com.e7orbit.vision.PointConfig
import com.e7orbit.vision.VisionConfig
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskCoordinatorTest {
    @Test
    fun onlyOneChildTaskCanOwnTheExecutionSlot() = runTest {
        val coordinator = coordinator()
        coordinator.attachGateway(TestGateway())

        coordinator.startShop(RunConfig(maxRefreshes = 3))
        coordinator.startHunt(HuntConfig(runCount = 2))

        assertEquals(TaskKind.SHOP, coordinator.activeTask.value)
        assertEquals(AutomationPhase.WAITING_FOR_SHOP, coordinator.shopStatus.value.phase)
        assertEquals(HuntPhase.ERROR, coordinator.huntStatus.value.phase)
        assertEquals(
            HuntStopReason.INVALID_CONFIGURATION,
            coordinator.huntStatus.value.stopReason,
        )
        coordinator.stop(TaskKind.SHOP)
        coordinator.shutdown()
    }

    @Test
    fun repeatedStartDoesNotCorruptTheActiveTaskStatus() = runTest {
        val coordinator = coordinator()
        coordinator.attachGateway(TestGateway())

        coordinator.startShop(RunConfig(maxRefreshes = 3))
        coordinator.startShop(RunConfig(maxRefreshes = 9))

        assertEquals(TaskKind.SHOP, coordinator.activeTask.value)
        assertEquals(AutomationPhase.WAITING_FOR_SHOP, coordinator.shopStatus.value.phase)
        assertEquals(3, coordinator.shopStatus.value.config.maxRefreshes)
        coordinator.stop(TaskKind.SHOP)
        coordinator.shutdown()
    }

    @Test
    fun staleGatewayDetachDoesNotStopTheCurrentTask() = runTest {
        val coordinator = coordinator()
        val stale = TestGateway()
        val current = TestGateway()
        coordinator.attachGateway(stale)
        coordinator.attachGateway(current)
        coordinator.startShop(RunConfig())

        coordinator.detachGateway(stale)

        assertTrue(coordinator.shopStatus.value.serviceReady)
        assertEquals(TaskKind.SHOP, coordinator.activeTask.value)
        coordinator.stop(TaskKind.SHOP)
        coordinator.shutdown()
    }

    @Test
    fun stoppedTaskCannotPublishAfterCancellation() = runTest {
        val coordinator = coordinator()
        coordinator.attachGateway(TestGateway())
        coordinator.startShop(RunConfig())

        coordinator.stop(TaskKind.SHOP)
        yield()

        assertNull(coordinator.activeTask.value)
        assertEquals(AutomationPhase.COMPLETED, coordinator.shopStatus.value.phase)
        assertEquals(StopReason.USER_STOPPED, coordinator.shopStatus.value.stopReason)
        coordinator.shutdown()
    }

    @Test
    fun configurationFailureReleasesTheExecutionSlot() = runTest {
        val coordinator = coordinator(
            shopPersistence = object : ShopTaskPersistence {
                override suspend fun saveConfig(config: RunConfig) {
                    throw IOException("disk full")
                }

                override suspend fun saveSummary(summary: RunSummary) = Unit
            },
        )
        coordinator.attachGateway(TestGateway())

        coordinator.startShop(RunConfig())

        assertNull(coordinator.activeTask.value)
        assertEquals(AutomationPhase.ERROR, coordinator.shopStatus.value.phase)
        assertEquals(StopReason.INTERNAL_ERROR, coordinator.shopStatus.value.stopReason)
        assertTrue(coordinator.shopStatus.value.message.contains("disk full"))
        coordinator.shutdown()
    }

    @Test
    fun restartWaitsForCancelledTaskCleanup() = runTest {
        val coordinator = coordinator()
        val gateway = SlowAwaitGateway()
        coordinator.attachGateway(gateway)
        coordinator.startShop(RunConfig())

        coordinator.stop(TaskKind.SHOP)
        coordinator.restart(TaskKind.SHOP)

        assertEquals(1, gateway.awaitCalls)
        assertEquals(TaskKind.SHOP, coordinator.activeTask.value)

        gateway.allowFirstTaskToFinish.complete(Unit)
        yield()

        assertEquals(2, gateway.awaitCalls)
        assertEquals(TaskKind.SHOP, coordinator.activeTask.value)
        coordinator.stop(TaskKind.SHOP)
        coordinator.shutdown()
    }

    @Test
    fun stoppingDuringIrreversibleGestureReportsUncertainEffect() = runTest {
        val coordinator = coordinator(
            shopVision = PurchaseShopVision,
            recognizer = ShopRecognizer,
            monitorClock = WarmupThenBlockingClock(),
        )
        val gateway = BlockingTapGateway()
        coordinator.attachGateway(gateway)
        coordinator.startShop(RunConfig())

        assertEquals(AutomationPhase.PURCHASING, coordinator.shopStatus.value.phase)

        coordinator.stop(TaskKind.SHOP)
        yield()

        assertNull(coordinator.activeTask.value)
        assertEquals(StopReason.UNCERTAIN_EFFECT, coordinator.shopStatus.value.stopReason)
        assertTrue(coordinator.shopStatus.value.message.contains("效果不确定"))
        coordinator.shutdown()
    }

    private fun coordinator(
        shopPersistence: ShopTaskPersistence = NoOpShopPersistence,
        shopVision: ShopVision = ReadyShopVision,
        recognizer: GameUiRecognizer = UnknownRecognizer,
        monitorClock: AutomationClock = BlockingClock,
    ): TaskCoordinator {
        val monitor = GameUiMonitor(
            recognizer = recognizer,
            clock = monitorClock,
            dispatcher = Dispatchers.Unconfined,
        )
        return TaskCoordinator(
            shopRunner = ShopTaskRunner(
                vision = shopVision,
                visionConfig = testVisionConfig(),
                persistence = shopPersistence,
                clock = BlockingClock,
            ),
            huntRunner = HuntTaskRunner(
                vision = ReadyHuntVision,
                persistence = HuntTaskPersistence { },
                clock = BlockingClock,
            ),
            uiMonitor = monitor,
            diagnosticSink = TaskDiagnosticSink { _, _ -> null },
            clock = BlockingClock,
            dispatcher = Dispatchers.Unconfined,
        )
    }

    private fun testVisionConfig() = VisionConfig(
        referenceWidth = 1920,
        referenceHeight = 1080,
        purchaseButtonX = 1700,
        scrollFrom = PointConfig(1300, 800),
        scrollTo = PointConfig(1300, 300),
        templates = emptyList(),
    )

    private object NoOpShopPersistence : ShopTaskPersistence {
        override suspend fun saveConfig(config: RunConfig) = Unit
        override suspend fun saveSummary(summary: RunSummary) = Unit
    }

    private object UnknownRecognizer : GameUiRecognizer {
        override fun health(): VisionHealth = readyHealth()
        override suspend fun recognize(frame: ScreenFrame): UiRecognition =
            UiRecognition(GameUiPage.UNKNOWN, 0.0)
    }

    private object ShopRecognizer : GameUiRecognizer {
        override fun health(): VisionHealth = readyHealth()
        override suspend fun recognize(frame: ScreenFrame): UiRecognition =
            UiRecognition(GameUiPage.SHOP, 1.0)
    }

    private object ReadyShopVision : ShopVision {
        override fun health(): VisionHealth = readyHealth()
        override suspend fun detectPage(frame: ScreenFrame): ShopPage = ShopPage.UNKNOWN
        override suspend fun findTargets(
            frame: ScreenFrame,
            config: RunConfig,
        ): List<PurchaseTarget> = emptyList()

        override suspend fun verifyPurchase(
            frame: ScreenFrame,
            target: PurchaseTarget,
        ): MatchResult = MatchResult(false)

        override suspend fun findAction(
            frame: ScreenFrame,
            action: VisualAction,
        ): MatchResult = MatchResult(false)
    }

    private object PurchaseShopVision : ShopVision {
        private val target = PurchaseTarget(
            type = ItemType.COVENANT_BOOKMARK,
            itemBounds = ScreenRect(500, 200, 620, 300),
            purchaseButtonBounds = ScreenRect(1600, 200, 1800, 300),
            confidence = 0.99,
            rowIndex = 1,
        )

        override fun health(): VisionHealth = readyHealth()
        override suspend fun detectPage(frame: ScreenFrame): ShopPage = ShopPage.SHOP
        override suspend fun findTargets(
            frame: ScreenFrame,
            config: RunConfig,
        ): List<PurchaseTarget> = listOf(target)

        override suspend fun verifyPurchase(
            frame: ScreenFrame,
            target: PurchaseTarget,
        ): MatchResult = MatchResult(false)

        override suspend fun findAction(
            frame: ScreenFrame,
            action: VisualAction,
        ): MatchResult = MatchResult(false)
    }

    private object ReadyHuntVision : HuntVision {
        override fun health(): VisionHealth = readyHealth()
        override suspend fun detectPage(frame: ScreenFrame): HuntPage = HuntPage.UNKNOWN
        override suspend fun findDungeon(
            frame: ScreenFrame,
            dungeon: HuntDungeon,
        ): MatchResult = MatchResult(false)

        override suspend fun isManagedBattleEnabled(frame: ScreenFrame): Boolean = false
        override suspend fun managedProgressSignature(frame: ScreenFrame): Long = 0L
        override suspend fun findAction(
            frame: ScreenFrame,
            action: VisualAction,
        ): MatchResult = MatchResult(false)
    }

    private open class TestGateway : ScreenGateway {
        override suspend fun capture(): ScreenFrame = ScreenFrame(
            bitmap = null,
            width = 1920,
            height = 1080,
            capturedAtElapsedMs = 0L,
            sequence = 1L,
        )

        override suspend fun tap(point: ScreenPoint): GestureResult = GestureResult.COMPLETED
        override suspend fun swipe(
            from: ScreenPoint,
            to: ScreenPoint,
            durationMs: Long,
        ): GestureResult = GestureResult.COMPLETED
    }

    private class SlowAwaitGateway : TestGateway() {
        val allowFirstTaskToFinish = CompletableDeferred<Unit>()
        var awaitCalls = 0

        override suspend fun awaitTargetApp(timeoutMs: Long): Boolean {
            awaitCalls += 1
            val call = awaitCalls
            return try {
                awaitCancellation()
            } finally {
                if (call == 1) {
                    withContext(NonCancellable) {
                        allowFirstTaskToFinish.await()
                    }
                }
            }
        }
    }

    private class BlockingTapGateway : TestGateway() {
        override suspend fun tap(point: ScreenPoint): GestureResult = awaitCancellation()
    }

    private object BlockingClock : AutomationClock {
        override fun elapsedRealtime(): Long = 0L
        override suspend fun delay(durationMs: Long): Nothing = awaitCancellation()
    }

    private class WarmupThenBlockingClock : AutomationClock {
        private var delays = 0

        override fun elapsedRealtime(): Long = delays.toLong()

        override suspend fun delay(durationMs: Long) {
            delays += 1
            if (delays >= 2) awaitCancellation()
        }
    }

    private companion object {
        fun readyHealth() = VisionHealth(
            openCvReady = true,
            loadedTemplates = 1,
            requiredTemplates = 1,
            missingTemplateIds = emptyList(),
        )
    }
}
