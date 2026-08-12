package com.e7orbit.optimizer

import com.e7orbit.data.E7Gear
import com.e7orbit.data.E7GearStat
import com.e7orbit.data.E7Hero
import com.e7orbit.data.E7HeroStats
import com.e7orbit.data.GearSlot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException

enum class OptimizerMetric(val label: String) {
    COMBAT_POWER("战斗力"),
    DAMAGE_PER_SPEED("伤害效率"),
    EFFECTIVE_HEALTH("有效生命"),
    SPEED("速度"),
    GEAR_SCORE("装备分"),
}

enum class OptimizerStat(val label: String) {
    ATTACK("攻击"),
    HEALTH("生命"),
    DEFENSE("防御"),
    SPEED("速度"),
    CRIT_CHANCE("暴击率"),
    CRIT_DAMAGE("暴击伤害"),
    EFFECTIVENESS("效果命中"),
    RESISTANCE("效果抗性"),
}

data class OptimizerConstraints(
    val attack: Int = 0,
    val health: Int = 0,
    val defense: Int = 0,
    val speed: Int = 0,
    val critChance: Int = 0,
    val critDamage: Int = 0,
    val effectiveness: Int = 0,
    val resistance: Int = 0,
)

data class GearOptimizationConfig(
    val metric: OptimizerMetric = OptimizerMetric.COMBAT_POWER,
    val constraints: OptimizerConstraints = OptimizerConstraints(),
    val requiredSets: Set<String> = emptySet(),
    val allowLocked: Boolean = true,
    val allowEquipped: Boolean = true,
    val onlyMaxed: Boolean = true,
    val maxResults: Int = 20,
)

data class OptimizedHeroStats(
    val attack: Int,
    val health: Int,
    val defense: Int,
    val speed: Int,
    val critChance: Int,
    val critDamage: Int,
    val effectiveness: Int,
    val resistance: Int,
    val dualAttackChance: Int,
    val combatPower: Int,
    val effectiveHealth: Int,
    val damage: Int,
    val damagePerSpeed: Int,
    val gearScore: Int,
    val breakdowns: Map<OptimizerStat, StatBreakdown> = emptyMap(),
)

/**
 * 单条属性的面板构成，用于概览页括号展开。
 * [gearPercent] 装备百分比加成合计（%），[gearFlat] 装备固定加成合计，
 * [setBonus] 套装提供的加成，[exclusiveEquipmentBonus] 为专属装备满值。
 * 百分比字段保存目录中的显示百分比，固定属性保存面板点数。
 */
data class StatBreakdown(
    val gearPercent: Double = 0.0,
    val gearFlat: Double = 0.0,
    val setBonus: Double = 0.0,
    val setIsPercent: Boolean = false,
    val exclusiveEquipmentBonus: Double = 0.0,
    val exclusiveEquipmentIsPercent: Boolean = false,
)

private data class ExclusiveEquipmentBonus(
    val stat: OptimizerStat? = null,
    val panelValue: Double = 0.0,
    val displayValue: Double = 0.0,
    val isPercent: Boolean = false,
) {
    fun panelValueFor(target: OptimizerStat): Double =
        if (stat == target) panelValue else 0.0

    fun displayValueFor(target: OptimizerStat): Double =
        if (stat == target) displayValue else 0.0
}

data class OptimizedBuild(
    val items: List<E7Gear>,
    val stats: OptimizedHeroStats,
    val completedSets: List<String>,
    val rankingValue: Long,
)

data class GearOptimizationOutcome(
    val builds: List<OptimizedBuild>,
    val combinationsEvaluated: Long,
    val candidatesBySlot: Map<GearSlot, Int>,
)

/**
 * Bounded on-device optimizer based on Fribbels' StatCalculator formulas.
 * Fribbels uses native/GPU exhaustive search; Android uses per-slot candidate
 * ranking plus beam search to keep execution interactive without changing the
 * final stat, set, constraint, or ranking calculations.
 */
class GearOptimizer(
    private val candidatesPerSlot: Int = DEFAULT_CANDIDATES_PER_SLOT,
    private val beamWidth: Int = DEFAULT_BEAM_WIDTH,
) {
    fun optimize(
        hero: E7Hero,
        inventory: List<E7Gear>,
        config: GearOptimizationConfig,
        percentageBaseStats: E7HeroStats? = hero.stats,
        isCancelled: () -> Boolean = { false },
    ): GearOptimizationOutcome {
        hero.stats ?: throw IllegalArgumentException("该英雄缺少六星满觉基础属性")
        val percentageBase = percentageBaseStats
            ?: throw IllegalArgumentException("该英雄缺少六星满觉基础属性")
        require(config.requiredSets.sumOf(::setPieces) <= SLOT_ORDER.size) {
            "必选套装需要超过 6 件装备"
        }

        val eligible = inventory.asSequence()
            .filter { it.slot in SLOT_ORDER }
            .filter { setPieces(it.setCode) > 0 }
            .filter { config.allowLocked || !it.locked }
            .filter { config.allowEquipped || it.equippedHeroId == null }
            .filter { !config.onlyMaxed || it.enhance == 15 }
            .toList()

        val candidates = SLOT_ORDER.associateWith { slot ->
            selectCandidates(
                items = eligible.filter { it.slot == slot },
                hero = hero,
                percentageBase = percentageBase,
                config = config,
            )
        }
        val missing = candidates.filterValues(List<E7Gear>::isEmpty).keys
        require(missing.isEmpty()) {
            "这些部位没有可用装备：${missing.joinToString { it.label }}"
        }

        var beam = listOf(PartialBuild())
        var combinations = 0L
        SLOT_ORDER.forEachIndexed { index, slot ->
            if (isCancelled()) throw CancellationException("配装计算已停止")
            val remainingSlots = SLOT_ORDER.size - index - 1
            val expanded = ArrayList<PartialBuild>(beam.size * candidates.getValue(slot).size)
            for ((partialIndex, partial) in beam.withIndex()) {
                if (partialIndex % CANCELLATION_CHECK_INTERVAL == 0 && isCancelled()) {
                    throw CancellationException("配装计算已停止")
                }
                for (item in candidates.getValue(slot)) {
                    combinations++
                    val next = partial.add(item, percentageBase)
                    if (
                        canStillFormCompleteSets(next.setCounts, remainingSlots) &&
                        canStillMeetRequiredSets(next.setCounts, config.requiredSets, remainingSlots)
                    ) {
                        expanded += next
                    }
                }
            }
            beam = expanded
                .asSequence()
                .map { it to heuristicScore(it, hero, percentageBase, config) }
                .sortedByDescending(Pair<PartialBuild, Double>::second)
                .take(beamWidth)
                .map(Pair<PartialBuild, Double>::first)
                .toList()
            if (beam.isEmpty()) return GearOptimizationOutcome(emptyList(), combinations, candidates.mapValues { it.value.size })
        }

        val builds = beam.asSequence()
            .filter { hasOnlyCompleteSets(it.setCounts) }
            .filter { counts -> config.requiredSets.all { set -> counts.setCounts.getOrDefault(set, 0) >= setPieces(set) } }
            .map { partial ->
                val stats = calculateStats(hero, partial.items, percentageBase)
                OptimizedBuild(
                    items = partial.items,
                    stats = stats,
                    completedSets = completedSets(partial.setCounts),
                    rankingValue = rankingValue(stats, config.metric),
                )
            }
            .filter { passesConstraints(it.stats, config.constraints) }
            .sortedWith(
                compareByDescending<OptimizedBuild> { it.rankingValue }
                    .thenByDescending { it.stats.gearScore },
            )
            .take(config.maxResults.coerceIn(1, MAX_RESULTS))
            .toList()

        return GearOptimizationOutcome(
            builds = builds,
            combinationsEvaluated = combinations,
            candidatesBySlot = candidates.mapValues { it.value.size },
        )
    }

    fun calculateStats(
        hero: E7Hero,
        items: List<E7Gear>,
        percentageBaseStats: E7HeroStats? = hero.stats,
    ): OptimizedHeroStats {
        require(items.size <= SLOT_ORDER.size) { "配装不能超过六个部位" }
        val base = hero.stats ?: throw IllegalArgumentException("该英雄缺少基础属性")
        val percentageBase = percentageBaseStats
            ?: throw IllegalArgumentException("该英雄缺少基础属性")
        val percentageBaseAttack = percentageBase.attack ?: 0
        val percentageBaseHealth = percentageBase.health ?: 0
        val percentageBaseDefense = percentageBase.defense ?: 0
        val percentageBaseSpeed = percentageBase.speed ?: 0
        val accumulated = StatAccumulator()
        items.forEach {
            accumulated.add(
                it,
                percentageBaseAttack,
                percentageBaseHealth,
                percentageBaseDefense,
            )
        }
        val setCounts = items.groupingBy(E7Gear::setCode).eachCount()

        val baseAttack = base.attack ?: 0
        val baseHealth = base.health ?: 0
        val baseDefense = base.defense ?: 0
        val baseSpeed = base.speed ?: 0
        val exclusiveBonus = exclusiveEquipmentBonus(
            hero = hero,
            baseAttack = percentageBaseAttack,
            baseHealth = percentageBaseHealth,
            baseDefense = percentageBaseDefense,
        )
        // Fribbels keeps the accumulator as float and truncates only the final stat.
        val rawAttack = baseAttack + accumulated.attack +
            setCounts.completeCount("set_att") * percentageBaseAttack * 0.45 +
            exclusiveBonus.panelValueFor(OptimizerStat.ATTACK)
        val rawHealth = baseHealth + accumulated.health +
            setCounts.completeCount("set_max_hp") * percentageBaseHealth * 0.20 +
            setCounts.completeCount("set_opener") * percentageBaseHealth * 0.20 -
            setCounts.completeCount("set_torrent") * percentageBaseHealth * 0.10 +
            exclusiveBonus.panelValueFor(OptimizerStat.HEALTH)
        val rawDefense = baseDefense + accumulated.defense +
            setCounts.completeCount("set_def") * percentageBaseDefense * 0.20 +
            exclusiveBonus.panelValueFor(OptimizerStat.DEFENSE)
        val attack = rawAttack.toInt()
        val health = rawHealth.toInt()
        val defense = rawDefense.toInt()
        val speed = (
            baseSpeed + accumulated.speed +
                setCounts.completeCount("set_speed") * percentageBaseSpeed * 0.25 +
                setCounts.completeCount("set_revenge") * percentageBaseSpeed * 0.12 +
                (setCounts.completeCount("set_revenant") + setCounts.completeCount("set_weak")) *
                percentageBaseSpeed * 0.15 + exclusiveBonus.panelValueFor(OptimizerStat.SPEED)
            ).toInt()
        val critChance = (
            (base.criticalChance ?: 15) + accumulated.critChance +
                setCounts.completeCount("set_cri") * 12 +
                exclusiveBonus.panelValueFor(OptimizerStat.CRIT_CHANCE)
            ).toInt()
        val critDamage = (
            (base.criticalDamage ?: 150) + accumulated.critDamage +
                setCounts.completeCount("set_cri_dmg") * 60 +
                exclusiveBonus.panelValueFor(OptimizerStat.CRIT_DAMAGE)
            ).toInt()
        val effectiveness = (
            (base.effectiveness ?: 0) + accumulated.effectiveness +
                setCounts.completeCount("set_acc") * 20 +
                exclusiveBonus.panelValueFor(OptimizerStat.EFFECTIVENESS)
            ).toInt()
        val resistance = (
            (base.effectResistance ?: 0) + accumulated.resistance +
                setCounts.completeCount("set_res") * 20 +
                exclusiveBonus.panelValueFor(OptimizerStat.RESISTANCE)
            ).toInt()
        val dualAttackChance = min(
            DEFAULT_DUAL_ATTACK_CHANCE + accumulated.dualAttackChance +
                setCounts.completeCount("set_coop") * UNITY_SET_DUAL_ATTACK_CHANCE,
            MAX_DUAL_ATTACK_CHANCE,
        ).toInt()

        val cappedCrit = min(critChance, 100) / 100.0
        val cappedCritDamage = min(critDamage, 350) / 100.0
        val combatPower = (
            (
                rawAttack * 1.6 + rawAttack * 1.6 * cappedCrit * cappedCritDamage
                ) * (1.0 + (speed - 45) * 0.02) + rawHealth + rawDefense * 9.3
            ) * (1.0 + (resistance / 100.0 + effectiveness / 100.0) / 4.0)
        val effectiveHealth = rawHealth * (rawDefense / 300.0 + 1.0)
        val penetrationMultiplier = if (setCounts.completeCount("set_penetrate") > 0) PEN_MULTIPLIER else 1.0
        val damageSetMultiplier = 1.0 +
            setCounts.completeCount("set_rage") * 0.30 +
            setCounts.completeCount("set_torrent") * 0.10
        val damage = (
            cappedCrit * rawAttack * cappedCritDamage + (1.0 - cappedCrit) * rawAttack
            ) * penetrationMultiplier * damageSetMultiplier

        return OptimizedHeroStats(
            attack = attack,
            health = health,
            defense = defense,
            speed = speed,
            critChance = critChance,
            critDamage = critDamage,
            effectiveness = effectiveness,
            resistance = resistance,
            dualAttackChance = dualAttackChance,
            combatPower = combatPower.toInt(),
            effectiveHealth = effectiveHealth.toInt(),
            damage = damage.toInt(),
            damagePerSpeed = (damage * speed / 1000.0).toInt(),
            gearScore = items.sumOf(::gearScore),
            breakdowns = buildBreakdowns(
                items,
                percentageBaseAttack,
                percentageBaseHealth,
                percentageBaseDefense,
                setCounts,
                exclusiveBonus,
            ),
        )
    }

    private fun exclusiveEquipmentBonus(
        hero: E7Hero,
        baseAttack: Int,
        baseHealth: Int,
        baseDefense: Int,
    ): ExclusiveEquipmentBonus {
        val equipment = hero.exclusiveEquipment ?: return ExclusiveEquipmentBonus()
        // The catalog stores the roll range, not an imported per-instance roll.
        val value = equipment.statMax
        if (!value.isFinite() || value == 0.0) return ExclusiveEquipmentBonus()
        val stat = when (equipment.statType.lowercase()) {
            "attack" -> OptimizerStat.ATTACK
            "health" -> OptimizerStat.HEALTH
            "defense" -> OptimizerStat.DEFENSE
            "speed" -> OptimizerStat.SPEED
            "critical_chance" -> OptimizerStat.CRIT_CHANCE
            "critical_damage" -> OptimizerStat.CRIT_DAMAGE
            "effectiveness" -> OptimizerStat.EFFECTIVENESS
            "effect_resistance" -> OptimizerStat.RESISTANCE
            else -> return ExclusiveEquipmentBonus()
        }
        val panelValue = when (stat) {
            OptimizerStat.ATTACK -> if (equipment.statPercent) baseAttack * value / 100.0 else value
            OptimizerStat.HEALTH -> if (equipment.statPercent) baseHealth * value / 100.0 else value
            OptimizerStat.DEFENSE -> if (equipment.statPercent) baseDefense * value / 100.0 else value
            OptimizerStat.SPEED,
            OptimizerStat.CRIT_CHANCE,
            OptimizerStat.CRIT_DAMAGE,
            OptimizerStat.EFFECTIVENESS,
            OptimizerStat.RESISTANCE,
            -> value
        }
        return ExclusiveEquipmentBonus(
            stat = stat,
            panelValue = panelValue,
            displayValue = value,
            isPercent = equipment.statPercent,
        )
    }

    private fun buildBreakdowns(
        items: List<E7Gear>,
        baseAttack: Int,
        baseHealth: Int,
        baseDefense: Int,
        setCounts: Map<String, Int>,
        exclusiveBonus: ExclusiveEquipmentBonus,
    ): Map<OptimizerStat, StatBreakdown> {
        // 分类累加装备的百分比与固定值（只对攻击/生命/防御区分，其余属性装备只有固定值）。
        var attackPct = 0.0
        var attackFlat = 0.0
        var healthPct = 0.0
        var healthFlat = 0.0
        var defensePct = 0.0
        var defenseFlat = 0.0
        var speed = 0.0
        var critChance = 0.0
        var critDamage = 0.0
        var effectiveness = 0.0
        var resistance = 0.0
        items.forEach { item ->
            (listOf(item.mainStat) + item.substats).forEach { stat ->
                when (stat.type) {
                    "Attack" -> attackFlat += stat.value
                    "AttackPercent" -> attackPct += stat.value
                    "Health" -> healthFlat += stat.value
                    "HealthPercent" -> healthPct += stat.value
                    "Defense" -> defenseFlat += stat.value
                    "DefensePercent" -> defensePct += stat.value
                    "Speed" -> speed += stat.value
                    "CriticalHitChancePercent" -> critChance += stat.value
                    "CriticalHitDamagePercent" -> critDamage += stat.value
                    "EffectivenessPercent" -> effectiveness += stat.value
                    "EffectResistancePercent" -> resistance += stat.value
                }
            }
        }
        return mapOf(
            OptimizerStat.ATTACK to StatBreakdown(
                gearPercent = attackPct,
                gearFlat = attackFlat,
                setBonus = setCounts.completeCount("set_att") * 45.0,
                setIsPercent = true,
                exclusiveEquipmentBonus = exclusiveBonus.displayValueFor(OptimizerStat.ATTACK),
                exclusiveEquipmentIsPercent = exclusiveBonus.isPercent,
            ),
            OptimizerStat.HEALTH to StatBreakdown(
                gearPercent = healthPct,
                gearFlat = healthFlat,
                setBonus = setCounts.completeCount("set_max_hp") * 20.0 +
                    setCounts.completeCount("set_opener") * 20.0 -
                    setCounts.completeCount("set_torrent") * 10.0,
                setIsPercent = true,
                exclusiveEquipmentBonus = exclusiveBonus.displayValueFor(OptimizerStat.HEALTH),
                exclusiveEquipmentIsPercent = exclusiveBonus.isPercent,
            ),
            OptimizerStat.DEFENSE to StatBreakdown(
                gearPercent = defensePct,
                gearFlat = defenseFlat,
                setBonus = setCounts.completeCount("set_def") * 20.0,
                setIsPercent = true,
                exclusiveEquipmentBonus = exclusiveBonus.displayValueFor(OptimizerStat.DEFENSE),
                exclusiveEquipmentIsPercent = exclusiveBonus.isPercent,
            ),
            OptimizerStat.SPEED to StatBreakdown(
                gearFlat = speed,
                setBonus = setCounts.completeCount("set_speed") * 25.0 +
                    setCounts.completeCount("set_revenge") * 12.0 +
                    (setCounts.completeCount("set_revenant") + setCounts.completeCount("set_weak")) * 15.0,
                setIsPercent = true,
                exclusiveEquipmentBonus = exclusiveBonus.displayValueFor(OptimizerStat.SPEED),
                exclusiveEquipmentIsPercent = exclusiveBonus.isPercent,
            ),
            OptimizerStat.CRIT_CHANCE to StatBreakdown(
                gearFlat = critChance,
                setBonus = setCounts.completeCount("set_cri") * 12.0,
                exclusiveEquipmentBonus = exclusiveBonus.displayValueFor(OptimizerStat.CRIT_CHANCE),
                exclusiveEquipmentIsPercent = exclusiveBonus.isPercent,
            ),
            OptimizerStat.CRIT_DAMAGE to StatBreakdown(
                gearFlat = critDamage,
                setBonus = setCounts.completeCount("set_cri_dmg") * 60.0,
                exclusiveEquipmentBonus = exclusiveBonus.displayValueFor(OptimizerStat.CRIT_DAMAGE),
                exclusiveEquipmentIsPercent = exclusiveBonus.isPercent,
            ),
            OptimizerStat.EFFECTIVENESS to StatBreakdown(
                gearFlat = effectiveness,
                setBonus = setCounts.completeCount("set_acc") * 20.0,
                exclusiveEquipmentBonus = exclusiveBonus.displayValueFor(OptimizerStat.EFFECTIVENESS),
                exclusiveEquipmentIsPercent = exclusiveBonus.isPercent,
            ),
            OptimizerStat.RESISTANCE to StatBreakdown(
                gearFlat = resistance,
                setBonus = setCounts.completeCount("set_res") * 20.0,
                exclusiveEquipmentBonus = exclusiveBonus.displayValueFor(OptimizerStat.RESISTANCE),
                exclusiveEquipmentIsPercent = exclusiveBonus.isPercent,
            ),
        )
    }

    private fun selectCandidates(
        items: List<E7Gear>,
        hero: E7Hero,
        percentageBase: E7HeroStats,
        config: GearOptimizationConfig,
    ): List<E7Gear> {
        val ranked = items.sortedByDescending { item ->
            itemUtility(item, hero, percentageBase, config)
        }
        val selected = LinkedHashMap<Long, E7Gear>()
        ranked.take(candidatesPerSlot).forEach { selected[it.id] = it }
        config.requiredSets.forEach { required ->
            ranked.asSequence().filter { it.setCode == required }.take(REQUIRED_SET_RESERVE)
                .forEach { selected[it.id] = it }
        }
        return selected.values.toList()
    }

    private fun itemUtility(
        item: E7Gear,
        hero: E7Hero,
        percentageBase: E7HeroStats,
        config: GearOptimizationConfig,
    ): Double {
        hero.stats ?: return 0.0
        val accumulator = StatAccumulator().apply {
            add(
                item,
                percentageBase.attack ?: 0,
                percentageBase.health ?: 0,
                percentageBase.defense ?: 0,
            )
        }
        val objective = when (config.metric) {
            OptimizerMetric.COMBAT_POWER ->
                accumulator.attack / 8.0 + accumulator.health / 80.0 + accumulator.defense / 4.0 +
                    accumulator.speed * 3.0 + accumulator.critChance * 2.0 +
                    accumulator.critDamage * 1.2 + accumulator.effectiveness + accumulator.resistance
            OptimizerMetric.DAMAGE_PER_SPEED ->
                accumulator.attack / 5.0 + accumulator.speed * 3.0 +
                    accumulator.critChance * 3.0 + accumulator.critDamage * 1.8
            OptimizerMetric.EFFECTIVE_HEALTH ->
                accumulator.health / 40.0 + accumulator.defense / 2.0 + accumulator.speed * 1.5
            OptimizerMetric.SPEED -> accumulator.speed * 20.0 + gearScore(item)
            OptimizerMetric.GEAR_SCORE -> gearScore(item) * 5.0
        }
        return objective + gearScore(item)
    }

    private fun heuristicScore(
        partial: PartialBuild,
        hero: E7Hero,
        percentageBase: E7HeroStats,
        config: GearOptimizationConfig,
    ): Double {
        val base = hero.stats ?: return 0.0
        val accumulator = partial.accumulator
        val exclusiveBonus = exclusiveEquipmentBonus(
            hero = hero,
            baseAttack = percentageBase.attack ?: 0,
            baseHealth = percentageBase.health ?: 0,
            baseDefense = percentageBase.defense ?: 0,
        )
        val projected = ProjectedStats(
            attack = (base.attack ?: 0) + accumulator.attack +
                exclusiveBonus.panelValueFor(OptimizerStat.ATTACK),
            health = (base.health ?: 0) + accumulator.health +
                exclusiveBonus.panelValueFor(OptimizerStat.HEALTH),
            defense = (base.defense ?: 0) + accumulator.defense +
                exclusiveBonus.panelValueFor(OptimizerStat.DEFENSE),
            speed = (base.speed ?: 0) + accumulator.speed +
                exclusiveBonus.panelValueFor(OptimizerStat.SPEED),
            critChance = (base.criticalChance ?: 15) + accumulator.critChance +
                exclusiveBonus.panelValueFor(OptimizerStat.CRIT_CHANCE),
            critDamage = (base.criticalDamage ?: 150) + accumulator.critDamage +
                exclusiveBonus.panelValueFor(OptimizerStat.CRIT_DAMAGE),
            effectiveness = (base.effectiveness ?: 0) + accumulator.effectiveness +
                exclusiveBonus.panelValueFor(OptimizerStat.EFFECTIVENESS),
            resistance = (base.effectResistance ?: 0) + accumulator.resistance +
                exclusiveBonus.panelValueFor(OptimizerStat.RESISTANCE),
        )
        val objective = when (config.metric) {
            OptimizerMetric.COMBAT_POWER ->
                projected.attack / 10.0 + projected.health / 100.0 + projected.defense / 5.0 +
                    projected.speed * 2.0 + projected.critChance * 2.0 + projected.critDamage +
                    projected.effectiveness + projected.resistance
            OptimizerMetric.DAMAGE_PER_SPEED ->
                projected.attack / 5.0 + projected.speed * 3.0 +
                    projected.critChance * 3.0 + projected.critDamage * 2.0
            OptimizerMetric.EFFECTIVE_HEALTH ->
                projected.health / 50.0 + projected.defense / 2.5 + projected.speed
            OptimizerMetric.SPEED -> projected.speed * 20.0
            OptimizerMetric.GEAR_SCORE -> partial.gearScore * 10.0
        }
        val constraints = config.constraints
        val progress = progress(projected.attack, constraints.attack) +
            progress(projected.health, constraints.health) +
            progress(projected.defense, constraints.defense) +
            progress(projected.speed, constraints.speed) +
            progress(projected.critChance, constraints.critChance) +
            progress(projected.critDamage, constraints.critDamage) +
            progress(projected.effectiveness, constraints.effectiveness) +
            progress(projected.resistance, constraints.resistance)
        val setProgress = config.requiredSets.sumOf { set ->
            min(partial.setCounts.getOrDefault(set, 0).toDouble() / setPieces(set), 1.0)
        }
        return objective + partial.gearScore * 2.0 + progress * 5_000.0 + setProgress * 10_000.0
    }

    private fun progress(value: Double, minimum: Int): Double =
        if (minimum <= 0) 0.0 else min(value / minimum, 1.0)

    private data class PartialBuild(
        val items: List<E7Gear> = emptyList(),
        val accumulator: StatAccumulator = StatAccumulator(),
        val setCounts: Map<String, Int> = emptyMap(),
        val gearScore: Int = 0,
    ) {
        fun add(item: E7Gear, percentageBase: E7HeroStats): PartialBuild {
            val nextAccumulator = accumulator.copy().apply {
                add(
                    item,
                    percentageBase.attack ?: 0,
                    percentageBase.health ?: 0,
                    percentageBase.defense ?: 0,
                )
            }
            return PartialBuild(
                items = items + item,
                accumulator = nextAccumulator,
                setCounts = setCounts + (item.setCode to setCounts.getOrDefault(item.setCode, 0) + 1),
                gearScore = gearScore + gearScore(item),
            )
        }
    }

    private data class StatAccumulator(
        var attack: Double = 0.0,
        var health: Double = 0.0,
        var defense: Double = 0.0,
        var speed: Double = 0.0,
        var critChance: Double = 0.0,
        var critDamage: Double = 0.0,
        var effectiveness: Double = 0.0,
        var resistance: Double = 0.0,
        var dualAttackChance: Double = 0.0,
    ) {
        fun add(item: E7Gear, baseAttack: Int, baseHealth: Int, baseDefense: Int) {
            add(item.mainStat, baseAttack, baseHealth, baseDefense)
            item.substats.forEach { add(it, baseAttack, baseHealth, baseDefense) }
        }

        private fun add(stat: E7GearStat, baseAttack: Int, baseHealth: Int, baseDefense: Int) {
            when (stat.type) {
                "Attack" -> attack += stat.value
                "AttackPercent" -> attack += stat.value / 100.0 * baseAttack
                "Health" -> health += stat.value
                "HealthPercent" -> health += stat.value / 100.0 * baseHealth
                "Defense" -> defense += stat.value
                "DefensePercent" -> defense += stat.value / 100.0 * baseDefense
                "Speed" -> speed += stat.value
                "CriticalHitChancePercent" -> critChance += stat.value
                "CriticalHitDamagePercent" -> critDamage += stat.value
                "EffectivenessPercent" -> effectiveness += stat.value
                "EffectResistancePercent" -> resistance += stat.value
                "DualAttackChancePercent" -> dualAttackChance += stat.value
            }
        }
    }

    private data class ProjectedStats(
        val attack: Double,
        val health: Double,
        val defense: Double,
        val speed: Double,
        val critChance: Double,
        val critDamage: Double,
        val effectiveness: Double,
        val resistance: Double,
    )

    companion object {
        private val SLOT_ORDER = listOf(
            GearSlot.WEAPON,
            GearSlot.HELMET,
            GearSlot.ARMOR,
            GearSlot.NECKLACE,
            GearSlot.RING,
            GearSlot.BOOTS,
        )
        private const val DEFAULT_CANDIDATES_PER_SLOT = 48
        private const val DEFAULT_BEAM_WIDTH = 3_000
        private const val REQUIRED_SET_RESERVE = 24
        private const val MAX_RESULTS = 100
        private const val CANCELLATION_CHECK_INTERVAL = 64
        private const val TARGET_DEFENSE = 1_500.0
        private const val DEFAULT_DUAL_ATTACK_CHANCE = 3.0
        private const val UNITY_SET_DUAL_ATTACK_CHANCE = 4.0
        private const val MAX_DUAL_ATTACK_CHANCE = 20.0
        private val PEN_MULTIPLIER = (TARGET_DEFENSE / 300.0 + 1.0) /
            (0.00283333 * TARGET_DEFENSE + 1.0)

        val SET_PIECES = mapOf(
            "set_max_hp" to 2,
            "set_def" to 2,
            "set_att" to 4,
            "set_speed" to 4,
            "set_cri" to 2,
            "set_acc" to 2,
            "set_cri_dmg" to 4,
            "set_vampire" to 4,
            "set_counter" to 4,
            "set_res" to 2,
            "set_coop" to 2,
            "set_rage" to 4,
            "set_immune" to 2,
            "set_penetrate" to 2,
            "set_revenge" to 4,
            "set_scar" to 4,
            "set_shield" to 4,
            "set_torrent" to 2,
            "set_revenant" to 4,
            "set_riposte" to 4,
            "set_opener" to 4,
            "set_chase" to 2,
            "set_weak" to 4,
            "set_might" to 2,
        )

        fun setPieces(code: String): Int = SET_PIECES[code] ?: 0

        fun hasOnlyCompleteSets(counts: Map<String, Int>): Boolean =
            counts.entries.sumOf { (set, count) ->
                val pieces = setPieces(set)
                if (pieces == 0) 0 else count / pieces * pieces
            } == 6

        fun completedSets(counts: Map<String, Int>): List<String> = buildList {
            counts.forEach { (set, count) ->
                val pieces = setPieces(set)
                if (pieces > 0) repeat(count / pieces) { add(set) }
            }
        }

        private fun Map<String, Int>.completeCount(set: String): Int {
            val pieces = setPieces(set)
            return if (pieces == 0) 0 else getOrDefault(set, 0) / pieces
        }

        private fun canStillFormCompleteSets(
            counts: Map<String, Int>,
            remainingSlots: Int,
        ): Boolean {
            val piecesNeeded = counts.entries.sumOf { (set, count) ->
                val pieces = setPieces(set)
                if (pieces == 0 || count % pieces == 0) 0 else pieces - count % pieces
            }
            val uncommittedSlots = remainingSlots - piecesNeeded
            return uncommittedSlots >= 0 && uncommittedSlots % 2 == 0
        }

        private fun canStillMeetRequiredSets(
            counts: Map<String, Int>,
            requiredSets: Set<String>,
            remainingSlots: Int,
        ): Boolean = requiredSets.sumOf { set ->
            (setPieces(set) - counts.getOrDefault(set, 0)).coerceAtLeast(0)
        } <= remainingSlots

        private fun passesConstraints(stats: OptimizedHeroStats, c: OptimizerConstraints): Boolean =
            stats.attack >= c.attack && stats.health >= c.health && stats.defense >= c.defense &&
                stats.speed >= c.speed && stats.critChance >= c.critChance &&
                stats.critDamage >= c.critDamage && stats.effectiveness >= c.effectiveness &&
                stats.resistance >= c.resistance

        private fun rankingValue(stats: OptimizedHeroStats, metric: OptimizerMetric): Long = when (metric) {
            OptimizerMetric.COMBAT_POWER -> stats.combatPower.toLong()
            OptimizerMetric.DAMAGE_PER_SPEED -> stats.damagePerSpeed.toLong()
            OptimizerMetric.EFFECTIVE_HEALTH -> stats.effectiveHealth.toLong()
            OptimizerMetric.SPEED -> stats.speed.toLong()
            OptimizerMetric.GEAR_SCORE -> stats.gearScore.toLong()
        }

        /** Fribbels ItemDb weighted substat score (main stat excluded). */
        fun gearScore(item: E7Gear): Int = item.substats.sumOf { stat ->
            when (stat.type) {
                "AttackPercent", "DefensePercent", "HealthPercent",
                "EffectResistancePercent", "EffectivenessPercent",
                -> stat.value
                "Speed" -> stat.value * 2.0
                "CriticalHitDamagePercent" -> stat.value * (8.0 / 7.0)
                "CriticalHitChancePercent" -> stat.value * (8.0 / 5.0)
                "Attack" -> stat.value * (3.46 / 39.0)
                "Defense" -> stat.value * (4.99 / 31.0)
                "Health" -> stat.value * (3.09 / 174.0)
                else -> 0.0
            }
        }.roundToInt()
    }
}
