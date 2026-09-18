package com.e7orbit.vision

import com.e7orbit.logging.OrbitLogger
import com.e7orbit.model.MatchResult
import com.e7orbit.model.ScreenRect
import kotlin.math.max
import kotlin.math.min
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

internal class OpenCvTemplateMatcher(
    private val repository: TemplateRepository,
    private val logger: OrbitLogger,
    private val logPrefix: String,
) {
    fun geometry(source: Mat): VisionGeometry = VisionGeometry(
        referenceWidth = repository.config.referenceWidth,
        referenceHeight = repository.config.referenceHeight,
        frameWidth = source.cols(),
        frameHeight = source.rows(),
    )

    fun bestMatch(
        source: Mat,
        templateId: String,
        thresholdOverride: Double? = null,
    ): MatchResult {
        val definition = repository.definition(templateId)
            ?: return MatchResult(matched = false)
        val geometry = geometry(source)
        val template = repository.template(templateId, geometry)
            ?: return MatchResult(matched = false)
        val region = geometry.mapRegion(
            region = definition.region,
            horizontalAnchor = definition.horizontalAnchor,
            verticalAnchor = definition.verticalAnchor,
        )
        val threshold = definition.resolveThreshold(thresholdOverride)
        val primary = matchOne(source, template, region, threshold, definition)
        logMatch(templateId, primary, threshold, geometry.scale, region)
        if (
            primary.matched ||
            region.isFullFrame(source) ||
            definition.matchMode == MatchMode.MASKED
        ) {
            return primary
        }

        logger.debug(
            "$logPrefix.template_full_frame_fallback",
            "template" to templateId,
            "primaryScore" to primary.confidence,
        )
        return matchOne(
            source = source,
            template = template,
            region = ScreenRect(0, 0, source.cols(), source.rows()),
            threshold = threshold,
            definition = definition,
        ).also { fallback ->
            logMatch(
                templateId,
                fallback,
                threshold,
                geometry.scale,
                ScreenRect(0, 0, source.cols(), source.rows()),
            )
        }
    }

    fun allMatches(
        source: Mat,
        templateId: String,
        thresholdOverride: Double? = null,
        maxMatchesOverride: Int? = null,
    ): List<MatchResult> {
        val definition = repository.definition(templateId)
            ?: return emptyList()
        val geometry = geometry(source)
        val template = repository.template(templateId, geometry)
            ?: return emptyList()
        val region = geometry.mapRegion(
            region = definition.region,
            horizontalAnchor = definition.horizontalAnchor,
            verticalAnchor = definition.verticalAnchor,
        )
        val threshold = definition.resolveThreshold(thresholdOverride)
        val maxMatches = maxMatchesOverride ?: definition.maxMatches
        val primary = matchMany(
            source,
            template,
            region,
            threshold,
            maxMatches,
            templateId,
            definition,
        )
        if (
            primary.isNotEmpty() ||
            region.isFullFrame(source) ||
            definition.matchMode == MatchMode.MASKED
        ) {
            return primary
        }
        logger.debug(
            "$logPrefix.template_full_frame_fallback",
            "template" to templateId,
            "primaryMatches" to primary.size,
        )
        return matchMany(
            source = source,
            template = template,
            region = ScreenRect(0, 0, source.cols(), source.rows()),
            threshold = threshold,
            maxMatches = maxMatches,
            templateId = templateId,
            definition = definition,
        )
    }

    private fun matchOne(
        source: Mat,
        template: ScaledTemplate,
        region: ScreenRect,
        threshold: Double,
        definition: TemplateConfig,
    ): MatchResult {
        if (region.width < template.bgr.cols() || region.height < template.bgr.rows()) {
            return MatchResult(matched = false)
        }
        val sourceRegion = ensureBgr(
            source.submat(Rect(region.left, region.top, region.width, region.height)),
        )
        val result = Mat(
            sourceRegion.rows() - template.bgr.rows() + 1,
            sourceRegion.cols() - template.bgr.cols() + 1,
            CvType.CV_32FC1,
        )
        return try {
            matchTemplate(sourceRegion, template, result, definition.matchMode)
            val minMax = Core.minMaxLoc(result)
            val left = region.left + minMax.maxLoc.x.toInt()
            val top = region.top + minMax.maxLoc.y.toInt()
            MatchResult(
                matched = minMax.maxVal >= threshold,
                confidence = definition.reportConfidence(minMax.maxVal),
                bounds = ScreenRect(
                    left = left,
                    top = top,
                    right = left + template.bgr.cols(),
                    bottom = top + template.bgr.rows(),
                ),
            )
        } finally {
            result.release()
            sourceRegion.release()
        }
    }

    private fun matchMany(
        source: Mat,
        template: ScaledTemplate,
        region: ScreenRect,
        threshold: Double,
        maxMatches: Int,
        templateId: String,
        definition: TemplateConfig,
    ): List<MatchResult> {
        if (region.width < template.bgr.cols() || region.height < template.bgr.rows()) {
            return emptyList()
        }
        val sourceRegion = ensureBgr(
            source.submat(Rect(region.left, region.top, region.width, region.height)),
        )
        val result = Mat(
            sourceRegion.rows() - template.bgr.rows() + 1,
            sourceRegion.cols() - template.bgr.cols() + 1,
            CvType.CV_32FC1,
        )
        val matches = mutableListOf<MatchResult>()
        try {
            matchTemplate(sourceRegion, template, result, definition.matchMode)
            var matchCount = 0
            while (matchCount < maxMatches) {
                val minMax = Core.minMaxLoc(result)
                if (matchCount == 0) {
                    logger.debug(
                        "$logPrefix.template_candidates",
                        "template" to templateId,
                        "bestScore" to minMax.maxVal,
                        "threshold" to threshold,
                        "maxMatches" to maxMatches,
                        "region" to region,
                    )
                }
                if (minMax.maxVal < threshold) break

                val left = region.left + minMax.maxLoc.x.toInt()
                val top = region.top + minMax.maxLoc.y.toInt()
                matches += MatchResult(
                    matched = true,
                    confidence = definition.reportConfidence(minMax.maxVal),
                    bounds = ScreenRect(
                        left = left,
                        top = top,
                        right = left + template.bgr.cols(),
                        bottom = top + template.bgr.rows(),
                    ),
                )

                val suppressLeft = max(0, minMax.maxLoc.x.toInt() - template.bgr.cols() / 2)
                val suppressTop = max(0, minMax.maxLoc.y.toInt() - template.bgr.rows() / 2)
                val suppressRight = min(
                    result.cols() - 1,
                    minMax.maxLoc.x.toInt() + template.bgr.cols() / 2,
                )
                val suppressBottom = min(
                    result.rows() - 1,
                    minMax.maxLoc.y.toInt() + template.bgr.rows() / 2,
                )
                Imgproc.rectangle(
                    result,
                    Point(suppressLeft.toDouble(), suppressTop.toDouble()),
                    Point(suppressRight.toDouble(), suppressBottom.toDouble()),
                    Scalar(-1.0),
                    Imgproc.FILLED,
                )
                matchCount += 1
            }
        } finally {
            result.release()
            sourceRegion.release()
        }
        return matches.sortedByDescending(MatchResult::confidence)
    }

    private fun matchTemplate(
        sourceRegion: Mat,
        template: ScaledTemplate,
        result: Mat,
        matchMode: MatchMode,
    ) {
        val mask = template.mask
        if (matchMode == MatchMode.MASKED && mask != null) {
            Imgproc.matchTemplate(
                sourceRegion,
                template.bgr,
                result,
                Imgproc.TM_CCORR_NORMED,
                mask,
            )
        } else {
            Imgproc.matchTemplate(
                sourceRegion,
                template.bgr,
                result,
                Imgproc.TM_CCOEFF_NORMED,
            )
        }
    }

    private fun ensureBgr(source: Mat): Mat {
        if (source.channels() == 3) return source
        val bgr = Mat()
        when (source.channels()) {
            1 -> Imgproc.cvtColor(source, bgr, Imgproc.COLOR_GRAY2BGR)
            4 -> Imgproc.cvtColor(source, bgr, Imgproc.COLOR_BGRA2BGR)
            else -> source.copyTo(bgr)
        }
        source.release()
        return bgr
    }

    private fun ScreenRect.isFullFrame(source: Mat): Boolean =
        left == 0 && top == 0 && right == source.cols() && bottom == source.rows()

    private fun logMatch(
        templateId: String,
        match: MatchResult,
        threshold: Double,
        scale: Double,
        region: ScreenRect,
    ) {
        logger.debug(
            "$logPrefix.template",
            "template" to templateId,
            "score" to match.confidence,
            "threshold" to threshold,
            "matched" to match.matched,
            "scale" to scale,
            "region" to region,
            "bounds" to match.bounds,
        )
    }

    fun bestOf(
        source: Mat,
        vararg templateIds: String,
    ): MatchResult = templateIds
        .map { templateId -> bestMatch(source, templateId) }
        .maxByOrNull { match -> match.confidence }
        ?: MatchResult(matched = false)

    fun anyMatch(
        source: Mat,
        vararg templateIds: String,
    ): Boolean = templateIds.any { templateId ->
        bestMatch(source, templateId).matched
    }
}
