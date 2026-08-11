package com.e7orbit.optimizer

import com.e7orbit.data.E7Artifact
import com.e7orbit.data.E7DataSource
import com.e7orbit.data.E7Hero
import com.e7orbit.data.E7HeroStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ArtifactBonusTest {
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

    private val artifact = E7Artifact(
        code = "a1",
        name = "Test Artifact",
        rarity = 5,
        role = "warrior",
        attack = 500,
        health = 1_200,
        defense = null,
        description = null,
    )

    @Test
    fun roleRestrictedArtifactIgnoredForOtherRoles() {
        val mageHero = hero.copy(role = "mage")
        assertSame(mageHero, mageHero.withArtifact(artifact))
    }

    @Test
    fun roleRestrictedArtifactAppliesToMatchingRole() {
        assertEquals(1_500, hero.withArtifact(artifact).stats?.attack)
    }

    @Test
    fun rolelessArtifactAppliesToAnyRole() {
        val roleless = artifact.copy(role = null)
        val mageHero = hero.copy(role = "mage")
        assertEquals(1_500, mageHero.withArtifact(roleless).stats?.attack)
    }

    @Test
    fun addsMaxLevelWhiteStats() {
        val result = hero.withArtifact(artifact)
        assertEquals(1_500, result.stats?.attack)
        assertEquals(6_200, result.stats?.health)
    }

    @Test
    fun nullArtifactUnchanged() {
        assertSame(hero, hero.withArtifact(null))
    }

    @Test
    fun noStatsUnchanged() {
        val empty = artifact.copy(attack = null, health = null)
        assertSame(hero, hero.withArtifact(empty))
    }

    @Test
    fun combinesWithImprint() {
        val result = hero.withArtifact(artifact)
        assertEquals(1_500, result.stats?.attack)
        assertEquals(6_200, result.stats?.health)
        // 防御不受神器影响
        assertEquals(500, result.stats?.defense)
    }
}
