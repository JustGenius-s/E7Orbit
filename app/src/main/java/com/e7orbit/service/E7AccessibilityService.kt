package com.e7orbit.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.e7orbit.AppGraph
import com.e7orbit.automation.ScreenGateway
import com.e7orbit.model.GestureResult
import com.e7orbit.model.ScreenFrame
import com.e7orbit.model.ScreenPoint
import com.e7orbit.overlay.AutomationOverlay
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class E7AccessibilityService : AccessibilityService(), ScreenGateway {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val captureMutex = Mutex()
    private val gestureMutex = Mutex()
    private var lastCaptureAt = 0L
    private var statusJob: Job? = null
    private var overlay: AutomationOverlay? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppGraph.initialize(applicationContext)
        AppGraph.logger.info("service.connected")
        overlay = AutomationOverlay(this, AppGraph.automationRuntime)
        AppGraph.automationRuntime.attachGateway(this)
        statusJob?.cancel()
        statusJob = serviceScope.launch {
            AppGraph.automationRuntime.status.collectLatest { status ->
                overlay?.render(status)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        AppGraph.logger.warn("service.interrupted")
        AppGraph.automationRuntime.pause()
    }

    override fun onDestroy() {
        AppGraph.logger.warn("service.destroyed")
        statusJob?.cancel()
        overlay?.destroy()
        overlay = null
        AppGraph.automationRuntime.detachGateway(this)
        serviceScope.cancel()
        super.onDestroy()
    }

    override suspend fun capture(): ScreenFrame = captureMutex.withLock {
        val elapsed = SystemClock.elapsedRealtime() - lastCaptureAt
        if (elapsed < MIN_CAPTURE_INTERVAL_MS) {
            delay(MIN_CAPTURE_INTERVAL_MS - elapsed)
        }

        try {
            AppGraph.projectionCapture.capture().also { frame ->
                AppGraph.logger.debug(
                    "service.capture",
                    "sequence" to frame.sequence,
                    "width" to frame.width,
                    "height" to frame.height,
                    "overlayHidden" to false,
                )
            }
        } finally {
            lastCaptureAt = SystemClock.elapsedRealtime()
        }
    }

    override suspend fun tap(point: ScreenPoint): GestureResult = gestureMutex.withLock {
        val path = Path().apply {
            moveTo(point.x.toFloat(), point.y.toFloat())
        }
        dispatch(
            GestureDescription.StrokeDescription(
                path,
                0L,
                TAP_DURATION_MS,
            ),
        ).also { result ->
            AppGraph.logger.debug(
                "service.tap",
                "point" to "${point.x},${point.y}",
                "result" to result,
            )
        }
    }

    override suspend fun swipe(
        from: ScreenPoint,
        to: ScreenPoint,
        durationMs: Long,
    ): GestureResult = gestureMutex.withLock {
        val path = Path().apply {
            moveTo(from.x.toFloat(), from.y.toFloat())
            lineTo(to.x.toFloat(), to.y.toFloat())
        }
        dispatch(
            GestureDescription.StrokeDescription(
                path,
                0L,
                durationMs.coerceIn(100L, 2_000L),
            ),
        ).also { result ->
            AppGraph.logger.debug(
                "service.swipe",
                "from" to "${from.x},${from.y}",
                "to" to "${to.x},${to.y}",
                "durationMs" to durationMs,
                "result" to result,
            )
        }
    }

    private suspend fun dispatch(
        stroke: GestureDescription.StrokeDescription,
    ): GestureResult = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            val gesture = GestureDescription.Builder()
                .addStroke(stroke)
                .build()
            val accepted = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) {
                            continuation.resume(GestureResult.COMPLETED)
                        }
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) {
                            continuation.resume(GestureResult.CANCELLED)
                        }
                    }
                },
                null,
            )
            if (!accepted && continuation.isActive) {
                continuation.resume(GestureResult.REJECTED)
            }
        }
    }

    private companion object {
        const val MIN_CAPTURE_INTERVAL_MS = 360L
        const val TAP_DURATION_MS = 80L
    }
}
