package com.e7orbit.vision

import com.e7orbit.automation.GlobalUiVision
import com.e7orbit.automation.ShopVision
import com.e7orbit.automation.VisionHealth
import com.e7orbit.logging.NoOpOrbitLogger
import com.e7orbit.logging.OrbitLogger
import com.e7orbit.model.GameLocation
import com.e7orbit.model.ItemType
import com.e7orbit.model.MatchResult
import com.e7orbit.model.PurchaseTarget
import com.e7orbit.model.RunConfig
import com.e7orbit.model.ScreenFrame
import com.e7orbit.model.ShopPage
import com.e7orbit.model.VisualAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.min

class OpenCvShopVision(
    private val repository: TemplateRepository,
    private val logger: OrbitLogger = NoOpOrbitLogger,
) : ShopVision, GlobalUiVision {
    private val matcher = OpenCvTemplateMatcher(repository, logger, "vision")

    override fun health(): VisionHealth = repository.health(TemplateRequirements.SECRET_SHOP)

    override fun navigationHealth(): VisionHealth =
        repository.health(TemplateRequirements.GLOBAL_NAVIGATION)

    override suspend fun detectLocation(frame: ScreenFrame): GameLocation =
        withSource(frame) { source ->
            val lobby = matcher.bestMatch(source, TemplateIds.GLOBAL_LOBBY_ANCHOR)
            val location = if (lobby.matched) GameLocation.LOBBY else GameLocation.UNKNOWN
            logger.debug(
                "vision.global_location",
                "sequence" to frame.sequence,
                "location" to location,
                "lobby" to lobby.confidence,
            )
            location
        }

    override suspend fun detectPage(frame: ScreenFrame): ShopPage = withSource(frame) { source ->
        val resource = matcher.bestMatch(source, TemplateIds.RESOURCE_INSUFFICIENT)
        val purchase = matcher.bestMatch(source, TemplateIds.CONFIRM_PURCHASE)
        val refresh = matcher.bestMatch(source, TemplateIds.REFRESH_DIALOG)
        val shop = matcher.bestMatch(source, TemplateIds.SHOP_ANCHOR)
        val lobby = matcher.bestMatch(source, TemplateIds.SHOP_LOBBY_SECRET_SHOP)
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
        val targets = withSource(frame) { source ->
            val buttons = matcher.allMatches(
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
                    matcher.allMatches(source, TemplateIds.COVENANT_ITEM, config.matchThreshold)
                        .forEach { add(ItemType.COVENANT_BOOKMARK to it) }
                }
                if (config.buyMysticMedals) {
                    matcher.allMatches(source, TemplateIds.MYSTIC_ITEM, config.matchThreshold)
                        .forEach { add(ItemType.MYSTIC_MEDAL to it) }
                }
            }

            val rowPairTolerance = ROW_PAIR_TOLERANCE_PX * matcher.geometry(source).scale
            val rowBucketHeight = (ROW_BUCKET_HEIGHT_PX * matcher.geometry(source).scale)
                .coerceAtLeast(1.0)
            val targets = items
                .sortedBy { it.second.bounds?.top }
                .mapNotNull { (type, itemMatch) ->
                    val itemBounds = itemMatch.bounds ?: return@mapNotNull null
                    val itemCenterY = itemBounds.center.y
                    val button = buttons
                        .filter { it.bounds != null }
                        .minByOrNull { abs(it.bounds!!.center.y - itemCenterY) }
                        ?.takeIf {
                            abs(it.bounds!!.center.y - itemCenterY) <= rowPairTolerance
                        }
                        ?: return@mapNotNull null

                    PurchaseTarget(
                        type = type,
                        itemBounds = itemBounds,
                        purchaseButtonBounds = button.bounds!!,
                        confidence = min(itemMatch.confidence, button.confidence),
                        rowIndex = (itemCenterY / rowBucketHeight).toInt(),
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
        return targets
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
            matcher.bestMatch(source, template)
        }
        logger.info(
            "vision.purchase_verification",
            "sequence" to frame.sequence,
            "type" to target.type,
            "matched" to match.matched,
            "confidence" to match.confidence,
        )
        return match
    }

    override suspend fun findAction(
        frame: ScreenFrame,
        action: VisualAction,
    ): MatchResult {
        val match = withSource(frame) { source ->
            when (action) {
                VisualAction.OPEN_MENU -> matcher.bestOf(
                    source,
                    TemplateIds.GLOBAL_MENU_BUTTON,
                    TemplateIds.GLOBAL_MENU_BUTTON_PLAIN,
                )

                VisualAction.RETURN_TO_LOBBY ->
                    matcher.bestMatch(source, TemplateIds.GLOBAL_RETURN_TO_LOBBY)

                VisualAction.OPEN_SECRET_SHOP ->
                    matcher.bestMatch(source, TemplateIds.SHOP_LOBBY_SECRET_SHOP)

                VisualAction.CONFIRM_PURCHASE ->
                    matcher.bestMatch(source, TemplateIds.CONFIRM_PURCHASE)

                VisualAction.REFRESH_SHOP ->
                    matcher.bestMatch(source, TemplateIds.REFRESH_BUTTON)
                VisualAction.CONFIRM_REFRESH ->
                    matcher.bestMatch(source, TemplateIds.CONFIRM_REFRESH)
                VisualAction.DISMISS_ERROR ->
                    matcher.bestMatch(source, TemplateIds.RESOURCE_INSUFFICIENT)

                else -> MatchResult(matched = false)
            }
        }
        logger.debug(
            "vision.action",
            "sequence" to frame.sequence,
            "action" to action,
            "matched" to match.matched,
            "confidence" to match.confidence,
            "center" to match.center?.let { "${it.x},${it.y}" },
        )
        return match
    }

    private suspend fun <T> withSource(
        frame: ScreenFrame,
        block: (Mat) -> T,
    ): T = withContext(Dispatchers.Default) {
        val bitmap = requireNotNull(frame.bitmap) { "截图不包含 Bitmap" }

        val rgba = Mat()
        val bgr = Mat()
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
            logger.debug(
                "vision.frame",
                "sequence" to frame.sequence,
                "source" to "${bgr.cols()}x${bgr.rows()}",
                "templateScale" to matcher.geometry(bgr).scale,
            )
            block(bgr)
        } finally {
            rgba.release()
            bgr.release()
        }
    }

    private companion object {
        const val ROW_PAIR_TOLERANCE_PX = 55
        const val ROW_BUCKET_HEIGHT_PX = 100
    }
}
