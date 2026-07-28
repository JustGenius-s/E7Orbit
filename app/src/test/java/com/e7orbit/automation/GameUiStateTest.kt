package com.e7orbit.automation

import com.e7orbit.model.GestureResult
import com.e7orbit.model.ScreenFrame
import com.e7orbit.model.ScreenPoint
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameUiStateTest {
    @Test
    fun stabilizerRequiresTwoMatchingKnownFrames() {
        val stabilizer = UiStateStabilizer()

        val first = stabilizer.reduce(UiRecognition(GameUiPage.SHOP, 0.95), 1L, 10L)
        val second = stabilizer.reduce(UiRecognition(GameUiPage.SHOP, 0.96), 2L, 20L)

        assertFalse(first.isStable)
        assertEquals(GameUiPage.UNKNOWN, first.page)
        assertEquals(GameUiPage.SHOP, first.candidatePage)
        assertTrue(second.isStable)
        assertEquals(GameUiPage.SHOP, second.page)
        assertEquals(2, second.stableFrames)
    }

    @Test
    fun pageChangeAndResetDiscardPreviousStableState() {
        val stabilizer = UiStateStabilizer()
        stabilizer.reduce(UiRecognition(GameUiPage.SHOP, 1.0), 1L, 10L)
        stabilizer.reduce(UiRecognition(GameUiPage.SHOP, 1.0), 2L, 20L)

        val changed = stabilizer.reduce(UiRecognition(GameUiPage.LOBBY, 1.0), 3L, 30L)
        stabilizer.reset()
        val afterReset = stabilizer.reduce(UiRecognition(GameUiPage.LOBBY, 1.0), 4L, 40L)

        assertFalse(changed.isStable)
        assertEquals(GameUiPage.UNKNOWN, changed.page)
        assertEquals(GameUiPage.LOBBY, changed.candidatePage)
        assertFalse(afterReset.isStable)
        assertEquals(1, afterReset.stableFrames)
    }

    @Test
    fun actionGuardAllowsGestureOnContractPage() = runTest {
        val gateway = CountingGestureGateway()
        val session = testSession(
            gateway = gateway,
            uiStateSource = TestGameUiStateSource(fallbackPage = GameUiPage.SHOP),
            clock = ImmediateClock,
        )
        session.updateUiContract(shopContract())

        session.executor.tap(
            operationId = "shop.allowed",
            point = ScreenPoint(10, 10),
            policy = OperationPolicy.idempotent(),
        )

        assertEquals(1, gateway.taps)
    }

    @Test
    fun actionGuardBlocksGestureBeforeGatewayOnMismatchedPage() = runTest {
        val gateway = CountingGestureGateway()
        val session = testSession(
            gateway = gateway,
            uiStateSource = TestGameUiStateSource(fallbackPage = GameUiPage.LOBBY),
            clock = ImmediateClock,
        )
        session.updateUiContract(shopContract())

        val failure = runCatching {
            session.executor.tap(
                operationId = "shop.blocked",
                point = ScreenPoint(10, 10),
                policy = OperationPolicy.idempotent(),
            )
        }.exceptionOrNull() as OperationExecutionException

        assertEquals(ExecutionFailureKind.UI_STATE_MISMATCH, failure.failure.kind)
        assertEquals(0, gateway.taps)
    }

    @Test
    fun actionGuardBlocksGestureWhenGameLeavesForeground() = runTest {
        val gateway = CountingGestureGateway(foreground = false)
        val session = testSession(
            gateway = gateway,
            uiStateSource = TestGameUiStateSource(fallbackPage = GameUiPage.SHOP),
            clock = ImmediateClock,
        )
        session.updateUiContract(shopContract())

        val failure = runCatching {
            session.executor.tap(
                operationId = "shop.background_blocked",
                point = ScreenPoint(10, 10),
                policy = OperationPolicy.idempotent(),
            )
        }.exceptionOrNull() as OperationExecutionException

        assertEquals(ExecutionFailureKind.UI_STATE_MISMATCH, failure.failure.kind)
        assertEquals(0, gateway.taps)
    }

    private fun shopContract() = TaskUiContract(
        task = TaskKind.SHOP,
        step = "scan",
        allowedPages = setOf(GameUiPage.SHOP),
    )

    private class CountingGestureGateway(
        private val foreground: Boolean = true,
    ) : ScreenGateway {
        var taps = 0

        override fun isTargetAppForeground(): Boolean = foreground

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

    private object ImmediateClock : AutomationClock {
        override fun elapsedRealtime(): Long = 0L
        override suspend fun delay(durationMs: Long) = Unit
    }
}
