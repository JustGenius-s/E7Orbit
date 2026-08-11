package com.e7orbit.optimizer

import com.e7orbit.data.E7DataSource
import com.e7orbit.data.E7Hero
import com.e7orbit.data.E7HeroStats
import com.e7orbit.data.E7ImprintGrade
import com.e7orbit.data.E7ImprintSection
import com.e7orbit.data.E7MemoryImprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SelfImprintTest {
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
    fun appliesSelectedConcentrationRank() {
        val imprinted = hero.copy(
            memoryImprint = E7MemoryImprint(
                concentration = E7ImprintSection(
                    grades = listOf(
                        E7ImprintGrade(rank = "SSS", value = "Critical Hit Chance +16.8%"),
                        E7ImprintGrade(rank = "B", value = "Critical Hit Chance +8.4%"),
                    ),
                ),
            ),
        )

        assertEquals(31, imprinted.withSelfImprint(ImprintRank.SSS).stats?.criticalChance)
        assertEquals(23, imprinted.withSelfImprint(ImprintRank.B).stats?.criticalChance)
        // 未提供的档位不叠加
        assertEquals(15, imprinted.withSelfImprint(ImprintRank.S).stats?.criticalChance)
    }

    @Test
    fun prefersStructuredFieldsOverText() {
        val imprinted = hero.copy(
            memoryImprint = E7MemoryImprint(
                concentration = E7ImprintSection(
                    grades = listOf(
                        E7ImprintGrade(
                            rank = "SSS",
                            value = "Speed +4",
                            stat = "speed",
                            amount = 6.0,
                            percent = false,
                        ),
                    ),
                ),
            ),
        )

        assertEquals(106, imprinted.withSelfImprint(ImprintRank.SSS).stats?.speed)
    }

    @Test
    fun percentAttackScalesOffBaseStat() {
        val imprinted = hero.copy(
            memoryImprint = E7MemoryImprint(
                concentration = E7ImprintSection(
                    grades = listOf(E7ImprintGrade(rank = "SSS", value = "Attack +8.4%")),
                ),
            ),
        )

        assertEquals(1_084, imprinted.withSelfImprint().stats?.attack)
    }

    @Test
    fun flatSpeedAddsDirectly() {
        val imprinted = hero.copy(
            memoryImprint = E7MemoryImprint(
                concentration = E7ImprintSection(
                    grades = listOf(E7ImprintGrade(rank = "SS", value = "Speed +4")),
                ),
            ),
        )

        assertEquals(104, imprinted.withSelfImprint(ImprintRank.SS).stats?.speed)
    }

    @Test
    fun heroesWithoutImprintStayUnchanged() {
        assertSame(hero, hero.withSelfImprint())
    }

    @Test
    fun unparseableValuesAreIgnored() {
        val imprinted = hero.copy(
            memoryImprint = E7MemoryImprint(
                concentration = E7ImprintSection(
                    grades = listOf(E7ImprintGrade(rank = "SSS", value = "未知加成")),
                ),
            ),
        )

        assertSame(imprinted, imprinted.withSelfImprint())
    }
}
