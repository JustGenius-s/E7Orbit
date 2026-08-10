package com.e7orbit.optimizer

import android.content.Context
import com.e7orbit.data.E7Gear
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class EquipmentPlan(
    val id: String,
    val name: String,
    val assignments: Map<Long, Long>,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long = createdAtEpochMs,
) {
    val equippedGearCount: Int
        get() = assignments.size

    val equippedHeroCount: Int
        get() = assignments.values.toSet().size
}

@Serializable
data class EquipmentPlanCollection(
    val plans: List<EquipmentPlan> = emptyList(),
    val selectedPlanId: String? = null,
) {
    fun normalized(): EquipmentPlanCollection {
        val selected = selectedPlanId?.takeIf { id -> plans.any { it.id == id } }
            ?: plans.firstOrNull()?.id
        return copy(selectedPlanId = selected)
    }
}

fun createEquipmentPlan(
    name: String,
    gears: List<E7Gear>,
    nowEpochMs: Long = System.currentTimeMillis(),
    id: String = UUID.randomUUID().toString(),
): EquipmentPlan = EquipmentPlan(
    id = id,
    name = name,
    assignments = gears.mapNotNull { gear ->
        gear.equippedHeroId?.let { heroId -> gear.id to heroId }
    }.toMap(),
    createdAtEpochMs = nowEpochMs,
)

fun EquipmentPlan.copyAs(
    name: String,
    nowEpochMs: Long = System.currentTimeMillis(),
    id: String = UUID.randomUUID().toString(),
): EquipmentPlan = copy(
    id = id,
    name = name,
    createdAtEpochMs = nowEpochMs,
    updatedAtEpochMs = nowEpochMs,
)

fun EquipmentPlan.applyBuild(
    heroId: Long,
    gearIds: Collection<Long>,
    validGearIds: Set<Long>,
    nowEpochMs: Long = System.currentTimeMillis(),
): EquipmentPlan {
    val selected = gearIds.filterTo(linkedSetOf()) { it in validGearIds }
    require(selected.size == OPTIMIZER_EQUIPMENT_SLOT_COUNT) {
        "配装方案必须包含六件有效装备"
    }
    val nextAssignments = assignments
        .filter { (gearId, assignedHeroId) -> assignedHeroId != heroId && gearId !in selected }
        .toMutableMap()
        .apply { selected.forEach { gearId -> put(gearId, heroId) } }
    return copy(
        assignments = nextAssignments,
        updatedAtEpochMs = nowEpochMs,
    )
}

fun EquipmentPlan.applyTo(gears: List<E7Gear>): List<E7Gear> = gears.map { gear ->
    gear.copy(equippedHeroId = assignments[gear.id])
}

fun EquipmentPlan.containsBuild(heroId: Long, gearIds: Collection<Long>): Boolean {
    val expected = gearIds.toSet()
    if (expected.size != OPTIMIZER_EQUIPMENT_SLOT_COUNT) return false
    return assignments.filterValues { it == heroId }.keys == expected
}

class EquipmentPlanStore(context: Context) {
    private val file = context.applicationContext.filesDir.resolve("optimizer/equipment-plans.json")
    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(): EquipmentPlanCollection = runCatching {
        if (!file.exists()) return@runCatching EquipmentPlanCollection()
        json.decodeFromString<EquipmentPlanCollection>(file.readText(Charsets.UTF_8)).normalized()
    }.getOrDefault(EquipmentPlanCollection())

    suspend fun save(collection: EquipmentPlanCollection) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                writeAtomically(
                    target = file,
                    content = json.encodeToString(collection.normalized()),
                )
            }
        }
    }

    private fun writeAtomically(target: File, content: String) {
        target.parentFile?.mkdirs()
        val temporary = target.resolveSibling("${target.name}.tmp")
        temporary.writeText(content, Charsets.UTF_8)
        if (target.exists()) check(target.delete()) { "无法更新配装方案" }
        check(temporary.renameTo(target)) { "无法保存配装方案" }
    }
}

private const val OPTIMIZER_EQUIPMENT_SLOT_COUNT = 6
