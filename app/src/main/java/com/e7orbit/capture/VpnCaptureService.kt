package com.e7orbit.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.app.ServiceCompat
import com.e7orbit.AppGraph
import com.e7orbit.R
import com.e7orbit.ui.MainActivity
import com.e7orbit.vpn.GearPayloadSink
import com.e7orbit.vpn.VpnForwarder
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 装备抓包前台服务(VPNService 连接级代理)。
 *
 * 流程:
 *  1. 建立 VPN 接口并路由 0.0.0.0/0(全流量进 tun);
 *  2. [VpnForwarder] 为每个 TCP 连接建立伪连接 + protect() 真实 socket 双通道转发,
 *     UDP 无状态转发 —— 游戏全程保持在线;
 *  3. 3333/5222 端口的载荷由 [GearPayloadSink] 落盘,供协议解析。
 */
class VpnCaptureService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var forwarder: VpnForwarder? = null
    private var forwarderThread: Thread? = null
    private var payloadSink: GearPayloadSink? = null
    private val stopped = AtomicBoolean(false)
    private var captureSegments = 0L
    private var captureBytes = 0L
    private var captureReported = 0L

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_START -> startCapture()
            ACTION_STOP -> {
                finishCapture(importPayload = true)
                stopSelf()
            }
            else -> return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onDestroy() {
        finishCapture(importPayload = true)
        AppGraph.logger.info("vpn.service_destroyed")
        super.onDestroy()
    }

    private fun startCapture() {
        startForegroundCompat()
        if (vpnInterface != null) return

        stopped.set(false)
        captureSegments = 0L
        captureBytes = 0L
        captureReported = 0L

        val builder = Builder().apply {
            setSession("E7 Orbit 装备抓包")
            setConfigureIntent(PendingIntent.getActivity(
                this@VpnCaptureService,
                0,
                Intent(this@VpnCaptureService, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))
            addAddress(TUN_ADDRESS, 32)
            // IPv6:路由全部 IPv6 流量(游戏 TCP 主流量走 IPv6 时也能代理)
            addAddress(InetAddress.getByName(TUN_ADDRESS_V6), 128)
            addRoute(InetAddress.getByName("::"), 0)
            // 全路由(含 198.18.0.0/15 乐变网关段):
            // 游戏→乐变网关(MuMu 内置网络代理)的流量进 tun 被我们代理。
            // 早期全路由失败(protect 套娃/缺 ACK/mid-stream)已全部修复。
            addRoute(InetAddress.getByName("0.0.0.0"), 0)
            // 关键:把 E7Orbit 自身排除在 VPN 之外。
            // MuMu 模拟器上 VpnService.protect() 失效(返回 false),
            // 导致转发 socket 的出网流量又进 tun 自循环。
            // 自身 disallow 后,转发 socket 直接走物理网卡出网,无需 protect。
            addDisallowedApplication(this@VpnCaptureService.packageName)
        }

        val fd = try {
            builder.establish()
        } catch (error: Throwable) {
            AppGraph.logger.error("vpn.establish_failed", error)
            AppGraph.vpnCapture.reportError("VPN 建立失败: ${error.message}")
            stopSelf()
            return
        }
        if (fd == null) {
            AppGraph.logger.error("vpn.establish_rejected")
            AppGraph.vpnCapture.reportError("VPN 建立被拒绝,请检查系统设置")
            stopSelf()
            return
        }

        vpnInterface = fd
        payloadSink = GearPayloadSink(applicationContext)
        AppGraph.vpnCapture.attach()
        AppGraph.logger.info(
            "vpn.started",
            "sink" to payloadSink?.path(),
        )
        startForwarder(fd)
    }

    private fun startForwarder(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val activeForwarder = VpnForwarder(
            input = input,
            output = output,
            protectTcp = { socket ->
                val ok = protect(socket)
                if (!ok) AppGraph.logger.error("vpn.protect_tcp_failed")
            },
            protectUdp = { socket ->
                val ok = protect(socket)
                if (!ok) AppGraph.logger.error("vpn.protect_udp_failed")
            },
            onLog = { AppGraph.logger.info("vpn.relay.$it") },
            onStats = { packets, bytes -> AppGraph.vpnCapture.report(packets, bytes) },
            onGearPayload = { connectionId, port, direction, data ->
                if (payloadSink?.append(connectionId, port, direction, data) == true) {
                    captureSegments += 1
                    captureBytes += data.size
                    if (captureSegments - captureReported >= 64) {
                        captureReported = captureSegments
                        AppGraph.vpnCapture.reportCapture(captureSegments, captureBytes)
                    }
                }
            },
        )
        forwarder = activeForwarder
        forwarderThread = thread(name = "e7orbit-vpn-forwarder", isDaemon = true) {
            try {
                activeForwarder.run()
            } catch (error: Throwable) {
                if (!stopped.get()) AppGraph.logger.error("vpn.forwarder_stopped", error)
            } finally {
                AppGraph.vpnCapture.reportCapture(captureSegments, captureBytes)
            }
        }
    }

    private fun finishCapture(importPayload: Boolean) {
        if (!stopped.compareAndSet(false, true)) return

        forwarder?.close()
        forwarder = null
        vpnInterface?.close()
        vpnInterface = null
        forwarderThread?.interrupt()
        forwarderThread = null

        val sink = payloadSink
        payloadSink = null
        val payload = sink?.finish()
        AppGraph.vpnCapture.reportCapture(captureSegments, captureBytes)
        AppGraph.vpnCapture.detach()
        stopForeground(STOP_FOREGROUND_REMOVE)

        if (importPayload && payload != null) {
            AppGraph.gearImportRepository.import(payload)
        }
        AppGraph.logger.info(
            "vpn.capture_finished",
            "segments" to captureSegments,
            "bytes" to captureBytes,
            "import" to importPayload,
        )
    }

    private fun startForegroundCompat() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
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
            .setContentTitle("E7 Orbit 正在抓包")
            .setContentText("进入背包后返回 Orbit 并停止抓包")
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
                "装备抓包",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    override fun onCreate() {
        super.onCreate()
        AppGraph.logger.info("vpn.service_created")
        createNotificationChannel()
    }

    companion object {
        private const val ACTION_START = "com.e7orbit.capture.VPN_START"
        private const val ACTION_STOP = "com.e7orbit.capture.VPN_STOP"
        private const val NOTIFICATION_CHANNEL_ID = "vpn_capture"
        private const val NOTIFICATION_ID = 7202
        private const val TUN_ADDRESS = "10.8.0.2"
        private const val TUN_ADDRESS_V6 = "fd00::2"

        /** 装备数据端口(Fribbels 同款)。 */
        const val GEAR_PORT = 3333

        /** E7 聊天/推送端口。 */
        const val CHAT_PORT = 5222

        fun start(context: Context) {
            val intent = Intent(context, VpnCaptureService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, VpnCaptureService::class.java).apply {
                    action = ACTION_STOP
                },
            )
        }
    }
}
