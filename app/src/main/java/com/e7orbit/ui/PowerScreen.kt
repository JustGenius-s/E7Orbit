package com.e7orbit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.e7orbit.data.BailiPower
import com.e7orbit.data.E7Gear
import com.e7orbit.optimizer.GearOptimizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 百里战力页面（对齐 e7bot.top/gs 网页版样式）。
 */
internal fun LazyListScope.powerScreenItems(
    gears: List<E7Gear>,
    heroNames: Map<Long, String>,
    importedAtEpochMs: Long,
) {
    item(key = "power-content") {
        PowerContent(
            gears = gears,
            heroNames = heroNames,
            importedAtEpochMs = importedAtEpochMs,
        )
    }
}

@Composable
private fun PowerContent(
    gears: List<E7Gear>,
    heroNames: Map<Long, String>,
    importedAtEpochMs: Long,
) {
    val result = remember(gears) { BailiPower.evaluate(gears) }
    var selectedCategory by rememberSaveable {
        mutableStateOf(BailiPower.Category.DPS.name)
    }
    val category = BailiPower.Category.valueOf(selectedCategory)

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 主卡片：对齐网页版
        MainPowerCard(result = result, updatedAt = importedAtEpochMs)

        // 统计格子
        StatsGrid(stats = result.stats)

        // 分类明细
        Text(
            text = "分类明细",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        CategoryChipsRow(
            result = result,
            selected = category,
            onSelected = { selectedCategory = it.name },
        )

        val itemsInCategory = remember(result, selectedCategory) {
            result.items
                .filter { it.category == category && it.points > 0.0 }
                .sortedByDescending(BailiPower.Scored::points)
        }
        if (itemsInCategory.isEmpty()) {
            EmptyCategoryHint(category = category)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsInCategory.forEach { scored ->
                    PowerGearRow(scored = scored, heroNames = heroNames)
                }
            }
        }

        // 未来可期单独展示
        val stashItems = remember(result) {
            result.items
                .filter { it.category == BailiPower.Category.STASH && it.points > 0.0 }
                .sortedByDescending(BailiPower.Scored::points)
        }
        if (stashItems.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "未来可期（装等 75+ 未入选主类）",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                stashItems.forEach { scored ->
                    PowerGearRow(scored = scored, heroNames = heroNames, dimmed = true)
                }
            }
        }
    }
}

// ---------------------------------------------------------------
// 主卡片：总战力大数字 + 雷达图（对齐网页版）
// ---------------------------------------------------------------

@Composable
private fun MainPowerCard(result: BailiPower.Result, updatedAt: Long) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 总战力大数字（多层立体描边）
                GradientPowerNumber(total = result.total)

                Spacer(Modifier.height(2.dp))

                // 装饰分隔：横线 + 交叉剑图标 + 横线（对齐网页版）
                SwordDivider()

                Spacer(Modifier.height(4.dp))

                // 「百里战力v5.0」
                Text(
                    text = "百里战力v5.0",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF333333),
                    letterSpacing = 1.sp,
                )

                Spacer(Modifier.height(8.dp))

                // 雷达图
                val values = listOf(
                    "一速" to (result.byCategory[BailiPower.Category.FIRST_SPEED] ?: 0.0),
                    "速度" to (result.byCategory[BailiPower.Category.SPEED] ?: 0.0),
                    "输出" to (result.byCategory[BailiPower.Category.DPS] ?: 0.0),
                    "坦克" to (result.byCategory[BailiPower.Category.TANK] ?: 0.0),
                    "双效" to (result.byCategory[BailiPower.Category.DUAL_EFFECT] ?: 0.0),
                    "半肉" to (result.byCategory[BailiPower.Category.HYBRID] ?: 0.0),
                )
                PowerRadarChart(
                    values = values,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.0f)
                        .padding(horizontal = 16.dp),
                )
            }

            // 右上角更新时间
            if (updatedAt > 0) {
                val formatter = remember(updatedAt) {
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                }
                Text(
                    text = "更新时间: ${formatter.format(Date(updatedAt))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF88888888),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 12.dp, end = 12.dp),
                )
            }

            // 左上角速度勋章
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(
                    com.e7orbit.R.drawable.e7_power_emblem,
                ),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 120.dp, start = 16.dp)
                    .size(64.dp),
            )
        }
    }
}

/**
 * 装饰分隔：左横线 - 交叉剑 - 右横线（对齐网页版）。
 */
@Composable
private fun SwordDivider() {
    androidx.compose.foundation.Canvas(
        modifier = Modifier
            .width(220.dp)
            .height(16.dp),
    ) {
        val cy = size.height / 2f
        val lineColor = Color(0xFF999999)
        val swordColor = Color(0xFF666666)

        // 左横线
        drawLine(
            color = lineColor,
            start = Offset(0f, cy),
            end = Offset(size.width * 0.38f, cy),
            strokeWidth = 1.5f,
        )
        // 右横线
        drawLine(
            color = lineColor,
            start = Offset(size.width * 0.62f, cy),
            end = Offset(size.width, cy),
            strokeWidth = 1.5f,
        )

        // 交叉剑（X 形）
        val cx = size.width / 2f
        val swordLen = 10.dp.toPx()
        // 剑 1: 从左上到右下
        drawLine(
            color = swordColor,
            start = Offset(cx - swordLen, cy - swordLen * 0.7f),
            end = Offset(cx + swordLen, cy + swordLen * 0.7f),
            strokeWidth = 2.5f,
        )
        // 剑 2: 从右上到左下
        drawLine(
            color = swordColor,
            start = Offset(cx + swordLen, cy - swordLen * 0.7f),
            end = Offset(cx - swordLen, cy + swordLen * 0.7f),
            strokeWidth = 2.5f,
        )
        // 剑钉饰（中心两个小圆点）
        drawCircle(color = swordColor, radius = 2.5f, center = Offset(cx - 4.dp.toPx(), cy + 5.dp.toPx()))
        drawCircle(color = swordColor, radius = 2.5f, center = Offset(cx + 4.dp.toPx(), cy + 5.dp.toPx()))
    }
}

/**
 * 百里战力总分数数字（对齐 e7bot.top 网页版的多层立体描边字）。
 *
 * 参考图是分 4 层绘制：
 *   1. 外层深棕红描边（最粗，提供轮廓）
 *   2. 中层亮红描边（制造立体感）
 *   3. 内层金色渐变（从顶到底：亮金→橙金→橙红）
 *   4. 顶部亮金高光
 */
@Composable
private fun GradientPowerNumber(total: Double) {
    val formatted = "%,d".format(total.roundToInt())
    androidx.compose.foundation.Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(90.dp),
    ) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val textSizePx = 64.sp.toPx()

        // 共同 Paint
        val basePaint = android.graphics.Paint().apply {
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = textSizePx
            // 使用 SANS_SERIF + BOLD_ITALIC 更接近游戏感
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.SANS_SERIF,
                android.graphics.Typeface.BOLD_ITALIC,
            )
        }

        // 第 1 层：深棕红外描边（最粗）
        val outlinePaint = android.graphics.Paint(basePaint).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = textSizePx * 0.16f
            color = android.graphics.Color.parseColor("#7B1F0A")
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        drawContext.canvas.nativeCanvas.drawText(
            formatted, centerX, centerY + textSizePx * 0.35f, outlinePaint,
        )

        // 第 2 层：亮红描边（中等粗细）
        val midPaint = android.graphics.Paint(basePaint).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = textSizePx * 0.09f
            color = android.graphics.Color.parseColor("#E63946")
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        drawContext.canvas.nativeCanvas.drawText(
            formatted, centerX, centerY + textSizePx * 0.35f, midPaint,
        )

        // 第 3 层：金色渐变填充
        val gradientPaint = android.graphics.Paint(basePaint).apply {
            style = android.graphics.Paint.Style.FILL
            shader = android.graphics.LinearGradient(
                0f, centerY - textSizePx * 0.5f,
                0f, centerY + textSizePx * 0.4f,
                intArrayOf(
                    android.graphics.Color.parseColor("#FFEB3B"),  // 顶部亮金
                    android.graphics.Color.parseColor("#FFC107"),  // 中黄金
                    android.graphics.Color.parseColor("#FF9800"),  // 橙金
                    android.graphics.Color.parseColor("#FF5722"),  // 底部橙红
                ),
                floatArrayOf(0f, 0.4f, 0.75f, 1f),
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
        drawContext.canvas.nativeCanvas.drawText(
            formatted, centerX, centerY + textSizePx * 0.35f, gradientPaint,
        )

        // 第 4 层：顶部亮金高光（在上半部分加一层半透明白色渐变）
        val highlightPaint = android.graphics.Paint(basePaint).apply {
            style = android.graphics.Paint.Style.FILL
            shader = android.graphics.LinearGradient(
                0f, centerY - textSizePx * 0.5f,
                0f, centerY + textSizePx * 0.05f,
                intArrayOf(
                    android.graphics.Color.parseColor("#B3FFFFFF"),  // 70% 白
                    android.graphics.Color.parseColor("#00FFFFFF"),  // 透明
                ),
                null,
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
        drawContext.canvas.nativeCanvas.drawText(
            formatted, centerX, centerY + textSizePx * 0.35f, highlightPaint,
        )
    }
}

// ---------------------------------------------------------------
// 统计格子（对齐网页版 2x5）
// ---------------------------------------------------------------

@Composable
private fun StatsGrid(stats: BailiPower.Stats) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatCell("90/88装", stats.gear88or90.toString(), Modifier.weight(1f))
                StatCell("75+装备", stats.gear75Plus.toString(), Modifier.weight(1f))
                StatCell("70+装备", stats.gear70Plus.toString(), Modifier.weight(1f))
                StatCell("重铸75+", stats.reforge75Plus.toString(), Modifier.weight(1f))
                StatCell("重铸70+", stats.reforge70Plus.toString(), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatCell("25+速", formatSpeedPair(stats.speed25), Modifier.weight(1f))
                StatCell("22+速", formatSpeedPair(stats.speed22), Modifier.weight(1f))
                StatCell("20+速", formatSpeedPair(stats.speed20), Modifier.weight(1f))
                StatCell("18+速", formatSpeedPair(stats.speed18), Modifier.weight(1f))
                StatCell("15+速", formatSpeedPair(stats.speed15), Modifier.weight(1f))
            }
            Text(
                text = "注：速度装为速度套/总",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF999999),
            )
        }
    }
}

private fun formatSpeedPair(pair: Pair<Int, Int>): String = "${pair.first}/${pair.second}"

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFFF5F5F5))
            .padding(vertical = 8.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF777777),
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF222222),
            textAlign = TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------
// 分类明细
// ---------------------------------------------------------------

@Composable
private fun CategoryChipsRow(
    result: BailiPower.Result,
    selected: BailiPower.Category,
    onSelected: (BailiPower.Category) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(BailiPower.Category.entries.toList(), key = { it.name }) { category ->
            val score = result.byCategory[category] ?: 0.0
            FilterChip(
                selected = selected == category,
                onClick = { onSelected(category) },
                label = {
                    Text("${category.label} ${score.roundToInt()}")
                },
            )
        }
    }
}

@Composable
private fun EmptyCategoryHint(category: BailiPower.Category) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "「${category.label}」分类暂无入选装备",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PowerGearRow(
    scored: BailiPower.Scored,
    heroNames: Map<Long, String>,
    dimmed: Boolean = false,
) {
    val gear = scored.gear
    val alpha = if (dimmed) 0.6f else 1f
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GearSlotAsset(
                slot = gear.slot,
                rank = gear.rank,
                gearCode = gear.code,
                enhancement = gear.enhance,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = gear.setName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = gear.rank,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = buildString {
                        append(gear.slot.label)
                        append(" · ")
                        append(gear.mainStat.label)
                        append(" ")
                        append(gear.mainStat.displayValue())
                        gear.equippedHeroId?.let { id ->
                            heroNames[id]?.let { name ->
                                append(" · ")
                                append(name)
                            }
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = scored.points.roundToInt().toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                )
                Text(
                    text = "装等 ${scored.totalGs.takeIf { it > 0 } ?: GearOptimizer.gearScore(gear)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                )
            }
        }
    }
}

// ------------------------------------------------------------
// Preview
// ------------------------------------------------------------

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, widthDp = 400)
@Composable
private fun PowerMainCardPreview() {
    val fakeResult = BailiPower.Result(
        total = 2614.0,
        byCategory = mapOf(
            BailiPower.Category.FIRST_SPEED to 235.0,
            BailiPower.Category.SPEED to 321.0,
            BailiPower.Category.DPS to 365.0,
            BailiPower.Category.TANK to 689.0,
            BailiPower.Category.DUAL_EFFECT to 561.0,
            BailiPower.Category.HYBRID to 377.0,
        ),
        items = emptyList(),
        stashCount = 0,
        stats = BailiPower.Stats(
            gear88or90 = 1284, gear75Plus = 157, gear70Plus = 615,
            reforge75Plus = 12, reforge70Plus = 60,
            speed25 = 1 to 3, speed22 = 7 to 22, speed20 = 22 to 67,
            speed18 = 36 to 127, speed15 = 93 to 251,
        ),
    )
    MaterialTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            MainPowerCard(result = fakeResult, updatedAt = System.currentTimeMillis())
        }
    }
}
