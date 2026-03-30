# 当前进度 & 卡点记录

## 已完成

### Server（Rust + axum）
- WebSocket 服务，固定端口 9527
- HTTP 服务，提供 Web 控制台（`GET /`）
- `POST /api/send` — 循环广播 "test" 消息到所有 client
- `GET /api/clients` — 返回在线 client 列表（SN + ID）
- Web UI：发送次数/间隔可配置，设备列表自动刷新

### Client（Android 13+，Kotlin，MVVM）
- 前台服务 `NetworkScanService`，扫描局域网子网 1-254，并发探测 9527 端口
- `WebSocketManager`（OkHttp）：连接后发送设备 SN，接收消息
- `MainViewModel`：收集连接状态 + 消息流
- `MainActivity`：显示连接状态与收到的消息，支持清除

## 当前卡点

### Server 编译失败（网络问题）
- **现象**：`cargo run` 下载 axum 等依赖时 crates.io 超时（家庭网络）
- **已修复**：在 `server/.cargo/config.toml` 中配置了 rsproxy 国内镜像
- **明天操作**：在公司网络或使用镜像后重新执行 `cargo run`

```powershell
cd D:\ClaudeCodeProjects\demo\server
cargo run
# 看到 "LAN Server listening on http://0.0.0.0:9527" 即成功
```

### 访问方式（容易踩坑）
- ✅ 正确：浏览器输入 `http://<电脑局域网IP>:9527`
- ❌ 错误：直接双击打开 `index.html` 文件（会显示访问提示并阻止操作）

### 防火墙
已执行（家里电脑已放行）：
```powershell
netsh advfirewall firewall add rule name="LAN Server 9527" protocol=TCP dir=in localport=9527 action=allow
```
公司电脑同样需要执行一次。

### Android 连接
- 手机与电脑需在**同一 WiFi** 下
- Server 启动后，App 启动约 15s 内会自动扫描并建立连接
- 如长时间未连接可杀掉 App 重启

## 下一步

- [ ] 验证端到端通信（server 发 → client 收）
- [ ] 考虑 server 显示每个 client 的最后收到确认时间
