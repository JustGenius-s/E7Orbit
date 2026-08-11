package com.e7orbit.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.e7orbit.R

/**
 * Wiki 卡片的「元素光辉」视觉层。
 *
 * 设计：E7 是火焰红 / 寒气蓝 / 自然绿 / 光明金 / 黑暗紫的强元素游戏，
 * 卡片不再用纯黑 scrim，而是让英雄属性色从立绘背后「晕」出来——
 * 巨大的 M3E 多边形光晕 ([OrbitPolygonShapes.AuraHalo]) + 元素色径向渐变 +
 * 底部元素色 scrim 保证文字可读。元素色是本页唯一的 accent 体系。
 */

/**
 * 卡片背景：E7 风圣约卡面（GPT 生成的金框 + 蓝宝石 + 星座刻线底图），
 * 铺满整张卡片，置于立绘之后。
 */
@Composable
internal fun BoxScope.HeroCardBackdrop(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.e7_card_frame),
        contentDescription = null,
        modifier = modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
    )
}

/** 中性深色压底，保证叠加在卡面上的文字可读。 */
@Composable
internal fun BoxScope.HeroCardScrim() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.40f to Color.Transparent,
                        0.70f to Color.Black.copy(alpha = 0.42f),
                        1f to Color.Black.copy(alpha = 0.88f),
                    ),
                ),
            ),
    )
}


