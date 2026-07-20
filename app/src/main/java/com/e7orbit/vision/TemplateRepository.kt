package com.e7orbit.vision

import android.content.Context
import com.e7orbit.automation.VisionHealth
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

private const val ASSET_ROOT = "vision/cn_1920x1080"

class TemplateRepository(
    context: Context,
    private val openCvReady: Boolean,
) : AutoCloseable {
    private val assets = context.applicationContext.assets
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    val config: VisionConfig by lazy {
        assets.open("$ASSET_ROOT/regions.json").bufferedReader().use { reader ->
            json.decodeFromString<VisionConfig>(reader.readText())
        }
    }

    private val templatesDelegate = lazy {
        buildMap {
            config.templates.forEach { template ->
                loadTemplate(template)?.let { put(template.id, it) }
            }
        }
    }
    private val templates: Map<String, Mat> by templatesDelegate
    private val scaledTemplates = ConcurrentHashMap<ScaledTemplateKey, Mat>()

    fun template(id: String): Mat? = templates[id]

    fun template(
        id: String,
        scale: Double,
    ): Mat? {
        val original = template(id) ?: return null
        val width = (original.cols() * scale).roundToInt().coerceAtLeast(1)
        val height = (original.rows() * scale).roundToInt().coerceAtLeast(1)
        if (width == original.cols() && height == original.rows()) return original
        val key = ScaledTemplateKey(id, width, height)
        return scaledTemplates.computeIfAbsent(key) {
            Mat().also { scaled ->
                Imgproc.resize(
                    original,
                    scaled,
                    Size(width.toDouble(), height.toDouble()),
                    0.0,
                    0.0,
                    if (scale >= 1.0) Imgproc.INTER_CUBIC else Imgproc.INTER_AREA,
                )
            }
        }
    }

    fun health(): VisionHealth {
        val requiredIds = config.templates.filter(TemplateConfig::required).map(TemplateConfig::id)
        return health(requiredIds)
    }

    fun health(requiredIds: Collection<String>): VisionHealth {
        val missing = requiredIds.filterNot(templates::containsKey)
        return VisionHealth(
            openCvReady = openCvReady,
            loadedTemplates = templates.size,
            requiredTemplates = requiredIds.size,
            missingTemplateIds = missing,
        )
    }

    private fun loadTemplate(definition: TemplateConfig): Mat? {
        if (!openCvReady) return null
        return try {
            val bytes = assets.open("$ASSET_ROOT/${definition.file}").use { it.readBytes() }
            val encoded = MatOfByte(*bytes)
            try {
                val decoded = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_COLOR)
                if (decoded.empty()) {
                    decoded.release()
                    null
                } else {
                    decoded
                }
            } finally {
                encoded.release()
            }
        } catch (_: FileNotFoundException) {
            null
        }
    }

    override fun close() {
        scaledTemplates.values.forEach(Mat::release)
        scaledTemplates.clear()
        if (templatesDelegate.isInitialized()) {
            templates.values.forEach(Mat::release)
        }
    }

    private data class ScaledTemplateKey(
        val id: String,
        val width: Int,
        val height: Int,
    )
}
