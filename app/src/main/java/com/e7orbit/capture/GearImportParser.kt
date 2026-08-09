package com.e7orbit.capture

import com.e7orbit.data.E7Gear
import com.e7orbit.data.E7GearStat
import com.e7orbit.data.E7ScannedHero
import com.e7orbit.data.GearSlot
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.math.round

internal object GearImportParser {
    data class ParsedExport(
        val gears: List<E7Gear>,
        val heroes: List<E7ScannedHero>,
    )

    fun parseExport(payload: String): ParsedExport {
        val root = Json.parseToJsonElement(payload).jsonObject
        val heroObjects = root["heroes"]?.jsonArray
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
        val heroes = heroObjects.mapNotNull(::parseHero)
        val topLevelItems = root["items"]?.jsonArray
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
        val parsed = LinkedHashMap<Long, E7Gear>()
        topLevelItems.forEach { item ->
            (parseItem(item) ?: parseConvertedItem(item))?.let { parsed[it.id] = it }
        }
        heroObjects.forEach { hero ->
            val heroId = hero.stableId("id") ?: return@forEach
            val equipment = hero["equipment"] as? JsonObject ?: return@forEach
            equipment.values.forEach { element ->
                val item = element as? JsonObject ?: return@forEach
                parseConvertedItem(item, equippedHeroId = heroId)?.let { parsed[it.id] = it }
            }
        }
        return ParsedExport(gears = parsed.values.toList(), heroes = heroes)
    }

    fun parseHeroExport(payload: String): List<E7ScannedHero> = parseExport(payload).heroes

    fun parseHero(unit: JsonObject): E7ScannedHero? {
        val name = unit.string("name")?.takeIf(String::isNotBlank) ?: return null
        return E7ScannedHero(
            id = unit.stableId("id") ?: return null,
            name = name,
            stars = unit.int("g") ?: unit.int("stars"),
            awaken = unit.int("z") ?: unit.int("awaken"),
        )
    }

    fun parseItem(item: JsonObject): E7Gear? {
        val setCode = item.string("f") ?: return null
        val level = item.int("level") ?: return null
        val operations = item["op"] as? JsonArray ?: return null
        if (operations.isEmpty()) return null

        val rank = RANKS.getOrElse(item.int("g") ?: 0) { "未知" }
        val mainOperation = operations.firstOrNull().operation() ?: return null
        val mainType = STAT_TYPES[mainOperation.type] ?: mainOperation.type
        val mainValue = item.double("mainStatValue") ?: mainOperation.value
        val substats = aggregateSubstats(operations.drop(1))

        return E7Gear(
            id = item.long("id") ?: return null,
            code = item.string("code").orEmpty(),
            slot = parseSlot(item.string("type"), item.string("code")),
            setCode = setCode,
            setName = SET_NAMES[setCode] ?: setCode,
            rank = rank,
            level = level,
            enhance = calculateEnhance(rank, operations.size - 1),
            mainStat = E7GearStat(
                type = mainType,
                value = normalizeValue(mainOperation.type, mainValue),
            ),
            substats = substats,
            locked = item.boolean("l") ?: false,
            equippedHeroId = item.long("p"),
        )
    }

    private fun parseConvertedItem(
        item: JsonObject,
        equippedHeroId: Long? = null,
    ): E7Gear? {
        val slot = CONVERTED_SLOTS[item.string("gear")] ?: return null
        val setValue = item.string("set") ?: return null
        val setCode = CONVERTED_SETS[setValue] ?: setValue.takeIf { it.startsWith("set_") }
            ?: return null
        val main = item["main"] as? JsonObject ?: return null
        val mainType = main.string("type") ?: return null
        val mainValue = main.double("value") ?: return null
        val sourceId = item.string("ingameId")
            ?.takeUnless { it == "undefined" }
            ?: item.string("id")
            ?: return null
        val id = sourceId.toLongOrNull() ?: stableLongId(sourceId)
        val substats = (item["substats"] as? JsonArray)
            ?.mapNotNull { element ->
                val stat = element as? JsonObject ?: return@mapNotNull null
                E7GearStat(
                    type = stat.string("type") ?: return@mapNotNull null,
                    value = stat.double("value") ?: return@mapNotNull null,
                    rolls = stat.int("rolls"),
                    modified = stat.boolean("modified") ?: false,
                )
            }
            .orEmpty()
        return E7Gear(
            id = id,
            code = item.string("code") ?: item.string("name").orEmpty(),
            slot = slot,
            setCode = setCode,
            setName = SET_NAMES[setCode] ?: setValue,
            rank = CONVERTED_RANKS[item.string("rank")] ?: item.string("rank").orEmpty(),
            level = item.int("level") ?: return null,
            enhance = item.int("enhance") ?: 0,
            mainStat = E7GearStat(type = mainType, value = mainValue),
            substats = substats,
            locked = item.boolean("locked") ?: false,
            equippedHeroId = equippedHeroId
                ?: item.stableId("equippedById")
                ?: item.stableId("ingameEquippedId"),
        )
    }

    private fun stableLongId(value: String): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return ByteBuffer.wrap(digest).long and Long.MAX_VALUE
    }

    private fun aggregateSubstats(elements: List<JsonElement>): List<E7GearStat> {
        val values = linkedMapOf<String, Double>()
        val rolls = linkedMapOf<String, Int>()
        val modified = mutableSetOf<String>()
        for (element in elements) {
            val operation = element.operation() ?: continue
            val type = STAT_TYPES[operation.type] ?: operation.type
            val existing = type in values
            values[type] = (values[type] ?: 0.0) + normalizeValue(operation.type, operation.value)
            if (!existing) {
                rolls[type] = 1
            } else if (operation.annotation !in setOf("u", "c")) {
                rolls[type] = rolls.getValue(type) + 1
            }
            if (operation.annotation == "c") modified += type
        }
        return values.map { (type, value) ->
            E7GearStat(
                type = type,
                value = roundTenth(value),
                rolls = rolls[type],
                modified = type in modified,
            )
        }
    }

    private fun calculateEnhance(rank: String, operationCount: Int): Int {
        val maxCount = COUNT_BY_RANK[rank] ?: 0
        val offset = OFFSET_BY_RANK[rank] ?: 0
        return ((operationCount.coerceAtMost(maxCount) - offset) * 3).coerceAtLeast(0)
    }

    private fun normalizeValue(type: String, value: Double): Double =
        if (type in FLAT_STATS) value else roundTenth(value * 100.0)

    private fun roundTenth(value: Double): Double = round(value * 10.0) / 10.0

    private fun parseSlot(type: String?, code: String?): GearSlot = when (type) {
        "weapon" -> GearSlot.WEAPON
        "helm" -> GearSlot.HELMET
        "armor" -> GearSlot.ARMOR
        "neck" -> GearSlot.NECKLACE
        "ring" -> GearSlot.RING
        "boot" -> GearSlot.BOOTS
        else -> when (code?.substringBefore('_')?.lastOrNull()) {
            'w' -> GearSlot.WEAPON
            'h' -> GearSlot.HELMET
            'a' -> GearSlot.ARMOR
            'n' -> GearSlot.NECKLACE
            'r' -> GearSlot.RING
            'b' -> GearSlot.BOOTS
            else -> GearSlot.UNKNOWN
        }
    }

    private fun JsonElement?.operation(): Operation? {
        val array = this as? JsonArray ?: return null
        val type = array.getOrNull(0).primitive()?.contentOrNull ?: return null
        val value = array.getOrNull(1).primitive()?.doubleOrNull ?: return null
        val annotation = array.getOrNull(2).primitive()?.contentOrNull
        return Operation(type, value, annotation)
    }

    private fun JsonElement?.primitive(): JsonPrimitive? =
        (this as? JsonPrimitive)?.takeUnless { it is JsonNull }

    private fun JsonObject.string(key: String): String? = this[key].primitive()?.contentOrNull
    private fun JsonObject.stableId(key: String): Long? = string(key)
        ?.takeUnless { it == "undefined" || it == "null" }
        ?.let { it.toLongOrNull() ?: stableLongId(it) }
    private fun JsonObject.int(key: String): Int? = this[key].primitive()?.intOrNull
    private fun JsonObject.long(key: String): Long? = this[key].primitive()?.longOrNull
    private fun JsonObject.double(key: String): Double? = this[key].primitive()?.doubleOrNull
    private fun JsonObject.boolean(key: String): Boolean? = this[key].primitive()?.booleanOrNull

    private data class Operation(val type: String, val value: Double, val annotation: String?)

    private val FLAT_STATS = setOf("max_hp", "speed", "att", "def")
    private val RANKS = listOf("未知", "普通", "优秀", "稀有", "英雄", "传说")
    private val CONVERTED_RANKS = mapOf(
        "Normal" to "普通",
        "Good" to "优秀",
        "Rare" to "稀有",
        "Heroic" to "英雄",
        "Epic" to "传说",
    )
    private val CONVERTED_SLOTS = mapOf(
        "Weapon" to GearSlot.WEAPON,
        "Helmet" to GearSlot.HELMET,
        "Armor" to GearSlot.ARMOR,
        "Necklace" to GearSlot.NECKLACE,
        "Ring" to GearSlot.RING,
        "Boots" to GearSlot.BOOTS,
    )
    private val CONVERTED_SETS = mapOf(
        "HitSet" to "set_acc",
        "AttackSet" to "set_att",
        "UnitySet" to "set_coop",
        "CounterSet" to "set_counter",
        "DestructionSet" to "set_cri_dmg",
        "CriticalSet" to "set_cri",
        "DefenseSet" to "set_def",
        "ImmunitySet" to "set_immune",
        "HealthSet" to "set_max_hp",
        "PenetrationSet" to "set_penetrate",
        "RageSet" to "set_rage",
        "ResistSet" to "set_res",
        "RevengeSet" to "set_revenge",
        "InjurySet" to "set_scar",
        "SpeedSet" to "set_speed",
        "LifestealSet" to "set_vampire",
        "ProtectionSet" to "set_shield",
        "TorrentSet" to "set_torrent",
        "ReversalSet" to "set_revenant",
        "RiposteSet" to "set_riposte",
        "PursuitSet" to "set_chase",
        "WarfareSet" to "set_opener",
        "WeakeningSet" to "set_weak",
        "FervorSet" to "set_might",
    )
    private val COUNT_BY_RANK = mapOf("普通" to 5, "优秀" to 6, "稀有" to 7, "英雄" to 8, "传说" to 9)
    private val OFFSET_BY_RANK = mapOf("普通" to 0, "优秀" to 1, "稀有" to 2, "英雄" to 3, "传说" to 4)
    private val STAT_TYPES = mapOf(
        "att_rate" to "AttackPercent",
        "max_hp_rate" to "HealthPercent",
        "def_rate" to "DefensePercent",
        "att" to "Attack",
        "max_hp" to "Health",
        "def" to "Defense",
        "speed" to "Speed",
        "res" to "EffectResistancePercent",
        "cri" to "CriticalHitChancePercent",
        "cri_dmg" to "CriticalHitDamagePercent",
        "acc" to "EffectivenessPercent",
        "coop" to "DualAttackChancePercent",
    )
    private val SET_NAMES = mapOf(
        "set_acc" to "命中套装",
        "set_att" to "攻击套装",
        "set_coop" to "夹攻套装",
        "set_counter" to "反击套装",
        "set_cri_dmg" to "破灭套装",
        "set_cri" to "暴击套装",
        "set_def" to "防御套装",
        "set_immune" to "免疫套装",
        "set_max_hp" to "生命套装",
        "set_penetrate" to "穿透套装",
        "set_rage" to "愤怒套装",
        "set_res" to "抗性套装",
        "set_revenge" to "复仇套装",
        "set_scar" to "伤口套装",
        "set_speed" to "速度套装",
        "set_vampire" to "吸血套装",
        "set_shield" to "保护套装",
        "set_torrent" to "激流套装",
        "set_revenant" to "逆袭套装",
        "set_riposte" to "裂伤套装",
        "set_chase" to "追击套装",
        "set_opener" to "先制套装",
        "set_weak" to "弱化套装",
        "set_might" to "威势套装",
    )
}
