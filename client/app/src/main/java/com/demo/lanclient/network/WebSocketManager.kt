package com.demo.lanclient.network

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class WsState { IDLE, CONNECTING, CONNECTED, DISCONNECTED }

/**
 * 管理与 Server 的 WebSocket 长连接。
 * 单例，由 NetworkScanService 持有。
 */
class WebSocketManager(private val deviceSn: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)   // 长连接不超时
        .build()

    private var ws: WebSocket? = null

    private val _state = MutableStateFlow(WsState.IDLE)
    val state = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val messages = _messages.asSharedFlow()

    private val _connectedServer = MutableStateFlow<String?>(null)
    val connectedServer = _connectedServer.asStateFlow()

    fun connect(serverIp: String, port: Int = 9527) {
        if (_state.value == WsState.CONNECTED || _state.value == WsState.CONNECTING) return

        _state.value = WsState.CONNECTING
        val url = "ws://$serverIp:$port/ws"
        val request = Request.Builder().url(url).build()

        ws = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                // 连接成功后立即发送设备 SN
                val payload = JSONObject().put("sn", deviceSn).toString()
                webSocket.send(payload)
                _state.value = WsState.CONNECTED
                _connectedServer.value = serverIp
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                _messages.tryEmit(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                _messages.tryEmit(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _state.value = WsState.DISCONNECTED
                _connectedServer.value = null
                ws = null
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _state.value = WsState.DISCONNECTED
                _connectedServer.value = null
                ws = null
            }
        })
    }

    fun disconnect() {
        ws?.close(1000, "Client closed")
        ws = null
        _state.value = WsState.IDLE
        _connectedServer.value = null
    }

    fun isConnected() = _state.value == WsState.CONNECTED
}
