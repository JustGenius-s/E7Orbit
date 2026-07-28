package com.e7orbit.automation

import com.e7orbit.logging.NoOpOrbitLogger
import com.e7orbit.logging.OrbitLogger
import com.e7orbit.model.MatchResult
import com.e7orbit.model.VisualAction

enum class HomeNavigationFailure {
    SCREENSHOT_FAILED,
    INVALID_RESOLUTION,
    UI_STATE_MISMATCH,
    LOW_CONFIDENCE,
    TIMEOUT,
    GESTURE_FAILED,
    UNCERTAIN_EFFECT,
}

class HomeNavigationException(
    val failure: HomeNavigationFailure,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class HomeNavigator(
    private val vision: GlobalUiVision,
    private val clock: AutomationClock = SystemAutomationClock,
    private val logger: OrbitLogger = NoOpOrbitLogger,
) {
    private val navigationWorkflow: Workflow<HomeWorkflowContext> by lazy {
        buildNavigationWorkflow()
    }

    fun health(): VisionHealth = vision.navigationHealth()

    suspend fun ensureHome(
        session: AutomationSession,
        onStatus: (String) -> Unit,
    ) {
        val context = HomeWorkflowContext(
            navigator = this,
            onStatus = onStatus,
        )
        try {
            navigationWorkflow.run(context, session)
            logger.info(
                "home.navigation.completed",
                "session" to session.sessionId,
                "gesture" to session.latestGestureReceipt()?.token?.value,
            )
        } catch (error: OperationExecutionException) {
            throw error.asHomeNavigationException()
        } catch (error: UiStateMismatchException) {
            throw HomeNavigationException(
                failure = HomeNavigationFailure.TIMEOUT,
                message = "等待游戏主页加载超时",
                cause = error,
            )
        } catch (error: VisualActionNotFoundException) {
            throw HomeNavigationException(
                failure = HomeNavigationFailure.LOW_CONFIDENCE,
                message = error.message ?: "未找到导航按钮",
                cause = error,
            )
        }
    }

    private fun buildNavigationWorkflow(): Workflow<HomeWorkflowContext> =
        workflow("home_navigation") {
            defaults {
                diagnoseOnFailure = true
                maxAttempts = 1
            }

            stage("location") {
                step("detect", effectSafety = EffectSafety.READ_ONLY) {
                    execute {
                        if (context.navigator.observeHome(session).confirmedOrNull() != null) {
                            context.navigator.logger.debug("home.navigation.already_home")
                            completeWorkflow()
                        }
                        context.returnHome = context.navigator
                            .observeAction(session, VisualAction.RETURN_TO_LOBBY)
                            .confirmedOrNull()
                    }
                }
            }

            stage("menu") {
                step(
                    id = "open_if_needed",
                    effectSafety = EffectSafety.RECONCILIATION_REQUIRED,
                ) {
                    execute {
                        if (context.returnHome != null) return@execute
                        context.onStatus("打开快捷菜单")
                        val openMenu = context.navigator.waitForAction(
                            session = session,
                            action = VisualAction.OPEN_MENU,
                            timeoutMs = OPEN_MENU_TIMEOUT_MS,
                        )
                        context.navigator.tapMatch(
                            session = session,
                            operationId = "home.open_menu",
                            match = openMenu,
                            failureMessage = "打开快捷菜单失败",
                        )
                        session.clock.delay(AFTER_TAP_DELAY_MS)
                        context.onStatus("选择返回主页")
                        context.returnHome = context.navigator.waitForAction(
                            session = session,
                            action = VisualAction.RETURN_TO_LOBBY,
                            timeoutMs = MENU_OPEN_TIMEOUT_MS,
                        )
                    }
                    recover {
                        val observed = context.navigator
                            .observeAction(session, VisualAction.RETURN_TO_LOBBY)
                            .confirmedOrNull()
                        if (observed != null) {
                            context.returnHome = observed
                            StepRecovery.Recovered
                        } else {
                            StepRecovery.Fail
                        }
                    }
                }
            }

            stage("return") {
                step(
                    id = "tap_and_verify",
                    effectSafety = EffectSafety.RECONCILIATION_REQUIRED,
                ) {
                    execute {
                        context.onStatus("正在返回游戏主页")
                        val returnHome = context.returnHome ?: throw HomeNavigationException(
                            HomeNavigationFailure.LOW_CONFIDENCE,
                            "未找到返回主页按钮",
                        )
                        context.navigator.tapMatch(
                            session = session,
                            operationId = "home.return_to_lobby",
                            match = returnHome,
                            failureMessage = "返回主页失败",
                        )
                        context.onStatus("等待游戏主页加载")
                        context.navigator.waitForHome(session)
                    }
                    recover {
                        if (context.navigator.observeHome(session).confirmedOrNull() != null) {
                            StepRecovery.Recovered
                        } else {
                            StepRecovery.Fail
                        }
                    }
                }
            }
        }

    private suspend fun observeHome(
        session: AutomationSession,
    ): Observation<GameUiPage> {
        val snapshot = session.currentUiSnapshot()
        return if (snapshot.isStable && snapshot.page == GameUiPage.LOBBY) {
            Observation.Confirmed(snapshot.page, snapshot.confidence)
        } else {
            Observation.Absent("当前不在主页")
        }
    }

    private suspend fun observeAction(
        session: AutomationSession,
        action: VisualAction,
    ): Observation<MatchResult> = session.executor
        .capture("home.find_${action.name.lowercase()}")
        .use { frame ->
            val match = vision.findAction(frame, action)
            if (match.matched && match.center != null) {
                Observation.Confirmed(match, match.confidence)
            } else {
                Observation.Absent("未找到 ${action.name}")
            }
        }

    private suspend fun waitForAction(
        session: AutomationSession,
        action: VisualAction,
        timeoutMs: Long,
    ): MatchResult = session.executor.waitUntil(
        operationId = "home.wait_${action.name.lowercase()}",
        timeoutMs = timeoutMs,
        pollIntervalMs = POLL_INTERVAL_MS,
        diagnosticReason = "home_navigation_action_${action.name.lowercase()}_timeout",
    ) {
        observeAction(session, action).confirmedOrNull()
    }

    private suspend fun waitForHome(
        session: AutomationSession,
    ) {
        session.awaitUi(
            contract = TaskUiContract(
                task = session.currentTaskKind() ?: TaskKind.SHOP,
                step = "home.wait_lobby",
                allowedPages = setOf(GameUiPage.LOBBY),
            ),
            timeoutMs = HOME_TIMEOUT_MS,
        )
    }

    private suspend fun tapMatch(
        session: AutomationSession,
        operationId: String,
        match: MatchResult,
        failureMessage: String,
    ) {
        VisualActionExecutor(
            operations = session.executor,
            vision = vision,
            namespace = "home",
            logger = logger,
        ).tapLocated(
            operationId = operationId,
            targetLabel = operationId,
            match = match,
            policy = OperationPolicy.reconciliationRequired(),
            failureMessage = failureMessage,
        )
    }

    private fun OperationExecutionException.asHomeNavigationException():
        HomeNavigationException {
        val navigationFailure = when (failure.kind) {
            ExecutionFailureKind.SCREENSHOT_FAILED ->
                HomeNavigationFailure.SCREENSHOT_FAILED
            ExecutionFailureKind.INVALID_RESOLUTION ->
                HomeNavigationFailure.INVALID_RESOLUTION
            ExecutionFailureKind.UI_STATE_MISMATCH ->
                HomeNavigationFailure.UI_STATE_MISMATCH
            ExecutionFailureKind.TIMEOUT -> HomeNavigationFailure.TIMEOUT
            ExecutionFailureKind.GESTURE_FAILED -> HomeNavigationFailure.GESTURE_FAILED
            ExecutionFailureKind.UNCERTAIN_EFFECT ->
                HomeNavigationFailure.UNCERTAIN_EFFECT
        }
        return HomeNavigationException(
            failure = navigationFailure,
            message = message ?: "返回主页失败",
            cause = this,
        )
    }

    private data class HomeWorkflowContext(
        val navigator: HomeNavigator,
        val onStatus: (String) -> Unit,
        var returnHome: MatchResult? = null,
    )

    private companion object {
        const val OPEN_MENU_TIMEOUT_MS = 30_000L
        const val MENU_OPEN_TIMEOUT_MS = 10_000L
        const val HOME_TIMEOUT_MS = 30_000L
        const val POLL_INTERVAL_MS = 500L
        const val AFTER_TAP_DELAY_MS = 800L
    }
}
