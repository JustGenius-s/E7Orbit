package com.e7orbit.ui

import androidx.annotation.DrawableRes
import com.e7orbit.R
import com.e7orbit.data.GearSlot

@DrawableRes
internal fun gearSlotIconRes(slot: GearSlot): Int? = when (slot) {
    GearSlot.WEAPON -> R.drawable.e7_gear_weapon
    GearSlot.HELMET -> R.drawable.e7_gear_helmet
    GearSlot.ARMOR -> R.drawable.e7_gear_armor
    GearSlot.NECKLACE -> R.drawable.e7_gear_necklace
    GearSlot.RING -> R.drawable.e7_gear_ring
    GearSlot.BOOTS -> R.drawable.e7_gear_boots
    GearSlot.UNKNOWN -> null
}

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
