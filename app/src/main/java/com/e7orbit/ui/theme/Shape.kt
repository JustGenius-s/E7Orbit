package com.e7orbit.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * M3 圆角形状阶梯（用于 Card / Surface / Button 等容器）。
 *
 * M3 Expressive 的「多边形形状」（Cookie / Gem / Sunny / Heart 等 35 种
 * 以及形状间 Morph 形变）不在此阶梯内，统一由 [OrbitPolygonShapes] /
 * [rememberMorphingShape]（见 ShapeMorphs.kt）提供，通过 `.asShape` /
 * `rememberPolygonShape()` 转成 Compose Shape 后用于 `clip` / `background` /
 * `Surface(shape = …)`。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
val OrbitShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    largeIncreased = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
    extraLargeIncreased = RoundedCornerShape(32.dp),
    extraExtraLarge = RoundedCornerShape(48.dp),
)
