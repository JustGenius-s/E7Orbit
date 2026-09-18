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
            buffSlugs = listOf("efct_ex_turn", "efct_cleanse"),
            debuffSlugs = listOf("stic_stun"),
        )
        val effects = listOf(
            SupabaseStatusEffectRow(
                slug = "efct_cleanse",
                label = "Cleanse",
                description = "Removes debuffs",
                iconUrl = null,
            ),
            SupabaseStatusEffectRow(
                slug = "efct_ex_turn",
                label = "Extra Turn",
            ),
            SupabaseStatusEffectRow(
                slug = "stic_stun",
                label = "Stun",
            ),
        ).associateBy(SupabaseStatusEffectRow::slug)

        val domain = skill.toDomain(effects)

        assertEquals(listOf("efct_ex_turn", "efct_cleanse"), domain.buffs.map(E7StatusEffect::slug))
        assertEquals(listOf("stic_stun"), domain.debuffs.map(E7StatusEffect::slug))
        assertEquals("Removes debuffs", domain.buffs.last().description)
        assertEquals(null, domain.buffs.last().iconUrl)
    }

    @Test
    fun `exclusive equipment row maps the canonical catalog shape`() {
        val equipment = SupabaseExclusiveEquipmentRow(
            code = "ee-c1001",
            heroCode = "c1001",
            name = "Test Equipment",
            iconUrl = "https://example.com/equipment.png",
            statType = "speed",
            statMin = 5.0,
            statMax = 10.0,
            enhancements = listOf(
                SupabaseExclusiveEnhancementRow(option = 1, skillSlot = 1, description = "First"),
                SupabaseExclusiveEnhancementRow(option = 2, skillSlot = 2, description = "Second"),
                SupabaseExclusiveEnhancementRow(option = 3, skillSlot = 3, description = "Third"),
            ),
        ).toDomain()

        assertEquals("c1001", equipment.heroCode)
        assertEquals("speed", equipment.statType)
        assertEquals(5.0, equipment.statMin, 0.0)
        assertEquals(10.0, equipment.statMax, 0.0)
        assertEquals(listOf(1, 2, 3), equipment.enhancements.map(E7ExclusiveEquipmentEnhancement::option))
        assertEquals(listOf(1, 2, 3), equipment.enhancements.map { it.skillSlot })
        assertEquals(listOf("First", "Second", "Third"), equipment.enhancements.map { it.description })
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
