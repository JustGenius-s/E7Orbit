package com.e7orbit.data

data class ArtifactWikiDraft(
    val name: String,
    val rarity: String,
    val role: String,
    val baseAttack: String,
    val baseHealth: String,
    val attack: String,
    val health: String,
    val defense: String,
    val description: String,
    val maxDescription: String,
    val lore: String,
    val imageUrl: String,
    val iconUrl: String,
)

fun E7Artifact.toWikiDraft(): ArtifactWikiDraft = ArtifactWikiDraft(
    name = name,
    rarity = rarity?.toString().orEmpty(),
    role = role.toEditableArtifactRole(),
    baseAttack = baseAttack?.toString().orEmpty(),
    baseHealth = baseHealth?.toString().orEmpty(),
    attack = attack?.toString().orEmpty(),
    health = health?.toString().orEmpty(),
    defense = defense?.toString().orEmpty(),
    description = description.orEmpty(),
    maxDescription = maxDescription.orEmpty(),
    lore = lore.orEmpty(),
    imageUrl = imageUrl.orEmpty(),
    iconUrl = iconUrl.orEmpty(),
)

fun ArtifactWikiDraft.toArtifact(original: E7Artifact): E7Artifact {
    val normalizedName = name.trim().requireArtifactValue("神器名称不能为空")
    val normalizedRarity = rarity.optionalArtifactInt("稀有度")?.also {
        require(it in 1..6) { "稀有度必须在 1 到 6 之间" }
    }
    return original.copy(
        name = normalizedName,
        rarity = normalizedRarity,
        role = role.trim().ifEmpty { null },
        baseAttack = baseAttack.optionalArtifactStat("初始攻击力"),
        baseHealth = baseHealth.optionalArtifactStat("初始生命值"),
        attack = attack.optionalArtifactStat("满级攻击力"),
        health = health.optionalArtifactStat("满级生命值"),
        defense = defense.optionalArtifactStat("防御力"),
        description = description.trim().ifEmpty { null },
        maxDescription = maxDescription.trim().ifEmpty { null },
        lore = lore.trim().ifEmpty { null },
        imageUrl = imageUrl.trim().ifEmpty { null },
        iconUrl = iconUrl.trim().ifEmpty { null },
    )
}

private fun String.optionalArtifactInt(label: String): Int? =
    trim().takeIf(String::isNotEmpty)?.let {
        it.toIntOrNull() ?: throw IllegalArgumentException("$label 必须是整数")
    }

private fun String.optionalArtifactStat(label: String): Int? = optionalArtifactInt(label)?.also {
    require(it >= 0) { "$label 不能小于 0" }
}

private fun String.requireArtifactValue(message: String): String = also {
    require(it.isNotEmpty()) { message }
}

private fun String?.toEditableArtifactRole(): String = when (this?.lowercase()) {
    "soul_weaver", "soulweaver" -> "manauser"
    else -> this?.lowercase().orEmpty()
}
