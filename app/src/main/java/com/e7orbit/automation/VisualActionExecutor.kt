package com.e7orbit.automation

import com.e7orbit.logging.NoOpOrbitLogger
import com.e7orbit.logging.OrbitLogger
import com.e7orbit.model.DevicePoint
import com.e7orbit.model.MatchResult
import com.e7orbit.model.ScreenRatioPoint
import com.e7orbit.model.VisualAction

class VisualActionNotFoundException(
    val action: VisualAction?,
    message: String,
) : RuntimeException(message)

/**
 * The only high-level path for UI gestures.
 *
 * Domain workflows provide a visual action or a visual match. Absolute coordinates
 * remain confined to the accessibility gateway and are never authored in workflows.
 */
class VisualActionExecutor(
    private val operations: OperationExecutor,
    private val vision: VisualActionVision,
    private val namespace: String,
    private val logger: OrbitLogger = NoOpOrbitLogger,
) {
    suspend fun tap(
        action: VisualAction,
        operationId: String,
        policy: OperationPolicy,
        failureMessage: String,
    ): MatchResult {
        val match = operations.capture("$namespace.find_${action.name.lowercase()}").use { frame ->
            vision.findAction(frame, action)
        }
        return tapLocated(
            operationId = operationId,
            targetLabel = action.name,
            action = action,
            match = match,
            policy = policy,
            failureMessage = failureMessage,
        )
    }

    suspend fun tapLocated(
        operationId: String,
        targetLabel: String,
        action: VisualAction? = null,
        match: MatchResult,
        policy: OperationPolicy,
        failureMessage: String,
    ): MatchResult {
        val point = match.center
        logger.debug(
            "visual_action.located",
            "operation" to operationId,
            "target" to targetLabel,
            "matched" to match.matched,
            "confidence" to match.confidence,
            "bounds" to match.bounds,
        )
        if (!match.matched || point == null) {
            throw VisualActionNotFoundException(
                action = action,
                message = failureMessage,
            )
        }
        operations.tap(
            operationId = operationId,
            point = DevicePoint.from(point),
            policy = policy,
        )
        return match
    }

    suspend fun swipe(
        operationId: String,
        from: ScreenRatioPoint,
        to: ScreenRatioPoint,
        durationMs: Long,
        policy: OperationPolicy,
    ) {
        val size = operations.capture("$operationId.geometry").use { frame ->
            frame.width to frame.height
        }
        operations.swipe(
            operationId = operationId,
            from = from.toScreenPoint(size.first, size.second),
            to = to.toScreenPoint(size.first, size.second),
            durationMs = durationMs,
            policy = policy,
        )
    }
}
