package com.e7orbit.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AutomationRunCoordinatorTest {
    @Test
    fun sameKindCannotAcquireCoordinatorTwice() {
        val coordinator = AutomationRunCoordinator()

        val first = coordinator.tryAcquire(AutomationKind.SHOP)
        val second = coordinator.tryAcquire(AutomationKind.SHOP)

        assertNotNull(first)
        assertNull(second)
        assertEquals(AutomationKind.SHOP, coordinator.activeKind())
    }

    @Test
    fun staleLeaseCannotReleaseANewerRun() {
        val coordinator = AutomationRunCoordinator()
        val first = requireNotNull(coordinator.tryAcquire(AutomationKind.SHOP))
        coordinator.release(first)
        val second = requireNotNull(coordinator.tryAcquire(AutomationKind.HUNT))

        assertNotEquals(first.token, second.token)
        assertFalse(coordinator.release(first))
        assertEquals(AutomationKind.HUNT, coordinator.activeKind())

        coordinator.release(second)
        assertNull(coordinator.activeKind())
    }
}
