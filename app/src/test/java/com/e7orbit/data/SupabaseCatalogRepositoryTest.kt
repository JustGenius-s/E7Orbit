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
}
