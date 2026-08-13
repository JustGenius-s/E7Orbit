package com.e7orbit.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.e7orbit.R
import com.e7orbit.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that downloads catalog assets (icons, artwork) in the background,
 * showing progress in the notification shade. Survives the user leaving the app;
 * stops itself when the batch is done.
 */
class AssetPreloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notificationManager: NotificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Call startForeground as early as possible. The system kills the service with
        // ForegroundServiceDidNotStartInTimeException if startForeground() isn't reached
        // within ~10s of startForegroundService(); doing it in onCreate (before any
        // per-intent work) keeps us well under that limit on cold starts.
        createNotificationChannel()
        startForegroundWithNotification(buildNotification(0, 0, indeterminate = true))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val urls = intent?.getStringArrayListExtra(EXTRA_URLS).orEmpty().distinct()
        if (urls.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        notificationManager.notify(
            NOTIFICATION_ID,
            buildNotification(0, urls.size, indeterminate = true),
        )

        scope.launch {
            var lastNotifiedDone = -1
            val result = IconAssetStore.preload(
                context = applicationContext,
                urls = urls,
            ) { done, total ->
                // Throttle shade updates; the final one is posted in the completion path.
                if (done - lastNotifiedDone >= NOTIFY_EVERY || done == total) {
                    lastNotifiedDone = done
                    notificationManager.notify(
                        NOTIFICATION_ID,
                        buildNotification(done, total, indeterminate = false),
                    )
                }
            }
            notificationManager.notify(
                NOTIFICATION_ID,
                buildCompletionNotification(result),
            )
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundWithNotification(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(done: Int, total: Int, indeterminate: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_orbit)
            .setContentTitle("正在下载游戏资源")
            .setContentText(if (indeterminate) "准备下载…" else "$done / $total")
            .setProgress(total, done, indeterminate)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .build()
    }

    private fun buildCompletionNotification(result: IconAssetStore.PreloadResult): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = buildString {
            append("完成：新下载 ").append(result.downloaded)
            if (result.skipped > 0) append("，已有 ").append(result.skipped)
            if (result.failed > 0) append("，失败 ").append(result.failed).append("（展示时重试）")
        }
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_orbit)
            .setContentTitle("资源下载完成")
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "资源下载",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        private const val EXTRA_URLS = "urls"
        private const val NOTIFICATION_CHANNEL_ID = "asset_preload"
        private const val NOTIFICATION_ID = 7301
        private const val NOTIFY_EVERY = 10

        fun start(context: Context, urls: Collection<String>) {
            if (urls.isEmpty()) return
            val intent = Intent(context, AssetPreloadService::class.java).apply {
                putStringArrayListExtra(EXTRA_URLS, ArrayList(urls))
            }
            context.startForegroundService(intent)
        }
    }
}
