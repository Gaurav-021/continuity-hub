use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::Arc;
use tokio::sync::RwLock;
use tracing::{debug, info, warn};

#[cfg(target_os = "linux")]
use zbus::interface;

#[derive(Clone)]
pub struct TrayManager {
    device_name: Arc<RwLock<String>>,
    battery_percentage: Arc<AtomicU32>,
    is_charging: Arc<AtomicBool>,
    is_connected: Arc<AtomicBool>,
    telemetry_tooltip: Arc<RwLock<String>>,
}

impl TrayManager {
    pub fn new() -> Self {
        Self {
            device_name: Arc::new(RwLock::new("No phone connected".to_string())),
            battery_percentage: Arc::new(AtomicU32::new(0)),
            is_charging: Arc::new(AtomicBool::new(false)),
            is_connected: Arc::new(AtomicBool::new(false)),
            telemetry_tooltip: Arc::new(RwLock::new("Continuity Hub: Waiting for connection...".to_string())),
        }
    }

    pub async fn update_telemetry(
        &self,
        device_name: &str,
        battery: u32,
        charging: bool,
        temp_c: f32,
        network: &str,
    ) {
        *self.device_name.write().await = device_name.to_string();
        self.battery_percentage.store(battery, Ordering::Relaxed);
        self.is_charging.store(charging, Ordering::Relaxed);
        self.is_connected.store(true, Ordering::Relaxed);

        let tooltip = format!(
            "{}\nBattery: {}% ({})\nTemperature: {:.1}°C\nNetwork: {}",
            device_name,
            battery,
            if charging { "Charging" } else { "Discharging" },
            temp_c,
            network
        );
        *self.telemetry_tooltip.write().await = tooltip;
    }

    pub async fn clear_telemetry(&self) {
        self.is_connected.store(false, Ordering::Relaxed);
        *self.device_name.write().await = "No phone connected".to_string();
        *self.telemetry_tooltip.write().await = "Continuity Hub: Waiting for connection...".to_string();
    }

    pub async fn start(self: Arc<Self>) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        #[cfg(target_os = "linux")]
        {
            let pid = std::process::id();
            let bus_name = format!("org.kde.StatusNotifierItem-{}-1", pid);
            let item = StatusNotifierItem {
                manager: Arc::clone(&self),
            };

            let connection = match zbus::connection::Builder::session() {
                Ok(b) => match b.name(bus_name.as_str()) {
                    Ok(b) => match b.serve_at("/StatusNotifierItem", item) {
                        Ok(b) => match b.build().await {
                            Ok(c) => c,
                            Err(e) => {
                                warn!("Failed to build D-Bus connection for Tray: {}", e);
                                return Ok(());
                            }
                        },
                        Err(e) => {
                            warn!("Failed to serve /StatusNotifierItem: {}", e);
                            return Ok(());
                        }
                    },
                    Err(e) => {
                        warn!("Failed to request bus name for Tray: {}", e);
                        return Ok(());
                    }
                },
                Err(e) => {
                    warn!("Failed to connect to session bus for Tray: {}", e);
                    return Ok(());
                }
            };

            info!("Registered D-Bus StatusNotifierItem at /StatusNotifierItem on {}", bus_name);

            // Register with StatusNotifierWatcher
            let watcher_res: Result<zbus::Proxy, zbus::Error> = zbus::Proxy::new(
                &connection,
                "org.kde.StatusNotifierWatcher",
                "/StatusNotifierWatcher",
                "org.kde.StatusNotifierWatcher",
            )
            .await;

            if let Ok(watcher) = watcher_res {
                let path = "/StatusNotifierItem";
                let reg_res: Result<(), zbus::Error> = watcher.call("RegisterStatusNotifierItem", &(path)).await;
                match reg_res {
                    Ok(_) => info!("Successfully registered Continuity Hub in system tray (StatusNotifierWatcher)"),
                    Err(e) => debug!("StatusNotifierWatcher registration returned: {} (normal if tray host is still starting)", e),
                }
            } else {
                debug!("StatusNotifierWatcher not found on session bus");
            }

            // Keep connection alive in background
            tokio::spawn(async move {
                let _conn = connection;
                futures::future::pending::<()>().await;
            });
        }

        Ok(())
    }
}

impl Default for TrayManager {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(target_os = "linux")]
pub struct StatusNotifierItem {
    manager: Arc<TrayManager>,
}

#[cfg(target_os = "linux")]
#[interface(name = "org.kde.StatusNotifierItem")]
impl StatusNotifierItem {
    #[zbus(property)]
    fn category(&self) -> &str {
        "ApplicationStatus"
    }

    #[zbus(property)]
    fn id(&self) -> &str {
        "continuity-hub"
    }

    #[zbus(property)]
    async fn title(&self) -> String {
        let name = self.manager.device_name.read().await;
        if self.manager.is_connected.load(Ordering::Relaxed) {
            let batt = self.manager.battery_percentage.load(Ordering::Relaxed);
            format!("Continuity Hub — {} ({}%)", *name, batt)
        } else {
            "Continuity Hub".to_string()
        }
    }

    #[zbus(property)]
    fn status(&self) -> &str {
        "Active"
    }

    #[zbus(property)]
    fn window_id(&self) -> i32 {
        0
    }

    #[zbus(property)]
    fn icon_name(&self) -> &str {
        "continuity-hub"
    }

    #[zbus(property)]
    fn overlay_icon_name(&self) -> &str {
        ""
    }

    #[zbus(property)]
    fn attention_icon_name(&self) -> &str {
        "continuity-hub"
    }

    #[zbus(property)]
    fn attention_movie_name(&self) -> &str {
        ""
    }

    #[zbus(property)]
    fn icon_theme_path(&self) -> &str {
        ""
    }

    #[zbus(property)]
    fn item_is_menu(&self) -> bool {
        false
    }

    #[zbus(property)]
    async fn tool_tip(&self) -> (String, Vec<(i32, i32, Vec<u8>)>, String, String) {
        let title = if self.manager.is_connected.load(Ordering::Relaxed) {
            let name = self.manager.device_name.read().await;
            let batt = self.manager.battery_percentage.load(Ordering::Relaxed);
            format!("{} ({}%)", *name, batt)
        } else {
            "Continuity Hub".to_string()
        };

        let desc = self.manager.telemetry_tooltip.read().await.clone();
        ("continuity-hub".to_string(), Vec::new(), title, desc)
    }

    async fn context_menu(&self, _x: i32, _y: i32) {
        info!("Tray context menu invoked");
    }

    async fn activate(&self, _x: i32, _y: i32) {
        info!("Tray icon activated (left-click) — opening Continuity Camera Viewer");
        let _ = tokio::process::Command::new("xdg-open")
            .arg("http://127.0.0.1:52802")
            .spawn();
    }

    async fn secondary_activate(&self, _x: i32, _y: i32) {
        info!("Tray icon secondary activated (middle-click)");
    }

    async fn scroll(&self, _delta: i32, _orientation: &str) {
        debug!("Tray icon scroll: delta={}, orientation={}", _delta, _orientation);
    }
}
