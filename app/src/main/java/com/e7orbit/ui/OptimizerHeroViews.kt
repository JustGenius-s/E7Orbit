package com.e7orbit.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.e7orbit.data.E7HeroExclusiveEquipment
import com.e7orbit.optimizer.EquippedHeroBuild
import com.e7orbit.optimizer.OptimizedHeroStats
import com.e7orbit.optimizer.OptimizerStat
import com.e7orbit.optimizer.StatBreakdown
import com.e7orbit.ui.theme.OrbitPolygonShapes
import com.e7orbit.ui.theme.asShape
import java.text.NumberFormat
import java.util.Locale

internal fun formatNumber(value: Int): String =
    NumberFormat.getIntegerInstance(Locale.CHINA).format(value)

internal fun EquippedHeroBuild.combatSummaryText(): String = listOfNotNull(
    stats?.combatPower?.let { "战力 ${formatNumber(it)}" },
    stats?.speed?.let { "速度 $it" },
).joinToString(" · ").ifBlank { "暂无最终面板" }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun EquippedHeroHeader(
    build: EquippedHeroBuild,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier,
) {
    SectionSurface(
        modifier = modifier.optimizerSharedBounds(
            key = "optimizer-hero-${build.instanceId}",
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
        ),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.extraLargeIncreased,
        contentPadding = PaddingValues(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RemoteImage(
                url = build.hero?.assets?.iconUrl ?: build.hero?.assets?.thumbnailUrl,
                contentDescription = build.displayName,
                modifier = Modifier
                    .size(64.dp)
                    .clip(OrbitPolygonShapes.HeroAvatar.asShape),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = build.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                HeroIdentityIcons(
                    attribute = build.hero?.attribute,
                    role = build.hero?.role,
                    rarity = build.scannedHero?.stars ?: build.hero?.rarity,
                    iconSize = 22.dp,
                )
                Text(
                    text = listOfNotNull(
                        build.scannedHero?.awaken?.let { "觉醒 $it" },
                        build.hero?.zodiac,
                    ).joinToString(" · ").ifBlank { "游戏实例 ${build.instanceId}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                GearSetSummaryRow(build.sets)
            }
        }
    }
}

@Composable
internal fun CompactStatsGrid(stats: OptimizedHeroStats) {
    val values = listOf(
        StatDisplay("Attack", "攻击", formatNumber(stats.attack)),
        StatDisplay("Health", "生命", formatNumber(stats.health)),
        StatDisplay("Defense", "防御", formatNumber(stats.defense)),
        StatDisplay("Speed", "速度", stats.speed.toString()),
        StatDisplay("CriticalHitChancePercent", "暴击", "${stats.critChance}%"),
        StatDisplay("CriticalHitDamagePercent", "暴伤", "${stats.critDamage}%"),
        StatDisplay("EffectivenessPercent", "命中", "${stats.effectiveness}%"),
        StatDisplay("EffectResistancePercent", "抗性", "${stats.resistance}%"),
        StatDisplay("DualAttackChancePercent", "夹攻", "${stats.dualAttackChance}%"),
    )
    values.chunked(4).forEachIndexed { rowIndex, row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            row.forEach { stat ->
                StatCell(stat, Modifier.weight(1f), compact = true)
            }
        }
        if (rowIndex == 0) Spacer(Modifier.height(8.dp))
    }
}

@Composable
internal fun HeroStatsGrid(stats: OptimizedHeroStats) {
    val values = listOf(
        StatDisplay("Attack", "攻击", formatNumber(stats.attack),
            breakdown = stats.breakdowns[OptimizerStat.ATTACK]),
        StatDisplay("Health", "生命", formatNumber(stats.health),
            breakdown = stats.breakdowns[OptimizerStat.HEALTH]),
        StatDisplay("Defense", "防御", formatNumber(stats.defense),
            breakdown = stats.breakdowns[OptimizerStat.DEFENSE]),
        StatDisplay("Speed", "速度", stats.speed.toString(),
            breakdown = stats.breakdowns[OptimizerStat.SPEED]),
        StatDisplay("CriticalHitChancePercent", "暴击率", "${stats.critChance}%",
            breakdown = stats.breakdowns[OptimizerStat.CRIT_CHANCE]),
        StatDisplay("CriticalHitDamagePercent", "暴击伤害", "${stats.critDamage}%",
            breakdown = stats.breakdowns[OptimizerStat.CRIT_DAMAGE]),
        StatDisplay("EffectivenessPercent", "效果命中", "${stats.effectiveness}%",
            breakdown = stats.breakdowns[OptimizerStat.EFFECTIVENESS]),
        StatDisplay("EffectResistancePercent", "效果抗性", "${stats.resistance}%",
            breakdown = stats.breakdowns[OptimizerStat.RESISTANCE]),
        StatDisplay("DualAttackChancePercent", "夹攻率", "${stats.dualAttackChance}%"),
        StatDisplay(null, "战斗力", formatNumber(stats.combatPower)),
        StatDisplay(null, "有效生命", formatNumber(stats.effectiveHealth)),
        StatDisplay(null, "伤害", formatNumber(stats.damage)),
        StatDisplay(null, "装备分", stats.gearScore.toString()),
    )
    values.chunked(2).forEachIndexed { rowIndex, row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            row.forEach { stat ->
                StatCell(stat, Modifier.weight(1f), compact = false)
            }
            // 奇数个时补齐占位，保持两列对齐。
            repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
        }
        if (rowIndex != values.chunked(2).lastIndex) Spacer(Modifier.height(12.dp))
    }
}

internal data class StatDisplay(
    val type: String?,
    val label: String,
    val value: String,
    val breakdown: StatBreakdown? = null,
)

private val BreakdownPercentColor = Color(0xFFF5A623) // 橙：装备百分比
private val BreakdownFlatColor = Color(0xFF3D8BFF)    // 蓝：装备固定
private val BreakdownSetColor = Color(0xFFE53935)     // 红：套装
private val BreakdownExclusiveColor = Color(0xFF00A67A) // 绿：专属装备

private fun breakdownText(breakdown: StatBreakdown): AnnotatedString? {
    data class Part(val text: String, val color: Color)
    val parts = buildList {
        if (breakdown.gearPercent != 0.0) {
            add(Part("+${formatBreakdownValue(breakdown.gearPercent)}%", BreakdownPercentColor))
        }
        if (breakdown.gearFlat != 0.0) {
            add(Part("+${formatBreakdownValue(breakdown.gearFlat)}", BreakdownFlatColor))
        }
        if (breakdown.setBonus != 0.0) {
            val suffix = if (breakdown.setIsPercent) "%" else ""
            val sign = if (breakdown.setBonus > 0) "+" else ""
            add(Part("$sign${formatBreakdownValue(breakdown.setBonus)}$suffix", BreakdownSetColor))
        }
        if (breakdown.exclusiveEquipmentBonus != 0.0) {
            val suffix = if (breakdown.exclusiveEquipmentIsPercent) "%" else ""
            val sign = if (breakdown.exclusiveEquipmentBonus > 0) "+" else ""
            add(
                Part(
                    "专武 $sign${formatBreakdownValue(breakdown.exclusiveEquipmentBonus)}$suffix",
                    BreakdownExclusiveColor,
                ),
            )
        }
    }
    if (parts.isEmpty()) return null
    return buildAnnotatedString {
        withStyle(SpanStyle(color = Color(0xFF8A9099))) { append("(") }
        parts.forEachIndexed { index, part ->
            if (index > 0) withStyle(SpanStyle(color = Color(0xFF8A9099))) { append(" ") }
            withStyle(SpanStyle(color = part.color)) { append(part.text) }
        }
        withStyle(SpanStyle(color = Color(0xFF8A9099))) { append(")") }
    }
}

private fun formatBreakdownValue(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value)

@Composable
internal fun ExclusiveEquipmentDetail(equipment: E7HeroExclusiveEquipment) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteImage(
            url = equipment.iconUrl,
            contentDescription = equipment.name,
            modifier = Modifier.size(56.dp),
            contentScale = ContentScale.Fit,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = equipment.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                exclusiveEquipmentStatIconRes(
                    equipment.statType,
                    equipment.statPercent,
                )?.let { resId ->
                    GearAssetIcon(
                        resId = resId,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = "${equipment.statLabel()} ${equipment.statRange()} · 计算采用 +${equipment.statMaximum()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun E7HeroExclusiveEquipment.statLabel(): String = when (statType.lowercase()) {
    "attack" -> "攻击"
    "health" -> "生命"
    "defense" -> "防御"
    "speed" -> "速度"
    "critical_chance" -> "暴击率"
    "critical_damage" -> "暴击伤害"
    "effectiveness" -> "效果命中"
    "effect_resistance" -> "效果抗性"
    else -> statType
}

private fun E7HeroExclusiveEquipment.statRange(): String {
    val suffix = if (statPercent) "%" else ""
    return "${formatBreakdownValue(statMin)}-${formatBreakdownValue(statMax)}$suffix"
}

private fun E7HeroExclusiveEquipment.statMaximum(): String =
    "${formatBreakdownValue(statMax)}${if (statPercent) "%" else ""}"

@Composable
internal fun StatCell(stat: StatDisplay, modifier: Modifier, compact: Boolean) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            stat.type?.let(::gearStatIconRes)?.let { resId ->
                GearAssetIcon(
                    resId = resId,
                    contentDescription = null,
                    modifier = Modifier.size(if (compact) 14.dp else 16.dp),
                )
                Spacer(Modifier.width(3.dp))
            }
            Text(
                text = stat.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stat.value,
                fontSize = if (compact) 13.sp else 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            stat.breakdown?.let { breakdownText(it) }?.let { breakdownText ->
                Spacer(Modifier.width(4.dp))
                Text(
                    text = breakdownText,
                    fontSize = if (compact) 11.sp else 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
