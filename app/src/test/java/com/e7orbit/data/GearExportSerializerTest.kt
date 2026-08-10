package com.e7orbit.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GearExportSerializerTest {
    @Test
    fun writesScannerFormatWithRawFieldsAndHeroes() {
        val gear = testGear()
        val rawItem = Json.parseToJsonElement(
            """{
                "code":"test_helm","ct":123,"e":456,"f":"set_speed","g":5,
                "id":1234567890,"l":true,"level":90,"mainStatBaseValue":567,
                "mainStatId":"test_helm_m","mainStatType":"max_hp",
                "mainStatValue":2835,"mg":311111,"op":[["max_hp",567],["speed",18]],
                "s":"abcd","statMultiplier":2.25,"tierMultiplier":1,"type":"helm",
                "p":987654321
            }""".trimIndent(),
        ).jsonObject
        val rawHero = Json.parseToJsonElement(
            """{"code":"c0001","g":6,"id":123,"name":"Test Hero","z":6}""",
        ).jsonObject

        val root = Json.parseToJsonElement(
            GearExportSerializer.serializeScannerExport(
                gears = listOf(gear),
                rawItems = listOf(rawItem),
                rawHeroes = listOf(rawHero),
            ),
        ).jsonObject
        val item = root.getValue("items").jsonArray.single().jsonObject
        val requiredRawFields = setOf(
            "code", "ct", "e", "f", "g", "id", "level", "mainStatType",
            "mainStatValue", "mg", "op", "s", "type",
        )
        val requiredConvertedFields = setOf(
            "gear", "rank", "set", "enhance", "main", "substats",
            "ingameId", "ingameEquippedId",
        )
        assertTrue(item.keys.containsAll(requiredRawFields))
        assertTrue(item.keys.containsAll(requiredConvertedFields))
        assertEquals(1_234_567_890, item.getValue("id").jsonPrimitive.long)
        assertEquals("987654321", item.getValue("ingameEquippedId").jsonPrimitive.content)
        val hero = root.getValue("heroes").jsonArray.single().jsonObject
        assertEquals(6, hero.getValue("stars").jsonPrimitive.long)
        assertEquals(6, hero.getValue("awaken").jsonPrimitive.long)
    }

    @Test
    fun rewritesEquipmentAssignmentsForPlanExport() {
        val first = testGear()
        val second = testGear().copy(
            id = 2_345_678_901,
            code = "test_ring",
            slot = GearSlot.RING,
            equippedHeroId = null,
        )
        val rawFirst = Json.parseToJsonElement(
            """{"id":1234567890,"p":987654321,"gear":"Helmet","rank":"Epic","set":"SpeedSet","level":90,"enhance":15,"main":{"type":"Health","value":2835},"substats":[],"ingameId":1234567890,"ingameEquippedId":"987654321"}""",
        ).jsonObject
        val rawSecond = Json.parseToJsonElement(
            """{"id":2345678901,"gear":"Ring","rank":"Epic","set":"SpeedSet","level":90,"enhance":15,"main":{"type":"Health","value":2835},"substats":[],"ingameId":2345678901,"ingameEquippedId":"undefined"}""",
        ).jsonObject
        val source = Json.parseToJsonElement(
            GearExportSerializer.serializeScannerExport(
                gears = listOf(first, second),
                rawItems = listOf(rawFirst, rawSecond),
                rawHeroes = emptyList(),
            ),
        ).toString()

        val root = Json.parseToJsonElement(
            GearExportSerializer.rewriteAssignments(
                export = source,
                gears = listOf(first, second),
                assignments = mapOf(second.id to 123L),
            ),
        ).jsonObject
        val items = root.getValue("items").jsonArray.map { it.jsonObject }
        val rewrittenFirst = items.first { it.getValue("id").jsonPrimitive.long == first.id }
        val rewrittenSecond = items.first { it.getValue("id").jsonPrimitive.long == second.id }
        assertEquals(
            "undefined",
            rewrittenFirst.getValue("ingameEquippedId").jsonPrimitive.content,
        )
        assertEquals(null, rewrittenFirst["p"]?.jsonPrimitive?.contentOrNull)
        assertEquals("123", rewrittenSecond.getValue("ingameEquippedId").jsonPrimitive.content)
        assertEquals(123L, rewrittenSecond.getValue("p").jsonPrimitive.long)
    }

    @Test
    fun normalizesNestedHeroEquipmentWithoutDuplicatingOldAssignments() {
        val gear = testGear().copy(
            id = 42L,
            equippedHeroId = 7L,
        )
        val export = """
            {
              "items": [],
              "heroes": [{
                "id": 7,
                "name": "Test Hero",
                "equipment": {
                  "Helmet": {
                    "id": 42,
                    "gear": "Helmet",
                    "rank": "Epic",
                    "set": "SpeedSet",
                    "level": 90,
                    "enhance": 15,
                    "main": {"type": "Health", "value": 2835},
                    "substats": []
                  }
                }
              }]
            }
        """.trimIndent()

        val root = Json.parseToJsonElement(
            GearExportSerializer.rewriteAssignments(
                export = export,
                gears = listOf(gear),
                assignments = emptyMap(),
            ),
        ).jsonObject
        val item = root.getValue("items").jsonArray.single().jsonObject
        val hero = root.getValue("heroes").jsonArray.single().jsonObject
        assertEquals(null, item["ingameEquippedId"])
        assertEquals(null, hero["equipment"])
    }

    @Test
    fun writesFribbelsMergeFormat() {
        val output = GearExportSerializer.serialize(listOf(testGear()))

        val root = Json.parseToJsonElement(output).jsonObject
        val item = root.getValue("items").jsonArray.single().jsonObject
        assertEquals("Helmet", item.getValue("gear").jsonPrimitive.content)
        assertEquals("Epic", item.getValue("rank").jsonPrimitive.content)
        assertEquals("SpeedSet", item.getValue("set").jsonPrimitive.content)
        assertEquals(90, item.getValue("level").jsonPrimitive.long)
        assertEquals(15, item.getValue("enhance").jsonPrimitive.long)
        assertEquals(1_234_567_890, item.getValue("id").jsonPrimitive.long)
        assertEquals(1_234_567_890, item.getValue("ingameId").jsonPrimitive.long)
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

    private fun testGear(): E7Gear =
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
        )
}
