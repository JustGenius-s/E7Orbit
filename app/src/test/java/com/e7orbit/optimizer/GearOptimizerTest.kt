package com.e7orbit.optimizer

import com.e7orbit.data.E7DataSource
import com.e7orbit.data.E7Gear
import com.e7orbit.data.E7GearStat
import com.e7orbit.data.E7Hero
import com.e7orbit.data.E7HeroStats
import com.e7orbit.data.E7ScannedHero
import com.e7orbit.data.GearSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.system.measureTimeMillis
import org.junit.Test

class GearOptimizerTest {
    private val hero = E7Hero(
        code = "test",
        name = "Test Hero",
        rarity = 5,
        attribute = "fire",
        role = "warrior",
        zodiac = null,
        stats = E7HeroStats(
            attack = 1_000,
            health = 5_000,
            defense = 500,
            speed = 100,
            criticalChance = 15,
            criticalDamage = 150,
            effectiveness = 0,
            effectResistance = 0,
            combatPower = null,
        ),
        source = E7DataSource.OFFICIAL_AND_FRIBBELS,
    )

    @Test
    fun calculatesFribbelsStatsAndFourPlusTwoSets() {
        val items = listOf(
            gear(1, GearSlot.WEAPON, "set_speed", E7GearStat("Attack", 500.0)),
            gear(2, GearSlot.HELMET, "set_speed", E7GearStat("Health", 2_000.0)),
            gear(3, GearSlot.ARMOR, "set_speed", E7GearStat("Defense", 300.0)),
            gear(4, GearSlot.NECKLACE, "set_speed", E7GearStat("CriticalHitDamagePercent", 70.0)),
            gear(5, GearSlot.RING, "set_cri", E7GearStat("AttackPercent", 65.0)),
            gear(6, GearSlot.BOOTS, "set_cri", E7GearStat("Speed", 45.0)),
        )

        val stats = GearOptimizer().calculateStats(hero, items)

        assertEquals(2_150, stats.attack)
        assertEquals(7_000, stats.health)
        assertEquals(800, stats.defense)
        assertEquals(170, stats.speed)
        assertEquals(27, stats.critChance)
        assertEquals(220, stats.critDamage)
        // Fribbels WSS only scores substats; these fixtures only have main stats.
        assertEquals(0, items.sumOf(GearOptimizer::gearScore))
        assertTrue(GearOptimizer.hasOnlyCompleteSets(items.groupingBy(E7Gear::setCode).eachCount()))
    }

    @Test
    fun groupsEquippedGearByScannedHeroAndCalculatesFinalStats() {
        val instanceId = 77L
        val scannedHeroes = listOf(
            E7ScannedHero(id = instanceId, name = "Test-Hero", stars = 6, awaken = 6),
        )
        val items = listOf(
            gear(1, GearSlot.WEAPON, "set_speed", E7GearStat("Attack", 500.0)),
            gear(2, GearSlot.HELMET, "set_speed", E7GearStat("Health", 2_000.0)),
            gear(3, GearSlot.ARMOR, "set_speed", E7GearStat("Defense", 300.0)),
            gear(4, GearSlot.NECKLACE, "set_speed", E7GearStat("CriticalHitDamagePercent", 70.0)),
            gear(5, GearSlot.RING, "set_cri", E7GearStat("AttackPercent", 65.0)),
            gear(6, GearSlot.BOOTS, "set_cri", E7GearStat("Speed", 45.0)),
        ).map { it.copy(equippedHeroId = instanceId) }

        val build = buildEquippedHeroes(scannedHeroes, listOf(hero), items).single()

        assertEquals(instanceId, build.instanceId)
        assertEquals("Test-Hero", build.displayName)
        assertEquals(hero.code, build.hero?.code)
        assertTrue(build.isComplete)
        assertEquals(170, build.stats?.speed)
        assertEquals(1, build.sets.first { it.code == "set_speed" }.completedCount)
        assertEquals(1, build.sets.first { it.code == "set_cri" }.completedCount)
    }

    @Test
    fun findsBuildMatchingRequiredSetsAndConstraints() {
        val inventory = buildList {
            GearSlot.entries.filter { it != GearSlot.UNKNOWN }.forEachIndexed { index, slot ->
                add(gear(index.toLong() + 1, slot, "set_max_hp", E7GearStat("Speed", 10.0)))
                add(gear(index.toLong() + 101, slot, "set_cri", E7GearStat("AttackPercent", 30.0)))
            }
        }

        val outcome = GearOptimizer(candidatesPerSlot = 20, beamWidth = 2_000).optimize(
            hero = hero,
            inventory = inventory,
            config = GearOptimizationConfig(
                metric = OptimizerMetric.SPEED,
                constraints = OptimizerConstraints(speed = 155),
                requiredSets = setOf("set_max_hp"),
                maxResults = 10,
            ),
        )

        assertTrue(outcome.builds.isNotEmpty())
        outcome.builds.forEach { build ->
            assertTrue(build.stats.speed >= 155)
            assertTrue("set_max_hp" in build.completedSets)
            assertEquals(6, build.items.size)
        }
    }

    @Test
    fun handlesRealisticInventorySizeWithinInteractiveTime() {
        val sets = listOf("set_speed", "set_cri", "set_max_hp", "set_att", "set_def", "set_res")
        var nextId = 1L
        val inventory = buildList {
            GearSlot.entries.filter { it != GearSlot.UNKNOWN }.forEachIndexed { slotIndex, slot ->
                repeat(236) { itemIndex ->
                    val set = sets[(itemIndex + slotIndex) % sets.size]
                    add(
                        E7Gear(
                            id = nextId++,
                            code = "large-$nextId",
                            slot = slot,
                            setCode = set,
                            setName = set,
                            rank = "传说",
                            level = 90,
                            enhance = 15,
                            mainStat = when (slot) {
                                GearSlot.NECKLACE -> E7GearStat("CriticalHitDamagePercent", 70.0)
                                GearSlot.RING -> E7GearStat("AttackPercent", 65.0)
                                GearSlot.BOOTS -> E7GearStat("Speed", 45.0)
                                else -> E7GearStat("Health", 2_000.0)
                            },
                            substats = listOf(
                                E7GearStat("Speed", (itemIndex % 25 + 1).toDouble()),
                                E7GearStat("CriticalHitChancePercent", (itemIndex % 16 + 1).toDouble()),
                                E7GearStat("AttackPercent", (itemIndex % 35 + 5).toDouble()),
                                E7GearStat("HealthPercent", (itemIndex % 30 + 5).toDouble()),
                            ),
                            locked = false,
                        ),
                    )
                }
            }
        }
        lateinit var result: GearOptimizationOutcome
        val elapsed = measureTimeMillis {
            result = GearOptimizer().optimize(
                hero = hero,
                inventory = inventory,
                config = GearOptimizationConfig(
                    metric = OptimizerMetric.COMBAT_POWER,
                    constraints = OptimizerConstraints(speed = 180, critChance = 85),
                    requiredSets = setOf("set_speed"),
                ),
            )
        }

        assertEquals(1_416, inventory.size)
        assertTrue(result.builds.isNotEmpty())
        assertTrue("elapsed=${elapsed}ms", elapsed < 5_000)
        assertTrue(result.combinationsEvaluated > 1_000)
    }

    @Test
    fun excludesLockedEquippedAndUnenhancedItems() {
        val inventory = GearSlot.entries.filter { it != GearSlot.UNKNOWN }.flatMapIndexed { index, slot ->
            listOf(
                gear(index.toLong(), slot, "set_max_hp", E7GearStat("Speed", 8.0)),
                gear(100L + index, slot, "set_max_hp", E7GearStat("Speed", 50.0), locked = true),
                gear(200L + index, slot, "set_max_hp", E7GearStat("Speed", 40.0), equipped = true),
                gear(300L + index, slot, "set_max_hp", E7GearStat("Speed", 30.0), enhance = 12),
            )
        }

        val result = GearOptimizer(candidatesPerSlot = 20, beamWidth = 500).optimize(
            hero = hero,
            inventory = inventory,
            config = GearOptimizationConfig(
                metric = OptimizerMetric.SPEED,
                allowLocked = false,
                allowEquipped = false,
                onlyMaxed = true,
            ),
        ).builds.single()

        assertEquals(148, result.stats.speed)
        assertTrue(result.items.none(E7Gear::locked))
        assertTrue(result.items.none { it.equippedHeroId != null })
        assertTrue(result.items.all { it.enhance == 15 })
    }

    private fun gear(
        id: Long,
        slot: GearSlot,
        set: String,
        main: E7GearStat,
        locked: Boolean = false,
        equipped: Boolean = false,
        enhance: Int = 15,
    ): E7Gear = E7Gear(
        id = id,
        code = "gear-$id",
        slot = slot,
        setCode = set,
        setName = set,
        rank = "传说",
        level = 90,
        enhance = enhance,
        mainStat = main,
        substats = emptyList(),
        locked = locked,
        equippedHeroId = if (equipped) 1L else null,
    )
}
