package com.e7orbit.vision

import android.content.Context
import com.e7orbit.automation.VisionHealth
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

const val SHOP_ASSET_ROOT = "vision/shop"
const val HUNT_ASSET_ROOT = "vision/cn_1920x1080"

class TemplateRepository(
    context: Context,
    private val openCvReady: Boolean,
) : AutoCloseable {
    private val assets = context.applicationContext.assets
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    val shopConfig: VisionConfig by lazy { loadConfig(SHOP_ASSET_ROOT) }
    val huntConfig: VisionConfig by lazy { loadConfig(HUNT_ASSET_ROOT) }
    val config: VisionConfig get() = shopConfig

    private val templatesDelegate = lazy {
        buildMap {
            shopConfig.templates.forEach { template ->
                loadTemplate(SHOP_ASSET_ROOT, template)?.let { put(template.id, it) }
            }
            huntConfig.templates.forEach { template ->
                if (!containsKey(template.id)) {
                    loadTemplate(HUNT_ASSET_ROOT, template)?.let { put(template.id, it) }
                }
            }
        }
    }
    private val templates: Map<String, Mat> by templatesDelegate
    private val scaledTemplates = ConcurrentHashMap<ScaledTemplateKey, ScaledTemplate>()

    fun definition(id: String): TemplateConfig? =
        shopConfig.template(id) ?: huntConfig.template(id)

    fun template(id: String): Mat? = templates[id]

    internal fun template(
        id: String,
        geometry: VisionGeometry,
    ): ScaledTemplate? {
        val definition = definition(id) ?: return null
        val original = template(id) ?: return null
        val scale = definition.scaleFor(geometry, original.cols(), original.rows())
        val width = (original.cols() * scale).roundToInt().coerceAtLeast(1)
        val height = (original.rows() * scale).roundToInt().coerceAtLeast(1)
        val key = ScaledTemplateKey(id, width, height, definition.matchMode)
        return scaledTemplates.computeIfAbsent(key) {
            prepareTemplate(original, width, height, definition.matchMode)
        }
    }

    fun health(): VisionHealth {
        val requiredIds = shopConfig.templates.filter(TemplateConfig::required).map(TemplateConfig::id)
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

    private fun loadConfig(packRoot: String): VisionConfig =
        assets.open("$packRoot/regions.json").bufferedReader().use { reader ->
            json.decodeFromString<VisionConfig>(reader.readText())
        }

    private fun loadTemplate(
        packRoot: String,
        definition: TemplateConfig,
    ): Mat? {
        if (!openCvReady) return null
        return try {
            val bytes = assets.open("$packRoot/${definition.file}").use { it.readBytes() }
            val encoded = MatOfByte(*bytes)
            try {
                val decoded = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_UNCHANGED)
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

    private fun prepareTemplate(
        original: Mat,
        width: Int,
        height: Int,
        matchMode: MatchMode,
    ): ScaledTemplate {
        val scaled = Mat()
        Imgproc.resize(
            original,
            scaled,
            Size(width.toDouble(), height.toDouble()),
            0.0,
            0.0,
            if (width >= original.cols()) Imgproc.INTER_CUBIC else Imgproc.INTER_AREA,
        )
        return try {
            splitPrepared(scaled, matchMode)
        } finally {
            scaled.release()
        }
    }

    private fun splitPrepared(
        source: Mat,
        matchMode: MatchMode,
    ): ScaledTemplate {
        val bgr = Mat()
        when (source.channels()) {
            1 -> Imgproc.cvtColor(source, bgr, Imgproc.COLOR_GRAY2BGR)
            4 -> Imgproc.cvtColor(source, bgr, Imgproc.COLOR_BGRA2BGR)
            else -> source.copyTo(bgr)
        }
        val mask = if (matchMode == MatchMode.MASKED && source.channels() == 4) {
            val alpha = Mat()
            Core.extractChannel(source, alpha, 3)
            val bgrMask = Mat()
            Imgproc.cvtColor(alpha, bgrMask, Imgproc.COLOR_GRAY2BGR)
            alpha.release()
            bgrMask
        } else {
            null
        }
        return ScaledTemplate(bgr = bgr, mask = mask)
    }

    override fun close() {
        scaledTemplates.values.forEach(ScaledTemplate::release)
        scaledTemplates.clear()
        if (templatesDelegate.isInitialized()) {
            templates.values.forEach(Mat::release)
        }
    }

    private data class ScaledTemplateKey(
        val id: String,
        val width: Int,
        val height: Int,
        val matchMode: MatchMode,
    )
}

internal data class ScaledTemplate(
    val bgr: Mat,
    val mask: Mat?,
) {
    fun release() {
        bgr.release()
        mask?.release()
    }
}
