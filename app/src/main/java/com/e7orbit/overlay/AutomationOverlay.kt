package com.e7orbit.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.e7orbit.automation.AutomationRuntime
import com.e7orbit.model.AutomationPhase
import com.e7orbit.model.AutomationStatus
import com.e7orbit.ui.MainActivity
import kotlin.math.roundToInt

@SuppressLint("ClickableViewAccessibility", "SetTextI18n")
class AutomationOverlay(
    private val context: Context,
    private val runtime: AutomationRuntime,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val root = LinearLayout(context)
    private val title = TextView(context)
    private val summary = TextView(context)
    private val progress = ProgressBar(
        context,
        null,
        android.R.attr.progressBarStyleHorizontal,
    )
    private val details = TextView(context)
    private val expandedActions = LinearLayout(context)
    private val pauseButton = Button(context)
    private val expandButton = Button(context)
    private val stopButton = Button(context)
    private val returnButton = Button(context)
    private val params = WindowManager.LayoutParams(
        dp(360),
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    )

    private var added = false
    private var expanded = false
    private var pausedByExpansion = false
    private var stopConfirmationDeadline = 0L

    init {
        params.gravity = Gravity.TOP or Gravity.START
        params.x = dp(16)
        params.y = dp(48)
        buildView()
    }

    fun render(status: AutomationStatus) {
        if (status.phase == AutomationPhase.IDLE) {
            hide()
            return
        }
        show()

        val stateText = status.phase.displayName()
        title.text = "E7 Orbit  ·  $stateText"
        summary.text = buildString {
            append("刷新 ${status.stats.completedRefreshes}/${status.config.maxRefreshes}")
            append("  金币 ${"%,d".format(status.stats.goldSpent)}")
            appendLine()
            append(
                "誓约 ${status.stats.covenantBookmarksBought} " +
                    "(${"%.1f".format(status.stats.covenantRatePercent)}%)",
            )
            append(
                "  神秘 ${status.stats.mysticMedalsBought} " +
                    "(${"%.1f".format(status.stats.mysticRatePercent)}%)",
            )
        }
        progress.max = status.config.maxRefreshes.coerceAtLeast(1)
        progress.progress = status.stats.completedRefreshes.coerceAtMost(progress.max)
        details.text = buildString {
            appendLine(status.message)
            append("耗时 ${formatDuration(status.stats.elapsedMs)}")
            status.lastConfidence?.let {
                append("  ·  置信度 ${"%.1f".format(it * 100)}%")
            }
        }
        pauseButton.text = if (status.phase == AutomationPhase.PAUSED) "继续" else "暂停"
        pauseButton.isEnabled = status.isRunning || status.phase == AutomationPhase.PAUSED
        stopButton.isEnabled = status.isRunning || status.phase == AutomationPhase.PAUSED
        if (System.currentTimeMillis() > stopConfirmationDeadline) {
            stopButton.text = "停止"
        }
    }

    fun setCaptureHidden(hidden: Boolean) {
        if (added) root.alpha = if (hidden) 0f else 1f
    }

    fun destroy() {
        if (added) {
            runCatching { windowManager.removeViewImmediate(root) }
            added = false
        }
    }

    private fun buildView() {
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(12), dp(16), dp(12))
        root.background = roundedBackground(
            color = 0xFFFFFFFF.toInt(),
            radiusDp = 18f,
            strokeColor = 0xFFDEDEDE.toInt(),
        )
        root.elevation = dp(12).toFloat()

        title.setTextColor(0xFF1B1B1B.toInt())
        title.textSize = 15f
        title.setPadding(0, 0, 0, dp(6))
        root.addView(title, matchWrap())

        summary.setTextColor(0xFF3F3F3F.toInt())
        summary.textSize = 13f
        root.addView(summary, matchWrap())

        progress.progressTintList = android.content.res.ColorStateList.valueOf(
            0xFF1B1B1B.toInt(),
        )
        progress.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(
            0xFFEBEBEB.toInt(),
        )
        root.addView(
            progress,
            LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                dp(5),
            ).apply { topMargin = dp(8) },
        )

        val compactActions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        styleButton(pauseButton)
        pauseButton.text = "暂停"
        pauseButton.setOnClickListener {
            if (runtime.status.value.phase == AutomationPhase.PAUSED) {
                pausedByExpansion = false
                runtime.resume()
            } else {
                runtime.pause()
            }
        }
        styleButton(expandButton)
        expandButton.text = "详情"
        expandButton.setOnClickListener { setExpanded(!expanded) }
        compactActions.addView(pauseButton, weightedButton())
        compactActions.addView(expandButton, weightedButton().apply { marginStart = dp(8) })
        root.addView(compactActions, matchWrap().apply { topMargin = dp(8) })

        details.setTextColor(0xFF5F5F5F.toInt())
        details.textSize = 12f
        details.visibility = View.GONE
        details.setPadding(0, dp(10), 0, dp(6))
        root.addView(details, matchWrap())

        expandedActions.orientation = LinearLayout.HORIZONTAL
        expandedActions.visibility = View.GONE
        styleButton(returnButton)
        returnButton.text = "返回应用"
        returnButton.setOnClickListener {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(intent)
        }
        styleButton(stopButton, danger = true)
        stopButton.text = "停止"
        stopButton.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now <= stopConfirmationDeadline) {
                runtime.stop()
                stopConfirmationDeadline = 0L
                stopButton.text = "停止"
            } else {
                stopConfirmationDeadline = now + 3_000L
                stopButton.text = "确认停止"
            }
        }
        expandedActions.addView(returnButton, weightedButton())
        expandedActions.addView(stopButton, weightedButton().apply { marginStart = dp(6) })
        root.addView(expandedActions, matchWrap().apply { topMargin = dp(4) })

        title.setOnTouchListener(DragListener())
    }

    private fun setExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        details.visibility = if (expanded) View.VISIBLE else View.GONE
        expandedActions.visibility = if (expanded) View.VISIBLE else View.GONE
        expandButton.text = if (expanded) "收起" else "详情"
        if (expanded && runtime.status.value.isRunning) {
            pausedByExpansion = true
            runtime.pause()
        } else if (!expanded && pausedByExpansion) {
            pausedByExpansion = false
            runtime.resume()
        }
        if (added) windowManager.updateViewLayout(root, params)
    }

    private fun show() {
        if (added) return
        windowManager.addView(root, params)
        added = true
    }

    private fun hide() {
        if (!added) return
        runCatching { windowManager.removeView(root) }
        added = false
    }

    private fun styleButton(
        button: Button,
        danger: Boolean = false,
    ) {
        val textColor = if (danger) 0xFFBA1A1A.toInt() else 0xFF1B1B1B.toInt()
        val strokeColor = if (danger) 0xFFBA1A1A.toInt() else 0xFFDEDEDE.toInt()
        button.setTextColor(textColor)
        button.textSize = 12f
        button.isAllCaps = false
        button.minHeight = 0
        button.minimumHeight = 0
        button.setPadding(dp(8), dp(8), dp(8), dp(8))
        button.background = roundedBackground(
            color = 0xFFFFFFFF.toInt(),
            radiusDp = 12f,
            strokeColor = strokeColor,
        )
    }

    private fun roundedBackground(
        color: Int,
        radiusDp: Float,
        strokeColor: Int,
    ) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp.roundToInt()).toFloat()
        setStroke(dp(1), strokeColor)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
    )

    private fun weightedButton() = LinearLayout.LayoutParams(
        0,
        WindowManager.LayoutParams.WRAP_CONTENT,
        1f,
    )

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    private fun formatDuration(durationMs: Long): String {
        val seconds = durationMs.coerceAtLeast(0L) / 1000
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val remaining = seconds % 60
        return "%02d:%02d:%02d".format(hours, minutes, remaining)
    }

    private fun AutomationPhase.displayName(): String = when (this) {
        AutomationPhase.IDLE -> "待机"
        AutomationPhase.WAITING_FOR_SERVICE -> "等待服务"
        AutomationPhase.WAITING_FOR_SHOP -> "等待商店"
        AutomationPhase.SCANNING_TOP -> "扫描上半页"
        AutomationPhase.VERIFYING_PURCHASE -> "确认购买"
        AutomationPhase.SCANNING_BOTTOM -> "扫描下半页"
        AutomationPhase.REFRESHING -> "刷新中"
        AutomationPhase.WAITING_FOR_REFRESH -> "等待加载"
        AutomationPhase.PAUSED -> "已暂停"
        AutomationPhase.COMPLETED -> "已完成"
        AutomationPhase.ERROR -> "异常停止"
    }

    private inner class DragListener : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var moved = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    moved = moved ||
                        kotlin.math.abs(event.rawX - touchX) > dp(4) ||
                        kotlin.math.abs(event.rawY - touchY) > dp(4)
                    params.x = startX + (event.rawX - touchX).roundToInt()
                    params.y = startY + (event.rawY - touchY).roundToInt()
                    if (added) windowManager.updateViewLayout(root, params)
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) view.performClick()
                    params.x = params.x.coerceAtLeast(dp(8))
                    params.y = params.y.coerceAtLeast(dp(8))
                    if (added) windowManager.updateViewLayout(root, params)
                    return true
                }
            }
            return false
        }
    }
}
