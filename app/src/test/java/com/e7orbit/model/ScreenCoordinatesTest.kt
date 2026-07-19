package com.e7orbit.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenCoordinatesTest {
    @Test
    fun convertsReferenceCoordinatesToDeviceCoordinates() {
        val point = ReferencePoint(
            x = 512,
            y = 288,
            referenceWidth = 1024,
            referenceHeight = 576,
        )

        assertEquals(
            DevicePoint(960, 540),
            point.toDevicePoint(deviceWidth = 1920, deviceHeight = 1080),
        )
    }

    @Test
    fun devicePointExplicitlyBridgesToLegacyScreenPoint() {
        assertEquals(
            ScreenPoint(25, 50),
            DevicePoint(25, 50).toScreenPoint(),
        )
    }
}
