package com.e7orbit.model

import kotlin.math.roundToInt

/** A point whose coordinates already match the current device frame. */
data class DevicePoint(
    val x: Int,
    val y: Int,
) {
    fun toScreenPoint(): ScreenPoint = ScreenPoint(x, y)

    companion object {
        fun from(point: ScreenPoint): DevicePoint = DevicePoint(point.x, point.y)
    }
}

/** A point expressed in an explicitly named reference coordinate space. */
data class ReferencePoint(
    val x: Int,
    val y: Int,
    val referenceWidth: Int,
    val referenceHeight: Int,
) {
    init {
        require(referenceWidth > 0) { "referenceWidth 必须大于 0" }
        require(referenceHeight > 0) { "referenceHeight 必须大于 0" }
    }

    fun toDevicePoint(
        deviceWidth: Int,
        deviceHeight: Int,
    ): DevicePoint {
        require(deviceWidth > 0) { "deviceWidth 必须大于 0" }
        require(deviceHeight > 0) { "deviceHeight 必须大于 0" }
        return DevicePoint(
            x = (x.toDouble() / referenceWidth * deviceWidth).roundToInt(),
            y = (y.toDouble() / referenceHeight * deviceHeight).roundToInt(),
        )
    }
}

/** A point expressed as a fraction of the current screen dimensions. */
data class ScreenRatioPoint(
    val xRatio: Double,
    val yRatio: Double,
) {
    init {
        require(xRatio in 0.0..1.0) { "xRatio 必须在 0 到 1 之间" }
        require(yRatio in 0.0..1.0) { "yRatio 必须在 0 到 1 之间" }
    }

    fun toScreenPoint(
        width: Int,
        height: Int,
    ): ScreenPoint {
        require(width > 0) { "width 必须大于 0" }
        require(height > 0) { "height 必须大于 0" }
        return ScreenPoint(
            x = (width * xRatio).roundToInt(),
            y = (height * yRatio).roundToInt(),
        )
    }
}
