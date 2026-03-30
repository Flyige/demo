package com.demo.lanclient

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.demo.lanclient.databinding.ActivityMainBinding
import com.demo.lanclient.network.WsState
import com.demo.lanclient.service.NetworkScanService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val vm: MainViewModel by viewModels()

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 用户决定后直接启动服务 */ startScanService() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUi()
        requestNotificationPermissionIfNeeded()
        observeState()
    }

    private fun setupUi() {
        binding.btnClear.setOnClickListener { vm.clearMessages() }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startScanService()
        } else {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun startScanService() {
        val sn = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val intent = Intent(this, NetworkScanService::class.java).apply {
            putExtra(NetworkScanService.EXTRA_DEVICE_SN, sn)
        }
        startForegroundService(intent)

        // 等待 Service 初始化 WebSocketManager 后再绑定 ViewModel
        lifecycleScope.launch {
            repeat(20) {
                val mgr = NetworkScanService.wsManager
                if (mgr != null) {
                    vm.attachManager(mgr)
                    return@launch
                }
                delay(200)
            }
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.uiState.collect { state ->
                    // 状态栏
                    binding.tvStatus.text = when (state.wsState) {
                        WsState.IDLE         -> "空闲"
                        WsState.CONNECTING   -> "连接中..."
                        WsState.CONNECTED    -> "已连接：${state.connectedServer}"
                        WsState.DISCONNECTED -> "已断开，等待重连..."
                    }
                    binding.tvStatus.setTextColor(
                        getColor(
                            if (state.wsState == WsState.CONNECTED) R.color.connected
                            else R.color.disconnected
                        )
                    )

                    // 消息列表
                    if (state.messages.isEmpty()) {
                        binding.tvEmpty.visibility = View.VISIBLE
                        binding.tvMessages.visibility = View.GONE
                    } else {
                        binding.tvEmpty.visibility = View.GONE
                        binding.tvMessages.visibility = View.VISIBLE
                        binding.tvMessages.text = state.messages.joinToString("\n")
                        // 自动滚动到底部
                        binding.scrollView.post {
                            binding.scrollView.fullScroll(View.FOCUS_DOWN)
                        }
                    }
                }
            }
        }
    }
}
