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
import com.e7orbit.model.VisualAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc

class OpenCvHuntVision(
    private val repository: TemplateRepository,
    private val logger: OrbitLogger = NoOpOrbitLogger,
) : HuntVision {
    private val matcher = OpenCvTemplateMatcher(repository, logger, "hunt.vision")
    override fun health(): VisionHealth = repository.health(REQUIRED_TEMPLATE_IDS)

    override suspend fun detectPage(frame: ScreenFrame): HuntPage = withSource(frame) { source ->
        val page = when {
            matcher.bestMatch(source, TemplateIds.HUNT_MANAGED_COMPLETE).matched ->
                HuntPage.MANAGED_COMPLETE

            matcher.bestMatch(source, TemplateIds.HUNT_MANAGED_PANEL).matched ->
                HuntPage.MANAGED_PANEL

            matcher.bestMatch(source, TemplateIds.HUNT_DELEGATE_CONFIRM).matched ->
                HuntPage.DELEGATION_CONFIRMATION

            matcher.bestMatch(source, TemplateIds.HUNT_BATTLE_CONTROLS).matched ->
                HuntPage.BATTLE_CONTROLS

            matcher.bestMatch(source, TemplateIds.HUNT_QUICK_BATTLE).matched ->
                HuntPage.TEAM_QUICK_BATTLE

            matcher.bestMatch(source, TemplateIds.HUNT_TEAM_READY).matched ->
                HuntPage.TEAM_READY

            matcher.bestMatch(source, TemplateIds.HUNT_SELECTION).matched ->
                HuntPage.HUNT_SELECTION

            matcher.anyMatch(
                source,
                TemplateIds.HUNT_BATTLE_MENU,
                TemplateIds.HUNT_BATTLE_MENU_EVENT,
            ) ->
                HuntPage.BATTLE_MENU

            matcher.bestMatch(source, TemplateIds.HUNT_MANAGED_STATUS).matched ->
                HuntPage.LOBBY_MANAGED

            matcher.anyMatch(
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
            matcher.bestMatch(source, TemplateIds.HUNT_REPEAT_ENABLED).matched
        }

    override suspend fun findDungeon(
        frame: ScreenFrame,
        dungeon: HuntDungeon,
    ): MatchResult = withSource(frame) { source ->
        matcher.bestMatch(
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

    override suspend fun findAction(
        frame: ScreenFrame,
        action: VisualAction,
    ): MatchResult = withSource(frame) { source ->
        val templateId = when (action) {
            VisualAction.HUNT_OPEN_BATTLE -> TemplateIds.HUNT_ACTION_OPEN_BATTLE
            VisualAction.HUNT_OPEN_SELECTION -> TemplateIds.HUNT_ACTION_OPEN_SELECTION
            VisualAction.HUNT_SELECT_HELL -> TemplateIds.HUNT_ACTION_SELECT_HELL
            VisualAction.HUNT_DISABLE_QUICK_BATTLE ->
                TemplateIds.HUNT_ACTION_DISABLE_QUICK_BATTLE
            VisualAction.HUNT_ENABLE_MANAGED_BATTLE ->
                TemplateIds.HUNT_ACTION_ENABLE_MANAGED_BATTLE
            VisualAction.HUNT_START_BATTLE -> TemplateIds.HUNT_ACTION_START_BATTLE
            VisualAction.HUNT_OPEN_DELEGATION -> TemplateIds.HUNT_ACTION_OPEN_DELEGATION
            VisualAction.HUNT_CONFIRM_DELEGATION ->
                TemplateIds.HUNT_ACTION_CONFIRM_DELEGATION
            VisualAction.HUNT_OPEN_MANAGED_STATUS ->
                TemplateIds.HUNT_ACTION_OPEN_MANAGED_STATUS
            VisualAction.HUNT_STOP_MANAGED -> TemplateIds.HUNT_ACTION_STOP_MANAGED
            else -> return@withSource MatchResult(matched = false)
        }
        matcher.bestMatch(source, templateId)
    }

    override suspend fun managedProgressSignature(frame: ScreenFrame): Long =
        withSource(frame) { source ->
            val geometry = matcher.geometry(source)
            val region = geometry.mapRegion(
                ScreenRect(466, 372, 496, 402),
                horizontalAnchor = HorizontalAnchor.CENTER,
                verticalAnchor = VerticalAnchor.CENTER,
            )
            val progressRegion = source.submat(
                Rect(region.left, region.top, region.width, region.height),
            )
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
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            logger.debug(
                "hunt.vision.frame",
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
            TemplateIds.HUNT_ACTION_OPEN_BATTLE,
            TemplateIds.HUNT_ACTION_OPEN_SELECTION,
            TemplateIds.HUNT_ACTION_SELECT_HELL,
            TemplateIds.HUNT_ACTION_DISABLE_QUICK_BATTLE,
            TemplateIds.HUNT_ACTION_ENABLE_MANAGED_BATTLE,
            TemplateIds.HUNT_ACTION_START_BATTLE,
            TemplateIds.HUNT_ACTION_OPEN_DELEGATION,
            TemplateIds.HUNT_ACTION_CONFIRM_DELEGATION,
            TemplateIds.HUNT_ACTION_OPEN_MANAGED_STATUS,
            TemplateIds.HUNT_ACTION_STOP_MANAGED,
        )
        const val FNV_OFFSET_BASIS = -3750763034362895579L
        const val FNV_PRIME = 1099511628211L
    }
}
