package com.e7orbit.data

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
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

private const val OFFICIAL_HERO_URL =
    "https://static-pubcomm.onstove.com/gameRecord/epic7/epic7_hero.json"
private const val FRIBBELS_HERO_URL =
    "https://e7-optimizer-game-data.s3-accelerate.amazonaws.com/herodata.json"
private const val FRIBBELS_ARTIFACT_URL =
    "https://e7-optimizer-game-data.s3-accelerate.amazonaws.com/artifactdata.json"

private const val OFFICIAL_CACHE = "official-heroes.json"
private const val FRIBBELS_HERO_CACHE = "fribbels-heroes.json"
private const val FRIBBELS_ARTIFACT_CACHE = "fribbels-artifacts.json"

class E7DataRepository(
    context: Context,
) {
    private val cacheDir = context.applicationContext.cacheDir.resolve("e7-data")
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    suspend fun load(forceRefresh: Boolean = false): E7DataSnapshot = withContext(Dispatchers.IO) {
        cacheDir.mkdirs()
        val official = readSource(
            url = OFFICIAL_HERO_URL,
            fileName = OFFICIAL_CACHE,
            forceRefresh = forceRefresh,
        )
        val fribbelsHeroes = readSource(
            url = FRIBBELS_HERO_URL,
            fileName = FRIBBELS_HERO_CACHE,
            forceRefresh = forceRefresh,
        )
        val fribbelsArtifacts = readSource(
            url = FRIBBELS_ARTIFACT_URL,
            fileName = FRIBBELS_ARTIFACT_CACHE,
            forceRefresh = forceRefresh,
        )

        val heroes = mergeHeroes(official, fribbelsHeroes)
        E7DataSnapshot(
            heroes = heroes,
            artifacts = parseArtifacts(fribbelsArtifacts),
            fetchedAtEpochMs = System.currentTimeMillis(),
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

    private fun mergeHeroes(
        officialPayload: String,
        fribbelsPayload: String,
    ): List<E7Hero> {
        val officialHeroes = parseOfficialHeroes(officialPayload)
        val fribbelsHeroes = parseFribbelsHeroes(fribbelsPayload)
        return officialHeroes.map { official ->
            val details = fribbelsHeroes.firstOrNull { candidate ->
                candidate.code == official.code ||
                    candidate.name.equals(official.name, ignoreCase = true)
            }
            E7Hero(
                code = official.code,
                name = details?.name?.takeIf { it.isNotBlank() } ?: official.name,
                rarity = details?.rarity ?: official.grade.toIntOrNull(),
                attribute = details?.attribute?.ifBlank { official.attribute } ?: official.attribute,
                role = details?.role?.ifBlank { official.role } ?: official.role,
                zodiac = details?.zodiac,
                stats = details?.stats,
                assets = details?.assets?.toDomain() ?: E7HeroAssets(),
            )
        }.sortedBy { it.name.lowercase() }
    }

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
                rarity = objectValue.stringValue("rarity", "grade", "maxLevel"),
                role = objectValue.stringValue("role", "job", "job_cd"),
                attack = stats?.intValue("attack", "atk"),
                health = stats?.intValue("health", "hp"),
                defense = stats?.intValue("defense", "def"),
                description = objectValue.stringValue("description", "desc", "skillDesc"),
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
