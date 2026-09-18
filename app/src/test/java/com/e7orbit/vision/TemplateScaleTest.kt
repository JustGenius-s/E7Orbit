package com.e7orbit.vision

import org.junit.Assert.assertEquals
import org.junit.Test

class TemplateScaleTest {
    @Test
    fun nativeDisplaySizeOverridesTexturePixels() {
        val geometry = VisionGeometry(
            referenceWidth = 1024,
            referenceHeight = 576,
            frameWidth = 1920,
            frameHeight = 1080,
        )
        val template = TemplateConfig(
            id = "covenant_item",
            file = "covenant_item.png",
            region = RectConfig(330, 60, 600, 576),
            threshold = 0.75,
            displayWidth = 52,
            displayHeight = 52,
            matchMode = MatchMode.MASKED,
        )

        assertEquals(1.875, geometry.scale, 0.0001)
        assertEquals(
            (52 * 1.875) / 72.0,
            template.scaleFor(geometry, nativeWidth = 72, nativeHeight = 65),
            0.0001,
        )
    }

    @Test
    fun maskedThresholdIgnoresHigherUserOverride() {
        val template = TemplateConfig(
            id = "mystic_item",
            file = "mystic_item.png",
            region = RectConfig(330, 60, 600, 576),
            threshold = 0.75,
            matchMode = MatchMode.MASKED,
        )

        assertEquals(0.75, template.resolveThreshold(0.92), 0.0001)
        assertEquals(0.75, template.resolveThreshold(null), 0.0001)
        assertEquals(0.70, template.resolveThreshold(0.70), 0.0001)
    }

    @Test
    fun opaqueThresholdUsesMaxWithUserOverride() {
        val template = TemplateConfig(
            id = "purchase_button",
            file = "purchase_button.png",
            region = RectConfig(820, 50, 1024, 576),
            threshold = 0.88,
        )

        assertEquals(0.92, template.resolveThreshold(0.92), 0.0001)
        assertEquals(0.88, template.resolveThreshold(0.80), 0.0001)
        assertEquals(0.88, template.resolveThreshold(null), 0.0001)
    }

    @Test
    fun maskedConfidenceMapsCalibratedHitOntoOpaqueScale() {
        val template = TemplateConfig(
            id = "mystic_item",
            file = "mystic_item.png",
            region = RectConfig(330, 60, 600, 576),
            threshold = 0.75,
            matchMode = MatchMode.MASKED,
        )

        assertEquals(0.92, template.reportConfidence(0.75), 0.0001)
        assertEquals(1.0, template.reportConfidence(1.0), 0.0001)
        assertEquals(0.74, template.reportConfidence(0.74), 0.0001)
    }

    @Test
    fun screenshotTemplatesKeepReferenceScale() {
        val geometry = VisionGeometry(
            referenceWidth = 1024,
            referenceHeight = 576,
            frameWidth = 1280,
            frameHeight = 720,
        )
        val template = TemplateConfig(
            id = "refresh_button",
            file = "refresh_button.png",
            region = RectConfig(0, 430, 330, 576),
            threshold = 0.90,
        )

        assertEquals(geometry.scale, template.scaleFor(geometry, 246, 53), 0.0001)
    }
}

class ShopLayoutMetricsTest {
    @Test
    fun pairToleranceScalesWithSixteenByNineFrame() {
        val geometry = VisionGeometry(
            referenceWidth = 1024,
            referenceHeight = 576,
            frameWidth = 1920,
            frameHeight = 1080,
        )

        assertEquals(55 * 1.875, ShopLayoutMetrics.rowPairTolerance(geometry), 0.0001)
        assertEquals(100 * 1.875, ShopLayoutMetrics.rowBucketHeight(geometry), 0.0001)
    }

    @Test
    fun pairToleranceShrinksOnSmallerLandscapeFrame() {
        val geometry = VisionGeometry(
            referenceWidth = 1024,
            referenceHeight = 576,
            frameWidth = 1280,
            frameHeight = 720,
        )

        assertEquals(55 * geometry.scale, ShopLayoutMetrics.rowPairTolerance(geometry), 0.0001)
    }

    @Test
    fun pairToleranceFollowsFitScaleOnUltrawideFrame() {
        val geometry = VisionGeometry(
            referenceWidth = 1024,
            referenceHeight = 576,
            frameWidth = 2400,
            frameHeight = 1080,
        )

        assertEquals(1.875, geometry.scale, 0.0001)
        assertEquals(55 * 1.875, ShopLayoutMetrics.rowPairTolerance(geometry), 0.0001)
    }
}
