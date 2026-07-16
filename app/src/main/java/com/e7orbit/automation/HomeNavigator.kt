package com.e7orbit.automation

import com.e7orbit.logging.NoOpOrbitLogger
import com.e7orbit.logging.OrbitLogger
import com.e7orbit.model.GameLocation
import com.e7orbit.model.GlobalAction
import com.e7orbit.model.MatchResult
import com.e7orbit.model.ScreenFrame

enum class HomeNavigationFailure {
    SCREENSHOT_FAILED,
    INVALID_RESOLUTION,
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
    fun health(): VisionHealth = vision.navigationHealth()

    suspend fun ensureHome(
        gateway: ScreenGateway,
        awaitRunPermission: suspend () -> Unit,
        onStatus: (String) -> Unit,
        onDiagnostic: suspend (ScreenFrame, String) -> Unit,
    ) {
        val executor = OperationExecutor(
            gateway = gateway,
            clock = clock,
            awaitRunPermission = awaitRunPermission,
            onDiagnostic = { frame, reason ->
                onDiagnostic(frame, "home_navigation_$reason")
            },
            logger = logger,
        )
        try {
            ensureHome(executor, onStatus)
        } catch (error: OperationExecutionException) {
            throw error.asHomeNavigationException()
        }
    }

    private suspend fun ensureHome(
        executor: OperationExecutor,
        onStatus: (String) -> Unit,
    ) {
        if (isHome(executor)) {
            logger.debug("home.navigation.already_home")
            return
        }

        onStatus("正在返回游戏主页")
        var returnHome = findAction(
            action = GlobalAction.RETURN_TO_LOBBY,
            executor = executor,
        )
        if (!returnHome.matched) {
            onStatus("打开快捷菜单")
            val openMenu = waitForAction(
                action = GlobalAction.OPEN_MENU,
                timeoutMs = OPEN_MENU_TIMEOUT_MS,
                executor = executor,
            )
            tapMatch(
                operationId = "home.open_menu",
                match = openMenu,
                failureMessage = "打开快捷菜单失败",
                executor = executor,
            )
            clock.delay(AFTER_TAP_DELAY_MS)
            onStatus("选择返回主页")
            returnHome = waitForAction(
                action = GlobalAction.RETURN_TO_LOBBY,
                timeoutMs = MENU_OPEN_TIMEOUT_MS,
                executor = executor,
            )
        }

        tapMatch(
            operationId = "home.return_to_lobby",
            match = returnHome,
            failureMessage = "返回主页失败",
            executor = executor,
        )
        onStatus("等待游戏主页加载")
        waitForHome(executor)
        logger.info("home.navigation.completed")
    }

    private suspend fun isHome(
        executor: OperationExecutor,
    ): Boolean = executor.capture("home.detect_location").use { frame ->
        vision.detectLocation(frame) == GameLocation.LOBBY
    }

    private suspend fun findAction(
        action: GlobalAction,
        executor: OperationExecutor,
    ): MatchResult = executor.capture("home.find_${action.name.lowercase()}").use { frame ->
        vision.findGlobalAction(frame, action)
    }

    private suspend fun waitForAction(
        action: GlobalAction,
        timeoutMs: Long,
        executor: OperationExecutor,
    ): MatchResult = executor.waitUntil(
        operationId = "home.wait_${action.name.lowercase()}",
        timeoutMs = timeoutMs,
        pollIntervalMs = POLL_INTERVAL_MS,
        diagnosticReason = "action_${action.name.lowercase()}_timeout",
    ) {
        findAction(action, executor).takeIf { match ->
            match.matched && match.center != null
        }
    }

    private suspend fun waitForHome(
        executor: OperationExecutor,
    ) {
        var matches = 0
        executor.waitUntil(
            operationId = "home.wait_lobby",
            timeoutMs = HOME_TIMEOUT_MS,
            pollIntervalMs = POLL_INTERVAL_MS,
            diagnosticReason = "home_timeout",
        ) {
            if (isHome(executor)) {
                matches += 1
            } else {
                matches = 0
            }
            Unit.takeIf { matches >= REQUIRED_HOME_MATCHES }
        }
    }

    private suspend fun tapMatch(
        operationId: String,
        match: MatchResult,
        failureMessage: String,
        executor: OperationExecutor,
    ) {
        val point = match.center ?: throw HomeNavigationException(
            HomeNavigationFailure.LOW_CONFIDENCE,
            failureMessage,
        )
        executor.tap(
            operationId = operationId,
            point = point,
            policy = OperationPolicy.reconciliationRequired(),
        )
    }

    private fun OperationExecutionException.asHomeNavigationException():
        HomeNavigationException {
        val navigationFailure = when (failure.kind) {
            ExecutionFailureKind.SCREENSHOT_FAILED ->
                HomeNavigationFailure.SCREENSHOT_FAILED
            ExecutionFailureKind.INVALID_RESOLUTION ->
                HomeNavigationFailure.INVALID_RESOLUTION
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

    private companion object {
        const val OPEN_MENU_TIMEOUT_MS = 30_000L
        const val MENU_OPEN_TIMEOUT_MS = 10_000L
        const val HOME_TIMEOUT_MS = 30_000L
        const val POLL_INTERVAL_MS = 500L
        const val AFTER_TAP_DELAY_MS = 800L
        const val REQUIRED_HOME_MATCHES = 2
    }
}
