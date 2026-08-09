package com.e7orbit.optimizer

import android.content.Context
import com.e7orbit.data.E7Gear
import com.e7orbit.data.E7Hero
import com.e7orbit.data.E7ScannedHero
import com.e7orbit.data.GearSlot
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class OptimizerContent {
    HEROES,
    EQUIPMENT,
}

data class HeroOptimizerPreference(
    val metric: OptimizerMetric = OptimizerMetric.COMBAT_POWER,
    val minimums: Map<OptimizerStat, Int> = emptyMap(),
    val requiredSets: Set<String> = emptySet(),
) {
    val isConfigured: Boolean
        get() = metric != OptimizerMetric.COMBAT_POWER ||
            minimums.any { it.value > 0 } ||
            requiredSets.isNotEmpty()
}

data class EquippedSetSummary(
    val code: String,
    val name: String,
    val pieceCount: Int,
    val requiredPieces: Int,
    val completedCount: Int,
)

data class EquippedHeroBuild(
    val instanceId: Long,
    val scannedHero: E7ScannedHero?,
    val hero: E7Hero?,
    val items: List<E7Gear>,
    val sets: List<EquippedSetSummary>,
    val stats: OptimizedHeroStats?,
) {
    val displayName: String
        get() = scannedHero?.name ?: hero?.name ?: "英雄 #$instanceId"

    val isComplete: Boolean
        get() = items.map(E7Gear::slot).toSet().containsAll(EQUIPMENT_SLOTS)
}

fun buildEquippedHeroes(
    scannedHeroes: List<E7ScannedHero>,
    catalog: List<E7Hero>,
    gears: List<E7Gear>,
    calculator: GearOptimizer = GearOptimizer(),
): List<EquippedHeroBuild> {
    val scannedById = scannedHeroes.associateBy(E7ScannedHero::id)
    val catalogByName = catalog.associateBy { normalizeHeroName(it.name) }
    return gears.asSequence()
        .filter { it.equippedHeroId != null }
        .groupBy { requireNotNull(it.equippedHeroId) }
        .map { (instanceId, equipped) ->
            val scanned = scannedById[instanceId]
            val hero = scanned?.name?.let { catalogByName[normalizeHeroName(it)] }
            val items = equipped
                .filter { it.slot in EQUIPMENT_SLOTS }
                .distinctBy(E7Gear::slot)
                .sortedBy { EQUIPMENT_SLOTS.indexOf(it.slot) }
            val stats = hero
                ?.takeIf { items.size == EQUIPMENT_SLOTS.size }
                ?.let { runCatching { calculator.calculateStats(it, items) }.getOrNull() }
            EquippedHeroBuild(
                instanceId = instanceId,
                scannedHero = scanned,
                hero = hero,
                items = items,
                sets = summarizeEquippedSets(items),
                stats = stats,
            )
        }
        .sortedWith(
            compareByDescending<EquippedHeroBuild>(EquippedHeroBuild::isComplete)
                .thenBy { it.displayName.lowercase() },
        )
}

fun matchScannedHero(scanned: E7ScannedHero?, catalog: List<E7Hero>): E7Hero? {
    if (scanned == null) return null
    val normalized = normalizeHeroName(scanned.name)
    return catalog.firstOrNull { normalizeHeroName(it.name) == normalized }
}

private fun summarizeEquippedSets(items: List<E7Gear>): List<EquippedSetSummary> =
    items.groupBy(E7Gear::setCode)
        .map { (code, setItems) ->
            val required = GearOptimizer.setPieces(code)
            EquippedSetSummary(
                code = code,
                name = setItems.first().setName.removeSuffix("套装"),
                pieceCount = setItems.size,
                requiredPieces = required,
                completedCount = if (required > 0) setItems.size / required else 0,
            )
        }
        .sortedWith(
            compareByDescending<EquippedSetSummary> { it.completedCount }
                .thenByDescending { it.pieceCount }
                .thenBy { it.name },
        )

private fun normalizeHeroName(value: String): String =
    value.lowercase().filter(Char::isLetterOrDigit)

private val EQUIPMENT_SLOTS = listOf(
    GearSlot.WEAPON,
    GearSlot.HELMET,
    GearSlot.ARMOR,
    GearSlot.NECKLACE,
    GearSlot.RING,
    GearSlot.BOOTS,
)

class OptimizerPreferenceStore(context: Context) {
    private val file = context.applicationContext.filesDir.resolve("optimizer/hero-preferences.json")
    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(): Map<Long, HeroOptimizerPreference> = runCatching {
        if (!file.exists()) return@runCatching emptyMap()
        json.decodeFromString<Map<Long, SavedPreference>>(file.readText(Charsets.UTF_8))
            .mapValues { (_, saved) -> saved.toDomain() }
    }.getOrDefault(emptyMap())

    suspend fun save(preferences: Map<Long, HeroOptimizerPreference>) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val saved = preferences.mapValues { (_, preference) -> preference.toSaved() }
                writeAtomically(file, json.encodeToString(saved))
            }
        }
    }

    private fun writeAtomically(target: File, content: String) {
        target.parentFile?.mkdirs()
        val temporary = target.resolveSibling("${target.name}.tmp")
        temporary.writeText(content, Charsets.UTF_8)
        if (target.exists()) check(target.delete()) { "无法更新英雄配装偏好" }
        check(temporary.renameTo(target)) { "无法保存英雄配装偏好" }
    }

    @Serializable
    private data class SavedPreference(
        val metric: String = OptimizerMetric.COMBAT_POWER.name,
        val minimums: Map<String, Int> = emptyMap(),
        val requiredSets: Set<String> = emptySet(),
    ) {
        fun toDomain(): HeroOptimizerPreference = HeroOptimizerPreference(
            metric = runCatching { OptimizerMetric.valueOf(metric) }
                .getOrDefault(OptimizerMetric.COMBAT_POWER),
            minimums = minimums.mapNotNull { (name, value) ->
                runCatching { OptimizerStat.valueOf(name) }.getOrNull()
                    ?.let { it to value.coerceAtLeast(0) }
            }.toMap(),
            requiredSets = requiredSets,
        )
    }

    private fun HeroOptimizerPreference.toSaved(): SavedPreference = SavedPreference(
        metric = metric.name,
        minimums = minimums.mapKeys { it.key.name },
        requiredSets = requiredSets,
    )
}
