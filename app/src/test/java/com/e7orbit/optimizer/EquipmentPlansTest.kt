package com.e7orbit.optimizer

import com.e7orbit.data.E7Gear
import com.e7orbit.data.E7GearStat
import com.e7orbit.data.GearSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EquipmentPlansTest {
    @Test
    fun applyingBuildOnlyChangesSelectedPlanAndResolvesConflicts() {
        val imported = gears().mapIndexed { index, gear ->
            gear.copy(equippedHeroId = if (index < 3) 10L else 20L)
        }
        val original = createEquipmentPlan("原始", imported, nowEpochMs = 1L, id = "a")
        val independentCopy = original.copyAs("副本", nowEpochMs = 2L, id = "b")
        val selectedIds = imported.map(E7Gear::id)

        val updated = original.applyBuild(
            heroId = 10L,
            gearIds = selectedIds,
            validGearIds = selectedIds.toSet(),
            nowEpochMs = 3L,
        )

        assertTrue(selectedIds.all { updated.assignments[it] == 10L })
        assertEquals(3, independentCopy.assignments.values.count { it == 10L })
        assertEquals(3, independentCopy.assignments.values.count { it == 20L })
        assertEquals(3L, updated.updatedAtEpochMs)
    }

    @Test
    fun applyingPlanClearsAssignmentsMissingFromPlan() {
        val imported = gears().map { it.copy(equippedHeroId = 99L) }
        val plan = createEquipmentPlan("空方案", emptyList(), nowEpochMs = 1L, id = "empty")

        val applied = plan.applyTo(imported)

        assertTrue(applied.all { it.equippedHeroId == null })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBuildWithMissingInventoryItem() {
        val imported = gears()
        val plan = createEquipmentPlan("方案", imported, nowEpochMs = 1L, id = "a")
        plan.applyBuild(
            heroId = 10L,
            gearIds = imported.map(E7Gear::id),
            validGearIds = imported.dropLast(1).mapTo(hashSetOf(), E7Gear::id),
        )
    }

    private fun gears(): List<E7Gear> = listOf(
        GearSlot.WEAPON,
        GearSlot.HELMET,
        GearSlot.ARMOR,
        GearSlot.NECKLACE,
        GearSlot.RING,
        GearSlot.BOOTS,
    ).mapIndexed { index, slot ->
        E7Gear(
            id = index + 1L,
            code = "gear-$index",
            slot = slot,
            setCode = if (index < 4) "set_speed" else "set_cri",
            setName = "套装",
            rank = "传说",
            level = 90,
            enhance = 15,
            mainStat = E7GearStat("Health", 100.0),
            substats = emptyList(),
            locked = false,
        )
    }
}
