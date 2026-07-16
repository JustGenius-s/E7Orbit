package com.e7orbit.automation

import java.util.concurrent.atomic.AtomicReference

enum class AutomationKind {
    SHOP,
    HUNT,
}

class AutomationRunCoordinator {
    private val active = AtomicReference<AutomationKind?>()

    fun tryAcquire(kind: AutomationKind): Boolean =
        active.compareAndSet(null, kind) || active.get() == kind

    fun release(kind: AutomationKind) {
        active.compareAndSet(kind, null)
    }

    fun activeKind(): AutomationKind? = active.get()
}
