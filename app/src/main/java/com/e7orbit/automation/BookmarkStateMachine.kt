package com.e7orbit.automation

import android.os.SystemClock
import com.e7orbit.logging.NoOpOrbitLogger
import com.e7orbit.logging.OrbitLogger
import com.e7orbit.model.AutomationPhase
import com.e7orbit.model.COVENANT_BOOKMARK_GOLD_COST
import com.e7orbit.model.ItemType
import com.e7orbit.model.MYSTIC_MEDAL_GOLD_COST
import com.e7orbit.model.MatchResult
import com.e7orbit.model.PurchaseTarget
import com.e7orbit.model.RunConfig
import com.e7orbit.model.RunStats
import com.e7orbit.model.ScreenFrame
import com.e7orbit.model.ScreenRatioPoint
import com.e7orbit.model.ShopPage
import com.e7orbit.model.StopReason
import com.e7orbit.model.VisualAction
import com.e7orbit.vision.VisionConfig
import kotlin.math.abs
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
    private val shopEntryWorkflow: Workflow<BookmarkWorkflowContext> by lazy {
        buildShopEntryWorkflow()
    }
    private val shopRefreshWorkflow: Workflow<BookmarkWorkflowContext> by lazy {
        buildShopRefreshWorkflow()
    }
    private val purchaseWorkflow: Workflow<PurchaseWorkflowContext> by lazy {
        buildPurchaseWorkflow()
    }

    suspend fun run(
        config: RunConfig,
        gateway: ScreenGateway,
        awaitRunPermission: suspend () -> Unit,
        onStatus: (AutomationPhase, RunStats, String, Double?) -> Unit,
        onDiagnostic: suspend (ScreenFrame, String) -> Unit,
    ): MachineResult = run(
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
        config: RunConfig,
        session: AutomationSession,
        onStatus: (AutomationPhase, RunStats, String, Double?) -> Unit,
    ): MachineResult {
        val context = BookmarkWorkflowContext(
            config = config,
            stats = RunStats(startedAtElapsedMs = clock.elapsedRealtime()),
            onStatus = onStatus,
            logger = logger,
        )
        logger.info(
            "machine.started",
            "maxRefreshes" to config.maxRefreshes,
            "threshold" to config.matchThreshold,
        )

        return try {
            shopEntryWorkflow.run(context, session)
            while (context.stats.completedRefreshes < config.maxRefreshes) {
                val cycle = context.stats.completedRefreshes + 1
                context.cycleNumber = cycle
                shopRefreshWorkflow.run(
                    context = context,
                    session = session,
                    runKey = "refresh-$cycle",
                )
            }

            context.finish(clock.elapsedRealtime())
            MachineResult(
                reason = StopReason.REFRESH_LIMIT_REACHED,
                stats = context.stats,
                message = "已完成 ${context.stats.completedRefreshes} 次刷新",
                successful = true,
            )
        } catch (stop: MachineStop) {
            logger.warn(
                "machine.stopped",
                "reason" to stop.reason,
                "message" to stop.message,
                "refreshes" to context.stats.completedRefreshes,
            )
            session.diagnose(stop.reason.name)
            context.finish(clock.elapsedRealtime())
            MachineResult(
                reason = stop.reason,
                stats = context.stats,
                message = stop.message ?: "自动化已停止",
                successful = false,
            )
        } catch (error: VisualActionNotFoundException) {
            logger.warn(
                "machine.visual_action_missing",
                "action" to error.action,
                "message" to error.message,
            )
            session.diagnose("visual_action_missing")
            context.finish(clock.elapsedRealtime())
            MachineResult(
                reason = StopReason.LOW_CONFIDENCE,
                stats = context.stats,
                message = error.message ?: "未找到可点击控件",
                successful = false,
            )
        } catch (error: OperationExecutionException) {
            val reason = error.failure.kind.toStopReason()
            logger.warn(
                "machine.operation_failed",
                "operation" to error.failure.operationId,
                "kind" to error.failure.kind,
                "message" to error.message,
                "refreshes" to context.stats.completedRefreshes,
            )
            session.diagnose(reason.name)
            context.finish(clock.elapsedRealtime())
            MachineResult(
                reason = reason,
                stats = context.stats,
                message = error.message ?: "自动化操作失败",
                successful = false,
            )
        }
    }

    private fun buildShopEntryWorkflow(): Workflow<BookmarkWorkflowContext> =
        workflow("bookmark_entry") {
            defaults {
                diagnoseOnFailure = false
            }
            stage("navigation") {
                step("ensure_home", effectSafety = EffectSafety.RECONCILIATION_REQUIRED) {
                    execute {
                        navigateHomeIfNeeded(
                            session = session,
                            publish = context::publish,
                        )
                    }
                }
            }
            stage("shop") {
                step("open_or_confirm", effectSafety = EffectSafety.RECONCILIATION_REQUIRED) {
                    execute {
                        context.publish(
                            AutomationPhase.WAITING_FOR_SHOP,
                            "等待主页或秘密商店",
                        )
                        when (
                            waitForAnyPage(
                                expected = setOf(ShopPage.LOBBY, ShopPage.SHOP),
                                timeoutMs = WAIT_FOR_SHOP_TIMEOUT_MS,
                                session = session,
                            )
                        ) {
                            ShopPage.LOBBY -> {
                                context.publish(
                                    AutomationPhase.WAITING_FOR_SHOP,
                                    "从主页进入秘密商店",
                                )
                                visualActions(session).tap(
                                    action = VisualAction.OPEN_SECRET_SHOP,
                                    operationId = "shop.open_secret_shop",
                                    policy = OperationPolicy.reconciliationRequired(),
                                    failureMessage = "未找到主页秘密商店入口",
                                )
                                waitForPage(
                                    expected = ShopPage.SHOP,
                                    timeoutMs = PAGE_TIMEOUT_MS,
                                    consecutiveMatches = 2,
                                    session = session,
                                )
                            }

                            ShopPage.SHOP -> waitForPage(
                                expected = ShopPage.SHOP,
                                timeoutMs = PAGE_TIMEOUT_MS,
                                consecutiveMatches = 1,
                                session = session,
                            )

                            else -> error("不可达的商店入口状态")
                        }
                    }
                    recover {
                        if (
                            hasIssuedGesture &&
                            observeShopPage(session) == ShopPage.SHOP
                        ) {
                            StepRecovery.Recovered
                        } else {
                            StepRecovery.Fail
                        }
                    }
                }
            }
        }

    private fun buildShopRefreshWorkflow(): Workflow<BookmarkWorkflowContext> =
        workflow("bookmark_refresh") {
            defaults {
                diagnoseOnFailure = false
            }
            stage("scan") {
                step("top", effectSafety = EffectSafety.RECONCILIATION_REQUIRED) {
                    execute {
                        session.awaitActive()
                        context.stats = context.stats.copy(
                            shopPagesScanned = context.stats.shopPagesScanned + 1,
                        )
                        context.publish(
                            AutomationPhase.SCANNING_TOP,
                            "扫描上半页 ${context.cycleNumber}/${context.config.maxRefreshes}",
                        )
                        scanAndPurchase(
                            context = context,
                            session = session,
                            scanKey = "top",
                        )
                    }
                }
                step("scroll_bottom", effectSafety = EffectSafety.IDEMPOTENT) {
                    execute {
                        session.awaitActive()
                        context.publish(AutomationPhase.SCANNING_BOTTOM, "滑动并扫描下半页")
                        visualActions(session).swipe(
                            operationId = "shop.scroll",
                            from = visionConfig.scrollFrom.toRatioPoint(),
                            to = visionConfig.scrollTo.toRatioPoint(),
                            durationMs = SCROLL_DURATION_MS,
                            policy = OperationPolicy.idempotent(),
                        )
                        logger.info(
                            "gesture.scroll",
                            "from" to visionConfig.scrollFrom,
                            "to" to visionConfig.scrollTo,
                            "durationMs" to SCROLL_DURATION_MS,
                        )
                        clock.delay(AFTER_SCROLL_DELAY_MS)
                    }
                }
                step("bottom", effectSafety = EffectSafety.RECONCILIATION_REQUIRED) {
                    execute {
                        scanAndPurchase(
                            context = context,
                            session = session,
                            scanKey = "bottom",
                        )
                    }
                }
            }
            stage("refresh") {
                step("open_dialog", effectSafety = EffectSafety.RECONCILIATION_REQUIRED) {
                    execute {
                        session.awaitActive()
                        context.publish(AutomationPhase.REFRESHING, "准备刷新秘密商店")
                        visualActions(session).tap(
                            action = VisualAction.REFRESH_SHOP,
                            operationId = "shop.refresh_shop",
                            policy = OperationPolicy.reconciliationRequired(),
                            failureMessage = "未找到刷新按钮",
                        )
                        context.refreshDialogPage = waitForAnyPage(
                            expected = setOf(
                                ShopPage.REFRESH_CONFIRMATION,
                                ShopPage.RESOURCE_INSUFFICIENT,
                            ),
                            timeoutMs = DIALOG_TIMEOUT_MS,
                            session = session,
                        )
                        requireRefreshConfirmation(context.refreshDialogPage)
                    }
                    recover {
                        if (!hasIssuedGesture) return@recover StepRecovery.Fail
                        val page = observeShopPage(session)
                        if (
                            page == ShopPage.REFRESH_CONFIRMATION ||
                            page == ShopPage.RESOURCE_INSUFFICIENT
                        ) {
                            context.refreshDialogPage = page
                            StepRecovery.Recovered
                        } else {
                            StepRecovery.Fail
                        }
                    }
                }
                step("confirm", effectSafety = EffectSafety.RECONCILIATION_REQUIRED) {
                    execute {
                        requireRefreshConfirmation(context.refreshDialogPage)
                        visualActions(session).tap(
                            action = VisualAction.CONFIRM_REFRESH,
                            operationId = "shop.confirm_refresh",
                            policy = OperationPolicy.reconciliationRequired(),
                            failureMessage = "未找到刷新确认按钮",
                        )
                        context.publish(
                            AutomationPhase.WAITING_FOR_REFRESH,
                            "等待新商品加载",
                        )
                        requireConfirmedEffect(
                            uncertainMessage =
                                "已点击刷新确认，但未能确认刷新结果；天空石可能已消耗",
                        ) {
                            waitForPage(
                                expected = ShopPage.SHOP,
                                timeoutMs = PAGE_TIMEOUT_MS,
                                consecutiveMatches = 2,
                                session = session,
                            )
                        }
                        context.recordRefresh()
                    }
                }
            }
        }

    private fun buildPurchaseWorkflow(): Workflow<PurchaseWorkflowContext> =
        workflow("bookmark_purchase") {
            defaults {
                diagnoseOnFailure = false
            }
            stage("target") {
                step("revalidate", effectSafety = EffectSafety.READ_ONLY) {
                    execute {
                        session.awaitActive()
                        context.currentTarget = session.executor
                            .capture("shop.revalidate_target")
                            .use { frame ->
                                val targets = vision.findTargets(
                                    frame,
                                    context.parent.config,
                                )
                                logger.debug(
                                    "purchase.revalidate",
                                    "sequence" to frame.sequence,
                                    "originalType" to context.originalTarget.type,
                                    "originalY" to
                                        context.originalTarget.itemBounds.center.y,
                                    "candidateCount" to targets.size,
                                )
                                targets
                                    .filter { it.type == context.originalTarget.type }
                                    .minByOrNull { target ->
                                        abs(
                                            target.itemBounds.center.y -
                                                context.originalTarget.itemBounds.center.y,
                                        )
                                    }
                                    ?.takeIf { target ->
                                        abs(
                                            target.itemBounds.center.y -
                                                context.originalTarget.itemBounds.center.y,
                                        ) <= TARGET_REVALIDATE_TOLERANCE_PX
                                    }
                            }
                        if (context.currentTarget == null) completeWorkflow()
                    }
                }
            }
            stage("dialog") {
                step("open", effectSafety = EffectSafety.RECONCILIATION_REQUIRED) {
                    execute {
                        val target = context.requireCurrentTarget()
                        context.parent.publish(
                            AutomationPhase.PURCHASING,
                            "购买${target.type.displayName()}",
                            target.confidence,
                        )
                        visualActions(session).tapLocated(
                            operationId = "shop.open_purchase_confirmation",
                            targetLabel = "purchase_${target.type.name.lowercase()}",
                            match = MatchResult(
                                matched = true,
                                confidence = target.confidence,
                                bounds = target.purchaseButtonBounds,
                            ),
                            policy = OperationPolicy.reconciliationRequired(),
                            failureMessage = "购买按钮定位结果无效",
                        )
                        logger.info(
                            "gesture.purchase_button",
                            "type" to target.type,
                            "point" to "${target.purchaseButton.x},${target.purchaseButton.y}",
                            "confidence" to target.confidence,
                        )
                        context.dialogPage = waitForAnyPage(
                            expected = setOf(
                                ShopPage.PURCHASE_CONFIRMATION,
                                ShopPage.RESOURCE_INSUFFICIENT,
                            ),
                            timeoutMs = DIALOG_TIMEOUT_MS,
                            session = session,
                        )
                        requirePurchaseConfirmation(context.dialogPage)
                    }
                    recover {
                        if (!hasIssuedGesture) return@recover StepRecovery.Fail
                        val page = observeShopPage(session)
                        if (
                            page == ShopPage.PURCHASE_CONFIRMATION ||
                            page == ShopPage.RESOURCE_INSUFFICIENT
                        ) {
                            context.dialogPage = page
                            StepRecovery.Recovered
                        } else {
                            StepRecovery.Fail
                        }
                    }
                }
                step("verify", effectSafety = EffectSafety.READ_ONLY) {
                    execute {
                        requirePurchaseConfirmation(context.dialogPage)
                        val target = context.requireCurrentTarget()
                        context.parent.publish(
                            AutomationPhase.VERIFYING_PURCHASE,
                            "确认${target.type.displayName()}",
                            target.confidence,
                        )
                        val verification = session.executor
                            .capture("shop.verify_purchase")
                            .use { frame -> vision.verifyPurchase(frame, target) }
                        if (
                            !verification.matched ||
                            verification.confidence < context.parent.config.matchThreshold
                        ) {
                            logger.warn(
                                "purchase.verification_failed",
                                "type" to target.type,
                                "matched" to verification.matched,
                                "confidence" to verification.confidence,
                                "required" to context.parent.config.matchThreshold,
                            )
                            throw MachineStop(
                                StopReason.LOW_CONFIDENCE,
                                "购买确认与目标不一致，已停止",
                            )
                        }
                        context.verificationConfidence = verification.confidence
                        logger.info(
                            "purchase.verified",
                            "type" to target.type,
                            "confidence" to verification.confidence,
                        )
                    }
                }
                step("confirm", effectSafety = EffectSafety.RECONCILIATION_REQUIRED) {
                    execute {
                        val target = context.requireCurrentTarget()
                        visualActions(session).tap(
                            action = VisualAction.CONFIRM_PURCHASE,
                            operationId = "shop.confirm_purchase",
                            policy = OperationPolicy.reconciliationRequired(),
                            failureMessage = "未找到购买确认按钮",
                        )
                        requireConfirmedEffect(
                            uncertainMessage =
                                "已点击购买确认，但未能确认购买结果；金币可能已消耗",
                        ) {
                            waitForPage(
                                expected = ShopPage.SHOP,
                                timeoutMs = PAGE_TIMEOUT_MS,
                                consecutiveMatches = 1,
                                session = session,
                            )
                        }
                        context.recordPurchase()
                        context.parent.publish(
                            AutomationPhase.VERIFYING_PURCHASE,
                            "已购买${target.type.displayName()}",
                            context.verificationConfidence,
                        )
                    }
                }
            }
        }

    private suspend fun observeShopPage(session: AutomationSession): ShopPage =
        session.executor.capture("shop.reconcile_page").use { frame ->
            vision.detectPage(frame)
        }

    private fun requireRefreshConfirmation(page: ShopPage?) {
        when (page) {
            ShopPage.REFRESH_CONFIRMATION -> Unit
            ShopPage.RESOURCE_INSUFFICIENT -> throw MachineStop(
                StopReason.RESOURCE_INSUFFICIENT,
                "天空石不足，已安全停止",
            )
            else -> throw MachineStop(
                StopReason.UNKNOWN_PAGE,
                "未能确认刷新对话框状态",
            )
        }
    }

    private fun requirePurchaseConfirmation(page: ShopPage?) {
        when (page) {
            ShopPage.PURCHASE_CONFIRMATION -> Unit
            ShopPage.RESOURCE_INSUFFICIENT -> throw MachineStop(
                StopReason.RESOURCE_INSUFFICIENT,
                "金币不足，已安全停止",
            )
            else -> throw MachineStop(
                StopReason.UNKNOWN_PAGE,
                "未能确认购买对话框状态",
            )
        }
    }

    private suspend fun scanAndPurchase(
        context: BookmarkWorkflowContext,
        session: AutomationSession,
        scanKey: String,
    ) {
        val operations = session.executor
        val initialTargets = operations.capture("shop.scan_page").use { frame ->
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

                ShopPage.SHOP -> vision.findTargets(frame, context.config)
                else -> {
                    try {
                        session.saveDiagnostic(frame, "unknown_page_scan_${frame.sequence}")
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

        initialTargets.forEachIndexed { index, originalTarget ->
            purchaseWorkflow.run(
                context = PurchaseWorkflowContext(
                    parent = context,
                    originalTarget = originalTarget,
                ),
                session = session,
                runKey = buildString {
                    append("refresh-")
                    append(context.cycleNumber)
                    append('-')
                    append(scanKey)
                    append('-')
                    append(index)
                    append('-')
                    append(originalTarget.type.name.lowercase())
                },
            )
        }
    }

    private suspend fun navigateHomeIfNeeded(
        session: AutomationSession,
        publish: (AutomationPhase, String, Double?) -> Unit,
    ) {
        val navigator = homeNavigator ?: return
        try {
            navigator.ensureHome(
                session = session,
                onStatus = { message ->
                    publish(AutomationPhase.WAITING_FOR_SHOP, message, null)
                },
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

    private fun visualActions(session: AutomationSession): VisualActionExecutor =
        VisualActionExecutor(
            operations = session.executor,
            vision = vision,
            namespace = "shop",
            logger = logger,
        )

    private suspend fun waitForPage(
        expected: ShopPage,
        timeoutMs: Long,
        consecutiveMatches: Int,
        session: AutomationSession,
    ) {
        var count = 0
        var unknownDiagnosticSaved = false
        val operations = session.executor
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
                                session.saveDiagnostic(
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
        session: AutomationSession,
    ): ShopPage {
        var unknownDiagnosticSaved = false
        val operations = session.executor
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
                            session.saveDiagnostic(frame, "unknown_wait_any_${frame.sequence}")
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

    private fun ItemType.displayName(): String = when (this) {
        ItemType.COVENANT_BOOKMARK -> "誓约书签"
        ItemType.MYSTIC_MEDAL -> "神秘奖牌"
    }

    private fun com.e7orbit.vision.PointConfig.toRatioPoint() = ScreenRatioPoint(
        xRatio = x.toDouble() / visionConfig.referenceWidth,
        yRatio = y.toDouble() / visionConfig.referenceHeight,
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

    private class BookmarkWorkflowContext(
        val config: RunConfig,
        var stats: RunStats,
        private val onStatus: (AutomationPhase, RunStats, String, Double?) -> Unit,
        private val logger: OrbitLogger,
    ) {
        var cycleNumber: Int = 0
        var refreshDialogPage: ShopPage? = null

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

        fun recordRefresh() {
            if (stats.completedRefreshes >= cycleNumber) return
            stats = stats.copy(completedRefreshes = cycleNumber)
            refreshDialogPage = null
        }

        fun finish(elapsedRealtimeMs: Long) {
            stats = stats.copy(finishedAtElapsedMs = elapsedRealtimeMs)
        }
    }

    private class PurchaseWorkflowContext(
        val parent: BookmarkWorkflowContext,
        val originalTarget: PurchaseTarget,
    ) {
        var currentTarget: PurchaseTarget? = null
        var dialogPage: ShopPage? = null
        var verificationConfidence: Double? = null
        private var purchaseRecorded = false

        fun requireCurrentTarget(): PurchaseTarget = currentTarget
            ?: error("购买目标尚未重新确认")

        fun recordPurchase() {
            if (purchaseRecorded) return
            purchaseRecorded = true
            parent.stats = when (requireCurrentTarget().type) {
                ItemType.COVENANT_BOOKMARK -> parent.stats.copy(
                    covenantBookmarksBought = parent.stats.covenantBookmarksBought + 1,
                    goldSpent = parent.stats.goldSpent + COVENANT_BOOKMARK_GOLD_COST,
                )

                ItemType.MYSTIC_MEDAL -> parent.stats.copy(
                    mysticMedalsBought = parent.stats.mysticMedalsBought + 1,
                    goldSpent = parent.stats.goldSpent + MYSTIC_MEDAL_GOLD_COST,
                )
            }
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
