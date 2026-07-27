package com.e7orbit.data

data class E7Hero(
    val code: String,
    val name: String,
    val rarity: Int?,
    val attribute: String,
    val role: String,
    val zodiac: String?,
    val stats: E7HeroStats?,
    val assets: E7HeroAssets = E7HeroAssets(),
    val source: E7DataSource = E7DataSource.OFFICIAL_AND_FRIBBELS,
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
    val rarity: String?,
    val role: String?,
    val attack: Int?,
    val health: Int?,
    val defense: Int?,
    val description: String?,
)

enum class E7DataSource {
    OFFICIAL_AND_FRIBBELS,
}

data class E7DataSnapshot(
    val heroes: List<E7Hero> = emptyList(),
    val artifacts: List<E7Artifact> = emptyList(),
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
