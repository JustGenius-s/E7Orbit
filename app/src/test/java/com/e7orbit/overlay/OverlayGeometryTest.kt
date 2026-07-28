package com.e7orbit.overlay

import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayGeometryTest {
    private val area = OverlayAvailableArea(
        left = 24,
        top = 20,
        right = 1_896,
        bottom = 1_060,
    )

    @Test
    fun overlayWindow_isExcludedFromScreenCapture() {
        assertTrue(
            AUTOMATION_OVERLAY_WINDOW_FLAGS and
                WindowManager.LayoutParams.FLAG_SECURE != 0,
        )
    }

    @Test
    fun nearestDockSide_usesWindowCenter() {
        assertEquals(
            OverlayDockSide.START,
            nearestDockSide(windowX = 700, windowWidth = 200, area = area),
        )
        assertEquals(
            OverlayDockSide.END,
            nearestDockSide(windowX = 1_000, windowWidth = 200, area = area),
        )
    }

    @Test
    fun dockedX_placesWindowFlushWithAvailableEdge() {
        assertEquals(24, dockedX(OverlayDockSide.START, windowWidth = 28, area = area))
        assertEquals(1_868, dockedX(OverlayDockSide.END, windowWidth = 28, area = area))
    }

    @Test
    fun endDock_keepsTheSameScreenEdgeWhileWidthChanges() {
        listOf(28, 44, 60, 72).forEach { width ->
            assertEquals(area.right, dockedX(OverlayDockSide.END, width, area) + width)
        }
    }

    @Test
    fun edgeTouchWindow_staysInsideTheVisibleDockedEdge() {
        assertEquals(
            24,
            edgeTouchX(
                side = OverlayDockSide.START,
                visualWindowX = 24,
                visualWindowWidth = 72,
                touchWindowWidth = 28,
            ),
        )
        assertEquals(
            1_868,
            edgeTouchX(
                side = OverlayDockSide.END,
                visualWindowX = 1_824,
                visualWindowWidth = 72,
                touchWindowWidth = 28,
            ),
        )
    }

    @Test
    fun clampOverlayPosition_respectsInsetsAndMargin() {
        assertEquals(
            OverlayPosition(x = 32, y = 28),
            clampOverlayPosition(
                x = -200,
                y = -100,
                windowWidth = 480,
                windowHeight = 72,
                area = area,
                margin = 8,
            ),
        )
        assertEquals(
            OverlayPosition(x = 1_408, y = 980),
            clampOverlayPosition(
                x = 1_900,
                y = 1_100,
                windowWidth = 480,
                windowHeight = 72,
                area = area,
                margin = 8,
            ),
        )
    }

    @Test
    fun narrowArea_stillReturnsStablePosition() {
        val narrowArea = OverlayAvailableArea(left = 0, top = 0, right = 320, bottom = 160)

        assertEquals(
            OverlayPosition(x = 8, y = 80),
            clampOverlayPosition(
                x = 120,
                y = 120,
                windowWidth = 480,
                windowHeight = 72,
                area = narrowArea,
                margin = 8,
            ),
        )
    }
}
