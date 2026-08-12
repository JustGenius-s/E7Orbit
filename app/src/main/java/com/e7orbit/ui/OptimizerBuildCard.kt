package com.e7orbit.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.e7orbit.R
import com.e7orbit.data.E7Artifact
import com.e7orbit.data.E7Gear
import com.e7orbit.data.E7HeroExclusiveEquipment
import com.e7orbit.data.GearSlot
import com.e7orbit.optimizer.EquippedHeroBuild
import java.util.Locale

private val OptimizerCardBackground = Color(0xFF080D14)
private val OptimizerCardBorder = Color(0xFF46515E)
private val OptimizerCardConfiguredBorder = Color(0xFF8D744C)
private val OptimizerCardText = Color(0xFFF1F3F6)
private val OptimizerCardMutedText = Color(0xFFB4BBC4)
private val OptimizerCardLevel = Color(0xFFEBCB63)
private val OptimizerHeroLevel = Color(0xFFFF850F)
private val GameTextShadow = Shadow(color = Color.Black, blurRadius = 3f)

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
        colors = CardDefaults.cardColors(containerColor = OptimizerCardBackground),
        border = BorderStroke(
            1.dp,
            if (preferenceConfigured) OptimizerCardConfiguredBorder else OptimizerCardBorder,
        ),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val horizontalPadding = 6.dp
            val identityGap = 5.dp
            val identityWidth = (maxWidth * 0.20f).coerceIn(72.dp, 116.dp)
            val equipmentWidth = maxWidth - horizontalPadding * 2 - identityGap - identityWidth
            val slotWidth = ((equipmentWidth - 6.dp) / 4).coerceAtLeast(52.dp)
            val rowHeight = (slotWidth * 107f / 88f).coerceIn(76.dp, 104.dp)
            val contentHeight = rowHeight * 2 + 4.dp

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(contentHeight + 12.dp)
                    .padding(horizontal = horizontalPadding, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(identityGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OptimizerHeroIdentity(
                    build = build,
                    modifier = Modifier
                        .width(identityWidth)
                        .fillMaxHeight(),
                )
                OptimizerEquipmentGrid(
                    build = build,
                    rowHeight = rowHeight,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun OptimizerHeroIdentity(
    build: EquippedHeroBuild,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val avatarSize = (maxWidth - 4.dp).coerceIn(54.dp, 82.dp)
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Lv. Max",
                color = OptimizerHeroLevel,
                fontSize = 12.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.Black,
                style = MaterialTheme.typography.labelLarge.copy(shadow = GameTextShadow),
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Box(
                modifier = Modifier.size(avatarSize),
                contentAlignment = Alignment.Center,
            ) {
                RemoteImage(
                    url = build.hero?.assets?.iconUrl ?: build.hero?.assets?.thumbnailUrl,
                    contentDescription = build.displayName,
                    modifier = Modifier
                        .size(avatarSize * 0.83f)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
                Image(
                    painter = painterResource(R.drawable.e7_optimizer_hero_frame),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
                build.hero?.role?.let(::heroClassIconRes)?.let { roleIcon ->
                    GearAssetIcon(
                        resId = roleIcon,
                        contentDescription = build.hero.role,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset(x = 0.dp, y = 5.dp)
                            .size(20.dp),
                    )
                }
                build.hero?.attribute?.let(::heroElementIconRes)?.let { attributeIcon ->
                    GearAssetIcon(
                        resId = attributeIcon,
                        contentDescription = build.hero.attribute,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = (-1).dp, y = 7.dp)
                            .size(19.dp),
                    )
                }
            }
            Text(
                text = build.displayName,
                color = OptimizerCardText,
                fontSize = 13.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            val rarity = build.scannedHero?.stars ?: build.hero?.rarity
            if (rarity != null) {
                HeroStars(stars = rarity, iconSize = 10.dp)
            }
            build.stats?.combatPower?.let { combatPower ->
                Text(
                    text = "战力 ${formatNumber(combatPower)}",
                    color = OptimizerCardMutedText,
                    fontSize = 9.sp,
                    lineHeight = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun OptimizerEquipmentGrid(
    build: EquippedHeroBuild,
    rowHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val gearBySlot = build.items.associateBy(E7Gear::slot)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(rowHeight),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            OptimizerGearSlot(gearBySlot[GearSlot.WEAPON], GearSlot.WEAPON, Modifier.weight(1f))
            OptimizerGearSlot(gearBySlot[GearSlot.HELMET], GearSlot.HELMET, Modifier.weight(1f))
            OptimizerGearSlot(gearBySlot[GearSlot.ARMOR], GearSlot.ARMOR, Modifier.weight(1f))
            OptimizerArtifactSlot(
                artifact = build.artifact,
                artifactLevel = build.scannedHero?.artifactLevel,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(rowHeight),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            OptimizerGearSlot(gearBySlot[GearSlot.NECKLACE], GearSlot.NECKLACE, Modifier.weight(1f))
            OptimizerGearSlot(gearBySlot[GearSlot.RING], GearSlot.RING, Modifier.weight(1f))
            OptimizerGearSlot(gearBySlot[GearSlot.BOOTS], GearSlot.BOOTS, Modifier.weight(1f))
            OptimizerExclusiveEquipmentSlot(
                equipment = build.hero?.exclusiveEquipment,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun OptimizerGearSlot(
    gear: E7Gear?,
    slot: GearSlot,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxHeight(),
        contentAlignment = Alignment.Center,
    ) {
        val slotWidth = minOf(maxWidth, maxHeight * 88f / 107f)
        val slotHeight = maxHeight
        val iconSize = slotWidth
        Box(
            modifier = Modifier
                .width(slotWidth)
                .fillMaxHeight(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                modifier = Modifier.size(iconSize),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.e7_optimizer_gear_frame),
                    contentDescription = if (gear == null) "未装备${slot.label}" else null,
                    modifier = Modifier.size(iconSize * 0.92f),
                    contentScale = ContentScale.FillBounds,
                )
                gear?.let {
                    OptimizerGearItem(
                        gear = it,
                        slot = slot,
                        size = iconSize,
                        modifier = Modifier.offset(x = (-2).dp, y = (-2).dp),
                    )
                }
            }
            if (gear != null) {
                SlotStatFooter(
                    type = gear.mainStat.type,
                    value = gear.mainStat.displayValue(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .height((slotHeight - iconSize).coerceAtLeast(14.dp)),
                )
            }
        }
    }
}

@Composable
private fun OptimizerGearItem(
    gear: E7Gear,
    slot: GearSlot,
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
        val itemIcon = gearItemIconRes(gear.code, slot) ?: gearSlotIconRes(slot)
        itemIcon?.let { resId ->
            GearAssetIcon(
                resId = resId,
                contentDescription = slot.label,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(7.dp),
            )
        }
        Text(
            text = gear.level.toString(),
            color = OptimizerCardLevel,
            fontSize = 10.sp,
            lineHeight = 10.sp,
            fontWeight = FontWeight.Black,
            style = MaterialTheme.typography.labelSmall.copy(shadow = GameTextShadow),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = size * 0.10f + 6.dp, top = 9.dp),
        )
        OptimizerGearEnhanceBadge(
            enhance = gear.enhance,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = (-1).dp, y = 1.dp),
        )
        gearSetIconRes(gear.setCode)?.let { setIcon ->
            GearAssetIcon(
                resId = setIcon,
                contentDescription = gear.setName,
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
private fun OptimizerArtifactSlot(
    artifact: E7Artifact?,
    artifactLevel: Int?,
    modifier: Modifier = Modifier,
) {
    FramedSpecialSlot(
        frameRes = R.drawable.e7_optimizer_artifact_frame,
        emptyDescription = "未装备神器",
        modifier = modifier,
        footer = {
            artifact?.rarity?.let { rarity ->
                HeroStars(stars = rarity, iconSize = 9.dp)
            }
        },
    ) { frameSize ->
        if (artifact != null) {
            RemoteImage(
                url = artifact.iconUrl ?: artifact.imageUrl,
                contentDescription = artifact.name,
                modifier = Modifier
                    .size(frameSize * 0.92f)
                    .offset(x = (-1).dp, y = (-1).dp),
                contentScale = ContentScale.Fit,
            )
            artifactLevel?.let { level ->
                OptimizerEnhanceText(
                    enhance = level,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 1.dp, y = (-1).dp),
                )
            }
        }
    }
}

@Composable
private fun OptimizerExclusiveEquipmentSlot(
    equipment: E7HeroExclusiveEquipment?,
    modifier: Modifier = Modifier,
) {
    FramedSpecialSlot(
        frameRes = R.drawable.e7_optimizer_exclusive_frame,
        emptyDescription = "无专属装备",
        modifier = modifier,
        footer = {
            if (equipment != null) {
                SlotStatFooter(
                    type = exclusiveStatType(equipment),
                    value = exclusiveStatRange(equipment),
                    modifier = Modifier.height(14.dp),
                )
            }
        },
    ) { frameSize ->
        if (equipment != null) {
            RemoteImage(
                url = equipment.iconUrl,
                contentDescription = equipment.name,
                modifier = Modifier
                    .size(frameSize * 0.92f)
                    .offset(x = (-1).dp, y = (-1).dp),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

@Composable
private fun FramedSpecialSlot(
    frameRes: Int,
    emptyDescription: String,
    modifier: Modifier = Modifier,
    footer: @Composable () -> Unit,
    content: @Composable androidx.compose.foundation.layout.BoxScope.(Dp) -> Unit,
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxHeight(),
        contentAlignment = Alignment.Center,
    ) {
        val frameSize = minOf(maxWidth, maxHeight * 0.90f)
        Box(
            modifier = Modifier
                .size(frameSize)
                .align(Alignment.TopCenter),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(frameRes),
                contentDescription = emptyDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            content(frameSize)
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(14.dp),
            contentAlignment = Alignment.Center,
        ) {
            footer()
        }
    }
}

@Composable
private fun OptimizerGearEnhanceBadge(
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
        OptimizerEnhanceText(enhance = enhance)
    }
}

@Composable
private fun OptimizerEnhanceText(
    enhance: Int,
    modifier: Modifier = Modifier,
) {
    Text(
        text = "+${enhance.coerceAtLeast(0)}",
        color = Color.White,
        fontSize = 10.sp,
        lineHeight = 10.sp,
        letterSpacing = 0.sp,
        fontWeight = FontWeight.Black,
        style = MaterialTheme.typography.labelSmall.copy(shadow = GameTextShadow),
        maxLines = 1,
        modifier = modifier,
    )
}

@Composable
private fun SlotStatFooter(
    type: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        gearStatIconRes(type)?.let { statIcon ->
            GearAssetIcon(
                resId = statIcon,
                contentDescription = null,
                modifier = Modifier.size(11.dp),
            )
            Spacer(Modifier.width(2.dp))
        }
        Text(
            text = value,
            color = OptimizerCardText,
            fontSize = 10.sp,
            lineHeight = 11.sp,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelSmall.copy(shadow = GameTextShadow),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun exclusiveStatType(equipment: E7HeroExclusiveEquipment): String = when (
    equipment.statType.lowercase()
) {
    "attack" -> if (equipment.statPercent) "AttackPercent" else "Attack"
    "health" -> if (equipment.statPercent) "HealthPercent" else "Health"
    "defense" -> if (equipment.statPercent) "DefensePercent" else "Defense"
    "speed" -> "Speed"
    "critical_chance" -> "CriticalHitChancePercent"
    "critical_damage" -> "CriticalHitDamagePercent"
    "effectiveness" -> "EffectivenessPercent"
    "effect_resistance" -> "EffectResistancePercent"
    else -> equipment.statType
}

private fun exclusiveStatRange(equipment: E7HeroExclusiveEquipment): String {
    val minimum = formatCompactNumber(equipment.statMin)
    val maximum = formatCompactNumber(equipment.statMax)
    val suffix = if (equipment.statPercent) "%" else ""
    return if (minimum == maximum) "$maximum$suffix" else "$minimum-$maximum$suffix"
}

private fun formatCompactNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(Locale.US, value)
