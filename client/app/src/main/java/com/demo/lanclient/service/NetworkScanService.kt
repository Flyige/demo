package com.demo.lanclient.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.demo.lanclient.R
import com.demo.lanclient.network.WebSocketManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

class NetworkScanService : Service() {

    companion object {
        const val CHANNEL_ID       = "lan_scan_channel"
        const val NOTIFICATION_ID  = 1001
        const val SERVER_PORT      = 9527
        const val SCAN_INTERVAL_MS = 15_000L
        const val SOCKET_TIMEOUT_MS = 500

        const val EXTRA_DEVICE_SN = "device_sn"

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
                        delay(2_000)
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
     * 并发扫描本机子网所有主机的 SERVER_PORT。
     * 使用 CompletableDeferred 在找到第一个响应后立即返回结果。
     */
    private suspend fun scanForServer(): String? = coroutineScope {
        val localIp = getLocalIpAddress() ?: return@coroutineScope null
        val prefix  = localIp.substringBeforeLast(".")

        val found = CompletableDeferred<String?>()

        val scanJob = launch(Dispatchers.IO) {
            (1..254).map { host ->
                async {
                    val ip = "$prefix.$host"
                    if (ip == localIp) return@async
                    try {
                        Socket().use { sock ->
                            sock.connect(InetSocketAddress(ip, SERVER_PORT), SOCKET_TIMEOUT_MS)
                        }
                        // 端口可达，尝试完成 Deferred（仅第一个成功的会生效）
                        found.complete(ip)
                    } catch (_: Exception) {
                        // 超时或拒绝，忽略
                    }
                }
            }.awaitAll()
            // 所有主机扫描完毕仍未找到
            found.complete(null)
        }

        val result = found.await()
        scanJob.cancel()
        result
    }

    private fun getLocalIpAddress(): String? {
        val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return null
        // 确认是 WiFi 网络
        val caps = cm.getNetworkCapabilities(network) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val props = cm.getLinkProperties(network) ?: return null
        return props.linkAddresses
            .mapNotNull { it.address as? java.net.Inet4Address }
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
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
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        wsManager?.disconnect()
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
