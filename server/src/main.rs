use axum::{
    extract::{
        ws::{Message, WebSocket, WebSocketUpgrade},
        State,
    },
    response::{Html, IntoResponse},
    routing::{get, post},
    Json, Router,
};
use futures_util::{SinkExt, StreamExt};
use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    net::SocketAddr,
    sync::Arc,
    time::Duration,
};
use tokio::sync::{broadcast, Mutex};
use tower_http::cors::CorsLayer;
use uuid::Uuid;

/// 每个已连接 Client 的元信息
#[derive(Clone, Serialize)]
struct ClientInfo {
    id: String,
    sn: String,
}

/// 共享状态
#[derive(Clone)]
struct AppState {
    /// client_id -> sn
    clients: Arc<Mutex<HashMap<String, String>>>,
    /// 广播通道：向所有已订阅的 WebSocket 连接发送消息
    tx: broadcast::Sender<String>,
}

/// POST /api/send 的请求体
#[derive(Deserialize)]
struct SendConfig {
    /// 发送次数，默认 10
    count: Option<u32>,
    /// 每次间隔（毫秒），默认 500
    interval_ms: Option<u64>,
}

/// 内联 HTML，编译时嵌入
const HTML: &str = include_str!("../static/index.html");

// ── 路由处理 ──────────────────────────────────────────────

async fn index_handler() -> Html<&'static str> {
    Html(HTML)
}

async fn ws_handler(
    ws: WebSocketUpgrade,
    State(state): State<AppState>,
) -> impl IntoResponse {
    ws.on_upgrade(|socket| handle_socket(socket, state))
}

async fn handle_socket(socket: WebSocket, state: AppState) {
    let client_id = Uuid::new_v4().to_string();
    let (mut sender, mut receiver) = socket.split();

    // 等待 client 发来第一条消息：{ "sn": "xxxx" }
    let sn = match receiver.next().await {
        Some(Ok(Message::Text(text))) => {
            let v: serde_json::Value = serde_json::from_str(&text).unwrap_or_default();
            v["sn"].as_str().unwrap_or("unknown").to_string()
        }
        _ => "unknown".to_string(),
    };

    tracing::info!("Client connected  id={} sn={}", client_id, sn);
    state
        .clients
        .lock()
        .await
        .insert(client_id.clone(), sn.clone());

    // 订阅广播
    let mut rx = state.tx.subscribe();
    let send_id = client_id.clone();

    // 将广播消息转发给该 client
    let send_task = tokio::spawn(async move {
        while let Ok(msg) = rx.recv().await {
            if sender.send(Message::Text(msg)).await.is_err() {
                break;
            }
        }
    });

    // 读取 client 的后续消息（维持连接，处理 Close 帧）
    loop {
        match receiver.next().await {
            Some(Ok(Message::Close(_))) | None => break,
            Some(Ok(Message::Ping(d))) => {
                // axum 自动回 Pong，无需手动处理，但 Ping 消息仍会到达 receiver
                let _ = d;
            }
            _ => {}
        }
    }

    send_task.abort();
    state.clients.lock().await.remove(&send_id);
    tracing::info!("Client disconnected id={} sn={}", send_id, sn);
}

/// POST /api/send — 触发循环发送 "test" 到所有 client
async fn send_handler(
    State(state): State<AppState>,
    Json(cfg): Json<SendConfig>,
) -> impl IntoResponse {
    let count = cfg.count.unwrap_or(10);
    let interval_ms = cfg.interval_ms.unwrap_or(500);
    let tx = state.tx.clone();

    tokio::spawn(async move {
        for i in 1..=count {
            let msg = format!("test#{i}");
            tracing::info!("Broadcast -> {}", msg);
            // 若无订阅者，send 会返回 Err，忽略即可
            let _ = tx.send(msg);
            tokio::time::sleep(Duration::from_millis(interval_ms)).await;
        }
        tracing::info!("Broadcast finished ({} messages)", count);
    });

    Json(serde_json::json!({
        "status": "started",
        "count": count,
        "interval_ms": interval_ms
    }))
}

/// GET /api/clients — 返回当前在线 client 列表
async fn clients_handler(State(state): State<AppState>) -> impl IntoResponse {
    let map = state.clients.lock().await;
    let list: Vec<ClientInfo> = map
        .iter()
        .map(|(id, sn)| ClientInfo {
            id: id.clone(),
            sn: sn.clone(),
        })
        .collect();
    Json(list)
}

// ── 入口 ──────────────────────────────────────────────────

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter("lan_server=debug,info")
        .init();

    let (tx, _rx) = broadcast::channel::<String>(128);
    let state = AppState {
        clients: Arc::new(Mutex::new(HashMap::new())),
        tx,
    };

    let app = Router::new()
        .route("/", get(index_handler))
        .route("/ws", get(ws_handler))
        .route("/api/send", post(send_handler))
        .route("/api/clients", get(clients_handler))
        .layer(CorsLayer::permissive())
        .with_state(state);

    let addr = SocketAddr::from(([0, 0, 0, 0], 9527));
    tracing::info!("LAN Server listening on http://0.0.0.0:9527");

    let listener = tokio::net::TcpListener::bind(addr).await.unwrap();
    axum::serve(listener, app).await.unwrap();
}
