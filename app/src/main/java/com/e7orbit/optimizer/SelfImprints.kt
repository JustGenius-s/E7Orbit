package com.e7orbit.optimizer

import com.e7orbit.data.E7Hero
import com.e7orbit.data.E7HeroStats
import com.e7orbit.data.E7ImprintGrade

/** Self-imprint (自身刻印) ranks, weakest to strongest. */
enum class ImprintRank(val label: String) {
    B("B"),
    A("A"),
    S("S"),
    SS("SS"),
    SSS("SSS"),
    ;

    companion object {
        val DEFAULT = SSS

        fun of(raw: String?): ImprintRank? = when (raw?.trim()?.uppercase()) {
            "B" -> B
            "A" -> A
            "S" -> S
            "SS" -> SS
            "SSS" -> SSS
            else -> null
        }
    }
}

private enum class ImprintStat {
    ATTACK, HEALTH, DEFENSE, SPEED,
    CRIT_CHANCE, CRIT_DAMAGE, EFFECTIVENESS, RESISTANCE,
}

/**
 * Applies a hero's Imprint Concentration (自身刻印) bonus at [rank] to their
 * base stats. Percent bonuses scale off the base stat, matching in-game
 * behavior. Heroes without usable concentration data at that rank are
 * returned unchanged.
 *
 * Structured grade fields (stat/amount/percent, synced into the catalog) are
 * preferred; the legacy English text form is parsed as a fallback.
 */
fun E7Hero.withSelfImprint(rank: ImprintRank = ImprintRank.DEFAULT): E7Hero {
    val base = stats ?: return this
    val grades = memoryImprint?.concentration?.grades.orEmpty()
    if (grades.isEmpty()) return this
    val grade = grades.firstOrNull { ImprintRank.of(it.rank) == rank } ?: return this
    val bonus = parseImprintBonus(grade) ?: return this
    return copy(stats = base.applyImprintBonus(bonus))
}

private data class ImprintBonus(
    val stat: ImprintStat,
    val value: Double,
    val isPercent: Boolean,
)

private fun parseImprintBonus(grade: E7ImprintGrade): ImprintBonus? {
    // Prefer the structured fields synced alongside the display text.
    imprintStatCodeOf(grade.stat)?.let { stat ->
        val amount = grade.amount
        if (amount != null) return ImprintBonus(stat, amount, grade.percent)
    }
    val match = IMPRINT_VALUE_REGEX.matchEntire(grade.value.trim()) ?: return null
    val stat = imprintStatLabelOf(match.groupValues[1]) ?: return null
    val value = match.groupValues[2].toDoubleOrNull() ?: return null
    return ImprintBonus(stat, value, match.groupValues[3].isNotEmpty())
}

private val IMPRINT_VALUE_REGEX = Regex("""^([A-Za-z ]+?)\s*\+?\s*([0-9]+(?:\.[0-9]+)?)\s*(%?)$""")

private fun imprintStatCodeOf(code: String?): ImprintStat? = when (code?.trim()?.lowercase()) {
    "attack" -> ImprintStat.ATTACK
    "health" -> ImprintStat.HEALTH
    "defense" -> ImprintStat.DEFENSE
    "speed" -> ImprintStat.SPEED
    "critical_chance" -> ImprintStat.CRIT_CHANCE
    "critical_damage" -> ImprintStat.CRIT_DAMAGE
    "effectiveness" -> ImprintStat.EFFECTIVENESS
    "effect_resistance" -> ImprintStat.RESISTANCE
    else -> null
}

private fun imprintStatLabelOf(label: String): ImprintStat? = when (label.trim().lowercase()) {
    "attack" -> ImprintStat.ATTACK
    "health" -> ImprintStat.HEALTH
    "defense" -> ImprintStat.DEFENSE
    "speed" -> ImprintStat.SPEED
    "critical hit chance" -> ImprintStat.CRIT_CHANCE
    "critical hit damage" -> ImprintStat.CRIT_DAMAGE
    "effectiveness" -> ImprintStat.EFFECTIVENESS
    "effect resistance" -> ImprintStat.RESISTANCE
    else -> null
}

private fun E7HeroStats.applyImprintBonus(bonus: ImprintBonus): E7HeroStats {
    fun scaled(base: Int?): Int {
        val baseValue = base ?: 0
        return if (bonus.isPercent) {
            (baseValue * bonus.value / 100.0).toInt()
        } else {
            bonus.value.toInt()
        }
    }
    return when (bonus.stat) {
        ImprintStat.ATTACK -> copy(attack = (attack ?: 0) + scaled(attack))
        ImprintStat.HEALTH -> copy(health = (health ?: 0) + scaled(health))
        ImprintStat.DEFENSE -> copy(defense = (defense ?: 0) + scaled(defense))
        ImprintStat.SPEED -> copy(speed = (speed ?: 0) + scaled(speed))
        // Crit/effect rolls only come in percent form; the value is a flat
        // addition to the percentage-based stat, not scaled off the base.
        ImprintStat.CRIT_CHANCE -> copy(criticalChance = (criticalChance ?: 15) + bonus.value.toInt())
        ImprintStat.CRIT_DAMAGE -> copy(criticalDamage = (criticalDamage ?: 150) + bonus.value.toInt())
        ImprintStat.EFFECTIVENESS -> copy(effectiveness = (effectiveness ?: 0) + bonus.value.toInt())
        ImprintStat.RESISTANCE -> copy(effectResistance = (effectResistance ?: 0) + bonus.value.toInt())
    }
}
