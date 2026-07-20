package com.e7orbit.automation

import com.e7orbit.model.GestureResult
import com.e7orbit.model.MatchResult
import com.e7orbit.model.ScreenFrame
import com.e7orbit.model.ScreenPoint
import com.e7orbit.model.ScreenRatioPoint
import com.e7orbit.model.ScreenRect
import com.e7orbit.model.VisualAction
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualActionExecutorTest {
    @Test
    fun tapsCenterReturnedByVision() = runTest {
        val gateway = RecordingGateway()
        val executor = visualExecutor(
            gateway = gateway,
            match = MatchResult(
                matched = true,
                confidence = 0.98,
                bounds = ScreenRect(100, 200, 300, 400),
            ),
        )

        executor.tap(
            action = VisualAction.CONFIRM_PURCHASE,
            operationId = "confirm_purchase",
            policy = OperationPolicy.reconciliationRequired(),
            failureMessage = "missing",
        )

        assertEquals(listOf(ScreenPoint(200, 300)), gateway.tappedPoints)
    }

    @Test
    fun missingVisualActionNeverFallsBackToCoordinates() = runTest {
        val gateway = RecordingGateway()
        val executor = visualExecutor(
            gateway = gateway,
            match = MatchResult(matched = false),
        )

        var failure: VisualActionNotFoundException? = null
        try {
            executor.tap(
                action = VisualAction.HUNT_STOP_MANAGED,
                operationId = "stop_managed",
                policy = OperationPolicy.externalLongRunning(),
                failureMessage = "missing stop button",
            )
        } catch (error: VisualActionNotFoundException) {
            failure = error
        }

        assertEquals(VisualAction.HUNT_STOP_MANAGED, failure?.action)
        assertTrue(gateway.tappedPoints.isEmpty())
    }

    @Test
    fun swipeUsesCurrentFrameRatios() = runTest {
        val gateway = RecordingGateway()
        val executor = visualExecutor(
            gateway = gateway,
            match = MatchResult(matched = false),
        )

        executor.swipe(
            operationId = "scroll",
            from = ScreenRatioPoint(0.75, 0.80),
            to = ScreenRatioPoint(0.75, 0.20),
            durationMs = 500L,
            policy = OperationPolicy.idempotent(),
        )

        assertEquals(ScreenPoint(1440, 864), gateway.swipeFrom)
        assertEquals(ScreenPoint(1440, 216), gateway.swipeTo)
    }

    private fun visualExecutor(
        gateway: RecordingGateway,
        match: MatchResult,
    ): VisualActionExecutor {
        val operations = OperationExecutor(
            gateway = gateway,
            clock = TestClock,
            awaitRunPermission = {},
            onDiagnostic = { _, _ -> },
        )
        val vision = object : VisualActionVision {
            override suspend fun findAction(
                frame: ScreenFrame,
                action: VisualAction,
            ): MatchResult = match
        }
        return VisualActionExecutor(
            operations = operations,
            vision = vision,
            namespace = "test",
        )
    }

    private class RecordingGateway : ScreenGateway {
        val tappedPoints = mutableListOf<ScreenPoint>()
        var swipeFrom: ScreenPoint? = null
        var swipeTo: ScreenPoint? = null

        override suspend fun capture(): ScreenFrame = ScreenFrame(
            bitmap = null,
            width = 1920,
            height = 1080,
            capturedAtElapsedMs = 0L,
            sequence = 1L,
        )

        override suspend fun tap(point: ScreenPoint): GestureResult {
            tappedPoints += point
            return GestureResult.COMPLETED
        }

        override suspend fun swipe(
            from: ScreenPoint,
            to: ScreenPoint,
            durationMs: Long,
        ): GestureResult {
            swipeFrom = from
            swipeTo = to
            return GestureResult.COMPLETED
        }
    }

    private object TestClock : AutomationClock {
        override fun elapsedRealtime(): Long = 0L

        override suspend fun delay(durationMs: Long) = Unit
    }
}
