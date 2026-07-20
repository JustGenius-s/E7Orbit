package com.e7orbit.automation

import com.e7orbit.logging.NoOpOrbitLogger
import com.e7orbit.logging.OrbitLogger
import com.e7orbit.model.DevicePoint
import com.e7orbit.model.GestureResult
import com.e7orbit.model.REFERENCE_HEIGHT
import com.e7orbit.model.REFERENCE_WIDTH
import com.e7orbit.model.ScreenFrame
import com.e7orbit.model.ScreenPoint
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

@Serializable
enum class EffectSafety {
    READ_ONLY,
    IDEMPOTENT,
    RECONCILIATION_REQUIRED,
    EXTERNAL_LONG_RUNNING,
}

data class OperationPolicy(
    val effectSafety: EffectSafety,
    val maxAttempts: Int,
    val retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts 必须至少为 1" }
        require(retryDelayMs >= 0L) { "retryDelayMs 不能为负数" }
    }

    companion object {
        fun idempotent(
            maxAttempts: Int = 3,
            retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
        ) = OperationPolicy(
            effectSafety = EffectSafety.IDEMPOTENT,
            maxAttempts = maxAttempts,
            retryDelayMs = retryDelayMs,
        )

        fun reconciliationRequired() = OperationPolicy(
            effectSafety = EffectSafety.RECONCILIATION_REQUIRED,
            maxAttempts = 1,
        )

        fun externalLongRunning() = OperationPolicy(
            effectSafety = EffectSafety.EXTERNAL_LONG_RUNNING,
            maxAttempts = 1,
        )

        private const val DEFAULT_RETRY_DELAY_MS = 160L
    }
}

enum class ExecutionFailureKind {
    SCREENSHOT_FAILED,
    INVALID_RESOLUTION,
    GESTURE_FAILED,
    UNCERTAIN_EFFECT,
    TIMEOUT,
}

data class ExecutionFailure(
    val kind: ExecutionFailureKind,
    val operationId: String,
    val message: String,
    val cause: Throwable? = null,
)

class OperationExecutionException(
    val failure: ExecutionFailure,
) : RuntimeException(failure.message, failure.cause)

/**
 * Shared execution primitives for workflows.
 *
 * This class owns cancellation propagation, resolution checks, pause-aware waiting,
 * gesture retry policy and diagnostic capture. Domain workflows remain responsible
 * for preconditions, postconditions and reconciliation of irreversible effects.
 */
class OperationExecutor(
    private val gateway: ScreenGateway,
    private val clock: AutomationClock,
    private val awaitRunPermission: suspend () -> Unit,
    private val onDiagnostic: suspend (ScreenFrame, String) -> Unit,
    private val onGestureReceipt: (GestureReceipt) -> Unit = {},
    private val logger: OrbitLogger = NoOpOrbitLogger,
    private val expectedWidth: Int = REFERENCE_WIDTH,
    private val expectedHeight: Int = REFERENCE_HEIGHT,
) {
    private var excludedPermissionWaitMs = 0L

    suspend fun awaitActive() {
        val startedAt = clock.elapsedRealtime()
        awaitRunPermission()
        excludedPermissionWaitMs +=
            (clock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
    }

    suspend fun capture(operationId: String): ScreenFrame {
        awaitActive()
        val frame = try {
            gateway.capture()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw failure(
                kind = ExecutionFailureKind.SCREENSHOT_FAILED,
                operationId = operationId,
                message = "截图失败：${error.message.orEmpty()}",
                cause = error,
            )
        }
        if (frame.width != expectedWidth || frame.height != expectedHeight) {
            frame.close()
            throw failure(
                kind = ExecutionFailureKind.INVALID_RESOLUTION,
                operationId = operationId,
                message = "需要 ${expectedWidth}×${expectedHeight}，" +
                    "当前为 ${frame.width}×${frame.height}",
            )
        }
        return frame
    }

    suspend fun tap(
        operationId: String,
        point: ScreenPoint,
        policy: OperationPolicy,
    ): GestureResult = performGesture(operationId, policy) {
        gateway.tap(point)
    }

    suspend fun tap(
        operationId: String,
        point: DevicePoint,
        policy: OperationPolicy,
    ): GestureResult = tap(operationId, point.toScreenPoint(), policy)

    suspend fun swipe(
        operationId: String,
        from: ScreenPoint,
        to: ScreenPoint,
        durationMs: Long,
        policy: OperationPolicy,
    ): GestureResult = performGesture(operationId, policy) {
        gateway.swipe(from, to, durationMs)
    }

    suspend fun performGesture(
        operationId: String,
        policy: OperationPolicy,
        gesture: suspend () -> GestureResult,
    ): GestureResult {
        repeat(policy.maxAttempts) { attempt ->
            awaitActive()
            val token = GestureToken(nextGestureToken.incrementAndGet())
            recordGesture(
                token = token,
                operationId = operationId,
                attempt = attempt + 1,
                policy = policy,
                outcome = GestureOutcome.DISPATCHING,
            )
            val result = try {
                gesture()
            } catch (cancelled: CancellationException) {
                recordGesture(
                    token = token,
                    operationId = operationId,
                    attempt = attempt + 1,
                    policy = policy,
                    outcome = GestureOutcome.INTERRUPTED,
                    detail = cancelled.message,
                )
                throw cancelled
            } catch (error: Exception) {
                recordGesture(
                    token = token,
                    operationId = operationId,
                    attempt = attempt + 1,
                    policy = policy,
                    outcome = GestureOutcome.FAILED,
                    detail = error.message,
                )
                throw failure(
                    kind = ExecutionFailureKind.GESTURE_FAILED,
                    operationId = operationId,
                    message = "$operationId 执行异常：${error.message.orEmpty()}",
                    cause = error,
                )
            }
            recordGesture(
                token = token,
                operationId = operationId,
                attempt = attempt + 1,
                policy = policy,
                outcome = when (result) {
                    GestureResult.COMPLETED -> GestureOutcome.COMPLETED
                    GestureResult.CANCELLED -> GestureOutcome.CANCELLED
                    GestureResult.REJECTED -> GestureOutcome.REJECTED
                    GestureResult.TIMED_OUT -> GestureOutcome.TIMED_OUT
                },
            )
            if (result == GestureResult.COMPLETED) return result

            val attemptsRemain = attempt < policy.maxAttempts - 1
            val canRetry = result in setOf(
                GestureResult.CANCELLED,
                GestureResult.TIMED_OUT,
            ) &&
                attemptsRemain &&
                policy.effectSafety == EffectSafety.IDEMPOTENT
            if (canRetry) {
                logger.warn(
                    "operation.gesture.retrying",
                    "operation" to operationId,
                    "attempt" to attempt + 1,
                    "result" to result,
                )
                clock.delay(policy.retryDelayMs)
                return@repeat
            }

            val uncertain = result == GestureResult.TIMED_OUT ||
                result == GestureResult.CANCELLED &&
                policy.effectSafety in setOf(
                    EffectSafety.RECONCILIATION_REQUIRED,
                    EffectSafety.EXTERNAL_LONG_RUNNING,
                )
            throw failure(
                kind = if (uncertain) {
                    ExecutionFailureKind.UNCERTAIN_EFFECT
                } else {
                    ExecutionFailureKind.GESTURE_FAILED
                },
                operationId = operationId,
                message = if (uncertain) {
                    "$operationId 的执行结果不确定，需要重新观察后再决定是否重试"
                } else {
                    "$operationId 执行失败：$result"
                },
            )
        }
        throw failure(
            kind = ExecutionFailureKind.GESTURE_FAILED,
            operationId = operationId,
            message = "$operationId 执行失败",
        )
    }

    suspend fun <T> waitUntil(
        operationId: String,
        timeoutMs: Long,
        pollIntervalMs: Long,
        diagnosticReason: String = operationId,
        probe: suspend () -> T?,
    ): T {
        require(timeoutMs > 0L) { "timeoutMs 必须大于 0" }
        require(pollIntervalMs > 0L) { "pollIntervalMs 必须大于 0" }
        val startedAt = clock.elapsedRealtime()
        val excludedAtStart = excludedPermissionWaitMs
        var firstProbe = true
        while (true) {
            awaitActive()
            val elapsedBeforeProbe = activeElapsedSince(startedAt, excludedAtStart)
            if (!firstProbe && elapsedBeforeProbe >= timeoutMs) break
            firstProbe = false
            probe()?.let { return it }
            val elapsedAfterProbe = activeElapsedSince(startedAt, excludedAtStart)
            if (elapsedAfterProbe >= timeoutMs) break
            val remaining = timeoutMs - elapsedAfterProbe
            clock.delay(minOf(pollIntervalMs, remaining))
        }
        diagnose(diagnosticReason)
        throw failure(
            kind = ExecutionFailureKind.TIMEOUT,
            operationId = operationId,
            message = "等待 $operationId 超时",
        )
    }

    suspend fun diagnose(reason: String) {
        try {
            gateway.capture().use { frame ->
                onDiagnostic(frame, reason)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            logger.error("operation.diagnostic.failed", error, "reason" to reason)
        }
    }

    private fun activeElapsedSince(
        startedAt: Long,
        excludedAtStart: Long,
    ): Long {
        val wallElapsed = (clock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
        val excluded = (excludedPermissionWaitMs - excludedAtStart).coerceAtLeast(0L)
        return (wallElapsed - excluded).coerceAtLeast(0L)
    }

    private fun failure(
        kind: ExecutionFailureKind,
        operationId: String,
        message: String,
        cause: Throwable? = null,
    ) = OperationExecutionException(
        ExecutionFailure(
            kind = kind,
            operationId = operationId,
            message = message,
            cause = cause,
        ),
    )

    private fun recordGesture(
        token: GestureToken,
        operationId: String,
        attempt: Int,
        policy: OperationPolicy,
        outcome: GestureOutcome,
        detail: String? = null,
    ) {
        val receipt = GestureReceipt(
            token = token,
            operationId = operationId,
            attempt = attempt,
            effectSafety = policy.effectSafety,
            outcome = outcome,
            recordedAtElapsedMs = clock.elapsedRealtime(),
            detail = detail,
        )
        try {
            onGestureReceipt(receipt)
        } catch (error: Throwable) {
            logger.error(
                "operation.gesture_receipt.failed",
                error,
                "operation" to operationId,
                "outcome" to outcome,
            )
        }
    }

    private companion object {
        val nextGestureToken = AtomicLong(0L)
    }
}
