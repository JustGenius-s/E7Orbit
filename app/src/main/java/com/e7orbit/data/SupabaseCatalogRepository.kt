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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private const val SUPABASE_CACHE_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1_000L
private const val SUPABASE_CACHE_VERSION = 4 // 4: add skill buffs/debuffs
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
            val payload = SupabaseCatalogPayload(
                cacheVersion = SUPABASE_CACHE_VERSION,
                heroes = loadAllRows("hero_catalog", listOf("code")),
                skills = loadAllRows("hero_skills", listOf("hero_code", "slot")),
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
    val artifacts: List<SupabaseArtifactRow> = emptyList(),
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
    val buffs: List<JsonElement> = emptyList(),
    val debuffs: List<JsonElement> = emptyList(),
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

internal fun SupabaseSkillRow.toDomain(): E7HeroSkill = E7HeroSkill(
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
    buffs = buffs.mapNotNull(::parseStatusEffect),
    debuffs = debuffs.mapNotNull(::parseStatusEffect),
)

private fun parseStatusEffect(element: JsonElement): E7StatusEffect? {
    val obj = element as? JsonObject ?: return null
    val slug = obj["slug"]?.jsonPrimitive?.contentOrNull ?: return null
    val label = obj["label"]?.jsonPrimitive?.contentOrNull ?: slug
    return E7StatusEffect(
        slug = slug,
        label = label,
        description = obj["description"]?.jsonPrimitive?.contentOrNull,
        iconUrl = obj["icon_url"]?.jsonPrimitive?.contentOrNull
            ?: obj["iconUrl"]?.jsonPrimitive?.contentOrNull,
    )
}
