package com.e7orbit.data

import android.content.Context
import android.content.Intent
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1_000L
private const val RTA_ANALYSIS_CACHE_MAX_AGE_MS = 6L * 60L * 60L * 1_000L

private const val RTA_SEASONS_URL =
    "https://e7api.onstove.com/gameApi/getSeasonList?lang=en"
private const val RTA_ANALYSIS_URL =
    "https://e7api.onstove.com/gameApi/getHeroAnalysis"

private const val RTA_SEASONS_CACHE = "rta-seasons.json"

class E7DataRepository(
    context: Context,
) {
    private val cacheDir = context.applicationContext.cacheDir.resolve("e7-data")
    private val supabaseCatalog = SupabaseCatalogRepository(context)

    suspend fun load(forceRefresh: Boolean = false): E7DataSnapshot =
        loadSnapshot(forceRefresh = forceRefresh)

    private suspend fun loadSnapshot(
        forceRefresh: Boolean,
        maintainedCatalogOverride: SupabaseCatalog? = null,
    ): E7DataSnapshot = withContext(Dispatchers.IO) {
        cacheDir.mkdirs()
        val maintainedCatalog = maintainedCatalogOverride
            ?: runCatching { supabaseCatalog.load(forceRefresh) }.getOrNull()
            ?: throw IllegalStateException("英雄 Wiki 数据源暂时不可用")

        val statusEffectsBySlug = maintainedCatalog.effects
            .map(SupabaseStatusEffectRow::toDomain)
            .filter { it.slug.isNotBlank() }
            .associateBy(E7StatusEffect::slug)
        val buffSlugs = maintainedCatalog.skills
            .flatMap(SupabaseSkillRow::buffSlugs)
            .toSet()
        val debuffSlugs = maintainedCatalog.skills
            .flatMap(SupabaseSkillRow::debuffSlugs)
            .toSet()
        fun effectsFor(slugs: Set<String>): List<E7StatusEffect> = slugs
            .map { slug -> statusEffectsBySlug[slug] ?: E7StatusEffect(slug, slug) }
            .sortedWith(compareBy(E7StatusEffect::label, E7StatusEffect::slug))
        E7DataSnapshot(
            heroes = mergeHeroes(maintainedCatalog),
            artifacts = mergeArtifacts(maintainedCatalog),
            buffStatusEffects = effectsFor(buffSlugs),
            debuffStatusEffects = effectsFor(debuffSlugs),
            fetchedAtEpochMs = System.currentTimeMillis(),
        )
    }

    internal val wikiEditingConfigured: Boolean
        get() = supabaseCatalog.wikiEditingConfigured

    internal suspend fun restoreWikiEditor(): WikiEditorIdentity? = withContext(Dispatchers.IO) {
        supabaseCatalog.restoreWikiEditor()
    }

    internal suspend fun signInWikiEditor(email: String, password: String): WikiEditorIdentity =
        withContext(Dispatchers.IO) {
            supabaseCatalog.signInWikiEditor(email, password)
        }

    internal suspend fun registerWikiAccount(
        email: String,
        password: String,
    ): WikiRegistrationResult = withContext(Dispatchers.IO) {
        supabaseCatalog.registerWikiAccount(email, password)
    }

    internal suspend fun resendWikiConfirmation(email: String) = withContext(Dispatchers.IO) {
        supabaseCatalog.resendWikiConfirmation(email)
    }

    internal suspend fun sendWikiPasswordReset(email: String) = withContext(Dispatchers.IO) {
        supabaseCatalog.sendWikiPasswordReset(email)
    }

    internal suspend fun updateWikiPassword(password: String): WikiEditorIdentity =
        withContext(Dispatchers.IO) {
            supabaseCatalog.updateWikiPassword(password)
        }

    internal suspend fun consumeWikiAuthDeepLink(intent: Intent): WikiAuthLinkType? =
        withContext(Dispatchers.IO) {
            supabaseCatalog.consumeWikiAuthDeepLink(intent)
        }

    internal suspend fun signOutWikiEditor() = withContext(Dispatchers.IO) {
        supabaseCatalog.signOutWikiEditor()
    }

    internal suspend fun saveWikiHero(hero: E7Hero): E7DataSnapshot = withContext(Dispatchers.IO) {
        val maintainedCatalog = supabaseCatalog.saveWikiHero(hero)
        loadSnapshot(
            forceRefresh = true,
            maintainedCatalogOverride = maintainedCatalog,
        )
    }

    internal suspend fun saveWikiArtifact(artifact: E7Artifact): E7DataSnapshot =
        withContext(Dispatchers.IO) {
            val maintainedCatalog = supabaseCatalog.saveWikiArtifact(artifact)
            loadSnapshot(
                forceRefresh = true,
                maintainedCatalogOverride = maintainedCatalog,
            )
        }

    internal suspend fun uploadWikiImage(storagePath: String, bytes: ByteArray): String =
        withContext(Dispatchers.IO) {
            supabaseCatalog.uploadWikiImage(storagePath, bytes)
        }

    suspend fun loadRtaSeasons(forceRefresh: Boolean = false): List<RtaSeason> =
        withContext(Dispatchers.IO) {
            cacheDir.mkdirs()
            parseRtaSeasons(
                readPostSource(
                    url = RTA_SEASONS_URL,
                    fileName = RTA_SEASONS_CACHE,
                    maxAgeMs = CACHE_MAX_AGE_MS,
                    forceRefresh = forceRefresh,
                ),
            )
        }

    suspend fun loadHeroRta(
        heroCode: String,
        seasonCode: String,
        tierCode: String,
        forceRefresh: Boolean = false,
    ): HeroRtaAnalysis = withContext(Dispatchers.IO) {
        cacheDir.mkdirs()
        val encodedHero = heroCode.urlEncoded()
        val encodedSeason = seasonCode.urlEncoded()
        val encodedTier = tierCode.urlEncoded()
        val url = "$RTA_ANALYSIS_URL?hero_code=$encodedHero" +
            "&season_code=$encodedSeason&grade_code=$encodedTier&lang=en"
        parseHeroRtaAnalysis(
            readPostSource(
                url = url,
                fileName = "rta-$encodedHero-$encodedSeason-$encodedTier.json",
                maxAgeMs = RTA_ANALYSIS_CACHE_MAX_AGE_MS,
                forceRefresh = forceRefresh,
            ),
        )
    }

    private fun readPostSource(
        url: String,
        fileName: String,
        maxAgeMs: Long,
        forceRefresh: Boolean,
    ): String {
        val cacheFile = cacheDir.resolve(fileName)
        val cacheIsFresh = cacheFile.exists() &&
            System.currentTimeMillis() - cacheFile.lastModified() < maxAgeMs
        if (!forceRefresh && cacheIsFresh) {
            return cacheFile.readText()
        }

        return try {
            val fresh = fetchPost(url)
            validateRtaPayload(fresh)
            cacheFile.writeText(fresh)
            fresh
        } catch (error: Exception) {
            if (cacheFile.exists()) {
                cacheFile.readText()
            } else {
                throw error
            }
        }
    }

    private fun fetchPost(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json;charset=UTF-8")
            setRequestProperty("Caller-Id", "WEB_STOVE_EPIC7")
            setRequestProperty("Caller-Detail", "")
            setRequestProperty("User-Agent", "E7Orbit/0.1")
        }
        return try {
            connection.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                writer.write("{}")
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IllegalStateException("HTTP $status from $url")
            }
            connection.inputStream.bufferedReader().use { reader -> reader.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun mergeHeroes(maintainedCatalog: SupabaseCatalog): List<E7Hero> {
        val effectsBySlug = maintainedCatalog.effects.associateBy(SupabaseStatusEffectRow::slug)
        val maintainedSkills = maintainedCatalog.skills
            .groupBy { it.heroCode }
            .mapValues { (_, skills) ->
                skills.sortedBy(SupabaseSkillRow::slot).map { skill ->
                    skill.toDomain(effectsBySlug)
                }
            }
        val exclusiveEquipmentByHeroCode = maintainedCatalog.exclusiveEquipment
            .associateBy(SupabaseExclusiveEquipmentRow::heroCode)

        return maintainedCatalog.heroes
            .filter { it.code.isNotBlank() && it.name.isNotBlank() }
            .map { hero ->
                E7Hero(
                    code = hero.code,
                    name = hero.name,
                    rarity = hero.rarity,
                    attribute = hero.attribute,
                    role = hero.role,
                    zodiac = hero.zodiac,
                    stats = hero.toStats(),
                    assets = E7HeroAssets(
                        iconUrl = hero.iconUrl,
                        thumbnailUrl = hero.thumbnailUrl,
                        imageUrl = hero.imageUrl,
                    ),
                    description = hero.description,
                    awakenings = hero.awakenings.map(SupabaseAwakeningRow::toDomain),
                    memoryImprint = hero.memoryImprint?.toDomain(),
                    skills = maintainedSkills[hero.code].orEmpty(),
                    exclusiveEquipment = exclusiveEquipmentByHeroCode[hero.code]?.toDomain(),
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    private fun mergeArtifacts(maintainedCatalog: SupabaseCatalog): List<E7Artifact> {
        return maintainedCatalog.artifacts
            .filter { it.code.isNotBlank() && it.name.isNotBlank() }
            .map { artifact ->
                E7Artifact(
                    code = artifact.code,
                    name = artifact.name,
                    rarity = artifact.rarity,
                    role = artifact.role?.takeIf(String::isNotBlank),
                    attack = artifact.attack,
                    health = artifact.health,
                    defense = artifact.defense,
                    description = artifact.description,
                    maxDescription = artifact.maxDescription,
                    lore = artifact.lore,
                    imageUrl = artifact.imageUrl ?: artifact.iconUrl,
                    iconUrl = artifact.iconUrl ?: artifact.imageUrl,
                    baseAttack = artifact.baseAttack,
                    baseHealth = artifact.baseHealth,
                    aliases = emptyList(),
                )
            }
            .sortedBy { it.name.lowercase() }
    }
}

internal fun parseRtaSeasons(payload: String): List<RtaSeason> {
    val response = RTA_JSON.decodeFromString<RtaResponse<RtaSeasonValueDto>>(payload)
    response.requireSuccess()
    return response.value?.resultBody.orEmpty()
        .filter { it.code.isNotBlank() }
        .map { season ->
            RtaSeason(
                code = season.code,
                name = season.name.ifBlank { season.code },
                startDate = season.startDate,
                endDate = season.endDate,
                isCurrent = season.isCurrent == 1,
            )
        }
        .sortedByDescending(RtaSeason::startDate)
}

internal fun parseHeroRtaAnalysis(payload: String): HeroRtaAnalysis {
    val response = RTA_JSON.decodeFromString<RtaResponse<RtaAnalysisValueDto>>(payload)
    response.requireSuccess()
    val analysis = response.value?.resultBody
        ?: throw IllegalStateException("官方 RTA 数据为空")
    val currentWinRate = analysis.winRates.firstOrNull {
        it.seasonCode == analysis.seasonCode
    } ?: analysis.winRates.firstOrNull()
    return HeroRtaAnalysis(
        heroCode = analysis.heroCode,
        seasonCode = analysis.seasonCode,
        tierCode = analysis.tierCode,
        sampleSize = analysis.sampleSize,
        equipmentSets = analysis.equipmentSets.map { equipment ->
            RtaEquipmentSet(
                rank = equipment.rank,
                setCodes = equipment.setCodes,
                usageRate = equipment.usageRate,
                winRate = equipment.winRate,
            )
        },
        pickPositions = analysis.pickPositions.map { rate ->
            RtaPositionRate(position = rate.position, rate = rate.rate)
        },
        banPositions = analysis.banPositions.map { rate ->
            RtaPositionRate(position = rate.position, rate = rate.rate)
        },
        winRate = currentWinRate?.winRate,
        winRateRank = currentWinRate?.rank?.takeIf { it > 0 },
    )
}

private val RTA_JSON = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

private fun String.urlEncoded(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8.toString())

private fun RtaResponse<*>.requireSuccess() {
    if (code != 0) {
        throw IllegalStateException(message.ifBlank { "官方 RTA 接口返回错误 $code" })
    }
}

private fun validateRtaPayload(payload: String) {
    val root = RTA_JSON.parseToJsonElement(payload).jsonObject
    val code = root["code"]?.jsonPrimitive?.intOrNull
        ?: throw IllegalStateException("官方 RTA 接口响应无效")
    if (code != 0) {
        val message = root["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
        throw IllegalStateException(message.ifBlank { "官方 RTA 接口返回错误 $code" })
    }
}

@Serializable
private data class RtaResponse<T>(
    val code: Int = -1,
    val message: String = "",
    val value: T? = null,
)

@Serializable
private data class RtaSeasonValueDto(
    @SerialName("result_body") val resultBody: List<RtaSeasonDto> = emptyList(),
)

@Serializable
private data class RtaSeasonDto(
    @SerialName("season_code") val code: String = "",
    val name: String = "",
    val startDate: String = "",
    val endDate: String = "",
    @SerialName("is_now_season") val isCurrent: Int = 0,
)

@Serializable
private data class RtaAnalysisValueDto(
    @SerialName("result_body") val resultBody: RtaAnalysisDto? = null,
)

@Serializable
private data class RtaAnalysisDto(
    val heroCode: String = "",
    val seasonCode: String = "",
    @SerialName("seasonTierCode") val tierCode: String = "",
    @SerialName("current_seasontier_tot") val sampleSize: Int = 0,
    @SerialName("equip") val equipmentSets: List<RtaEquipmentSetDto> = emptyList(),
    @SerialName("pick") val pickPositions: List<RtaPositionRateDto> = emptyList(),
    @SerialName("ban") val banPositions: List<RtaPositionRateDto> = emptyList(),
    @SerialName("win_rate") val winRates: List<RtaWinRateDto> = emptyList(),
)

@Serializable
private data class RtaEquipmentSetDto(
    val rank: Int = 0,
    @SerialName("equip_list") val setCodes: List<String> = emptyList(),
    @SerialName("rate") val usageRate: Double = 0.0,
    @SerialName("win_rate") val winRate: Double = 0.0,
)

@Serializable
private data class RtaPositionRateDto(
    @SerialName("num") val position: Int = 0,
    val rate: Double = 0.0,
)

@Serializable
private data class RtaWinRateDto(
    @SerialName("season_code") val seasonCode: String = "",
    @SerialName("win_rate") val winRate: Double = 0.0,
    val rank: Int = 0,
)
