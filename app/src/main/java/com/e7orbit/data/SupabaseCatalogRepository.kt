package com.e7orbit.data

import android.content.Context
import android.content.Intent
import com.e7orbit.BuildConfig
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.parseSessionFromFragment
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

private const val SUPABASE_CACHE_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1_000L
private const val SUPABASE_CACHE_VERSION = 10 // 10: map exclusive equipment to hero skill slots
private const val SUPABASE_PAGE_SIZE = 500
private const val WIKI_AUTH_SCHEME = "e7orbit"
private const val WIKI_AUTH_HOST = "auth"
private const val WIKI_AUTH_REDIRECT_URL = "$WIKI_AUTH_SCHEME://$WIKI_AUTH_HOST"

/**
 * Reads the public catalog and saves individual Wiki edits through authenticated RLS policies.
 * Bulk imports remain in the sync tool, where a service-role key stays outside the APK.
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
                    install(Auth) {
                        scheme = WIKI_AUTH_SCHEME
                        host = WIKI_AUTH_HOST
                        flowType = FlowType.PKCE
                    }
                    install(Postgrest)
                    install(Storage)
                }
            }
        }

    internal suspend fun load(
        forceRefresh: Boolean = false,
        allowStaleFallback: Boolean = true,
    ): SupabaseCatalog? = withContext(Dispatchers.IO) {
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
                exclusiveEquipment = loadAllRows(
                    "hero_exclusive_equipment",
                    listOf("hero_code"),
                ),
                artifacts = loadAllRows("artifact_catalog", listOf("code")),
            )
            cacheFile.writeText(json.encodeToString(payload))
            payload
        } catch (error: Exception) {
            if (allowStaleFallback) readCache() ?: throw error else throw error
        }
    }

    internal val wikiEditingConfigured: Boolean
        get() = client != null

    internal suspend fun restoreWikiEditor(): WikiEditorIdentity? {
        val configuredClient = client ?: return null
        configuredClient.auth.awaitInitialization()
        return currentWikiIdentity(configuredClient)
    }

    internal suspend fun signInWikiEditor(email: String, password: String): WikiEditorIdentity {
        val configuredClient = requireNotNull(client) { "Supabase 尚未配置" }
        configuredClient.auth.awaitInitialization()
        configuredClient.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
        return requireNotNull(currentWikiIdentity(configuredClient)) { "登录会话不可用" }
    }

    internal suspend fun registerWikiAccount(email: String, password: String): WikiRegistrationResult {
        val configuredClient = requireNotNull(client) { "Supabase 尚未配置" }
        configuredClient.auth.awaitInitialization()
        configuredClient.auth.signUpWith(
            provider = Email,
            redirectUrl = "$WIKI_AUTH_REDIRECT_URL?flow=signup",
        ) {
            this.email = email.trim()
            this.password = password
        }
        val identity = currentWikiIdentity(configuredClient)
        return WikiRegistrationResult(
            email = email.trim(),
            identity = identity,
            requiresEmailConfirmation = identity == null,
        )
    }

    internal suspend fun resendWikiConfirmation(email: String) {
        val configuredClient = requireNotNull(client) { "Supabase 尚未配置" }
        configuredClient.auth.awaitInitialization()
        configuredClient.auth.resendEmail(
            type = OtpType.Email.SIGNUP,
            email = email.trim(),
            redirectUrl = "$WIKI_AUTH_REDIRECT_URL?flow=signup",
        )
    }

    internal suspend fun sendWikiPasswordReset(email: String) {
        val configuredClient = requireNotNull(client) { "Supabase 尚未配置" }
        configuredClient.auth.awaitInitialization()
        configuredClient.auth.resetPasswordForEmail(
            email = email.trim(),
            redirectUrl = "$WIKI_AUTH_REDIRECT_URL?flow=recovery",
        )
    }

    internal suspend fun updateWikiPassword(password: String): WikiEditorIdentity {
        val configuredClient = requireNotNull(client) { "Supabase 尚未配置" }
        configuredClient.auth.awaitInitialization()
        checkNotNull(configuredClient.auth.currentUserOrNull()) {
            "密码恢复会话不可用，请重新打开重置邮件"
        }
        configuredClient.auth.updateUser { this.password = password }
        return requireNotNull(currentWikiIdentity(configuredClient)) { "登录会话不可用" }
    }

    internal suspend fun consumeWikiAuthDeepLink(intent: Intent): WikiAuthLinkType? {
        val configuredClient = client ?: return null
        val uri = intent.data ?: return null
        if (uri.scheme != WIKI_AUTH_SCHEME || uri.host != WIKI_AUTH_HOST) return null
        val errorDescription = uri.getQueryParameter("error_description")
            ?: uri.getQueryParameter("error")
        if (!errorDescription.isNullOrBlank()) error(errorDescription)

        configuredClient.auth.awaitInitialization()
        uri.getQueryParameter("code")?.takeIf(String::isNotBlank)?.let { code ->
            configuredClient.auth.exchangeCodeForSession(code)
        } ?: uri.fragment?.takeIf(String::isNotBlank)?.let { fragment ->
            configuredClient.auth.importSession(configuredClient.auth.parseSessionFromFragment(fragment))
        } ?: error("邮件链接无效或已过期")

        return when (uri.getQueryParameter("flow") ?: uri.getQueryParameter("type")) {
            "signup" -> WikiAuthLinkType.SIGNUP_CONFIRMATION
            "recovery" -> WikiAuthLinkType.PASSWORD_RECOVERY
            else -> WikiAuthLinkType.UNKNOWN
        }
    }

    internal suspend fun signOutWikiEditor() {
        client?.auth?.signOut()
    }

    private suspend fun currentWikiIdentity(
        configuredClient: io.github.jan.supabase.SupabaseClient,
    ): WikiEditorIdentity? {
        val user = configuredClient.auth.currentUserOrNull() ?: return null
        return WikiEditorIdentity(
            email = user.email.orEmpty(),
            // Account authentication remains usable before the Wiki migration is installed.
            canEdit = runCatching {
                configuredClient.postgrest.rpc("is_wiki_editor").decodeAs<Boolean>()
            }.getOrDefault(false),
        )
    }

    internal suspend fun saveWikiHero(hero: E7Hero): SupabaseCatalog {
        val configuredClient = requireNotNull(client) { "Supabase 尚未配置" }
        configuredClient.auth.awaitInitialization()
        checkNotNull(configuredClient.auth.currentUserOrNull()) { "请先登录 Wiki 管理员账号" }
        val parameters = buildJsonObject {
            put("p_hero_code", hero.code)
            put("p_hero", json.encodeToJsonElement(WikiHeroWriteRow.from(hero)))
            put(
                "p_skills",
                json.encodeToJsonElement(
                    hero.skills.sortedBy(E7HeroSkill::slot).map(WikiSkillWriteRow.Companion::from),
                ),
            )
            put(
                "p_exclusive_equipment",
                hero.exclusiveEquipment
                    ?.let(WikiExclusiveEquipmentWriteRow.Companion::from)
                    ?.let { equipment -> json.encodeToJsonElement(equipment) }
                    ?: JsonNull,
            )
        }
        configuredClient.postgrest.rpc(
            function = "save_wiki_hero",
            parameters = parameters,
        )
        return checkNotNull(
            load(
                forceRefresh = true,
                allowStaleFallback = false,
            ),
        ) { "Wiki 保存成功，但无法重新读取云端资料" }
    }

    internal suspend fun uploadWikiImage(storagePath: String, bytes: ByteArray): String {
        val configuredClient = requireNotNull(client) { "Supabase 尚未配置" }
        configuredClient.auth.awaitInitialization()
        checkNotNull(configuredClient.auth.currentUserOrNull()) { "请先登录 Wiki 管理员账号" }
        configuredClient.storage.from("Epic7").upload(storagePath, bytes) { upsert = true }
        val base = configuredClient.storage.from("Epic7").publicUrl(storagePath)
        return "$base?v=${System.currentTimeMillis()}"
    }

    internal suspend fun saveWikiArtifact(artifact: E7Artifact): SupabaseCatalog {
        val configuredClient = requireNotNull(client) { "Supabase 尚未配置" }
        configuredClient.auth.awaitInitialization()
        checkNotNull(configuredClient.auth.currentUserOrNull()) { "请先登录 Wiki 管理员账号" }
        val parameters = buildJsonObject {
            put("p_artifact_code", artifact.code)
            put("p_artifact", json.encodeToJsonElement(WikiArtifactWriteRow.from(artifact)))
        }
        configuredClient.postgrest.rpc(
            function = "save_wiki_artifact",
            parameters = parameters,
        )
        return checkNotNull(
            load(
                forceRefresh = true,
                allowStaleFallback = false,
            ),
        ) { "Wiki 保存成功，但无法重新读取云端资料" }
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

internal data class WikiEditorIdentity(
    val email: String,
    val canEdit: Boolean,
)

internal data class WikiRegistrationResult(
    val email: String,
    val identity: WikiEditorIdentity?,
    val requiresEmailConfirmation: Boolean,
)

internal enum class WikiAuthLinkType {
    SIGNUP_CONFIRMATION,
    PASSWORD_RECOVERY,
    UNKNOWN,
}

@Serializable
private data class WikiHeroWriteRow(
    val name: String,
    val rarity: Int?,
    val attribute: String,
    val role: String,
    val zodiac: String?,
    val description: String?,
    @SerialName("icon_url") val iconUrl: String?,
    @SerialName("thumbnail_url") val thumbnailUrl: String?,
    @SerialName("image_url") val imageUrl: String?,
    @SerialName("stats_attack") val attack: Int?,
    @SerialName("stats_health") val health: Int?,
    @SerialName("stats_defense") val defense: Int?,
    @SerialName("stats_speed") val speed: Int?,
    @SerialName("stats_critical_chance") val criticalChance: Int?,
    @SerialName("stats_critical_damage") val criticalDamage: Int?,
    @SerialName("stats_effectiveness") val effectiveness: Int?,
    @SerialName("stats_effect_resistance") val effectResistance: Int?,
    @SerialName("stats_combat_power") val combatPower: Int?,
) {
    companion object {
        fun from(hero: E7Hero) = WikiHeroWriteRow(
            name = hero.name,
            rarity = hero.rarity,
            attribute = hero.attribute,
            role = hero.role,
            zodiac = hero.zodiac,
            description = hero.description,
            iconUrl = hero.assets.iconUrl,
            thumbnailUrl = hero.assets.thumbnailUrl,
            imageUrl = hero.assets.imageUrl,
            attack = hero.stats?.attack,
            health = hero.stats?.health,
            defense = hero.stats?.defense,
            speed = hero.stats?.speed,
            criticalChance = hero.stats?.criticalChance,
            criticalDamage = hero.stats?.criticalDamage,
            effectiveness = hero.stats?.effectiveness,
            effectResistance = hero.stats?.effectResistance,
            combatPower = hero.stats?.combatPower,
        )
    }
}

@Serializable
private data class WikiSkillWriteRow(
    val slot: Int,
    val name: String,
    @SerialName("icon_url") val iconUrl: String?,
    val description: String?,
    @SerialName("enhanced_description") val enhancedDescription: String?,
    val cooldown: Int?,
    @SerialName("soul_gain") val soulGain: Int?,
    @SerialName("soul_requirement") val soulRequirement: Int?,
    @SerialName("soul_description") val soulDescription: String?,
    @SerialName("attack_rate") val attackRate: Double?,
    val pow: Double?,
    @SerialName("is_passive") val isPassive: Boolean,
    @SerialName("can_enhance") val canEnhance: Boolean,
    val values: List<JsonElement>,
    val enhancements: List<String>,
    @SerialName("buff_slugs") val buffSlugs: List<String>,
    @SerialName("debuff_slugs") val debuffSlugs: List<String>,
) {
    companion object {
        fun from(skill: E7HeroSkill) = WikiSkillWriteRow(
            slot = skill.slot,
            name = skill.name,
            iconUrl = skill.iconUrl,
            description = skill.description,
            enhancedDescription = skill.enhancedDescription,
            cooldown = skill.cooldown,
            soulGain = skill.soulGain,
            soulRequirement = skill.soulRequirement,
            soulDescription = skill.soulDescription,
            attackRate = skill.attackRate,
            pow = skill.pow,
            isPassive = skill.isPassive,
            canEnhance = skill.canEnhance,
            values = skill.values,
            enhancements = skill.enhancements,
            buffSlugs = skill.buffs.map(E7StatusEffect::slug),
            debuffSlugs = skill.debuffs.map(E7StatusEffect::slug),
        )
    }
}

@Serializable
private data class WikiExclusiveEquipmentWriteRow(
    val name: String,
    val description: String?,
    @SerialName("icon_url") val iconUrl: String,
    @SerialName("stat_type") val statType: String,
    @SerialName("stat_min") val statMin: Double,
    @SerialName("stat_max") val statMax: Double,
    @SerialName("stat_percent") val statPercent: Boolean,
    val enhancements: List<SupabaseExclusiveEnhancementRow>,
) {
    companion object {
        fun from(equipment: E7HeroExclusiveEquipment) = WikiExclusiveEquipmentWriteRow(
            name = equipment.name,
            description = equipment.description,
            iconUrl = equipment.iconUrl,
            statType = equipment.statType,
            statMin = equipment.statMin,
            statMax = equipment.statMax,
            statPercent = equipment.statPercent,
            enhancements = equipment.enhancements.map {
                SupabaseExclusiveEnhancementRow(
                    option = it.option,
                    skillSlot = it.skillSlot,
                    description = it.description,
                )
            },
        )
    }
}

@Serializable
private data class WikiArtifactWriteRow(
    val name: String,
    val rarity: Int?,
    val role: String?,
    val description: String?,
    @SerialName("max_description") val maxDescription: String?,
    val lore: String?,
    @SerialName("image_url") val imageUrl: String?,
    @SerialName("icon_url") val iconUrl: String?,
    @SerialName("stats_attack") val attack: Int?,
    @SerialName("stats_health") val health: Int?,
    @SerialName("stats_defense") val defense: Int?,
    @SerialName("base_attack") val baseAttack: Int?,
    @SerialName("base_health") val baseHealth: Int?,
) {
    companion object {
        fun from(artifact: E7Artifact) = WikiArtifactWriteRow(
            name = artifact.name,
            rarity = artifact.rarity,
            role = artifact.role,
            description = artifact.description,
            maxDescription = artifact.maxDescription,
            lore = artifact.lore,
            imageUrl = artifact.imageUrl,
            iconUrl = artifact.iconUrl,
            attack = artifact.attack,
            health = artifact.health,
            defense = artifact.defense,
            baseAttack = artifact.baseAttack,
            baseHealth = artifact.baseHealth,
        )
    }
}

@Serializable
internal data class SupabaseCatalogPayload(
    val cacheVersion: Int = 0,
    val heroes: List<SupabaseHeroRow> = emptyList(),
    val skills: List<SupabaseSkillRow> = emptyList(),
    val effects: List<SupabaseStatusEffectRow> = emptyList(),
    val exclusiveEquipment: List<SupabaseExclusiveEquipmentRow> = emptyList(),
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
    val stat: String? = null,
    val amount: Double? = null,
    val percent: Boolean = false,
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
internal data class SupabaseExclusiveEnhancementRow(
    val option: Int = 0,
    @SerialName("skill_slot") val skillSlot: Int? = null,
    val description: String = "",
)

@Serializable
internal data class SupabaseExclusiveEquipmentRow(
    val code: String = "",
    @SerialName("hero_code") val heroCode: String = "",
    val name: String = "",
    val description: String? = null,
    @SerialName("icon_url") val iconUrl: String = "",
    @SerialName("stat_type") val statType: String = "",
    @SerialName("stat_min") val statMin: Double = 0.0,
    @SerialName("stat_max") val statMax: Double = 0.0,
    @SerialName("stat_percent") val statPercent: Boolean = false,
    val enhancements: List<SupabaseExclusiveEnhancementRow> = emptyList(),
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
    grades = grades.map {
        E7ImprintGrade(
            rank = it.rank,
            value = it.value,
            stat = it.stat,
            amount = it.amount,
            percent = it.percent,
        )
    },
)

private fun SupabaseResourceCostRow.toDomain(): E7ResourceCost = E7ResourceCost(
    code = code,
    label = label,
    quantity = quantity,
)

internal fun SupabaseExclusiveEquipmentRow.toDomain(): E7HeroExclusiveEquipment = E7HeroExclusiveEquipment(
    code = code,
    heroCode = heroCode,
    name = name,
    description = description,
    iconUrl = iconUrl,
    statType = statType,
    statMin = statMin,
    statMax = statMax,
    statPercent = statPercent,
    enhancements = enhancements.map {
        E7ExclusiveEquipmentEnhancement(
            option = it.option,
            skillSlot = it.skillSlot,
            description = it.description,
        )
    },
)

internal fun SupabaseStatusEffectRow.toDomain(): E7StatusEffect = E7StatusEffect(
    slug = slug,
    label = label.ifBlank { slug },
    description = description,
    iconUrl = iconUrl,
)

private fun String.toFallbackEffect(): E7StatusEffect = E7StatusEffect(
    slug = this,
    label = this,
)
