package com.e7orbit.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

object GearExportSerializer {
    fun serialize(gears: List<E7Gear>): String = JsonObject(
        mapOf("items" to JsonArray(gears.map(::serializeGear))),
    ).toString()

    fun serializeScannerExport(
        gears: List<E7Gear>,
        rawItems: List<JsonObject>,
        rawHeroes: List<JsonObject>,
    ): String {
        val gearsById = gears.associateBy(E7Gear::id)
        val items = rawItems.mapNotNull { raw ->
            val id = raw["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val gear = gearsById[id] ?: return@mapNotNull null
            serializeScannerGear(raw, gear)
        }
        val heroes = rawHeroes.map(::serializeHero)
        return JsonObject(
            mapOf(
                "items" to JsonArray(items),
                "heroes" to JsonArray(heroes),
            ),
        ).toString()
    }

    private fun serializeScannerGear(raw: JsonObject, gear: E7Gear): JsonObject = JsonObject(
        buildMap {
            putAll(raw)
            put("gear", JsonPrimitive(SLOT_NAMES.getValue(gear.slot)))
            put("rank", JsonPrimitive(RANK_NAMES[gear.rank] ?: gear.rank))
            put("set", JsonPrimitive(SET_NAMES[gear.setCode] ?: gear.setCode))
            put("name", JsonPrimitive(raw["name"]?.jsonPrimitive?.contentOrNull ?: "Unknown"))
            put("enhance", JsonPrimitive(gear.enhance))
            put("main", serializeScannerMainStat(gear.mainStat))
            put("substats", JsonArray(gear.substats.map(::serializeScannerSubstat)))
            put("id", JsonPrimitive(gear.id))
            put("ingameId", JsonPrimitive(gear.id))
            put(
                "ingameEquippedId",
                JsonPrimitive(raw["p"]?.jsonPrimitive?.contentOrNull ?: "undefined"),
            )
        },
    )

    private fun serializeHero(raw: JsonObject): JsonObject = JsonObject(
        buildMap {
            putAll(raw)
            raw["g"]?.let { put("stars", it) }
            raw["z"]?.let { put("awaken", it) }
        },
    )

    private fun serializeGear(gear: E7Gear): JsonObject = JsonObject(
        buildMap {
            put("gear", JsonPrimitive(SLOT_NAMES.getValue(gear.slot)))
            put("rank", JsonPrimitive(RANK_NAMES[gear.rank] ?: gear.rank))
            put("set", JsonPrimitive(SET_NAMES[gear.setCode] ?: gear.setCode))
            put("level", JsonPrimitive(gear.level))
            put("enhance", JsonPrimitive(gear.enhance))
            put("main", serializeStat(gear.mainStat))
            put("substats", JsonArray(gear.substats.map(::serializeStat)))
            put("id", JsonPrimitive(gear.id))
            put("ingameId", JsonPrimitive(gear.id))
            gear.equippedHeroId?.let {
                put("ingameEquippedId", JsonPrimitive(it.toString()))
            }
            put("locked", JsonPrimitive(gear.locked))
        },
    )

    private fun serializeStat(stat: E7GearStat): JsonObject = JsonObject(
        buildMap {
            put("type", JsonPrimitive(stat.type))
            put("value", stat.value.jsonNumber())
            stat.rolls?.let { put("rolls", JsonPrimitive(it)) }
            put("modified", JsonPrimitive(stat.modified))
        },
    )

    private fun serializeScannerMainStat(stat: E7GearStat): JsonObject = JsonObject(
        mapOf(
            "type" to JsonPrimitive(stat.type),
            "value" to stat.value.jsonNumber(),
        ),
    )

    private fun serializeScannerSubstat(stat: E7GearStat): JsonObject = JsonObject(
        buildMap {
            put("type", JsonPrimitive(stat.type))
            put("value", stat.value.jsonNumber())
            stat.rolls?.let { put("rolls", JsonPrimitive(it)) }
            if (stat.modified) put("modified", JsonPrimitive(true))
        },
    )

    private fun Double.jsonNumber(): JsonElement = if (isFinite()) {
        if (this % 1.0 == 0.0) JsonPrimitive(toLong()) else JsonPrimitive(this)
    } else {
        JsonNull
    }

    private val SLOT_NAMES = mapOf(
        GearSlot.WEAPON to "Weapon",
        GearSlot.HELMET to "Helmet",
        GearSlot.ARMOR to "Armor",
        GearSlot.NECKLACE to "Necklace",
        GearSlot.RING to "Ring",
        GearSlot.BOOTS to "Boots",
        GearSlot.UNKNOWN to "Unknown",
    )

    private val RANK_NAMES = mapOf(
        "普通" to "Normal",
        "优秀" to "Good",
        "稀有" to "Rare",
        "英雄" to "Heroic",
        "传说" to "Epic",
        "未知" to "Unknown",
    )

    private val SET_NAMES = mapOf(
        "set_acc" to "HitSet",
        "set_att" to "AttackSet",
        "set_coop" to "UnitySet",
        "set_counter" to "CounterSet",
        "set_cri_dmg" to "DestructionSet",
        "set_cri" to "CriticalSet",
        "set_def" to "DefenseSet",
        "set_immune" to "ImmunitySet",
        "set_max_hp" to "HealthSet",
        "set_penetrate" to "PenetrationSet",
        "set_rage" to "RageSet",
        "set_res" to "ResistSet",
        "set_revenge" to "RevengeSet",
        "set_scar" to "InjurySet",
        "set_speed" to "SpeedSet",
        "set_vampire" to "LifestealSet",
        "set_shield" to "ProtectionSet",
        "set_torrent" to "TorrentSet",
        "set_revenant" to "ReversalSet",
        "set_riposte" to "RiposteSet",
        "set_chase" to "PursuitSet",
        "set_opener" to "WarfareSet",
        "set_weak" to "WeakeningSet",
        "set_might" to "FervorSet",
    )
}
