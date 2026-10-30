use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use tokio::sync::RwLock;
use tracing::{debug, info};

#[derive(Debug, Clone, serde::Deserialize)]
pub struct HyprMonitor {
    pub id: i32,
    pub name: String,
    pub width: u32,
    pub height: u32,
    pub x: i32,
    pub y: i32,
    #[serde(rename = "activeWorkspace")]
    pub active_workspace: Option<HyprWorkspace>,
}

#[derive(Debug, Clone, serde::Deserialize)]
pub struct HyprWorkspace {
    pub id: i32,
    pub name: String,
}

pub type ScreenGeometry = (i32, i32, u32, u32);

pub struct SidecarManager {
    is_active: Arc<AtomicBool>,
    sidecar_workspace: Arc<RwLock<Option<i32>>>,
    sidecar_geometry: Arc<RwLock<Option<ScreenGeometry>>>,
}

impl Default for SidecarManager {
    fn default() -> Self {
        Self::new()
    }
}

impl SidecarManager {
    pub fn new() -> Self {
        Self {
            is_active: Arc::new(AtomicBool::new(false)),
            sidecar_workspace: Arc::new(RwLock::new(None)),
            sidecar_geometry: Arc::new(RwLock::new(None)),
        }
    }

    pub fn is_active(&self) -> bool {
        self.is_active.load(Ordering::Relaxed)
    }

    pub async fn get_workspace_id(&self) -> Option<i32> {
        *self.sidecar_workspace.read().await
    }

    pub async fn start_sidecar(&self) -> Result<(i32, i32, u32, u32), String> {
        info!("Creating Hyprland virtual headless monitor 'Sidecar'...");

        // 1. Create headless output in Hyprland
        let status = tokio::process::Command::new("hyprctl")
            .args(["output", "create", "headless", "Sidecar"])
            .status()
            .await
            .map_err(|e| format!("Failed to invoke hyprctl output create: {}", e))?;

        if !status.success() {
            return Err("hyprctl output create headless Sidecar exited with error".to_string());
        }

        // Give Hyprland a moment to initialize the virtual monitor
        tokio::time::sleep(tokio::time::Duration::from_millis(150)).await;

        // 2. Query monitor info
        let monitors = self.query_monitors().await?;
        let sidecar_mon = monitors
            .into_iter()
            .find(|m| m.name == "Sidecar")
            .ok_or_else(|| "Sidecar monitor was created but not found in hyprctl monitors".to_string())?;

        let geom = (sidecar_mon.x, sidecar_mon.y, sidecar_mon.width, sidecar_mon.height);
        *self.sidecar_geometry.write().await = Some(geom);

        let ws_id = sidecar_mon.active_workspace.map(|ws| ws.id);
        *self.sidecar_workspace.write().await = ws_id;
        self.is_active.store(true, Ordering::SeqCst);

        info!(
            "Hyprland Sidecar output active at ({}, {}) {}x{}, active workspace: {:?}",
            geom.0, geom.1, geom.2, geom.3, ws_id
        );

        Ok(geom)
    }

    pub async fn stop_sidecar(&self) {
        if !self.is_active.swap(false, Ordering::SeqCst) {
            return;
        }

        info!("Removing Hyprland virtual headless monitor 'Sidecar'...");
        let _ = tokio::process::Command::new("hyprctl")
            .args(["output", "remove", "Sidecar"])
            .status()
            .await;

        *self.sidecar_workspace.write().await = None;
        *self.sidecar_geometry.write().await = None;
    }

    pub async fn move_active_window_to_sidecar(&self) -> Result<(), String> {
        let ws_id = self.get_workspace_id().await.ok_or_else(|| "Sidecar workspace is not active".to_string())?;
        info!("Moving active window to Sidecar workspace {}", ws_id);

        let lua_code = format!("hl.dsp.window.move({{ workspace = {} }})", ws_id);
        let status = tokio::process::Command::new("hyprctl")
            .args(["eval", &lua_code])
            .status()
            .await
            .map_err(|e| format!("Failed to move window: {}", e))?;

        if status.success() {
            Ok(())
        } else {
            Err("hyprctl eval failed".to_string())
        }
    }

    pub async fn handle_touch_click(&self, norm_x: f32, norm_y: f32) {
        let geom = *self.sidecar_geometry.read().await;
        if let Some((x, y, w, h)) = geom {
            let target_x = x + (norm_x.clamp(0.0, 1.0) * w as f32) as i32;
            let target_y = y + (norm_y.clamp(0.0, 1.0) * h as f32) as i32;

            debug!("Sidecar touch mapped to screen coords: ({}, {})", target_x, target_y);
            let lua_code = format!("hl.dsp.cursor.move({{ x = {}, y = {} }})", target_x, target_y);
            let _ = tokio::process::Command::new("hyprctl")
                .args(["eval", &lua_code])
                .status()
                .await;
        }
    }

    async fn query_monitors(&self) -> Result<Vec<HyprMonitor>, String> {
        let output = tokio::process::Command::new("hyprctl")
            .args(["monitors", "-j"])
            .output()
            .await
            .map_err(|e| format!("Failed to run hyprctl monitors -j: {}", e))?;

        serde_json::from_slice(&output.stdout)
            .map_err(|e| format!("Failed to parse hyprctl monitors JSON: {}", e))
    }
}

impl Drop for SidecarManager {
    fn drop(&mut self) {
        if self.is_active.load(Ordering::Relaxed) {
            let _ = std::process::Command::new("hyprctl")
                .args(["output", "remove", "Sidecar"])
                .status();
        }
    }
}
