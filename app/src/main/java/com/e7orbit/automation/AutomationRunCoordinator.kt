package com.e7orbit.automation

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class AutomationKind {
    SHOP,
    HUNT,
}

class RunLease internal constructor(
    val kind: AutomationKind,
    internal val token: Long,
)

class AutomationRunCoordinator {
    private val nextToken = AtomicLong(0L)
    private val active = AtomicReference<RunLease?>()

    fun tryAcquire(kind: AutomationKind): RunLease? {
        val lease = RunLease(
            kind = kind,
            token = nextToken.incrementAndGet(),
        )
        return lease.takeIf { active.compareAndSet(null, it) }
    }

    fun release(lease: RunLease): Boolean = active.compareAndSet(lease, null)

    fun activeKind(): AutomationKind? = active.get()?.kind
}
