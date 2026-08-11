package com.e7orbit.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.e7orbit.ui.theme.OrbitArtifactHighlight
import com.e7orbit.ui.theme.OrbitSkillTurnHighlight

// 百分比（含小数、可选正负号）：50%、12.5%、-10%、+5%
private val percentPattern = Regex("""[+\-−]?\d+(?:\.\d+)?%""")

// 回合数（含可选正负号）：3回合、2 回合、-1回合、+1回合
private val turnPattern = Regex("""[+\-−]?\d+(?:\.\d+)?\s*回合""")

private data class SkillHighlight(
    val range: IntRange,
    val color: Color,
)

/**
 * 把技能描述里的百分比数值染成橙色、回合数染成蓝色，
 * 复用神器描述的高亮风格。
 *
 * 高亮片段如果紧贴着其他文字（常见于中文排版），会在两侧各补一个
 * 细发空格（U+200A，约为普通空格 1/5 宽），让数值和普通文字之间
 * 留一点缝隙；原本两侧已有空白时不补。
 */
internal fun String.withSkillValueHighlight(): AnnotatedString {
    val highlights = buildList<SkillHighlight> {
        percentPattern.findAll(this@withSkillValueHighlight).forEach { match ->
            add(SkillHighlight(match.range, OrbitArtifactHighlight))
        }
        turnPattern.findAll(this@withSkillValueHighlight).forEach { match ->
            add(SkillHighlight(match.range, OrbitSkillTurnHighlight))
        }
    }
    if (highlights.isEmpty()) return AnnotatedString(this)

    val sorted = highlights.sortedBy { it.range.first }
    val style = SpanStyle(fontWeight = FontWeight.SemiBold)

    return buildAnnotatedString {
        var cursor = 0
        sorted.forEach { highlight ->
            val start = highlight.range.first
            val end = highlight.range.last + 1
            if (start < cursor) return@forEach // 保险：跳过重叠命中

            append(substring(cursor, start))
            if (start > 0 && !this@withSkillValueHighlight[start - 1].isWhitespace()) {
                append('\u200A')
            }
            val valueStart = length
            append(substring(start, end))
            addStyle(style.copy(color = highlight.color), valueStart, length)
            if (end < this@withSkillValueHighlight.length &&
                !this@withSkillValueHighlight[end].isWhitespace()
            ) {
                append('\u200A')
            }
            cursor = end
        }
        append(substring(cursor, this@withSkillValueHighlight.length))
    }
}
