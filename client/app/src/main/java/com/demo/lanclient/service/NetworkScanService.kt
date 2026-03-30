package com.demo.lanclient.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.demo.lanclient.R
import com.demo.lanclient.network.WebSocketManager
import com.demo.lanclient.network.WsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

class NetworkScanService : Service() {

    companion object {
        const val CHANNEL_ID      = "lan_scan_channel"
        const val NOTIFICATION_ID = 1001
        const val SERVER_PORT     = 9527
        const val SCAN_INTERVAL_MS = 15_000L   // 未连接时每 15 s 扫一次
        const val SOCKET_TIMEOUT_MS = 500

        const val EXTRA_DEVICE_SN = "device_sn"

        /** 供外部获取单例 WebSocketManager */
        @Volatile
        var wsManager: WebSocketManager? = null
            private set
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var scanJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("正在扫描局域网..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sn = intent?.getStringExtra(EXTRA_DEVICE_SN) ?: android.os.Build.SERIAL
        if (wsManager == null) {
            wsManager = WebSocketManager(sn)
        }
        startScanLoop()
        return START_STICKY
    }

    private fun startScanLoop() {
        scanJob?.cancel()
        scanJob = scope.launch {
            while (true) {
                val manager = wsManager ?: break
                if (!manager.isConnected()) {
                    updateNotification("扫描中...")
                    val serverIp = scanForServer()
                    if (serverIp != null) {
                        updateNotification("连接到 $serverIp...")
                        manager.connect(serverIp, SERVER_PORT)
                        // 等待连接结果
                        delay(2000)
                        if (manager.isConnected()) {
                            updateNotification("已连接：$serverIp")
                        }
                    } else {
                        updateNotification("未找到服务器，${SCAN_INTERVAL_MS / 1000}s 后重试")
                    }
                }
                delay(SCAN_INTERVAL_MS)
            }
        }
    }

    /**
     * 获取本机所在子网，并发扫描所有主机的 SERVER_PORT。
     * 返回第一个响应的 IP，或 null。
     */
    private suspend fun scanForServer(): String? = withContext(Dispatchers.IO) {
        val localIp = getLocalIpAddress() ?: return@withContext null
        val prefix   = localIp.substringBeforeLast(".")   // e.g. "192.168.1"

        // 并发扫描 1-254
        val results = (1..254).map { host ->
            scope.launch {
                val ip = "$prefix.$host"
                if (ip == localIp) return@launch
                try {
                    Socket().use { sock ->
                        sock.connect(InetSocketAddress(ip, SERVER_PORT), SOCKET_TIMEOUT_MS)
                        // 有响应说明端口开放
                    }
                    // 找到后通知父协程
                    throw FoundException(ip)
                } catch (_: FoundException) {
                    throw it   // re-throw
                } catch (_: Exception) {
                    // 超时或拒绝，忽略
                }
            }
        }

        var found: String? = null
        for (job in results) {
            try {
                job.join()
            } catch (e: FoundException) {
                found = e.ip
                // 取消其余扫描
                results.forEach { it.cancel() }
                break
            }
        }
        found
    }

    private fun getLocalIpAddress(): String? {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = wm.connectionInfo.ipAddress
        if (ip == 0) return null
        return String.format(
            "%d.%d.%d.%d",
            ip and 0xff,
            ip shr 8 and 0xff,
            ip shr 16 and 0xff,
            ip shr 24 and 0xff
        )
    }

    // ── Notification ──────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "局域网扫描",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "保持 LAN 连接的后台服务" }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LAN Client")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        wsManager?.disconnect()
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 用于在协程中跨 launch 边界传递"已找到"结果 */
    private class FoundException(val ip: String) : Exception(ip)
}
