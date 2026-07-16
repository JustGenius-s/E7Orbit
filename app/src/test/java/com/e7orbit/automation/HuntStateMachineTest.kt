package com.e7orbit.automation

import com.e7orbit.model.GestureResult
import com.e7orbit.model.HuntConfig
import com.e7orbit.model.HuntPage
import com.e7orbit.model.HuntStopReason
import com.e7orbit.model.ScreenFrame
import com.e7orbit.model.ScreenPoint
import com.e7orbit.vision.PointConfig
import com.e7orbit.vision.VisionConfig
import java.util.ArrayDeque
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HuntStateMachineTest {
    @Test
    fun completesConfiguredManagedRunFromLobby() = runTest {
        val vision = FakeHuntVision(
            pages = listOf(
                HuntPage.LOBBY,
                HuntPage.BATTLE_MENU,
                HuntPage.HUNT_SELECTION,
                HuntPage.TEAM_QUICK_BATTLE,
                HuntPage.TEAM_READY,
                HuntPage.BATTLE_CONTROLS,
                HuntPage.DELEGATION_CONFIRMATION,
                HuntPage.LOBBY_MANAGED,
                HuntPage.MANAGED_PANEL,
                HuntPage.LOBBY_MANAGED,
                HuntPage.MANAGED_COMPLETE,
            ),
            managedStates = listOf(false, true),
            progressSignatures = listOf(10L, 20L),
        )
        val gateway = FakeHuntGateway()
        val machine = HuntStateMachine(
            vision = vision,
            visionConfig = testVisionConfig(),
            clock = FakeHuntClock(),
        )

        val result = machine.run(
            config = HuntConfig(runCount = 1),
            gateway = gateway,
            awaitRunPermission = {},
            onStatus = { _, _, _, _ -> },
            onDiagnostic = { _, _ -> },
        )

        assertTrue(result.successful)
        assertEquals(HuntStopReason.RUN_LIMIT_REACHED, result.reason)
        assertEquals(1, result.stats.completedRuns)
        assertEquals(11, gateway.taps)
    }

    private class FakeHuntVision(
        pages: List<HuntPage>,
        managedStates: List<Boolean>,
        progressSignatures: List<Long>,
    ) : HuntVision {
        private val pages = ArrayDeque(pages)
        private val managedStates = ArrayDeque(managedStates)
        private val signatures = ArrayDeque(progressSignatures)

        override fun health(): VisionHealth = VisionHealth(
            openCvReady = true,
            loadedTemplates = 10,
            requiredTemplates = 10,
            missingTemplateIds = emptyList(),
        )

        override suspend fun detectPage(frame: ScreenFrame): HuntPage =
            pages.removeFirst()

        override suspend fun isManagedBattleEnabled(frame: ScreenFrame): Boolean =
            managedStates.removeFirst()

        override suspend fun managedProgressSignature(frame: ScreenFrame): Long =
            signatures.removeFirst()
    }

    private class FakeHuntGateway : ScreenGateway {
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

    private class FakeHuntClock : AutomationClock {
        private var now = 0L

        override fun elapsedRealtime(): Long = now

        override suspend fun delay(durationMs: Long) {
            now += durationMs
        }
    }

    private fun testVisionConfig() = VisionConfig(
        referenceWidth = 1024,
        referenceHeight = 576,
        purchaseButtonX = 0,
        scrollFrom = PointConfig(0, 0),
        scrollTo = PointConfig(0, 0),
        templates = emptyList(),
    )
}
