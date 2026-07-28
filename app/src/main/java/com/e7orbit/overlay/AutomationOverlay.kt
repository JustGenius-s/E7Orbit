package com.e7orbit.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.e7orbit.automation.TaskCoordinator
import com.e7orbit.automation.TaskKind
import com.e7orbit.model.AutomationPhase
import com.e7orbit.model.AutomationStatus
import com.e7orbit.model.HuntPhase
import com.e7orbit.model.HuntStatus
import com.e7orbit.ui.MainActivity
import com.e7orbit.ui.theme.E7OrbitTheme
import kotlin.math.roundToInt

internal val AUTOMATION_OVERLAY_WINDOW_FLAGS: Int =
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_SECURE

@SuppressLint("ClickableViewAccessibility")
class AutomationOverlay(
    private val context: Context,
    private val taskCoordinator: TaskCoordinator,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val viewTreeOwners = OverlayViewTreeOwners()
    private val params = WindowManager.LayoutParams(
        dp(OverlayUiTokens.EDGE_HANDLE_WIDTH_DP),
        dp(OverlayUiTokens.HEIGHT_DP),
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        AUTOMATION_OVERLAY_WINDOW_FLAGS,
        android.graphics.PixelFormat.TRANSLUCENT,
    )

    private var uiState by mutableStateOf(AutomationOverlayUiState())
    private var presentation by mutableStateOf(OverlayPresentation.EDGE)
    private var anchorSide by mutableStateOf(OverlayDockSide.END)
    private var morph by mutableFloatStateOf(OverlayPresentation.EDGE.morph)
    private var stopConfirmationPending by mutableStateOf(false)

    private var added = false
    private var shouldBeVisible = false
    private var docked = true
    private var presentationAnimator: ValueAnimator? = null
    private var stopConfirmationDeadline = 0L
    private var dragStartX = 0
    private var dragStartY = 0
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f

    private val autoDockRunnable = Runnable {
        if (presentation == OverlayPresentation.COMPACT && docked) {
            setPresentation(OverlayPresentation.EDGE)
        }
    }
    private val resetStopConfirmationRunnable = Runnable {
        if (System.currentTimeMillis() > stopConfirmationDeadline) {
            stopConfirmationDeadline = 0L
            stopConfirmationPending = false
        }
    }

    private val overlayView = ComposeView(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        setViewTreeLifecycleOwner(viewTreeOwners)
        setViewTreeSavedStateRegistryOwner(viewTreeOwners)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            E7OrbitTheme {
                AutomationOverlayContent(
                    state = uiState,
                    morph = morph,
                    dockSide = anchorSide,
                    stopConfirmationPending = stopConfirmationPending,
                    onPrimaryClick = ::handlePrimaryClick,
                    onReturnToApp = ::returnToApp,
                    onPauseResume = ::togglePause,
                    onStop = ::requestStop,
                    onDragStart = ::handleDragStart,
                    onDrag = ::handleDrag,
                    onDragEnd = ::snapToEdge,
                )
            }
        }
    }

    init {
        params.gravity = Gravity.TOP or Gravity.START
        val area = availableArea()
        params.x = dockedX(anchorSide, params.width, area)
        params.y = area.top +
            ((area.bottom - area.top - params.height).coerceAtLeast(0) * 0.28f).roundToInt()
    }

    fun render(
        shopStatus: AutomationStatus,
        huntStatus: HuntStatus,
    ) {
        if (
            shopStatus.phase == AutomationPhase.IDLE &&
            huntStatus.phase == HuntPhase.IDLE
        ) {
            shouldBeVisible = false
            hide()
            return
        }
        shouldBeVisible = true
        uiState = AutomationOverlayUiState.from(shopStatus, huntStatus)
        show()
    }

    fun destroy() {
        presentationAnimator?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        if (added) {
            runCatching { windowManager.removeViewImmediate(overlayView) }
            added = false
        }
        viewTreeOwners.destroy()
        overlayView.disposeComposition()
    }

    private fun show() {
        if (added) return
        keepInsideScreen()
        windowManager.addView(overlayView, params)
        added = true
        viewTreeOwners.onShown()
        scheduleAutoDockIfNeeded()
    }

    private fun hide() {
        setPresentation(OverlayPresentation.EDGE, animate = false)
        viewTreeOwners.onHidden()
        if (!added) return
        runCatching { windowManager.removeView(overlayView) }
        added = false
    }

    private fun handlePrimaryClick() {
        when (presentation) {
            OverlayPresentation.EDGE -> setPresentation(OverlayPresentation.COMPACT)
            OverlayPresentation.COMPACT -> setPresentation(OverlayPresentation.EXPANDED)
            OverlayPresentation.EXPANDED -> setPresentation(OverlayPresentation.COMPACT)
        }
    }

    private fun setPresentation(
        value: OverlayPresentation,
        animate: Boolean = true,
    ) {
        if (presentation == value && presentationAnimator?.isRunning != true) {
            scheduleAutoDockIfNeeded()
            return
        }
        presentation = value
        cancelAutoDock()
        presentationAnimator?.cancel()

        val target = value.morph
        if (!animate) {
            applyMorph(target)
            scheduleAutoDockIfNeeded()
            return
        }
        presentationAnimator = ValueAnimator.ofFloat(morph, target).apply {
            duration = OverlayUiTokens.PRESENTATION_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { applyMorph(it.animatedValue as Float) }
            addListener(
                object : AnimatorListenerAdapter() {
                    private var cancelled = false

                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        if (!cancelled) scheduleAutoDockIfNeeded()
                    }
                },
            )
            start()
        }
    }

    private fun applyMorph(value: Float) {
        morph = value
        params.width = widthForMorph(value)
        keepInsideScreen()
        if (added) runCatching { windowManager.updateViewLayout(overlayView, params) }
    }

    private fun widthForMorph(value: Float): Int = if (value <= 1f) {
        lerp(
            dp(OverlayUiTokens.EDGE_HANDLE_WIDTH_DP),
            dp(OverlayUiTokens.COMPACT_SIZE_DP),
            value.coerceIn(0f, 1f),
        )
    } else {
        lerp(
            dp(OverlayUiTokens.COMPACT_SIZE_DP),
            expandedWidth(),
            (value - 1f).coerceIn(0f, 1f),
        )
    }

    private fun expandedWidth(): Int {
        val area = availableArea()
        return dp(OverlayUiTokens.EXPANDED_WIDTH_DP)
            .coerceAtMost(area.right - area.left)
            .coerceAtLeast(dp(OverlayUiTokens.EDGE_HANDLE_WIDTH_DP))
    }

    private fun keepInsideScreen() {
        val area = availableArea()
        val clamped = clampOverlayPosition(
            x = params.x,
            y = params.y,
            windowWidth = params.width,
            windowHeight = params.height,
            area = area,
            margin = dp(OverlayUiTokens.SCREEN_MARGIN_DP),
        )
        params.x = if (docked) dockedX(anchorSide, params.width, area) else clamped.x
        params.y = clamped.y
    }

    private fun updatePosition(x: Int, y: Int) {
        params.x = x
        params.y = y
        keepInsideScreen()
        if (added) runCatching { windowManager.updateViewLayout(overlayView, params) }
    }

    private fun handleDragStart() {
        cancelAutoDock()
        presentationAnimator?.cancel()
        docked = false
        dragStartX = params.x
        dragStartY = params.y
        dragOffsetX = 0f
        dragOffsetY = 0f
    }

    private fun handleDrag(deltaX: Float, deltaY: Float) {
        dragOffsetX += deltaX
        dragOffsetY += deltaY
        updatePosition(
            dragStartX + dragOffsetX.roundToInt(),
            dragStartY + dragOffsetY.roundToInt(),
        )
    }

    private fun snapToEdge() {
        val area = availableArea()
        val targetSide = nearestDockSide(params.x, params.width, area)
        val startX = params.x
        val startMorph = morph
        anchorSide = targetSide
        presentation = OverlayPresentation.EDGE
        cancelAutoDock()
        presentationAnimator?.cancel()
        docked = false

        presentationAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = OverlayUiTokens.SNAP_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                val animatedMorph = startMorph +
                    (OverlayPresentation.EDGE.morph - startMorph) * fraction
                morph = animatedMorph
                params.width = widthForMorph(animatedMorph)
                params.x = lerp(
                    startX,
                    dockedX(targetSide, params.width, area),
                    fraction,
                )
                params.y = clampOverlayPosition(
                    x = params.x,
                    y = params.y,
                    windowWidth = params.width,
                    windowHeight = params.height,
                    area = area,
                    margin = dp(OverlayUiTokens.SCREEN_MARGIN_DP),
                ).y
                if (added) runCatching { windowManager.updateViewLayout(overlayView, params) }
            }
            addListener(
                object : AnimatorListenerAdapter() {
                    private var cancelled = false

                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        if (cancelled) return
                        docked = true
                        applyMorph(OverlayPresentation.EDGE.morph)
                    }
                },
            )
            start()
        }
    }

    private fun scheduleAutoDockIfNeeded() {
        cancelAutoDock()
        if (presentation == OverlayPresentation.COMPACT && docked) {
            mainHandler.postDelayed(
                autoDockRunnable,
                OverlayUiTokens.AUTO_DOCK_DELAY_MS,
            )
        }
    }

    private fun cancelAutoDock() {
        mainHandler.removeCallbacks(autoDockRunnable)
    }

    private fun returnToApp() {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        context.startActivity(intent)
    }

    private fun togglePause() {
        when {
            uiState.isActiveTerminal -> restartActive()
            uiState.isActivePaused -> resumeActive()
            else -> pauseActive()
        }
    }

    private fun requestStop() {
        if (uiState.isActiveTerminal) {
            stopConfirmationDeadline = 0L
            stopConfirmationPending = false
            dismissActiveTerminal()
            return
        }
        val now = System.currentTimeMillis()
        if (now <= stopConfirmationDeadline) {
            stopConfirmationDeadline = 0L
            stopConfirmationPending = false
            stopActive()
            return
        }

        stopConfirmationDeadline = now + OverlayUiTokens.STOP_CONFIRMATION_MS
        stopConfirmationPending = true
        mainHandler.removeCallbacks(resetStopConfirmationRunnable)
        mainHandler.postDelayed(
            resetStopConfirmationRunnable,
            OverlayUiTokens.STOP_CONFIRMATION_MS,
        )
    }

    private fun dismissActiveTerminal() {
        taskCoordinator.dismiss(uiState.activeTaskKind)
    }

    private fun stopActive() {
        taskCoordinator.stop(uiState.activeTaskKind)
    }

    private fun restartActive() {
        taskCoordinator.restart(uiState.activeTaskKind)
    }

    private fun pauseActive() {
        taskCoordinator.pause(uiState.activeTaskKind)
    }

    private fun resumeActive() {
        taskCoordinator.resume(uiState.activeTaskKind)
    }

    private fun availableArea(): OverlayAvailableArea {
        val metrics = windowManager.currentWindowMetrics
        val insets = metrics.windowInsets.getInsets(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
        )
        return OverlayAvailableArea(
            left = insets.left,
            top = insets.top,
            right = metrics.bounds.width() - insets.right,
            bottom = metrics.bounds.height() - insets.bottom,
        )
    }

    private fun lerp(start: Int, end: Int, fraction: Float): Int =
        (start + (end - start) * fraction).roundToInt()

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()
}

private class OverlayViewTreeOwners : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    init {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun onShown() {
        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }
    }

    fun onHidden() {
        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
        }
    }

    fun destroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
}
