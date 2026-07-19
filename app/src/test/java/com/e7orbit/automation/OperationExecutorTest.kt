package com.e7orbit.automation

import com.e7orbit.model.GestureResult
import com.e7orbit.model.ScreenFrame
import com.e7orbit.model.ScreenPoint
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationExecutorTest {
    @Test
    fun propagatesCancellationFromCapture() = runTest {
        val executor = executor(
            gateway = FakeOperationGateway(
                captureError = CancellationException("cancelled"),
            ),
        )

        var propagated = false
        try {
            executor.capture("capture")
        } catch (_: CancellationException) {
            propagated = true
        }

        assertTrue(propagated)
    }

    @Test
    fun retriesCancelledIdempotentGesture() = runTest {
        val gateway = FakeOperationGateway(
            tapResults = listOf(GestureResult.CANCELLED, GestureResult.COMPLETED),
        )

        executor(gateway).tap(
            operationId = "open_menu",
            point = ScreenPoint(10, 10),
            policy = OperationPolicy.idempotent(),
        )

        assertEquals(2, gateway.taps)
    }

    @Test
    fun doesNotBlindlyRetryUncertainEffect() = runTest {
        val gateway = FakeOperationGateway(
            tapResults = listOf(GestureResult.CANCELLED, GestureResult.COMPLETED),
        )

        var kind: ExecutionFailureKind? = null
        try {
            executor(gateway).tap(
                operationId = "confirm_refresh",
                point = ScreenPoint(10, 10),
                policy = OperationPolicy.reconciliationRequired(),
            )
        } catch (error: OperationExecutionException) {
            kind = error.failure.kind
        }

        assertEquals(ExecutionFailureKind.UNCERTAIN_EFFECT, kind)
        assertEquals(1, gateway.taps)
    }

    @Test
    fun normalizesGestureExceptions() = runTest {
        val gateway = FakeOperationGateway(
            tapError = IllegalStateException("service disconnected"),
        )

        var failure: ExecutionFailure? = null
        try {
            executor(gateway).tap(
                operationId = "open_menu",
                point = ScreenPoint(10, 10),
                policy = OperationPolicy.idempotent(),
            )
        } catch (error: OperationExecutionException) {
            failure = error.failure
        }

        assertEquals(ExecutionFailureKind.GESTURE_FAILED, failure?.kind)
        assertTrue(failure?.cause is IllegalStateException)
        assertEquals(1, gateway.taps)
    }

    @Test
    fun excludesPauseObservedInsideProbeFromTimeout() = runTest {
        val clock = FakeOperationClock()
        var gateCalls = 0
        var probes = 0
        val executor = executor(
            gateway = FakeOperationGateway(),
            clock = clock,
            awaitRunPermission = {
                gateCalls += 1
                if (gateCalls == 2) clock.advance(10_000L)
            },
        )

        val result = executor.waitUntil(
            operationId = "page",
            timeoutMs = 100L,
            pollIntervalMs = 50L,
        ) {
            executor.capture("page_probe").use { }
            probes += 1
            "ready".takeIf { probes == 2 }
        }

        assertEquals("ready", result)
        assertEquals(2, probes)
    }

    @Test
    fun recordsCompletedGestureReceiptWithStableToken() = runTest {
        val receipts = mutableListOf<GestureReceipt>()

        executor(
            gateway = FakeOperationGateway(),
            onGestureReceipt = receipts::add,
        ).tap(
            operationId = "open_menu",
            point = ScreenPoint(10, 10),
            policy = OperationPolicy.reconciliationRequired(),
        )

        assertEquals(
            listOf(GestureOutcome.DISPATCHING, GestureOutcome.COMPLETED),
            receipts.map(GestureReceipt::outcome),
        )
        assertEquals(receipts.first().token, receipts.last().token)
    }

    @Test
    fun recordsInterruptedUncertainGestureBeforePropagatingCancellation() = runTest {
        val receipts = mutableListOf<GestureReceipt>()
        val executor = executor(
            gateway = FakeOperationGateway(
                tapError = CancellationException("runtime stopped"),
            ),
            onGestureReceipt = receipts::add,
        )

        var cancelled = false
        try {
            executor.tap(
                operationId = "confirm_refresh",
                point = ScreenPoint(10, 10),
                policy = OperationPolicy.reconciliationRequired(),
            )
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertEquals(GestureOutcome.INTERRUPTED, receipts.last().outcome)
        assertTrue(receipts.last().effectMayBeUncertain)
    }

    private fun executor(
        gateway: ScreenGateway,
        clock: FakeOperationClock = FakeOperationClock(),
        awaitRunPermission: suspend () -> Unit = {},
        onGestureReceipt: (GestureReceipt) -> Unit = {},
    ) = OperationExecutor(
        gateway = gateway,
        clock = clock,
        awaitRunPermission = awaitRunPermission,
        onDiagnostic = { _, _ -> },
        onGestureReceipt = onGestureReceipt,
    )

    private class FakeOperationGateway(
        private val captureError: Throwable? = null,
        private val tapError: Throwable? = null,
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
            tapError?.let { throw it }
            return tapResults.pollFirst() ?: GestureResult.COMPLETED
        }

        override suspend fun swipe(
            from: ScreenPoint,
            to: ScreenPoint,
            durationMs: Long,
        ): GestureResult = GestureResult.COMPLETED
    }

    private class FakeOperationClock : AutomationClock {
        private var now = 0L

        override fun elapsedRealtime(): Long = now

        override suspend fun delay(durationMs: Long) {
            now += durationMs.coerceAtLeast(1L)
        }

        fun advance(durationMs: Long) {
            now += durationMs
        }
    }
}
