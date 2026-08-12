package com.e7orbit.data

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class WikiHeroEditingTest {
    @Test
    fun draftBuildsValidatedHeroUpdate() {
        val original = sampleHero()
        val baseDraft = original.toWikiDraft()
        val updated = baseDraft.copy(
            name = "  Wiki Hero  ",
            rarity = "5",
            attack = "1350",
            exclusiveEquipment = null,
            skills = listOf(
                baseDraft.skills.single().copy(
                    enhancements = "伤害提升 5%\n伤害提升 10%",
                    buffSlugs = listOf("attack_up", "speed_up"),
                    debuffSlugs = listOf("defense_down"),
                ),
            ),
        ).toHero(original)

        assertEquals("Wiki Hero", updated.name)
        assertEquals(5, updated.rarity)
        assertEquals(1350, updated.stats?.attack)
        assertNull(updated.exclusiveEquipment)
        assertEquals(listOf("伤害提升 5%", "伤害提升 10%"), updated.skills.single().enhancements)
        assertEquals(listOf("attack_up", "speed_up"), updated.skills.single().buffs.map { it.slug })
        assertEquals(listOf("defense_down"), updated.skills.single().debuffs.map { it.slug })
        assertEquals(listOf(JsonPrimitive(1.0)), updated.skills.single().values)
    }

    @Test
    fun selectedStatusEffectsKeepOrderAndRemoveDuplicates() {
        val original = sampleHero()
        val baseDraft = original.toWikiDraft()

        val updated = baseDraft.copy(
            skills = listOf(
                baseDraft.skills.single().copy(
                    buffSlugs = listOf("speed_up", "attack_up", "speed_up"),
                    debuffSlugs = listOf("defense_down", "defense_down"),
                ),
            ),
        ).toHero(original)

        assertEquals(listOf("speed_up", "attack_up"), updated.skills.single().buffs.map { it.slug })
        assertEquals(listOf("defense_down"), updated.skills.single().debuffs.map { it.slug })
    }

    @Test
    fun duplicateSkillSlotsAreRejected() {
        val original = sampleHero()
        val skill = original.toWikiDraft().skills.single()
        val error = assertThrows(IllegalArgumentException::class.java) {
            original.toWikiDraft().copy(
                skills = listOf(skill, skill.copy(name = "重复技能")),
            ).toHero(original)
        }

        assertEquals("技能栏位不能重复", error.message)
    }

    @Test
    fun incompleteExclusiveEquipmentIsRejected() {
        val original = sampleHero()
        val equipment = original.toWikiDraft().exclusiveEquipment!!
        val error = assertThrows(IllegalArgumentException::class.java) {
            original.toWikiDraft().copy(
                exclusiveEquipment = equipment.copy(statMax = "5", statMin = "10"),
            ).toHero(original)
        }

        assertEquals("专属装备属性上限不能小于下限", error.message)
    }

    private fun sampleHero(): E7Hero = E7Hero(
        code = "c1001",
        name = "Test Hero",
        rarity = 5,
        attribute = "fire",
        role = "warrior",
        zodiac = "aries",
        stats = E7HeroStats(
            attack = 1200,
            health = 6000,
            defense = 700,
            speed = 110,
            criticalChance = 15,
            criticalDamage = 150,
            effectiveness = 0,
            effectResistance = 0,
            combatPower = 17000,
        ),
        assets = E7HeroAssets(
            iconUrl = "https://example.com/icon.webp",
            thumbnailUrl = "https://example.com/thumb.webp",
            imageUrl = "https://example.com/art.webp",
        ),
        description = "Description",
        skills = listOf(
            E7HeroSkill(
                slot = 1,
                name = "Skill One",
                description = "Deals damage.",
                values = listOf(JsonPrimitive(1.0)),
                buffs = listOf(E7StatusEffect("attack_up", "攻击力提升")),
            ),
        ),
        exclusiveEquipment = E7HeroExclusiveEquipment(
            code = "ee-c1001",
            heroCode = "c1001",
            name = "Exclusive",
            iconUrl = "https://example.com/ee.webp",
            statType = "attack",
            statMin = 7.0,
            statMax = 14.0,
            enhancements = (1..3).map { option ->
                E7ExclusiveEquipmentEnhancement(
                    option = option,
                    skillSlot = 1,
                    description = "Option $option",
                )
            },
        ),
    )
}
