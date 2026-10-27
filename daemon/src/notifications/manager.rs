use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use prost::Message;
use tokio::sync::{mpsc, Mutex};
use tracing::{debug, info};
use uuid::Uuid;

use crate::proto::{
    notification_action_request::ActionType,
    notification_message::Msg,
    Channel, NotificationAction, NotificationActionRequest,
    NotificationMessage, NotificationPayload, Packet,
};

pub struct NotificationManager {
    my_device_id: String,
    sequence_number: AtomicU64,
    active_notifications: Arc<Mutex<HashMap<String, u32>>>,
}

impl NotificationManager {
    pub fn new(my_device_id: String) -> Self {
        Self {
            my_device_id,
            sequence_number: AtomicU64::new(0),
            active_notifications: Arc::new(Mutex::new(HashMap::new())),
        }
    }

    fn wrap_packet(&self, msg: NotificationMessage) -> Packet {
        let mut bytes = Vec::new();
        msg.encode(&mut bytes).unwrap();
        Packet {
            session_id: self.my_device_id.clone(),
            sequence_number: self.sequence_number.fetch_add(1, Ordering::SeqCst),
            channel: Channel::Notifications as i32,
            payload: bytes,
        }
    }

    pub async fn handle_message(
        &self,
        msg: NotificationMessage,
        peer_name: &str,
        peer_tx: mpsc::Sender<Packet>,
    ) -> Option<Packet> {
        match msg.msg? {
            Msg::Post(payload) => {
                info!(
                    "Incoming notification from {}: [{}] {} - {}",
                    peer_name, payload.app_name, payload.title, payload.body
                );

                let summary = if payload.app_name.is_empty() {
                    payload.title.clone()
                } else {
                    format!("[{}] {}", payload.app_name, payload.title)
                };

                let body = payload.body.clone();
                let notif_id = payload.notification_id.clone();
                let my_device_id = self.my_device_id.clone();
                let active_map = Arc::clone(&self.active_notifications);

                tokio::spawn(async move {
                    let mut notification = notify_rust::Notification::new();
                    notification
                        .appname(&format!("Continuity ({})", payload.app_name))
                        .summary(&summary)
                        .body(&body)
                        .icon("dialog-information")
                        .timeout(notify_rust::Timeout::Milliseconds(8000));

                    notification.action("dismiss", "Dismiss");

                    match notification.show_async().await {
                        Ok(handle) => {
                            let id = handle.id();
                            active_map.lock().await.insert(notif_id.clone(), id);

                            let notif_id_clone = notif_id.clone();
                            let my_device_id_clone = my_device_id.clone();
                            let peer_tx_clone = peer_tx.clone();
                            let active_map_cleanup = Arc::clone(&active_map);

                            handle.wait_for_action(move |action| {
                                debug!("Notification action clicked: {}", action);
                                if action == "dismiss" {
                                    let req = NotificationActionRequest {
                                        notification_id: notif_id_clone,
                                        action_id: "dismiss".to_string(),
                                        action_type: ActionType::ActionDismiss as i32,
                                        reply_text: String::new(),
                                        source_device_id: my_device_id_clone,
                                    };
                                    let mut bytes = Vec::new();
                                    NotificationMessage {
                                        msg: Some(Msg::Action(req)),
                                    }
                                    .encode(&mut bytes)
                                    .unwrap();

                                    let packet = Packet {
                                        session_id: "dismiss".to_string(),
                                        sequence_number: 0,
                                        channel: Channel::Notifications as i32,
                                        payload: bytes,
                                    };
                                    let _ = peer_tx_clone.try_send(packet);
                                }
                            });

                            active_map_cleanup.lock().await.remove(&notif_id);
                        }
                        Err(e) => {
                            debug!("notify-rust show_async error: {}, falling back to notify-send", e);
                            let _ = std::process::Command::new("notify-send")
                                .arg("-a")
                                .arg(format!("Continuity: {}", payload.app_name))
                                .arg(&summary)
                                .arg(&body)
                                .spawn();
                        }
                    }
                });

                None
            }

            Msg::Dismiss(dismiss) => {
                debug!("Notification dismissed on phone: {}", dismiss.notification_id);
                let mut map = self.active_notifications.lock().await;
                if let Some(desktop_id) = map.remove(&dismiss.notification_id) {
                    tokio::spawn(async move {
                        if let Ok(conn) = zbus::connection::Connection::session().await {
                            let _ = conn
                                .call_method(
                                    Some("org.freedesktop.Notifications"),
                                    "/org/freedesktop/Notifications",
                                    Some("org.freedesktop.Notifications"),
                                    "CloseNotification",
                                    &(desktop_id),
                                )
                                .await;
                        }
                    });
                }
                None
            }

            Msg::Action(action) => {
                debug!("Notification action received: {:?}", action);
                None
            }
        }
    }

    pub fn create_alert_packet(
        &self,
        title: &str,
        body: &str,
        app_name: &str,
    ) -> Packet {
        let payload = NotificationPayload {
            notification_id: Uuid::new_v4().to_string(),
            package_name: "com.continuityhub.desktop".to_string(),
            app_name: app_name.to_string(),
            title: title.to_string(),
            body: body.to_string(),
            timestamp_ms: std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis() as u64,
            icon_data: Vec::new(),
            category: "desktop_alert".to_string(),
            has_quick_reply: false,
            actions: vec![NotificationAction {
                action_id: "dismiss".to_string(),
                label: "Dismiss".to_string(),
                is_quick_reply: false,
            }],
            source_device_id: self.my_device_id.clone(),
        };

        self.wrap_packet(NotificationMessage {
            msg: Some(Msg::Post(payload)),
        })
    }
}
