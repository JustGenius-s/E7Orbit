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
        val definition = repository.config.template(templateId)
            ?: return MatchResult(matched = false)
        val geometry = geometry(source)
        val template = repository.template(templateId, geometry.scale)
            ?: return MatchResult(matched = false)
        val region = geometry.mapRegion(
            region = definition.region,
            horizontalAnchor = definition.horizontalAnchor,
            verticalAnchor = definition.verticalAnchor,
        )
        val threshold = thresholdOverride?.let { max(it, definition.threshold) }
            ?: definition.threshold
        val primary = matchOne(source, template, region, threshold)
        logMatch(templateId, primary, threshold, geometry.scale, region)
        if (primary.matched || region.isFullFrame(source)) return primary

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

    private fun matchOne(
        source: Mat,
        template: Mat,
        region: ScreenRect,
        threshold: Double,
    ): MatchResult {
        if (region.width < template.cols() || region.height < template.rows()) {
            return MatchResult(matched = false)
        }
        val sourceRegion = source.submat(
            Rect(region.left, region.top, region.width, region.height),
        )
        val result = Mat(
            sourceRegion.rows() - template.rows() + 1,
            sourceRegion.cols() - template.cols() + 1,
            CvType.CV_32FC1,
        )
        return try {
            Imgproc.matchTemplate(sourceRegion, template, result, Imgproc.TM_CCOEFF_NORMED)
            val minMax = Core.minMaxLoc(result)
            val left = region.left + minMax.maxLoc.x.toInt()
            val top = region.top + minMax.maxLoc.y.toInt()
            MatchResult(
                matched = minMax.maxVal >= threshold,
                confidence = minMax.maxVal,
                bounds = ScreenRect(
                    left = left,
                    top = top,
                    right = left + template.cols(),
                    bottom = top + template.rows(),
                ),
            )
        } finally {
            result.release()
            sourceRegion.release()
        }
    }

    fun allMatches(
        source: Mat,
        templateId: String,
        thresholdOverride: Double? = null,
        maxMatchesOverride: Int? = null,
    ): List<MatchResult> {
        val definition = repository.config.template(templateId)
            ?: return emptyList()
        val geometry = geometry(source)
        val template = repository.template(templateId, geometry.scale)
            ?: return emptyList()
        val region = geometry.mapRegion(
            region = definition.region,
            horizontalAnchor = definition.horizontalAnchor,
            verticalAnchor = definition.verticalAnchor,
        )
        val threshold = thresholdOverride?.let { max(it, definition.threshold) }
            ?: definition.threshold
        val maxMatches = maxMatchesOverride ?: definition.maxMatches
        val primary = matchMany(source, template, region, threshold, maxMatches, templateId)
        if (primary.isNotEmpty() || region.isFullFrame(source)) return primary
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
        )
    }

    private fun matchMany(
        source: Mat,
        template: Mat,
        region: ScreenRect,
        threshold: Double,
        maxMatches: Int,
        templateId: String,
    ): List<MatchResult> {
        if (region.width < template.cols() || region.height < template.rows()) {
            return emptyList()
        }
        val sourceRegion = source.submat(
            Rect(region.left, region.top, region.width, region.height),
        )
        val result = Mat(
            sourceRegion.rows() - template.rows() + 1,
            sourceRegion.cols() - template.cols() + 1,
            CvType.CV_32FC1,
        )
        val matches = mutableListOf<MatchResult>()
        try {
            Imgproc.matchTemplate(sourceRegion, template, result, Imgproc.TM_CCOEFF_NORMED)
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
                    confidence = minMax.maxVal,
                    bounds = ScreenRect(
                        left = left,
                        top = top,
                        right = left + template.cols(),
                        bottom = top + template.rows(),
                    ),
                )

                val suppressLeft = max(0, minMax.maxLoc.x.toInt() - template.cols() / 2)
                val suppressTop = max(0, minMax.maxLoc.y.toInt() - template.rows() / 2)
                val suppressRight = min(
                    result.cols() - 1,
                    minMax.maxLoc.x.toInt() + template.cols() / 2,
                )
                val suppressBottom = min(
                    result.rows() - 1,
                    minMax.maxLoc.y.toInt() + template.rows() / 2,
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
