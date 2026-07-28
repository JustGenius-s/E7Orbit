package com.e7orbit.overlay

internal enum class OverlayDockSide {
    START,
    END,
}

internal data class OverlayAvailableArea(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(right >= left)
        require(bottom >= top)
    }
}

internal data class OverlayPosition(
    val x: Int,
    val y: Int,
)

internal fun nearestDockSide(
    windowX: Int,
    windowWidth: Int,
    area: OverlayAvailableArea,
): OverlayDockSide {
    val windowCenter = windowX + windowWidth / 2
    val areaCenter = area.left + (area.right - area.left) / 2
    return if (windowCenter <= areaCenter) OverlayDockSide.START else OverlayDockSide.END
}

internal fun dockedX(
    side: OverlayDockSide,
    windowWidth: Int,
    area: OverlayAvailableArea,
): Int = when (side) {
    OverlayDockSide.START -> area.left
    OverlayDockSide.END -> (area.right - windowWidth).coerceAtLeast(area.left)
}

internal fun edgeTouchX(
    side: OverlayDockSide,
    visualWindowX: Int,
    visualWindowWidth: Int,
    touchWindowWidth: Int,
): Int = when (side) {
    OverlayDockSide.START -> visualWindowX
    OverlayDockSide.END -> visualWindowX + visualWindowWidth - touchWindowWidth
}

internal fun clampOverlayPosition(
    x: Int,
    y: Int,
    windowWidth: Int,
    windowHeight: Int,
    area: OverlayAvailableArea,
    margin: Int,
): OverlayPosition {
    val minX = area.left + margin
    val maxX = (area.right - windowWidth - margin).coerceAtLeast(minX)
    val minY = area.top + margin
    val maxY = (area.bottom - windowHeight - margin).coerceAtLeast(minY)
    return OverlayPosition(
        x = x.coerceIn(minX, maxX),
        y = y.coerceIn(minY, maxY),
    )
}
