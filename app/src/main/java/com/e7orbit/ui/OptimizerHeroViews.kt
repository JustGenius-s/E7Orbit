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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.e7orbit.optimizer.EquippedHeroBuild
import com.e7orbit.optimizer.OptimizedHeroStats
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
internal fun EquippedHeroCard(
    build: EquippedHeroBuild,
    preferenceConfigured: Boolean,
    onClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .optimizerSharedBounds(
                key = "optimizer-hero-${build.instanceId}",
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
            ),
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RemoteImage(
                    url = build.hero?.assets?.iconUrl ?: build.hero?.assets?.thumbnailUrl,
                    contentDescription = build.displayName,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(OrbitPolygonShapes.HeroAvatar.asShape),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = build.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    GearSetSummaryRow(build.sets)
                    HeroIdentityIcons(
                        attribute = build.hero?.attribute,
                        role = build.hero?.role,
                        rarity = build.scannedHero?.stars ?: build.hero?.rarity,
                        iconSize = 17.dp,
                    )
                    Text(
                        text = build.combatSummaryText(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${build.items.size}/6",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = if (preferenceConfigured) "已设置偏好" else "查看详情",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (preferenceConfigured) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            build.stats?.let { CompactStatsGrid(it) }
                ?: Text(
                    text = if (!build.isComplete) "装备不完整，无法计算最终属性" else "未匹配到英雄基础属性",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
    }
}

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
        StatDisplay("Attack", "攻击", formatNumber(stats.attack)),
        StatDisplay("Health", "生命", formatNumber(stats.health)),
        StatDisplay("Defense", "防御", formatNumber(stats.defense)),
        StatDisplay("Speed", "速度", stats.speed.toString()),
        StatDisplay("CriticalHitChancePercent", "暴击率", "${stats.critChance}%"),
        StatDisplay("CriticalHitDamagePercent", "暴击伤害", "${stats.critDamage}%"),
        StatDisplay("EffectivenessPercent", "效果命中", "${stats.effectiveness}%"),
        StatDisplay("EffectResistancePercent", "效果抗性", "${stats.resistance}%"),
        StatDisplay(null, "战斗力", formatNumber(stats.combatPower)),
        StatDisplay(null, "有效生命", formatNumber(stats.effectiveHealth)),
        StatDisplay(null, "伤害", formatNumber(stats.damage)),
        StatDisplay(null, "装备分", stats.gearScore.toString()),
    )
    values.chunked(3).forEachIndexed { rowIndex, row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            row.forEach { stat ->
                StatCell(stat, Modifier.weight(1f), compact = false)
            }
        }
        if (rowIndex != values.chunked(3).lastIndex) Spacer(Modifier.height(10.dp))
    }
}

internal data class StatDisplay(
    val type: String?,
    val label: String,
    val value: String,
)

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
        Text(
            text = stat.value,
            fontSize = if (compact) 13.sp else 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
