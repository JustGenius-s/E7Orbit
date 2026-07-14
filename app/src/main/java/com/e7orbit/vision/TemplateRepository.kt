package com.e7orbit.vision

import android.content.Context
import com.e7orbit.automation.VisionHealth
import java.io.FileNotFoundException
import kotlinx.serialization.json.Json
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.imgcodecs.Imgcodecs

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

    fun template(id: String): Mat? = templates[id]

    fun health(): VisionHealth {
        val requiredIds = config.templates.filter(TemplateConfig::required).map(TemplateConfig::id)
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
        if (templatesDelegate.isInitialized()) {
            templates.values.forEach(Mat::release)
        }
    }
}
