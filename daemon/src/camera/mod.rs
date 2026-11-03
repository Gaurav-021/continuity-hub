use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::Arc;
use prost::Message;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tokio::sync::{broadcast, mpsc, RwLock};
use tracing::{debug, info, warn};

use crate::proto::{
    camera_config::Lens,
    camera_control_command::Action as CameraAction,
    camera_message::Msg,
    input_message::Event as InputEvent,
    phone_nav_action::Action as PhoneNavActionEnum,
    phone_touch_event::TouchType as PhoneTouchTypeEnum,
    CameraConfig, CameraControlCommand, CameraFrame, CameraMessage, CameraSnapshot,
    Channel, DesktopScreenFrame, InputMessage, Packet, PhoneNavAction, PhoneTextAction, PhoneTouchEvent,
};

pub mod v4l2;
pub mod sidecar;
use v4l2::V4l2LoopbackWriter;
use sidecar::SidecarManager;

#[derive(Clone, Debug)]
pub struct CameraStatus {
    pub is_streaming: bool,
    pub width: u32,
    pub height: u32,
    pub lens: String,
    pub fps: u32,
    pub torch: bool,
    pub zoom: f32,
    pub rotation: u32,
}

pub struct CameraManager {
    my_device_id: String,
    http_port: u16,
    is_streaming: Arc<AtomicBool>,
    is_desktop_streaming: Arc<AtomicBool>,
    is_sidecar_streaming: Arc<AtomicBool>,
    is_phone_mirror_streaming: Arc<AtomicBool>,
    sidecar_mgr: Arc<SidecarManager>,
    latest_frame: Arc<RwLock<Option<CameraFrame>>>,
    latest_phone_frame: Arc<RwLock<Option<CameraFrame>>>,
    active_peer_tx: Arc<RwLock<Option<mpsc::Sender<Packet>>>>,
    frame_tx: broadcast::Sender<bytes::Bytes>,
    phone_frame_tx: broadcast::Sender<bytes::Bytes>,
    fps_counter: Arc<AtomicU32>,
    current_fps: Arc<AtomicU32>,
    phone_fps_counter: Arc<AtomicU32>,
    current_phone_fps: Arc<AtomicU32>,
    current_lens: Arc<RwLock<String>>,
    current_rotation: Arc<AtomicU32>,
    current_width: Arc<RwLock<u32>>,
    current_height: Arc<RwLock<u32>>,
    phone_width: Arc<RwLock<u32>>,
    phone_height: Arc<RwLock<u32>>,
    snapshot_path: PathBuf,
    v4l2_writer: Arc<tokio::sync::Mutex<V4l2LoopbackWriter>>,
    file_transfer_mgr: Arc<RwLock<Option<Arc<crate::transfer::FileTransferManager>>>>,
    is_phone_privacy_active: Arc<AtomicBool>,
}

impl CameraManager {
    pub fn new(my_device_id: String) -> Arc<Self> {
        Self::with_port(my_device_id, 52802)
    }

    pub fn with_port(my_device_id: String, http_port: u16) -> Arc<Self> {
        let (frame_tx, _) = broadcast::channel::<bytes::Bytes>(32);
        let (phone_frame_tx, _) = broadcast::channel::<bytes::Bytes>(32);
        let snapshot_path = std::env::temp_dir().join("continuity-camera-snapshot.jpg");
        let v4l2_writer = Arc::new(tokio::sync::Mutex::new(V4l2LoopbackWriter::new()));
        let file_transfer_mgr = Arc::new(RwLock::new(None));
        let is_phone_privacy_active = Arc::new(AtomicBool::new(false));

        let is_phone_mirror_streaming = Arc::new(AtomicBool::new(false));
        let phone_fps_counter = Arc::new(AtomicU32::new(0));
        let current_phone_fps = Arc::new(AtomicU32::new(0));
        let phone_width = Arc::new(RwLock::new(720));
        let phone_height = Arc::new(RwLock::new(1600));

        let manager = Arc::new(Self {
            my_device_id,
            http_port,
            is_streaming: Arc::new(AtomicBool::new(false)),
            is_desktop_streaming: Arc::new(AtomicBool::new(false)),
            is_sidecar_streaming: Arc::new(AtomicBool::new(false)),
            is_phone_mirror_streaming,
            sidecar_mgr: Arc::new(SidecarManager::new()),
            latest_frame: Arc::new(RwLock::new(None)),
            latest_phone_frame: Arc::new(RwLock::new(None)),
            active_peer_tx: Arc::new(RwLock::new(None)),
            frame_tx,
            phone_frame_tx,
            fps_counter: Arc::new(AtomicU32::new(0)),
            current_fps: Arc::new(AtomicU32::new(0)),
            phone_fps_counter: Arc::clone(&phone_fps_counter),
            current_phone_fps: Arc::clone(&current_phone_fps),
            current_lens: Arc::new(RwLock::new("Back".to_string())),
            current_rotation: Arc::new(AtomicU32::new(0)),
            current_width: Arc::new(RwLock::new(1920)),
            current_height: Arc::new(RwLock::new(1080)),
            phone_width,
            phone_height,
            snapshot_path,
            v4l2_writer,
            file_transfer_mgr,
            is_phone_privacy_active,
        });

        // Spawn FPS calculation ticker (every 1 second)
        let fps_counter = Arc::clone(&manager.fps_counter);
        let current_fps = Arc::clone(&manager.current_fps);
        tokio::spawn(async move {
            let mut interval = tokio::time::interval(tokio::time::Duration::from_secs(1));
            loop {
                interval.tick().await;
                let count = fps_counter.swap(0, Ordering::Relaxed);
                current_fps.store(count, Ordering::Relaxed);
                let p_count = phone_fps_counter.swap(0, Ordering::Relaxed);
                current_phone_fps.store(p_count, Ordering::Relaxed);
            }
        });

        manager
    }

    pub async fn start_http_stream_server(self: &Arc<Self>) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        let addr = format!("127.0.0.1:{}", self.http_port);
        let listener = TcpListener::bind(&addr).await?;
        info!("Continuity Camera HTTP MJPEG stream server listening on http://{}/live.mjpg", addr);

        let manager_clone = Arc::clone(self);
        tokio::spawn(async move {
            while let Ok((mut stream, peer_addr)) = listener.accept().await {
                debug!("Incoming HTTP camera stream connection from {}", peer_addr);
                let manager = Arc::clone(&manager_clone);

                tokio::spawn(async move {
                    let mut buf = [0u8; 4096];
                    let n = match stream.read(&mut buf).await {
                        Ok(n) if n > 0 => n,
                        _ => return,
                    };

                    let req_str = String::from_utf8_lossy(&buf[..n]);
                    let first_line = req_str.lines().next().unwrap_or("");
                    let mut parts = first_line.split_whitespace();
                    let method = parts.next().unwrap_or("GET");
                    let full_path = parts.next().unwrap_or("/");

                    if method == "OPTIONS" {
                        let cors_resp = "HTTP/1.1 200 OK\r\n\
                                         Access-Control-Allow-Origin: *\r\n\
                                         Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n\
                                         Access-Control-Allow-Headers: Content-Type\r\n\
                                         Content-Length: 0\r\n\r\n";
                        let _ = stream.write_all(cors_resp.as_bytes()).await;
                        return;
                    }

                    let path = full_path.split('?').next().unwrap_or(full_path);
                    let query_string = full_path.split_once('?').map(|(_, q)| q).unwrap_or("");

                    if path.starts_with("/live.mjpg") {
                        // MJPEG Stream multipart response
                        let header = "HTTP/1.1 200 OK\r\n\
                                      Content-Type: multipart/x-mixed-replace; boundary=frame\r\n\
                                      Cache-Control: no-cache, no-store, must-revalidate\r\n\
                                      Pragma: no-cache\r\n\
                                      Expires: 0\r\n\
                                      Access-Control-Allow-Origin: *\r\n\
                                      Connection: close\r\n\r\n";
                        if stream.write_all(header.as_bytes()).await.is_err() {
                            return;
                        }

                        let mut frame_rx = manager.frame_tx.subscribe();
                        loop {
                            match frame_rx.recv().await {
                                Ok(frame_bytes) => {
                                    let part_header = format!(
                                        "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: {}\r\n\r\n",
                                        frame_bytes.len()
                                    );
                                    if stream.write_all(part_header.as_bytes()).await.is_err() {
                                        break;
                                    }
                                    if stream.write_all(&frame_bytes).await.is_err() {
                                        break;
                                    }
                                    if stream.write_all(b"\r\n").await.is_err() {
                                        break;
                                    }
                                }
                                Err(broadcast::error::RecvError::Lagged(skipped)) => {
                                    debug!("Camera MJPEG HTTP client lagged, skipped {} frames", skipped);
                                    continue;
                                }
                                Err(broadcast::error::RecvError::Closed) => {
                                    break;
                                }
                            }
                        }
                    } else if path.starts_with("/phone/stream.mjpg") || path.starts_with("/phone.mjpg") {
                        // Phone Mirror MJPEG Stream multipart response
                        let header = "HTTP/1.1 200 OK\r\n\
                                      Content-Type: multipart/x-mixed-replace; boundary=frame\r\n\
                                      Cache-Control: no-cache, no-store, must-revalidate\r\n\
                                      Pragma: no-cache\r\n\
                                      Expires: 0\r\n\
                                      Access-Control-Allow-Origin: *\r\n\
                                      Connection: close\r\n\r\n";
                        if stream.write_all(header.as_bytes()).await.is_err() {
                            return;
                        }

                        // Send latest frame immediately if available
                        if let Some(ref frame) = *manager.latest_phone_frame.read().await {
                            let part_header = format!(
                                "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: {}\r\n\r\n",
                                frame.frame_data.len()
                            );
                            let _ = stream.write_all(part_header.as_bytes()).await;
                            let _ = stream.write_all(&frame.frame_data).await;
                            let _ = stream.write_all(b"\r\n").await;
                        }

                        let mut frame_rx = manager.phone_frame_tx.subscribe();
                        loop {
                            match frame_rx.recv().await {
                                Ok(frame_bytes) => {
                                    let part_header = format!(
                                        "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: {}\r\n\r\n",
                                        frame_bytes.len()
                                    );
                                    if stream.write_all(part_header.as_bytes()).await.is_err() {
                                        break;
                                    }
                                    if stream.write_all(&frame_bytes).await.is_err() {
                                        break;
                                    }
                                    if stream.write_all(b"\r\n").await.is_err() {
                                        break;
                                    }
                                }
                                Err(broadcast::error::RecvError::Lagged(skipped)) => {
                                    debug!("Phone mirror MJPEG client lagged, skipped {} frames", skipped);
                                    continue;
                                }
                                Err(broadcast::error::RecvError::Closed) => {
                                    break;
                                }
                            }
                        }
                    } else if path.starts_with("/phone/snapshot.jpg") {
                        let frame_opt = manager.latest_phone_frame.read().await.clone();
                        if let Some(frame) = frame_opt {
                            let header = format!(
                                "HTTP/1.1 200 OK\r\n\
                                 Content-Type: image/jpeg\r\n\
                                 Content-Length: {}\r\n\
                                 Access-Control-Allow-Origin: *\r\n\
                                 Connection: close\r\n\r\n",
                                frame.frame_data.len()
                            );
                            let _ = stream.write_all(header.as_bytes()).await;
                            let _ = stream.write_all(&frame.frame_data).await;
                        } else {
                            let not_found = "HTTP/1.1 404 Not Found\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: 0\r\n\r\n";
                            let _ = stream.write_all(not_found.as_bytes()).await;
                        }
                    } else if path.starts_with("/phone/status") {
                        let is_mirroring = manager.is_phone_mirror_streaming.load(Ordering::Relaxed);
                        let w = *manager.phone_width.read().await;
                        let h = *manager.phone_height.read().await;
                        let fps = manager.current_phone_fps.load(Ordering::Relaxed);

                        let json = serde_json::json!({
                            "is_mirroring": is_mirroring,
                            "width": w,
                            "height": h,
                            "fps": fps,
                            "stream_url": format!("http://127.0.0.1:{}/phone/stream.mjpg", manager.http_port),
                        });
                        let body = json.to_string();
                        let header = format!(
                            "HTTP/1.1 200 OK\r\n\
                             Content-Type: application/json\r\n\
                             Content-Length: {}\r\n\
                             Access-Control-Allow-Origin: *\r\n\
                             Connection: close\r\n\r\n",
                            body.len()
                        );
                        let _ = stream.write_all(header.as_bytes()).await;
                        let _ = stream.write_all(body.as_bytes()).await;
                    } else if path.starts_with("/phone/start") {
                        let ok = manager.start_phone_mirror().await;
                        let json = serde_json::json!({ "ok": ok, "status": if ok { "started" } else { "failed" } });
                        let body = json.to_string();
                        let header = format!(
                            "HTTP/1.1 200 OK\r\n\
                             Content-Type: application/json\r\n\
                             Content-Length: {}\r\n\
                             Access-Control-Allow-Origin: *\r\n\
                             Connection: close\r\n\r\n",
                            body.len()
                        );
                        let _ = stream.write_all(header.as_bytes()).await;
                        let _ = stream.write_all(body.as_bytes()).await;
                    } else if path.starts_with("/phone/stop") {
                        let ok = manager.stop_phone_mirror().await;
                        let json = serde_json::json!({ "ok": ok, "status": if ok { "stopped" } else { "failed" } });
                        let body = json.to_string();
                        let header = format!(
                            "HTTP/1.1 200 OK\r\n\
                             Content-Type: application/json\r\n\
                             Content-Length: {}\r\n\
                             Access-Control-Allow-Origin: *\r\n\
                             Connection: close\r\n\r\n",
                            body.len()
                        );
                        let _ = stream.write_all(header.as_bytes()).await;
                        let _ = stream.write_all(body.as_bytes()).await;
                    } else if path.starts_with("/phone/privacy") {
                        let mut enabled = true;
                        for part in query_string.split('&') {
                            if let Some((k, v)) = part.split_once('=') {
                                if k == "enabled" {
                                    enabled = v == "true" || v == "1";
                                }
                            }
                        }
                        let req_body = req_str.split_once("\r\n\r\n")
                            .map(|(_, b)| b)
                            .or_else(|| req_str.split_once("\n\n").map(|(_, b)| b))
                            .unwrap_or("");
                        if let Ok(json) = serde_json::from_str::<serde_json::Value>(req_body.trim()) {
                            if let Some(v) = json.get("enabled").and_then(|v| v.as_bool()) {
                                enabled = v;
                            }
                        }

                        let ok = manager.set_phone_privacy_mode(enabled).await;
                        let json = serde_json::json!({ "ok": ok, "privacy_mode": enabled });
                        let body = json.to_string();
                        let header = format!(
                            "HTTP/1.1 200 OK\r\n\
                             Content-Type: application/json\r\n\
                             Content-Length: {}\r\n\
                             Access-Control-Allow-Origin: *\r\n\
                             Connection: close\r\n\r\n",
                            body.len()
                        );
                        let _ = stream.write_all(header.as_bytes()).await;
                        let _ = stream.write_all(body.as_bytes()).await;
                    } else if path.starts_with("/phone/drop") {
                        // Extract filename from query parameter
                        let mut filename = "dropped_file".to_string();
                        for part in query_string.split('&') {
                            if let Some((k, v)) = part.split_once('=') {
                                if k == "filename" || k == "name" {
                                    filename = url_decode(v);
                                }
                            }
                        }

                        let clean_filename = std::path::Path::new(&filename)
                            .file_name()
                            .and_then(|f| f.to_str())
                            .unwrap_or("dropped_file")
                            .to_string();

                        let drop_dir = std::env::temp_dir().join("continuity-drops");
                        let _ = tokio::fs::create_dir_all(&drop_dir).await;
                        let dest_path = drop_dir.join(&clean_filename);

                        let mut content_length = 0usize;
                        for line in req_str.lines() {
                            if let Some((k, v)) = line.split_once(':') {
                                if k.trim().eq_ignore_ascii_case("content-length") {
                                    content_length = v.trim().parse().unwrap_or(0);
                                }
                            }
                        }

                        let header_end_opt = buf[..n].windows(4).position(|w| w == b"\r\n\r\n").map(|p| p + 4)
                            .or_else(|| buf[..n].windows(2).position(|w| w == b"\n\n").map(|p| p + 2));

                        let mut dest_file = match tokio::fs::File::create(&dest_path).await {
                            Ok(f) => f,
                            Err(e) => {
                                let err_json = serde_json::json!({ "ok": false, "error": format!("Cannot create file: {}", e) });
                                let body = err_json.to_string();
                                let header = format!("HTTP/1.1 500 Internal Server Error\r\nContent-Type: application/json\r\nContent-Length: {}\r\nAccess-Control-Allow-Origin: *\r\n\r\n", body.len());
                                let _ = stream.write_all(header.as_bytes()).await;
                                let _ = stream.write_all(body.as_bytes()).await;
                                return;
                            }
                        };

                        let mut bytes_written = 0usize;
                        if let Some(hdr_end) = header_end_opt {
                            let initial_chunk = &buf[hdr_end..n];
                            if !initial_chunk.is_empty() {
                                let _ = dest_file.write_all(initial_chunk).await;
                                bytes_written += initial_chunk.len();
                            }
                        }

                        let mut chunk = [0u8; 64 * 1024];
                        while bytes_written < content_length {
                            let remaining = content_length - bytes_written;
                            let to_read = std::cmp::min(chunk.len(), remaining);
                            match stream.read(&mut chunk[..to_read]).await {
                                Ok(0) => break,
                                Ok(bytes_read) => {
                                    let _ = dest_file.write_all(&chunk[..bytes_read]).await;
                                    bytes_written += bytes_read;
                                }
                                Err(_) => break,
                            }
                        }
                        let _ = dest_file.flush().await;
                        drop(dest_file);

                        let active_tx_opt = manager.active_peer_tx.read().await.clone();
                        let file_mgr_opt = manager.file_transfer_mgr.read().await.clone();
                        let (transfer_queued, status_msg) = if let (Some(peer_tx), Some(fm)) = (active_tx_opt, file_mgr_opt) {
                            let dest_path_clone = dest_path.clone();
                            tokio::spawn(async move {
                                match fm.send_file(&dest_path_clone, peer_tx).await {
                                    Ok(id) => {
                                        info!("Dropped file {:?} dispatched to peer phone (Transfer ID: {})", dest_path_clone, id);
                                    }
                                    Err(e) => {
                                        warn!("Failed to dispatch dropped file {:?}: {}", dest_path_clone, e);
                                    }
                                }
                            });
                            (true, "Transfer queued to peer phone".to_string())
                        } else {
                            (false, "No active mobile peer connected to receive file".to_string())
                        };

                        let json = serde_json::json!({
                            "ok": transfer_queued,
                            "filename": clean_filename,
                            "bytes_received": bytes_written,
                            "status": status_msg,
                        });
                        let body = json.to_string();
                        let header = format!(
                            "HTTP/1.1 200 OK\r\n\
                             Content-Type: application/json\r\n\
                             Content-Length: {}\r\n\
                             Access-Control-Allow-Origin: *\r\n\
                             Connection: close\r\n\r\n",
                            body.len()
                        );
                        let _ = stream.write_all(header.as_bytes()).await;
                        let _ = stream.write_all(body.as_bytes()).await;
                    } else if path.starts_with("/phone/input") {
                        // Extract parameters from JSON body or query string
                        let req_body = req_str.split_once("\r\n\r\n")
                            .map(|(_, b)| b)
                            .or_else(|| req_str.split_once("\n\n").map(|(_, b)| b))
                            .unwrap_or("");

                        let mut event_type = String::new();
                        let mut x: f32 = 0.0;
                        let mut y: f32 = 0.0;
                        let mut end_x: f32 = 0.0;
                        let mut end_y: f32 = 0.0;
                        let mut duration_ms: u32 = 200;
                        let mut scroll_dx: f32 = 0.0;
                        let mut scroll_dy: f32 = 0.0;
                        let mut nav_action = String::new();
                        let mut text_str = String::new();
                        let mut press_enter = false;

                        if let Ok(json) = serde_json::from_str::<serde_json::Value>(req_body.trim()) {
                            if let Some(t) = json.get("type").and_then(|v| v.as_str()) { event_type = t.to_string(); }
                            if let Some(v) = json.get("x").and_then(|v| v.as_f64()) { x = v as f32; }
                            if let Some(v) = json.get("y").and_then(|v| v.as_f64()) { y = v as f32; }
                            if let Some(v) = json.get("end_x").and_then(|v| v.as_f64()) { end_x = v as f32; }
                            if let Some(v) = json.get("end_y").and_then(|v| v.as_f64()) { end_y = v as f32; }
                            if let Some(v) = json.get("duration").and_then(|v| v.as_u64()) { duration_ms = v as u32; }
                            if let Some(v) = json.get("scroll_dx").and_then(|v| v.as_f64()) { scroll_dx = v as f32; }
                            if let Some(v) = json.get("scroll_dy").and_then(|v| v.as_f64()) { scroll_dy = v as f32; }
                            if let Some(v) = json.get("action").and_then(|v| v.as_str()) { nav_action = v.to_string(); }
                            if let Some(v) = json.get("text").and_then(|v| v.as_str()) { text_str = v.to_string(); }
                            if let Some(v) = json.get("enter").and_then(|v| v.as_bool()) { press_enter = v; }
                        }

                        // Query string fallback
                        for part in query_string.split('&') {
                            if let Some((k, v)) = part.split_once('=') {
                                let decoded = url_decode(v);
                                match k {
                                    "type" => event_type = decoded.clone(),
                                    "x" => x = decoded.parse().unwrap_or(x),
                                    "y" => y = decoded.parse().unwrap_or(y),
                                    "end_x" => end_x = decoded.parse().unwrap_or(end_x),
                                    "end_y" => end_y = decoded.parse().unwrap_or(end_y),
                                    "duration" => duration_ms = decoded.parse().unwrap_or(duration_ms),
                                    "scroll_dx" => scroll_dx = decoded.parse().unwrap_or(scroll_dx),
                                    "scroll_dy" => scroll_dy = decoded.parse().unwrap_or(scroll_dy),
                                    "action" => nav_action = decoded.clone(),
                                    "text" => text_str = decoded.clone(),
                                    "enter" => press_enter = decoded == "true" || decoded == "1",
                                    _ => {}
                                }
                            }
                        }

                        if (event_type == "touch" || event_type.is_empty()) && !nav_action.is_empty() {
                            event_type = nav_action.clone();
                        }

                        let mut dispatched = false;
                        match event_type.as_str() {
                            "tap" => {
                                dispatched = manager.send_phone_touch(PhoneTouchEvent {
                                    r#type: PhoneTouchTypeEnum::TouchTap as i32,
                                    normalized_x: x,
                                    normalized_y: y,
                                    end_x: x,
                                    end_y: y,
                                    duration_ms: 50,
                                    scroll_dx: 0.0,
                                    scroll_dy: 0.0,
                                }).await;
                            }
                            "swipe" => {
                                dispatched = manager.send_phone_touch(PhoneTouchEvent {
                                    r#type: PhoneTouchTypeEnum::TouchSwipe as i32,
                                    normalized_x: x,
                                    normalized_y: y,
                                    end_x,
                                    end_y,
                                    duration_ms,
                                    scroll_dx: 0.0,
                                    scroll_dy: 0.0,
                                }).await;
                            }
                            "scroll" => {
                                dispatched = manager.send_phone_touch(PhoneTouchEvent {
                                    r#type: PhoneTouchTypeEnum::TouchScroll as i32,
                                    normalized_x: x,
                                    normalized_y: y,
                                    end_x: x,
                                    end_y: y,
                                    duration_ms: 100,
                                    scroll_dx,
                                    scroll_dy,
                                }).await;
                            }
                            "nav" => {
                                let action_enum = match nav_action.to_lowercase().as_str() {
                                    "back" => PhoneNavActionEnum::Back,
                                    "home" => PhoneNavActionEnum::Home,
                                    "recents" => PhoneNavActionEnum::Recents,
                                    "notifications" => PhoneNavActionEnum::Notifications,
                                    "lock" => PhoneNavActionEnum::Lock,
                                    "backspace" => PhoneNavActionEnum::Backspace,
                                    _ => PhoneNavActionEnum::Unknown,
                                };
                                if action_enum != PhoneNavActionEnum::Unknown {
                                    dispatched = manager.send_phone_nav(action_enum).await;
                                }
                            }
                            "text" if !text_str.is_empty() => {
                                dispatched = manager.send_phone_text(text_str, press_enter).await;
                            }
                            _ => {}
                        }

                        let json = serde_json::json!({ "ok": dispatched, "type": event_type });
                        let body = json.to_string();
                        let header = format!(
                            "HTTP/1.1 200 OK\r\n\
                             Content-Type: application/json\r\n\
                             Content-Length: {}\r\n\
                             Access-Control-Allow-Origin: *\r\n\
                             Connection: close\r\n\r\n",
                            body.len()
                        );
                        let _ = stream.write_all(header.as_bytes()).await;
                        let _ = stream.write_all(body.as_bytes()).await;
                    } else if path == "/phone" || path == "/phone/" {
                        // Interactive HTML5 Phone Mirror Viewer
                        let html = r#"<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>Continuity Hub — Phone Mirror</title>
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <style>
        :root {
            --bg-color: #0b0f19;
            --card-bg: #151c2c;
            --accent-color: #3b82f6;
            --accent-hover: #2563eb;
            --border-color: #243048;
            --text-color: #f1f5f9;
            --text-muted: #94a3b8;
            --phone-bezel: #1e293b;
        }
        * { box-sizing: border-box; margin: 0; padding: 0; user-select: none; }
        body {
            background: var(--bg-color);
            color: var(--text-color);
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
            min-height: 100vh;
            display: flex;
            flex-direction: column;
            align-items: center;
            justify-content: center;
            padding: 12px;
            overflow: hidden;
        }
        .header {
            display: flex;
            align-items: center;
            justify-content: space-between;
            width: 100%;
            max-width: 380px;
            margin-bottom: 8px;
        }
        .brand {
            display: flex;
            align-items: center;
            gap: 8px;
            font-weight: 700;
            font-size: 16px;
        }
        .badge {
            font-size: 11px;
            font-weight: 700;
            padding: 2px 8px;
            border-radius: 9999px;
            background: #22c55e22;
            color: #4ade80;
            border: 1px solid #22c55e44;
            display: inline-flex;
            align-items: center;
            gap: 5px;
        }
        .badge.offline {
            background: #ef444422;
            color: #f87171;
            border-color: #ef444444;
        }
        .badge-dot {
            width: 6px;
            height: 6px;
            border-radius: 50%;
            background: currentColor;
        }
        .phone-wrapper {
            position: relative;
            width: 100%;
            max-width: 360px;
            height: 700px;
            max-height: calc(100vh - 120px);
            background: #000;
            border-radius: 38px;
            border: 8px solid var(--phone-bezel);
            box-shadow: 0 20px 50px -10px rgba(0, 0, 0, 0.8), 0 0 0 1px #334155;
            overflow: hidden;
            display: flex;
            flex-direction: column;
        }
        .phone-notch {
            position: absolute;
            top: 6px;
            left: 50%;
            transform: translateX(-50%);
            width: 70px;
            height: 14px;
            background: #000;
            border-radius: 10px;
            z-index: 10;
        }
        .screen-container {
            position: relative;
            flex: 1;
            width: 100%;
            height: 100%;
            background: #0f172a;
            display: flex;
            align-items: center;
            justify-content: center;
            cursor: pointer;
            overflow: hidden;
        }
        #phoneImage {
            width: 100%;
            height: 100%;
            object-fit: contain;
            display: block;
            pointer-events: auto;
            -webkit-user-drag: none;
        }
        .screen-overlay {
            position: absolute;
            inset: 0;
            background: rgba(15, 23, 42, 0.88);
            display: flex;
            flex-direction: column;
            align-items: center;
            justify-content: center;
            padding: 20px;
            text-align: center;
            gap: 14px;
            z-index: 5;
            transition: opacity 0.2s;
        }
        .screen-overlay.hidden { display: none; }
        .drop-overlay {
            position: absolute;
            inset: 0;
            background: rgba(11, 15, 25, 0.92);
            border: 3px dashed var(--accent-color);
            border-radius: 28px;
            display: flex;
            flex-direction: column;
            align-items: center;
            justify-content: center;
            padding: 24px;
            text-align: center;
            z-index: 20;
            backdrop-filter: blur(8px);
            animation: pulse-border 1.5s infinite;
        }
        .drop-overlay.hidden { display: none; }
        @keyframes pulse-border {
            0%, 100% { border-color: #3b82f6; box-shadow: inset 0 0 30px rgba(59, 130, 246, 0.3); }
            50% { border-color: #60a5fa; box-shadow: inset 0 0 50px rgba(96, 165, 250, 0.5); }
        }
        .toast {
            position: absolute;
            top: 24px;
            left: 50%;
            transform: translateX(-50%);
            background: #1e293b;
            color: #f8fafc;
            font-size: 12px;
            font-weight: 600;
            padding: 8px 16px;
            border-radius: 9999px;
            border: 1px solid #334155;
            box-shadow: 0 10px 25px rgba(0,0,0,0.5);
            z-index: 30;
            display: flex;
            align-items: center;
            gap: 6px;
            pointer-events: none;
            transition: all 0.25s cubic-bezier(0.16, 1, 0.3, 1);
        }
        .toast.hidden {
            opacity: 0;
            transform: translate(-50%, -15px);
        }
        .toast.success { border-color: #22c55e; color: #4ade80; }
        .toast.error { border-color: #ef4444; color: #f87171; }
        .toast.info { border-color: #3b82f6; color: #60a5fa; }
        .nav-bar {
            height: 48px;
            background: #0a0f1d;
            border-top: 1px solid #1e293b;
            display: flex;
            align-items: center;
            justify-content: space-around;
            padding: 0 12px;
            z-index: 10;
        }
        .nav-btn {
            background: transparent;
            border: none;
            color: var(--text-muted);
            cursor: pointer;
            padding: 8px 12px;
            border-radius: 8px;
            transition: all 0.15s;
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 15px;
        }
        .nav-btn:hover {
            color: var(--text-color);
            background: rgba(255, 255, 255, 0.08);
        }
        .nav-btn:active {
            transform: scale(0.92);
        }
        .control-bar {
            width: 100%;
            max-width: 380px;
            margin-top: 10px;
            display: flex;
            flex-direction: column;
            gap: 8px;
        }
        .actions-row {
            display: flex;
            gap: 8px;
            justify-content: space-between;
        }
        .btn {
            flex: 1;
            padding: 8px 10px;
            font-size: 12px;
            font-weight: 600;
            border-radius: 10px;
            border: 1px solid var(--border-color);
            background: var(--card-bg);
            color: var(--text-color);
            cursor: pointer;
            transition: background 0.15s;
            text-align: center;
        }
        .btn:hover { background: #1e293b; }
        .btn.primary {
            background: var(--accent-color);
            border-color: var(--accent-color);
            color: white;
        }
        .btn.primary:hover { background: var(--accent-hover); }
        .btn.active {
            background: #f59e0b22;
            border-color: #f59e0b66;
            color: #fbbf24;
        }
        .shortcuts-hint {
            font-size: 11px;
            color: var(--text-muted);
            text-align: center;
            margin-top: 2px;
        }
    </style>
</head>
<body>
    <div class="header">
        <div class="brand">
            <span>📱 Phone Mirror</span>
            <span id="statusBadge" class="badge"><span class="badge-dot"></span> <span id="statusText">Ready</span></span>
        </div>
        <div style="font-size: 11px; color: var(--text-muted);" id="fpsCounter">30 FPS</div>
    </div>

    <div class="phone-wrapper">
        <div class="phone-notch"></div>
        <div class="screen-container" id="screenContainer">
            <div id="dropOverlay" class="drop-overlay hidden">
                <div style="font-size: 38px;">📲</div>
                <div style="font-weight: 700; font-size: 16px; margin-top: 8px;">Drop File into Phone</div>
                <div id="dropFileName" style="font-size: 12px; color: #94a3b8; margin-top: 4px;">
                    Send Photos, APKs, Videos, and Files instantly
                </div>
            </div>
            <div id="toast" class="toast hidden"></div>
            <div id="overlay" class="screen-overlay">
                <div style="font-size: 36px;">📱</div>
                <div style="font-weight: 600; font-size: 15px;">Wireless Phone Mirroring</div>
                <div style="font-size: 12px; color: var(--text-muted); max-width: 250px;">
                    Native pure Wayland control. Drag files here or type directly to control your phone.
                </div>
                <button class="btn primary" id="btnStartOverlay" style="padding: 10px 20px; font-size: 13px;">
                    Start Mirroring
                </button>
            </div>
            <img id="phoneImage" src="/phone/stream.mjpg" alt="Phone Screen" draggable="false" />
        </div>
        <div class="nav-bar">
            <button class="nav-btn" id="btnBack" title="Back (Esc)">◀</button>
            <button class="nav-btn" id="btnHome" title="Home (Super/Home)">⚪</button>
            <button class="nav-btn" id="btnRecents" title="Recents (Alt+Tab)">◽</button>
            <button class="nav-btn" id="btnNotif" title="Notifications">🔔</button>
            <button class="nav-btn" id="btnLock" title="Lock Screen">🔒</button>
        </div>
    </div>

    <div class="control-bar">
        <div class="actions-row">
            <button class="btn primary" id="btnToggleMirror">▶ Start Mirroring</button>
            <button class="btn" id="btnPrivacy">🔒 Privacy Screen: OFF</button>
            <button class="btn" id="btnRefreshStream">🔄</button>
        </div>
        <div class="shortcuts-hint">
            ⌨️ Esc=Back • Super=Home • Alt+Tab=Recents • Ctrl+V=Paste • Drop files to transfer
        </div>
    </div>

    <script>
        const phoneImg = document.getElementById('phoneImage');
        const overlay = document.getElementById('overlay');
        const dropOverlay = document.getElementById('dropOverlay');
        const toast = document.getElementById('toast');
        const statusBadge = document.getElementById('statusBadge');
        const statusText = document.getElementById('statusText');
        const fpsCounter = document.getElementById('fpsCounter');
        const btnToggleMirror = document.getElementById('btnToggleMirror');
        const btnStartOverlay = document.getElementById('btnStartOverlay');
        const btnPrivacy = document.getElementById('btnPrivacy');
        const btnRefreshStream = document.getElementById('btnRefreshStream');

        let isMirroring = false;
        let isPrivacyActive = false;
        let toastTimer = null;

        function showToast(msg, type = 'info') {
            toast.textContent = msg;
            toast.className = 'toast ' + type;
            clearTimeout(toastTimer);
            toastTimer = setTimeout(() => {
                toast.className = 'toast hidden';
            }, 3000);
        }

        async function sendInput(params) {
            try {
                await fetch('/phone/input', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(params)
                });
            } catch (e) {
                console.error("Input send error:", e);
            }
        }

        async function startMirror() {
            try {
                const res = await fetch('/phone/start');
                const data = await res.json();
                if (data.ok) {
                    isMirroring = true;
                    overlay.classList.add('hidden');
                    btnToggleMirror.textContent = '⏹ Stop';
                    statusText.textContent = 'Mirroring';
                    statusBadge.classList.remove('offline');
                    phoneImg.src = '/phone/stream.mjpg?t=' + Date.now();
                }
            } catch (e) {
                console.error("Start mirror error:", e);
            }
        }

        async function stopMirror() {
            try {
                await fetch('/phone/stop');
                isMirroring = false;
                overlay.classList.remove('hidden');
                btnToggleMirror.textContent = '▶ Start Mirroring';
                statusText.textContent = 'Stopped';
                if (isPrivacyActive) {
                    isPrivacyActive = false;
                    btnPrivacy.textContent = '🔒 Privacy Screen: OFF';
                    btnPrivacy.classList.remove('active');
                }
            } catch (e) {
                console.error("Stop mirror error:", e);
            }
        }

        async function togglePrivacy() {
            isPrivacyActive = !isPrivacyActive;
            btnPrivacy.textContent = isPrivacyActive ? '🔒 Privacy Screen: ON' : '🔒 Privacy Screen: OFF';
            btnPrivacy.classList.toggle('active', isPrivacyActive);
            showToast(isPrivacyActive ? 'Phone display dimmed (Privacy Screen active)' : 'Phone display restored', 'info');
            try {
                await fetch('/phone/privacy?enabled=' + isPrivacyActive, { method: 'POST' });
            } catch (e) {
                console.error("Privacy toggle error:", e);
            }
        }

        btnToggleMirror.onclick = () => isMirroring ? stopMirror() : startMirror();
        btnStartOverlay.onclick = startMirror;
        btnPrivacy.onclick = togglePrivacy;
        btnRefreshStream.onclick = () => {
            phoneImg.src = '/phone/stream.mjpg?t=' + Date.now();
        };

        // Navigation Actions
        document.getElementById('btnBack').onclick = () => sendInput({ type: 'nav', action: 'back' });
        document.getElementById('btnHome').onclick = () => sendInput({ type: 'nav', action: 'home' });
        document.getElementById('btnRecents').onclick = () => sendInput({ type: 'nav', action: 'recents' });
        document.getElementById('btnNotif').onclick = () => sendInput({ type: 'nav', action: 'notifications' });
        document.getElementById('btnLock').onclick = () => sendInput({ type: 'nav', action: 'lock' });

        // Touch & Mouse Gesture Dispatch
        let isPointerDown = false;
        let startX = 0, startY = 0, startTime = 0;

        function getNormalizedCoords(e) {
            const rect = phoneImg.getBoundingClientRect();
            const x = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
            const y = Math.max(0, Math.min(1, (e.clientY - rect.top) / rect.height));
            return { x, y };
        }

        phoneImg.addEventListener('mousedown', (e) => {
            e.preventDefault();
            isPointerDown = true;
            const coords = getNormalizedCoords(e);
            startX = coords.x;
            startY = coords.y;
            startTime = Date.now();
        });

        window.addEventListener('mouseup', (e) => {
            if (!isPointerDown) return;
            isPointerDown = false;

            const coords = getNormalizedCoords(e);
            const endX = coords.x;
            const endY = coords.y;
            const duration = Math.max(50, Date.now() - startTime);

            const distSq = (endX - startX) * (endX - startX) + (endY - startY) * (endY - startY);
            if (distSq < 0.0004) {
                sendInput({ type: 'tap', x: startX, y: startY });
            } else {
                sendInput({
                    type: 'swipe',
                    x: startX,
                    y: startY,
                    end_x: endX,
                    end_y: endY,
                    duration: Math.min(500, duration)
                });
            }
        });

        phoneImg.addEventListener('wheel', (e) => {
            e.preventDefault();
            const coords = getNormalizedCoords(e);
            const scrollDy = e.deltaY > 0 ? 100 : -100;
            sendInput({
                type: 'scroll',
                x: coords.x,
                y: coords.y,
                scroll_dy: scrollDy
            });
        }, { passive: false });

        // Native Desktop Keyboard Navigation
        window.addEventListener('keydown', async (e) => {
            // 1. Esc -> Back
            if (e.key === 'Escape') {
                e.preventDefault();
                sendInput({ type: 'nav', action: 'back' });
                return;
            }

            // 2. Super / Meta or Home -> Home
            if (e.key === 'Meta' || e.key === 'Home') {
                e.preventDefault();
                sendInput({ type: 'nav', action: 'home' });
                return;
            }

            // 3. Alt+Tab or F1/F2 or Tab -> Recent Apps
            if ((e.altKey && e.key === 'Tab') || e.key === 'F1' || e.key === 'F2') {
                e.preventDefault();
                sendInput({ type: 'nav', action: 'recents' });
                return;
            }

            // 4. Ctrl+V / Cmd+V -> Direct Clipboard Paste to focused phone field
            if ((e.ctrlKey || e.metaKey) && (e.key === 'v' || e.key === 'V')) {
                e.preventDefault();
                try {
                    const text = await navigator.clipboard.readText();
                    if (text) {
                        sendInput({ type: 'text', text: text, enter: false });
                        showToast('Pasted to phone', 'info');
                    }
                } catch (_) {}
                return;
            }

            // 5. Backspace -> Backspace
            if (e.key === 'Backspace') {
                e.preventDefault();
                sendInput({ type: 'nav', action: 'backspace' });
                return;
            }

            // 6. Enter -> Enter
            if (e.key === 'Enter') {
                e.preventDefault();
                sendInput({ type: 'text', text: '', enter: true });
                return;
            }

            // 7. Direct Character Typing
            if (e.key.length === 1 && !e.ctrlKey && !e.metaKey && !e.altKey) {
                e.preventDefault();
                sendInput({ type: 'text', text: e.key, enter: false });
                return;
            }
        });

        // Desktop Drag-and-Drop into Phone
        ['dragenter', 'dragover'].forEach(name => {
            window.addEventListener(name, (e) => {
                e.preventDefault();
                e.dataTransfer.dropEffect = 'copy';
                dropOverlay.classList.remove('hidden');
            });
        });

        ['dragleave', 'dragend'].forEach(name => {
            window.addEventListener(name, (e) => {
                if (e.relatedTarget === null || e.clientX === 0 || e.clientY === 0) {
                    dropOverlay.classList.add('hidden');
                }
            });
        });

        window.addEventListener('drop', async (e) => {
            e.preventDefault();
            dropOverlay.classList.add('hidden');
            const files = e.dataTransfer.files;
            if (!files || files.length === 0) return;

            for (const file of files) {
                showToast(`Sending ${file.name} to phone...`, 'info');
                try {
                    const res = await fetch('/phone/drop?filename=' + encodeURIComponent(file.name), {
                        method: 'POST',
                        body: file
                    });
                    const data = await res.json();
                    if (data.ok) {
                        showToast(`✓ Sent ${file.name} to phone!`, 'success');
                    } else {
                        showToast(`Failed: ${data.transfer_id_or_err || 'Transfer error'}`, 'error');
                    }
                } catch (err) {
                    showToast(`Upload failed: ${err.message}`, 'error');
                }
            }
        });

        // Periodic status polling
        setInterval(async () => {
            try {
                const res = await fetch('/phone/status');
                const data = await res.json();
                if (data.is_mirroring) {
                    overlay.classList.add('hidden');
                    btnToggleMirror.textContent = '⏹ Stop';
                    statusText.textContent = 'Live';
                    statusBadge.classList.remove('offline');
                    fpsCounter.textContent = `${data.fps} FPS (${data.width}x${data.height})`;
                    isMirroring = true;
                } else if (isMirroring) {
                    fpsCounter.textContent = '0 FPS';
                }
            } catch (_) {}
        }, 1500);
    </script>
</body>
</html>"#;
                        let header = format!(
                            "HTTP/1.1 200 OK\r\n\
                             Content-Type: text/html; charset=utf-8\r\n\
                             Content-Length: {}\r\n\
                             Connection: close\r\n\r\n",
                            html.len()
                        );
                        let _ = stream.write_all(header.as_bytes()).await;
                        let _ = stream.write_all(html.as_bytes()).await;
                    } else if path.starts_with("/snapshot.jpg") {
                        let frame_opt = manager.latest_frame.read().await.clone();
                        if let Some(frame) = frame_opt {
                            let header = format!(
                                "HTTP/1.1 200 OK\r\n\
                                 Content-Type: image/jpeg\r\n\
                                 Content-Length: {}\r\n\
                                 Access-Control-Allow-Origin: *\r\n\
                                 Connection: close\r\n\r\n",
                                frame.frame_data.len()
                            );
                            let _ = stream.write_all(header.as_bytes()).await;
                            let _ = stream.write_all(&frame.frame_data).await;
                        } else {
                            let not_found = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n";
                            let _ = stream.write_all(not_found.as_bytes()).await;
                        }
                    } else if path.starts_with("/status") {
                        let status = manager.get_status().await;
                        let json = serde_json::json!({
                            "is_streaming": status.is_streaming,
                            "width": status.width,
                            "height": status.height,
                            "lens": status.lens,
                            "fps": status.fps,
                            "torch": status.torch,
                            "zoom": status.zoom,
                            "rotation": status.rotation,
                            "mjpeg_url": format!("http://127.0.0.1:{}/live.mjpg", manager.http_port),
                            "snapshot_url": format!("http://127.0.0.1:{}/snapshot.jpg", manager.http_port),
                        });
                        let body = json.to_string();
                        let header = format!(
                            "HTTP/1.1 200 OK\r\n\
                             Content-Type: application/json\r\n\
                             Content-Length: {}\r\n\
                             Access-Control-Allow-Origin: *\r\n\
                             Connection: close\r\n\r\n",
                            body.len()
                        );
                        let _ = stream.write_all(header.as_bytes()).await;
                        let _ = stream.write_all(body.as_bytes()).await;
                    } else {
                        // Welcome / viewer HTML page
                        let html = format!(
                            r#"<!DOCTYPE html>
<html>
<head>
    <title>Continuity Camera</title>
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <style>
        body {{ background: #0f172a; color: #f8fafc; font-family: system-ui, sans-serif; margin: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; min-height: 100vh; }}
        h1 {{ margin-bottom: 8px; font-weight: 700; }}
        .badge {{ background: #22c55e; color: black; font-size: 12px; font-weight: bold; padding: 4px 8px; border-radius: 9999px; }}
        .container {{ display: flex; flex-direction: column; align-items: center; background: #1e293b; padding: 24px; border-radius: 16px; box-shadow: 0 10px 25px rgba(0,0,0,0.5); max-width: 90vw; }}
        img {{ border-radius: 12px; max-width: 100%; height: auto; border: 1px solid #334155; }}
        .endpoints {{ margin-top: 16px; font-family: monospace; font-size: 13px; color: #94a3b8; }}
        a {{ color: #38bdf8; text-decoration: none; font-weight: 600; }}
        a:hover {{ text-decoration: underline; }}
    </style>
</head>
<body>
    <div class="container">
        <h1>📹 Continuity Camera <span class="badge">LIVE</span></h1>
        <p style="color: #94a3b8; margin-top: 0;">Wireless Virtual Webcam Stream for Linux Hyprland & OBS</p>
        <img src="/live.mjpg" alt="Continuity Camera Stream">
        <div class="endpoints">
            <p>MJPEG Stream URL: <code>http://127.0.0.1:{}/live.mjpg</code></p>
            <p>Single Snapshot URL: <code>http://127.0.0.1:{}/snapshot.jpg</code></p>
            <p style="margin-top: 12px;">📱 Looking for Phone Mirroring? <a href="/phone">Open Interactive Phone Mirror</a></p>
        </div>
    </div>
</body>
</html>"#,
                            manager.http_port, manager.http_port
                        );
                        let header = format!(
                            "HTTP/1.1 200 OK\r\n\
                             Content-Type: text/html; charset=utf-8\r\n\
                             Content-Length: {}\r\n\
                             Connection: close\r\n\r\n",
                            html.len()
                        );
                        let _ = stream.write_all(header.as_bytes()).await;
                        let _ = stream.write_all(html.as_bytes()).await;
                    }
                });
            }
        });

        Ok(())
    }

    pub fn sidecar_manager(&self) -> Arc<SidecarManager> {
        Arc::clone(&self.sidecar_mgr)
    }

    pub async fn set_peer_tx(&self, tx: mpsc::Sender<Packet>) {
        *self.active_peer_tx.write().await = Some(tx);
    }

    pub async fn get_status(&self) -> CameraStatus {
        CameraStatus {
            is_streaming: self.is_streaming.load(Ordering::Relaxed),
            width: *self.current_width.read().await,
            height: *self.current_height.read().await,
            lens: self.current_lens.read().await.clone(),
            fps: self.current_fps.load(Ordering::Relaxed),
            torch: false,
            zoom: 1.0,
            rotation: self.current_rotation.load(Ordering::Relaxed),
        }
    }

    pub async fn handle_message(
        &self,
        msg: CameraMessage,
        peer_name: &str,
        peer_tx: mpsc::Sender<Packet>,
    ) -> Option<Packet> {
        self.set_peer_tx(peer_tx).await;

        let inner = msg.msg?;
        match inner {
            Msg::Frame(frame) => {
                self.handle_camera_frame(frame, peer_name).await;
                None
            }
            Msg::Config(config) => {
                self.handle_camera_config(config, peer_name).await;
                None
            }
            Msg::Snapshot(snapshot) => {
                self.handle_camera_snapshot(snapshot, peer_name).await;
                None
            }
            Msg::Control(control) => {
                self.handle_camera_control(control).await
            }
            Msg::DesktopScreen(_) => None,
        }
    }

    async fn handle_camera_frame(&self, frame: CameraFrame, _peer_name: &str) {
        let frame_bytes = bytes::Bytes::from(frame.frame_data.clone());

        if frame.is_phone_screen {
            self.is_phone_mirror_streaming.store(true, Ordering::Relaxed);
            self.phone_fps_counter.fetch_add(1, Ordering::Relaxed);

            *self.phone_width.write().await = frame.width;
            *self.phone_height.write().await = frame.height;

            *self.latest_phone_frame.write().await = Some(frame);

            // Zero-copy broadcast to Phone Mirror HTTP stream clients
            let _ = self.phone_frame_tx.send(frame_bytes);
            return;
        }

        self.is_streaming.store(true, Ordering::Relaxed);
        self.fps_counter.fetch_add(1, Ordering::Relaxed);

        *self.current_width.write().await = frame.width;
        *self.current_height.write().await = frame.height;

        let frame_data = frame.frame_data.clone();
        *self.latest_frame.write().await = Some(frame);

        // 1. Zero-copy broadcast to HTTP MJPEG clients
        let _ = self.frame_tx.send(frame_bytes);

        // 2. Feed to Linux V4L2 loopback device (/dev/video10) if available
        let mut v4l2 = self.v4l2_writer.lock().await;
        v4l2.write_frame(&frame_data, *self.current_width.read().await, *self.current_height.read().await);
    }

    async fn handle_camera_config(&self, config: CameraConfig, peer_name: &str) {
        let lens_str = match config.lens() {
            Lens::Back => "Back",
            Lens::Front => "Front",
            Lens::Wide => "Ultra-Wide",
        };
        info!(
            "Received Camera Config from {}: {}x{} @ {}fps, lens: {}, torch: {}",
            peer_name, config.width, config.height, config.target_fps, lens_str, config.torch_enabled
        );

        *self.current_width.write().await = config.width;
        *self.current_height.write().await = config.height;
        *self.current_lens.write().await = lens_str.to_string();
        self.current_rotation.store(config.rotation_degrees, Ordering::Relaxed);
    }

    async fn handle_camera_snapshot(&self, snapshot: CameraSnapshot, peer_name: &str) {
        info!(
            "Received high-res Camera Snapshot from {} ({} bytes, {}x{})",
            peer_name,
            snapshot.image_bytes.len(),
            snapshot.width,
            snapshot.height
        );
        if let Err(e) = tokio::fs::write(&self.snapshot_path, &snapshot.image_bytes).await {
            warn!("Failed to save camera snapshot to {:?}: {}", self.snapshot_path, e);
        } else {
            info!("Saved camera snapshot to {:?}", self.snapshot_path);
        }
    }

    async fn handle_camera_control(&self, control: CameraControlCommand) -> Option<Packet> {
        let action = control.action();
        debug!("Received camera control command: {:?}", action);

        match action {
            CameraAction::RequestDesktopScreen => {
                // Capture desktop output via grim and return DesktopScreenFrame
                if let Some(jpeg_bytes) = self.capture_desktop_screen().await {
                    let screen_frame = DesktopScreenFrame {
                        timestamp_ms: chrono::Utc::now().timestamp_millis() as u64,
                        width: 1920,
                        height: 1080,
                        image_bytes: jpeg_bytes,
                        is_sidecar: false,
                        monitor_name: "Primary".to_string(),
                    };

                    let reply_msg = CameraMessage {
                        msg: Some(Msg::DesktopScreen(screen_frame)),
                    };

                    let mut bytes = Vec::new();
                    reply_msg.encode(&mut bytes).ok()?;

                    return Some(Packet {
                        session_id: self.my_device_id.clone(),
                        sequence_number: 1,
                        channel: Channel::Camera as i32,
                        payload: bytes,
                    });
                }
            }
            CameraAction::StartDesktopStream => {
                info!("Starting continuous Hyprland desktop screencast stream...");
                self.is_desktop_streaming.store(true, Ordering::Relaxed);
                let is_streaming = Arc::clone(&self.is_desktop_streaming);
                let peer_tx_arc = Arc::clone(&self.active_peer_tx);
                let my_device_id = self.my_device_id.clone();

                tokio::spawn(async move {
                    // ~15 FPS stream interval (66ms) with low overhead
                    let mut interval = tokio::time::interval(tokio::time::Duration::from_millis(66));
                    interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);

                    while is_streaming.load(Ordering::Relaxed) {
                        interval.tick().await;

                        #[cfg(target_os = "linux")]
                        {
                            let output = tokio::process::Command::new("grim")
                                .args(["-t", "jpeg", "-q", "40", "-"])
                                .output()
                                .await;

                            if let Ok(out) = output {
                                if out.status.success() && !out.stdout.is_empty() {
                                    let screen_frame = DesktopScreenFrame {
                                        timestamp_ms: chrono::Utc::now().timestamp_millis() as u64,
                                        width: 1920,
                                        height: 1080,
                                        image_bytes: out.stdout,
                                        is_sidecar: false,
                                        monitor_name: "Primary".to_string(),
                                    };

                                    let reply_msg = CameraMessage {
                                        msg: Some(Msg::DesktopScreen(screen_frame)),
                                    };

                                    let mut bytes = Vec::new();
                                    if reply_msg.encode(&mut bytes).is_ok() {
                                        let packet = Packet {
                                            session_id: my_device_id.clone(),
                                            sequence_number: 1,
                                            channel: Channel::Camera as i32,
                                            payload: bytes,
                                        };

                                        let guard = peer_tx_arc.read().await;
                                        if let Some(ref tx) = *guard {
                                            match tx.try_send(packet) {
                                                Ok(()) => {}
                                                Err(tokio::sync::mpsc::error::TrySendError::Full(_)) => {
                                                    debug!("Dropping desktop screen frame due to transport backpressure");
                                                }
                                                Err(tokio::sync::mpsc::error::TrySendError::Closed(_)) => {
                                                    break;
                                                }
                                            }
                                        } else {
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    }
                    info!("Hyprland desktop screencast stream finished");
                });
            }
            CameraAction::StopDesktopStream => {
                info!("Stopping continuous Hyprland desktop screencast stream");
                self.is_desktop_streaming.store(false, Ordering::Relaxed);
            }
            CameraAction::StartSidecar => {
                info!("Starting Hyprland Sidecar virtual second monitor stream...");
                let sidecar_mgr = Arc::clone(&self.sidecar_mgr);
                let is_streaming = Arc::clone(&self.is_sidecar_streaming);
                let peer_tx_arc = Arc::clone(&self.active_peer_tx);
                let my_device_id = self.my_device_id.clone();

                match sidecar_mgr.start_sidecar().await {
                    Ok((_x, _y, width, height)) => {
                        is_streaming.store(true, Ordering::SeqCst);
                        tokio::spawn(async move {
                            let mut interval = tokio::time::interval(tokio::time::Duration::from_millis(50));
                            interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);

                            while is_streaming.load(Ordering::Relaxed) {
                                interval.tick().await;

                                #[cfg(target_os = "linux")]
                                {
                                    let output = tokio::process::Command::new("grim")
                                        .args(["-o", "Sidecar", "-s", "1", "-t", "jpeg", "-q", "40", "-"])
                                        .output()
                                        .await;

                                    if let Ok(out) = output {
                                        if out.status.success() && !out.stdout.is_empty() {
                                            let screen_frame = DesktopScreenFrame {
                                                timestamp_ms: chrono::Utc::now().timestamp_millis() as u64,
                                                width,
                                                height,
                                                image_bytes: out.stdout,
                                                is_sidecar: true,
                                                monitor_name: "Sidecar".to_string(),
                                            };

                                            let reply_msg = CameraMessage {
                                                msg: Some(Msg::DesktopScreen(screen_frame)),
                                            };

                                            let mut bytes = Vec::new();
                                            if reply_msg.encode(&mut bytes).is_ok() {
                                                let packet = Packet {
                                                    session_id: my_device_id.clone(),
                                                    sequence_number: 1,
                                                    channel: Channel::Camera as i32,
                                                    payload: bytes,
                                                };

                                                let guard = peer_tx_arc.read().await;
                                                if let Some(ref tx) = *guard {
                                                    match tx.try_send(packet) {
                                                        Ok(()) => {}
                                                        Err(tokio::sync::mpsc::error::TrySendError::Full(_)) => {
                                                            debug!("Dropping sidecar screen frame due to transport backpressure");
                                                        }
                                                        Err(tokio::sync::mpsc::error::TrySendError::Closed(_)) => {
                                                            break;
                                                        }
                                                    }
                                                } else {
                                                    break;
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            info!("Hyprland Sidecar stream finished");
                        });
                    }
                    Err(e) => {
                        warn!("Failed to start Hyprland Sidecar: {}", e);
                    }
                }
            }
            CameraAction::StopSidecar => {
                info!("Stopping Hyprland Sidecar virtual second monitor stream");
                self.is_sidecar_streaming.store(false, Ordering::SeqCst);
                self.sidecar_mgr.stop_sidecar().await;
            }
            CameraAction::SidecarClick => {
                debug!("Received Sidecar touch click event: ({}, {})", control.touch_x, control.touch_y);
                self.sidecar_mgr.handle_touch_click(control.touch_x, control.touch_y).await;
            }
            CameraAction::SetRotation => {
                info!("Updated camera stream rotation to {}°", control.rotation_degrees);
                self.current_rotation.store(control.rotation_degrees, Ordering::Relaxed);
            }
            CameraAction::StartPhoneMirror => {
                info!("Received StartPhoneMirror command");
                self.is_phone_mirror_streaming.store(true, Ordering::Relaxed);
            }
            CameraAction::StopPhoneMirror => {
                info!("Received StopPhoneMirror command");
                self.is_phone_mirror_streaming.store(false, Ordering::Relaxed);
                self.is_phone_privacy_active.store(false, Ordering::Relaxed);
            }
            CameraAction::SetPhonePrivacyMode => {
                info!("Received SetPhonePrivacyMode command: {}", control.privacy_mode);
                self.is_phone_privacy_active.store(control.privacy_mode, Ordering::Relaxed);
            }
            _ => {}
        }

        None
    }

    /// Capture current Hyprland screen via grim and encode as JPEG
    pub async fn capture_desktop_screen(&self) -> Option<Vec<u8>> {
        #[cfg(target_os = "linux")]
        {
            let output = tokio::process::Command::new("grim")
                .args(["-t", "jpeg", "-q", "55", "-"])
                .output()
                .await;

            match output {
                Ok(out) if out.status.success() && !out.stdout.is_empty() => {
                    debug!("Captured Hyprland desktop screen ({} bytes)", out.stdout.len());
                    Some(out.stdout)
                }
                Ok(out) => {
                    warn!("grim command exited with error: {}", String::from_utf8_lossy(&out.stderr));
                    None
                }
                Err(e) => {
                    warn!("Failed to execute grim for desktop capture: {}", e);
                    None
                }
            }
        }
        #[cfg(not(target_os = "linux"))]
        {
            None
        }
    }

    pub fn create_control_packet(&self, action: CameraAction, lens: Lens, torch: bool, zoom_ratio: f32) -> Packet {
        let cmd = CameraControlCommand {
            action: action as i32,
            lens: lens as i32,
            torch,
            zoom_ratio,
            width: 1920,
            height: 1080,
            rotation_degrees: self.current_rotation.load(Ordering::Relaxed),
            touch_x: 0.0,
            touch_y: 0.0,
            privacy_mode: false,
        };

        let msg = CameraMessage {
            msg: Some(Msg::Control(cmd)),
        };

        let mut bytes = Vec::new();
        msg.encode(&mut bytes).unwrap();

        Packet {
            session_id: self.my_device_id.clone(),
            sequence_number: 1,
            channel: Channel::Camera as i32,
            payload: bytes,
        }
    }

    pub async fn send_control(&self, action: CameraAction, lens: Lens, torch: bool, zoom_ratio: f32) {
        let packet = self.create_control_packet(action, lens, torch, zoom_ratio);
        let guard = self.active_peer_tx.read().await;
        if let Some(ref tx) = *guard {
            let _ = tx.send(packet).await;
        }
    }

    pub async fn set_file_transfer_manager(&self, mgr: Arc<crate::transfer::FileTransferManager>) {
        *self.file_transfer_mgr.write().await = Some(mgr);
    }

    pub async fn set_phone_privacy_mode(&self, enabled: bool) -> bool {
        info!("Setting Phone Privacy Mode to: {}", enabled);
        self.is_phone_privacy_active.store(enabled, Ordering::Relaxed);
        let cmd = CameraControlCommand {
            action: CameraAction::SetPhonePrivacyMode as i32,
            lens: Lens::Back as i32,
            torch: false,
            zoom_ratio: 1.0,
            width: 0,
            height: 0,
            rotation_degrees: 0,
            touch_x: 0.0,
            touch_y: 0.0,
            privacy_mode: enabled,
        };
        let msg = CameraMessage {
            msg: Some(Msg::Control(cmd)),
        };
        let mut bytes = Vec::new();
        if msg.encode(&mut bytes).is_err() {
            return false;
        }
        let packet = Packet {
            session_id: self.my_device_id.clone(),
            sequence_number: 1,
            channel: Channel::Camera as i32,
            payload: bytes,
        };
        let guard = self.active_peer_tx.read().await;
        if let Some(ref tx) = *guard {
            tx.send(packet).await.is_ok()
        } else {
            false
        }
    }

    pub async fn start_phone_mirror(&self) -> bool {
        info!("Sending StartPhoneMirror command to Android peer...");
        let privacy = self.is_phone_privacy_active.load(Ordering::Relaxed);
        let cmd = CameraControlCommand {
            action: CameraAction::StartPhoneMirror as i32,
            lens: Lens::Back as i32,
            torch: false,
            zoom_ratio: 1.0,
            width: 720,
            height: 1600,
            rotation_degrees: 0,
            touch_x: 0.0,
            touch_y: 0.0,
            privacy_mode: privacy,
        };
        let msg = CameraMessage {
            msg: Some(Msg::Control(cmd)),
        };
        let mut bytes = Vec::new();
        if msg.encode(&mut bytes).is_err() {
            return false;
        }
        let packet = Packet {
            session_id: self.my_device_id.clone(),
            sequence_number: 1,
            channel: Channel::Camera as i32,
            payload: bytes,
        };
        self.is_phone_mirror_streaming.store(true, Ordering::Relaxed);
        let guard = self.active_peer_tx.read().await;
        if let Some(ref tx) = *guard {
            tx.send(packet).await.is_ok()
        } else {
            false
        }
    }

    pub async fn stop_phone_mirror(&self) -> bool {
        info!("Sending StopPhoneMirror command to Android peer...");
        let cmd = CameraControlCommand {
            action: CameraAction::StopPhoneMirror as i32,
            lens: Lens::Back as i32,
            torch: false,
            zoom_ratio: 1.0,
            width: 0,
            height: 0,
            rotation_degrees: 0,
            touch_x: 0.0,
            touch_y: 0.0,
            privacy_mode: false,
        };
        let msg = CameraMessage {
            msg: Some(Msg::Control(cmd)),
        };
        let mut bytes = Vec::new();
        if msg.encode(&mut bytes).is_err() {
            return false;
        }
        let packet = Packet {
            session_id: self.my_device_id.clone(),
            sequence_number: 1,
            channel: Channel::Camera as i32,
            payload: bytes,
        };
        self.is_phone_mirror_streaming.store(false, Ordering::Relaxed);
        let guard = self.active_peer_tx.read().await;
        if let Some(ref tx) = *guard {
            tx.send(packet).await.is_ok()
        } else {
            false
        }
    }

    pub async fn send_phone_touch(&self, event: PhoneTouchEvent) -> bool {
        let input_msg = InputMessage {
            event: Some(InputEvent::PhoneTouch(event)),
        };
        let mut bytes = Vec::new();
        if input_msg.encode(&mut bytes).is_err() {
            return false;
        }
        let packet = Packet {
            session_id: self.my_device_id.clone(),
            sequence_number: 1,
            channel: Channel::Input as i32,
            payload: bytes,
        };
        let guard = self.active_peer_tx.read().await;
        if let Some(ref tx) = *guard {
            tx.send(packet).await.is_ok()
        } else {
            false
        }
    }

    pub async fn send_phone_nav(&self, action: PhoneNavActionEnum) -> bool {
        let input_msg = InputMessage {
            event: Some(InputEvent::PhoneNav(PhoneNavAction {
                action: action as i32,
            })),
        };
        let mut bytes = Vec::new();
        if input_msg.encode(&mut bytes).is_err() {
            return false;
        }
        let packet = Packet {
            session_id: self.my_device_id.clone(),
            sequence_number: 1,
            channel: Channel::Input as i32,
            payload: bytes,
        };
        let guard = self.active_peer_tx.read().await;
        if let Some(ref tx) = *guard {
            tx.send(packet).await.is_ok()
        } else {
            false
        }
    }

    pub async fn send_phone_text(&self, text: String, press_enter: bool) -> bool {
        let input_msg = InputMessage {
            event: Some(InputEvent::PhoneText(PhoneTextAction {
                text,
                press_enter,
            })),
        };
        let mut bytes = Vec::new();
        if input_msg.encode(&mut bytes).is_err() {
            return false;
        }
        let packet = Packet {
            session_id: self.my_device_id.clone(),
            sequence_number: 1,
            channel: Channel::Input as i32,
            payload: bytes,
        };
        let guard = self.active_peer_tx.read().await;
        if let Some(ref tx) = *guard {
            tx.send(packet).await.is_ok()
        } else {
            false
        }
    }
}

fn url_decode(s: &str) -> String {
    let mut result = String::new();
    let mut chars = s.chars().peekable();
    while let Some(c) = chars.next() {
        if c == '%' {
            let hex: String = chars.by_ref().take(2).collect();
            if let Ok(byte) = u8::from_str_radix(&hex, 16) {
                result.push(byte as char);
            }
        } else if c == '+' {
            result.push(' ');
        } else {
            result.push(c);
        }
    }
    result
}
