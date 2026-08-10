package com.e7orbit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 六边形雷达图（对齐 e7bot.top/gs 网页版样式）。
 *
 * 关键视觉特征（参考网页版）：
 * - 网格是**同心圆**（不是多边形），最外圈浅灰
 * - 数据多边形浅蓝描边 + 浅蓝半透明填充
 * - 顶点白色填充 + 蓝色描边的小圆点
 * - 类别标签在六个轴外侧（黑色，较大字号）
 * - 数值标签紧跟在顶点外侧（黑色，较小字号）
 * - 刻度数字沿"一速"轴（右方向）水平排列，0 在圆心
 * - 轴顺序：右=一速，右上=速度，左上=输出，左=坦克，左下=双效，右下=半肉
 */
@Composable
internal fun PowerRadarChart(
    values: List<Pair<String, Double>>,
    modifier: Modifier = Modifier,
    lineColor: Color = Color(0xFF7FB3D5),      // 浅蓝描边
    fillColor: Color = Color(0x557FB3D5),      // 更浅的蓝填充
    gridColor: Color = Color(0xFFD8D8D8),      // 浅灰网格
) {
    require(values.size == 6) { "PowerRadarChart requires exactly 6 axes" }
    BoxWithConstraints(modifier = modifier) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val labelTextSize = with(density) { 14.sp.toPx() }
        val valueTextSize = with(density) { 12.sp.toPx() }
        val tickTextSize = with(density) { 9.sp.toPx() }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val center = Offset(width / 2f, height / 2f)
            val radius = min(width, height) / 2f * 0.60f

            // 网页版固定刻度 100-700，但用户选了动态最大值
            val maxValue = (values.maxOfOrNull { it.second } ?: 0.0)
                .let { if (it <= 0.0) 100.0 else it * 1.15 }
                .let { raw -> (Math.ceil(raw / 100.0) * 100.0).coerceAtLeast(100.0) }

            val rings = 6

            // 1. 画同心圆网格
            for (ring in 1..rings) {
                val r = radius * ring / rings
                drawCircle(
                    color = gridColor,
                    radius = r,
                    center = center,
                    style = Stroke(width = 1f),
                )
            }

            // 2. 画轴线（从中心到 6 个顶点）
            for (i in 0 until 6) {
                val angle = angleForIndex(i)
                val x = center.x + radius * cos(angle).toFloat()
                val y = center.y + radius * sin(angle).toFloat()
                drawLine(
                    color = gridColor,
                    start = center,
                    end = Offset(x, y),
                    strokeWidth = 1f,
                )
            }

            // 3. 画数据多边形（先填充后描边）
            val valuePath = Path()
            values.forEachIndexed { i, (_, v) ->
                val ratio = (v / maxValue).coerceIn(0.0, 1.0)
                val angle = angleForIndex(i)
                val x = center.x + (radius * ratio * cos(angle)).toFloat()
                val y = center.y + (radius * ratio * sin(angle)).toFloat()
                if (i == 0) valuePath.moveTo(x, y) else valuePath.lineTo(x, y)
            }
            valuePath.close()
            drawPath(valuePath, color = fillColor)
            drawPath(valuePath, color = lineColor, style = Stroke(width = 2.5f))

            // 4. 画顶点圆点（白色填充 + 蓝色描边）
            values.forEachIndexed { i, (_, v) ->
                val ratio = (v / maxValue).coerceIn(0.0, 1.0)
                val angle = angleForIndex(i)
                val x = center.x + (radius * ratio * cos(angle)).toFloat()
                val y = center.y + (radius * ratio * sin(angle)).toFloat()
                drawCircle(color = Color.White, radius = 5f, center = Offset(x, y))
                drawCircle(
                    color = lineColor,
                    radius = 5f,
                    center = Offset(x, y),
                    style = Stroke(width = 2f),
                )
            }

            // 5. 文本标签（对齐网页版）
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                textAlign = android.graphics.Paint.Align.CENTER
            }
            values.forEachIndexed { i, (label, v) ->
                val angle = angleForIndex(i)
                val cosA = cos(angle).toFloat()
                val sinA = sin(angle).toFloat()

                // 类别标签：放在最外圈之外（轴延长线上）
                val labelDist = radius * 1.15f
                val lx = center.x + labelDist * cosA
                val ly = center.y + labelDist * sinA + labelTextSize * 0.35f
                paint.color = android.graphics.Color.parseColor("#333333")
                paint.textSize = labelTextSize
                paint.isFakeBoldText = false
                drawContext.canvas.nativeCanvas.drawText(label, lx, ly, paint)

                // 数值标签：智能避让
                // 顶点离圆心距离 ratio * radius。如果顶点已经很靠外（>0.75），数值就往圆心方向放
                // 否则往顶点外侧放
                val ratio = (v / maxValue).coerceIn(0.0, 1.0)
                val vertexDist = radius * ratio.toFloat()
                val valueDist = if (ratio > 0.72f) {
                    // 顶点很靠外，数值放在顶点内侧（避开圆圈）
                    vertexDist - radius * 0.10f
                } else {
                    // 顶点居中或靠内，数值放在顶点外侧
                    vertexDist + radius * 0.08f
                }
                val vx = center.x + valueDist * cosA
                val vy = center.y + valueDist * sinA + valueTextSize * 0.35f
                paint.color = android.graphics.Color.parseColor("#222222")
                paint.textSize = valueTextSize
                paint.isFakeBoldText = false
                val valueText = if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(v)
                drawContext.canvas.nativeCanvas.drawText(valueText, vx, vy, paint)
            }

            // 6. 刻度数字（沿"一速"轴 = 右方向，0 在圆心）
            paint.color = android.graphics.Color.parseColor("#999999")
            paint.textSize = tickTextSize
            paint.textAlign = android.graphics.Paint.Align.LEFT
            paint.isFakeBoldText = false
            for (ring in 0..rings) {
                val r = radius * ring / rings
                val tx = center.x + r + 3f
                val ty = center.y - 3f  // 稍微偏上避免压线
                val label = (maxValue * ring / rings).toInt().toString()
                drawContext.canvas.nativeCanvas.drawText(label, tx, ty, paint)
            }
        }
    }
}

/**
 * 索引到角度的映射。屏幕坐标系 y 轴向下。
 * 0 = 一速（正右，0°）
 * 1 = 速度（右上，-60°）
 * 2 = 输出（左上，-120°）
 * 3 = 坦克（正左，180°）
 * 4 = 双效（左下，120°）
 * 5 = 半肉（右下，60°）
 */
private fun angleForIndex(index: Int): Double {
    return when (index) {
        0 -> 0.0                       // 一速（右）
        1 -> -PI / 3                   // 速度（右上）
        2 -> -2 * PI / 3               // 输出（左上）
        3 -> PI                        // 坦克（左）
        4 -> 2 * PI / 3                // 双效（左下）
        5 -> PI / 3                    // 半肉（右下）
        else -> 0.0
    }
}
