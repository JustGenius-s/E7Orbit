package com.e7orbit.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.e7orbit.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.e7orbit.data.E7Gear
import com.e7orbit.data.E7GearStat
import com.e7orbit.data.GearSetNames
import com.e7orbit.data.GearSlot
import com.e7orbit.optimizer.EquippedHeroBuild
import com.e7orbit.optimizer.EquippedSetSummary
import com.e7orbit.optimizer.GearOptimizer
import com.e7orbit.ui.theme.OrbitPolygonShapes
import com.e7orbit.ui.theme.asShape

internal val EQUIPMENT_SLOTS = listOf(
    GearSlot.WEAPON,
    GearSlot.HELMET,
    GearSlot.ARMOR,
    GearSlot.NECKLACE,
    GearSlot.RING,
    GearSlot.BOOTS,
)

internal const val MAX_STAT_DIGITS = 7

@Composable
internal fun DetailedGearRow(slot: GearSlot, gear: E7Gear?) {
    if (gear == null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = slot.label,
                modifier = Modifier.width(64.dp),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("未装备", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GearItemDisplay(gear = gear, size = 56.dp)
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    GearSetNames.shortName(gear.setCode, gear.setName),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                GearStatInline(stat = gear.mainStat, showModified = false)
            }
            Text(
                "分 ${GearOptimizer.gearScore(gear)}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        if (gear.substats.isNotEmpty()) {
            Spacer(Modifier.height(7.dp))
            GearSubstatsRow(
                substats = gear.substats,
                modifier = Modifier.padding(start = 64.dp),
            )
        }
    }
}

@Composable
internal fun InventoryGearCard(
    gear: E7Gear,
    equippedHero: EquippedHeroBuild?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small),
    ) {
        // 背景图保持原始宽高比，与卡片等宽（FillWidth），高度按比例，不变形。
        Image(
            painter = painterResource(R.drawable.e7_gear_item_frame_equip),
            contentDescription = null,
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.FillWidth,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 42.dp, vertical = 30.dp),
        ) {
            // 顶部固定高度：装备、套装/部位和英雄身份信息始终占同一行。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(GearCardHeaderHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GearItemDisplay(gear = gear, size = GearCardItemIconSize)
                Spacer(Modifier.width(18.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        GearSetNames.fullName(gear.setCode, gear.setName),
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        gear.slot.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "装备分数",
                            color = GearItemLevelColor,
                            fontSize = 13.sp,
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(4.dp))
                        GearScoreDigits(
                            score = GearOptimizer.gearScore(gear),
                            modifier = Modifier.offset(y = 1.dp),
                            digitHeight = 16.dp,
                        )
                    }
                }
                equippedHero?.let { build ->
                    Spacer(Modifier.width(8.dp))
                    OptimizerHeroIdentity(
                        build = build,
                        modifier = Modifier
                            .width(74.dp)
                            .fillMaxHeight(),
                        showLevelAndStars = false,
                    )
                }
            }
            Spacer(Modifier.height(GearCardHeaderToMainStatSpacing))
            // 主属性行：图标 + 标签（左） / 数值（右）。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                gearStatIconRes(gear.mainStat.type)?.let { resId ->
                    GearAssetIcon(
                        resId = resId,
                        contentDescription = gear.mainStat.label,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    gear.mainStat.label,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    gear.mainStat.displayValue(),
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                )
            }
            // 副属性：每条一行，标签左 / 数值右。
            if (gear.substats.isNotEmpty()) {
                Spacer(Modifier.height(GearCardMainToSubstatsSpacing))
                gear.substats.forEach { stat ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = GearCardSubstatHorizontalInset),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stat.label,
                            color = GearCardSubstatTextColor,
                            fontSize = 13.sp,
                            maxLines = 1,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            stat.displayValue(),
                            color = GearCardSubstatTextColor,
                            fontSize = 13.sp,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun EquippedHeroAvatar(
    build: EquippedHeroBuild,
    modifier: Modifier = Modifier,
) {
    RemoteImage(
        url = build.hero?.assets?.iconUrl ?: build.hero?.assets?.thumbnailUrl,
        contentDescription = "已装备英雄",
        modifier = modifier.clip(OrbitPolygonShapes.HeroAvatar.asShape),
        contentScale = ContentScale.Crop,
    )
}

private val GearCardHeaderHeight = 110.dp
private val GearCardItemIconSize = 84.dp
private val GearCardHeaderToMainStatSpacing = 8.dp
private val GearCardMainToSubstatsSpacing = 10.dp
private val GearCardSubstatHorizontalInset = 6.dp
private val GearCardSubstatTextColor = Color(0xFFE6E6E6)
private val GearItemLevelColor = Color(0xFFEBCB63)
private val GearItemTextShadow = Shadow(color = Color.Black, blurRadius = 3f)

/** Shared visual used anywhere a normal equipment item is shown. */
@Composable
internal fun GearItemDisplay(
    gear: E7Gear,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        GearAssetIcon(
            resId = gearItemBackgroundRes(gear.rank, hasGear = true),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .padding(2.dp),
        )
        val itemIcon = gearItemIconRes(gear.code, gear.slot) ?: gearSlotIconRes(gear.slot)
        itemIcon?.let { resId ->
            GearAssetIcon(
                resId = resId,
                contentDescription = gear.slot.label,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(7.dp),
            )
        }
        Text(
            text = gear.level.toString(),
            color = GearItemLevelColor,
            fontSize = 10.sp,
            lineHeight = 10.sp,
            fontWeight = FontWeight.Black,
            style = MaterialTheme.typography.labelSmall.copy(shadow = GearItemTextShadow),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = size * 0.10f + 6.dp, top = 9.dp),
        )
        GearItemEnhancementBadge(
            enhance = gear.enhance,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = (-1).dp, y = 1.dp),
        )
        gearSetIconRes(gear.setCode)?.let { setIcon ->
            GearAssetIcon(
                resId = setIcon,
                contentDescription = GearSetNames.fullName(gear.setCode, gear.setName),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 2.dp)
                    .padding(end = 1.dp, bottom = 1.dp)
                    .size(size * 0.40f),
            )
        }
    }
}

@Composable
private fun GearItemEnhancementBadge(
    enhance: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .shadow(elevation = 3.dp, shape = CircleShape, clip = false)
            .background(Color(0xFFD63A40), CircleShape)
            .padding(horizontal = 2.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "+${enhance.coerceAtLeast(0)}",
            color = Color.White,
            fontSize = 10.sp,
            lineHeight = 10.sp,
            letterSpacing = 0.sp,
            fontWeight = FontWeight.Black,
            style = MaterialTheme.typography.labelSmall.copy(shadow = GearItemTextShadow),
            maxLines = 1,
        )
    }
}

@Composable
internal fun GearAssetIcon(
    resId: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(resId),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}

@Composable
internal fun GearSetSummaryRow(sets: List<EquippedSetSummary>) {
    val visibleSets = sets.filter { it.completedCount > 0 }.ifEmpty { sets }
    if (visibleSets.isEmpty()) {
        Text(
            "暂无套装",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(visibleSets, key = EquippedSetSummary::code) { set ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                gearSetIconRes(set.code)?.let { resId ->
                    GearAssetIcon(
                        resId = resId,
                        contentDescription = set.name,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = buildString {
                        append(set.name)
                        if (set.completedCount > 1) append(" x${set.completedCount}")
                        else if (set.completedCount == 0) append(" ${set.pieceCount}/${set.requiredPieces}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 装等（gear score）用 game_eff_exp 数字图片逐位渲染。 */
@Composable
internal fun GearScoreDigits(
    score: Int,
    modifier: Modifier = Modifier,
    digitHeight: Dp = 18.dp,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        score.coerceAtLeast(0).toString().forEach { digit ->
            gearScoreDigitRes(digit)?.let { resId ->
                GearAssetIcon(
                    resId = resId,
                    contentDescription = null,
                    modifier = Modifier
                        .height(digitHeight)
                        .width(digitHeight * gearScoreDigitAspectRatio(digit)),
                )
            }
        }
    }
}

@Composable
internal fun GearStatInline(
    stat: E7GearStat,
    modifier: Modifier = Modifier,
    showModified: Boolean = true,
    textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        gearStatIconRes(stat.type)?.let { resId ->
            GearAssetIcon(
                resId = resId,
                contentDescription = stat.label,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = "${stat.label} ${stat.displayValue()}${if (showModified && stat.modified) "*" else ""}",
            style = MaterialTheme.typography.bodySmall,
            color = textColor,
            maxLines = 1,
        )
    }
}

@Composable
internal fun GearSubstatsRow(
    substats: List<E7GearStat>,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(substats, key = E7GearStat::type) { stat ->
            GearStatInline(stat, textColor = textColor)
        }
    }
}
