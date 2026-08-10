package com.e7orbit.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtifactDescriptionDiffTest {
    @Test
    fun `highlights an entire replaced percentage token`() {
        val base = "After attacking, has a 50% chance to deal additional damage."
        val updated = "After attacking, has a 100% chance to deal additional damage."

        assertEquals(listOf("100%"), highlightedText(base, updated))
    }

    @Test
    fun `highlights the replaced token when comparing in reverse`() {
        val base = "After attacking, has a 50% chance to deal additional damage."
        val updated = "After attacking, has a 100% chance to deal additional damage."

        assertEquals(listOf("50%"), highlightedText(updated, base))
    }

    @Test
    fun `highlights multiple changed values independently`() {
        val base = "Increases Hit Chance by 10% and Critical Hit Damage by 15%."
        val updated = "Increases Hit Chance by 20% and Critical Hit Damage by 30%."

        assertEquals(listOf("20%", "30%"), highlightedText(base, updated))
    }

    @Test
    fun `leaves a repeated unchanged value unhighlighted`() {
        val base = "Increases Effectiveness by 35%. Has a 35% chance to debuff."
        val updated = "Increases Effectiveness by 35%. Has a 70% chance to debuff."

        assertEquals(listOf("70%"), highlightedText(base, updated))
    }

    @Test
    fun `highlights consecutive inserted words as one range`() {
        val base = "Increases damage dealt."
        val updated = "Increases greatly increased damage dealt."

        assertEquals(listOf("greatly increased"), highlightedText(base, updated))
    }

    @Test
    fun `ignores whitespace-only changes`() {
        val base = "After attacking, has a 50% chance."
        val updated = "After  attacking, has a 50% chance."

        assertEquals(emptyList<String>(), highlightedText(base, updated))
    }

    @Test
    fun `returns no ranges for identical descriptions`() {
        val description = "Increases Attack by 10%."

        assertEquals(emptyList<String>(), highlightedText(description, description))
    }

    private fun highlightedText(base: String, updated: String): List<String> =
        changedDescriptionRanges(base, updated).map { range ->
            updated.substring(range.start, range.endExclusive)
        }
}
