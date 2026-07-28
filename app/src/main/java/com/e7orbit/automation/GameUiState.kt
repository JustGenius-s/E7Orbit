package com.e7orbit.automation

import com.e7orbit.model.HuntPage
import com.e7orbit.model.ShopPage
import kotlinx.coroutines.flow.StateFlow

enum class TaskKind {
    SHOP,
    HUNT,
}

enum class GameUiPage {
    UNKNOWN,
    GAME_PAGE,
    LOBBY,
    SHOP,
    SHOP_PURCHASE_CONFIRMATION,
    SHOP_REFRESH_CONFIRMATION,
    RESOURCE_INSUFFICIENT,
    BATTLE_MENU,
    HUNT_SELECTION,
    HUNT_TEAM_QUICK_BATTLE,
    HUNT_TEAM_READY,
    HUNT_BATTLE_CONTROLS,
    HUNT_DELEGATION_CONFIRMATION,
    HUNT_MANAGED_LOBBY,
    HUNT_MANAGED_PANEL,
    HUNT_MANAGED_COMPLETE,
}

enum class UiMonitorStatus {
    DETACHED,
    WAITING_FOR_GAME,
    OBSERVING,
    CAPTURE_UNAVAILABLE,
}

data class GameUiSnapshot(
    val page: GameUiPage = GameUiPage.UNKNOWN,
    val candidatePage: GameUiPage = GameUiPage.UNKNOWN,
    val confidence: Double = 0.0,
    val stableFrames: Int = 0,
    val frameSequence: Long = 0L,
    val observedAtElapsedMs: Long = 0L,
    val status: UiMonitorStatus = UiMonitorStatus.DETACHED,
) {
    val isStable: Boolean
        get() = status == UiMonitorStatus.OBSERVING &&
            page != GameUiPage.UNKNOWN &&
            stableFrames >= REQUIRED_STABLE_FRAMES

    companion object {
        const val REQUIRED_STABLE_FRAMES = 2
    }
}

data class UiRecognition(
    val page: GameUiPage,
    val confidence: Double,
)

data class TaskUiContract(
    val task: TaskKind,
    val step: String,
    val allowedPages: Set<GameUiPage>,
    val minimumConfidence: Double = 0.0,
) {
    init {
        require(step.isNotBlank()) { "UI contract step 不能为空" }
        require(allowedPages.isNotEmpty()) { "UI contract 必须至少允许一个页面" }
        require(minimumConfidence in 0.0..1.0) { "minimumConfidence 必须在 0 到 1 之间" }
    }

    fun accepts(snapshot: GameUiSnapshot): Boolean =
        snapshot.isStable &&
            snapshot.page in allowedPages &&
            snapshot.confidence >= minimumConfidence
}

interface GameUiStateSource {
    val state: StateFlow<GameUiSnapshot>

    suspend fun awaitAllowed(
        contract: TaskUiContract,
        timeoutMs: Long = ACTION_GUARD_TIMEOUT_MS,
    ): GameUiSnapshot

    companion object {
        const val ACTION_GUARD_TIMEOUT_MS = 3_000L
    }
}

class UiStateMismatchException(
    val contract: TaskUiContract,
    val snapshot: GameUiSnapshot,
) : RuntimeException(
    "任务 ${contract.task}/${contract.step} 不允许在 ${snapshot.page} 执行动作",
)

internal class UiStateStabilizer(
    private val requiredStableFrames: Int = GameUiSnapshot.REQUIRED_STABLE_FRAMES,
) {
    private var candidate = GameUiPage.UNKNOWN
    private var candidateFrames = 0

    init {
        require(requiredStableFrames >= 1) { "requiredStableFrames 必须至少为 1" }
    }

    fun reduce(
        recognition: UiRecognition,
        frameSequence: Long,
        observedAtElapsedMs: Long,
    ): GameUiSnapshot {
        if (recognition.page == candidate) {
            candidateFrames += 1
        } else {
            candidate = recognition.page
            candidateFrames = 1
        }
        val stable = candidateFrames >= requiredStableFrames &&
            candidate != GameUiPage.UNKNOWN
        return GameUiSnapshot(
            page = candidate.takeIf { stable } ?: GameUiPage.UNKNOWN,
            candidatePage = candidate,
            confidence = recognition.confidence.takeIf { stable } ?: 0.0,
            stableFrames = candidateFrames,
            frameSequence = frameSequence,
            observedAtElapsedMs = observedAtElapsedMs,
            status = UiMonitorStatus.OBSERVING,
        )
    }

    fun reset() {
        candidate = GameUiPage.UNKNOWN
        candidateFrames = 0
    }
}

internal fun ShopPage.toGameUiPage(): GameUiPage = when (this) {
    ShopPage.LOBBY -> GameUiPage.LOBBY
    ShopPage.SHOP -> GameUiPage.SHOP
    ShopPage.PURCHASE_CONFIRMATION -> GameUiPage.SHOP_PURCHASE_CONFIRMATION
    ShopPage.REFRESH_CONFIRMATION -> GameUiPage.SHOP_REFRESH_CONFIRMATION
    ShopPage.RESOURCE_INSUFFICIENT -> GameUiPage.RESOURCE_INSUFFICIENT
    ShopPage.UNKNOWN -> GameUiPage.UNKNOWN
}

internal fun GameUiPage.toShopPage(): ShopPage = when (this) {
    GameUiPage.LOBBY -> ShopPage.LOBBY
    GameUiPage.SHOP -> ShopPage.SHOP
    GameUiPage.SHOP_PURCHASE_CONFIRMATION -> ShopPage.PURCHASE_CONFIRMATION
    GameUiPage.SHOP_REFRESH_CONFIRMATION -> ShopPage.REFRESH_CONFIRMATION
    GameUiPage.RESOURCE_INSUFFICIENT -> ShopPage.RESOURCE_INSUFFICIENT
    else -> ShopPage.UNKNOWN
}

internal fun HuntPage.toGameUiPage(): GameUiPage = when (this) {
    HuntPage.LOBBY -> GameUiPage.LOBBY
    HuntPage.LOBBY_MANAGED -> GameUiPage.HUNT_MANAGED_LOBBY
    HuntPage.BATTLE_MENU -> GameUiPage.BATTLE_MENU
    HuntPage.HUNT_SELECTION -> GameUiPage.HUNT_SELECTION
    HuntPage.TEAM_QUICK_BATTLE -> GameUiPage.HUNT_TEAM_QUICK_BATTLE
    HuntPage.TEAM_READY -> GameUiPage.HUNT_TEAM_READY
    HuntPage.BATTLE_CONTROLS -> GameUiPage.HUNT_BATTLE_CONTROLS
    HuntPage.DELEGATION_CONFIRMATION -> GameUiPage.HUNT_DELEGATION_CONFIRMATION
    HuntPage.MANAGED_PANEL -> GameUiPage.HUNT_MANAGED_PANEL
    HuntPage.MANAGED_COMPLETE -> GameUiPage.HUNT_MANAGED_COMPLETE
    HuntPage.UNKNOWN -> GameUiPage.UNKNOWN
}

internal fun GameUiPage.toHuntPage(): HuntPage = when (this) {
    GameUiPage.LOBBY -> HuntPage.LOBBY
    GameUiPage.HUNT_MANAGED_LOBBY -> HuntPage.LOBBY_MANAGED
    GameUiPage.BATTLE_MENU -> HuntPage.BATTLE_MENU
    GameUiPage.HUNT_SELECTION -> HuntPage.HUNT_SELECTION
    GameUiPage.HUNT_TEAM_QUICK_BATTLE -> HuntPage.TEAM_QUICK_BATTLE
    GameUiPage.HUNT_TEAM_READY -> HuntPage.TEAM_READY
    GameUiPage.HUNT_BATTLE_CONTROLS -> HuntPage.BATTLE_CONTROLS
    GameUiPage.HUNT_DELEGATION_CONFIRMATION -> HuntPage.DELEGATION_CONFIRMATION
    GameUiPage.HUNT_MANAGED_PANEL -> HuntPage.MANAGED_PANEL
    GameUiPage.HUNT_MANAGED_COMPLETE -> HuntPage.MANAGED_COMPLETE
    else -> HuntPage.UNKNOWN
}

internal val NAVIGABLE_GAME_PAGES: Set<GameUiPage> =
    GameUiPage.entries.filterTo(mutableSetOf()) { it != GameUiPage.UNKNOWN }
