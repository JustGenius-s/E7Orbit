package com.e7orbit.logging

import android.content.Context
import android.util.Log
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface OrbitLogger {
    fun debug(event: String, vararg fields: Pair<String, Any?>)
    fun info(event: String, vararg fields: Pair<String, Any?>)
    fun warn(event: String, vararg fields: Pair<String, Any?>)
    fun error(event: String, error: Throwable? = null, vararg fields: Pair<String, Any?>)
}

object NoOpOrbitLogger : OrbitLogger {
    override fun debug(event: String, vararg fields: Pair<String, Any?>) = Unit
    override fun info(event: String, vararg fields: Pair<String, Any?>) = Unit
    override fun warn(event: String, vararg fields: Pair<String, Any?>) = Unit
    override fun error(
        event: String,
        error: Throwable?,
        vararg fields: Pair<String, Any?>,
    ) = Unit
}

class FileOrbitLogger(
    context: Context,
) : OrbitLogger, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private val directory = File(context.applicationContext.filesDir, "diagnostics/logs")
    private val sessionId = UUID.randomUUID().toString().take(8)
    private val formatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss.SSSXXX")
        .withZone(ZoneId.systemDefault())
    private val logFile = File(directory, "orbit-$sessionId.log")

    init {
        info(
            "logger.started",
            "session" to sessionId,
            "file" to logFile.name,
        )
    }

    override fun debug(event: String, vararg fields: Pair<String, Any?>) {
        write(Level.DEBUG, event, null, fields)
    }

    override fun info(event: String, vararg fields: Pair<String, Any?>) {
        write(Level.INFO, event, null, fields)
    }

    override fun warn(event: String, vararg fields: Pair<String, Any?>) {
        write(Level.WARN, event, null, fields)
    }

    override fun error(
        event: String,
        error: Throwable?,
        vararg fields: Pair<String, Any?>,
    ) {
        write(Level.ERROR, event, error, fields)
    }

    override fun close() {
        scope.cancel()
    }

    private fun write(
        level: Level,
        event: String,
        error: Throwable?,
        fields: Array<out Pair<String, Any?>>,
    ) {
        val message = buildString {
            append(event)
            fields.forEach { (key, value) ->
                append(' ')
                append(sanitize(key))
                append('=')
                append(sanitize(value?.toString() ?: "null"))
            }
            error?.let {
                append(" error=")
                append(sanitize(it.stackTraceToString()))
            }
        }
        Log.println(level.priority, LOG_TAG, message)
        val line = "${formatter.format(Instant.now())}\t${level.name}\t$message\n"
        scope.launch {
            writeMutex.withLock {
                check(directory.exists() || directory.mkdirs()) {
                    "无法创建日志目录"
                }
                rotateIfNeeded()
                logFile.appendText(line, Charsets.UTF_8)
            }
        }
    }

    private fun rotateIfNeeded() {
        if (!logFile.exists() || logFile.length() < MAX_FILE_BYTES) return
        val rotated = File(directory, "orbit-$sessionId-${System.currentTimeMillis()}.log")
        logFile.renameTo(rotated)
        directory.listFiles { file ->
            file.isFile && file.name.startsWith("orbit-") && file.extension == "log"
        }?.sortedByDescending(File::lastModified)
            ?.drop(MAX_LOG_FILES)
            ?.forEach { it.delete() }
    }

    private fun sanitize(value: String): String =
        value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')

    private enum class Level(
        val priority: Int,
    ) {
        DEBUG(Log.DEBUG),
        INFO(Log.INFO),
        WARN(Log.WARN),
        ERROR(Log.ERROR),
    }

    private companion object {
        const val LOG_TAG = "E7Orbit"
        const val MAX_FILE_BYTES = 2L * 1024L * 1024L
        const val MAX_LOG_FILES = 4
    }
}
