package com.e7orbit.automation

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Durable workflow journal.
 *
 * Checkpoints are evidence for reconciliation and diagnostics. They are deliberately
 * not interpreted as permission to skip a workflow step after process restart.
 */
class FileWorkflowCheckpointStore(
    private val file: File,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    },
) : WorkflowCheckpointStore {
    private val mutex = Mutex()
    private var loaded = false
    private var persistedEntryCount = 0
    private val checkpoints = mutableListOf<WorkflowCheckpoint>()

    init {
        require(maxEntries > 0) { "maxEntries 必须大于 0" }
    }

    override suspend fun record(checkpoint: WorkflowCheckpoint) {
        mutex.withLock {
            loadIfNeeded()
            checkpoints += checkpoint
            trimToRetentionLimit()
            append(checkpoint)
            if (persistedEntryCount >= maxEntries + compactionSlack()) {
                compact()
            }
        }
    }

    override suspend fun history(runId: String): List<WorkflowCheckpoint> =
        mutex.withLock {
            loadIfNeeded()
            checkpoints.filter { checkpoint -> checkpoint.runId == runId }
        }

    override suspend fun recent(limit: Int): List<WorkflowCheckpoint> {
        require(limit >= 0) { "limit 不能为负数" }
        if (limit == 0) return emptyList()
        return mutex.withLock {
            loadIfNeeded()
            checkpoints.takeLast(limit)
        }
    }

    private suspend fun loadIfNeeded() {
        if (loaded) return
        val lines = withContext(Dispatchers.IO) {
            if (file.isFile) file.readLines() else emptyList()
        }
        persistedEntryCount = lines.size
        val restored = lines.mapNotNull { line ->
            line.takeIf(String::isNotBlank)?.let { encoded ->
                runCatching {
                    json.decodeFromString<WorkflowCheckpoint>(encoded)
                }.getOrNull()
            }
        }
        checkpoints += restored.takeLast(maxEntries)
        loaded = true
    }

    private fun trimToRetentionLimit() {
        val excess = checkpoints.size - maxEntries
        if (excess > 0) checkpoints.subList(0, excess).clear()
    }

    private suspend fun append(checkpoint: WorkflowCheckpoint) =
        withContext(Dispatchers.IO) {
            ensureParentDirectory()
            val separator = if (file.length() == 0L) "" else System.lineSeparator()
            file.appendText(separator + json.encodeToString(checkpoint))
            persistedEntryCount += 1
        }

    private suspend fun compact() = withContext(Dispatchers.IO) {
        ensureParentDirectory()
        val parent = file.parentFile
        val tempFile = File(parent, "${file.name}.tmp")
        tempFile.writeText(
            checkpoints.joinToString(System.lineSeparator()) { checkpoint ->
                json.encodeToString(checkpoint)
            },
        )
        try {
            Files.move(
                tempFile.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                tempFile.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        persistedEntryCount = checkpoints.size
    }

    private fun ensureParentDirectory() {
        val parent = file.parentFile ?: return
        if (!parent.exists()) {
            check(parent.mkdirs() || parent.isDirectory) {
                "无法创建 checkpoint 目录：${parent.absolutePath}"
            }
        }
    }

    private fun compactionSlack(): Int = maxOf(1, maxEntries / 4)

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 2_000
    }
}
