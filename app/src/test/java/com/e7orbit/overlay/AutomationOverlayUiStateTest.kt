package com.e7orbit.overlay

import com.e7orbit.automation.TaskKind
import com.e7orbit.model.AutomationPhase
import com.e7orbit.model.AutomationStatus
import com.e7orbit.model.HuntPhase
import com.e7orbit.model.HuntStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationOverlayUiStateTest {
    @Test
    fun from_selectsRunningShopTask() {
        val state = AutomationOverlayUiState.from(
            shopStatus = AutomationStatus(phase = AutomationPhase.SCANNING_TOP),
            huntStatus = HuntStatus(),
        )

        assertEquals(OverlayMode.SHOP, state.activeMode)
        assertEquals(TaskKind.SHOP, state.activeTaskKind)
        assertEquals("上扫", state.phaseLabel)
        assertEquals(0.22f, state.progress)
        assertFalse(state.isActiveTerminal)
    }

    @Test
    fun from_prioritizesPausedHuntTask() {
        val state = AutomationOverlayUiState.from(
            shopStatus = AutomationStatus(phase = AutomationPhase.COMPLETED),
            huntStatus = HuntStatus(phase = HuntPhase.PAUSED),
        )

        assertEquals(OverlayMode.HUNT, state.activeMode)
        assertEquals(TaskKind.HUNT, state.activeTaskKind)
        assertEquals("暂停", state.phaseLabel)
        assertTrue(state.isActivePaused)
    }

    @Test
    fun errorState_exposesTerminalAndAccessibilityState() {
        val state = AutomationOverlayUiState.from(
            shopStatus = AutomationStatus(phase = AutomationPhase.ERROR),
            huntStatus = HuntStatus(),
        )

        assertTrue(state.isActiveTerminal)
        assertTrue(state.isActiveError)
        assertTrue(
            state.accessibilityDescription(
                presentation = OverlayPresentation.EDGE,
                dockSide = OverlayDockSide.END,
            ).contains("右侧"),
        )
    }
}
