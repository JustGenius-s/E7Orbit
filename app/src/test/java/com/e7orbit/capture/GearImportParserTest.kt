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
    fun parsesConvertedFribbelsExportWithEquippedRelationships() {
        val parsed = GearImportParser.parseExport(
            """
            {
              "items": [
                {
                  "id":"gear-uuid-1",
                  "gear":"Weapon",
                  "rank":"Epic",
                  "set":"SpeedSet",
                  "level":90,
                  "enhance":15,
                  "main":{"type":"Attack","value":525},
                  "substats":[{"type":"Speed","value":17}],
                  "equippedById":"hero-uuid-1",
                  "locked":true
                }
              ],
              "heroes": [
                {
                  "id":"hero-uuid-1",
                  "code":"c1001",
                  "name":"Ruele of Light",
                  "stars":6,
                  "awaken":6,
                  "equipment":{
                    "Weapon":{
                      "id":"gear-uuid-1",
                      "gear":"Weapon",
                      "rank":"Epic",
                      "set":"SpeedSet",
                      "level":90,
                      "enhance":15,
                      "main":{"type":"Attack","value":525},
                      "substats":[{"type":"Speed","value":17}],
                      "locked":true
                    }
                  }
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(1, parsed.heroes.size)
        assertEquals(1, parsed.gears.size)
        assertEquals("c1001", parsed.heroes.single().code)
        assertEquals(parsed.heroes.single().id, parsed.gears.single().equippedHeroId)
        assertEquals(GearSlot.WEAPON, parsed.gears.single().slot)
        assertEquals("set_speed", parsed.gears.single().setCode)
        assertEquals("速度套装", parsed.gears.single().setName)
        assertEquals("传说", parsed.gears.single().rank)
        assertEquals(17.0, parsed.gears.single().substats.single().value, 0.0)
        assertTrue(parsed.gears.single().locked)
    }

    @Test
    fun parsesHeroesFromCompatibleExport() {
        val heroes = GearImportParser.parseHeroExport(
            """
            {
              "items": [],
              "heroes": [
                {"id":987654321,"name":"Ruele of Light","g":6,"z":6},
                {"id":123456789,"name":"Arbiter Vildred","stars":6,"awaken":5}
              ]
            }
            """.trimIndent(),
        )

        assertEquals(2, heroes.size)
        assertEquals("Ruele of Light", heroes[0].name)
        assertEquals(6, heroes[1].stars)
        assertEquals(5, heroes[1].awaken)
    }

    @Test
    fun parsesScannedHeroIdentity() {
        val unit = Json.parseToJsonElement(
            """{"id":987654321,"code":"c1001","name":"Ruele of Light","g":6,"z":6,"artifactName":"Rod of Amaryllis","artifactLevel":"27"}""",
        ).jsonObject

        val hero = GearImportParser.parseHero(unit)

        assertNotNull(hero)
        assertEquals(987654321L, hero?.id)
        assertEquals("c1001", hero?.code)
        assertEquals("Ruele of Light", hero?.name)
        assertEquals(6, hero?.stars)
        assertEquals(6, hero?.awaken)
        assertEquals("Rod of Amaryllis", hero?.artifactName)
        assertEquals(27, hero?.artifactLevel)
    }

    @Test
    fun parsesScannedArtifactCodeAndIgnoresNoneValues() {
        val withCode = Json.parseToJsonElement(
            """{"id":1,"name":"Hero","artifactCode":"efh01","artifactName":"None"}""",
        ).jsonObject
        val withoutArtifact = Json.parseToJsonElement(
            """{"id":2,"name":"Hero","artifactCode":"undefined","artifactLevel":"None"}""",
        ).jsonObject

        assertEquals("efh01", GearImportParser.parseHero(withCode)?.artifactCode)
        assertEquals(null, GearImportParser.parseHero(withCode)?.artifactName)
        assertEquals(null, GearImportParser.parseHero(withoutArtifact)?.artifactCode)
        assertEquals(null, GearImportParser.parseHero(withoutArtifact)?.artifactLevel)
    }

    @Test
    fun ignoresScannedHeroWithoutStableIdentity() {
        val missingName = Json.parseToJsonElement("""{"id":123}""").jsonObject
        val missingId = Json.parseToJsonElement("""{"name":"Ruele of Light"}""").jsonObject

        assertEquals(null, GearImportParser.parseHero(missingName))
        assertEquals(null, GearImportParser.parseHero(missingId))
    }

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
