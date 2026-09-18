package com.e7orbit.data

import kotlinx.serialization.json.JsonElement

data class E7Hero(
    val code: String,
    val name: String,
    val rarity: Int?,
    val attribute: String,
    val role: String,
    val zodiac: String?,
    val stats: E7HeroStats?,
    val assets: E7HeroAssets = E7HeroAssets(),
    val description: String? = null,
    val awakenings: List<E7HeroAwakening> = emptyList(),
    val memoryImprint: E7MemoryImprint? = null,
    val skills: List<E7HeroSkill> = emptyList(),
    val exclusiveEquipment: E7HeroExclusiveEquipment? = null,
    val source: E7DataSource = E7DataSource.OFFICIAL_AND_FRIBBELS,
)

/** Maintained hero skill data, normally synced from the user's Supabase catalog. */
data class E7HeroSkill(
    val slot: Int,
    val name: String,
    val iconUrl: String? = null,
    val description: String? = null,
    val enhancedDescription: String? = null,
    val cooldown: Int? = null,
    val soulGain: Int? = null,
    val soulRequirement: Int? = null,
    val soulDescription: String? = null,
    val attackRate: Double? = null,
    val pow: Double? = null,
    val isPassive: Boolean = false,
    val canEnhance: Boolean = false,
    val values: List<JsonElement> = emptyList(),
    val enhancements: List<String> = emptyList(),
    val buffs: List<E7StatusEffect> = emptyList(),
    val debuffs: List<E7StatusEffect> = emptyList(),
)

data class E7HeroExclusiveEquipment(
    val code: String,
    val heroCode: String,
    val name: String,
    val description: String? = null,
    val iconUrl: String,
    val statType: String,
    val statMin: Double,
    val statMax: Double,
    val statPercent: Boolean = false,
    val enhancements: List<E7ExclusiveEquipmentEnhancement> = emptyList(),
)

data class E7ExclusiveEquipmentEnhancement(
    val option: Int,
    val skillSlot: Int? = null,
    val description: String,
)

data class E7HeroAwakening(
    val rank: Int,
    val stats: List<E7GrowthStat> = emptyList(),
    val resources: List<E7ResourceCost> = emptyList(),
    val skillBefore: String? = null,
    val skillAfter: String? = null,
)

data class E7GrowthStat(
    val label: String,
    val value: String,
)

data class E7ResourceCost(
    val code: String,
    val label: String,
    val quantity: Int,
)

data class E7MemoryImprint(
    val release: E7ImprintSection? = null,
    val concentration: E7ImprintSection? = null,
)

data class E7ImprintSection(
    val position: String? = null,
    val grades: List<E7ImprintGrade> = emptyList(),
)

data class E7ImprintGrade(
    val rank: String,
    val value: String,
    /** Structured stat code (e.g. "speed", "critical_chance"), when synced with it. */
    val stat: String? = null,
    /** Numeric bonus amount. */
    val amount: Double? = null,
    /** True when [amount] is a percentage of the base stat. */
    val percent: Boolean = false,
)

/** A buff/debuff applied by a skill. [iconUrl] points at the status effect icon. */
data class E7StatusEffect(
    val slug: String,
    val label: String,
    val description: String? = null,
    val iconUrl: String? = null,
)

data class E7HeroAssets(
    val iconUrl: String? = null,
    val thumbnailUrl: String? = null,
    val imageUrl: String? = null,
)

data class E7HeroStats(
    val attack: Int?,
    val health: Int?,
    val defense: Int?,
    val speed: Int?,
    val criticalChance: Int?,
    val criticalDamage: Int?,
    val effectiveness: Int?,
    val effectResistance: Int?,
    val combatPower: Int?,
)

data class E7Artifact(
    val code: String,
    val name: String,
    val rarity: Int?,
    val role: String?,
    val attack: Int?,
    val health: Int?,
    val defense: Int?,
    val description: String?,
    val maxDescription: String? = null,
    val lore: String? = null,
    val imageUrl: String? = null,
    val iconUrl: String? = null,
    val baseAttack: Int? = null,
    val baseHealth: Int? = null,
    val aliases: List<String> = emptyList(),
)

enum class E7DataSource {
    OFFICIAL_AND_FRIBBELS,
}

data class E7DataSnapshot(
    val heroes: List<E7Hero> = emptyList(),
    val artifacts: List<E7Artifact> = emptyList(),
    val buffStatusEffects: List<E7StatusEffect> = emptyList(),
    val debuffStatusEffects: List<E7StatusEffect> = emptyList(),
    val fetchedAtEpochMs: Long = 0L,
)

data class RtaSeason(
    val code: String,
    val name: String,
    val startDate: String,
    val endDate: String,
    val isCurrent: Boolean,
)

enum class RtaTier(
    val code: String,
    val label: String,
) {
    MASTER("master", "大师"),
    CHALLENGER("challenger", "挑战者"),
    CHAMPION("champion", "冠军"),
    EMPEROR("emperor", "皇帝"),
    LEGEND("legend", "传说"),
}

data class HeroRtaAnalysis(
    val heroCode: String,
    val seasonCode: String,
    val tierCode: String,
    val sampleSize: Int,
    val equipmentSets: List<RtaEquipmentSet>,
    val pickPositions: List<RtaPositionRate>,
    val banPositions: List<RtaPositionRate>,
    val winRate: Double?,
    val winRateRank: Int?,
)

data class RtaEquipmentSet(
    val rank: Int,
    val setCodes: List<String>,
    val usageRate: Double,
    val winRate: Double,
)

data class RtaPositionRate(
    val position: Int,
    val rate: Double,
)
