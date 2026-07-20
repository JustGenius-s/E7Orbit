package com.e7orbit.vision

import com.e7orbit.model.ScreenRect
import org.junit.Assert.assertEquals
import org.junit.Test

class VisionGeometryTest {
    @Test
    fun scalesReferenceRegionUniformlyOnSixteenByNineFrame() {
        val geometry = VisionGeometry(
            referenceWidth = 1024,
            referenceHeight = 576,
            frameWidth = 1920,
            frameHeight = 1080,
        )

        assertEquals(1.875, geometry.scale, 0.0001)
        assertEquals(
            ScreenRect(188, 188, 375, 375),
            geometry.mapRegion(ScreenRect(100, 100, 200, 200)),
        )
    }

    @Test
    fun anchorsLeftCenterAndRightRegionsOnUltrawideFrame() {
        val geometry = VisionGeometry(
            referenceWidth = 1024,
            referenceHeight = 576,
            frameWidth = 2400,
            frameHeight = 1080,
        )
        val region = ScreenRect(100, 100, 200, 200)

        assertEquals(
            ScreenRect(188, 188, 375, 375),
            geometry.mapRegion(region, HorizontalAnchor.LEFT, VerticalAnchor.TOP),
        )
        assertEquals(
            ScreenRect(428, 188, 615, 375),
            geometry.mapRegion(region, HorizontalAnchor.CENTER, VerticalAnchor.TOP),
        )
        assertEquals(
            ScreenRect(668, 188, 855, 375),
            geometry.mapRegion(region, HorizontalAnchor.RIGHT, VerticalAnchor.TOP),
        )
    }

    @Test
    fun autoAnchorKeepsEdgeControlsAtTheirScreenEdges() {
        val geometry = VisionGeometry(
            referenceWidth = 1024,
            referenceHeight = 576,
            frameWidth = 2400,
            frameHeight = 1080,
        )

        assertEquals(
            ScreenRect(0, 356, 244, 619),
            geometry.mapRegion(ScreenRect(0, 190, 130, 330)),
        )
        assertEquals(
            ScreenRect(2168, 356, 2400, 619),
            geometry.mapRegion(ScreenRect(900, 190, 1024, 330)),
        )
    }

    @Test
    fun centersReferenceCanvasWhenFrameHasVerticalSurplus() {
        val geometry = VisionGeometry(
            referenceWidth = 1024,
            referenceHeight = 576,
            frameWidth = 1920,
            frameHeight = 1200,
        )

        assertEquals(
            ScreenRect(563, 360, 1313, 735),
            geometry.mapRegion(
                region = ScreenRect(300, 160, 700, 360),
                horizontalAnchor = HorizontalAnchor.CENTER,
                verticalAnchor = VerticalAnchor.CENTER,
            ),
        )
    }
}
