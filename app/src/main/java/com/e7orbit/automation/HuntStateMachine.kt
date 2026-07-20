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
import com.e7orbit.model.ScreenFrame
import com.e7orbit.model.ScreenRatioPoint
import com.e7orbit.model.VisualAction
import kotlinx.coroutines.CancellationException

data class HuntMachineResult(
    val reason: HuntStopReason,
    val stats: HuntStats,
    val message: String,
    val successful: Boolean,
)

class HuntStateMachine(
    private val vision: HuntVision,
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
            onDiagnostic = onDiagnostic,
            logger = logger,
        ),
        onStatus = onStatus,
    )

    suspend fun run(
        config: HuntConfig,
        session: AutomationSession,
        onStatus: (HuntPhase, HuntStats, String, Double?) -> Unit,
    ): HuntMachineResult {
        var stats = HuntStats(startedAtElapsedMs = clock.elapsedRealtime())
        val operations = session.executor
        val visualActions = VisualActionExecutor(
            operations = operations,
            vision = vision,
            namespace = "hunt",
            logger = logger,
        )

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
            runWorkflowStep(
                workflowId = "hunt_managed",
                stepId = "configuration.validate",
                context = Unit,
                session = session,
                execute = {
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
                },
            )
            runWorkflowStep(
                workflowId = "hunt_managed",
                stepId = "navigation.ensure_home",
                context = Unit,
                session = session,
                effectSafety = EffectSafety.RECONCILIATION_REQUIRED,
                execute = {
                    navigateHomeIfNeeded(
                        session = session,
                        publish = ::publish,
                    )
                },
            )

            while (stats.completedRuns < config.runCount) {
                val batchRunKey = "batch-${stats.completedRuns + 1}"
                runWorkflowStep(
                    workflowId = "hunt_managed",
                    stepId = "lobby.wait_ready",
                    context = Unit,
                    session = session,
                    runKey = batchRunKey,
                    execute = {
                        publish(HuntPhase.WAITING_FOR_LOBBY, "等待游戏大厅")
                        val lobbyPage = waitForAnyPage(
                            expected = setOf(HuntPage.LOBBY, HuntPage.LOBBY_MANAGED),
                            timeoutMs = WAIT_FOR_LOBBY_TIMEOUT_MS,
                            operations = operations,
                        )
                        if (lobbyPage == HuntPage.LOBBY_MANAGED) {
                            throw MachineStop(
                                HuntStopReason.UNKNOWN_PAGE,
                                "检测到已有托管战斗，请先结束后再运行",
                            )
                        }
                    },
                )

                runWorkflowStep(
                    workflowId = "hunt_managed",
                    stepId = "navigation.open_battle",
                    context = Unit,
                    session = session,
                    runKey = batchRunKey,
                    effectSafety = EffectSafety.RECONCILIATION_REQUIRED,
                    execute = {
                        publish(HuntPhase.OPENING_BATTLE, "进入战斗")
                        visualActions.tap(
                            action = VisualAction.HUNT_OPEN_BATTLE,
                            operationId = "hunt.open_battle",
                            policy = OperationPolicy.reconciliationRequired(),
                            failureMessage = "未找到战斗入口",
                        )
                        waitForPage(
                            expected = HuntPage.BATTLE_MENU,
                            timeoutMs = PAGE_TIMEOUT_MS,
                            operations = operations,
                        )
                    },
                    recover = {
                        recoverIfPageReached(
                            gestureIssued = hasIssuedGesture,
                            operations = operations,
                            expected = setOf(HuntPage.BATTLE_MENU),
                        )
                    },
                )

                runWorkflowStep(
                    workflowId = "hunt_managed",
                    stepId = "navigation.open_hunt",
                    context = Unit,
                    session = session,
                    runKey = batchRunKey,
                    effectSafety = EffectSafety.RECONCILIATION_REQUIRED,
                    execute = {
                        publish(HuntPhase.OPENING_HUNT, "进入讨伐")
                        visualActions.tap(
                            action = VisualAction.HUNT_OPEN_SELECTION,
                            operationId = "hunt.open_selection",
                            policy = OperationPolicy.reconciliationRequired(),
                            failureMessage = "未找到讨伐入口",
                        )
                        waitForPage(
                            expected = HuntPage.HUNT_SELECTION,
                            timeoutMs = PAGE_TIMEOUT_MS,
                            operations = operations,
                        )
                    },
                    recover = {
                        recoverIfPageReached(
                            gestureIssued = hasIssuedGesture,
                            operations = operations,
                            expected = setOf(HuntPage.HUNT_SELECTION),
                        )
                    },
                )

                runWorkflowStep(
                    workflowId = "hunt_managed",
                    stepId = "selection.dungeon",
                    context = Unit,
                    session = session,
                    runKey = batchRunKey,
                    effectSafety = EffectSafety.RECONCILIATION_REQUIRED,
                    execute = {
                        publish(
                            HuntPhase.SELECTING_BOSS,
                            "选择${config.dungeon.displayName}",
                        )
                        selectDungeon(
                            dungeon = config.dungeon,
                            operations = operations,
                            visualActions = visualActions,
                            session = session,
                        )
                        clock.delay(AFTER_TAP_DELAY_MS)
                    },
                )

                var teamPage = HuntPage.UNKNOWN
                runWorkflowStep(
                    workflowId = "hunt_managed",
                    stepId = "selection.difficulty",
                    context = Unit,
                    session = session,
                    runKey = batchRunKey,
                    effectSafety = EffectSafety.RECONCILIATION_REQUIRED,
                    execute = {
                        publish(HuntPhase.SELECTING_DIFFICULTY, "选择地狱")
                        visualActions.tap(
                            action = VisualAction.HUNT_SELECT_HELL,
                            operationId = "hunt.select_hell",
                            policy = OperationPolicy.reconciliationRequired(),
                            failureMessage = "未找到地狱难度按钮",
                        )
                        teamPage = waitForAnyPage(
                            expected = setOf(
                                HuntPage.TEAM_QUICK_BATTLE,
                                HuntPage.TEAM_READY,
                            ),
                            timeoutMs = PAGE_TIMEOUT_MS,
                            operations = operations,
                        )
                    },
                    recover = {
                        if (!hasIssuedGesture) {
                            StepRecovery.Fail
                        } else {
                            val page = observeHuntPage(operations)
                            if (
                                page == HuntPage.TEAM_QUICK_BATTLE ||
                                page == HuntPage.TEAM_READY
                            ) {
                                teamPage = page
                                StepRecovery.Recovered
                            } else {
                                StepRecovery.Fail
                            }
                        }
                    },
                )

                runWorkflowStep(
                    workflowId = "hunt_managed",
                    stepId = "team.disable_quick_battle_if_needed",
                    context = Unit,
                    session = session,
                    runKey = batchRunKey,
                    effectSafety = EffectSafety.RECONCILIATION_REQUIRED,
                    execute = {
                        if (teamPage == HuntPage.TEAM_QUICK_BATTLE) {
                            publish(HuntPhase.DISABLING_QUICK_BATTLE, "关闭快速战斗")
                            visualActions.tap(
                                action = VisualAction.HUNT_DISABLE_QUICK_BATTLE,
                                operationId = "hunt.disable_quick_battle",
                                policy = OperationPolicy.reconciliationRequired(),
                                failureMessage = "未找到快速战斗开关",
                            )
                            waitForPage(
                                expected = HuntPage.TEAM_READY,
                                timeoutMs = PAGE_TIMEOUT_MS,
                                operations = operations,
                            )
                        }
                    },
                    recover = {
                        recoverIfPageReached(
                            gestureIssued = hasIssuedGesture,
                            operations = operations,
                            expected = setOf(HuntPage.TEAM_READY),
                        )
                    },
                )

                runWorkflowStep(
                    workflowId = "hunt_managed",
                    stepId = "team.enable_managed_battle",
                    context = Unit,
                    session = session,
                    runKey = batchRunKey,
                    effectSafety = EffectSafety.RECONCILIATION_REQUIRED,
                    execute = {
                        publish(HuntPhase.CONFIGURING_MANAGED_BATTLE, "开启托管战斗")
                        val managedEnabled = observeManagedBattleEnabled(operations)
                        if (!managedEnabled) {
                            visualActions.tap(
                                action = VisualAction.HUNT_ENABLE_MANAGED_BATTLE,
                                operationId = "hunt.enable_managed_battle",
                                policy = OperationPolicy.reconciliationRequired(),
                                failureMessage = "未找到托管战斗开关",
                            )
                            clock.delay(AFTER_TAP_DELAY_MS)
                            if (!observeManagedBattleEnabled(operations)) {
                                throw MachineStop(
                                    HuntStopReason.LOW_CONFIDENCE,
                                    "未能确认托管战斗已开启",
                                )
                            }
                        }
                    },
                    recover = {
                        if (
                            hasIssuedGesture &&
                            observeManagedBattleEnabled(operations)
                        ) {
                            StepRecovery.Recovered
                        } else {
                            StepRecovery.Fail
                        }
                    },
                )

                runWorkflowStep(
                    workflowId = "hunt_managed",
                    stepId = "battle.start_and_verify",
                    context = Unit,
                    session = session,
                    runKey = batchRunKey,
                    effectSafety = EffectSafety.EXTERNAL_LONG_RUNNING,
                    execute = {
                        publish(HuntPhase.STARTING_BATTLE, "开始讨伐")
                        visualActions.tap(
                            action = VisualAction.HUNT_START_BATTLE,
                            operationId = "hunt.start_battle",
                            policy = OperationPolicy.externalLongRunning(),
                            failureMessage = "未找到开始讨伐按钮",
                        )
                        publish(
                            HuntPhase.WAITING_FOR_BATTLE_CONTROLS,
                            "等待战斗托管面板",
                        )
                        requireConfirmedEffect(
                            uncertainMessage =
                                "已点击开始讨伐，但未能确认战斗状态；讨伐可能已经开始",
                        ) {
                            waitForPage(
                                expected = HuntPage.BATTLE_CONTROLS,
                                timeoutMs = BATTLE_START_TIMEOUT_MS,
                                operations = operations,
                            )
                        }
                    },
                    recover = {
                        recoverIfPageReached(
                            gestureIssued = hasIssuedGesture,
                            operations = operations,
                            expected = setOf(HuntPage.BATTLE_CONTROLS),
                        )
                    },
                )

                runWorkflowStep(
                    workflowId = "hunt_managed",
                    stepId = "delegation.open",
                    context = Unit,
                    session = session,
                    runKey = batchRunKey,
                    effectSafety = EffectSafety.RECONCILIATION_REQUIRED,
                    execute = {
                        publish(HuntPhase.DELEGATING_BATTLE, "转交托管")
                        visualActions.tap(
                            action = VisualAction.HUNT_OPEN_DELEGATION,
                            operationId = "hunt.open_delegation",
                            policy = OperationPolicy.reconciliationRequired(),
                            failureMessage = "未找到托管入口",
                        )
                        waitForPage(
                            expected = HuntPage.DELEGATION_CONFIRMATION,
                            timeoutMs = PAGE_TIMEOUT_MS,
                            operations = operations,
                        )
                    },
                    recover = {
                        recoverIfPageReached(
                            gestureIssued = hasIssuedGesture,
                            operations = operations,
                            expected = setOf(HuntPage.DELEGATION_CONFIRMATION),
                        )
                    },
                )

                runWorkflowStep(
                    workflowId = "hunt_managed",
                    stepId = "delegation.confirm",
                    context = Unit,
                    session = session,
                    runKey = batchRunKey,
                    effectSafety = EffectSafety.EXTERNAL_LONG_RUNNING,
                    execute = {
                        publish(HuntPhase.CONFIRMING_DELEGATION, "确认托管")
                        visualActions.tap(
                            action = VisualAction.HUNT_CONFIRM_DELEGATION,
                            operationId = "hunt.confirm_delegation",
                            policy = OperationPolicy.externalLongRunning(),
                            failureMessage = "未找到托管确认按钮",
                        )
                        requireConfirmedEffect(
                            uncertainMessage =
                                "已点击确认托管，但未能确认托管状态；托管可能已经开始",
                        ) {
                            waitForPage(
                                expected = HuntPage.LOBBY_MANAGED,
                                timeoutMs = PAGE_TIMEOUT_MS,
                                operations = operations,
                            )
                        }
                    },
                    recover = {
                        recoverIfPageReached(
                            gestureIssued = hasIssuedGesture,
                            operations = operations,
                            expected = setOf(HuntPage.LOBBY_MANAGED),
                        )
                    },
                )

                runWorkflowStep(
                    workflowId = "hunt_managed",
                    stepId = "managed.open_panel",
                    context = Unit,
                    session = session,
                    runKey = batchRunKey,
                    effectSafety = EffectSafety.RECONCILIATION_REQUIRED,
                    execute = {
                        publish(HuntPhase.MANAGED_IN_LOBBY, "讨伐托管中")
                        visualActions.tap(
                            action = VisualAction.HUNT_OPEN_MANAGED_STATUS,
                            operationId = "hunt.open_managed_status",
                            policy = OperationPolicy.reconciliationRequired(),
                            failureMessage = "未找到托管状态入口",
                        )
                        waitForAnyPage(
                            expected = setOf(
                                HuntPage.MANAGED_PANEL,
                                HuntPage.MANAGED_COMPLETE,
                            ),
                            timeoutMs = PAGE_TIMEOUT_MS,
                            operations = operations,
                        )
                        clock.delay(MANAGED_PANEL_OPEN_DELAY_MS)
                    },
                    recover = {
                        recoverIfPageReached(
                            gestureIssued = hasIssuedGesture,
                            operations = operations,
                            expected = setOf(
                                HuntPage.MANAGED_PANEL,
                                HuntPage.MANAGED_COMPLETE,
                            ),
                        )
                    },
                )

                runWorkflowStep(
                    workflowId = "hunt_managed",
                    stepId = "managed.monitor_batch",
                    context = Unit,
                    session = session,
                    runKey = batchRunKey,
                    effectSafety = EffectSafety.EXTERNAL_LONG_RUNNING,
                    execute = {
                        val targetInBatch = minOf(
                            MAX_SUPPORTED_HUNT_RUNS,
                            config.runCount - stats.completedRuns,
                        )
                        var observedInBatch = 0
                        var previousSignature = operations
                            .capture("hunt.managed_progress_baseline")
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
                                val observation = operations
                                    .capture("hunt.observe_managed_progress")
                                    .useFrame { frame ->
                                        vision.detectPage(frame) to
                                            vision.managedProgressSignature(frame)
                                    }
                                if (observation.first == HuntPage.MANAGED_COMPLETE) {
                                    if (observedInBatch < targetInBatch) {
                                        session.diagnose(
                                            "hunt_managed_progress_uncertain_" +
                                                "${observedInBatch}_of_$targetInBatch",
                                        )
                                        throw MachineStop(
                                            HuntStopReason.LOW_CONFIDENCE,
                                            "托管已结束，但只能确认 " +
                                                "$observedInBatch/$targetInBatch 次；" +
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

                                if (
                                    candidateStablePolls >= PROGRESS_SIGNATURE_STABLE_POLLS
                                ) {
                                    previousSignature = requireNotNull(candidateSignature)
                                    candidateSignature = null
                                    candidateStablePolls = 0
                                    observedInBatch += 1
                                    stats = stats.copy(
                                        completedRuns = stats.completedRuns + 1,
                                    )
                                    publish(
                                        HuntPhase.MANAGED_IN_LOBBY,
                                        "托管中 ${stats.completedRuns}/${config.runCount}",
                                    )
                                    if (observedInBatch >= targetInBatch) {
                                        visualActions.tap(
                                            action = VisualAction.HUNT_STOP_MANAGED,
                                            operationId = "hunt.stop_managed",
                                            policy = OperationPolicy.externalLongRunning(),
                                            failureMessage = "未找到停止托管按钮",
                                        )
                                        requireConfirmedEffect(
                                            uncertainMessage =
                                                "已请求停止托管，但未能确认停止结果；" +
                                                    "游戏中的托管可能仍在运行",
                                        ) {
                                            waitForPage(
                                                expected = HuntPage.MANAGED_COMPLETE,
                                                timeoutMs = PAGE_TIMEOUT_MS,
                                                operations = operations,
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
                                    visualActions = visualActions,
                                )
                            }
                            throw error
                        }

                        if (stats.completedRuns < config.runCount) {
                            throw MachineStop(
                                HuntStopReason.LOW_CONFIDENCE,
                                "本批只能确认 ${stats.completedRuns}/${config.runCount} 次；" +
                                    "当前进度识别不支持安全开启下一批，" +
                                    "已停止以避免超额讨伐",
                            )
                        }
                    },
                )
            }

            stats = stats.copy(finishedAtElapsedMs = clock.elapsedRealtime())
            HuntMachineResult(
                reason = HuntStopReason.RUN_LIMIT_REACHED,
                stats = stats,
                message = "已完成 ${stats.completedRuns} 次讨伐",
                successful = true,
            )
        } catch (stop: MachineStop) {
            session.diagnose("hunt_${stop.reason.name}")
            stats = stats.copy(finishedAtElapsedMs = clock.elapsedRealtime())
            HuntMachineResult(
                reason = stop.reason,
                stats = stats,
                message = stop.message ?: "自动讨伐已停止",
                successful = false,
            )
        } catch (error: VisualActionNotFoundException) {
            logger.warn(
                "hunt.machine.visual_action_missing",
                "action" to error.action,
                "message" to error.message,
            )
            session.diagnose("hunt_visual_action_missing")
            stats = stats.copy(finishedAtElapsedMs = clock.elapsedRealtime())
            HuntMachineResult(
                reason = HuntStopReason.LOW_CONFIDENCE,
                stats = stats,
                message = error.message ?: "未找到可点击控件",
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
            session.diagnose("hunt_${reason.name}")
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
        operations: OperationExecutor,
    ): HuntPage = waitForAnyPage(
        expected = setOf(expected),
        timeoutMs = timeoutMs,
        operations = operations,
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
        operations: OperationExecutor,
    ): HuntPage {
        val expectedLabel = expected.joinToString("_")
        try {
            return operations.waitUntil(
                operationId = "hunt.wait_${expectedLabel.lowercase()}",
                timeoutMs = timeoutMs,
                pollIntervalMs = PAGE_POLL_INTERVAL_MS,
                diagnosticReason = "hunt_wait_$expectedLabel",
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

    private suspend fun observeHuntPage(
        operations: OperationExecutor,
    ): HuntPage = operations.capture("hunt.reconcile_page").useFrame { frame ->
        vision.detectPage(frame)
    }

    private suspend fun observeManagedBattleEnabled(
        operations: OperationExecutor,
    ): Boolean = operations.capture("hunt.observe_managed_enabled").useFrame { frame ->
        vision.isManagedBattleEnabled(frame)
    }

    private suspend fun recoverIfPageReached(
        gestureIssued: Boolean,
        operations: OperationExecutor,
        expected: Set<HuntPage>,
    ): StepRecovery {
        if (!gestureIssued) return StepRecovery.Fail
        return if (observeHuntPage(operations) in expected) {
            StepRecovery.Recovered
        } else {
            StepRecovery.Fail
        }
    }

    private suspend fun selectDungeon(
        dungeon: HuntDungeon,
        operations: OperationExecutor,
        visualActions: VisualActionExecutor,
        session: AutomationSession,
    ) {
        suspend fun findAndTap(): Boolean {
            operations.awaitActive()
            val match = operations.capture("hunt.find_${dungeon.name.lowercase()}")
                .useFrame { frame ->
                vision.findDungeon(frame, dungeon)
            }
            logger.debug(
                "hunt.dungeon.match",
                "dungeon" to dungeon,
                "score" to match.confidence,
                "matched" to match.matched,
            )
            if (!match.matched || match.center == null) return false
            visualActions.tapLocated(
                operationId = "hunt.select_${dungeon.name.lowercase()}",
                targetLabel = "dungeon_${dungeon.name.lowercase()}",
                match = match,
                policy = OperationPolicy.reconciliationRequired(),
                failureMessage = "地下城定位结果无效：${dungeon.displayName}",
            )
            return true
        }

        if (findAndTap()) return

        repeat(DUNGEON_RESET_SWIPES) {
            visualActions.swipe(
                from = DUNGEON_SCROLL_TOP,
                to = DUNGEON_SCROLL_BOTTOM,
                operationId = "hunt.reset_dungeon_list",
                durationMs = DUNGEON_SCROLL_DURATION_MS,
                policy = OperationPolicy.idempotent(),
            )
            clock.delay(AFTER_DUNGEON_SCROLL_DELAY_MS)
        }

        repeat(DUNGEON_SEARCH_PAGES) { pageIndex ->
            if (findAndTap()) return
            if (pageIndex < DUNGEON_SEARCH_PAGES - 1) {
                visualActions.swipe(
                    from = DUNGEON_SCROLL_BOTTOM,
                    to = DUNGEON_SCROLL_TOP,
                    operationId = "hunt.scroll_dungeon_list",
                    durationMs = DUNGEON_SCROLL_DURATION_MS,
                    policy = OperationPolicy.idempotent(),
                )
                clock.delay(AFTER_DUNGEON_SCROLL_DELAY_MS)
            }
        }

        session.diagnose("hunt_dungeon_${dungeon.name.lowercase()}_not_found")
        throw MachineStop(
            HuntStopReason.LOW_CONFIDENCE,
            "未找到地下城：${dungeon.displayName}",
        )
    }

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
        visualActions: VisualActionExecutor,
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
                    visualActions.tap(
                        action = VisualAction.HUNT_STOP_MANAGED,
                        operationId = "hunt.stop_managed_after_timeout",
                        policy = OperationPolicy.externalLongRunning(),
                        failureMessage = "未找到停止托管按钮",
                    )
                    requireConfirmedEffect(
                        uncertainMessage =
                            "托管监控已超时，已请求停止但无法确认结果；托管可能仍在运行",
                    ) {
                        waitForPage(
                            expected = HuntPage.MANAGED_COMPLETE,
                            timeoutMs = PAGE_TIMEOUT_MS,
                            operations = operations,
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

    private companion object {
        val DUNGEON_SCROLL_TOP = ScreenRatioPoint(0.89, 0.27)
        val DUNGEON_SCROLL_BOTTOM = ScreenRatioPoint(0.89, 0.89)
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
