package com.e7orbit.vision

import com.e7orbit.model.ScreenRect
import kotlin.math.roundToInt

/**
 * Maps the 16:9 template reference space into an arbitrary frame without distortion.
 *
 * The reference canvas is uniformly scaled to fit the frame. Surplus width or height
 * is then assigned according to the region's anchor, so edge UI can follow ultrawide
 * screens while centered dialogs remain centered.
 */
data class VisionGeometry(
    val referenceWidth: Int,
    val referenceHeight: Int,
    val frameWidth: Int,
    val frameHeight: Int,
) {
    init {
        require(referenceWidth > 0 && referenceHeight > 0) {
            "参考尺寸必须大于 0"
        }
        require(frameWidth > 0 && frameHeight > 0) {
            "画面尺寸必须大于 0"
        }
    }

    val scale: Double = minOf(
        frameWidth.toDouble() / referenceWidth,
        frameHeight.toDouble() / referenceHeight,
    )

    private val scaledReferenceWidth = referenceWidth * scale
    private val scaledReferenceHeight = referenceHeight * scale
    private val horizontalSurplus = (frameWidth - scaledReferenceWidth).coerceAtLeast(0.0)
    private val verticalSurplus = (frameHeight - scaledReferenceHeight).coerceAtLeast(0.0)

    fun mapRegion(
        region: RectConfig,
        horizontalAnchor: HorizontalAnchor = HorizontalAnchor.AUTO,
        verticalAnchor: VerticalAnchor = VerticalAnchor.AUTO,
    ): ScreenRect = mapRegion(
        region = region.toScreenRect(),
        horizontalAnchor = horizontalAnchor,
        verticalAnchor = verticalAnchor,
    )

    fun mapRegion(
        region: ScreenRect,
        horizontalAnchor: HorizontalAnchor = HorizontalAnchor.AUTO,
        verticalAnchor: VerticalAnchor = VerticalAnchor.AUTO,
    ): ScreenRect {
        val resolvedHorizontal = horizontalAnchor.resolve(region)
        val resolvedVertical = verticalAnchor.resolve(region)
        val horizontal = mapHorizontal(region, resolvedHorizontal)
        val vertical = mapVertical(region, resolvedVertical)
        val left = horizontal.first.roundToInt().coerceIn(0, frameWidth - 1)
        val top = vertical.first.roundToInt().coerceIn(0, frameHeight - 1)
        val right = horizontal.second.roundToInt().coerceIn(left + 1, frameWidth)
        val bottom = vertical.second.roundToInt().coerceIn(top + 1, frameHeight)
        return ScreenRect(left, top, right, bottom)
    }

    private fun mapHorizontal(
        region: ScreenRect,
        anchor: HorizontalAnchor,
    ): Pair<Double, Double> = when (anchor) {
        HorizontalAnchor.LEFT ->
            region.left * scale to region.right * scale

        HorizontalAnchor.CENTER -> horizontalSurplus / 2.0 + region.left * scale to
            horizontalSurplus / 2.0 + region.right * scale

        HorizontalAnchor.RIGHT -> horizontalSurplus + region.left * scale to
            horizontalSurplus + region.right * scale

        HorizontalAnchor.STRETCH -> region.left * scale to
            frameWidth - (referenceWidth - region.right) * scale

        HorizontalAnchor.AUTO -> error("AUTO anchor must be resolved")
    }

    private fun mapVertical(
        region: ScreenRect,
        anchor: VerticalAnchor,
    ): Pair<Double, Double> = when (anchor) {
        VerticalAnchor.TOP ->
            region.top * scale to region.bottom * scale

        VerticalAnchor.CENTER -> verticalSurplus / 2.0 + region.top * scale to
            verticalSurplus / 2.0 + region.bottom * scale

        VerticalAnchor.BOTTOM -> verticalSurplus + region.top * scale to
            verticalSurplus + region.bottom * scale

        VerticalAnchor.STRETCH -> region.top * scale to
            frameHeight - (referenceHeight - region.bottom) * scale

        VerticalAnchor.AUTO -> error("AUTO anchor must be resolved")
    }

    private fun HorizontalAnchor.resolve(region: ScreenRect): HorizontalAnchor {
        if (this != HorizontalAnchor.AUTO) return this
        if (region.left == 0 && region.right == referenceWidth) {
            return HorizontalAnchor.STRETCH
        }
        return when (region.center.x.toDouble() / referenceWidth) {
            in 0.0..<LEFT_CENTER_BOUNDARY -> HorizontalAnchor.LEFT
            in RIGHT_CENTER_BOUNDARY..1.0 -> HorizontalAnchor.RIGHT
            else -> HorizontalAnchor.CENTER
        }
    }

    private fun VerticalAnchor.resolve(region: ScreenRect): VerticalAnchor {
        if (this != VerticalAnchor.AUTO) return this
        if (region.top == 0 && region.bottom == referenceHeight) {
            return VerticalAnchor.STRETCH
        }
        return when (region.center.y.toDouble() / referenceHeight) {
            in 0.0..<TOP_CENTER_BOUNDARY -> VerticalAnchor.TOP
            in BOTTOM_CENTER_BOUNDARY..1.0 -> VerticalAnchor.BOTTOM
            else -> VerticalAnchor.CENTER
        }
    }

    private companion object {
        const val LEFT_CENTER_BOUNDARY = 0.40
        const val RIGHT_CENTER_BOUNDARY = 0.60
        const val TOP_CENTER_BOUNDARY = 0.40
        const val BOTTOM_CENTER_BOUNDARY = 0.60
    }
}
