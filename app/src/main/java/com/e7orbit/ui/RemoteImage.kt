package com.e7orbit.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun RemoteImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    var state by remember(url) { mutableStateOf<RemoteImageState>(RemoteImageState.Loading) }

    LaunchedEffect(url) {
        state = if (url.isNullOrBlank()) {
            RemoteImageState.Unavailable
        } else {
            RemoteImageCache.load(context, url)
                ?.let(RemoteImageState::Loaded)
                ?: RemoteImageState.Unavailable
        }
    }

    when (val imageState = state) {
        RemoteImageState.Loading,
        RemoteImageState.Unavailable,
        -> Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )

        is RemoteImageState.Loaded -> Image(
            bitmap = imageState.bitmap.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
        )
    }
}

private sealed interface RemoteImageState {
    data object Loading : RemoteImageState
    data object Unavailable : RemoteImageState
    data class Loaded(val bitmap: Bitmap) : RemoteImageState
}

private object RemoteImageCache {
    private const val MEMORY_CACHE_SIZE_KB = 32 * 1_024

    private val memory = object : LruCache<String, Bitmap>(MEMORY_CACHE_SIZE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1_024).coerceAtLeast(1)
    }

    suspend fun load(context: Context, url: String): Bitmap? = withContext(Dispatchers.IO) {
        memory.get(url)?.let { return@withContext it }

        val imageDir = context.applicationContext.cacheDir.resolve("e7-images").apply { mkdirs() }
        val cacheFile = imageDir.resolve(cacheFileName(url))
        decodeCached(cacheFile)?.let { bitmap ->
            memory.put(url, bitmap)
            return@withContext bitmap
        }

        download(url, cacheFile)?.also { bitmap -> memory.put(url, bitmap) }
    }

    private fun decodeCached(cacheFile: File): Bitmap? {
        if (!cacheFile.exists()) return null
        val bitmap = BitmapFactory.decodeFile(cacheFile.absolutePath)
        if (bitmap == null) cacheFile.delete()
        return bitmap
    }

    private fun download(url: String, cacheFile: File): Bitmap? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 12_000
            readTimeout = 20_000
            setRequestProperty("Accept", "image/*")
            setRequestProperty("User-Agent", "E7Orbit/0.1")
        }
        val temporaryFile = File.createTempFile(cacheFile.name, ".tmp", cacheFile.parentFile)
        return try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.use { input ->
                temporaryFile.outputStream().use { output -> input.copyTo(output) }
            }
            if (!temporaryFile.renameTo(cacheFile)) {
                temporaryFile.copyTo(cacheFile, overwrite = true)
                temporaryFile.delete()
            }
            decodeCached(cacheFile)
        } catch (_: Exception) {
            null
        } finally {
            temporaryFile.delete()
            connection.disconnect()
        }
    }

    private fun cacheFileName(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        return digest.joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        } + ".img"
    }
}
