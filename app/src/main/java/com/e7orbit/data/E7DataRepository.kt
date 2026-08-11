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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1_000L
private const val RTA_ANALYSIS_CACHE_MAX_AGE_MS = 6L * 60L * 60L * 1_000L

private const val OFFICIAL_HERO_URL =
    "https://static-pubcomm.onstove.com/gameRecord/epic7/epic7_hero.json"
private const val FRIBBELS_HERO_URL =
    "https://e7-optimizer-game-data.s3-accelerate.amazonaws.com/herodata.json"
private const val FRIBBELS_ARTIFACT_URL =
    "https://e7-optimizer-game-data.s3-accelerate.amazonaws.com/artifactdata.json"
private const val RTA_SEASONS_URL =
    "https://e7api.onstove.com/gameApi/getSeasonList?lang=en"
private const val RTA_ANALYSIS_URL =
    "https://e7api.onstove.com/gameApi/getHeroAnalysis"

private const val OFFICIAL_CACHE = "official-heroes.json"
private const val FRIBBELS_HERO_CACHE = "fribbels-heroes.json"
private const val FRIBBELS_ARTIFACT_CACHE = "fribbels-artifacts.json"
private const val RTA_SEASONS_CACHE = "rta-seasons.json"

class E7DataRepository(
    context: Context,
) {
    private val cacheDir = context.applicationContext.cacheDir.resolve("e7-data")
    private val supabaseCatalog = SupabaseCatalogRepository(context)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    suspend fun load(forceRefresh: Boolean = false): E7DataSnapshot =
        loadSnapshot(forceRefresh = forceRefresh)

    private suspend fun loadSnapshot(
        forceRefresh: Boolean,
        maintainedCatalogOverride: SupabaseCatalog? = null,
    ): E7DataSnapshot = withContext(Dispatchers.IO) {
        cacheDir.mkdirs()
        val official = runCatching {
            readSource(
                url = OFFICIAL_HERO_URL,
                fileName = OFFICIAL_CACHE,
                forceRefresh = forceRefresh,
            )
        }.getOrNull()
        val fribbelsHeroes = runCatching {
            readSource(
                url = FRIBBELS_HERO_URL,
                fileName = FRIBBELS_HERO_CACHE,
                forceRefresh = forceRefresh,
            )
        }.getOrNull()
        val fribbelsArtifacts = runCatching {
            readSource(
                url = FRIBBELS_ARTIFACT_URL,
                fileName = FRIBBELS_ARTIFACT_CACHE,
                forceRefresh = forceRefresh,
            )
        }.getOrNull()
        val maintainedCatalog = maintainedCatalogOverride ?: runCatching {
            supabaseCatalog.load(forceRefresh)
        }.getOrNull()
        if (official == null && fribbelsHeroes == null && maintainedCatalog == null) {
            throw IllegalStateException("英雄 Wiki 数据源暂时不可用")
        }

        val heroes = mergeHeroes(official, fribbelsHeroes, maintainedCatalog)
        E7DataSnapshot(
            heroes = heroes,
            artifacts = mergeArtifacts(fribbelsArtifacts, maintainedCatalog),
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

    private fun readSource(
        url: String,
        fileName: String,
        forceRefresh: Boolean,
    ): String {
        val cacheFile = cacheDir.resolve(fileName)
        val cacheIsFresh = cacheFile.exists() &&
            System.currentTimeMillis() - cacheFile.lastModified() < CACHE_MAX_AGE_MS
        if (!forceRefresh && cacheIsFresh) {
            return cacheFile.readText()
        }

        return try {
            val fresh = fetch(url)
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

    private fun fetch(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "E7Orbit/0.1")
        }
        return try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IllegalStateException("HTTP $status from $url")
            }
            connection.inputStream.bufferedReader().use { reader -> reader.readText() }
        } finally {
            connection.disconnect()
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

    private fun mergeHeroes(
        officialPayload: String?,
        fribbelsPayload: String?,
        maintainedCatalog: SupabaseCatalog? = null,
    ): List<E7Hero> {
        val officialHeroes = officialPayload?.let(::parseOfficialHeroes).orEmpty()
        val fribbelsHeroes = fribbelsPayload?.let(::parseFribbelsHeroes).orEmpty()
        val maintainedHeroes = maintainedCatalog?.heroes.orEmpty()
        val effectsBySlug = maintainedCatalog?.effects.orEmpty().associateBy(SupabaseStatusEffectRow::slug)
        val maintainedSkills = maintainedCatalog?.skills.orEmpty()
            .groupBy { it.heroCode }
            .mapValues { (_, skills) ->
                skills.sortedBy(SupabaseSkillRow::slot).map { skill ->
                    skill.toDomain(effectsBySlug)
                }
            }
        val exclusiveEquipmentByHeroCode = maintainedCatalog?.exclusiveEquipment.orEmpty()
            .associateBy(SupabaseExclusiveEquipmentRow::heroCode)
        val allCodes = linkedSetOf<String>().apply {
            officialHeroes.forEach { add(it.code) }
            fribbelsHeroes.mapNotNull { it.code }.forEach(::add)
            maintainedHeroes.forEach { add(it.code) }
        }.filter(String::isNotBlank)

        return allCodes.mapNotNull { code ->
            val official = officialHeroes.firstOrNull { it.code == code }
            val maintained = maintainedHeroes.firstOrNull { it.code == code }
                ?: official?.let { candidate ->
                    maintainedHeroes.firstOrNull {
                        it.name.equals(candidate.name, ignoreCase = true)
                    }
                }
            val details = fribbelsHeroes.firstOrNull { it.code == code }
                ?: maintained?.let { candidate ->
                    fribbelsHeroes.firstOrNull {
                        it.name.equals(candidate.name, ignoreCase = true)
                    }
                }
            val name = maintained?.name?.takeIf(String::isNotBlank)
                ?: details?.name?.takeIf(String::isNotBlank)
                ?: official?.name?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val fribbelsAssets = details?.assets?.toDomain() ?: E7HeroAssets()
            val maintainedAssets = E7HeroAssets(
                iconUrl = maintained?.iconUrl,
                thumbnailUrl = maintained?.thumbnailUrl,
                imageUrl = maintained?.imageUrl?.takeUnless { it.endsWith("/image.png") },
            )
            E7Hero(
                code = code,
                name = name,
                rarity = maintained?.rarity ?: details?.rarity ?: official?.grade?.toIntOrNull(),
                attribute = maintained?.attribute?.ifBlank { details?.attribute ?: official?.attribute.orEmpty() }
                    ?: details?.attribute?.ifBlank { official?.attribute.orEmpty() }
                    ?: official?.attribute.orEmpty(),
                role = maintained?.role?.ifBlank { details?.role ?: official?.role.orEmpty() }
                    ?: details?.role?.ifBlank { official?.role.orEmpty() }
                    ?: official?.role.orEmpty(),
                zodiac = maintained?.zodiac ?: details?.zodiac,
                stats = maintained?.toStats(details?.stats) ?: details?.stats,
                assets = maintainedAssets.mergeWith(fribbelsAssets),
                description = maintained?.description,
                awakenings = maintained?.awakenings.orEmpty().map(SupabaseAwakeningRow::toDomain),
                memoryImprint = maintained?.memoryImprint?.toDomain(),
                skills = maintainedSkills[code].orEmpty(),
                exclusiveEquipment = exclusiveEquipmentByHeroCode[code]?.toDomain(),
            )
        }.sortedBy { it.name.lowercase() }
    }

    private fun E7HeroAssets.mergeWith(fallback: E7HeroAssets): E7HeroAssets = E7HeroAssets(
        iconUrl = iconUrl ?: fallback.iconUrl,
        thumbnailUrl = thumbnailUrl ?: fallback.thumbnailUrl,
        imageUrl = imageUrl ?: fallback.imageUrl,
    )

    private fun parseOfficialHeroes(payload: String): List<OfficialHeroDto> {
        val root = json.parseToJsonElement(payload).jsonObject
        val languageArray = root["en"]?.jsonArray
            ?: root.values.firstOrNull { it is JsonArray }?.jsonArray
            ?: return emptyList()
        return languageArray.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement<OfficialHeroDto>(element) }.getOrNull()
        }
    }

    private fun parseFribbelsHeroes(payload: String): List<FribbelsHeroDto> {
        val root = json.parseToJsonElement(payload).jsonObject
        return root.values.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement<FribbelsHeroDto>(element) }.getOrNull()
        }
    }

    private fun parseArtifacts(payload: String): List<E7Artifact> {
        val root = json.parseToJsonElement(payload).jsonObject
        return root.mapNotNull { (key, element) ->
            val objectValue = element as? JsonObject ?: return@mapNotNull null
            val stats = objectValue["stats"]?.jsonObject
            val name = objectValue.stringValue("name", "artifactName", "itemName") ?: key
            E7Artifact(
                code = objectValue.stringValue("code", "artifactCode", "id") ?: key,
                name = name,
                rarity = objectValue.intValue("rarity", "grade", "maxLevel"),
                role = objectValue.stringValue("role", "job", "job_cd"),
                attack = stats?.intValue("attack", "atk"),
                health = stats?.intValue("health", "hp"),
                defense = stats?.intValue("defense", "def"),
                description = objectValue.stringValue("description", "desc", "skillDesc"),
            )
        }
    }

    private fun mergeArtifacts(
        fribbelsPayload: String?,
        maintainedCatalog: SupabaseCatalog?,
    ): List<E7Artifact> {
        val fribbelsArtifacts = fribbelsPayload?.let(::parseArtifacts).orEmpty()
        val maintainedArtifacts = maintainedCatalog?.artifacts.orEmpty()
        val allCodes = linkedSetOf<String>().apply {
            fribbelsArtifacts.forEach { add(it.code) }
            maintainedArtifacts.forEach { add(it.code) }
        }.filter(String::isNotBlank)

        return allCodes.mapNotNull { code ->
            val fribbels = fribbelsArtifacts.firstOrNull { it.code == code }
            val maintained = maintainedArtifacts.firstOrNull { it.code == code }
                ?: fribbels?.let { candidate ->
                    maintainedArtifacts.firstOrNull {
                        it.name.equals(candidate.name, ignoreCase = true)
                    }
                }
            val name = maintained?.name?.takeIf(String::isNotBlank)
                ?: fribbels?.name?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            E7Artifact(
                code = code,
                name = name,
                rarity = maintained?.rarity ?: fribbels?.rarity,
                role = maintained?.role?.takeIf(String::isNotBlank) ?: fribbels?.role,
                attack = maintained?.attack ?: fribbels?.attack,
                health = maintained?.health ?: fribbels?.health,
                defense = maintained?.defense ?: fribbels?.defense,
                description = maintained?.description ?: fribbels?.description,
                maxDescription = maintained?.maxDescription,
                lore = maintained?.lore,
                imageUrl = maintained?.imageUrl ?: maintained?.iconUrl,
                iconUrl = maintained?.iconUrl ?: maintained?.imageUrl,
                baseAttack = maintained?.baseAttack,
                baseHealth = maintained?.baseHealth,
            )
        }.sortedBy { it.name.lowercase() }
    }

    private fun JsonObject.stringValue(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.intValue(vararg keys: String): Int? = keys.firstNotNullOfOrNull { key ->
        this[key]?.jsonPrimitive?.intOrNull
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
private data class OfficialHeroDto(
    val code: String = "",
    val grade: String = "",
    val name: String = "",
    @SerialName("job_cd") val role: String = "",
    @SerialName("attribute_cd") val attribute: String = "",
)

@Serializable
private data class FribbelsHeroDto(
    val code: String? = null,
    @SerialName("_id") val id: String? = null,
    val name: String? = null,
    val rarity: Int? = null,
    val attribute: String? = null,
    val role: String? = null,
    val zodiac: String? = null,
    val assets: FribbelsHeroAssetsDto? = null,
    val calculatedStatus: Map<String, FribbelsStatusDto> = emptyMap(),
) {
    val stats: E7HeroStats?
        get() {
            val status = calculatedStatus["lv60SixStarFullyAwakened"]
                ?: calculatedStatus.values.firstOrNull()
                ?: return null
            return E7HeroStats(
                attack = status.attack,
                health = status.health,
                defense = status.defense,
                speed = status.speed,
                criticalChance = status.criticalChance?.toPercentInt(),
                criticalDamage = status.criticalDamage?.toPercentInt(),
                effectiveness = status.effectiveness?.toPercentInt(),
                effectResistance = status.effectResistance?.toPercentInt(),
                combatPower = status.combatPower,
            )
        }

    private fun Double.toPercentInt(): Int = (this * 100).toInt()
}

@Serializable
private data class FribbelsHeroAssetsDto(
    val icon: String? = null,
    val thumbnail: String? = null,
    val image: String? = null,
) {
    fun toDomain(): E7HeroAssets = E7HeroAssets(
        iconUrl = icon,
        thumbnailUrl = thumbnail,
        imageUrl = image?.takeUnless { it.endsWith("question_circle.png") },
    )
}

@Serializable
private data class FribbelsStatusDto(
    val cp: Int? = null,
    val atk: Int? = null,
    val hp: Int? = null,
    val spd: Int? = null,
    val def: Int? = null,
    val chc: Double? = null,
    val chd: Double? = null,
    val eff: Double? = null,
    val efr: Double? = null,
) {
    val combatPower: Int? get() = cp
    val attack: Int? get() = atk
    val health: Int? get() = hp
    val speed: Int? get() = spd
    val defense: Int? get() = def
    val criticalChance: Double? get() = chc
    val criticalDamage: Double? get() = chd
    val effectiveness: Double? get() = eff
    val effectResistance: Double? get() = efr
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
