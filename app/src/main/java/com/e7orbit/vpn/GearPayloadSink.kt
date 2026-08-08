package com.e7orbit.vpn

import android.content.Context
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Persists only Epic Seven importer traffic. Each TCP direction is stored as a
 * separate binary stream so the parser receives complete, ordered connections.
 */
class GearPayloadSink(context: Context) {
    private val sessionDir = context.filesDir
        .resolve("gear-scan")
        .resolve("session-${System.currentTimeMillis()}")
    private val streams = linkedMapOf<StreamKey, StreamFile>()
    private var nextFileId = 0
    private var closed = false
    private var capturedBytes = 0L
    private var capturedSegments = 0L

    init {
        sessionDir.mkdirs()
    }

    @Synchronized
    fun append(
        connectionId: Long,
        port: Int,
        direction: PayloadDirection,
        data: ByteArray,
    ): Boolean {
        if (closed || port !in CAPTURE_PORTS || data.isEmpty()) return false
        if (capturedBytes + data.size > MAX_CAPTURE_BYTES) return false

        val key = StreamKey(connectionId, port, direction)
        val stream = streams.getOrPut(key) {
            val file = sessionDir.resolve(
                "%04d-%d-%s.bin".format(nextFileId++, port, direction.name.lowercase()),
            )
            StreamFile(file, BufferedOutputStream(FileOutputStream(file)))
        }
        stream.output.write(data)
        stream.bytes += data.size
        capturedBytes += data.size
        capturedSegments += 1
        return true
    }

    @Synchronized
    fun finish(): GearCapturePayload {
        if (!closed) {
            streams.values.forEach { stream ->
                runCatching {
                    stream.output.flush()
                    stream.output.close()
                }
            }
            closed = true
        }
        val payloads = streams.values
            .filter { it.bytes > 0L }
            .map { it.file.readBytes().toHexString() }
        return GearCapturePayload(
            streams = payloads,
            segmentCount = capturedSegments,
            byteCount = capturedBytes,
            sessionPath = sessionDir.absolutePath,
        )
    }

    fun path(): String = sessionDir.absolutePath

    private data class StreamKey(
        val connectionId: Long,
        val port: Int,
        val direction: PayloadDirection,
    )

    private data class StreamFile(
        val file: File,
        val output: BufferedOutputStream,
        var bytes: Long = 0L,
    )

    companion object {
        private val CAPTURE_PORTS = setOf(3333, 5222)
        private const val MAX_CAPTURE_BYTES = 8L * 1024L * 1024L

        private fun ByteArray.toHexString(): String {
            val chars = CharArray(size * 2)
            forEachIndexed { index, byte ->
                val value = byte.toInt() and 0xFF
                chars[index * 2] = HEX[value ushr 4]
                chars[index * 2 + 1] = HEX[value and 0x0F]
            }
            return String(chars)
        }

        private val HEX = "0123456789abcdef".toCharArray()
    }
}

enum class PayloadDirection {
    GAME_TO_SERVER,
    SERVER_TO_GAME,
}

data class GearCapturePayload(
    val streams: List<String>,
    val segmentCount: Long,
    val byteCount: Long,
    val sessionPath: String,
)
