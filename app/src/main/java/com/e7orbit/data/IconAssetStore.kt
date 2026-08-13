package com.e7orbit.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Persistent, startup-warmed cache for remote icons.
 *
 * Files live under [Context.getFilesDir] (not the OS-evictable cache dir), so once an
 * icon is downloaded it is never re-downloaded. The sync tool (mirrorImage in
 * tools/sync-hero-catalog.mjs) never overwrites an existing Storage object, so a
 * Storage path is effectively immutable: "有更新" always means a brand-new path for
 * a new hero/skill, which a simple existence check detects.
 */
object IconAssetStore {
    private const val STORE_VERSION = "v1"
    private const val MEMORY_CACHE_SIZE_KB = 32 * 1_024
    private const val DEFAULT_CONCURRENCY = 6

    private val memory = object : LruCache<String, Bitmap>(MEMORY_CACHE_SIZE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1_024).coerceAtLeast(1)
    }

    data class PreloadResult(
        val downloaded: Int,
        val skipped: Int,
        val failed: Int,
    )

    fun rootDir(context: Context): File =
        File(context.applicationContext.filesDir, "e7-icons-$STORE_VERSION")

    fun localFile(context: Context, url: String): File =
        File(rootDir(context), relativeName(url))

    fun isCached(context: Context, url: String): Boolean =
        localFile(context, url).isFile

    /** Resolves a bitmap from memory, then disk, then network (populating the disk cache). */
    suspend fun load(context: Context, url: String): Bitmap? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        memory.get(url)?.let { return@withContext it }

        val file = localFile(context, url)
        decode(file)?.also { memory.put(url, it) }
            ?: run {
                if (fetchToFile(url, file)) decode(file)?.also { memory.put(url, it) } else null
            }
    }

    /**
     * Downloads every icon that is not already on disk. Already-cached icons are skipped
     * without any network traffic; per-item failures are swallowed so a single broken URL
     * never aborts the batch (the icon simply stays lazy and retries when shown).
     */
    suspend fun preload(
        context: Context,
        urls: Collection<String>,
        concurrency: Int = DEFAULT_CONCURRENCY,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): PreloadResult = withContext(Dispatchers.IO) {
        val targets = urls.asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        val missing = targets.filter { !localFile(context, it).isFile }
        val skipped = targets.size - missing.size
        if (missing.isEmpty()) return@withContext PreloadResult(0, skipped, 0)

        val completed = java.util.concurrent.atomic.AtomicInteger(skipped)
        val total = targets.size
        onProgress?.invoke(completed.get(), total)
        val semaphore = Semaphore(concurrency.coerceIn(1, 16))
        val outcomes = missing.map { url ->
            async {
                semaphore.withPermit {
                    fetchToFile(url, localFile(context, url))
                }.also {
                    onProgress?.invoke(completed.incrementAndGet(), total)
                }
            }
        }.awaitAll()

        PreloadResult(
            downloaded = outcomes.count { it },
            skipped = skipped,
            failed = outcomes.count { !it },
        )
    }

    private fun decode(file: File): Bitmap? {
        if (!file.isFile) return null
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        if (bitmap == null) file.delete()
        return bitmap
    }

    private fun fetchToFile(url: String, file: File): Boolean {
        file.parentFile?.mkdirs()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "image/*")
            setRequestProperty("User-Agent", "E7Orbit/0.1")
        }
        val temporary = File.createTempFile(file.name, ".tmp", file.parentFile)
        return try {
            if (connection.responseCode !in 200..299) return false
            connection.inputStream.use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            if (temporary.length() <= 0L) {
                temporary.delete()
                return false
            }
            if (!temporary.renameTo(file)) {
                temporary.copyTo(file, overwrite = true)
                temporary.delete()
            }
            true
        } catch (_: Exception) {
            temporary.delete()
            false
        } finally {
            temporary.delete()
            connection.disconnect()
        }
    }

    /**
     * Filename is the SHA-256 of the full URL (including any `?v=` cache-busting query)
     * plus the extension. An unchanged URL keeps its cached copy; a URL whose query changed
     * after a Wiki image overwrite resolves to a fresh file and re-downloads.
     */
    private fun relativeName(url: String): String {
        val ext = url.substringBefore('?').substringAfterLast('.', "")
            .takeIf { it.matches(EXT_PATTERN) }
            ?.let { ".$it" }
            ?: ""
        return sha256(url) + ext
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private val EXT_PATTERN = Regex("[A-Za-z0-9]{1,5}")
}
