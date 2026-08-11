package com.e7orbit.ui

import com.e7orbit.R
import com.e7orbit.data.GearSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GearIconAssetsTest {
    @Test
    fun `unmatched gear uses a real item icon for each slot`() {
        assertEquals(R.drawable.e7_fallback_gear_weapon, gearSlotIconRes(GearSlot.WEAPON))
        assertEquals(R.drawable.e7_fallback_gear_helmet, gearSlotIconRes(GearSlot.HELMET))
        assertEquals(R.drawable.e7_fallback_gear_armor, gearSlotIconRes(GearSlot.ARMOR))
        assertEquals(R.drawable.e7_fallback_gear_necklace, gearSlotIconRes(GearSlot.NECKLACE))
        assertEquals(R.drawable.e7_fallback_gear_ring, gearSlotIconRes(GearSlot.RING))
        assertEquals(R.drawable.e7_fallback_gear_boots, gearSlotIconRes(GearSlot.BOOTS))
    }

    @Test
    fun `game item backgrounds follow gear rarity`() {
        assertEquals(R.drawable.e7_item_bg01, gearItemBackgroundRes("普通", hasGear = true))
        assertEquals(R.drawable.e7_item_bg02, gearItemBackgroundRes("优秀", hasGear = true))
        assertEquals(R.drawable.e7_item_bg03, gearItemBackgroundRes("稀有", hasGear = true))
        assertEquals(R.drawable.e7_item_bg04, gearItemBackgroundRes("英雄", hasGear = true))
        assertEquals(R.drawable.e7_item_bg05, gearItemBackgroundRes("传说", hasGear = true))
        assertEquals(R.drawable.e7_item_slot, gearItemBackgroundRes(null, hasGear = false))
    }

    @Test
    fun `enhancement glyphs use the e series assets`() {
        assertEquals(R.drawable.e7_enhance_plus, gearEnhancePlusRes())
        assertEquals(R.drawable.e7_enhance_e0, gearEnhanceDigitRes('0'))
        assertEquals(R.drawable.e7_enhance_e5, gearEnhanceDigitRes('5'))
        assertEquals(R.drawable.e7_enhance_e9, gearEnhanceDigitRes('9'))
        assertNull(gearEnhanceDigitRes('+'))
    }

    @Test
    fun `power digits use the p series assets`() {
        assertEquals(R.drawable.e7_power_p0, powerDigitRes('0'))
        assertEquals(R.drawable.e7_power_p1, powerDigitRes('1'))
        assertEquals(R.drawable.e7_power_p9, powerDigitRes('9'))
        assertEquals(24, powerDigitWidth('1'))
        assertEquals(46, powerDigitWidth('8'))
        assertNull(powerDigitRes('+'))
    }

    @Test
    fun `speed badge currently uses the verified 25 speed stage five asset`() {
        assertEquals(R.drawable.e7_speed_badge_25_5, speedBadgeRes(0))
        assertEquals(R.drawable.e7_speed_badge_25_5, speedBadgeRes(1))
        assertEquals(R.drawable.e7_speed_badge_25_5, speedBadgeRes(5))
        assertEquals(R.drawable.e7_speed_badge_25_5, speedBadgeRes(8))
    }

    @Test
    fun `gear codes map to their extracted item icons`() {
        assertEquals(
            R.drawable.e7_item_icon_eq_weapon_pas006_u,
            gearItemIconRes("ecp6w_u", GearSlot.WEAPON),
        )
        assertEquals(
            R.drawable.e7_item_icon_eq_weapon_bone007,
            gearItemIconRes("ecw6w_u", GearSlot.WEAPON),
        )
        assertEquals(
            R.drawable.e7_item_icon_eq_ring_orb107,
            gearItemIconRes("ecb6r_u", GearSlot.RING),
        )
        assertEquals(
            R.drawable.e7_item_icon_eq_neck_alchemi_001,
            gearItemIconRes("eal85n_u", GearSlot.NECKLACE),
        )
        assertNull(gearItemIconRes("unknown", GearSlot.WEAPON))
    }

    @Test
    fun `exclusive equipment stats map to their corresponding icons`() {
        assertEquals(R.drawable.e7_stat_attack, exclusiveEquipmentStatIconRes("attack", false))
        assertEquals(R.drawable.e7_stat_attack_percent, exclusiveEquipmentStatIconRes("attack", true))
        assertEquals(R.drawable.e7_stat_health, exclusiveEquipmentStatIconRes("health", false))
        assertEquals(R.drawable.e7_stat_health_percent, exclusiveEquipmentStatIconRes("health", true))
        assertEquals(R.drawable.e7_stat_defense, exclusiveEquipmentStatIconRes("defense", false))
        assertEquals(R.drawable.e7_stat_defense_percent, exclusiveEquipmentStatIconRes("defense", true))
        assertEquals(R.drawable.e7_stat_speed, exclusiveEquipmentStatIconRes("speed", false))
        assertEquals(
            R.drawable.e7_stat_crit_chance,
            exclusiveEquipmentStatIconRes("critical_chance", true),
        )
        assertEquals(
            R.drawable.e7_stat_crit_damage,
            exclusiveEquipmentStatIconRes("critical_damage", true),
        )
        assertEquals(
            R.drawable.e7_stat_effectiveness,
            exclusiveEquipmentStatIconRes("effectiveness", true),
        )
        assertEquals(
            R.drawable.e7_stat_resistance,
            exclusiveEquipmentStatIconRes("effect_resistance", true),
        )
        assertNull(exclusiveEquipmentStatIconRes("unknown", false))
    }
}
