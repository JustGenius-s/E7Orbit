package com.e7orbit.ui

import com.e7orbit.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GearIconAssetsTest {
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
