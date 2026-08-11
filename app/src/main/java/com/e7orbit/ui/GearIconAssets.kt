package com.e7orbit.ui

import androidx.annotation.DrawableRes
import com.e7orbit.R
import com.e7orbit.data.GearSlot

@DrawableRes
internal fun gearSlotIconRes(slot: GearSlot): Int? = when (slot) {
    GearSlot.WEAPON -> R.drawable.e7_fallback_gear_weapon
    GearSlot.HELMET -> R.drawable.e7_fallback_gear_helmet
    GearSlot.ARMOR -> R.drawable.e7_fallback_gear_armor
    GearSlot.NECKLACE -> R.drawable.e7_fallback_gear_necklace
    GearSlot.RING -> R.drawable.e7_fallback_gear_ring
    GearSlot.BOOTS -> R.drawable.e7_fallback_gear_boots
    GearSlot.UNKNOWN -> null
}

@DrawableRes
internal fun gearItemBackgroundRes(rank: String?, hasGear: Boolean): Int = if (!hasGear) {
    R.drawable.e7_item_slot
} else {
    when (rank?.lowercase()) {
        "normal", "普通" -> R.drawable.e7_item_bg01
        "good", "优秀" -> R.drawable.e7_item_bg02
        "rare", "稀有" -> R.drawable.e7_item_bg03
        "heroic", "英雄" -> R.drawable.e7_item_bg04
        "epic", "传说" -> R.drawable.e7_item_bg05
        else -> R.drawable.e7_item_bg01
    }
}

@DrawableRes
internal fun gearEnhancePlusRes(): Int = R.drawable.e7_enhance_plus

@DrawableRes
internal fun gearEnhanceDigitRes(digit: Char): Int? = when (digit) {
    '0' -> R.drawable.e7_enhance_e0
    '1' -> R.drawable.e7_enhance_e1
    '2' -> R.drawable.e7_enhance_e2
    '3' -> R.drawable.e7_enhance_e3
    '4' -> R.drawable.e7_enhance_e4
    '5' -> R.drawable.e7_enhance_e5
    '6' -> R.drawable.e7_enhance_e6
    '7' -> R.drawable.e7_enhance_e7
    '8' -> R.drawable.e7_enhance_e8
    '9' -> R.drawable.e7_enhance_e9
    else -> null
}

@DrawableRes
internal fun powerDigitRes(digit: Char): Int? = when (digit) {
    '0' -> R.drawable.e7_power_p0
    '1' -> R.drawable.e7_power_p1
    '2' -> R.drawable.e7_power_p2
    '3' -> R.drawable.e7_power_p3
    '4' -> R.drawable.e7_power_p4
    '5' -> R.drawable.e7_power_p5
    '6' -> R.drawable.e7_power_p6
    '7' -> R.drawable.e7_power_p7
    '8' -> R.drawable.e7_power_p8
    '9' -> R.drawable.e7_power_p9
    else -> null
}

internal fun powerDigitWidth(digit: Char): Int = if (digit == '1') 24 else 46

@DrawableRes
internal fun speedBadgeRes(@Suppress("UNUSED_PARAMETER") speed25Count: Int): Int =
    R.drawable.e7_speed_badge_25_5

@DrawableRes
internal fun gearSetIconRes(setCode: String): Int? = when (setCode) {
    "set_max_hp" -> R.drawable.e7_set_health
    "set_def" -> R.drawable.e7_set_defense
    "set_att" -> R.drawable.e7_set_attack
    "set_speed" -> R.drawable.e7_set_speed
    "set_cri" -> R.drawable.e7_set_critical
    "set_acc" -> R.drawable.e7_set_hit
    "set_cri_dmg" -> R.drawable.e7_set_destruction
    "set_vampire" -> R.drawable.e7_set_lifesteal
    "set_counter" -> R.drawable.e7_set_counter
    "set_res" -> R.drawable.e7_set_resist
    "set_coop" -> R.drawable.e7_set_unity
    "set_rage" -> R.drawable.e7_set_rage
    "set_immune" -> R.drawable.e7_set_immunity
    "set_revenge" -> R.drawable.e7_set_revenge
    "set_scar" -> R.drawable.e7_set_injury
    "set_penetrate" -> R.drawable.e7_set_penetration
    "set_shield" -> R.drawable.e7_set_protection
    "set_torrent" -> R.drawable.e7_set_torrent
    "set_revenant" -> R.drawable.e7_set_reversal
    "set_riposte" -> R.drawable.e7_set_riposte
    "set_opener" -> R.drawable.e7_set_warfare
    "set_chase" -> R.drawable.e7_set_pursuit
    "set_weak" -> R.drawable.e7_set_weakening
    "set_might" -> R.drawable.e7_set_fervor
    else -> null
}

@DrawableRes
internal fun gearStatIconRes(statType: String): Int? = when (statType) {
    "Attack" -> R.drawable.e7_stat_attack
    "AttackPercent" -> R.drawable.e7_stat_attack_percent
    "Defense" -> R.drawable.e7_stat_defense
    "DefensePercent" -> R.drawable.e7_stat_defense_percent
    "Health" -> R.drawable.e7_stat_health
    "HealthPercent" -> R.drawable.e7_stat_health_percent
    "Speed" -> R.drawable.e7_stat_speed
    "CriticalHitChancePercent" -> R.drawable.e7_stat_crit_chance
    "CriticalHitDamagePercent" -> R.drawable.e7_stat_crit_damage
    "EffectivenessPercent" -> R.drawable.e7_stat_effectiveness
    "EffectResistancePercent" -> R.drawable.e7_stat_resistance
    else -> null
}

@DrawableRes
internal fun exclusiveEquipmentStatIconRes(statType: String, isPercent: Boolean): Int? {
    val gearStatType = when (statType.lowercase()) {
        "attack" -> if (isPercent) "AttackPercent" else "Attack"
        "health" -> if (isPercent) "HealthPercent" else "Health"
        "defense" -> if (isPercent) "DefensePercent" else "Defense"
        "speed" -> "Speed"
        "critical_chance" -> "CriticalHitChancePercent"
        "critical_damage" -> "CriticalHitDamagePercent"
        "effectiveness" -> "EffectivenessPercent"
        "effect_resistance" -> "EffectResistancePercent"
        else -> return null
    }
    return gearStatIconRes(gearStatType)
}

@DrawableRes
internal fun growthStatIconRes(label: String): Int? {
    val statType = when (label.lowercase()) {
        "attack" -> "Attack"
        "health" -> "Health"
        "defense" -> "Defense"
        "speed" -> "Speed"
        "critical hit rate", "critical hit chance" -> "CriticalHitChancePercent"
        "critical hit damage" -> "CriticalHitDamagePercent"
        "effectiveness" -> "EffectivenessPercent"
        "effect resistance" -> "EffectResistancePercent"
        else -> return null
    }
    return gearStatIconRes(statType)
}
