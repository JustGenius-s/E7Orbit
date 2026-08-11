package com.e7orbit.ui

import androidx.annotation.DrawableRes
import com.e7orbit.R
import com.e7orbit.data.GearSlot

@DrawableRes
internal fun gearItemIconRes(code: String, slot: GearSlot): Int? =
    exactGearItemIconRes(code) ?: inferredGearItemIconRes(code, slot)

@DrawableRes
private fun inferredGearItemIconRes(code: String, slot: GearSlot): Int? = when {
    code.startsWith("eal85") -> gearIconBySlot(
        slot,
        R.drawable.e7_item_icon_eq_weapon_alchemi_001,
        R.drawable.e7_item_icon_eq_helm_alchemi_001,
        R.drawable.e7_item_icon_eq_armor_alchemi_001,
        R.drawable.e7_item_icon_eq_neck_alchemi_001,
        R.drawable.e7_item_icon_eq_ring_alchemi_001,
        R.drawable.e7_item_icon_eq_boot_alchemi_001,
    )
    code.startsWith("eap3") -> gearIconBySlot(
        slot,
        R.drawable.e7_item_icon_eq_weapon_attpack003,
        R.drawable.e7_item_icon_eq_helm_attpack003,
        R.drawable.e7_item_icon_eq_armor_attpack003,
        R.drawable.e7_item_icon_eq_neck_attpack003,
        R.drawable.e7_item_icon_eq_ring_attpack003,
        R.drawable.e7_item_icon_eq_boot_attpack003,
    )
    code.startsWith("eih8") -> gearIconBySlot(
        slot,
        R.drawable.e7_item_icon_eq_weapon_insect008,
        R.drawable.e7_item_icon_eq_helm_insect008,
        R.drawable.e7_item_icon_eq_armor_insect008,
        R.drawable.e7_item_icon_eq_neck_insect008,
        R.drawable.e7_item_icon_eq_ring_insect008,
        R.drawable.e7_item_icon_eq_boot_insect008,
    )
    code.startsWith("ewb1") -> gearIconBySlot(
        slot,
        R.drawable.e7_item_icon_eq_weapon_webevent001,
        R.drawable.e7_item_icon_eq_helm_webevent001,
        R.drawable.e7_item_icon_eq_armor_webevent001,
        R.drawable.e7_item_icon_eq_neck_webevent001,
        R.drawable.e7_item_icon_eq_ring_webevent001,
        R.drawable.e7_item_icon_eq_boot_webevent001,
    )
    else -> inferredArenaIconRes(code, slot)
}

@DrawableRes
private fun inferredArenaIconRes(code: String, slot: GearSlot): Int? = when {
    code.startsWith("eah17") -> arenaGearIconRes(12, slot)
    code.startsWith("eah18") -> arenaGearIconRes(13, slot)
    code.startsWith("eah19") -> arenaGearIconRes(14, slot)
    code.startsWith("eah20") -> arenaGearIconRes(15, slot)
    code.startsWith("eah21") -> arenaGearIconRes(16, slot)
    else -> null
}

@DrawableRes
private fun arenaGearIconRes(season: Int, slot: GearSlot): Int? = when (season) {
    12 -> gearIconBySlot(
        slot,
        R.drawable.e7_item_icon_eq_weapon_arena012,
        R.drawable.e7_item_icon_eq_helm_arena012,
        R.drawable.e7_item_icon_eq_armor_arena012,
        R.drawable.e7_item_icon_eq_neck_arena012,
        R.drawable.e7_item_icon_eq_ring_arena012,
        R.drawable.e7_item_icon_eq_boot_arena012,
    )
    13 -> gearIconBySlot(
        slot,
        R.drawable.e7_item_icon_eq_weapon_arena013,
        R.drawable.e7_item_icon_eq_helm_arena013,
        R.drawable.e7_item_icon_eq_armor_arena013,
        R.drawable.e7_item_icon_eq_neck_arena013,
        R.drawable.e7_item_icon_eq_ring_arena013,
        R.drawable.e7_item_icon_eq_boot_arena013,
    )
    14 -> gearIconBySlot(
        slot,
        R.drawable.e7_item_icon_eq_weapon_arena014,
        R.drawable.e7_item_icon_eq_helm_arena014,
        R.drawable.e7_item_icon_eq_armor_arena014,
        R.drawable.e7_item_icon_eq_neck_arena014,
        R.drawable.e7_item_icon_eq_ring_arena014,
        R.drawable.e7_item_icon_eq_boot_arena014,
    )
    15 -> gearIconBySlot(
        slot,
        R.drawable.e7_item_icon_eq_weapon_arena015,
        R.drawable.e7_item_icon_eq_helm_arena015,
        R.drawable.e7_item_icon_eq_armor_arena015,
        R.drawable.e7_item_icon_eq_neck_arena015,
        R.drawable.e7_item_icon_eq_ring_arena015,
        R.drawable.e7_item_icon_eq_boot_arena015,
    )
    16 -> gearIconBySlot(
        slot,
        R.drawable.e7_item_icon_eq_weapon_arena016,
        R.drawable.e7_item_icon_eq_helm_arena016,
        R.drawable.e7_item_icon_eq_armor_arena016,
        R.drawable.e7_item_icon_eq_neck_arena016,
        R.drawable.e7_item_icon_eq_ring_arena016,
        R.drawable.e7_item_icon_eq_boot_arena016,
    )
    else -> null
}

@DrawableRes
private fun gearIconBySlot(
    slot: GearSlot,
    @DrawableRes weapon: Int,
    @DrawableRes helmet: Int,
    @DrawableRes armor: Int,
    @DrawableRes necklace: Int,
    @DrawableRes ring: Int,
    @DrawableRes boots: Int,
): Int? = when (slot) {
    GearSlot.WEAPON -> weapon
    GearSlot.HELMET -> helmet
    GearSlot.ARMOR -> armor
    GearSlot.NECKLACE -> necklace
    GearSlot.RING -> ring
    GearSlot.BOOTS -> boots
    GearSlot.UNKNOWN -> null
}
