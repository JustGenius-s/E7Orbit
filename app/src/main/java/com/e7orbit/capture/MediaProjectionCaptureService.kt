package com.e7orbit.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager
import com.e7orbit.AppGraph
import com.e7orbit.R
import com.e7orbit.automation.ScreenCaptureException
import com.e7orbit.model.ScreenFrame
import com.e7orbit.ui.MainActivity
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds
import androidx.core.graphics.createBitmap

class MediaProjectionCaptureService : Service() {
    private val captureMutex = Mutex()
    private val imageSignal = Channel<Unit>(Channel.CONFLATED)
    private val sequence = AtomicLong(0L)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val provider = ProjectionFrameProvider(::captureFrame)

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            AppGraph.logger.warn("projection.stopped_by_system")
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppGraph.initialize(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action != ACTION_START) return START_NOT_STICKY
        startForeground(NOTIFICATION_ID, buildNotification())
        if (projection != null) return START_NOT_STICKY

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = intent.intentExtra(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            AppGraph.logger.error("projection.invalid_consent_result")
            stopSelf()
            return START_NOT_STICKY
        }

        return try {
            startProjection(resultCode, resultData)
            START_NOT_STICKY
        } catch (error: Throwable) {
            AppGraph.logger.error("projection.start_failed", error)
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        AppGraph.projectionCapture.detach(provider)
        imageReader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        projection = null
        imageSignal.close()
        AppGraph.logger.info("projection.service_destroyed")
        super.onDestroy()
    }

    private fun startProjection(
        resultCode: Int,
        resultData: Intent,
    ) {
        val windowManager = getSystemService(WindowManager::class.java)
        val bounds = windowManager.maximumWindowMetrics.bounds
        val width = maxOf(bounds.width(), bounds.height())
        val height = minOf(bounds.width(), bounds.height())
        val densityDpi = resources.displayMetrics.densityDpi

        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        val activeProjection = projectionManager.getMediaProjection(resultCode, resultData)
            ?: throw ScreenCaptureException("无法创建 MediaProjection")
        activeProjection.registerCallback(projectionCallback, mainHandler)

        val reader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            MAX_IMAGES,
        )
        reader.setOnImageAvailableListener(
            { imageSignal.trySend(Unit) },
            mainHandler,
        )
        val display = activeProjection.createVirtualDisplay(
            "E7OrbitCapture",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null,
        )

        projection = activeProjection
        imageReader = reader
        virtualDisplay = display
        AppGraph.projectionCapture.attach(provider)
        AppGraph.logger.info(
            "projection.started",
            "width" to width,
            "height" to height,
            "densityDpi" to densityDpi,
        )
    }

    private suspend fun captureFrame(): ScreenFrame = captureMutex.withLock {
        val reader = imageReader
            ?: throw ScreenCaptureException("MediaProjection 尚未就绪")
        val image = acquireLatestImage(reader)
        try {
            image.toScreenFrame(sequence.incrementAndGet())
        } finally {
            image.close()
        }
    }

    private suspend fun acquireLatestImage(reader: ImageReader): Image {
        reader.acquireLatestImage()?.let { return it }
        return withTimeout(CAPTURE_TIMEOUT_MS.milliseconds) {
            while (true) {
                imageSignal.receive()
                reader.acquireLatestImage()?.let { return@withTimeout it }
            }
            throw ScreenCaptureException("等待 MediaProjection 帧超时")
        }
    }

    private fun Image.toScreenFrame(frameSequence: Long): ScreenFrame {
        val plane = planes.firstOrNull()
            ?: throw ScreenCaptureException("MediaProjection 图像没有像素平面")
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride <= 0 || rowStride <= 0) {
            throw ScreenCaptureException("MediaProjection 像素步幅无效")
        }

        val contentWidth = width
        val contentHeight = height
        val rowPadding = (rowStride - pixelStride * contentWidth).coerceAtLeast(0)
        val paddedWidth = contentWidth + rowPadding / pixelStride
        val padded = createBitmap(paddedWidth, contentHeight)
        padded.copyPixelsFromBuffer(plane.buffer)
        val bitmap = if (paddedWidth == contentWidth) {
            padded
        } else {
            Bitmap.createBitmap(padded, 0, 0, contentWidth, contentHeight).also {
                padded.recycle()
            }
        }
        return ScreenFrame(
            bitmap = bitmap,
            width = contentWidth,
            height = contentHeight,
            capturedAtElapsedMs = android.os.SystemClock.elapsedRealtime(),
            sequence = frameSequence,
        )
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_orbit)
            .setContentTitle("E7 Orbit 正在读取屏幕")
            .setContentText("自动化停止后可关闭屏幕捕获")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "屏幕捕获",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    @Suppress("DEPRECATION")
    private fun Intent.intentExtra(key: String): Intent? =
        if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(key, Intent::class.java)
        } else {
            getParcelableExtra(key)
        }

    companion object {
        private const val ACTION_START = "com.e7orbit.capture.START"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val NOTIFICATION_CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 7201
        private const val MAX_IMAGES = 3
        private const val CAPTURE_TIMEOUT_MS = 3_000L

        fun start(
            context: Context,
            resultCode: Int,
            resultData: Intent,
        ) {
            val intent = Intent(context, MediaProjectionCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
            context.startForegroundService(intent)
        }
    }
}
