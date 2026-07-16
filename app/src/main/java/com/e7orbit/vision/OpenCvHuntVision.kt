package com.e7orbit.vision

import com.e7orbit.automation.HuntVision
import com.e7orbit.automation.VisionHealth
import com.e7orbit.logging.NoOpOrbitLogger
import com.e7orbit.logging.OrbitLogger
import com.e7orbit.model.HuntDungeon
import com.e7orbit.model.HuntPage
import com.e7orbit.model.MatchResult
import com.e7orbit.model.ScreenFrame
import com.e7orbit.model.ScreenRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

class OpenCvHuntVision(
    private val repository: TemplateRepository,
    private val logger: OrbitLogger = NoOpOrbitLogger,
) : HuntVision {
    override fun health(): VisionHealth = repository.health(REQUIRED_TEMPLATE_IDS)

    override suspend fun detectPage(frame: ScreenFrame): HuntPage = withSource(frame) { source ->
        val page = when {
            bestMatch(source, TemplateIds.HUNT_MANAGED_COMPLETE).matched ->
                HuntPage.MANAGED_COMPLETE

            bestMatch(source, TemplateIds.HUNT_MANAGED_PANEL).matched ->
                HuntPage.MANAGED_PANEL

            bestMatch(source, TemplateIds.HUNT_DELEGATE_CONFIRM).matched ->
                HuntPage.DELEGATION_CONFIRMATION

            bestMatch(source, TemplateIds.HUNT_BATTLE_CONTROLS).matched ->
                HuntPage.BATTLE_CONTROLS

            bestMatch(source, TemplateIds.HUNT_QUICK_BATTLE).matched ->
                HuntPage.TEAM_QUICK_BATTLE

            bestMatch(source, TemplateIds.HUNT_TEAM_READY).matched ->
                HuntPage.TEAM_READY

            bestMatch(source, TemplateIds.HUNT_SELECTION).matched ->
                HuntPage.HUNT_SELECTION

            anyMatch(
                source,
                TemplateIds.HUNT_BATTLE_MENU,
                TemplateIds.HUNT_BATTLE_MENU_EVENT,
            ) ->
                HuntPage.BATTLE_MENU

            bestMatch(source, TemplateIds.HUNT_MANAGED_STATUS).matched ->
                HuntPage.LOBBY_MANAGED

            anyMatch(
                source,
                TemplateIds.HUNT_LOBBY_BATTLE,
                TemplateIds.HUNT_LOBBY_BATTLE_EVENT,
            ) ->
                HuntPage.LOBBY

            else -> HuntPage.UNKNOWN
        }
        logger.debug(
            "hunt.vision.page",
            "sequence" to frame.sequence,
            "page" to page,
        )
        page
    }

    override suspend fun isManagedBattleEnabled(frame: ScreenFrame): Boolean =
        withSource(frame) { source ->
            bestMatch(source, TemplateIds.HUNT_REPEAT_ENABLED).matched
        }

    override suspend fun findDungeon(
        frame: ScreenFrame,
        dungeon: HuntDungeon,
    ): MatchResult = withSource(frame) { source ->
        bestMatch(
            source,
            when (dungeon) {
                HuntDungeon.WYVERN -> TemplateIds.HUNT_DUNGEON_WYVERN
                HuntDungeon.GOLEM -> TemplateIds.HUNT_DUNGEON_GOLEM
                HuntDungeon.BANSHEE -> TemplateIds.HUNT_DUNGEON_BANSHEE
                HuntDungeon.AZIMANAK -> TemplateIds.HUNT_DUNGEON_AZIMANAK
                HuntDungeon.CAIDES -> TemplateIds.HUNT_DUNGEON_CAIDES
            },
        )
    }

    override suspend fun managedProgressSignature(frame: ScreenFrame): Long =
        withSource(frame) { source ->
            val progressRegion = source.submat(Rect(466, 372, 30, 30))
            val gray = Mat()
            val binary = Mat()
            try {
                Imgproc.cvtColor(progressRegion, gray, Imgproc.COLOR_BGR2GRAY)
                Imgproc.threshold(
                    gray,
                    binary,
                    0.0,
                    255.0,
                    Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU,
                )
                val pixels = ByteArray((binary.total() * binary.channels()).toInt())
                binary.get(0, 0, pixels)
                pixels.fold(FNV_OFFSET_BASIS) { hash, pixel ->
                    (hash xor pixel.toUByte().toLong()) * FNV_PRIME
                }
            } finally {
                progressRegion.release()
                gray.release()
                binary.release()
            }
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
            if (
                bgr.cols() == repository.config.referenceWidth &&
                bgr.rows() == repository.config.referenceHeight
            ) {
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
    ): MatchResult {
        val definition = repository.config.template(templateId)
            ?: return MatchResult(matched = false)
        val template = repository.template(templateId)
            ?: return MatchResult(matched = false)
        val region = definition.region.toScreenRect().clamp(source.cols(), source.rows())
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
                matched = minMax.maxVal >= definition.threshold,
                confidence = minMax.maxVal,
                bounds = ScreenRect(
                    left = left,
                    top = top,
                    right = left + template.cols(),
                    bottom = top + template.rows(),
                ),
            ).also { match ->
                logger.debug(
                    "hunt.vision.template",
                    "template" to templateId,
                    "score" to match.confidence,
                    "threshold" to definition.threshold,
                    "matched" to match.matched,
                )
            }
        } finally {
            result.release()
            sourceRegion.release()
        }
    }

    private fun anyMatch(
        source: Mat,
        vararg templateIds: String,
    ): Boolean = templateIds.any { templateId ->
        bestMatch(source, templateId).matched
    }

    private fun ScreenRect.clamp(width: Int, height: Int): ScreenRect = ScreenRect(
        left = left.coerceIn(0, width - 1),
        top = top.coerceIn(0, height - 1),
        right = right.coerceIn(1, width),
        bottom = bottom.coerceIn(1, height),
    )

    private companion object {
        val REQUIRED_TEMPLATE_IDS = listOf(
            TemplateIds.HUNT_LOBBY_BATTLE,
            TemplateIds.HUNT_LOBBY_BATTLE_EVENT,
            TemplateIds.HUNT_BATTLE_MENU,
            TemplateIds.HUNT_BATTLE_MENU_EVENT,
            TemplateIds.HUNT_SELECTION,
            TemplateIds.HUNT_DUNGEON_WYVERN,
            TemplateIds.HUNT_DUNGEON_GOLEM,
            TemplateIds.HUNT_DUNGEON_BANSHEE,
            TemplateIds.HUNT_DUNGEON_AZIMANAK,
            TemplateIds.HUNT_DUNGEON_CAIDES,
            TemplateIds.HUNT_QUICK_BATTLE,
            TemplateIds.HUNT_TEAM_READY,
            TemplateIds.HUNT_REPEAT_ENABLED,
            TemplateIds.HUNT_BATTLE_CONTROLS,
            TemplateIds.HUNT_DELEGATE_CONFIRM,
            TemplateIds.HUNT_MANAGED_STATUS,
            TemplateIds.HUNT_MANAGED_COMPLETE,
            TemplateIds.HUNT_MANAGED_PANEL,
        )
        const val FNV_OFFSET_BASIS = -3750763034362895579L
        const val FNV_PRIME = 1099511628211L
    }
}
