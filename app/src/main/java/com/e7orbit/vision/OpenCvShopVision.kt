package com.e7orbit.vision

import com.e7orbit.automation.GlobalUiVision
import com.e7orbit.automation.ShopVision
import com.e7orbit.automation.VisionHealth
import com.e7orbit.logging.NoOpOrbitLogger
import com.e7orbit.logging.OrbitLogger
import com.e7orbit.model.GameLocation
import com.e7orbit.model.GlobalAction
import com.e7orbit.model.ItemType
import com.e7orbit.model.MatchResult
import com.e7orbit.model.PurchaseTarget
import com.e7orbit.model.RunConfig
import com.e7orbit.model.ScreenFrame
import com.e7orbit.model.ScreenPoint
import com.e7orbit.model.ScreenRect
import com.e7orbit.model.ShopAction
import com.e7orbit.model.ShopPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class OpenCvShopVision(
    private val repository: TemplateRepository,
    private val logger: OrbitLogger = NoOpOrbitLogger,
) : ShopVision, GlobalUiVision {

    override fun health(): VisionHealth = repository.health(TemplateRequirements.SECRET_SHOP)

    override fun navigationHealth(): VisionHealth =
        repository.health(TemplateRequirements.GLOBAL_NAVIGATION)

    override suspend fun detectLocation(frame: ScreenFrame): GameLocation =
        withSource(frame) { source ->
            val lobby = bestMatch(source, TemplateIds.GLOBAL_LOBBY_ANCHOR)
            val location = if (lobby.matched) GameLocation.LOBBY else GameLocation.UNKNOWN
            logger.debug(
                "vision.global_location",
                "sequence" to frame.sequence,
                "location" to location,
                "lobby" to lobby.confidence,
            )
            location
        }

    override suspend fun findGlobalAction(
        frame: ScreenFrame,
        action: GlobalAction,
    ): MatchResult {
        val match = withSource(frame) { source ->
            when (action) {
                GlobalAction.OPEN_MENU -> bestOf(
                    source,
                    TemplateIds.GLOBAL_MENU_BUTTON,
                    TemplateIds.GLOBAL_MENU_BUTTON_PLAIN,
                )

                GlobalAction.RETURN_TO_LOBBY ->
                    bestMatch(source, TemplateIds.GLOBAL_RETURN_TO_LOBBY)
            }
        }
        val scaled = match.scaleToFrame(frame)
        logger.debug(
            "vision.global_action",
            "sequence" to frame.sequence,
            "action" to action,
            "matched" to scaled.matched,
            "confidence" to scaled.confidence,
            "center" to scaled.center?.let { "${it.x},${it.y}" },
        )
        return scaled
    }

    override suspend fun detectPage(frame: ScreenFrame): ShopPage = withSource(frame) { source ->
        val resource = bestMatch(source, TemplateIds.RESOURCE_INSUFFICIENT)
        val purchase = bestMatch(source, TemplateIds.CONFIRM_PURCHASE)
        val refresh = bestMatch(source, TemplateIds.REFRESH_DIALOG)
        val shop = bestMatch(source, TemplateIds.SHOP_ANCHOR)
        val lobby = bestMatch(source, TemplateIds.SHOP_LOBBY_SECRET_SHOP)
        val page = when {
            resource.matched ->
                ShopPage.RESOURCE_INSUFFICIENT

            purchase.matched ->
                ShopPage.PURCHASE_CONFIRMATION

            refresh.matched ->
                ShopPage.REFRESH_CONFIRMATION

            shop.matched ->
                ShopPage.SHOP

            lobby.matched ->
                ShopPage.LOBBY

            else -> ShopPage.UNKNOWN
        }
        logger.debug(
            "vision.page",
            "sequence" to frame.sequence,
            "page" to page,
            "shop" to shop.confidence,
            "lobby" to lobby.confidence,
            "purchase" to purchase.confidence,
            "refresh" to refresh.confidence,
            "resource" to resource.confidence,
        )
        page
    }

    override suspend fun findTargets(
        frame: ScreenFrame,
        config: RunConfig,
    ): List<PurchaseTarget> {
        val normalizedTargets = withSource(frame) { source ->
            val buttons = allMatches(
                source = source,
                templateId = TemplateIds.PURCHASE_BUTTON,
                thresholdOverride = config.matchThreshold,
            )
            if (buttons.isEmpty()) {
                logger.warn(
                    "vision.targets.no_purchase_button",
                    "sequence" to frame.sequence,
                    "threshold" to config.matchThreshold,
                )
                return@withSource emptyList()
            }

            val items = buildList {
                if (config.buyCovenantBookmarks) {
                    allMatches(source, TemplateIds.COVENANT_ITEM, config.matchThreshold)
                        .forEach { add(ItemType.COVENANT_BOOKMARK to it) }
                }
                if (config.buyMysticMedals) {
                    allMatches(source, TemplateIds.MYSTIC_ITEM, config.matchThreshold)
                        .forEach { add(ItemType.MYSTIC_MEDAL to it) }
                }
            }

            val targets = items
                .sortedBy { it.second.bounds?.top }
                .mapNotNull { (type, itemMatch) ->
                    val itemBounds = itemMatch.bounds ?: return@mapNotNull null
                    val itemCenterY = itemBounds.center.y
                    val button = buttons
                        .filter { it.bounds != null }
                        .minByOrNull { abs(it.bounds!!.center.y - itemCenterY) }
                        ?.takeIf {
                            abs(it.bounds!!.center.y - itemCenterY) <= ROW_PAIR_TOLERANCE_PX
                        }
                        ?: return@mapNotNull null

                    PurchaseTarget(
                        type = type,
                        itemBounds = itemBounds,
                        purchaseButton = button.bounds!!.center,
                        confidence = min(itemMatch.confidence, button.confidence),
                        rowIndex = itemCenterY / ROW_BUCKET_HEIGHT_PX,
                    )
                }
                .distinctBy { it.type to it.rowIndex }
            logger.debug(
                "vision.targets",
                "sequence" to frame.sequence,
                "buttonCount" to buttons.size,
                "itemCount" to items.size,
                "targetCount" to targets.size,
                "targets" to targets.joinToString {
                    "${it.type.name}:${it.confidence}@${it.purchaseButton.x},${it.purchaseButton.y}"
                },
            )
            targets
        }
        return normalizedTargets.map { target ->
            target.copy(
                itemBounds = target.itemBounds.scaleToFrame(frame),
                purchaseButton = target.purchaseButton.scaleToFrame(frame),
            )
        }
    }

    override suspend fun verifyPurchase(
        frame: ScreenFrame,
        target: PurchaseTarget,
    ): MatchResult {
        val match = withSource(frame) { source ->
            val template = when (target.type) {
                ItemType.COVENANT_BOOKMARK -> TemplateIds.COVENANT_CONFIRM
                ItemType.MYSTIC_MEDAL -> TemplateIds.MYSTIC_CONFIRM
            }
            bestMatch(source, template)
        }
        logger.info(
            "vision.purchase_verification",
            "sequence" to frame.sequence,
            "type" to target.type,
            "matched" to match.matched,
            "confidence" to match.confidence,
        )
        return match.scaleToFrame(frame)
    }

    override suspend fun findAction(
        frame: ScreenFrame,
        action: ShopAction,
    ): MatchResult {
        val match = withSource(frame) { source ->
            when (action) {
                ShopAction.OPEN_SECRET_SHOP ->
                    bestMatch(source, TemplateIds.SHOP_LOBBY_SECRET_SHOP)

                ShopAction.CONFIRM_PURCHASE ->
                    bestMatch(source, TemplateIds.CONFIRM_PURCHASE)

                ShopAction.REFRESH -> bestMatch(source, TemplateIds.REFRESH_BUTTON)
                ShopAction.CONFIRM_REFRESH -> bestMatch(source, TemplateIds.CONFIRM_REFRESH)
                ShopAction.DISMISS_ERROR ->
                    bestMatch(source, TemplateIds.RESOURCE_INSUFFICIENT)
            }
        }
        val scaled = match.scaleToFrame(frame)
        logger.debug(
            "vision.action",
            "sequence" to frame.sequence,
            "action" to action,
            "matched" to scaled.matched,
            "confidence" to scaled.confidence,
            "center" to scaled.center?.let { "${it.x},${it.y}" },
        )
        return scaled
    }

    private suspend fun <T> withSource(
        frame: ScreenFrame,
        block: (Mat) -> T,
    ): T = withContext(Dispatchers.Default) {
        val bitmap = requireNotNull(frame.bitmap) { "截图不包含 Bitmap" }

        val rgba = Mat()
        val bgr = Mat()
        val normalized = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            val mean = Core.mean(bgr)
            logger.debug(
                "vision.frame_pixels",
                "sequence" to frame.sequence,
                "meanB" to mean.`val`[0],
                "meanG" to mean.`val`[1],
                "meanR" to mean.`val`[2],
            )
            if (
                bgr.cols() == repository.config.referenceWidth &&
                bgr.rows() == repository.config.referenceHeight
            ) {
                logger.debug(
                    "vision.frame",
                    "sequence" to frame.sequence,
                    "source" to "${bgr.cols()}x${bgr.rows()}",
                    "normalized" to false,
                )
                block(bgr)
            } else {
                Imgproc.resize(
                    bgr,
                    normalized,
                    Size(
                        repository.config.referenceWidth.toDouble(),
                        repository.config.referenceHeight.toDouble(),
                    ),
                    0.0,
                    0.0,
                    Imgproc.INTER_AREA,
                )
                logger.debug(
                    "vision.frame",
                    "sequence" to frame.sequence,
                    "source" to "${bgr.cols()}x${bgr.rows()}",
                    "target" to "${normalized.cols()}x${normalized.rows()}",
                    "normalized" to true,
                )
                block(normalized)
            }
        } finally {
            rgba.release()
            bgr.release()
            normalized.release()
        }
    }

    private fun bestMatch(
        source: Mat,
        templateId: String,
        thresholdOverride: Double? = null,
    ): MatchResult {
        val definition = repository.config.template(templateId)
            ?: return MatchResult(matched = false)
        val template = repository.template(templateId)
            ?: return MatchResult(matched = false)
        val region = clampRegion(definition.region.toScreenRect(), source.cols(), source.rows())
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
        val threshold = thresholdOverride?.let { max(it, definition.threshold) }
            ?: definition.threshold
        return try {
            Imgproc.matchTemplate(sourceRegion, template, result, Imgproc.TM_CCOEFF_NORMED)
            val minMax = Core.minMaxLoc(result)
            val left = region.left + minMax.maxLoc.x.toInt()
            val top = region.top + minMax.maxLoc.y.toInt()
            val match = MatchResult(
                matched = minMax.maxVal >= threshold,
                confidence = minMax.maxVal,
                bounds = ScreenRect(
                    left = left,
                    top = top,
                    right = left + template.cols(),
                    bottom = top + template.rows(),
                ),
            )
            logger.debug(
                "vision.template",
                "template" to templateId,
                "score" to match.confidence,
                "threshold" to threshold,
                "matched" to match.matched,
                "bounds" to "${left},${top},${template.cols()},${template.rows()}",
            )
            match
        } finally {
            result.release()
            sourceRegion.release()
        }
    }

    private fun bestOf(
        source: Mat,
        vararg templateIds: String,
    ): MatchResult = templateIds
        .map { templateId -> bestMatch(source, templateId) }
        .maxByOrNull { match -> match.confidence }
        ?: MatchResult(matched = false)

    private fun allMatches(
        source: Mat,
        templateId: String,
        thresholdOverride: Double? = null,
        maxMatchesOverride: Int? = null,
    ): List<MatchResult> {
        val definition = repository.config.template(templateId)
            ?: return emptyList()
        val template = repository.template(templateId)
            ?: return emptyList()
        val region = clampRegion(definition.region.toScreenRect(), source.cols(), source.rows())
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
        val threshold = thresholdOverride?.let { max(it, definition.threshold) }
            ?: definition.threshold
        val maxMatches = maxMatchesOverride ?: definition.maxMatches

        try {
            Imgproc.matchTemplate(sourceRegion, template, result, Imgproc.TM_CCOEFF_NORMED)
            var matchCount = 0
            while (matchCount < maxMatches) {
                val minMax = Core.minMaxLoc(result)
                if (matchCount == 0) {
                    logger.debug(
                        "vision.template_candidates",
                        "template" to templateId,
                        "bestScore" to minMax.maxVal,
                        "threshold" to threshold,
                        "maxMatches" to maxMatches,
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

    private fun clampRegion(
        region: ScreenRect,
        width: Int,
        height: Int,
    ): ScreenRect = ScreenRect(
        left = region.left.coerceIn(0, width - 1),
        top = region.top.coerceIn(0, height - 1),
        right = region.right.coerceIn(1, width),
        bottom = region.bottom.coerceIn(1, height),
    )

    private fun ScreenRect.scaleToFrame(frame: ScreenFrame): ScreenRect {
        val scaleX = frame.width.toDouble() / repository.config.referenceWidth
        val scaleY = frame.height.toDouble() / repository.config.referenceHeight
        return ScreenRect(
            left = (left * scaleX).roundToInt(),
            top = (top * scaleY).roundToInt(),
            right = (right * scaleX).roundToInt(),
            bottom = (bottom * scaleY).roundToInt(),
        )
    }

    private fun ScreenPoint.scaleToFrame(frame: ScreenFrame): ScreenPoint {
        val scaleX = frame.width.toDouble() / repository.config.referenceWidth
        val scaleY = frame.height.toDouble() / repository.config.referenceHeight
        return ScreenPoint(
            x = (x * scaleX).roundToInt(),
            y = (y * scaleY).roundToInt(),
        )
    }

    private fun MatchResult.scaleToFrame(frame: ScreenFrame): MatchResult =
        copy(bounds = bounds?.scaleToFrame(frame))

    private companion object {
        const val ROW_PAIR_TOLERANCE_PX = 55
        const val ROW_BUCKET_HEIGHT_PX = 100
    }
}
