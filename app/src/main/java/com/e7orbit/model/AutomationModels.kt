package com.e7orbit.model

import android.graphics.Bitmap
import android.os.SystemClock
import kotlinx.serialization.Serializable

const val REFERENCE_WIDTH = 1920
const val REFERENCE_HEIGHT = 1080
const val E7_CN_PACKAGE = "com.zlongame.cn.epicseven"
const val COVENANT_BOOKMARK_GOLD_COST = 184_000L
const val MYSTIC_MEDAL_GOLD_COST = 280_000L
const val COVENANT_BOOKMARKS_PER_PURCHASE = 5
const val MYSTIC_MEDALS_PER_PURCHASE = 50

@Serializable
data class RunConfig(
    val buyCovenantBookmarks: Boolean = true,
    val buyMysticMedals: Boolean = true,
    val maxRefreshes: Int = 100,
    val matchThreshold: Double = 0.92,
) {
    fun normalized(): RunConfig = copy(
        maxRefreshes = maxRefreshes.coerceIn(1, 10_000),
        matchThreshold = matchThreshold.coerceIn(0.75, 0.99),
    )

    val hasPurchaseTarget: Boolean
        get() = buyCovenantBookmarks || buyMysticMedals
}

@Serializable
data class RunStats(
    val completedRefreshes: Int = 0,
    val shopPagesScanned: Int = 0,
    val covenantBookmarksBought: Int = 0,
    val mysticMedalsBought: Int = 0,
    val goldSpent: Long = 0L,
    val startedAtElapsedMs: Long = 0L,
    val finishedAtElapsedMs: Long? = null,
) {
    val elapsedMs: Long
        get() {
            if (startedAtElapsedMs == 0L) return 0L
            return (finishedAtElapsedMs ?: SystemClock.elapsedRealtime()) - startedAtElapsedMs
        }

    val covenantRatePercent: Double
        get() = ratePercent(covenantBookmarksBought)

    val mysticRatePercent: Double
        get() = ratePercent(mysticMedalsBought)

    val covenantBookmarksGained: Int
        get() = covenantBookmarksBought * COVENANT_BOOKMARKS_PER_PURCHASE

    val mysticMedalsGained: Int
        get() = mysticMedalsBought * MYSTIC_MEDALS_PER_PURCHASE

    private fun ratePercent(count: Int): Double =
        if (shopPagesScanned == 0) 0.0 else count * 100.0 / shopPagesScanned
}

enum class AutomationPhase {
    IDLE,
    WAITING_FOR_SERVICE,
    WAITING_FOR_SHOP,
    SCANNING_TOP,
    PURCHASING,
    VERIFYING_PURCHASE,
    SCANNING_BOTTOM,
    REFRESHING,
    WAITING_FOR_REFRESH,
    PAUSED,
    COMPLETED,
    ERROR,
}

enum class StopReason {
    NONE,
    USER_STOPPED,
    REFRESH_LIMIT_REACHED,
    RESOURCE_INSUFFICIENT,
    SERVICE_UNAVAILABLE,
    INVALID_CONFIGURATION,
    INVALID_RESOLUTION,
    TEMPLATES_MISSING,
    UNKNOWN_PAGE,
    LOW_CONFIDENCE,
    TIMEOUT,
    SCREENSHOT_FAILED,
    GESTURE_FAILED,
    UNCERTAIN_EFFECT,
    INTERNAL_ERROR,
}

data class AutomationStatus(
    val phase: AutomationPhase = AutomationPhase.IDLE,
    val config: RunConfig = RunConfig(),
    val stats: RunStats = RunStats(),
    val message: String = "尚未运行",
    val lastConfidence: Double? = null,
    val stopReason: StopReason = StopReason.NONE,
    val serviceReady: Boolean = false,
    val templatesReady: Boolean = false,
) {
    val isRunning: Boolean
        get() = phase in setOf(
            AutomationPhase.WAITING_FOR_SHOP,
            AutomationPhase.SCANNING_TOP,
            AutomationPhase.PURCHASING,
            AutomationPhase.VERIFYING_PURCHASE,
            AutomationPhase.SCANNING_BOTTOM,
            AutomationPhase.REFRESHING,
            AutomationPhase.WAITING_FOR_REFRESH,
        )

    val isTerminal: Boolean
        get() = phase == AutomationPhase.COMPLETED || phase == AutomationPhase.ERROR
}

data class ScreenPoint(
    val x: Int,
    val y: Int,
)

data class ScreenRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val center: ScreenPoint get() = ScreenPoint((left + right) / 2, (top + bottom) / 2)

    fun contains(point: ScreenPoint): Boolean =
        point.x in left..right && point.y in top..bottom
}

data class ScreenFrame(
    val bitmap: Bitmap?,
    val width: Int,
    val height: Int,
    val capturedAtElapsedMs: Long,
    val sequence: Long,
) : AutoCloseable {
    override fun close() {
        bitmap?.let { if (!it.isRecycled) it.recycle() }
    }
}

enum class ItemType {
    COVENANT_BOOKMARK,
    MYSTIC_MEDAL,
}

enum class GameLocation {
    LOBBY,
    UNKNOWN,
}

enum class VisualAction {
    OPEN_MENU,
    RETURN_TO_LOBBY,
    OPEN_SECRET_SHOP,
    CONFIRM_PURCHASE,
    REFRESH_SHOP,
    CONFIRM_REFRESH,
    DISMISS_ERROR,
    HUNT_OPEN_BATTLE,
    HUNT_OPEN_SELECTION,
    HUNT_SELECT_HELL,
    HUNT_DISABLE_QUICK_BATTLE,
    HUNT_ENABLE_MANAGED_BATTLE,
    HUNT_START_BATTLE,
    HUNT_OPEN_DELEGATION,
    HUNT_CONFIRM_DELEGATION,
    HUNT_OPEN_MANAGED_STATUS,
    HUNT_STOP_MANAGED,
}

data class PurchaseTarget(
    val type: ItemType,
    val itemBounds: ScreenRect,
    val purchaseButtonBounds: ScreenRect,
    val confidence: Double,
    val rowIndex: Int,
) {
    val purchaseButton: ScreenPoint
        get() = purchaseButtonBounds.center
}

enum class ShopPage {
    LOBBY,
    SHOP,
    PURCHASE_CONFIRMATION,
    REFRESH_CONFIRMATION,
    RESOURCE_INSUFFICIENT,
    UNKNOWN,
}

data class MatchResult(
    val matched: Boolean,
    val confidence: Double = 0.0,
    val bounds: ScreenRect? = null,
) {
    val center: ScreenPoint? get() = bounds?.center
}

enum class GestureResult {
    COMPLETED,
    CANCELLED,
    REJECTED,
    TIMED_OUT,
}

@Serializable
data class RunSummary(
    val completedRefreshes: Int = 0,
    val shopPagesScanned: Int = 0,
    val covenantBookmarksBought: Int = 0,
    val mysticMedalsBought: Int = 0,
    val goldSpent: Long = 0L,
    val elapsedMs: Long = 0L,
    val stopReason: String = StopReason.NONE.name,
    val completedAtEpochMs: Long = 0L,
) {
    val covenantRatePercent: Double
        get() = ratePercent(covenantBookmarksBought)

    val mysticRatePercent: Double
        get() = ratePercent(mysticMedalsBought)

    private fun ratePercent(count: Int): Double =
        if (shopPagesScanned == 0) 0.0 else count * 100.0 / shopPagesScanned
}
