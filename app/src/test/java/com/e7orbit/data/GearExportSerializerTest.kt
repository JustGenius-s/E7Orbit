package com.e7orbit.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GearExportSerializerTest {
    @Test
    fun writesFribbelsMergeFormat() {
        val output = GearExportSerializer.serialize(
            listOf(
                E7Gear(
                    id = 1_234_567_890,
                    code = "test_helm",
                    slot = GearSlot.HELMET,
                    setCode = "set_speed",
                    setName = "速度套装",
                    rank = "传说",
                    level = 90,
                    enhance = 15,
                    mainStat = E7GearStat("Health", 2835.0),
                    substats = listOf(
                        E7GearStat("Speed", 18.0, rolls = 3),
                        E7GearStat("EffectivenessPercent", 7.0, rolls = 1, modified = true),
                    ),
                    locked = true,
                    equippedHeroId = 987_654_321,
                ),
            ),
        )

        val root = Json.parseToJsonElement(output).jsonObject
        val item = root.getValue("items").jsonArray.single().jsonObject
        assertEquals("Helmet", item.getValue("gear").jsonPrimitive.content)
        assertEquals("Epic", item.getValue("rank").jsonPrimitive.content)
        assertEquals("SpeedSet", item.getValue("set").jsonPrimitive.content)
        assertEquals(90, item.getValue("level").jsonPrimitive.long)
        assertEquals(15, item.getValue("enhance").jsonPrimitive.long)
        assertEquals("1234567890", item.getValue("ingameId").jsonPrimitive.content)
        assertEquals("987654321", item.getValue("ingameEquippedId").jsonPrimitive.content)
        assertTrue(item.getValue("locked").jsonPrimitive.boolean)
        assertEquals("Health", item.getValue("main").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(2835.0, item.getValue("main").jsonObject.getValue("value").jsonPrimitive.double, 0.0)
        assertEquals(
            3,
            item.getValue("substats").jsonArray[0].jsonObject
                .getValue("rolls").jsonPrimitive.long,
        )
        assertEquals(
            true,
            item.getValue("substats").jsonArray[1].jsonObject
                .getValue("modified").jsonPrimitive.boolean,
        )
    }
}
