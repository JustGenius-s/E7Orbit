package com.e7orbit.automation

import com.e7orbit.model.AutomationPhase
import com.e7orbit.model.GameLocation
import com.e7orbit.model.GestureResult
import com.e7orbit.model.GlobalAction
import com.e7orbit.model.HuntConfig
import com.e7orbit.model.HuntDungeon
import com.e7orbit.model.HuntPage
import com.e7orbit.model.HuntPhase
import com.e7orbit.model.HuntStopReason
import com.e7orbit.model.MatchResult
import com.e7orbit.model.PurchaseTarget
import com.e7orbit.model.RunConfig
import com.e7orbit.model.RunSummary
import com.e7orbit.model.ScreenFrame
import com.e7orbit.model.ScreenPoint
import com.e7orbit.model.ShopAction
import com.e7orbit.model.ShopPage
import com.e7orbit.model.StopReason
import com.e7orbit.vision.PointConfig
import com.e7orbit.vision.VisionConfig
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeSafetyTest {
    @Test
    fun shopConfigFailureReleasesLeaseAndReportsError() = runTest {
        val coordinator = AutomationRunCoordinator()
        val runtime = shopRuntime(
            coordinator = coordinator,
            persistence = object : ShopRuntimePersistence {
                override suspend fun saveConfig(config: RunConfig) {
                    throw IOException("disk full")
                }

                override suspend fun saveSummary(summary: RunSummary) = Unit
            },
        )
        runtime.attachGateway(IdleGateway)

        runtime.start(RunConfig())
        advanceUntilIdle()

        assertEquals(AutomationPhase.ERROR, runtime.status.value.phase)
        assertEquals(StopReason.INTERNAL_ERROR, runtime.status.value.stopReason)
        assertTrue(runtime.status.value.message.contains("disk full"))
        assertNull(coordinator.activeKind())
        runtime.shutdown()
    }

    @Test
    fun huntConfigFailureReleasesLeaseAndReportsError() = runTest {
        val coordinator = AutomationRunCoordinator()
        val runtime = huntRuntime(
            coordinator = coordinator,
            persistence = HuntRuntimePersistence {
                throw IOException("read only")
            },
        )
        runtime.attachGateway(IdleGateway)

        runtime.start(HuntConfig())
        advanceUntilIdle()

        assertEquals(HuntPhase.ERROR, runtime.status.value.phase)
        assertEquals(HuntStopReason.INTERNAL_ERROR, runtime.status.value.stopReason)
        assertTrue(runtime.status.value.message.contains("read only"))
        assertNull(coordinator.activeKind())
        runtime.shutdown()
    }

    @Test
    fun staleGatewayDetachDoesNotStopShopRuntime() = runTest {
        val coordinator = AutomationRunCoordinator()
        val blockingGateway = BlockingGateway()
        val runtime = shopRuntime(coordinator = coordinator)
        runtime.attachGateway(IdleGateway)
        runtime.attachGateway(blockingGateway)
        runtime.start(RunConfig())
        advanceUntilIdle()

        runtime.detachGateway(IdleGateway)

        assertTrue(runtime.status.value.serviceReady)
        assertEquals(AutomationKind.SHOP, coordinator.activeKind())
        runtime.stop()
        advanceUntilIdle()
        runtime.shutdown()
    }

    @Test
    fun staleGatewayDetachDoesNotStopHuntRuntime() = runTest {
        val coordinator = AutomationRunCoordinator()
        val blockingGateway = BlockingGateway()
        val runtime = huntRuntime(coordinator = coordinator)
        runtime.attachGateway(IdleGateway)
        runtime.attachGateway(blockingGateway)
        runtime.start(HuntConfig())
        advanceUntilIdle()

        runtime.detachGateway(IdleGateway)

        assertTrue(runtime.status.value.serviceReady)
        assertEquals(AutomationKind.HUNT, coordinator.activeKind())
        runtime.stop()
        advanceUntilIdle()
        runtime.shutdown()
    }

    @Test
    fun shopRestartWaitsForCancelledRunToFinish() = runTest {
        val coordinator = AutomationRunCoordinator()
        val persistence = CountingShopPersistence()
        val gateway = SlowCancellationGateway()
        val runtime = shopRuntime(
            coordinator = coordinator,
            persistence = persistence,
        )
        runtime.attachGateway(gateway)
        runtime.start(RunConfig())
        advanceUntilIdle()
        assertEquals(1, persistence.configSaves)

        runtime.stop()
        runtime.restart()
        advanceUntilIdle()

        assertEquals(1, persistence.configSaves)
        assertEquals(AutomationKind.SHOP, coordinator.activeKind())

        gateway.allowFirstRunToFinish.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, persistence.configSaves)
        assertEquals(2, gateway.captureCalls)
        assertEquals(AutomationKind.SHOP, coordinator.activeKind())
        runtime.stop()
        advanceUntilIdle()
        runtime.shutdown()
    }

    @Test
    fun shopStartAfterStopWaitsForCancelledRunToFinish() = runTest {
        val coordinator = AutomationRunCoordinator()
        val persistence = CountingShopPersistence()
        val gateway = SlowCancellationGateway()
        val runtime = shopRuntime(
            coordinator = coordinator,
            persistence = persistence,
        )
        runtime.attachGateway(gateway)
        runtime.start(RunConfig())
        advanceUntilIdle()

        runtime.stop()
        val nextStart = launch { runtime.start(RunConfig(maxRefreshes = 2)) }
        advanceUntilIdle()

        assertEquals(1, persistence.configSaves)
        assertEquals(AutomationKind.SHOP, coordinator.activeKind())

        gateway.allowFirstRunToFinish.complete(Unit)
        advanceUntilIdle()

        assertTrue(nextStart.isCompleted)
        assertEquals(2, persistence.configSaves)
        assertEquals(2, runtime.status.value.config.maxRefreshes)
        runtime.stop()
        advanceUntilIdle()
        runtime.shutdown()
    }

    @Test
    fun shopRejectsMissingGlobalNavigationTemplates() = runTest {
        val coordinator = AutomationRunCoordinator()
        val runtime = shopRuntime(
            coordinator = coordinator,
            homeNavigator = HomeNavigator(
                vision = MissingGlobalUiVision,
                clock = TestClock,
            ),
        )
        runtime.attachGateway(IdleGateway)

        runtime.start(RunConfig())
        advanceUntilIdle()

        assertEquals(AutomationPhase.ERROR, runtime.status.value.phase)
        assertEquals(StopReason.TEMPLATES_MISSING, runtime.status.value.stopReason)
        assertTrue(runtime.status.value.message.contains("global_menu_button"))
        assertNull(coordinator.activeKind())
        runtime.shutdown()
    }

    @Test
    fun switchingGatewayUsesTheLatestServiceForNewOperations() = runTest {
        val first = CountingGateway()
        val second = CountingGateway()
        var current: ScreenGateway? = first
        val gateway = SwitchingScreenGateway { current }

        gateway.tap(ScreenPoint(1, 1))
        current = second
        gateway.tap(ScreenPoint(2, 2))

        assertEquals(1, first.taps)
        assertEquals(1, second.taps)
    }

    private fun TestScope.shopRuntime(
        coordinator: AutomationRunCoordinator,
        persistence: ShopRuntimePersistence = CountingShopPersistence(),
        homeNavigator: HomeNavigator? = null,
    ) = AutomationRuntime(
        vision = ReadyShopVision,
        visionConfig = testVisionConfig(),
        persistence = persistence,
        diagnosticSink = ShopRuntimeDiagnosticSink { _, _ -> null },
        clock = TestClock,
        runCoordinator = coordinator,
        homeNavigator = homeNavigator,
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun TestScope.huntRuntime(
        coordinator: AutomationRunCoordinator,
        persistence: HuntRuntimePersistence = HuntRuntimePersistence { },
    ) = HuntRuntime(
        vision = ReadyHuntVision,
        visionConfig = testVisionConfig(),
        persistence = persistence,
        diagnosticSink = HuntRuntimeDiagnosticSink { _, _ -> },
        clock = TestClock,
        runCoordinator = coordinator,
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    private class CountingShopPersistence : ShopRuntimePersistence {
        var configSaves = 0

        override suspend fun saveConfig(config: RunConfig) {
            configSaves += 1
        }

        override suspend fun saveSummary(summary: RunSummary) = Unit
    }

    private class BlockingGateway : ScreenGateway {
        override suspend fun capture(): ScreenFrame = awaitCancellation()

        override suspend fun tap(point: ScreenPoint): GestureResult = GestureResult.COMPLETED

        override suspend fun swipe(
            from: ScreenPoint,
            to: ScreenPoint,
            durationMs: Long,
        ): GestureResult = GestureResult.COMPLETED
    }

    private class CountingGateway : ScreenGateway {
        var taps = 0

        override suspend fun capture(): ScreenFrame = error("not used")

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

    private class SlowCancellationGateway : ScreenGateway {
        val allowFirstRunToFinish = CompletableDeferred<Unit>()
        var captureCalls = 0

        override suspend fun capture(): ScreenFrame {
            captureCalls += 1
            val call = captureCalls
            return try {
                awaitCancellation()
            } finally {
                if (call == 1) {
                    withContext(NonCancellable) {
                        allowFirstRunToFinish.await()
                    }
                }
            }
        }

        override suspend fun tap(point: ScreenPoint): GestureResult = GestureResult.COMPLETED

        override suspend fun swipe(
            from: ScreenPoint,
            to: ScreenPoint,
            durationMs: Long,
        ): GestureResult = GestureResult.COMPLETED
    }

    private object IdleGateway : ScreenGateway {
        override suspend fun capture(): ScreenFrame = error("capture should not be called")

        override suspend fun tap(point: ScreenPoint): GestureResult = GestureResult.COMPLETED

        override suspend fun swipe(
            from: ScreenPoint,
            to: ScreenPoint,
            durationMs: Long,
        ): GestureResult = GestureResult.COMPLETED
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
        ): MatchResult = MatchResult(matched = false)

        override suspend fun findAction(
            frame: ScreenFrame,
            action: ShopAction,
        ): MatchResult = MatchResult(matched = false)
    }

    private object ReadyHuntVision : HuntVision {
        override fun health(): VisionHealth = readyHealth()

        override suspend fun detectPage(frame: ScreenFrame): HuntPage = HuntPage.UNKNOWN

        override suspend fun findDungeon(
            frame: ScreenFrame,
            dungeon: HuntDungeon,
        ): MatchResult = MatchResult(matched = false)

        override suspend fun isManagedBattleEnabled(frame: ScreenFrame): Boolean = false

        override suspend fun managedProgressSignature(frame: ScreenFrame): Long = 0L
    }

    private object MissingGlobalUiVision : GlobalUiVision {
        override fun navigationHealth(): VisionHealth = VisionHealth(
            openCvReady = true,
            loadedTemplates = 3,
            requiredTemplates = 4,
            missingTemplateIds = listOf("global_menu_button"),
        )

        override suspend fun detectLocation(frame: ScreenFrame): GameLocation =
            error("navigation should not run")

        override suspend fun findGlobalAction(
            frame: ScreenFrame,
            action: GlobalAction,
        ): MatchResult = error("navigation should not run")
    }

    private object TestClock : AutomationClock {
        override fun elapsedRealtime(): Long = 1L

        override suspend fun delay(durationMs: Long) = Unit
    }

    private companion object {
        fun readyHealth() = VisionHealth(
            openCvReady = true,
            loadedTemplates = 1,
            requiredTemplates = 1,
            missingTemplateIds = emptyList(),
        )

        fun testVisionConfig() = VisionConfig(
            referenceWidth = 1920,
            referenceHeight = 1080,
            purchaseButtonX = 0,
            scrollFrom = PointConfig(0, 0),
            scrollTo = PointConfig(0, 0),
            templates = emptyList(),
        )
    }
}
