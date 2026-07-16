package com.e7orbit.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import com.e7orbit.automation.AutomationRuntime
import com.e7orbit.automation.HuntRuntime
import com.e7orbit.model.AutomationPhase
import com.e7orbit.model.AutomationStatus
import com.e7orbit.model.HuntPhase
import com.e7orbit.model.HuntStatus
import com.e7orbit.ui.MainActivity
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@SuppressLint("ClickableViewAccessibility")
class AutomationOverlay(
    private val context: Context,
    private val runtime: AutomationRuntime,
    private val huntRuntime: HuntRuntime,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val bubble = OrbitBubbleView(context)
    private val params = WindowManager.LayoutParams(
        dp(COMPACT_SIZE_DP),
        dp(BUBBLE_HEIGHT_DP),
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        android.graphics.PixelFormat.TRANSLUCENT,
    )

    private var added = false
    private var expanded = false
    private var expansionAnimator: ValueAnimator? = null
    private var stopConfirmationDeadline = 0L

    init {
        params.gravity = Gravity.TOP or Gravity.START
        params.x = (context.resources.displayMetrics.widthPixels * 0.30f).roundToInt()
        params.y = dp(8)
        bubble.background = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
        bubble.elevation = dp(10).toFloat()
    }

    fun render(
        shopStatus: AutomationStatus,
        huntStatus: HuntStatus,
    ) {
        if (
            shopStatus.phase == AutomationPhase.IDLE &&
            huntStatus.phase == HuntPhase.IDLE
        ) {
            hide()
            return
        }
        bubble.render(shopStatus, huntStatus)
        show()
    }

    fun destroy() {
        expansionAnimator?.cancel()
        bubble.destroy()
        if (added) {
            runCatching { windowManager.removeViewImmediate(bubble) }
            added = false
        }
    }

    private fun show() {
        if (added) return
        windowManager.addView(bubble, params)
        added = true
    }

    private fun hide() {
        if (!added) return
        setExpanded(false, animate = false)
        runCatching { windowManager.removeView(bubble) }
        added = false
    }

    private fun setExpanded(value: Boolean, animate: Boolean = true) {
        if (expanded == value && expansionAnimator?.isRunning != true) return
        expanded = value
        expansionAnimator?.cancel()

        val target = if (value) 1f else 0f
        if (!animate) {
            applyExpansion(target)
            return
        }
        expansionAnimator = ValueAnimator.ofFloat(bubble.expansion, target).apply {
            duration = EXPAND_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { applyExpansion(it.animatedValue as Float) }
            start()
        }
    }

    private fun applyExpansion(value: Float) {
        bubble.expansion = value
        params.width = lerp(
            dp(COMPACT_SIZE_DP),
            dp(EXPANDED_WIDTH_DP),
            value,
        )
        keepInsideScreen()
        bubble.invalidate()
        if (added) runCatching { windowManager.updateViewLayout(bubble, params) }
    }

    private fun keepInsideScreen() {
        val metrics = context.resources.displayMetrics
        params.x = params.x.coerceIn(
            dp(8),
            (metrics.widthPixels - params.width - dp(8)).coerceAtLeast(dp(8)),
        )
        params.y = params.y.coerceIn(
            dp(8),
            (metrics.heightPixels - params.height - dp(8)).coerceAtLeast(dp(8)),
        )
    }

    private fun updatePosition(x: Int, y: Int) {
        params.x = x
        params.y = y
        keepInsideScreen()
        if (added) runCatching { windowManager.updateViewLayout(bubble, params) }
    }

    private fun returnToApp() {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        context.startActivity(intent)
    }

    private fun requestStop() {
        if (bubble.isActiveTerminal()) {
            stopConfirmationDeadline = 0L
            bubble.stopConfirmationPending = false
            bubble.dismissActiveTerminal()
            return
        }
        val now = System.currentTimeMillis()
        if (now <= stopConfirmationDeadline) {
            stopConfirmationDeadline = 0L
            bubble.stopConfirmationPending = false
            bubble.stopActive()
            return
        }

        stopConfirmationDeadline = now + STOP_CONFIRMATION_MS
        bubble.stopConfirmationPending = true
        bubble.invalidate()
        bubble.postDelayed(
            {
                if (System.currentTimeMillis() > stopConfirmationDeadline) {
                    stopConfirmationDeadline = 0L
                    bubble.stopConfirmationPending = false
                    bubble.invalidate()
                }
            },
            STOP_CONFIRMATION_MS,
        )
    }

    private fun lerp(start: Int, end: Int, fraction: Float): Int =
        (start + (end - start) * fraction).roundToInt()

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    private inner class OrbitBubbleView(
        context: Context,
    ) : View(context) {
        private val density = resources.displayMetrics.density
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
        }
        private val path = Path()
        private val covenantIcon = loadItemIcon("covenant_item.png")
        private val mysticIcon = loadItemIcon("mystic_item.png")
        private var shopStatus = AutomationStatus()
        private var huntStatus = HuntStatus()
        private var activeMode = OverlayMode.SHOP
        private var phaseProgress = 0f
        private var progressAnimator: ValueAnimator? = null
        private var touchStartRawX = 0f
        private var touchStartRawY = 0f
        private var windowStartX = 0
        private var windowStartY = 0
        private var dragging = false
        private var canDrag = false
        private val collapseHoverRunnable = Runnable { setExpanded(false) }

        var expansion: Float = 0f
        var stopConfirmationPending: Boolean = false

        init {
            isClickable = true
            isFocusable = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        fun render(
            newShopStatus: AutomationStatus,
            newHuntStatus: HuntStatus,
        ) {
            shopStatus = newShopStatus
            huntStatus = newHuntStatus
            activeMode = when {
                newHuntStatus.isRunning || newHuntStatus.phase == HuntPhase.PAUSED ->
                    OverlayMode.HUNT

                newShopStatus.isRunning || newShopStatus.phase == AutomationPhase.PAUSED ->
                    OverlayMode.SHOP

                newHuntStatus.phase != HuntPhase.IDLE -> OverlayMode.HUNT
                else -> OverlayMode.SHOP
            }
            contentDescription = when (activeMode) {
                OverlayMode.SHOP -> buildString {
                    append("E7 Orbit，已执行 ${shopStatus.stats.completedRefreshes} 次")
                    append("，誓约书签增加 ${shopStatus.stats.covenantBookmarksGained}")
                    append("，神秘奖牌增加 ${shopStatus.stats.mysticMedalsGained}")
                    append("，金币消耗 ${shopStatus.stats.goldSpent}")
                }

                OverlayMode.HUNT ->
                    "E7 Orbit，${huntStatus.config.dungeon.displayName} " +
                        "${huntStatus.stats.completedRuns}/${huntStatus.config.runCount}"
            }
            animatePhaseProgress(activeProgress())
            invalidate()
        }

        fun isActiveTerminal(): Boolean = when (activeMode) {
            OverlayMode.SHOP -> shopStatus.isTerminal
            OverlayMode.HUNT -> huntStatus.isTerminal
        }

        fun dismissActiveTerminal() {
            when (activeMode) {
                OverlayMode.SHOP -> runtime.dismissTerminalStatus()
                OverlayMode.HUNT -> huntRuntime.dismissTerminalStatus()
            }
        }

        fun stopActive() {
            when (activeMode) {
                OverlayMode.SHOP -> runtime.stop()
                OverlayMode.HUNT -> huntRuntime.stop()
            }
        }

        fun destroy() {
            progressAnimator?.cancel()
            removeCallbacks(collapseHoverRunnable)
            covenantIcon?.let { if (!it.isRecycled) it.recycle() }
            mysticIcon?.let { if (!it.isRecycled) it.recycle() }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            drawContainer(canvas)
            drawMetrics(canvas)
            drawActions(canvas)
            drawBubble(canvas)
        }

        override fun onHoverEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER -> {
                    removeCallbacks(collapseHoverRunnable)
                    setExpanded(true)
                }

                MotionEvent.ACTION_HOVER_MOVE -> removeCallbacks(collapseHoverRunnable)
                MotionEvent.ACTION_HOVER_EXIT -> {
                    removeCallbacks(collapseHoverRunnable)
                    postDelayed(collapseHoverRunnable, HOVER_EXIT_DELAY_MS)
                }
            }
            return true
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartRawX = event.rawX
                    touchStartRawY = event.rawY
                    windowStartX = params.x
                    windowStartY = params.y
                    dragging = false
                    canDrag = event.x <= compactSize
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!canDrag) return true
                    val dx = event.rawX - touchStartRawX
                    val dy = event.rawY - touchStartRawY
                    dragging = dragging ||
                        kotlin.math.abs(dx) > dp(4) ||
                        kotlin.math.abs(dy) > dp(4)
                    if (dragging) {
                        updatePosition(
                            windowStartX + dx.roundToInt(),
                            windowStartY + dy.roundToInt(),
                        )
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        performClick()
                        handleClick(event.x, event.y)
                    }
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        private fun drawContainer(canvas: Canvas) {
            val rect = RectF(
                dp(2),
                dp(2),
                width.toFloat() - dp(2),
                height.toFloat() - dp(2),
            )
            fillPaint.color = COLOR_SURFACE
            fillPaint.style = Paint.Style.FILL
            canvas.drawRoundRect(rect, height / 2f, height / 2f, fillPaint)

            strokePaint.color = COLOR_OUTLINE
            strokePaint.strokeWidth = dpFloat(1f)
            canvas.drawRoundRect(rect, height / 2f, height / 2f, strokePaint)
        }

        private fun drawBubble(canvas: Canvas) {
            val centerX = compactSize / 2f
            val centerY = height / 2f
            val ringRadius = dpFloat(29f)

            fillPaint.color = COLOR_SURFACE
            canvas.drawCircle(centerX, centerY, ringRadius - dpFloat(3f), fillPaint)

            strokePaint.color = COLOR_PROGRESS_TRACK
            strokePaint.strokeWidth = dpFloat(4f)
            canvas.drawCircle(centerX, centerY, ringRadius, strokePaint)

            strokePaint.color = activePhaseColor()
            strokePaint.strokeWidth = dpFloat(4f)
            canvas.drawArc(
                RectF(
                    centerX - ringRadius,
                    centerY - ringRadius,
                    centerX + ringRadius,
                    centerY + ringRadius,
                ),
                -90f,
                360f * phaseProgress,
                false,
                strokePaint,
            )

            val centerText = if (expansion > 0.12f) {
                when (activeMode) {
                    OverlayMode.SHOP ->
                        "${shopStatus.stats.completedRefreshes}/${shopStatus.config.maxRefreshes}"

                    OverlayMode.HUNT ->
                        "${huntStatus.stats.completedRuns}/${huntStatus.config.runCount}"
                }
            } else {
                activeBubbleLabel()
            }
            textPaint.color = COLOR_ON_SURFACE
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = dpFloat(
                when {
                    centerText.length <= 2 -> 16f
                    centerText.length <= 5 -> 14f
                    centerText.length <= 8 -> 12f
                    else -> 10f
                },
            )
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            canvas.drawText(
                centerText,
                centerX,
                centeredBaseline(centerY, textPaint),
                textPaint,
            )
        }

        private fun drawMetrics(canvas: Canvas) {
            if (expansion < 0.72f) return
            if (activeMode == OverlayMode.HUNT) {
                drawHuntMetrics(canvas)
                return
            }
            val alpha = (((expansion - 0.72f) / 0.28f) * 255).roundToInt().coerceIn(0, 255)
            val metricsLeft = compactSize + dpFloat(4f)
            val metricsRight = actionRect(ACTION_HOME).left - dpFloat(8f)
            val cellWidth = (metricsRight - metricsLeft) / 3f
            val centerY = height / 2f

            drawMetric(
                canvas = canvas,
                type = MetricType.COVENANT,
                left = metricsLeft,
                width = cellWidth,
                value = "+${shopStatus.stats.covenantBookmarksGained}",
                alpha = alpha,
                centerY = centerY,
            )
            drawMetric(
                canvas = canvas,
                type = MetricType.MYSTIC,
                left = metricsLeft + cellWidth,
                width = cellWidth,
                value = "+${shopStatus.stats.mysticMedalsGained}",
                alpha = alpha,
                centerY = centerY,
            )
            drawMetric(
                canvas = canvas,
                type = MetricType.GOLD,
                left = metricsLeft + cellWidth * 2f,
                width = cellWidth,
                value = "-${formatNumber(shopStatus.stats.goldSpent)}",
                alpha = alpha,
                centerY = centerY,
            )
        }

        private fun drawHuntMetrics(canvas: Canvas) {
            val alpha = (((expansion - 0.72f) / 0.28f) * 255)
                .roundToInt()
                .coerceIn(0, 255)
            val metricsLeft = compactSize + dpFloat(4f)
            val metricsRight = actionRect(ACTION_HOME).left - dpFloat(8f)
            val cellWidth = (metricsRight - metricsLeft) / 3f
            val centerY = height / 2f
            drawTextMetric(
                canvas,
                "完成",
                huntStatus.stats.completedRuns.toString(),
                metricsLeft,
                cellWidth,
                centerY,
                alpha,
            )
            drawTextMetric(
                canvas,
                "目标",
                huntStatus.config.runCount.toString(),
                metricsLeft + cellWidth,
                cellWidth,
                centerY,
                alpha,
            )
            drawTextMetric(
                canvas,
                "模式",
                if (huntStatus.config.managedBattle) "托管" else "普通",
                metricsLeft + cellWidth * 2f,
                cellWidth,
                centerY,
                alpha,
            )
        }

        private fun drawTextMetric(
            canvas: Canvas,
            label: String,
            value: String,
            left: Float,
            width: Float,
            centerY: Float,
            alpha: Int,
        ) {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.alpha = alpha
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textPaint.color = COLOR_ON_SURFACE_VARIANT
            textPaint.textSize = dpFloat(9f)
            canvas.drawText(label, left + width / 2f, centerY - dpFloat(5f), textPaint)
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textPaint.color = COLOR_ON_SURFACE
            textPaint.textSize = dpFloat(12f)
            canvas.drawText(value, left + width / 2f, centerY + dpFloat(12f), textPaint)
            textPaint.alpha = 255
        }

        private fun drawMetric(
            canvas: Canvas,
            type: MetricType,
            left: Float,
            width: Float,
            value: String,
            alpha: Int,
            centerY: Float,
        ) {
            val iconSize = dpFloat(METRIC_ICON_SIZE_DP)
            val iconGap = dpFloat(METRIC_ICON_GAP_DP)
            val contentWidth = iconSize + iconGap + measureValue(value)
            val iconLeft = left + (width - contentWidth) / 2f
            val iconTop = centerY - iconSize / 2f
            when (type) {
                MetricType.COVENANT -> covenantIcon?.let {
                    drawItemIcon(canvas, it, iconLeft, iconTop, alpha)
                } ?: drawBookmarkIcon(canvas, iconLeft, iconTop, alpha)

                MetricType.MYSTIC -> mysticIcon?.let {
                    drawItemIcon(canvas, it, iconLeft, iconTop, alpha)
                } ?: drawMysticIcon(canvas, iconLeft, iconTop, alpha)

                MetricType.GOLD -> drawGoldIcon(canvas, iconLeft, iconTop, alpha)
            }

            textPaint.color = COLOR_ON_SURFACE
            textPaint.alpha = alpha
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.textSize = dpFloat(12f)
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            canvas.drawText(
                value,
                iconLeft + iconSize + iconGap,
                centeredBaseline(centerY, textPaint),
                textPaint,
            )
            textPaint.alpha = 255
        }

        private fun drawActions(canvas: Canvas) {
            if (expansion < 0.82f) return
            val alpha = (((expansion - 0.82f) / 0.18f) * 255).roundToInt().coerceIn(0, 255)
            drawActionBackground(canvas, actionRect(ACTION_HOME), alpha, danger = false)
            drawHomeIcon(canvas, actionRect(ACTION_HOME), alpha)

            drawActionBackground(canvas, actionRect(ACTION_PAUSE), alpha, danger = false)
            drawPauseOrPlayIcon(canvas, actionRect(ACTION_PAUSE), alpha)

            drawActionBackground(canvas, actionRect(ACTION_STOP), alpha, danger = true)
            drawStopIcon(canvas, actionRect(ACTION_STOP), alpha)
        }

        private fun drawActionBackground(
            canvas: Canvas,
            rect: RectF,
            alpha: Int,
            danger: Boolean,
        ) {
            fillPaint.color = COLOR_SURFACE
            fillPaint.alpha = alpha
            canvas.drawCircle(rect.centerX(), rect.centerY(), rect.width() / 2f, fillPaint)
            strokePaint.color = if (danger) COLOR_ERROR else COLOR_OUTLINE
            strokePaint.alpha = alpha
            strokePaint.strokeWidth = dpFloat(1f)
            canvas.drawCircle(rect.centerX(), rect.centerY(), rect.width() / 2f, strokePaint)
            fillPaint.alpha = 255
            strokePaint.alpha = 255
        }

        private fun drawItemIcon(
            canvas: Canvas,
            bitmap: Bitmap,
            left: Float,
            top: Float,
            alpha: Int,
        ) {
            bitmapPaint.alpha = alpha
            canvas.drawBitmap(
                bitmap,
                null,
                RectF(
                    left,
                    top,
                    left + dpFloat(METRIC_ICON_SIZE_DP),
                    top + dpFloat(METRIC_ICON_SIZE_DP),
                ),
                bitmapPaint,
            )
            bitmapPaint.alpha = 255
        }

        private fun loadItemIcon(fileName: String): Bitmap? = runCatching {
            context.assets.open("$VISION_ASSET_ROOT/$fileName").use { input ->
                BitmapFactory.decodeStream(input)
            }
        }.getOrNull()

        private fun drawBookmarkIcon(canvas: Canvas, left: Float, top: Float, alpha: Int) {
            fillPaint.color = COLOR_ON_SURFACE
            fillPaint.alpha = alpha
            path.reset()
            path.moveTo(left + dpFloat(4f), top + dpFloat(1f))
            path.lineTo(left + dpFloat(15f), top + dpFloat(1f))
            path.quadTo(
                left + dpFloat(17f),
                top + dpFloat(1f),
                left + dpFloat(17f),
                top + dpFloat(3f),
            )
            path.lineTo(left + dpFloat(17f), top + dpFloat(18f))
            path.lineTo(left + dpFloat(10.5f), top + dpFloat(14f))
            path.lineTo(left + dpFloat(4f), top + dpFloat(18f))
            path.close()
            canvas.drawPath(path, fillPaint)
            fillPaint.alpha = 255
        }

        private fun drawMysticIcon(canvas: Canvas, left: Float, top: Float, alpha: Int) {
            fillPaint.color = COLOR_ON_SURFACE
            fillPaint.alpha = alpha
            val centerX = left + dpFloat(9.5f)
            val centerY = top + dpFloat(9.5f)
            path.reset()
            repeat(10) { index ->
                val angle = -PI / 2 + index * PI / 5
                val radius = if (index % 2 == 0) dpFloat(9f) else dpFloat(4.2f)
                val x = centerX + cos(angle).toFloat() * radius
                val y = centerY + sin(angle).toFloat() * radius
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            canvas.drawPath(path, fillPaint)
            fillPaint.alpha = 255
        }

        private fun drawGoldIcon(canvas: Canvas, left: Float, top: Float, alpha: Int) {
            val centerX = left + dpFloat(METRIC_ICON_SIZE_DP / 2f)
            val centerY = top + dpFloat(METRIC_ICON_SIZE_DP / 2f)
            strokePaint.color = COLOR_ON_SURFACE
            strokePaint.alpha = alpha
            strokePaint.strokeWidth = dpFloat(1.8f)
            canvas.drawCircle(centerX, centerY, dpFloat(10f), strokePaint)
            canvas.drawCircle(centerX, centerY, dpFloat(7f), strokePaint)
            textPaint.color = COLOR_ON_SURFACE
            textPaint.alpha = alpha
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = dpFloat(9f)
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            canvas.drawText("G", centerX, centeredBaseline(centerY, textPaint), textPaint)
            strokePaint.alpha = 255
            textPaint.alpha = 255
        }

        private fun drawHomeIcon(canvas: Canvas, rect: RectF, alpha: Int) {
            strokePaint.color = COLOR_ON_SURFACE
            strokePaint.alpha = alpha
            strokePaint.strokeWidth = dpFloat(1.8f)
            strokePaint.strokeJoin = Paint.Join.ROUND
            path.reset()
            path.moveTo(rect.centerX() - dpFloat(7f), rect.centerY())
            path.lineTo(rect.centerX(), rect.centerY() - dpFloat(6f))
            path.lineTo(rect.centerX() + dpFloat(7f), rect.centerY())
            path.moveTo(rect.centerX() - dpFloat(5f), rect.centerY() - dpFloat(1f))
            path.lineTo(rect.centerX() - dpFloat(5f), rect.centerY() + dpFloat(7f))
            path.lineTo(rect.centerX() + dpFloat(5f), rect.centerY() + dpFloat(7f))
            path.lineTo(rect.centerX() + dpFloat(5f), rect.centerY() - dpFloat(1f))
            canvas.drawPath(path, strokePaint)
            strokePaint.alpha = 255
        }

        private fun drawPauseOrPlayIcon(canvas: Canvas, rect: RectF, alpha: Int) {
            fillPaint.color = COLOR_ON_SURFACE
            fillPaint.alpha = alpha
            if (isActivePaused() || isActiveTerminal()) {
                path.reset()
                path.moveTo(rect.centerX() - dpFloat(4f), rect.centerY() - dpFloat(7f))
                path.lineTo(rect.centerX() + dpFloat(7f), rect.centerY())
                path.lineTo(rect.centerX() - dpFloat(4f), rect.centerY() + dpFloat(7f))
                path.close()
                canvas.drawPath(path, fillPaint)
            } else {
                canvas.drawRoundRect(
                    RectF(
                        rect.centerX() - dpFloat(6f),
                        rect.centerY() - dpFloat(7f),
                        rect.centerX() - dpFloat(2f),
                        rect.centerY() + dpFloat(7f),
                    ),
                    dpFloat(1f),
                    dpFloat(1f),
                    fillPaint,
                )
                canvas.drawRoundRect(
                    RectF(
                        rect.centerX() + dpFloat(2f),
                        rect.centerY() - dpFloat(7f),
                        rect.centerX() + dpFloat(6f),
                        rect.centerY() + dpFloat(7f),
                    ),
                    dpFloat(1f),
                    dpFloat(1f),
                    fillPaint,
                )
            }
            fillPaint.alpha = 255
        }

        private fun drawStopIcon(canvas: Canvas, rect: RectF, alpha: Int) {
            fillPaint.color = COLOR_ERROR
            fillPaint.alpha = alpha
            if (stopConfirmationPending) {
                textPaint.color = COLOR_ERROR
                textPaint.alpha = alpha
                textPaint.textAlign = Paint.Align.CENTER
                textPaint.textSize = dpFloat(16f)
                textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                canvas.drawText(
                    "!",
                    rect.centerX(),
                    centeredBaseline(rect.centerY(), textPaint),
                    textPaint,
                )
                textPaint.alpha = 255
            } else {
                val half = dpFloat(6f)
                canvas.drawRoundRect(
                    RectF(
                        rect.centerX() - half,
                        rect.centerY() - half,
                        rect.centerX() + half,
                        rect.centerY() + half,
                    ),
                    dpFloat(2f),
                    dpFloat(2f),
                    fillPaint,
                )
            }
            fillPaint.alpha = 255
        }

        private fun handleClick(x: Float, y: Float) {
            if (expansion < 0.8f) {
                setExpanded(true)
                return
            }
            when {
                actionRect(ACTION_HOME).contains(x, y) -> returnToApp()
                actionRect(ACTION_PAUSE).contains(x, y) -> {
                    when {
                        isActiveTerminal() -> restartActive()
                        isActivePaused() -> resumeActive()
                        else -> pauseActive()
                    }
                }

                actionRect(ACTION_STOP).contains(x, y) -> requestStop()
                x <= compactSize -> setExpanded(false)
            }
        }

        private fun isActivePaused(): Boolean = when (activeMode) {
            OverlayMode.SHOP -> shopStatus.phase == AutomationPhase.PAUSED
            OverlayMode.HUNT -> huntStatus.phase == HuntPhase.PAUSED
        }

        private fun restartActive() {
            when (activeMode) {
                OverlayMode.SHOP -> runtime.restart()
                OverlayMode.HUNT -> huntRuntime.restart()
            }
        }

        private fun pauseActive() {
            when (activeMode) {
                OverlayMode.SHOP -> runtime.pause()
                OverlayMode.HUNT -> huntRuntime.pause()
            }
        }

        private fun resumeActive() {
            when (activeMode) {
                OverlayMode.SHOP -> runtime.resume()
                OverlayMode.HUNT -> huntRuntime.resume()
            }
        }

        private fun activeProgress(): Float = when (activeMode) {
            OverlayMode.SHOP -> shopStatus.phase.progress()
            OverlayMode.HUNT -> huntStatus.phase.progress()
        }

        private fun activeBubbleLabel(): String = when (activeMode) {
            OverlayMode.SHOP -> shopStatus.phase.bubbleLabel()
            OverlayMode.HUNT -> huntStatus.phase.bubbleLabel()
        }

        private fun activePhaseColor(): Int = when (activeMode) {
            OverlayMode.SHOP -> shopStatus.phase.phaseColor()
            OverlayMode.HUNT -> huntStatus.phase.phaseColor()
        }

        private fun actionRect(action: Int): RectF {
            val right = width.toFloat() - dpFloat(ACTION_PADDING_DP) -
                action * (dpFloat(ACTION_SIZE_DP) + dpFloat(ACTION_GAP_DP))
            val top = (height.toFloat() - dpFloat(ACTION_SIZE_DP)) / 2f
            return RectF(
                right - dpFloat(ACTION_SIZE_DP),
                top,
                right,
                top + dpFloat(ACTION_SIZE_DP),
            )
        }

        private fun animatePhaseProgress(target: Float) {
            if (isActivePaused()) return
            progressAnimator?.cancel()
            if (kotlin.math.abs(target - phaseProgress) < 0.001f) return
            progressAnimator = ValueAnimator.ofFloat(phaseProgress, target).apply {
                duration = PROGRESS_DURATION_MS
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    phaseProgress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        private fun measureValue(value: String): Float {
            textPaint.textSize = dpFloat(12f)
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            return textPaint.measureText(value)
        }

        private fun centeredBaseline(centerY: Float, paint: Paint): Float =
            centerY - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f

        private fun formatNumber(value: Long): String = "%,d".format(value)

        private fun dp(value: Int): Float = value * density

        private fun dpFloat(value: Int): Float = value * density

        private fun dpFloat(value: Float): Float = value * density

        private val compactSize: Float
            get() = dp(COMPACT_SIZE_DP)
    }

    private fun AutomationPhase.progress(): Float = when (this) {
        AutomationPhase.IDLE -> 0f
        AutomationPhase.WAITING_FOR_SERVICE -> 0.04f
        AutomationPhase.WAITING_FOR_SHOP -> 0.08f
        AutomationPhase.SCANNING_TOP -> 0.22f
        AutomationPhase.PURCHASING -> 0.34f
        AutomationPhase.VERIFYING_PURCHASE -> 0.46f
        AutomationPhase.SCANNING_BOTTOM -> 0.58f
        AutomationPhase.REFRESHING -> 0.76f
        AutomationPhase.WAITING_FOR_REFRESH -> 0.92f
        AutomationPhase.PAUSED -> 0f
        AutomationPhase.COMPLETED -> 1f
        AutomationPhase.ERROR -> 1f
    }

    private fun AutomationPhase.bubbleLabel(): String = when (this) {
        AutomationPhase.IDLE -> "待机"
        AutomationPhase.WAITING_FOR_SERVICE -> "服务"
        AutomationPhase.WAITING_FOR_SHOP -> "等待"
        AutomationPhase.SCANNING_TOP -> "上扫"
        AutomationPhase.PURCHASING -> "购买"
        AutomationPhase.VERIFYING_PURCHASE -> "确认"
        AutomationPhase.SCANNING_BOTTOM -> "下扫"
        AutomationPhase.REFRESHING -> "刷新"
        AutomationPhase.WAITING_FOR_REFRESH -> "加载"
        AutomationPhase.PAUSED -> "暂停"
        AutomationPhase.COMPLETED -> "完成"
        AutomationPhase.ERROR -> "异常"
    }

    private fun AutomationPhase.phaseColor(): Int = when (this) {
        AutomationPhase.COMPLETED -> COLOR_SUCCESS
        AutomationPhase.ERROR -> COLOR_ERROR
        AutomationPhase.PAUSED -> COLOR_WARNING
        else -> COLOR_ON_SURFACE
    }

    private fun HuntPhase.progress(): Float = when (this) {
        HuntPhase.IDLE -> 0f
        HuntPhase.WAITING_FOR_LOBBY -> 0.05f
        HuntPhase.OPENING_BATTLE -> 0.12f
        HuntPhase.OPENING_HUNT -> 0.20f
        HuntPhase.SELECTING_BOSS -> 0.28f
        HuntPhase.SELECTING_DIFFICULTY -> 0.36f
        HuntPhase.DISABLING_QUICK_BATTLE -> 0.44f
        HuntPhase.CONFIGURING_MANAGED_BATTLE -> 0.52f
        HuntPhase.STARTING_BATTLE -> 0.60f
        HuntPhase.WAITING_FOR_BATTLE_CONTROLS -> 0.68f
        HuntPhase.DELEGATING_BATTLE -> 0.76f
        HuntPhase.CONFIRMING_DELEGATION -> 0.84f
        HuntPhase.MANAGED_IN_LOBBY -> 0.94f
        HuntPhase.PAUSED -> 0f
        HuntPhase.COMPLETED -> 1f
        HuntPhase.ERROR -> 1f
    }

    private fun HuntPhase.bubbleLabel(): String = when (this) {
        HuntPhase.IDLE -> "待机"
        HuntPhase.WAITING_FOR_LOBBY -> "大厅"
        HuntPhase.OPENING_BATTLE -> "战斗"
        HuntPhase.OPENING_HUNT -> "讨伐"
        HuntPhase.SELECTING_BOSS -> "地下城"
        HuntPhase.SELECTING_DIFFICULTY -> "难度"
        HuntPhase.DISABLING_QUICK_BATTLE -> "快战"
        HuntPhase.CONFIGURING_MANAGED_BATTLE -> "托管"
        HuntPhase.STARTING_BATTLE -> "开始"
        HuntPhase.WAITING_FOR_BATTLE_CONTROLS -> "加载"
        HuntPhase.DELEGATING_BATTLE -> "转交"
        HuntPhase.CONFIRMING_DELEGATION -> "确认"
        HuntPhase.MANAGED_IN_LOBBY -> "挂机"
        HuntPhase.PAUSED -> "暂停"
        HuntPhase.COMPLETED -> "完成"
        HuntPhase.ERROR -> "异常"
    }

    private fun HuntPhase.phaseColor(): Int = when (this) {
        HuntPhase.COMPLETED -> COLOR_SUCCESS
        HuntPhase.ERROR -> COLOR_ERROR
        HuntPhase.PAUSED -> COLOR_WARNING
        else -> COLOR_ON_SURFACE
    }

    private enum class OverlayMode {
        SHOP,
        HUNT,
    }

    private enum class MetricType {
        COVENANT,
        MYSTIC,
        GOLD,
    }

    private companion object {
        const val COMPACT_SIZE_DP = 72
        const val BUBBLE_HEIGHT_DP = 72
        const val EXPANDED_WIDTH_DP = 480
        const val ACTION_SIZE_DP = 34
        const val ACTION_GAP_DP = 6
        const val ACTION_PADDING_DP = 8
        const val METRIC_ICON_SIZE_DP = 24f
        const val METRIC_ICON_GAP_DP = 2f
        const val ACTION_STOP = 0
        const val ACTION_PAUSE = 1
        const val ACTION_HOME = 2
        const val EXPAND_DURATION_MS = 240L
        const val PROGRESS_DURATION_MS = 260L
        const val STOP_CONFIRMATION_MS = 3_000L
        const val HOVER_EXIT_DELAY_MS = 140L
        const val VISION_ASSET_ROOT = "vision/cn_1920x1080"

        const val COLOR_SURFACE = 0xFFFFFFFF.toInt()
        const val COLOR_ON_SURFACE = 0xFF1B1B1B.toInt()
        const val COLOR_ON_SURFACE_VARIANT = 0xFF5F5F5F.toInt()
        const val COLOR_OUTLINE = 0xFFDEDEDE.toInt()
        const val COLOR_PROGRESS_TRACK = 0xFFEBEBEB.toInt()
        const val COLOR_SUCCESS = 0xFF247A52.toInt()
        const val COLOR_WARNING = 0xFF8A5A00.toInt()
        const val COLOR_ERROR = 0xFFBA1A1A.toInt()
    }
}
