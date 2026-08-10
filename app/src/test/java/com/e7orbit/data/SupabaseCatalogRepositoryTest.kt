package com.e7orbit.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SupabaseCatalogRepositoryTest {
    @Test
    fun `skill slug arrays resolve shared effect metadata in array order`() {
        val skill = SupabaseSkillRow(
            heroCode = "c1067",
            slot = 3,
            name = "Shining Star",
            buffSlugs = listOf("extra-turn", "cleanse"),
            debuffSlugs = listOf("stun"),
        )
        val effects = listOf(
            SupabaseStatusEffectRow(
                slug = "cleanse",
                label = "Cleanse",
                description = "Removes debuffs",
                iconUrl = "https://example.com/cleanse.png",
            ),
            SupabaseStatusEffectRow(
                slug = "extra-turn",
                label = "Extra Turn",
            ),
            SupabaseStatusEffectRow(
                slug = "stun",
                label = "Stun",
            ),
        ).associateBy(SupabaseStatusEffectRow::slug)

        val domain = skill.toDomain(effects)

        assertEquals(listOf("extra-turn", "cleanse"), domain.buffs.map(E7StatusEffect::slug))
        assertEquals(listOf("stun"), domain.debuffs.map(E7StatusEffect::slug))
        assertEquals("Removes debuffs", domain.buffs.last().description)
        assertEquals("https://example.com/cleanse.png", domain.buffs.last().iconUrl)
    }

    @Test
    fun `growth rows preserve awakening resources`() {
        val awakening = SupabaseAwakeningRow(
            rank = 3,
            stats = listOf(SupabaseGrowthStatRow(label = "Attack", value = "+20")),
            resources = listOf(
                SupabaseResourceCostRow(code = "light-rune", label = "Light Rune", quantity = 20),
            ),
            skillBefore = "Before",
            skillAfter = "After",
        ).toDomain()
        assertEquals(3, awakening.rank)
        assertEquals("+20", awakening.stats.single().value)
        assertEquals("light-rune", awakening.resources.single().code)
        assertEquals("After", awakening.skillAfter)
    }
}
