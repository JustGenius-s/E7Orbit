package com.e7orbit.model

import android.os.SystemClock
import kotlinx.serialization.Serializable

const val MAX_SUPPORTED_HUNT_RUNS = 30

@Serializable
enum class HuntDifficulty(
    val isSupported: Boolean,
) {
    HELL(isSupported = true),
    OTHERWORLD(isSupported = false),
}

@Serializable
enum class HuntDungeon(
    val displayName: String,
) {
    WYVERN("双足飞龙"),
    GOLEM("魔像"),
    BANSHEE("报丧妖"),
    AZIMANAK("阿吉马纳克"),
    CAIDES("卡戴斯"),
}

@Serializable
enum class HuntEnergyRefill(
    val isSupported: Boolean,
) {
    DISABLED(isSupported = true),
    LEIF_ONLY(isSupported = false),
    SKYSTONE_ONLY(isSupported = false),
    LEIF_THEN_SKYSTONE(isSupported = false),
}

@Serializable
data class HuntConfig(
    val dungeon: HuntDungeon = HuntDungeon.WYVERN,
    val difficulty: HuntDifficulty = HuntDifficulty.HELL,
    val managedBattle: Boolean = true,
    val runCount: Int = 20,
    val energyRefill: HuntEnergyRefill = HuntEnergyRefill.DISABLED,
) {
    fun normalized(): HuntConfig = copy(
        difficulty = difficulty.takeIf { it.isSupported }
            ?: HuntDifficulty.HELL,
        managedBattle = true,
        runCount = runCount.coerceIn(1, MAX_SUPPORTED_HUNT_RUNS),
        energyRefill = energyRefill.takeIf { it.isSupported }
            ?: HuntEnergyRefill.DISABLED,
    )
}

data class HuntStats(
    val completedRuns: Int = 0,
    val startedAtElapsedMs: Long = 0L,
    val finishedAtElapsedMs: Long? = null,
) {
    val elapsedMs: Long
        get() {
            if (startedAtElapsedMs == 0L) return 0L
            return (finishedAtElapsedMs ?: SystemClock.elapsedRealtime()) - startedAtElapsedMs
        }
}

enum class HuntPhase {
    IDLE,
    WAITING_FOR_LOBBY,
    OPENING_BATTLE,
    OPENING_HUNT,
    SELECTING_BOSS,
    SELECTING_DIFFICULTY,
    DISABLING_QUICK_BATTLE,
    CONFIGURING_MANAGED_BATTLE,
    STARTING_BATTLE,
    WAITING_FOR_BATTLE_CONTROLS,
    DELEGATING_BATTLE,
    CONFIRMING_DELEGATION,
    MANAGED_IN_LOBBY,
    PAUSED,
    COMPLETED,
    ERROR,
}

enum class HuntStopReason {
    NONE,
    USER_STOPPED,
    RUN_LIMIT_REACHED,
    SERVICE_UNAVAILABLE,
    INVALID_CONFIGURATION,
    INVALID_RESOLUTION,
    TEMPLATES_MISSING,
    UNSUPPORTED_BRANCH,
    UNKNOWN_PAGE,
    LOW_CONFIDENCE,
    TIMEOUT,
    SCREENSHOT_FAILED,
    GESTURE_FAILED,
    UNCERTAIN_EFFECT,
    INTERNAL_ERROR,
}

data class HuntStatus(
    val phase: HuntPhase = HuntPhase.IDLE,
    val config: HuntConfig = HuntConfig(),
    val stats: HuntStats = HuntStats(),
    val message: String = "尚未运行",
    val lastConfidence: Double? = null,
    val stopReason: HuntStopReason = HuntStopReason.NONE,
    val serviceReady: Boolean = false,
    val templatesReady: Boolean = false,
) {
    val isRunning: Boolean
        get() = phase !in setOf(
            HuntPhase.IDLE,
            HuntPhase.PAUSED,
            HuntPhase.COMPLETED,
            HuntPhase.ERROR,
        )

    val isTerminal: Boolean
        get() = phase == HuntPhase.COMPLETED || phase == HuntPhase.ERROR
}

enum class HuntPage {
    LOBBY,
    LOBBY_MANAGED,
    BATTLE_MENU,
    HUNT_SELECTION,
    TEAM_QUICK_BATTLE,
    TEAM_READY,
    BATTLE_CONTROLS,
    DELEGATION_CONFIRMATION,
    MANAGED_PANEL,
    MANAGED_COMPLETE,
    UNKNOWN,
}
