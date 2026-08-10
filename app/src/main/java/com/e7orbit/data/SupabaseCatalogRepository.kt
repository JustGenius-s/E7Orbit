package com.e7orbit.data

import android.content.Context
import com.e7orbit.BuildConfig
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private const val SUPABASE_CACHE_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1_000L
private const val SUPABASE_CACHE_VERSION = 7 // 7: refresh mirrored full-body hero artwork
private const val SUPABASE_PAGE_SIZE = 500

/**
 * Reads the user-maintained public catalog. Writes and bulk imports belong in the sync tool,
 * where a service-role key can stay outside the Android application.
 */
class SupabaseCatalogRepository(
    context: Context,
) {
    private val cacheFile = context.applicationContext.cacheDir
        .resolve("e7-data")
        .resolve("supabase-catalog.json")
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }
    private val client = BuildConfig.SUPABASE_URL.trim().takeIf(String::isNotBlank)
        ?.let { url ->
            BuildConfig.SUPABASE_ANON_KEY.trim().takeIf(String::isNotBlank)?.let { key ->
                createSupabaseClient(
                    supabaseUrl = url,
                    supabaseKey = key,
                ) {
                    install(Postgrest)
                }
            }
        }

    internal suspend fun load(forceRefresh: Boolean = false): SupabaseCatalog? = withContext(Dispatchers.IO) {
        if (client == null) return@withContext readCache()
        cacheFile.parentFile?.mkdirs()
        val cacheIsFresh = cacheFile.exists() &&
            System.currentTimeMillis() - cacheFile.lastModified() < SUPABASE_CACHE_MAX_AGE_MS
        // readCache() enforces the cache version; a version bump forces a refetch.
        if (!forceRefresh && cacheIsFresh) {
            readCache()?.let { return@withContext it }
        }

        try {
            val effects = runCatching {
                loadAllRows<SupabaseStatusEffectRow>("status_effect_catalog", listOf("slug"))
            }.getOrDefault(emptyList())
            val payload = SupabaseCatalogPayload(
                cacheVersion = SUPABASE_CACHE_VERSION,
                heroes = loadAllRows("hero_catalog", listOf("code")),
                skills = loadAllRows("hero_skills", listOf("hero_code", "slot")),
                effects = effects,
                artifacts = loadAllRows("artifact_catalog", listOf("code")),
            )
            cacheFile.writeText(json.encodeToString(payload))
            payload
        } catch (error: Exception) {
            readCache() ?: throw error
        }
    }

    private suspend inline fun <reified Row> loadAllRows(
        table: String,
        orderColumns: List<String>,
    ): List<Row> {
        val configuredClient = checkNotNull(client)
        val rows = mutableListOf<Row>()
        var start = 0L
        do {
            val page: List<Row> = json.decodeFromString(
                configuredClient.from(table).select {
                    orderColumns.forEach { column -> order(column, Order.ASCENDING) }
                    range(start, start + SUPABASE_PAGE_SIZE - 1L)
                }.data,
            )
            rows += page
            start += page.size
        } while (page.size == SUPABASE_PAGE_SIZE)
        return rows
    }

    private fun readCache(): SupabaseCatalog? = runCatching {
        if (!cacheFile.exists()) return null
        json.decodeFromString<SupabaseCatalogPayload>(cacheFile.readText())
            .takeIf { it.cacheVersion == SUPABASE_CACHE_VERSION }
    }.getOrNull()
}

internal typealias SupabaseCatalog = SupabaseCatalogPayload

@Serializable
internal data class SupabaseCatalogPayload(
    val cacheVersion: Int = 0,
    val heroes: List<SupabaseHeroRow> = emptyList(),
    val skills: List<SupabaseSkillRow> = emptyList(),
    val effects: List<SupabaseStatusEffectRow> = emptyList(),
    val artifacts: List<SupabaseArtifactRow> = emptyList(),
)

@Serializable
internal data class SupabaseStatusEffectRow(
    val slug: String = "",
    val label: String = "",
    val description: String? = null,
    @SerialName("icon_url") val iconUrl: String? = null,
)

@Serializable
internal data class SupabaseResourceCostRow(
    val code: String = "",
    val label: String = "",
    val quantity: Int = 0,
)

@Serializable
internal data class SupabaseGrowthStatRow(
    val label: String = "",
    val value: String = "",
)

@Serializable
internal data class SupabaseAwakeningRow(
    val rank: Int = 0,
    val stats: List<SupabaseGrowthStatRow> = emptyList(),
    val resources: List<SupabaseResourceCostRow> = emptyList(),
    @SerialName("skill_before") val skillBefore: String? = null,
    @SerialName("skill_after") val skillAfter: String? = null,
)

@Serializable
internal data class SupabaseImprintGradeRow(
    val rank: String = "",
    val value: String = "",
)

@Serializable
internal data class SupabaseImprintSectionRow(
    val position: String? = null,
    val grades: List<SupabaseImprintGradeRow> = emptyList(),
)

@Serializable
internal data class SupabaseMemoryImprintRow(
    val release: SupabaseImprintSectionRow? = null,
    val concentration: SupabaseImprintSectionRow? = null,
)

@Serializable
internal data class SupabaseArtifactRow(
    val code: String = "",
    val name: String = "",
    val rarity: Int? = null,
    val role: String = "",
    val description: String? = null,
    @SerialName("max_description") val maxDescription: String? = null,
    val lore: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("icon_url") val iconUrl: String? = null,
    @SerialName("stats_attack") val attack: Int? = null,
    @SerialName("stats_health") val health: Int? = null,
    @SerialName("stats_defense") val defense: Int? = null,
    @SerialName("base_attack") val baseAttack: Int? = null,
    @SerialName("base_health") val baseHealth: Int? = null,
)

@Serializable
internal data class SupabaseHeroRow(
    val code: String = "",
    val name: String = "",
    val rarity: Int? = null,
    val attribute: String = "",
    val role: String = "",
    val zodiac: String? = null,
    val description: String? = null,
    val awakenings: List<SupabaseAwakeningRow> = emptyList(),
    @SerialName("memory_imprint") val memoryImprint: SupabaseMemoryImprintRow? = null,
    @SerialName("icon_url") val iconUrl: String? = null,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("stats_attack") val attack: Int? = null,
    @SerialName("stats_health") val health: Int? = null,
    @SerialName("stats_defense") val defense: Int? = null,
    @SerialName("stats_speed") val speed: Int? = null,
    @SerialName("stats_critical_chance") val criticalChance: Int? = null,
    @SerialName("stats_critical_damage") val criticalDamage: Int? = null,
    @SerialName("stats_effectiveness") val effectiveness: Int? = null,
    @SerialName("stats_effect_resistance") val effectResistance: Int? = null,
    @SerialName("stats_combat_power") val combatPower: Int? = null,
)

@Serializable
internal data class SupabaseSkillRow(
    @SerialName("hero_code") val heroCode: String = "",
    val slot: Int = 0,
    val name: String = "",
    @SerialName("icon_url") val iconUrl: String? = null,
    val description: String? = null,
    @SerialName("enhanced_description") val enhancedDescription: String? = null,
    val cooldown: Int? = null,
    @SerialName("soul_gain") val soulGain: Int? = null,
    @SerialName("soul_requirement") val soulRequirement: Int? = null,
    @SerialName("soul_description") val soulDescription: String? = null,
    @SerialName("attack_rate") val attackRate: Double? = null,
    val pow: Double? = null,
    @SerialName("is_passive") val isPassive: Boolean = false,
    @SerialName("can_enhance") val canEnhance: Boolean = false,
    val values: List<JsonElement> = emptyList(),
    val enhancements: List<String> = emptyList(),
    @SerialName("buff_slugs") val buffSlugs: List<String> = emptyList(),
    @SerialName("debuff_slugs") val debuffSlugs: List<String> = emptyList(),
)

internal fun SupabaseHeroRow.toStats(fallback: E7HeroStats? = null): E7HeroStats? {
    val hasStats = listOf(
        attack,
        health,
        defense,
        speed,
        criticalChance,
        criticalDamage,
        effectiveness,
        effectResistance,
        combatPower,
    ).any { it != null }
    if (!hasStats && fallback == null) return null
    return E7HeroStats(
        attack = attack ?: fallback?.attack,
        health = health ?: fallback?.health,
        defense = defense ?: fallback?.defense,
        speed = speed ?: fallback?.speed,
        criticalChance = criticalChance ?: fallback?.criticalChance,
        criticalDamage = criticalDamage ?: fallback?.criticalDamage,
        effectiveness = effectiveness ?: fallback?.effectiveness,
        effectResistance = effectResistance ?: fallback?.effectResistance,
        combatPower = combatPower ?: fallback?.combatPower,
    )
}

internal fun SupabaseSkillRow.toDomain(
    effectsBySlug: Map<String, SupabaseStatusEffectRow> = emptyMap(),
): E7HeroSkill {
    val normalizedBuffs = buffSlugs.map { slug ->
        effectsBySlug[slug]?.toDomain() ?: slug.toFallbackEffect()
    }
    val normalizedDebuffs = debuffSlugs.map { slug ->
        effectsBySlug[slug]?.toDomain() ?: slug.toFallbackEffect()
    }
    return E7HeroSkill(
        slot = slot,
        name = name,
        iconUrl = iconUrl,
        description = description,
        enhancedDescription = enhancedDescription,
        cooldown = cooldown,
        soulGain = soulGain,
        soulRequirement = soulRequirement,
        soulDescription = soulDescription,
        attackRate = attackRate,
        pow = pow,
        isPassive = isPassive,
        canEnhance = canEnhance,
        values = values,
        enhancements = enhancements,
        buffs = normalizedBuffs,
        debuffs = normalizedDebuffs,
    )
}

internal fun SupabaseAwakeningRow.toDomain(): E7HeroAwakening = E7HeroAwakening(
    rank = rank,
    stats = stats.map { E7GrowthStat(label = it.label, value = it.value) },
    resources = resources.map(SupabaseResourceCostRow::toDomain),
    skillBefore = skillBefore,
    skillAfter = skillAfter,
)

internal fun SupabaseMemoryImprintRow.toDomain(): E7MemoryImprint = E7MemoryImprint(
    release = release?.toDomain(),
    concentration = concentration?.toDomain(),
)

private fun SupabaseImprintSectionRow.toDomain(): E7ImprintSection = E7ImprintSection(
    position = position,
    grades = grades.map { E7ImprintGrade(rank = it.rank, value = it.value) },
)

private fun SupabaseResourceCostRow.toDomain(): E7ResourceCost = E7ResourceCost(
    code = code,
    label = label,
    quantity = quantity,
)

private fun SupabaseStatusEffectRow.toDomain(): E7StatusEffect = E7StatusEffect(
    slug = slug,
    label = label.ifBlank { slug },
    description = description,
    iconUrl = iconUrl,
)

private fun String.toFallbackEffect(): E7StatusEffect = E7StatusEffect(
    slug = this,
    label = this,
)
