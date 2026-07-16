package com.e7orbit.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HuntConfigTest {
    @Test
    fun `defaults expose only implemented hunt capabilities`() {
        val config = HuntConfig()

        assertTrue(config.difficulty.isSupported)
        assertTrue(config.managedBattle)
        assertTrue(config.energyRefill.isSupported)
        assertEquals(HuntEnergyRefill.DISABLED, config.energyRefill)
    }

    @Test
    fun `normalized migrates unsupported persisted capabilities to safe values`() {
        val normalized = HuntConfig(
            difficulty = HuntDifficulty.OTHERWORLD,
            managedBattle = false,
            runCount = 0,
            energyRefill = HuntEnergyRefill.LEIF_THEN_SKYSTONE,
        ).normalized()

        assertEquals(HuntDifficulty.HELL, normalized.difficulty)
        assertTrue(normalized.managedBattle)
        assertEquals(1, normalized.runCount)
        assertEquals(HuntEnergyRefill.DISABLED, normalized.energyRefill)
    }

    @Test
    fun `normalized caps runs at the supported managed batch size`() {
        val normalized = HuntConfig(runCount = Int.MAX_VALUE).normalized()

        assertEquals(MAX_SUPPORTED_HUNT_RUNS, normalized.runCount)
    }
}
