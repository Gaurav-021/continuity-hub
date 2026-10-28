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
    CameraConfig, CameraControlCommand, CameraFrame, CameraMessage, CameraSnapshot,
    Channel, DesktopScreenFrame, Packet,
};

#[derive(Clone, Debug)]
pub struct CameraStatus {
    pub is_streaming: bool,
    pub width: u32,
    pub height: u32,
    pub lens: String,
    pub fps: u32,
    pub torch: bool,
    pub zoom: f32,
}

pub struct CameraManager {
    my_device_id: String,
    http_port: u16,
    is_streaming: Arc<AtomicBool>,
    latest_frame: Arc<RwLock<Option<CameraFrame>>>,
    active_peer_tx: Arc<RwLock<Option<mpsc::Sender<Packet>>>>,
    frame_tx: broadcast::Sender<Vec<u8>>,
    fps_counter: Arc<AtomicU32>,
    current_fps: Arc<AtomicU32>,
    current_lens: Arc<RwLock<String>>,
    current_width: Arc<RwLock<u32>>,
    current_height: Arc<RwLock<u32>>,
    snapshot_path: PathBuf,
}

impl CameraManager {
    pub fn new(my_device_id: String) -> Arc<Self> {
        Self::with_port(my_device_id, 52802)
    }

    pub fn with_port(my_device_id: String, http_port: u16) -> Arc<Self> {
        let (frame_tx, _) = broadcast::channel::<Vec<u8>>(32);
        let snapshot_path = std::env::temp_dir().join("continuity-camera-snapshot.jpg");

        let manager = Arc::new(Self {
            my_device_id,
            http_port,
            is_streaming: Arc::new(AtomicBool::new(false)),
            latest_frame: Arc::new(RwLock::new(None)),
            active_peer_tx: Arc::new(RwLock::new(None)),
            frame_tx,
            fps_counter: Arc::new(AtomicU32::new(0)),
            current_fps: Arc::new(AtomicU32::new(0)),
            current_lens: Arc::new(RwLock::new("Back".to_string())),
            current_width: Arc::new(RwLock::new(1920)),
            current_height: Arc::new(RwLock::new(1080)),
            snapshot_path,
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
                    let mut buf = [0u8; 1024];
                    let n = match stream.read(&mut buf).await {
                        Ok(n) if n > 0 => n,
                        _ => return,
                    };

                    let req_str = String::from_utf8_lossy(&buf[..n]);
                    let path = req_str
                        .lines()
                        .next()
                        .and_then(|line| line.split_whitespace().nth(1))
                        .unwrap_or("/");

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
        self.is_streaming.store(true, Ordering::Relaxed);
        self.fps_counter.fetch_add(1, Ordering::Relaxed);

        *self.current_width.write().await = frame.width;
        *self.current_height.write().await = frame.height;

        let frame_data = frame.frame_data.clone();
        *self.latest_frame.write().await = Some(frame);

        // Broadcast to HTTP MJPEG clients
        let _ = self.frame_tx.send(frame_data);
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

        if action == CameraAction::RequestDesktopScreen {
            // Capture desktop output via grim and return DesktopScreenFrame
            if let Some(jpeg_bytes) = self.capture_desktop_screen().await {
                let screen_frame = DesktopScreenFrame {
                    timestamp_ms: chrono::Utc::now().timestamp_millis() as u64,
                    width: 1920,
                    height: 1080,
                    image_bytes: jpeg_bytes,
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

        None
    }

    /// Capture current Hyprland screen via grim and encode as JPEG
    pub async fn capture_desktop_screen(&self) -> Option<Vec<u8>> {
        let output = tokio::process::Command::new("grim")
            .args(["-t", "jpeg", "-q", "65", "-"])
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

    pub fn create_control_packet(&self, action: CameraAction, lens: Lens, torch: bool, zoom_ratio: f32) -> Packet {
        let cmd = CameraControlCommand {
            action: action as i32,
            lens: lens as i32,
            torch,
            zoom_ratio,
            width: 1920,
            height: 1080,
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
}
