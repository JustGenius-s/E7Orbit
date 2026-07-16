package com.e7orbit.automation

import com.e7orbit.logging.NoOpOrbitLogger
import com.e7orbit.logging.OrbitLogger
import com.e7orbit.model.GestureResult
import com.e7orbit.model.MatchResult
import com.e7orbit.model.REFERENCE_HEIGHT
import com.e7orbit.model.REFERENCE_WIDTH
import com.e7orbit.model.ScreenFrame
import com.e7orbit.model.ShopAction
import com.e7orbit.model.ShopPage

enum class HomeNavigationFailure {
    SCREENSHOT_FAILED,
    INVALID_RESOLUTION,
    LOW_CONFIDENCE,
    TIMEOUT,
    GESTURE_FAILED,
}

class HomeNavigationException(
    val failure: HomeNavigationFailure,
    message: String,
) : RuntimeException(message)

class HomeNavigator(
    private val vision: ShopVision,
    private val clock: AutomationClock = SystemAutomationClock,
    private val logger: OrbitLogger = NoOpOrbitLogger,
) {
    fun health(): VisionHealth = vision.health()

    suspend fun ensureHome(
        gateway: ScreenGateway,
        awaitRunPermission: suspend () -> Unit,
        onStatus: (String) -> Unit,
        onDiagnostic: suspend (ScreenFrame, String) -> Unit,
    ) {
        if (isHome(gateway, awaitRunPermission)) {
            logger.debug("home.navigation.already_home")
            return
        }

        onStatus("正在返回游戏主页")
        var returnHome = findAction(
            action = ShopAction.RETURN_HOME,
            gateway = gateway,
            awaitRunPermission = awaitRunPermission,
        )
        if (!returnHome.matched) {
            onStatus("打开快捷菜单")
            val openMenu = waitForAction(
                action = ShopAction.OPEN_MAIN_MENU,
                timeoutMs = OPEN_MENU_TIMEOUT_MS,
                gateway = gateway,
                awaitRunPermission = awaitRunPermission,
                onDiagnostic = onDiagnostic,
            )
            tapMatch(
                match = openMenu,
                failureMessage = "打开快捷菜单失败",
                gateway = gateway,
                awaitRunPermission = awaitRunPermission,
            )
            clock.delay(AFTER_TAP_DELAY_MS)
            onStatus("选择返回主页")
            returnHome = waitForAction(
                action = ShopAction.RETURN_HOME,
                timeoutMs = MENU_OPEN_TIMEOUT_MS,
                gateway = gateway,
                awaitRunPermission = awaitRunPermission,
                onDiagnostic = onDiagnostic,
            )
        }

        tapMatch(
            match = returnHome,
            failureMessage = "返回主页失败",
            gateway = gateway,
            awaitRunPermission = awaitRunPermission,
        )
        onStatus("等待游戏主页加载")
        waitForHome(
            gateway = gateway,
            awaitRunPermission = awaitRunPermission,
            onDiagnostic = onDiagnostic,
        )
        logger.info("home.navigation.completed")
    }

    private suspend fun isHome(
        gateway: ScreenGateway,
        awaitRunPermission: suspend () -> Unit,
    ): Boolean {
        awaitRunPermission()
        return captureChecked(gateway).use { frame ->
            vision.detectPage(frame) == ShopPage.LOBBY
        }
    }

    private suspend fun findAction(
        action: ShopAction,
        gateway: ScreenGateway,
        awaitRunPermission: suspend () -> Unit,
    ): MatchResult {
        awaitRunPermission()
        return captureChecked(gateway).use { frame ->
            vision.findAction(frame, action)
        }
    }

    private suspend fun waitForAction(
        action: ShopAction,
        timeoutMs: Long,
        gateway: ScreenGateway,
        awaitRunPermission: suspend () -> Unit,
        onDiagnostic: suspend (ScreenFrame, String) -> Unit,
    ): MatchResult {
        val startedAt = clock.elapsedRealtime()
        while (clock.elapsedRealtime() - startedAt < timeoutMs) {
            val match = findAction(action, gateway, awaitRunPermission)
            if (match.matched && match.center != null) return match
            clock.delay(POLL_INTERVAL_MS)
        }
        diagnose(gateway, "action_${action.name.lowercase()}_timeout", onDiagnostic)
        throw HomeNavigationException(
            HomeNavigationFailure.TIMEOUT,
            "等待 ${action.name} 超时",
        )
    }

    private suspend fun waitForHome(
        gateway: ScreenGateway,
        awaitRunPermission: suspend () -> Unit,
        onDiagnostic: suspend (ScreenFrame, String) -> Unit,
    ) {
        var matches = 0
        val startedAt = clock.elapsedRealtime()
        while (clock.elapsedRealtime() - startedAt < HOME_TIMEOUT_MS) {
            if (isHome(gateway, awaitRunPermission)) {
                matches += 1
                if (matches >= REQUIRED_HOME_MATCHES) return
            } else {
                matches = 0
            }
            clock.delay(POLL_INTERVAL_MS)
        }
        diagnose(gateway, "home_timeout", onDiagnostic)
        throw HomeNavigationException(
            HomeNavigationFailure.TIMEOUT,
            "等待游戏主页超时",
        )
    }

    private suspend fun tapMatch(
        match: MatchResult,
        failureMessage: String,
        gateway: ScreenGateway,
        awaitRunPermission: suspend () -> Unit,
    ) {
        val point = match.center ?: throw HomeNavigationException(
            HomeNavigationFailure.LOW_CONFIDENCE,
            failureMessage,
        )
        repeat(GESTURE_MAX_ATTEMPTS) { attempt ->
            awaitRunPermission()
            when (val result = gateway.tap(point)) {
                GestureResult.COMPLETED -> return
                GestureResult.CANCELLED -> {
                    if (attempt < GESTURE_MAX_ATTEMPTS - 1) {
                        clock.delay(GESTURE_RETRY_DELAY_MS)
                    } else {
                        throw HomeNavigationException(
                            HomeNavigationFailure.GESTURE_FAILED,
                            "$failureMessage：$result",
                        )
                    }
                }

                GestureResult.REJECTED -> throw HomeNavigationException(
                    HomeNavigationFailure.GESTURE_FAILED,
                    "$failureMessage：$result",
                )
            }
        }
    }

    private suspend fun captureChecked(gateway: ScreenGateway): ScreenFrame {
        val frame = try {
            gateway.capture()
        } catch (error: Exception) {
            throw HomeNavigationException(
                HomeNavigationFailure.SCREENSHOT_FAILED,
                "截图失败：${error.message.orEmpty()}",
            )
        }
        if (frame.width != REFERENCE_WIDTH || frame.height != REFERENCE_HEIGHT) {
            frame.close()
            throw HomeNavigationException(
                HomeNavigationFailure.INVALID_RESOLUTION,
                "需要 ${REFERENCE_WIDTH}×${REFERENCE_HEIGHT}，" +
                    "当前为 ${frame.width}×${frame.height}",
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
            gateway.capture().use { frame ->
                onDiagnostic(frame, "home_navigation_$reason")
            }
        }.onFailure { error ->
            logger.error("home.navigation.diagnostic_failed", error, "reason" to reason)
        }
    }

    private companion object {
        const val OPEN_MENU_TIMEOUT_MS = 30_000L
        const val MENU_OPEN_TIMEOUT_MS = 10_000L
        const val HOME_TIMEOUT_MS = 30_000L
        const val POLL_INTERVAL_MS = 500L
        const val AFTER_TAP_DELAY_MS = 800L
        const val REQUIRED_HOME_MATCHES = 2
        const val GESTURE_MAX_ATTEMPTS = 3
        const val GESTURE_RETRY_DELAY_MS = 160L
    }
}
