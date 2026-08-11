package com.e7orbit.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toPath
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon

/**
 * M3 Expressive 多边形形状库（Material 形状体系的接入层）。
 *
 * 底层是 material3 内置的 [MaterialShapes]（35 种 Cookie / Burst / Sunny /
 * Heart / Clover 等多边形，基于 androidx.graphics:graphics-shapes），
 * 通过 [toShape] 转成 Compose [Shape] 后即可用于 `Modifier.clip()` /
 * `Modifier.background()` / `Surface(shape = …)` 等任意需要 Shape 的地方。
 *
 * 此外提供 [rememberMorphingShape] 做两个多边形之间的平滑形变动画
 * （M3 Expressive 的招牌能力，例如按下时从圆形“长出棱角”变成齿轮）。
 */

/** 项目语义化形状。想换造型只改这里，调用方不用动。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
object OrbitPolygonShapes {
    /** 英雄头像：饼干形，柔化的多边形，适合框住人像。 */
    val HeroAvatar: RoundedPolygon = MaterialShapes.Cookie9Sided

    /** 刻印档位选中态：七边形饼干，包裹选中的档位徽标。 */
    val ImprintRankBadge: RoundedPolygon = MaterialShapes.Cookie7Sided

    /** 悬浮状态球：宝石形（区别于普通圆形控件）。 */
    val StatusOrb: RoundedPolygon = MaterialShapes.Gem

    /** 运行状态指示点：小太阳/齿轮形，暗示“运转中”。 */
    val StatusDot: RoundedPolygon = MaterialShapes.Sunny

    /** 强调徽标：柔和花瓣形。 */
    val EmphasisBadge: RoundedPolygon = MaterialShapes.SoftBurst

    /** 爱心：收藏/点赞类。 */
    val Favorite: RoundedPolygon = MaterialShapes.Heart

    /** 四叶草：幸运/抽取类。 */
    val Lucky: RoundedPolygon = MaterialShapes.Clover4Leaf

    /** 卡片背景光晕：柔和放射花瓣，置于立绘后方按元素着色。 */
    val AuraHalo: RoundedPolygon = MaterialShapes.SoftBurst
}

/**
 * 把 [RoundedPolygon] 转成 Compose [Shape]。
 *
 * 注意：[toShape] 本身就是 @Composable 且内部已 remember，这里直接透传，
 * 让它参与调用方的 Composition 以正确缓存。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun rememberPolygonShape(polygon: RoundedPolygon): Shape = polygon.toShape()

/**
 * 便捷属性：`MaterialShapes.Gem.asShape` 即可作为 [Shape] 使用。
 * 内部走 [rememberPolygonShape]（即 [toShape]），需在 @Composable 作用域调用。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
val RoundedPolygon.asShape: Shape
    @Composable get() = rememberPolygonShape(this)

/**
 * 记住一个在 [start] 与 [end] 两个多边形之间形变的 [Shape]。
 *
 * @param progress 0f = 完全是 [start]，1f = 完全是 [end]；中间值平滑过渡。
 * 可配合 [androidx.compose.animation.core.animateFloatAsState] / 手势进度驱动。
 */
@Composable
fun rememberMorphingShape(
    start: RoundedPolygon,
    end: RoundedPolygon,
    progress: Float,
): Shape {
    val morph = remember(start, end) { Morph(start, end) }
    return remember(morph) { MorphShape(morph) }.also {
        it.progress = progress.coerceIn(0f, 1f)
    }
}

/**
 * 记住一个自动做形变动画的 [MorphShapeController]。
 *
 * 用法：
 * ```
 * val morph = rememberAnimatedMorphShape(Circle, Sunny)
 * // 触发：scope.launch { morph.animateTo(1f) }  / morph.animateTo(0f)
 * Box(Modifier.clip(morph.shape) ...)
 * ```
 */
@Composable
fun rememberAnimatedMorphShape(
    start: RoundedPolygon,
    end: RoundedPolygon,
    animationSpec: AnimationSpec<Float> = spring(dampingRatio = 0.6f, stiffness = 300f),
): MorphShapeController {
    val morph = remember(start, end) { Morph(start, end) }
    val shape = remember(morph) { MorphShape(morph) }
    val animatable = remember(morph) { Animatable(0f) }
    return remember(morph, animationSpec) {
        MorphShapeController(shape, animatable, animationSpec)
    }
}

class MorphShapeController internal constructor(
    val shape: MorphShape,
    private val animatable: Animatable<Float, *>,
    private val animationSpec: AnimationSpec<Float>,
) {
    val progress: Float get() = animatable.value

    suspend fun animateTo(target: Float) {
        animatable.animateTo(target.coerceIn(0f, 1f), animationSpec) {
            shape.progress = value.coerceIn(0f, 1f)
        }
    }

    suspend fun snapTo(value: Float) {
        val v = value.coerceIn(0f, 1f)
        animatable.snapTo(v)
        shape.progress = v
    }
}

/**
 * 由 [Morph] 驱动的 Compose [Shape]。改变 [progress] 即可让
 * 使用它的 `clip`/`background` 重绘为中间形态。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class MorphShape(
    private val morph: Morph,
    initialProgress: Float = 0f,
) : Shape {
    var progress: Float = initialProgress.coerceIn(0f, 1f)

    private val matrix = Matrix()
    private var lastSize = Size.Unspecified
    private var lastProgress = -1f
    private val reusablePath: Path = Path()

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        if (size != lastSize || progress != lastProgress) {
            // graphics-shapes 的多边形归一化在 1x1 空间，缩放到目标尺寸。
            matrix.reset()
            matrix.scale(size.width, size.height)
            reusablePath.rewind()
            morph.toPath(progress, reusablePath)
            reusablePath.transform(matrix)
            lastSize = size
            lastProgress = progress
        }
        return Outline.Generic(reusablePath)
    }
}

/** 常用预设：圆形 ↔ 小太阳齿轮（适合运行状态/按下反馈）。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
object OrbitMorphPresets {
    val CircleToSunny: Pair<RoundedPolygon, RoundedPolygon> =
        MaterialShapes.Circle to MaterialShapes.Sunny

    /** 圆形 ↔ 宝石（适合悬浮球激活态切换）。 */
    val CircleToGem: Pair<RoundedPolygon, RoundedPolygon> =
        MaterialShapes.Circle to MaterialShapes.Gem
}
