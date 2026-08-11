package com.e7orbit.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.e7orbit.data.E7Gear
import com.e7orbit.data.E7GearStat
import com.e7orbit.data.GearSlot
import com.e7orbit.optimizer.EquippedSetSummary
import com.e7orbit.optimizer.GearOptimizer

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
            GearSlotLabel(slot = slot, rank = null, modifier = Modifier.width(62.dp))
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
            Column(modifier = Modifier.width(62.dp)) {
                GearSlotLabel(
                    slot = slot,
                    rank = gear.rank,
                    gearCode = gear.code,
                    enhancement = gear.enhance,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    gearSetIconRes(gear.setCode)?.let { resId ->
                        GearAssetIcon(
                            resId = resId,
                            contentDescription = gear.setName,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        "${gear.setName.removeSuffix("套装")} · ${gear.rank}",
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GearStatInline(stat = gear.mainStat, showModified = false)
                    Text(
                        " · Lv.${gear.level}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                modifier = Modifier.padding(start = 62.dp),
            )
        }
    }
}

@Composable
internal fun InventoryGearCard(
    gear: E7Gear,
    equippedName: String?,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.width(64.dp)) {
                    GearSlotLabel(
                        slot = gear.slot,
                        rank = gear.rank,
                        gearCode = gear.code,
                        enhancement = gear.enhance,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        gearSetIconRes(gear.setCode)?.let { resId ->
                            GearAssetIcon(
                                resId = resId,
                                contentDescription = gear.setName,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.width(5.dp))
                        }
                        Text(
                            "${gear.setName.removeSuffix("套装")} · ${gear.rank} · Lv.${gear.level}",
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    GearStatInline(stat = gear.mainStat, showModified = false)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        GearOptimizer.gearScore(gear).toString(),
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        equippedName ?: "库存",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (equippedName == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (gear.substats.isNotEmpty()) {
                Spacer(Modifier.height(7.dp))
                GearSubstatsRow(substats = gear.substats)
            }
        }
    }
}

@Composable
internal fun GearSlotLabel(
    slot: GearSlot,
    rank: String?,
    gearCode: String? = null,
    enhancement: Int? = null,
    modifier: Modifier = Modifier,
) {
    if (slot == GearSlot.UNKNOWN) {
        Text(
            slot.label,
            modifier = modifier,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }
    GearSlotAsset(
        slot = slot,
        rank = rank,
        gearCode = gearCode,
        enhancement = enhancement,
        modifier = modifier.size(52.dp),
    )
}

@Composable
internal fun GearSlotAsset(
    slot: GearSlot,
    rank: String?,
    gearCode: String? = null,
    enhancement: Int? = null,
    modifier: Modifier = Modifier,
) {
    val foregroundRes = gearCode?.let { gearItemIconRes(it, slot) }
        ?: gearCode?.let { gearSlotIconRes(slot) }
    Box(
        modifier = modifier.clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        GearAssetIcon(
            resId = gearItemBackgroundRes(rank, hasGear = gearCode != null),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .offset(y = 2.dp),
        )
        foregroundRes?.let { resId ->
            GearAssetIcon(
                resId = resId,
                contentDescription = slot.label,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp)
                    .offset(y = 2.dp),
            )
        }
        enhancement?.let { value ->
            GearEnhancementBadge(
                enhance = value,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 2.dp),
            )
        }
    }
}

private data class GearEnhanceGlyph(
    val resId: Int,
    val width: Int,
)

@Composable
private fun GearEnhancementBadge(
    enhance: Int,
    modifier: Modifier = Modifier,
) {
    val glyphs = buildList {
        add(GearEnhanceGlyph(gearEnhancePlusRes(), width = 38))
        enhance.coerceAtLeast(0).toString().forEach { digit ->
            gearEnhanceDigitRes(digit)?.let { resId ->
                add(GearEnhanceGlyph(resId, width = if (digit == '1') 22 else 38))
            }
        }
    }
    Row(
        modifier = modifier.height(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        glyphs.forEach { glyph ->
            GearAssetIcon(
                resId = glyph.resId,
                contentDescription = null,
                modifier = Modifier
                    .height(10.dp)
                    .aspectRatio(glyph.width / 46f),
            )
        }
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

@Composable
internal fun GearStatInline(
    stat: E7GearStat,
    modifier: Modifier = Modifier,
    showModified: Boolean = true,
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
internal fun GearSubstatsRow(
    substats: List<E7GearStat>,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(substats, key = E7GearStat::type) { stat ->
            GearStatInline(stat)
        }
    }
}
