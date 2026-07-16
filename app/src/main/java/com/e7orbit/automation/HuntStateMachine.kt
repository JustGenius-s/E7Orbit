package com.e7orbit.automation

import com.e7orbit.logging.NoOpOrbitLogger
import com.e7orbit.logging.OrbitLogger
import com.e7orbit.model.GestureResult
import com.e7orbit.model.HuntConfig
import com.e7orbit.model.HuntDifficulty
import com.e7orbit.model.HuntDungeon
import com.e7orbit.model.HuntPage
import com.e7orbit.model.HuntPhase
import com.e7orbit.model.HuntStats
import com.e7orbit.model.HuntStopReason
import com.e7orbit.model.REFERENCE_HEIGHT
import com.e7orbit.model.REFERENCE_WIDTH
import com.e7orbit.model.ScreenFrame
import com.e7orbit.model.ScreenPoint
import com.e7orbit.vision.VisionConfig
import kotlin.math.roundToInt

data class HuntMachineResult(
    val reason: HuntStopReason,
    val stats: HuntStats,
    val message: String,
    val successful: Boolean,
)

class HuntStateMachine(
    private val vision: HuntVision,
    private val visionConfig: VisionConfig,
    private val clock: AutomationClock = SystemAutomationClock,
    private val logger: OrbitLogger = NoOpOrbitLogger,
    private val homeNavigator: HomeNavigator? = null,
) {
    suspend fun run(
        config: HuntConfig,
        gateway: ScreenGateway,
        awaitRunPermission: suspend () -> Unit,
        onStatus: (HuntPhase, HuntStats, String, Double?) -> Unit,
        onDiagnostic: suspend (ScreenFrame, String) -> Unit,
    ): HuntMachineResult {
        var stats = HuntStats(startedAtElapsedMs = clock.elapsedRealtime())

        fun publish(
            phase: HuntPhase,
            message: String,
            confidence: Double? = null,
        ) {
            logger.debug(
                "hunt.machine.phase",
                "phase" to phase,
                "message" to message,
                "completedRuns" to stats.completedRuns,
            )
            onStatus(phase, stats, message, confidence)
        }

        return try {
            if (config.difficulty != HuntDifficulty.HELL) {
                throw MachineStop(
                    HuntStopReason.UNSUPPORTED_BRANCH,
                    "异界讨伐识图素材尚未配置",
                )
            }
            if (!config.managedBattle) {
                throw MachineStop(
                    HuntStopReason.UNSUPPORTED_BRANCH,
                    "非托管战斗结算素材尚未配置",
                )
            }

            navigateHomeIfNeeded(
                gateway = gateway,
                awaitRunPermission = awaitRunPermission,
                publish = ::publish,
                onDiagnostic = onDiagnostic,
            )

            while (stats.completedRuns < config.runCount) {
                publish(HuntPhase.WAITING_FOR_LOBBY, "等待游戏大厅")
                val lobbyPage = waitForAnyPage(
                    expected = setOf(HuntPage.LOBBY, HuntPage.LOBBY_MANAGED),
                    timeoutMs = WAIT_FOR_LOBBY_TIMEOUT_MS,
                    gateway = gateway,
                    awaitRunPermission = awaitRunPermission,
                    onDiagnostic = onDiagnostic,
                )
                if (lobbyPage == HuntPage.LOBBY_MANAGED) {
                    throw MachineStop(
                        HuntStopReason.UNKNOWN_PAGE,
                        "检测到已有托管战斗，请先结束后再运行",
                    )
                }

                publish(HuntPhase.OPENING_BATTLE, "进入战斗")
                tapPoint(Points.LOBBY_BATTLE, "进入战斗失败", gateway, awaitRunPermission)
                waitForPage(
                    expected = HuntPage.BATTLE_MENU,
                    timeoutMs = PAGE_TIMEOUT_MS,
                    gateway = gateway,
                    awaitRunPermission = awaitRunPermission,
                    onDiagnostic = onDiagnostic,
                )

                publish(HuntPhase.OPENING_HUNT, "进入讨伐")
                tapPoint(Points.BATTLE_MENU_HUNT, "进入讨伐失败", gateway, awaitRunPermission)
                waitForPage(
                    expected = HuntPage.HUNT_SELECTION,
                    timeoutMs = PAGE_TIMEOUT_MS,
                    gateway = gateway,
                    awaitRunPermission = awaitRunPermission,
                    onDiagnostic = onDiagnostic,
                )

                publish(HuntPhase.SELECTING_BOSS, "选择${config.dungeon.displayName}")
                selectDungeon(
                    dungeon = config.dungeon,
                    gateway = gateway,
                    awaitRunPermission = awaitRunPermission,
                    onDiagnostic = onDiagnostic,
                )
                clock.delay(AFTER_TAP_DELAY_MS)

                publish(HuntPhase.SELECTING_DIFFICULTY, "选择地狱")
                tapPoint(Points.HELL, "选择地狱难度失败", gateway, awaitRunPermission)
                val teamPage = waitForAnyPage(
                    expected = setOf(HuntPage.TEAM_QUICK_BATTLE, HuntPage.TEAM_READY),
                    timeoutMs = PAGE_TIMEOUT_MS,
                    gateway = gateway,
                    awaitRunPermission = awaitRunPermission,
                    onDiagnostic = onDiagnostic,
                )

                if (teamPage == HuntPage.TEAM_QUICK_BATTLE) {
                    publish(HuntPhase.DISABLING_QUICK_BATTLE, "关闭快速战斗")
                    tapPoint(
                        Points.QUICK_BATTLE_TOGGLE,
                        "关闭快速战斗失败",
                        gateway,
                        awaitRunPermission,
                    )
                    waitForPage(
                        expected = HuntPage.TEAM_READY,
                        timeoutMs = PAGE_TIMEOUT_MS,
                        gateway = gateway,
                        awaitRunPermission = awaitRunPermission,
                        onDiagnostic = onDiagnostic,
                    )
                }

                publish(HuntPhase.CONFIGURING_MANAGED_BATTLE, "开启托管战斗")
                val managedEnabled = captureChecked(gateway).useFrame { frame ->
                    vision.isManagedBattleEnabled(frame)
                }
                if (!managedEnabled) {
                    tapPoint(
                        Points.MANAGED_CHECKBOX,
                        "开启托管战斗失败",
                        gateway,
                        awaitRunPermission,
                    )
                    clock.delay(AFTER_TAP_DELAY_MS)
                    val verified = captureChecked(gateway).useFrame { frame ->
                        vision.isManagedBattleEnabled(frame)
                    }
                    if (!verified) {
                        throw MachineStop(
                            HuntStopReason.LOW_CONFIDENCE,
                            "未能确认托管战斗已开启",
                        )
                    }
                }

                publish(HuntPhase.STARTING_BATTLE, "开始讨伐")
                tapPoint(Points.START_BATTLE, "开始讨伐失败", gateway, awaitRunPermission)
                publish(HuntPhase.WAITING_FOR_BATTLE_CONTROLS, "等待战斗托管面板")
                waitForPage(
                    expected = HuntPage.BATTLE_CONTROLS,
                    timeoutMs = BATTLE_START_TIMEOUT_MS,
                    gateway = gateway,
                    awaitRunPermission = awaitRunPermission,
                    onDiagnostic = onDiagnostic,
                )

                publish(HuntPhase.DELEGATING_BATTLE, "转交托管")
                tapPoint(Points.DELEGATE_WINDOW, "打开托管确认失败", gateway, awaitRunPermission)
                waitForPage(
                    expected = HuntPage.DELEGATION_CONFIRMATION,
                    timeoutMs = PAGE_TIMEOUT_MS,
                    gateway = gateway,
                    awaitRunPermission = awaitRunPermission,
                    onDiagnostic = onDiagnostic,
                )

                publish(HuntPhase.CONFIRMING_DELEGATION, "确认托管")
                tapPoint(
                    Points.CONFIRM_DELEGATION,
                    "确认托管失败",
                    gateway,
                    awaitRunPermission,
                )
                waitForPage(
                    expected = HuntPage.LOBBY_MANAGED,
                    timeoutMs = PAGE_TIMEOUT_MS,
                    gateway = gateway,
                    awaitRunPermission = awaitRunPermission,
                    onDiagnostic = onDiagnostic,
                )

                publish(HuntPhase.MANAGED_IN_LOBBY, "讨伐托管中")
                tapPoint(
                    Points.MANAGED_STATUS,
                    "打开托管状态失败",
                    gateway,
                    awaitRunPermission,
                )
                waitForAnyPage(
                    expected = setOf(HuntPage.MANAGED_PANEL, HuntPage.MANAGED_COMPLETE),
                    timeoutMs = PAGE_TIMEOUT_MS,
                    gateway = gateway,
                    awaitRunPermission = awaitRunPermission,
                    onDiagnostic = onDiagnostic,
                )
                clock.delay(MANAGED_PANEL_OPEN_DELAY_MS)

                val targetInBatch = minOf(MAX_MANAGED_BATCH, config.runCount - stats.completedRuns)
                var observedInBatch = 0
                var previousSignature = captureChecked(gateway).useFrame { frame ->
                    vision.managedProgressSignature(frame)
                }
                val batchStartedAt = clock.elapsedRealtime()
                while (clock.elapsedRealtime() - batchStartedAt < MANAGED_BATCH_TIMEOUT_MS) {
                    awaitRunPermission()
                    val observation = captureChecked(gateway).useFrame { frame ->
                        vision.detectPage(frame) to vision.managedProgressSignature(frame)
                    }
                    if (observation.first == HuntPage.MANAGED_COMPLETE) {
                        val completed = minOf(targetInBatch, observedInBatch + 1)
                        val unobserved = (completed - observedInBatch).coerceAtLeast(0)
                        stats = stats.copy(
                            completedRuns = stats.completedRuns + unobserved,
                        )
                        publish(
                            HuntPhase.MANAGED_IN_LOBBY,
                            "本批已完成 $completed 次",
                        )
                        break
                    }

                    if (observation.second != previousSignature) {
                        previousSignature = observation.second
                        observedInBatch += 1
                        stats = stats.copy(completedRuns = stats.completedRuns + 1)
                        publish(
                            HuntPhase.MANAGED_IN_LOBBY,
                            "托管中 ${stats.completedRuns}/${config.runCount}",
                        )
                        if (observedInBatch >= targetInBatch) {
                            tapPoint(
                                Points.STOP_MANAGED,
                                "停止托管失败",
                                gateway,
                                awaitRunPermission,
                            )
                            waitForPage(
                                expected = HuntPage.MANAGED_COMPLETE,
                                timeoutMs = PAGE_TIMEOUT_MS,
                                gateway = gateway,
                                awaitRunPermission = awaitRunPermission,
                                onDiagnostic = onDiagnostic,
                            )
                            break
                        }
                    }
                    clock.delay(MANAGED_POLL_INTERVAL_MS)
                }
                if (clock.elapsedRealtime() - batchStartedAt >= MANAGED_BATCH_TIMEOUT_MS) {
                    throw MachineStop(HuntStopReason.TIMEOUT, "等待托管战斗完成超时")
                }

                if (stats.completedRuns < config.runCount) {
                    tapPoint(
                        Points.CLOSE_MANAGED_PANEL,
                        "关闭托管结果失败",
                        gateway,
                        awaitRunPermission,
                    )
                    clock.delay(AFTER_TAP_DELAY_MS)
                }
            }

            stats = stats.copy(finishedAtElapsedMs = clock.elapsedRealtime())
            HuntMachineResult(
                reason = HuntStopReason.RUN_LIMIT_REACHED,
                stats = stats,
                message = "已完成 ${stats.completedRuns} 次讨伐",
                successful = true,
            )
        } catch (stop: MachineStop) {
            diagnose(gateway, stop.reason.name, onDiagnostic)
            stats = stats.copy(finishedAtElapsedMs = clock.elapsedRealtime())
            HuntMachineResult(
                reason = stop.reason,
                stats = stats,
                message = stop.message ?: "自动讨伐已停止",
                successful = false,
            )
        }
    }

    private suspend fun waitForPage(
        expected: HuntPage,
        timeoutMs: Long,
        gateway: ScreenGateway,
        awaitRunPermission: suspend () -> Unit,
        onDiagnostic: suspend (ScreenFrame, String) -> Unit,
    ): HuntPage = waitForAnyPage(
        expected = setOf(expected),
        timeoutMs = timeoutMs,
        gateway = gateway,
        awaitRunPermission = awaitRunPermission,
        onDiagnostic = onDiagnostic,
    )

    private suspend fun navigateHomeIfNeeded(
        gateway: ScreenGateway,
        awaitRunPermission: suspend () -> Unit,
        publish: (HuntPhase, String, Double?) -> Unit,
        onDiagnostic: suspend (ScreenFrame, String) -> Unit,
    ) {
        val navigator = homeNavigator ?: return
        try {
            navigator.ensureHome(
                gateway = gateway,
                awaitRunPermission = awaitRunPermission,
                onStatus = { message ->
                    publish(HuntPhase.WAITING_FOR_LOBBY, message, null)
                },
                onDiagnostic = onDiagnostic,
            )
        } catch (error: HomeNavigationException) {
            throw MachineStop(
                reason = when (error.failure) {
                    HomeNavigationFailure.SCREENSHOT_FAILED -> HuntStopReason.SCREENSHOT_FAILED
                    HomeNavigationFailure.INVALID_RESOLUTION -> HuntStopReason.INVALID_RESOLUTION
                    HomeNavigationFailure.LOW_CONFIDENCE -> HuntStopReason.LOW_CONFIDENCE
                    HomeNavigationFailure.TIMEOUT -> HuntStopReason.TIMEOUT
                    HomeNavigationFailure.GESTURE_FAILED -> HuntStopReason.GESTURE_FAILED
                },
                message = error.message ?: "返回主页失败",
            )
        }
    }

    private suspend fun waitForAnyPage(
        expected: Set<HuntPage>,
        timeoutMs: Long,
        gateway: ScreenGateway,
        awaitRunPermission: suspend () -> Unit,
        onDiagnostic: suspend (ScreenFrame, String) -> Unit,
    ): HuntPage {
        val startedAt = clock.elapsedRealtime()
        while (clock.elapsedRealtime() - startedAt < timeoutMs) {
            awaitRunPermission()
            val page = captureChecked(gateway).useFrame { frame ->
                vision.detectPage(frame)
            }
            if (page in expected) return page
            clock.delay(PAGE_POLL_INTERVAL_MS)
        }
        diagnose(gateway, "wait_${expected.joinToString("_")}", onDiagnostic)
        throw MachineStop(
            HuntStopReason.TIMEOUT,
            "等待 ${expected.joinToString("/")} 超时",
        )
    }

    private suspend fun tapPoint(
        normalizedPoint: ScreenPoint,
        failureMessage: String,
        gateway: ScreenGateway,
        awaitRunPermission: suspend () -> Unit,
    ) {
        val point = normalizedPoint.toCapturePoint()
        repeat(GESTURE_MAX_ATTEMPTS) { attempt ->
            awaitRunPermission()
            when (val result = gateway.tap(point)) {
                GestureResult.COMPLETED -> return
                GestureResult.CANCELLED -> {
                    if (attempt < GESTURE_MAX_ATTEMPTS - 1) {
                        clock.delay(GESTURE_RETRY_DELAY_MS)
                    } else {
                        throw MachineStop(
                            HuntStopReason.GESTURE_FAILED,
                            "$failureMessage：$result",
                        )
                    }
                }

                GestureResult.REJECTED -> throw MachineStop(
                    HuntStopReason.GESTURE_FAILED,
                    "$failureMessage：$result",
                )
            }
        }
    }

    private suspend fun selectDungeon(
        dungeon: HuntDungeon,
        gateway: ScreenGateway,
        awaitRunPermission: suspend () -> Unit,
        onDiagnostic: suspend (ScreenFrame, String) -> Unit,
    ) {
        suspend fun findAndTap(): Boolean {
            awaitRunPermission()
            val match = captureChecked(gateway).useFrame { frame ->
                vision.findDungeon(frame, dungeon)
            }
            logger.debug(
                "hunt.dungeon.match",
                "dungeon" to dungeon,
                "score" to match.confidence,
                "matched" to match.matched,
            )
            val center = match.center
            if (!match.matched || center == null) return false
            tapPoint(
                normalizedPoint = center,
                failureMessage = "选择${dungeon.displayName}失败",
                gateway = gateway,
                awaitRunPermission = awaitRunPermission,
            )
            return true
        }

        if (findAndTap()) return

        repeat(DUNGEON_RESET_SWIPES) {
            swipePoint(
                from = Points.DUNGEON_SCROLL_TOP,
                to = Points.DUNGEON_SCROLL_BOTTOM,
                failureMessage = "重置地下城列表失败",
                gateway = gateway,
                awaitRunPermission = awaitRunPermission,
            )
            clock.delay(AFTER_DUNGEON_SCROLL_DELAY_MS)
        }

        repeat(DUNGEON_SEARCH_PAGES) { pageIndex ->
            if (findAndTap()) return
            if (pageIndex < DUNGEON_SEARCH_PAGES - 1) {
                swipePoint(
                    from = Points.DUNGEON_SCROLL_BOTTOM,
                    to = Points.DUNGEON_SCROLL_TOP,
                    failureMessage = "滚动地下城列表失败",
                    gateway = gateway,
                    awaitRunPermission = awaitRunPermission,
                )
                clock.delay(AFTER_DUNGEON_SCROLL_DELAY_MS)
            }
        }

        diagnose(gateway, "dungeon_${dungeon.name.lowercase()}_not_found", onDiagnostic)
        throw MachineStop(
            HuntStopReason.LOW_CONFIDENCE,
            "未找到地下城：${dungeon.displayName}",
        )
    }

    private suspend fun swipePoint(
        from: ScreenPoint,
        to: ScreenPoint,
        failureMessage: String,
        gateway: ScreenGateway,
        awaitRunPermission: suspend () -> Unit,
    ) {
        val captureFrom = from.toCapturePoint()
        val captureTo = to.toCapturePoint()
        repeat(GESTURE_MAX_ATTEMPTS) { attempt ->
            awaitRunPermission()
            when (
                val result = gateway.swipe(
                    from = captureFrom,
                    to = captureTo,
                    durationMs = DUNGEON_SCROLL_DURATION_MS,
                )
            ) {
                GestureResult.COMPLETED -> return
                GestureResult.CANCELLED -> {
                    if (attempt < GESTURE_MAX_ATTEMPTS - 1) {
                        clock.delay(GESTURE_RETRY_DELAY_MS)
                    } else {
                        throw MachineStop(
                            HuntStopReason.GESTURE_FAILED,
                            "$failureMessage：$result",
                        )
                    }
                }

                GestureResult.REJECTED -> throw MachineStop(
                    HuntStopReason.GESTURE_FAILED,
                    "$failureMessage：$result",
                )
            }
        }
    }

    private suspend fun captureChecked(gateway: ScreenGateway): ScreenFrame {
        val frame = try {
            gateway.capture()
        } catch (error: Exception) {
            throw MachineStop(
                HuntStopReason.SCREENSHOT_FAILED,
                "截图失败：${error.message.orEmpty()}",
            )
        }
        if (frame.width != REFERENCE_WIDTH || frame.height != REFERENCE_HEIGHT) {
            frame.close()
            throw MachineStop(
                HuntStopReason.INVALID_RESOLUTION,
                "需要 ${REFERENCE_WIDTH}×${REFERENCE_HEIGHT}，当前为 ${frame.width}×${frame.height}",
            )
        }
        return frame
    }

    private suspend fun diagnose(
        gateway: ScreenGateway,
        reason: String,
        onDiagnostic: suspend (ScreenFrame, String) -> Unit,
    ) {
        runCatching {
            gateway.capture().useFrame { frame -> onDiagnostic(frame, "hunt_$reason") }
        }.onFailure { error ->
            logger.error("hunt.diagnostic.capture_failed", error, "reason" to reason)
        }
    }

    private fun ScreenPoint.toCapturePoint(): ScreenPoint = ScreenPoint(
        x = (x.toDouble() / visionConfig.referenceWidth * REFERENCE_WIDTH).roundToInt(),
        y = (y.toDouble() / visionConfig.referenceHeight * REFERENCE_HEIGHT).roundToInt(),
    )

    private suspend fun <T> ScreenFrame.useFrame(
        block: suspend (ScreenFrame) -> T,
    ): T = try {
        block(this)
    } finally {
        close()
    }

    private class MachineStop(
        val reason: HuntStopReason,
        message: String,
    ) : RuntimeException(message)

    private object Points {
        val LOBBY_BATTLE = ScreenPoint(970, 260)
        val BATTLE_MENU_HUNT = ScreenPoint(625, 330)
        val DUNGEON_SCROLL_TOP = ScreenPoint(910, 155)
        val DUNGEON_SCROLL_BOTTOM = ScreenPoint(910, 510)
        val HELL = ScreenPoint(705, 330)
        val QUICK_BATTLE_TOGGLE = ScreenPoint(994, 530)
        val MANAGED_CHECKBOX = ScreenPoint(474, 445)
        val START_BATTLE = ScreenPoint(865, 530)
        val DELEGATE_WINDOW = ScreenPoint(640, 161)
        val CONFIRM_DELEGATION = ScreenPoint(600, 369)
        val MANAGED_STATUS = ScreenPoint(765, 30)
        val STOP_MANAGED = ScreenPoint(347, 433)
        val CLOSE_MANAGED_PANEL = ScreenPoint(512, 553)
    }

    private companion object {
        const val MAX_MANAGED_BATCH = 30
        const val WAIT_FOR_LOBBY_TIMEOUT_MS = 5 * 60 * 1000L
        const val PAGE_TIMEOUT_MS = 20_000L
        const val BATTLE_START_TIMEOUT_MS = 90_000L
        const val MANAGED_BATCH_TIMEOUT_MS = 45 * 60 * 1000L
        const val PAGE_POLL_INTERVAL_MS = 500L
        const val MANAGED_POLL_INTERVAL_MS = 3_000L
        const val MANAGED_PANEL_OPEN_DELAY_MS = 1_000L
        const val AFTER_TAP_DELAY_MS = 800L
        const val AFTER_DUNGEON_SCROLL_DELAY_MS = 900L
        const val DUNGEON_SCROLL_DURATION_MS = 500L
        const val DUNGEON_RESET_SWIPES = 2
        const val DUNGEON_SEARCH_PAGES = 4
        const val GESTURE_MAX_ATTEMPTS = 3
        const val GESTURE_RETRY_DELAY_MS = 160L
    }
}
