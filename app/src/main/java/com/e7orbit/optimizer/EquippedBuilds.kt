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
    POWER,
}

enum class HeroBuildSortField(val label: String) {
    ROLE("职业"),
    RARITY("星级"),
    COMBAT_POWER("战力"),
    SPEED("速度"),
}

data class HeroBuildSort(
    val field: HeroBuildSortField = HeroBuildSortField.COMBAT_POWER,
    val direction: GearSortDirection = GearSortDirection.DESCENDING,
)

enum class GearSortField(val label: String) {
    SCORE("装备分数"),
    SET("套装"),
    MAIN_STAT("主属性数值"),
    SUBSTAT("副属性数值"),
    ENHANCE("强化等级"),
}

enum class GearSortDirection(val label: String) {
    DESCENDING("降序"),
    ASCENDING("升序"),
}

data class GearInventorySort(
    val field: GearSortField = GearSortField.SCORE,
    val direction: GearSortDirection = GearSortDirection.DESCENDING,
    val statType: String? = null,
)

data class GearInventoryFilter(
    val setCodes: Set<String> = emptySet(),
    val mainStatTypes: Set<String> = emptySet(),
    val substatTypes: Set<String> = emptySet(),
    val minimumScore: Int = 0,
) {
    val hasFilters: Boolean
        get() = setCodes.isNotEmpty() ||
            mainStatTypes.isNotEmpty() ||
            substatTypes.isNotEmpty() ||
            minimumScore > 0
}

fun filterAndSortGears(
    gears: List<E7Gear>,
    filter: GearInventoryFilter,
    sort: GearInventorySort,
): List<E7Gear> = gears
    .filter { gear -> gear.matches(filter) }
    .sortedWith(gearComparator(sort))

private fun E7Gear.matches(filter: GearInventoryFilter): Boolean =
    (filter.setCodes.isEmpty() || setCode in filter.setCodes) &&
        (filter.mainStatTypes.isEmpty() || mainStat.type in filter.mainStatTypes) &&
        filter.substatTypes.all { type -> substats.any { it.type == type } } &&
        GearOptimizer.gearScore(this) >= filter.minimumScore

private fun gearComparator(sort: GearInventorySort): Comparator<E7Gear> {
    val primary = Comparator<E7Gear> { first, second ->
        val firstValue = first.sortValue(sort)
        val secondValue = second.sortValue(sort)
        when {
            firstValue == null && secondValue == null -> 0
            firstValue == null -> 1
            secondValue == null -> -1
            else -> {
                val compared = compareSortValues(firstValue, secondValue)
                if (sort.direction == GearSortDirection.DESCENDING) -compared else compared
            }
        }
    }
    return primary
        .thenByDescending { gear -> GearOptimizer.gearScore(gear) }
        .thenBy { gear -> gear.slot.ordinal }
}

private fun E7Gear.sortValue(sort: GearInventorySort): Comparable<*>? = when (sort.field) {
    GearSortField.SCORE -> GearOptimizer.gearScore(this)
    GearSortField.SET -> setName
    GearSortField.MAIN_STAT -> mainStat.value.takeIf {
        sort.statType == null || mainStat.type == sort.statType
    }
    GearSortField.SUBSTAT -> substats.firstOrNull { it.type == sort.statType }?.value
    GearSortField.ENHANCE -> enhance
}

@Suppress("UNCHECKED_CAST")
private fun compareSortValues(first: Comparable<*>, second: Comparable<*>): Int =
    (first as Comparable<Any>).compareTo(second as Any)


fun sortEquippedHeroes(
    builds: List<EquippedHeroBuild>,
    sort: HeroBuildSort,
): List<EquippedHeroBuild> = builds.sortedWith(heroBuildComparator(sort))

private fun heroBuildComparator(sort: HeroBuildSort): Comparator<EquippedHeroBuild> {
    val primary = Comparator<EquippedHeroBuild> { first, second ->
        val firstValue = first.sortValue(sort.field)
        val secondValue = second.sortValue(sort.field)
        when {
            firstValue == null && secondValue == null -> 0
            firstValue == null -> 1
            secondValue == null -> -1
            else -> {
                val compared = compareSortValues(firstValue, secondValue)
                if (sort.direction == GearSortDirection.DESCENDING) -compared else compared
            }
        }
    }
    return primary.thenBy { it.displayName.lowercase() }
}

private fun EquippedHeroBuild.sortValue(field: HeroBuildSortField): Comparable<*>? = when (field) {
    HeroBuildSortField.ROLE -> hero?.role
    HeroBuildSortField.RARITY -> scannedHero?.stars ?: hero?.rarity
    HeroBuildSortField.COMBAT_POWER -> stats?.combatPower
    HeroBuildSortField.SPEED -> stats?.speed
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
    includeEmptyScannedHeroes: Boolean = false,
): List<EquippedHeroBuild> {
    val scannedById = scannedHeroes.associateBy(E7ScannedHero::id)
    val catalogByName = catalog.associateBy { normalizeHeroName(it.name) }
    val equippedByHero = gears.asSequence()
        .filter { it.equippedHeroId != null }
        .groupBy { requireNotNull(it.equippedHeroId) }
    val instanceIds = LinkedHashSet<Long>().apply {
        addAll(equippedByHero.keys)
        if (includeEmptyScannedHeroes) addAll(scannedHeroes.map(E7ScannedHero::id))
    }
    return instanceIds
        .map { instanceId ->
            val scanned = scannedById[instanceId]
            val hero = scanned?.name?.let { catalogByName[normalizeHeroName(it)] }
            val items = equippedByHero[instanceId].orEmpty()
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

private fun writeAtomically(target: File, content: String, errorMessage: String) {
    target.parentFile?.mkdirs()
    val temporary = target.resolveSibling("${target.name}.tmp")
    temporary.writeText(content, Charsets.UTF_8)
    if (target.exists()) check(target.delete()) { errorMessage }
    check(temporary.renameTo(target)) { errorMessage }
}

class OptimizerUiPreferenceStore(context: Context) {
    private val file = context.applicationContext.filesDir.resolve("optimizer/ui-preferences.json")
    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun loadHeroSort(): HeroBuildSort = runCatching {
        if (!file.exists()) return@runCatching HeroBuildSort()
        json.decodeFromString<SavedUiPreference>(file.readText(Charsets.UTF_8)).toDomain()
    }.getOrDefault(HeroBuildSort())

    suspend fun saveHeroSort(sort: HeroBuildSort) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                writeAtomically(
                    file,
                    json.encodeToString(SavedUiPreference.from(sort)),
                    "无法保存配装排序偏好",
                )
            }
        }
    }

    @Serializable
    private data class SavedUiPreference(
        val heroSortField: String = HeroBuildSortField.COMBAT_POWER.name,
        val heroSortDirection: String = GearSortDirection.DESCENDING.name,
    ) {
        fun toDomain(): HeroBuildSort = HeroBuildSort(
            field = runCatching { HeroBuildSortField.valueOf(heroSortField) }
                .getOrDefault(HeroBuildSortField.COMBAT_POWER),
            direction = runCatching { GearSortDirection.valueOf(heroSortDirection) }
                .getOrDefault(GearSortDirection.DESCENDING),
        )

        companion object {
            fun from(sort: HeroBuildSort): SavedUiPreference = SavedUiPreference(
                heroSortField = sort.field.name,
                heroSortDirection = sort.direction.name,
            )
        }
    }
}

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
        writeAtomically(target, content, "无法保存英雄配装偏好")
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
