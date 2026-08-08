package com.e7orbit.capture

import android.content.Context
import com.e7orbit.data.E7Gear
import com.e7orbit.data.GearExportSerializer
import com.e7orbit.data.GearImportPhase
import com.e7orbit.data.GearImportState
import com.e7orbit.logging.OrbitLogger
import com.e7orbit.vpn.GearCapturePayload
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class GearImportRepository(
    context: Context,
    private val logger: OrbitLogger,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val storeFile = appContext.filesDir.resolve("gear-scan/imported-gears.json")
    private val exportFile = appContext.filesDir.resolve("gear-scan/gear.txt")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val _state = MutableStateFlow(loadSavedState())

    val state: StateFlow<GearImportState> = _state.asStateFlow()

    fun import(payload: GearCapturePayload) {
        if (payload.streams.isEmpty()) {
            _state.value = _state.value.copy(
                phase = GearImportPhase.ERROR,
                errorMessage = "没有捕获到 3333/5222 装备流量，请进入背包后再停止抓包",
            )
            return
        }
        _state.value = _state.value.copy(
            phase = GearImportPhase.PARSING,
            errorMessage = null,
        )
        scope.launch {
            try {
                val response = requestItems(payload.streams)
                val root = json.parseToJsonElement(response).jsonObject
                val status = root["status"]?.jsonPrimitive?.contentOrNull
                check(status == "SUCCESS") { "解析服务返回 $status" }
                val rawItems = root["data"]?.jsonArray
                    ?.mapNotNull { it as? JsonObject }
                    .orEmpty()
                val gears = rawItems.mapNotNull(GearImportParser::parseItem)
                check(gears.isNotEmpty()) { "解析服务没有识别到装备，请重新打开背包后扫描" }
                val rawHeroes = root["units"]?.jsonArray
                    ?.mapNotNull { it as? JsonArray }
                    ?.maxByOrNull(JsonArray::size)
                    ?.mapNotNull { it as? JsonObject }
                    .orEmpty()
                val heroCount = rawHeroes.size
                val importedAt = System.currentTimeMillis()
                val export = GearExportSerializer.serializeScannerExport(
                    gears = gears,
                    rawItems = rawItems,
                    rawHeroes = rawHeroes,
                )
                persist(gears, heroCount, importedAt, export)
                _state.value = GearImportState(
                    phase = GearImportPhase.READY,
                    gears = gears,
                    heroCount = heroCount,
                    importedAtEpochMs = importedAt,
                )
                cleanupSessions(payload.sessionPath, importSucceeded = true)
                logger.info(
                    "gear.import_succeeded",
                    "items" to gears.size,
                    "heroes" to heroCount,
                    "streams" to payload.streams.size,
                    "bytes" to payload.byteCount,
                )
            } catch (error: Throwable) {
                cleanupSessions(payload.sessionPath, importSucceeded = false)
                logger.error("gear.import_failed", error)
                _state.value = _state.value.copy(
                    phase = GearImportPhase.ERROR,
                    errorMessage = error.message ?: "装备解析失败",
                )
            }
        }
    }

    private fun requestItems(streams: List<String>): String {
        val requestBody = buildJsonObject {
            put("data", buildJsonArray { streams.forEach { add(JsonPrimitive(it)) } })
        }.toString()
        val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setFixedLengthStreamingMode(requestBody.toByteArray(Charsets.UTF_8).size)
        }
        return try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(requestBody) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            check(status in 200..299) { "解析服务 HTTP $status: ${body.take(160)}" }
            body
        } finally {
            connection.disconnect()
        }
    }

    fun hasCompatibleExport(): Boolean = exportFile.exists()

    fun readGearExport(): String {
        check(hasCompatibleExport()) {
            "当前装备数据来自旧版本，请重新抓包并打开背包后再导出"
        }
        return exportFile.readText(Charsets.UTF_8)
    }

    private fun persist(
        gears: List<E7Gear>,
        heroCount: Int,
        importedAt: Long,
        export: String,
    ) {
        storeFile.parentFile?.mkdirs()
        val saved = SavedGearImport(gears, heroCount, importedAt)
        writeAtomically(storeFile, json.encodeToString(saved))
        writeAtomically(exportFile, export)
    }

    private fun writeAtomically(file: File, content: String) {
        val temp = file.resolveSibling("${file.name}.tmp")
        temp.writeText(content, Charsets.UTF_8)
        if (file.exists()) check(file.delete()) { "无法更新 ${file.name}" }
        check(temp.renameTo(file)) { "无法保存 ${file.name}" }
    }

    private fun loadSavedState(): GearImportState = runCatching {
        if (!storeFile.exists()) return@runCatching GearImportState()
        val saved = json.decodeFromString<SavedGearImport>(storeFile.readText(Charsets.UTF_8))
        GearImportState(
            phase = GearImportPhase.READY,
            gears = saved.gears,
            heroCount = saved.heroCount,
            importedAtEpochMs = saved.importedAtEpochMs,
        )
    }.getOrElse { error ->
        logger.error("gear.import_load_failed", error)
        GearImportState(
            phase = GearImportPhase.ERROR,
            errorMessage = "已保存的装备数据无法读取",
        )
    }

    private fun cleanupSessions(currentPath: String, importSucceeded: Boolean) {
        if (importSucceeded) File(currentPath).deleteRecursively()
        val parent = appContext.filesDir.resolve("gear-scan")
        parent.listFiles { file -> file.isDirectory }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(2)
            ?.forEach { it.deleteRecursively() }
    }

    override fun close() {
        scope.cancel()
    }

    @kotlinx.serialization.Serializable
    private data class SavedGearImport(
        val gears: List<E7Gear>,
        val heroCount: Int,
        val importedAtEpochMs: Long,
    )

    private companion object {
        const val API_URL = "https://krivpfvxi0.execute-api.us-west-2.amazonaws.com/dev/getItems"
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 120_000
    }
}
