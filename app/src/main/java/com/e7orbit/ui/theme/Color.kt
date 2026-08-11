package com.e7orbit.ui.theme

import androidx.compose.ui.graphics.Color

// Material Theme Builder: neutral monochrome light scheme.
internal val WhitePrimary = Color(0xFF1B1B1B)
internal val WhiteOnPrimary = Color(0xFFFFFFFF)
internal val WhitePrimaryContainer = Color(0xFFF0F0F0)
internal val WhiteOnPrimaryContainer = Color(0xFF1B1B1B)
internal val WhiteInversePrimary = Color(0xFFC7C7C7)
internal val WhiteSecondary = Color(0xFF4F4F4F)
internal val WhiteOnSecondary = Color(0xFFFFFFFF)
internal val WhiteSecondaryContainer = Color(0xFFF1F1F1)
internal val WhiteOnSecondaryContainer = Color(0xFF1C1C1C)
internal val WhiteTertiary = Color(0xFF3F3F3F)
internal val WhiteOnTertiary = Color(0xFFFFFFFF)
internal val WhiteTertiaryContainer = Color(0xFFEDEDED)
internal val WhiteOnTertiaryContainer = Color(0xFF1A1A1A)
internal val WhiteError = Color(0xFFBA1A1A)
internal val WhiteOnError = Color(0xFFFFFFFF)
internal val WhiteErrorContainer = Color(0xFFFFDAD6)
internal val WhiteOnErrorContainer = Color(0xFF410002)
internal val WhiteBackground = Color(0xFFFFFFFF)
internal val WhiteOnBackground = Color(0xFF1B1B1B)
internal val WhiteSurface = Color(0xFFFFFFFF)
internal val WhiteOnSurface = Color(0xFF1B1B1B)
internal val WhiteSurfaceVariant = Color(0xFFF3F3F3)
internal val WhiteOnSurfaceVariant = Color(0xFF5F5F5F)
internal val WhiteOutline = Color(0xFF767676)
internal val WhiteOutlineVariant = Color(0xFFDEDEDE)
internal val WhiteScrim = Color(0xFF000000)
internal val WhiteInverseSurface = Color(0xFF303030)
internal val WhiteInverseOnSurface = Color(0xFFF4F4F4)
internal val WhiteSurfaceDim = Color(0xFFE2E2E2)
internal val WhiteSurfaceBright = Color(0xFFFFFFFF)
internal val WhiteSurfaceContainerLowest = Color(0xFFFFFFFF)
internal val WhiteSurfaceContainerLow = Color(0xFFFAFAFA)
internal val WhiteSurfaceContainer = Color(0xFFF7F7F7)
internal val WhiteSurfaceContainerHigh = Color(0xFFF1F1F1)
internal val WhiteSurfaceContainerHighest = Color(0xFFEBEBEB)

val OrbitSuccess = Color(0xFF247A52)
val OrbitWarning = Color(0xFF8A5A00)
val OrbitArtifactHighlight = Color(0xFFC2410C)

// ── E7 元素色板（仅用于 Wiki/英雄相关，是这一页的 accent 体系）────────────
// 主色直接采样自官方元素图标（res/drawable/e7_element_*.png）的高饱和像素，
// 深色由主色在 HSL 空间降明度推导，保证与游戏内元素色一致。卡片光晕/scrim 用 alpha 叠加。
object OrbitElementColors {
    /** 火焰：官方图标采样 #CE322B。 */
    val Fire = Color(0xFFCE322B)
    val FireDeep = Color(0xFF7E1E1A)

    /** 寒气：官方图标采样 #179BF4。 */
    val Ice = Color(0xFF179BF4)
    val IceDeep = Color(0xFF065A92)

    /** 自然：官方图标采样 #4FB71B（偏黄绿，不是纯翠绿）。 */
    val Earth = Color(0xFF4FB71B)
    val EarthDeep = Color(0xFF398513)

    /** 光明：官方图标采样 #DBA817（暖金）。 */
    val Light = Color(0xFFDBA817)
    val LightDeep = Color(0xFF8A6A0E)

    /** 黑暗：官方图标采样 #AA42AC（洋红/品红，不是紫罗兰）。 */
    val Dark = Color(0xFFAA42AC)
    val DarkDeep = Color(0xFF6D2A6E)
}

/** 按 E7 属性返回 [主色, 深色]，用于卡面渐变/光晕/徽标着色。 */
fun elementColorsFor(attribute: String): Pair<Color, Color> =
    when (attribute.lowercase()) {
        "fire" -> OrbitElementColors.Fire to OrbitElementColors.FireDeep
        "ice", "water" -> OrbitElementColors.Ice to OrbitElementColors.IceDeep
        "earth", "wind" -> OrbitElementColors.Earth to OrbitElementColors.EarthDeep
        "light" -> OrbitElementColors.Light to OrbitElementColors.LightDeep
        "dark" -> OrbitElementColors.Dark to OrbitElementColors.DarkDeep
        else -> OrbitElementColors.Ice to OrbitElementColors.IceDeep // 中性回退
    }
