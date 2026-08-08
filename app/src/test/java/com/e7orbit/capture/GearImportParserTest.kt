package com.e7orbit.capture

import com.e7orbit.data.GearSlot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GearImportParserTest {
    @Test
    fun parsesFribbelsGearAndAggregatesSubstats() {
        val item = Json.parseToJsonElement(
            """
            {
              "code":"test_helm",
              "f":"set_counter",
              "g":5,
              "id":1234567890,
              "l":true,
              "level":90,
              "mainStatValue":2835,
              "op":[
                ["max_hp",567,null,null,0],
                ["max_hp_rate",0.08],
                ["att_rate",0.07],
                ["def_rate",0.07],
                ["cri",0.04],
                ["def_rate",0.05],
                ["cri",0.05],
                ["max_hp_rate",0.01,"u"],
                ["cri",0.03,"c","change2_cri_1_1"]
              ],
              "p":987654321,
              "type":"helm"
            }
            """.trimIndent(),
        ).jsonObject

        val gear = GearImportParser.parseItem(item)

        assertNotNull(gear)
        gear!!
        assertEquals(GearSlot.HELMET, gear.slot)
        assertEquals("反击套装", gear.setName)
        assertEquals("传说", gear.rank)
        assertEquals(90, gear.level)
        assertEquals(12, gear.enhance)
        assertEquals("Health", gear.mainStat.type)
        assertEquals(2835.0, gear.mainStat.value, 0.0)
        assertEquals(9.0, gear.substats.first { it.type == "HealthPercent" }.value, 0.0)
        assertEquals(12.0, gear.substats.first { it.type == "DefensePercent" }.value, 0.0)
        assertEquals(12.0, gear.substats.first { it.type == "CriticalHitChancePercent" }.value, 0.0)
        assertEquals(2, gear.substats.first { it.type == "CriticalHitChancePercent" }.rolls)
        assertTrue(gear.substats.first { it.type == "CriticalHitChancePercent" }.modified)
    }

    @Test
    fun ignoresNonEquipmentEntriesWithoutSetAndLevel() {
        val item = Json.parseToJsonElement(
            """{"code":"efw15","g":4,"id":1646560850,"op":[["att",18]]}""",
        ).jsonObject

        assertEquals(null, GearImportParser.parseItem(item))
    }
}
