package com.e7orbit.automation

import android.os.SystemClock
import com.e7orbit.logging.NoOpOrbitLogger
import com.e7orbit.logging.OrbitLogger
import com.e7orbit.model.AutomationPhase
import com.e7orbit.model.COVENANT_BOOKMARK_GOLD_COST
import com.e7orbit.model.ItemType
import com.e7orbit.model.MYSTIC_MEDAL_GOLD_COST
import com.e7orbit.model.REFERENCE_HEIGHT
import com.e7orbit.model.REFERENCE_WIDTH
import com.e7orbit.model.RunConfig
import com.e7orbit.model.RunStats
import com.e7orbit.model.ScreenFrame
import com.e7orbit.model.ScreenPoint
import com.e7orbit.model.ShopAction
import com.e7orbit.model.ShopPage
import com.e7orbit.model.StopReason
import com.e7orbit.vision.PointConfig
import com.e7orbit.vision.VisionConfig
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException

data class MachineResult(
    val reason: StopReason,
    val stats: RunStats,
    val message: String,
    val successful: Boolean,
)

class BookmarkStateMachine(
    private val vision: ShopVision,
    private val visionConfig: VisionConfig,
    private val clock: AutomationClock = SystemAutomationClock,
    private val logger: OrbitLogger = NoOpOrbitLogger,
    private val homeNavigator: HomeNavigator? = null,
) {
    suspend fun run(
        config: RunConfig,
        gateway: ScreenGateway,
        awaitRunPermission: suspend () -> Unit,
        onStatus: (AutomationPhase, RunStats, String, Double?) -> Unit,
        onDiagnostic: suspend (ScreenFrame, String) -> Unit,
    ): MachineResult {
        var stats = RunStats(startedAtElapsedMs = clock.elapsedRealtime())
        val operations = OperationExecutor(
            gateway = gateway,
            clock = clock,
            awaitRunPermission = awaitRunPermission,
            onDiagnostic = onDiagnostic,
            logger = logger,
        )
        logger.info(
            "machine.started",
            "maxRefreshes" to config.maxRefreshes,
            "threshold" to config.matchThreshold,
        )

        fun publish(
            phase: AutomationPhase,
            message: String,
            confidence: Double? = null,
        ) {
            logger.debug(
                "machine.phase",
                "phase" to phase,
                "message" to message,
                "confidence" to confidence,
            )
            onStatus(phase, stats, message, confidence)
        }

        return try {
            navigateHomeIfNeeded(
                gateway = gateway,
                awaitRunPermission = awaitRunPermission,
                publish = ::publish,
                onDiagnostic = onDiagnostic,
            )
            publish(AutomationPhase.WAITING_FOR_SHOP, "等待主页或秘密商店")
            when (
                waitForAnyPage(
                    expected = setOf(ShopPage.LOBBY, ShopPage.SHOP),
                    timeoutMs = WAIT_FOR_SHOP_TIMEOUT_MS,
                    gateway = gateway,
                    awaitRunPermission = awaitRunPermission,
                    onDiagnostic = onDiagnostic,
                )
            ) {
                ShopPage.LOBBY -> {
                    publish(AutomationPhase.WAITING_FOR_SHOP, "从主页进入秘密商店")
                    tapAction(
                        action = ShopAction.OPEN_SECRET_SHOP,
                        operations = operations,
                        policy = OperationPolicy.reconciliationRequired(),
                        failureMessage = "未找到主页秘密商店入口",
                    )
                    waitForPage(
                        expected = ShopPage.SHOP,
                        timeoutMs = PAGE_TIMEOUT_MS,
                        consecutiveMatches = 2,
                        gateway = gateway,
                        awaitRunPermission = awaitRunPermission,
                        onDiagnostic = onDiagnostic,
                    )
                }

                ShopPage.SHOP -> {
                    waitForPage(
                        expected = ShopPage.SHOP,
                        timeoutMs = PAGE_TIMEOUT_MS,
                        consecutiveMatches = 1,
                        gateway = gateway,
                        awaitRunPermission = awaitRunPermission,
                        onDiagnostic = onDiagnostic,
                    )
                }

                else -> error("不可达的商店入口状态")
            }

            while (stats.completedRefreshes < config.maxRefreshes) {
                awaitRunPermission()
                stats = stats.copy(shopPagesScanned = stats.shopPagesScanned + 1)
                publish(
                    AutomationPhase.SCANNING_TOP,
                    "扫描上半页 ${stats.completedRefreshes + 1}/${config.maxRefreshes}",
                )
                stats = scanAndPurchase(
                    config = config,
                    stats = stats,
                    gateway = gateway,
                    awaitRunPermission = awaitRunPermission,
                    operations = operations,
                    publish = ::publish,
                    onDiagnostic = onDiagnostic,
                )

                awaitRunPermission()
                publish(AutomationPhase.SCANNING_BOTTOM, "滑动并扫描下半页")
                val scrollFrom = visionConfig.scrollFrom.toCapturePoint()
                val scrollTo = visionConfig.scrollTo.toCapturePoint()
                val scrollResult = operations.swipe(
                    operationId = "shop.scroll",
                    from = scrollFrom,
                    to = scrollTo,
                    durationMs = SCROLL_DURATION_MS,
                    policy = OperationPolicy.idempotent(),
                )
                logger.info(
                    "gesture.scroll",
                    "from" to "${scrollFrom.x},${scrollFrom.y}",
                    "to" to "${scrollTo.x},${scrollTo.y}",
                    "durationMs" to SCROLL_DURATION_MS,
                    "result" to scrollResult,
                )
                clock.delay(AFTER_SCROLL_DELAY_MS)
                stats = scanAndPurchase(
                    config = config,
                    stats = stats,
                    gateway = gateway,
                    awaitRunPermission = awaitRunPermission,
                    operations = operations,
                    publish = ::publish,
                    onDiagnostic = onDiagnostic,
                )

                awaitRunPermission()
                publish(AutomationPhase.REFRESHING, "准备刷新秘密商店")
                tapAction(
                    action = ShopAction.REFRESH,
                    operations = operations,
                    policy = OperationPolicy.reconciliationRequired(),
                    failureMessage = "未找到刷新按钮",
                )
                when (
                    waitForAnyPage(
                        expected = setOf(
                            ShopPage.REFRESH_CONFIRMATION,
                            ShopPage.RESOURCE_INSUFFICIENT,
                        ),
                        timeoutMs = DIALOG_TIMEOUT_MS,
                        gateway = gateway,
                        awaitRunPermission = awaitRunPermission,
                        onDiagnostic = onDiagnostic,
                    )
                ) {
                    ShopPage.RESOURCE_INSUFFICIENT -> {
                        throw MachineStop(
                            StopReason.RESOURCE_INSUFFICIENT,
                            "天空石不足，已安全停止",
                        )
                    }

                    ShopPage.REFRESH_CONFIRMATION -> Unit
                    else -> error("不可达的刷新状态")
                }

                tapAction(
                    action = ShopAction.CONFIRM_REFRESH,
                    operations = operations,
                    policy = OperationPolicy.reconciliationRequired(),
                    failureMessage = "未找到刷新确认按钮",
                )
                publish(AutomationPhase.WAITING_FOR_REFRESH, "等待新商品加载")
                requireConfirmedEffect(
                    uncertainMessage = "已点击刷新确认，但未能确认刷新结果；天空石可能已消耗",
                ) {
                    waitForPage(
                        expected = ShopPage.SHOP,
                        timeoutMs = PAGE_TIMEOUT_MS,
                        consecutiveMatches = 2,
                        gateway = gateway,
                        awaitRunPermission = awaitRunPermission,
                        onDiagnostic = onDiagnostic,
                    )
                }
                stats = stats.copy(
                    completedRefreshes = stats.completedRefreshes + 1,
                )
            }

            stats = stats.copy(finishedAtElapsedMs = clock.elapsedRealtime())
            MachineResult(
                reason = StopReason.REFRESH_LIMIT_REACHED,
                stats = stats,
                message = "已完成 ${stats.completedRefreshes} 次刷新",
                successful = true,
            )
        } catch (stop: MachineStop) {
            logger.warn(
                "machine.stopped",
                "reason" to stop.reason,
                "message" to stop.message,
                "refreshes" to stats.completedRefreshes,
            )
            diagnose(gateway, stop.reason.name, onDiagnostic)
            stats = stats.copy(finishedAtElapsedMs = clock.elapsedRealtime())
            MachineResult(
                reason = stop.reason,
                stats = stats,
                message = stop.message ?: "自动化已停止",
                successful = false,
            )
        } catch (error: OperationExecutionException) {
            val reason = error.failure.kind.toStopReason()
            logger.warn(
                "machine.operation_failed",
                "operation" to error.failure.operationId,
                "kind" to error.failure.kind,
                "message" to error.message,
                "refreshes" to stats.completedRefreshes,
            )
            diagnose(gateway, reason.name, onDiagnostic)
            stats = stats.copy(finishedAtElapsedMs = clock.elapsedRealtime())
            MachineResult(
                reason = reason,
                stats = stats,
                message = error.message ?: "自动化操作失败",
                successful = false,
            )
        }
    }

    private suspend fun scanAndPurchase(
        config: RunConfig,
        stats: RunStats,
        gateway: ScreenGateway,
        awaitRunPermission: suspend () -> Unit,
        operations: OperationExecutor,
        publish: (AutomationPhase, String, Double?) -> Unit,
        onDiagnostic: suspend (ScreenFrame, String) -> Unit,
    ): RunStats {
        var currentStats = stats
        val initialTargets = captureChecked(gateway).use { frame ->
            val page = vision.detectPage(frame)
            logger.debug(
                "scan.page",
                "sequence" to frame.sequence,
                "page" to page,
                "phase" to "initial",
            )
            when (page) {
                ShopPage.RESOURCE_INSUFFICIENT -> throw MachineStop(
                    StopReason.RESOURCE_INSUFFICIENT,
                    "资源不足，已安全停止",
                )

                ShopPage.SHOP -> vision.findTargets(frame, config)
                else -> {
                    try {
                        onDiagnostic(frame, "unknown_page_scan_${frame.sequence}")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        logger.error(
                            "diagnostic.save_failed",
                            error,
                            "sequence" to frame.sequence,
                        )
                    }
                    logger.warn(
                        "scan.page_rejected",
                        "sequence" to frame.sequence,
                        "page" to page,
                    )
                    throw MachineStop(StopReason.UNKNOWN_PAGE, "当前不是秘密商店页面")
                }
            }
        }
        logger.info(
            "scan.targets",
            "count" to initialTargets.size,
            "targets" to initialTargets.joinToString {
                "${it.type.name}@${it.purchaseButton.x},${it.purchaseButton.y}:${it.confidence}"
            },
        )

        initialTargets.forEach { originalTarget ->
            awaitRunPermission()
            val currentTarget = captureChecked(gateway).use { frame ->
                val targets = vision.findTargets(frame, config)
                logger.debug(
                    "purchase.revalidate",
                    "sequence" to frame.sequence,
                    "originalType" to originalTarget.type,
                    "originalY" to originalTarget.itemBounds.center.y,
                    "candidateCount" to targets.size,
                )
                targets
                    .filter { it.type == originalTarget.type }
                    .minByOrNull { abs(it.itemBounds.center.y - originalTarget.itemBounds.center.y) }
                    ?.takeIf {
                        abs(it.itemBounds.center.y - originalTarget.itemBounds.center.y) <=
                            TARGET_REVALIDATE_TOLERANCE_PX
                    }
            } ?: return@forEach

            publish(
                AutomationPhase.PURCHASING,
                "购买${currentTarget.type.displayName()}",
                currentTarget.confidence,
            )
            val purchaseResult = operations.tap(
                operationId = "shop.open_purchase_confirmation",
                point = currentTarget.purchaseButton,
                policy = OperationPolicy.reconciliationRequired(),
            )
            logger.info(
                "gesture.purchase_button",
                "type" to currentTarget.type,
                "point" to "${currentTarget.purchaseButton.x},${currentTarget.purchaseButton.y}",
                "confidence" to currentTarget.confidence,
                "result" to purchaseResult,
            )

            when (
                waitForAnyPage(
                    expected = setOf(
                        ShopPage.PURCHASE_CONFIRMATION,
                        ShopPage.RESOURCE_INSUFFICIENT,
                    ),
                    timeoutMs = DIALOG_TIMEOUT_MS,
                    gateway = gateway,
                    awaitRunPermission = awaitRunPermission,
                    onDiagnostic = onDiagnostic,
                )
            ) {
                ShopPage.RESOURCE_INSUFFICIENT -> throw MachineStop(
                    StopReason.RESOURCE_INSUFFICIENT,
                    "金币不足，已安全停止",
                )

                ShopPage.PURCHASE_CONFIRMATION -> Unit
                else -> error("不可达的购买状态")
            }

            publish(
                AutomationPhase.VERIFYING_PURCHASE,
                "确认${currentTarget.type.displayName()}",
                currentTarget.confidence,
            )
            val verification = captureChecked(gateway).use { frame ->
                vision.verifyPurchase(frame, currentTarget)
            }
            if (!verification.matched || verification.confidence < config.matchThreshold) {
                logger.warn(
                    "purchase.verification_failed",
                    "type" to currentTarget.type,
                    "matched" to verification.matched,
                    "confidence" to verification.confidence,
                    "required" to config.matchThreshold,
                )
                throw MachineStop(
                    StopReason.LOW_CONFIDENCE,
                    "购买确认与目标不一致，已停止",
                )
            }
            logger.info(
                "purchase.verified",
                "type" to currentTarget.type,
                "confidence" to verification.confidence,
            )

            tapAction(
                action = ShopAction.CONFIRM_PURCHASE,
                operations = operations,
                policy = OperationPolicy.reconciliationRequired(),
                failureMessage = "未找到购买确认按钮",
            )
            requireConfirmedEffect(
                uncertainMessage = "已点击购买确认，但未能确认购买结果；金币可能已消耗",
            ) {
                waitForPage(
                    expected = ShopPage.SHOP,
                    timeoutMs = PAGE_TIMEOUT_MS,
                    consecutiveMatches = 1,
                    gateway = gateway,
                    awaitRunPermission = awaitRunPermission,
                    onDiagnostic = onDiagnostic,
                )
            }
            currentStats = when (currentTarget.type) {
                ItemType.COVENANT_BOOKMARK -> currentStats.copy(
                    covenantBookmarksBought = currentStats.covenantBookmarksBought + 1,
                    goldSpent = currentStats.goldSpent + COVENANT_BOOKMARK_GOLD_COST,
                )

                ItemType.MYSTIC_MEDAL -> currentStats.copy(
                    mysticMedalsBought = currentStats.mysticMedalsBought + 1,
                    goldSpent = currentStats.goldSpent + MYSTIC_MEDAL_GOLD_COST,
                )
            }
            publish(
                AutomationPhase.VERIFYING_PURCHASE,
                "已购买${currentTarget.type.displayName()}",
                verification.confidence,
            )
        }
        return currentStats
    }

    private suspend fun navigateHomeIfNeeded(
        gateway: ScreenGateway,
        awaitRunPermission: suspend () -> Unit,
        publish: (AutomationPhase, String, Double?) -> Unit,
        onDiagnostic: suspend (ScreenFrame, String) -> Unit,
    ) {
        val navigator = homeNavigator ?: return
        try {
            navigator.ensureHome(
                gateway = gateway,
                awaitRunPermission = awaitRunPermission,
                onStatus = { message ->
                    publish(AutomationPhase.WAITING_FOR_SHOP, message, null)
                },
                onDiagnostic = onDiagnostic,
            )
        } catch (error: HomeNavigationException) {
            throw MachineStop(
                reason = when (error.failure) {
                    HomeNavigationFailure.SCREENSHOT_FAILED -> StopReason.SCREENSHOT_FAILED
                    HomeNavigationFailure.INVALID_RESOLUTION -> StopReason.INVALID_RESOLUTION
                    HomeNavigationFailure.LOW_CONFIDENCE -> StopReason.LOW_CONFIDENCE
                    HomeNavigationFailure.TIMEOUT -> StopReason.TIMEOUT
                    HomeNavigationFailure.GESTURE_FAILED -> StopReason.GESTURE_FAILED
                    HomeNavigationFailure.UNCERTAIN_EFFECT -> StopReason.UNCERTAIN_EFFECT
                },
                message = error.message ?: "返回主页失败",
            )
        }
    }

    private suspend fun tapAction(
        action: ShopAction,
        operations: OperationExecutor,
        policy: OperationPolicy,
        failureMessage: String,
    ) {
        val actionMatch = operations.capture("shop.find_${action.name.lowercase()}").use { frame ->
            vision.findAction(frame, action)
        }
        val point = actionMatch.center
        logger.debug(
            "action.detected",
            "action" to action,
            "matched" to actionMatch.matched,
            "confidence" to actionMatch.confidence,
            "point" to point?.let { "${it.x},${it.y}" },
        )
        if (!actionMatch.matched || point == null) {
            throw MachineStop(StopReason.LOW_CONFIDENCE, failureMessage)
        }
        val result = operations.tap(
            operationId = "shop.${action.name.lowercase()}",
            point = point,
            policy = policy,
        )
        logger.info(
            "gesture.action",
            "action" to action,
            "point" to "${point.x},${point.y}",
            "result" to result,
        )
    }

    private suspend fun waitForPage(
        expected: ShopPage,
        timeoutMs: Long,
        consecutiveMatches: Int,
        gateway: ScreenGateway,
        awaitRunPermission: suspend () -> Unit,
        onDiagnostic: suspend (ScreenFrame, String) -> Unit,
    ) {
        var count = 0
        var unknownDiagnosticSaved = false
        val operations = OperationExecutor(
            gateway = gateway,
            clock = clock,
            awaitRunPermission = awaitRunPermission,
            onDiagnostic = onDiagnostic,
            logger = logger,
        )
        try {
            operations.waitUntil<Unit>(
                operationId = "shop.wait_${expected.name.lowercase()}",
                timeoutMs = timeoutMs,
                pollIntervalMs = POLL_INTERVAL_MS,
                diagnosticReason = "wait_${expected.name}",
            ) {
                val page = operations.capture("shop.observe_${expected.name.lowercase()}")
                    .use { frame ->
                        val detected = vision.detectPage(frame)
                        logger.debug(
                            "wait.page",
                            "sequence" to frame.sequence,
                            "expected" to expected,
                            "detected" to detected,
                            "matchCount" to count,
                        )
                        if (detected == ShopPage.UNKNOWN && !unknownDiagnosticSaved) {
                            try {
                                onDiagnostic(
                                    frame,
                                    "unknown_wait_${expected.name}_${frame.sequence}",
                                )
                                unknownDiagnosticSaved = true
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Throwable) {
                                logger.error(
                                    "diagnostic.save_failed",
                                    error,
                                    "sequence" to frame.sequence,
                                )
                            }
                        }
                        detected
                    }
                if (page == ShopPage.RESOURCE_INSUFFICIENT) {
                    throw MachineStop(
                        StopReason.RESOURCE_INSUFFICIENT,
                        "资源不足，已停止",
                    )
                }
                count = if (page == expected) count + 1 else 0
                Unit.takeIf { count >= consecutiveMatches }
            }
        } catch (error: OperationExecutionException) {
            if (error.failure.kind == ExecutionFailureKind.TIMEOUT) {
                throw MachineStop(StopReason.TIMEOUT, "等待 ${expected.name} 超时")
            }
            throw error
        }
    }

    private suspend fun waitForAnyPage(
        expected: Set<ShopPage>,
        timeoutMs: Long,
        gateway: ScreenGateway,
        awaitRunPermission: suspend () -> Unit,
        onDiagnostic: suspend (ScreenFrame, String) -> Unit,
    ): ShopPage {
        var unknownDiagnosticSaved = false
        val operations = OperationExecutor(
            gateway = gateway,
            clock = clock,
            awaitRunPermission = awaitRunPermission,
            onDiagnostic = onDiagnostic,
            logger = logger,
        )
        try {
            return operations.waitUntil(
                operationId = "shop.wait_any_page",
                timeoutMs = timeoutMs,
                pollIntervalMs = POLL_INTERVAL_MS,
                diagnosticReason = "wait_any_page",
            ) {
                operations.capture("shop.observe_any_page").use { frame ->
                    val detected = vision.detectPage(frame)
                    logger.debug(
                        "wait.any_page",
                        "sequence" to frame.sequence,
                        "expected" to expected.joinToString(),
                        "detected" to detected,
                    )
                    if (detected == ShopPage.UNKNOWN && !unknownDiagnosticSaved) {
                        try {
                            onDiagnostic(frame, "unknown_wait_any_${frame.sequence}")
                            unknownDiagnosticSaved = true
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            logger.error(
                                "diagnostic.save_failed",
                                error,
                                "sequence" to frame.sequence,
                            )
                        }
                    }
                    detected.takeIf { it in expected }
                }
            }
        } catch (error: OperationExecutionException) {
            if (error.failure.kind == ExecutionFailureKind.TIMEOUT) {
                throw MachineStop(StopReason.TIMEOUT, "等待页面变化超时")
            }
            throw error
        }
    }

    private suspend fun captureChecked(gateway: ScreenGateway): ScreenFrame {
        val frame = try {
            gateway.capture()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw MachineStop(
                StopReason.SCREENSHOT_FAILED,
                "截图失败：${error.message.orEmpty()}",
            )
        }
        if (frame.width != REFERENCE_WIDTH || frame.height != REFERENCE_HEIGHT) {
            logger.warn(
                "capture.invalid_resolution",
                "sequence" to frame.sequence,
                "width" to frame.width,
                "height" to frame.height,
            )
            frame.close()
            throw MachineStop(
                StopReason.INVALID_RESOLUTION,
                "需要 ${REFERENCE_WIDTH}×${REFERENCE_HEIGHT}，当前为 ${frame.width}×${frame.height}",
            )
        }
        logger.debug(
            "capture.accepted",
            "sequence" to frame.sequence,
            "width" to frame.width,
            "height" to frame.height,
            "capturedAt" to frame.capturedAtElapsedMs,
        )
        return frame
    }

    private suspend fun diagnose(
        gateway: ScreenGateway,
        reason: String,
        onDiagnostic: suspend (ScreenFrame, String) -> Unit,
    ) {
        try {
            gateway.capture().use { frame ->
                onDiagnostic(frame, reason)
                logger.info(
                    "diagnostic.captured",
                    "reason" to reason,
                    "sequence" to frame.sequence,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            logger.error("diagnostic.capture_failed", error, "reason" to reason)
        }
    }

    private fun ItemType.displayName(): String = when (this) {
        ItemType.COVENANT_BOOKMARK -> "誓约书签"
        ItemType.MYSTIC_MEDAL -> "神秘奖牌"
    }

    private fun PointConfig.toCapturePoint(): ScreenPoint = ScreenPoint(
        x = (x.toDouble() / visionConfig.referenceWidth * REFERENCE_WIDTH).roundToInt(),
        y = (y.toDouble() / visionConfig.referenceHeight * REFERENCE_HEIGHT).roundToInt(),
    )

    private fun ExecutionFailureKind.toStopReason(): StopReason = when (this) {
        ExecutionFailureKind.SCREENSHOT_FAILED -> StopReason.SCREENSHOT_FAILED
        ExecutionFailureKind.INVALID_RESOLUTION -> StopReason.INVALID_RESOLUTION
        ExecutionFailureKind.GESTURE_FAILED -> StopReason.GESTURE_FAILED
        ExecutionFailureKind.UNCERTAIN_EFFECT -> StopReason.UNCERTAIN_EFFECT
        ExecutionFailureKind.TIMEOUT -> StopReason.TIMEOUT
    }

    private suspend fun requireConfirmedEffect(
        uncertainMessage: String,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (stop: MachineStop) {
            if (stop.reason == StopReason.TIMEOUT) {
                throw MachineStop(StopReason.UNCERTAIN_EFFECT, uncertainMessage)
            }
            throw stop
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logger.error(
                "operation.postcondition_failed",
                error,
                "message" to uncertainMessage,
            )
            throw MachineStop(StopReason.UNCERTAIN_EFFECT, uncertainMessage)
        }
    }

    private class MachineStop(
        val reason: StopReason,
        message: String,
    ) : RuntimeException(message)

    private companion object {
        const val WAIT_FOR_SHOP_TIMEOUT_MS = 5 * 60 * 1000L
        const val DIALOG_TIMEOUT_MS = 8_000L
        const val PAGE_TIMEOUT_MS = 15_000L
        const val POLL_INTERVAL_MS = 450L
        const val SCROLL_DURATION_MS = 500L
        const val AFTER_SCROLL_DELAY_MS = 800L
        const val TARGET_REVALIDATE_TOLERANCE_PX = 100
    }
}

object SystemAutomationClock : AutomationClock {
    override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()

    override suspend fun delay(durationMs: Long) {
        kotlinx.coroutines.delay(durationMs.milliseconds)
    }
}
