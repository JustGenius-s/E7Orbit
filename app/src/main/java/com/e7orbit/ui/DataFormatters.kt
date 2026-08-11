package com.e7orbit.ui

import com.e7orbit.data.E7Hero
import com.e7orbit.data.GearSetNames
import com.e7orbit.data.HeroRtaAnalysis
import java.util.Locale

/**
 * Wiki/英雄详情共用的标签与数值格式化工具。
 * （原 DataScreens.kt 尾部私有扩展，抽出让各屏幕文件复用。）
 */

internal fun E7Hero.attributeLabel(): String = when (attribute.lowercase()) {
    "fire" -> "火焰"
    "ice", "water" -> "寒气"
    "earth", "wind" -> "自然"
    "light" -> "光明"
    "dark" -> "黑暗"
    else -> attribute
}

internal fun E7Hero.roleLabel(): String = role.roleLabel()

internal fun String.roleLabel(): String = when (lowercase()) {
    "knight" -> "骑士"
    "warrior" -> "战士"
    "ranger" -> "射手"
    "mage" -> "魔导士"
    "assassin" -> "盗贼"
    "manauser", "soul_weaver", "soulweaver" -> "精灵师"
    else -> this
}

internal fun Int?.displayOrDash(): String = this?.toString() ?: "—"

internal fun Int?.percentOrDash(): String = this?.let { "$it%" } ?: "—"

internal fun String.rtaDate(): String = take(10).ifBlank { "—" }

internal fun Double?.rtaPercent(): String = this?.let { value ->
    "%.2f".format(Locale.US, value).trimEnd('0').trimEnd('.') + "%"
} ?: "—"

internal fun HeroRtaAnalysis.hasRtaData(): Boolean =
    sampleSize > 0 ||
        winRate != null ||
        equipmentSets.isNotEmpty() ||
        pickPositions.isNotEmpty() ||
        banPositions.isNotEmpty()

internal fun String.rtaSetLabel(): String =
    GearSetNames.shortName(this, removePrefix("set_"))
