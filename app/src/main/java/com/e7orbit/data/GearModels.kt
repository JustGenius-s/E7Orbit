package com.e7orbit.data

import kotlinx.serialization.Serializable

@Serializable
data class E7ScannedHero(
    val id: Long,
    val name: String,
    val code: String? = null,
    val stars: Int? = null,
    val awaken: Int? = null,
)

@Serializable
data class E7Gear(
    val id: Long,
    val code: String,
    val slot: GearSlot,
    val setCode: String,
    val setName: String,
    val rank: String,
    val level: Int,
    val enhance: Int,
    val mainStat: E7GearStat,
    val substats: List<E7GearStat>,
    val locked: Boolean,
    val equippedHeroId: Long? = null,
)

@Serializable
data class E7GearStat(
    val type: String,
    val value: Double,
    val rolls: Int? = null,
    val modified: Boolean = false,
) {
    val label: String
        get() = STAT_LABELS[type] ?: type

    val isPercent: Boolean
        get() = type.endsWith("Percent")

    fun displayValue(): String {
        val number = if (value % 1.0 == 0.0) {
            value.toLong().toString()
        } else {
            "%.1f".format(value)
        }
        return if (isPercent) "$number%" else number
    }

    companion object {
        private val STAT_LABELS = mapOf(
            "Attack" to "攻击",
            "AttackPercent" to "攻击",
            "Health" to "生命",
            "HealthPercent" to "生命",
            "Defense" to "防御",
            "DefensePercent" to "防御",
            "Speed" to "速度",
            "EffectResistancePercent" to "效果抗性",
            "CriticalHitChancePercent" to "暴击率",
            "CriticalHitDamagePercent" to "暴击伤害",
            "EffectivenessPercent" to "效果命中",
            "DualAttackChancePercent" to "夹攻率",
        )
    }
}

@Serializable
enum class GearSlot(val label: String) {
    WEAPON("武器"),
    HELMET("头盔"),
    ARMOR("铠甲"),
    NECKLACE("项链"),
    RING("戒指"),
    BOOTS("鞋子"),
    UNKNOWN("未知部位"),
}

enum class GearImportPhase {
    IDLE,
    PARSING,
    READY,
    ERROR,
}

data class GearImportState(
    val phase: GearImportPhase = GearImportPhase.IDLE,
    val gears: List<E7Gear> = emptyList(),
    val heroes: List<E7ScannedHero> = emptyList(),
    val heroCount: Int = heroes.size,
    val importedAtEpochMs: Long = 0L,
    val errorMessage: String? = null,
)
