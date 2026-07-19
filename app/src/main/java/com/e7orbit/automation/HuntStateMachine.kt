package com.e7orbit.automation

import com.e7orbit.logging.NoOpOrbitLogger
import com.e7orbit.logging.OrbitLogger
import com.e7orbit.model.HuntConfig
import com.e7orbit.model.HuntDifficulty
import com.e7orbit.model.HuntDungeon
import com.e7orbit.model.HuntPage
import com.e7orbit.model.HuntPhase
import com.e7orbit.model.HuntStats
import com.e7orbit.model.HuntStopReason
import com.e7orbit.model.MAX_SUPPORTED_HUNT_RUNS
import com.e7orbit.model.REFERENCE_HEIGHT
import com.e7orbit.model.REFERENCE_WIDTH
import com.e7orbit.model.ScreenFrame
import com.e7orbit.model.ScreenPoint
import com.e7orbit.vision.VisionConfig
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException

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
    ): HuntMachineResult = run(
        config = config,
        session = AutomationSession(
            gateway = gateway,
            clock = clock,
            awaitRunPermission = awaitRunPermission,
            onDiagnostic = { frame, reason -> onDiagnostic(frame, "hunt_$reason") },
            logger = logger,
        ),
        onStatus = onStatus,
    )

    suspend fun run(
        config: HuntConfig,
        session: AutomationSession,
        onStatus: (HuntPhase, HuntStats, String, Double?) -> Unit,
    ): HuntMachineResult {
        val gateway = session.gateway
        val awaitRunPermission: suspend () -> Unit = session::awaitActive
        val onDiagnostic: suspend (ScreenFrame, String) -> Unit = session::saveDiagnostic
        var stats = HuntStats(startedAtElapsedMs = clock.elapsedRealtime())
        val operations = session.executor

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
                session = session,
                publish = ::publish,
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
                tapPoint(
                    point = Points.LOBBY_BATTLE,
                    operationId = "hunt.open_battle",
                    operations = operations,
                    policy = OperationPolicy.reconciliationRequired(),
                )
                waitForPage(
                    expected = HuntPage.BATTLE_MENU,
                    timeoutMs = PAGE_TIMEOUT_MS,
                    gateway = gateway,
                    awaitRunPermission = awaitRunPermission,
                    onDiagnostic = onDiagnostic,
                )

                publish(HuntPhase.OPENING_HUNT, "进入讨伐")
                tapPoint(
                    point = Points.BATTLE_MENU_HUNT,
                    operationId = "hunt.open_selection",
                    operations = operations,
                    policy = OperationPolicy.reconciliationRequired(),
                )
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
                    operations = operations,
                    onDiagnostic = onDiagnostic,
                )
                clock.delay(AFTER_TAP_DELAY_MS)

                publish(HuntPhase.SELECTING_DIFFICULTY, "选择地狱")
                tapPoint(
                    point = Points.HELL,
                    operationId = "hunt.select_hell",
                    operations = operations,
                    policy = OperationPolicy.reconciliationRequired(),
                )
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
                        point = Points.QUICK_BATTLE_TOGGLE,
                        operationId = "hunt.disable_quick_battle",
                        operations = operations,
                        policy = OperationPolicy.reconciliationRequired(),
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
                        point = Points.MANAGED_CHECKBOX,
                        operationId = "hunt.enable_managed_battle",
                        operations = operations,
                        policy = OperationPolicy.reconciliationRequired(),
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
                tapPoint(
                    point = Points.START_BATTLE,
                    operationId = "hunt.start_battle",
                    operations = operations,
                    policy = OperationPolicy.externalLongRunning(),
                )
                publish(HuntPhase.WAITING_FOR_BATTLE_CONTROLS, "等待战斗托管面板")
                requireConfirmedEffect(
                    uncertainMessage = "已点击开始讨伐，但未能确认战斗状态；讨伐可能已经开始",
                ) {
                    waitForPage(
                        expected = HuntPage.BATTLE_CONTROLS,
                        timeoutMs = BATTLE_START_TIMEOUT_MS,
                        gateway = gateway,
                        awaitRunPermission = awaitRunPermission,
                        onDiagnostic = onDiagnostic,
                    )
                }

                publish(HuntPhase.DELEGATING_BATTLE, "转交托管")
                tapPoint(
                    point = Points.DELEGATE_WINDOW,
                    operationId = "hunt.open_delegation",
                    operations = operations,
                    policy = OperationPolicy.reconciliationRequired(),
                )
                waitForPage(
                    expected = HuntPage.DELEGATION_CONFIRMATION,
                    timeoutMs = PAGE_TIMEOUT_MS,
                    gateway = gateway,
                    awaitRunPermission = awaitRunPermission,
                    onDiagnostic = onDiagnostic,
                )

                publish(HuntPhase.CONFIRMING_DELEGATION, "确认托管")
                tapPoint(
                    point = Points.CONFIRM_DELEGATION,
                    operationId = "hunt.confirm_delegation",
                    operations = operations,
                    policy = OperationPolicy.externalLongRunning(),
                )
                requireConfirmedEffect(
                    uncertainMessage = "已点击确认托管，但未能确认托管状态；托管可能已经开始",
                ) {
                    waitForPage(
                        expected = HuntPage.LOBBY_MANAGED,
                        timeoutMs = PAGE_TIMEOUT_MS,
                        gateway = gateway,
                        awaitRunPermission = awaitRunPermission,
                        onDiagnostic = onDiagnostic,
                    )
                }

                publish(HuntPhase.MANAGED_IN_LOBBY, "讨伐托管中")
                tapPoint(
                    point = Points.MANAGED_STATUS,
                    operationId = "hunt.open_managed_status",
                    operations = operations,
                    policy = OperationPolicy.reconciliationRequired(),
                )
                waitForAnyPage(
                    expected = setOf(HuntPage.MANAGED_PANEL, HuntPage.MANAGED_COMPLETE),
                    timeoutMs = PAGE_TIMEOUT_MS,
                    gateway = gateway,
                    awaitRunPermission = awaitRunPermission,
                    onDiagnostic = onDiagnostic,
                )
                clock.delay(MANAGED_PANEL_OPEN_DELAY_MS)

                val targetInBatch = minOf(
                    MAX_SUPPORTED_HUNT_RUNS,
                    config.runCount - stats.completedRuns,
                )
                var observedInBatch = 0
                var previousSignature = operations.capture("hunt.managed_progress_baseline")
                    .useFrame { frame -> vision.managedProgressSignature(frame) }
                var candidateSignature: Long? = null
                var candidateStablePolls = 0
                try {
                    operations.waitUntil<Unit>(
                        operationId = "hunt.managed_batch",
                        timeoutMs = MANAGED_BATCH_TIMEOUT_MS,
                        pollIntervalMs = MANAGED_POLL_INTERVAL_MS,
                        diagnosticReason = "managed_batch_timeout",
                    ) {
                        val observation = operations.capture("hunt.observe_managed_progress")
                            .useFrame { frame ->
                                vision.detectPage(frame) to
                                    vision.managedProgressSignature(frame)
                            }
                        if (observation.first == HuntPage.MANAGED_COMPLETE) {
                            if (observedInBatch < targetInBatch) {
                                diagnose(
                                    gateway,
                                    "managed_progress_uncertain_${observedInBatch}_of_$targetInBatch",
                                    onDiagnostic,
                                )
                                throw MachineStop(
                                    HuntStopReason.LOW_CONFIDENCE,
                                    "托管已结束，但只能确认 $observedInBatch/$targetInBatch 次；" +
                                        "为避免超额讨伐已停止，请核对实际次数",
                                )
                            }
                            publish(
                                HuntPhase.MANAGED_IN_LOBBY,
                                "本批已完成 $observedInBatch 次",
                            )
                            return@waitUntil Unit
                        }

                        val signature = observation.second
                        if (signature == previousSignature) {
                            candidateSignature = null
                            candidateStablePolls = 0
                        } else if (signature == candidateSignature) {
                            candidateStablePolls += 1
                        } else {
                            candidateSignature = signature
                            candidateStablePolls = 1
                        }

                        if (candidateStablePolls >= PROGRESS_SIGNATURE_STABLE_POLLS) {
                            previousSignature = requireNotNull(candidateSignature)
                            candidateSignature = null
                            candidateStablePolls = 0
                            observedInBatch += 1
                            stats = stats.copy(completedRuns = stats.completedRuns + 1)
                            publish(
                                HuntPhase.MANAGED_IN_LOBBY,
                                "托管中 ${stats.completedRuns}/${config.runCount}",
                            )
                            if (observedInBatch >= targetInBatch) {
                                tapPoint(
                                    point = Points.STOP_MANAGED,
                                    operationId = "hunt.stop_managed",
                                    operations = operations,
                                    policy = OperationPolicy.externalLongRunning(),
                                )
                                requireConfirmedEffect(
                                    uncertainMessage =
                                        "已请求停止托管，但未能确认停止结果；" +
                                            "游戏中的托管可能仍在运行",
                                ) {
                                    waitForPage(
                                        expected = HuntPage.MANAGED_COMPLETE,
                                        timeoutMs = PAGE_TIMEOUT_MS,
                                        gateway = gateway,
                                        awaitRunPermission = awaitRunPermission,
                                        onDiagnostic = onDiagnostic,
                                    )
                                }
                                return@waitUntil Unit
                            }
                        }
                        null
                    }
                } catch (error: OperationExecutionException) {
                    if (error.failure.kind == ExecutionFailureKind.TIMEOUT) {
                        stopManagedAfterTimeout(
                            operations = operations,
                            gateway = gateway,
                            awaitRunPermission = awaitRunPermission,
                            onDiagnostic = onDiagnostic,
                        )
                    }
                    throw error
                }

                if (stats.completedRuns < config.runCount) {
                    throw MachineStop(
                        HuntStopReason.LOW_CONFIDENCE,
                        "本批只能确认 ${stats.completedRuns}/${config.runCount} 次；" +
                            "当前进度识别不支持安全开启下一批，已停止以避免超额讨伐",
                    )
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
        } catch (error: OperationExecutionException) {
            val reason = error.failure.kind.toHuntStopReason()
            logger.warn(
                "hunt.machine.operation_failed",
                "operation" to error.failure.operationId,
                "kind" to error.failure.kind,
                "message" to error.message,
                "completedRuns" to stats.completedRuns,
            )
            diagnose(gateway, reason.name, onDiagnostic)
            stats = stats.copy(finishedAtElapsedMs = clock.elapsedRealtime())
            HuntMachineResult(
                reason = reason,
                stats = stats,
                message = error.message ?: "自动讨伐操作失败",
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
        session: AutomationSession,
        publish: (HuntPhase, String, Double?) -> Unit,
    ) {
        val navigator = homeNavigator ?: return
        try {
            navigator.ensureHome(
                session = session,
                onStatus = { message ->
                    publish(HuntPhase.WAITING_FOR_LOBBY, message, null)
                },
            )
        } catch (error: HomeNavigationException) {
            throw MachineStop(
                reason = when (error.failure) {
                    HomeNavigationFailure.SCREENSHOT_FAILED -> HuntStopReason.SCREENSHOT_FAILED
                    HomeNavigationFailure.INVALID_RESOLUTION -> HuntStopReason.INVALID_RESOLUTION
                    HomeNavigationFailure.LOW_CONFIDENCE -> HuntStopReason.LOW_CONFIDENCE
                    HomeNavigationFailure.TIMEOUT -> HuntStopReason.TIMEOUT
                    HomeNavigationFailure.GESTURE_FAILED -> HuntStopReason.GESTURE_FAILED
                    HomeNavigationFailure.UNCERTAIN_EFFECT ->
                        HuntStopReason.UNCERTAIN_EFFECT
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
        val operations = OperationExecutor(
            gateway = gateway,
            clock = clock,
            awaitRunPermission = awaitRunPermission,
            onDiagnostic = { frame, reason -> onDiagnostic(frame, "hunt_$reason") },
            logger = logger,
        )
        val expectedLabel = expected.joinToString("_")
        try {
            return operations.waitUntil(
                operationId = "hunt.wait_${expectedLabel.lowercase()}",
                timeoutMs = timeoutMs,
                pollIntervalMs = PAGE_POLL_INTERVAL_MS,
                diagnosticReason = "wait_$expectedLabel",
            ) {
                operations.capture("hunt.observe_page").useFrame { frame ->
                    vision.detectPage(frame).takeIf { it in expected }
                }
            }
        } catch (error: OperationExecutionException) {
            if (error.failure.kind == ExecutionFailureKind.TIMEOUT) {
                throw MachineStop(
                    HuntStopReason.TIMEOUT,
                    "等待 ${expected.joinToString("/")} 超时",
                )
            }
            throw error
        }
    }

    private suspend fun tapPoint(
        point: ScreenPoint,
        operationId: String,
        operations: OperationExecutor,
        policy: OperationPolicy,
    ) {
        operations.tap(
            operationId = operationId,
            point = point.toCapturePoint(),
            policy = policy,
        )
    }

    private suspend fun selectDungeon(
        dungeon: HuntDungeon,
        gateway: ScreenGateway,
        awaitRunPermission: suspend () -> Unit,
        operations: OperationExecutor,
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
                point = center,
                operationId = "hunt.select_${dungeon.name.lowercase()}",
                operations = operations,
                policy = OperationPolicy.reconciliationRequired(),
            )
            return true
        }

        if (findAndTap()) return

        repeat(DUNGEON_RESET_SWIPES) {
            swipePoint(
                from = Points.DUNGEON_SCROLL_TOP,
                to = Points.DUNGEON_SCROLL_BOTTOM,
                operationId = "hunt.reset_dungeon_list",
                operations = operations,
            )
            clock.delay(AFTER_DUNGEON_SCROLL_DELAY_MS)
        }

        repeat(DUNGEON_SEARCH_PAGES) { pageIndex ->
            if (findAndTap()) return
            if (pageIndex < DUNGEON_SEARCH_PAGES - 1) {
                swipePoint(
                    from = Points.DUNGEON_SCROLL_BOTTOM,
                    to = Points.DUNGEON_SCROLL_TOP,
                    operationId = "hunt.scroll_dungeon_list",
                    operations = operations,
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
        operationId: String,
        operations: OperationExecutor,
    ) {
        operations.swipe(
            operationId = operationId,
            from = from.toCapturePoint(),
            to = to.toCapturePoint(),
            durationMs = DUNGEON_SCROLL_DURATION_MS,
            policy = OperationPolicy.idempotent(),
        )
    }

    private suspend fun captureChecked(gateway: ScreenGateway): ScreenFrame {
        val frame = try {
            gateway.capture()
        } catch (cancelled: CancellationException) {
            throw cancelled
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
        try {
            gateway.capture().useFrame { frame -> onDiagnostic(frame, "hunt_$reason") }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
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

    private fun ExecutionFailureKind.toHuntStopReason(): HuntStopReason = when (this) {
        ExecutionFailureKind.SCREENSHOT_FAILED -> HuntStopReason.SCREENSHOT_FAILED
        ExecutionFailureKind.INVALID_RESOLUTION -> HuntStopReason.INVALID_RESOLUTION
        ExecutionFailureKind.GESTURE_FAILED -> HuntStopReason.GESTURE_FAILED
        ExecutionFailureKind.UNCERTAIN_EFFECT -> HuntStopReason.UNCERTAIN_EFFECT
        ExecutionFailureKind.TIMEOUT -> HuntStopReason.TIMEOUT
    }

    private suspend fun requireConfirmedEffect(
        uncertainMessage: String,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (stop: MachineStop) {
            if (stop.reason == HuntStopReason.TIMEOUT) {
                throw MachineStop(HuntStopReason.UNCERTAIN_EFFECT, uncertainMessage)
            }
            throw stop
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logger.error(
                "hunt.operation.postcondition_failed",
                error,
                "message" to uncertainMessage,
            )
            throw MachineStop(HuntStopReason.UNCERTAIN_EFFECT, uncertainMessage)
        }
    }

    private suspend fun stopManagedAfterTimeout(
        operations: OperationExecutor,
        gateway: ScreenGateway,
        awaitRunPermission: suspend () -> Unit,
        onDiagnostic: suspend (ScreenFrame, String) -> Unit,
    ): Nothing {
        val page = try {
            operations.capture("hunt.reconcile_managed_timeout").useFrame { frame ->
                vision.detectPage(frame)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logger.error("hunt.managed_timeout.reconcile_failed", error)
            throw MachineStop(
                HuntStopReason.UNCERTAIN_EFFECT,
                "托管监控已超时，且无法确认游戏状态；托管可能仍在运行，请立即检查",
            )
        }

        when (page) {
            HuntPage.MANAGED_COMPLETE -> throw MachineStop(
                HuntStopReason.LOW_CONFIDENCE,
                "托管监控超时后检测到托管已结束，但完成次数无法确认，请核对实际次数",
            )

            HuntPage.MANAGED_PANEL -> {
                try {
                    tapPoint(
                        point = Points.STOP_MANAGED,
                        operationId = "hunt.stop_managed_after_timeout",
                        operations = operations,
                        policy = OperationPolicy.externalLongRunning(),
                    )
                    requireConfirmedEffect(
                        uncertainMessage =
                            "托管监控已超时，已请求停止但无法确认结果；托管可能仍在运行",
                    ) {
                        waitForPage(
                            expected = HuntPage.MANAGED_COMPLETE,
                            timeoutMs = PAGE_TIMEOUT_MS,
                            gateway = gateway,
                            awaitRunPermission = awaitRunPermission,
                            onDiagnostic = onDiagnostic,
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (stop: MachineStop) {
                    throw stop
                } catch (error: OperationExecutionException) {
                    logger.error("hunt.managed_timeout.stop_failed", error)
                    throw MachineStop(
                        HuntStopReason.UNCERTAIN_EFFECT,
                        "托管监控已超时，停止操作结果不确定；托管可能仍在运行，请立即检查",
                    )
                }
                throw MachineStop(
                    HuntStopReason.TIMEOUT,
                    "托管监控已超时，已确认停止托管，请核对实际完成次数",
                )
            }

            else -> throw MachineStop(
                HuntStopReason.UNCERTAIN_EFFECT,
                "托管监控已超时，当前页面无法确认；托管可能仍在运行，请立即检查",
            )
        }
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
    }

    private companion object {
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
        const val PROGRESS_SIGNATURE_STABLE_POLLS = 2
    }
}
