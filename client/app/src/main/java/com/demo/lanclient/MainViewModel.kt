package com.demo.lanclient

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.demo.lanclient.network.WebSocketManager
import com.demo.lanclient.network.WsState
import com.demo.lanclient.service.NetworkScanService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class UiState(
    val wsState: WsState = WsState.IDLE,
    val connectedServer: String? = null,
    val messages: List<String> = emptyList()
)

class MainViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /**
     * 当 Service 启动后调用此方法注册监听，绑定 WebSocketManager 的 Flow。
     */
    fun attachManager(manager: WebSocketManager) {
        viewModelScope.launch {
            manager.state.collect { state ->
                _uiState.value = _uiState.value.copy(wsState = state)
            }
        }
        viewModelScope.launch {
            manager.connectedServer.collect { ip ->
                _uiState.value = _uiState.value.copy(connectedServer = ip)
            }
        }
        viewModelScope.launch {
            manager.messages.collect { msg ->
                val updated = (_uiState.value.messages + msg).takeLast(200)
                _uiState.value = _uiState.value.copy(messages = updated)
            }
        }
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(messages = emptyList())
    }
}
