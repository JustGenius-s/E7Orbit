package com.e7orbit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class WikiArtifactEditingTest {
    @Test
    fun draftBuildsValidatedArtifactUpdate() {
        val original = sampleArtifact()
        val updated = original.toWikiDraft().copy(
            name = "  Wiki Artifact  ",
            rarity = "5",
            role = "soulweaver",
            baseAttack = "12",
            attack = "180",
            defense = "",
            description = "  初始效果  ",
            imageUrl = "  https://example.com/new.webp  ",
        ).toArtifact(original)

        assertEquals("Wiki Artifact", updated.name)
        assertEquals(5, updated.rarity)
        assertEquals("soulweaver", updated.role)
        assertEquals(12, updated.baseAttack)
        assertEquals(180, updated.attack)
        assertNull(updated.defense)
        assertEquals("初始效果", updated.description)
        assertEquals("https://example.com/new.webp", updated.imageUrl)
    }

    @Test
    fun invalidRarityIsRejected() {
        val original = sampleArtifact()
        val error = assertThrows(IllegalArgumentException::class.java) {
            original.toWikiDraft().copy(rarity = "7").toArtifact(original)
        }

        assertEquals("稀有度必须在 1 到 6 之间", error.message)
    }

    @Test
    fun negativeStatsAreRejected() {
        val original = sampleArtifact()
        val error = assertThrows(IllegalArgumentException::class.java) {
            original.toWikiDraft().copy(health = "-1").toArtifact(original)
        }

        assertEquals("满级生命值 不能小于 0", error.message)
    }

    private fun sampleArtifact(): E7Artifact = E7Artifact(
        code = "ef0001",
        name = "Test Artifact",
        rarity = 5,
        role = "warrior",
        attack = 195,
        health = 702,
        defense = null,
        description = "Base effect",
        maxDescription = "Max effect",
        lore = "Lore",
        imageUrl = "https://example.com/artifact.webp",
        iconUrl = "https://example.com/icon.webp",
        baseAttack = 15,
        baseHealth = 54,
    )
}
