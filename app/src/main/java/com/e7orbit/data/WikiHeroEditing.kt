package com.e7orbit.data

import kotlinx.serialization.json.JsonElement

data class HeroWikiDraft(
    val name: String,
    val rarity: String,
    val attribute: String,
    val role: String,
    val zodiac: String,
    val description: String,
    val iconUrl: String,
    val thumbnailUrl: String,
    val imageUrl: String,
    val attack: String,
    val health: String,
    val defense: String,
    val speed: String,
    val criticalChance: String,
    val criticalDamage: String,
    val effectiveness: String,
    val effectResistance: String,
    val combatPower: String,
    val exclusiveEquipment: ExclusiveEquipmentWikiDraft?,
    val skills: List<HeroSkillWikiDraft>,
)

data class ExclusiveEquipmentWikiDraft(
    val name: String,
    val description: String,
    val iconUrl: String,
    val statType: String,
    val statMin: String,
    val statMax: String,
    val statPercent: Boolean,
    val enhancements: List<ExclusiveEnhancementWikiDraft>,
)

data class ExclusiveEnhancementWikiDraft(
    val option: Int,
    val skillSlot: String,
    val description: String,
)

data class HeroSkillWikiDraft(
    val slot: String,
    val name: String,
    val iconUrl: String,
    val description: String,
    val enhancedDescription: String,
    val cooldown: String,
    val soulGain: String,
    val soulRequirement: String,
    val soulDescription: String,
    val attackRate: String,
    val pow: String,
    val isPassive: Boolean,
    val canEnhance: Boolean,
    val values: List<JsonElement>,
    val enhancements: String,
    val buffSlugs: List<String>,
    val debuffSlugs: List<String>,
)

fun E7Hero.toWikiDraft(): HeroWikiDraft = HeroWikiDraft(
    name = name,
    rarity = rarity?.toString().orEmpty(),
    attribute = attribute.toEditableAttribute(),
    role = role.toEditableRole(),
    zodiac = zodiac.orEmpty(),
    description = description.orEmpty(),
    iconUrl = assets.iconUrl.orEmpty(),
    thumbnailUrl = assets.thumbnailUrl.orEmpty(),
    imageUrl = assets.imageUrl.orEmpty(),
    attack = stats?.attack?.toString().orEmpty(),
    health = stats?.health?.toString().orEmpty(),
    defense = stats?.defense?.toString().orEmpty(),
    speed = stats?.speed?.toString().orEmpty(),
    criticalChance = stats?.criticalChance?.toString().orEmpty(),
    criticalDamage = stats?.criticalDamage?.toString().orEmpty(),
    effectiveness = stats?.effectiveness?.toString().orEmpty(),
    effectResistance = stats?.effectResistance?.toString().orEmpty(),
    combatPower = stats?.combatPower?.toString().orEmpty(),
    exclusiveEquipment = exclusiveEquipment?.toWikiDraft(),
    skills = skills.sortedBy(E7HeroSkill::slot).map(E7HeroSkill::toWikiDraft),
)

fun HeroWikiDraft.toHero(original: E7Hero): E7Hero {
    val normalizedName = name.trim().requireNotEmpty("英雄名称不能为空")
    val normalizedRarity = rarity.optionalInt("稀有度")?.also {
        require(it in 1..6) { "稀有度必须在 1 到 6 之间" }
    }
    require(attribute.isNotBlank()) { "请选择英雄属性" }
    require(role.isNotBlank()) { "请选择英雄职业" }

    val nextStats = listOf(
        attack,
        health,
        defense,
        speed,
        criticalChance,
        criticalDamage,
        effectiveness,
        effectResistance,
        combatPower,
    ).takeIf { values -> values.any(String::isNotBlank) }?.let {
        E7HeroStats(
            attack = attack.optionalNonNegativeInt("攻击力"),
            health = health.optionalNonNegativeInt("生命值"),
            defense = defense.optionalNonNegativeInt("防御力"),
            speed = speed.optionalNonNegativeInt("速度"),
            criticalChance = criticalChance.optionalNonNegativeInt("暴击率"),
            criticalDamage = criticalDamage.optionalNonNegativeInt("暴击伤害"),
            effectiveness = effectiveness.optionalNonNegativeInt("效果命中"),
            effectResistance = effectResistance.optionalNonNegativeInt("效果抗性"),
            combatPower = combatPower.optionalNonNegativeInt("战斗力"),
        )
    }

    val nextSkills = skills.map { it.toSkill(original.skills) }
    require(nextSkills.map(E7HeroSkill::slot).distinct().size == nextSkills.size) {
        "技能栏位不能重复"
    }

    return original.copy(
        name = normalizedName,
        rarity = normalizedRarity,
        attribute = attribute.trim(),
        role = role.trim(),
        zodiac = zodiac.trim().ifEmpty { null },
        description = description.trim().ifEmpty { null },
        assets = E7HeroAssets(
            iconUrl = iconUrl.trim().ifEmpty { null },
            thumbnailUrl = thumbnailUrl.trim().ifEmpty { null },
            imageUrl = imageUrl.trim().ifEmpty { null },
        ),
        stats = nextStats,
        exclusiveEquipment = exclusiveEquipment?.toEquipment(original),
        skills = nextSkills.sortedBy(E7HeroSkill::slot),
    )
}

fun emptyExclusiveEquipmentWikiDraft(): ExclusiveEquipmentWikiDraft =
    ExclusiveEquipmentWikiDraft(
        name = "",
        description = "",
        iconUrl = "",
        statType = "attack",
        statMin = "",
        statMax = "",
        statPercent = false,
        enhancements = (1..3).map { option ->
            ExclusiveEnhancementWikiDraft(
                option = option,
                skillSlot = "",
                description = "",
            )
        },
    )

fun emptyHeroSkillWikiDraft(slot: Int): HeroSkillWikiDraft = HeroSkillWikiDraft(
    slot = slot.toString(),
    name = "",
    iconUrl = "",
    description = "",
    enhancedDescription = "",
    cooldown = "",
    soulGain = "",
    soulRequirement = "",
    soulDescription = "",
    attackRate = "",
    pow = "",
    isPassive = false,
    canEnhance = false,
    values = emptyList(),
    enhancements = "",
    buffSlugs = emptyList(),
    debuffSlugs = emptyList(),
)

private fun E7HeroExclusiveEquipment.toWikiDraft(): ExclusiveEquipmentWikiDraft {
    val enhancementsByOption = enhancements.associateBy(E7ExclusiveEquipmentEnhancement::option)
    return ExclusiveEquipmentWikiDraft(
        name = name,
        description = description.orEmpty(),
        iconUrl = iconUrl,
        statType = statType,
        statMin = statMin.toEditableNumber(),
        statMax = statMax.toEditableNumber(),
        statPercent = statPercent,
        enhancements = (1..3).map { option ->
            val enhancement = enhancementsByOption[option]
            ExclusiveEnhancementWikiDraft(
                option = option,
                skillSlot = enhancement?.skillSlot?.toString().orEmpty(),
                description = enhancement?.description.orEmpty(),
            )
        },
    )
}

private fun E7HeroSkill.toWikiDraft(): HeroSkillWikiDraft = HeroSkillWikiDraft(
    slot = slot.toString(),
    name = name,
    iconUrl = iconUrl.orEmpty(),
    description = description.orEmpty(),
    enhancedDescription = enhancedDescription.orEmpty(),
    cooldown = cooldown?.toString().orEmpty(),
    soulGain = soulGain?.toString().orEmpty(),
    soulRequirement = soulRequirement?.toString().orEmpty(),
    soulDescription = soulDescription.orEmpty(),
    attackRate = attackRate?.toEditableNumber().orEmpty(),
    pow = pow?.toEditableNumber().orEmpty(),
    isPassive = isPassive,
    canEnhance = canEnhance,
    values = values,
    enhancements = enhancements.joinToString("\n"),
    buffSlugs = buffs.map(E7StatusEffect::slug).distinct(),
    debuffSlugs = debuffs.map(E7StatusEffect::slug).distinct(),
)

private fun ExclusiveEquipmentWikiDraft.toEquipment(original: E7Hero): E7HeroExclusiveEquipment {
    val normalizedName = name.trim().requireNotEmpty("专属装备名称不能为空")
    val normalizedIconUrl = iconUrl.trim().requireNotEmpty("专属装备图标地址不能为空")
    require(statType.isNotBlank()) { "请选择专属装备属性" }
    val minimum = statMin.requiredDouble("专属装备属性下限")
    val maximum = statMax.requiredDouble("专属装备属性上限")
    require(maximum >= minimum) { "专属装备属性上限不能小于下限" }
    require(enhancements.size == 3) { "专属装备必须包含 3 个强化选项" }
    val normalizedEnhancements = enhancements.sortedBy(ExclusiveEnhancementWikiDraft::option).map {
        require(it.option in 1..3) { "专属装备强化选项无效" }
        E7ExclusiveEquipmentEnhancement(
            option = it.option,
            skillSlot = it.skillSlot.optionalInt("强化 ${it.option} 的技能栏位")?.also { slot ->
                require(slot in 1..5) { "强化 ${it.option} 的技能栏位必须在 1 到 5 之间" }
            },
            description = it.description.trim().requireNotEmpty("强化 ${it.option} 的说明不能为空"),
        )
    }
    require(normalizedEnhancements.map(E7ExclusiveEquipmentEnhancement::option).distinct().size == 3) {
        "专属装备强化选项不能重复"
    }
    return E7HeroExclusiveEquipment(
        code = original.exclusiveEquipment?.code ?: "ee-${original.code}",
        heroCode = original.code,
        name = normalizedName,
        description = description.trim().ifEmpty { null },
        iconUrl = normalizedIconUrl,
        statType = statType.trim(),
        statMin = minimum,
        statMax = maximum,
        statPercent = statPercent,
        enhancements = normalizedEnhancements,
    )
}

private fun HeroSkillWikiDraft.toSkill(originalSkills: List<E7HeroSkill>): E7HeroSkill {
    val normalizedSlot = slot.optionalInt("技能栏位")
        ?: throw IllegalArgumentException("技能栏位不能为空")
    require(normalizedSlot in 1..5) { "技能栏位必须在 1 到 5 之间" }
    val normalizedName = name.trim().requireNotEmpty("技能 $normalizedSlot 的名称不能为空")
    val original = originalSkills.firstOrNull { it.slot == normalizedSlot }
    return E7HeroSkill(
        slot = normalizedSlot,
        name = normalizedName,
        iconUrl = iconUrl.trim().ifEmpty { null },
        description = description.trim().ifEmpty { null },
        enhancedDescription = enhancedDescription.trim().ifEmpty { null },
        cooldown = cooldown.optionalNonNegativeInt("技能 $normalizedSlot 的冷却"),
        soulGain = soulGain.optionalNonNegativeInt("技能 $normalizedSlot 的灵魂获取"),
        soulRequirement = soulRequirement.optionalNonNegativeInt("技能 $normalizedSlot 的灵魂消耗"),
        soulDescription = soulDescription.trim().ifEmpty { null },
        attackRate = attackRate.optionalNonNegativeDouble("技能 $normalizedSlot 的攻击倍率"),
        pow = pow.optionalNonNegativeDouble("技能 $normalizedSlot 的 POW"),
        isPassive = isPassive,
        canEnhance = canEnhance,
        values = values,
        enhancements = enhancements.toListValues(),
        buffs = buffSlugs.distinct().map { slug ->
            original?.buffs?.firstOrNull { it.slug == slug } ?: E7StatusEffect(slug, slug)
        },
        debuffs = debuffSlugs.distinct().map { slug ->
            original?.debuffs?.firstOrNull { it.slug == slug } ?: E7StatusEffect(slug, slug)
        },
    )
}

private fun String.optionalInt(label: String): Int? = trim().takeIf(String::isNotEmpty)?.let {
    it.toIntOrNull() ?: throw IllegalArgumentException("$label 必须是整数")
}

private fun String.optionalNonNegativeInt(label: String): Int? = optionalInt(label)?.also {
    require(it >= 0) { "$label 不能小于 0" }
}

private fun String.requiredDouble(label: String): Double = trim().toDoubleOrNull()
    ?: throw IllegalArgumentException("$label 必须是数字")

private fun String.optionalNonNegativeDouble(label: String): Double? =
    trim().takeIf(String::isNotEmpty)?.let {
        val value = it.toDoubleOrNull() ?: throw IllegalArgumentException("$label 必须是数字")
        require(value >= 0.0) { "$label 不能小于 0" }
        value
    }

private fun String.toListValues(): List<String> = lineSequence()
    .map(String::trim)
    .filter(String::isNotEmpty)
    .toList()

private fun String.requireNotEmpty(message: String): String = also {
    require(it.isNotEmpty()) { message }
}

private fun Double.toEditableNumber(): String =
    if (this % 1.0 == 0.0) toInt().toString() else toString()

private fun String.toEditableAttribute(): String = when (lowercase()) {
    "water" -> "ice"
    "wind" -> "earth"
    else -> lowercase()
}

private fun String.toEditableRole(): String = when (lowercase()) {
    "soul_weaver", "soulweaver" -> "manauser"
    else -> lowercase()
}
