package com.e7orbit.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistedDraftTest {
    @Test
    fun `unrelated persisted emission does not overwrite pending local value`() {
        val draft = PersistedDraft("saved")

        assertTrue(draft.update("local"))
        draft.acceptPersisted("saved")

        assertEquals("local", draft.value)
    }

    @Test
    fun `matching persisted value completes pending write and allows later updates`() {
        val draft = PersistedDraft("saved")

        assertTrue(draft.update("local"))
        draft.acceptPersisted("local")
        draft.acceptPersisted("external")

        assertEquals("external", draft.value)
        assertFalse(draft.update("external"))
    }

    @Test
    fun `failed latest write rolls back to the newest persisted value`() {
        val draft = PersistedDraft("saved")

        draft.update("local")
        draft.acceptPersisted("external")
        draft.rejectPending("local")

        assertEquals("external", draft.value)
    }

    @Test
    fun `failure from an older write does not roll back a newer draft`() {
        val draft = PersistedDraft("saved")

        draft.update("first")
        draft.update("second")
        draft.rejectPending("first")

        assertEquals("second", draft.value)
    }

    @Test
    fun `successful no-op write clears matching pending value without flow echo`() {
        val draft = PersistedDraft("saved")

        draft.update("temporary")
        draft.update("saved")
        draft.acceptPersisted("saved")
        draft.rejectPending("temporary")
        draft.acceptPersisted("external")

        assertEquals("external", draft.value)
    }
}
