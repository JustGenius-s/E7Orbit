package com.e7orbit.capture

import com.e7orbit.data.E7Gear
import com.e7orbit.data.E7GearStat
import com.e7orbit.data.GearSlot
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
    private fun JsonObject.int(key: String): Int? = this[key].primitive()?.intOrNull
    private fun JsonObject.long(key: String): Long? = this[key].primitive()?.longOrNull
    private fun JsonObject.double(key: String): Double? = this[key].primitive()?.doubleOrNull
    private fun JsonObject.boolean(key: String): Boolean? = this[key].primitive()?.booleanOrNull

    private data class Operation(val type: String, val value: Double, val annotation: String?)

    private val FLAT_STATS = setOf("max_hp", "speed", "att", "def")
    private val RANKS = listOf("未知", "普通", "优秀", "稀有", "英雄", "传说")
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
