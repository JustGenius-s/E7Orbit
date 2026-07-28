package com.e7orbit.automation

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class GestureToken(
    val value: Long,
)

enum class GestureOutcome {
    DISPATCHING,
    COMPLETED,
    CANCELLED,
    REJECTED,
    TIMED_OUT,
    INTERRUPTED,
    RECONCILED,
    FAILED,
}

data class GestureReceipt(
    val token: GestureToken,
    val operationId: String,
    val attempt: Int,
    val effectSafety: EffectSafety,
    val outcome: GestureOutcome,
    val recordedAtElapsedMs: Long,
    val detail: String? = null,
) {
    val effectMayBeUncertain: Boolean
        get() = outcome in setOf(
            GestureOutcome.DISPATCHING,
            GestureOutcome.CANCELLED,
            GestureOutcome.TIMED_OUT,
            GestureOutcome.INTERRUPTED,
        ) &&
            effectSafety in setOf(
                EffectSafety.RECONCILIATION_REQUIRED,
                EffectSafety.EXTERNAL_LONG_RUNNING,
            )
}
