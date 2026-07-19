package com.e7orbit.automation

@JvmInline
value class GestureToken(
    val value: Long,
)

enum class GestureOutcome {
    DISPATCHING,
    COMPLETED,
    CANCELLED,
    REJECTED,
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
            GestureOutcome.CANCELLED,
            GestureOutcome.INTERRUPTED,
        ) &&
            effectSafety in setOf(
                EffectSafety.RECONCILIATION_REQUIRED,
                EffectSafety.EXTERNAL_LONG_RUNNING,
            )
}
