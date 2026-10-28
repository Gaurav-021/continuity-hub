use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use prost::Message;
use serde::Serialize;
use tokio::sync::{mpsc, Mutex, RwLock};
use tracing::{debug, error, info, warn};
use uuid::Uuid;

use crate::proto::{
    call_action_request::Action as CallAction,
    call_event::CallState,
    device_telemetry::{ChargingType, NetworkType},
    telephony_message::Msg,
    CallActionRequest, CallEvent, Channel, DeviceTelemetry, Packet, SendSmsRequest,
    SmsMessage, TelephonyMessage,
};

#[derive(Serialize, Debug, Clone)]
pub struct WaybarOutput {
    pub text: String,
    pub alt: String,
    pub tooltip: String,
    pub class: String,
    pub percentage: u32,
}

pub struct TelephonyManager {
    my_device_id: String,
    sequence_number: AtomicU64,
    latest_telemetry: Arc<RwLock<Option<DeviceTelemetry>>>,
    active_call_notifications: Arc<Mutex<HashMap<String, u32>>>,
    connected_peer_name: Arc<RwLock<Option<String>>>,
    telemetry_file_path: std::path::PathBuf,
}

impl TelephonyManager {
    pub fn new(my_device_id: String) -> Self {
        Self {
            my_device_id,
            sequence_number: AtomicU64::new(0),
            latest_telemetry: Arc::new(RwLock::new(None)),
            active_call_notifications: Arc::new(Mutex::new(HashMap::new())),
            connected_peer_name: Arc::new(RwLock::new(None)),
            telemetry_file_path: std::path::PathBuf::from("/tmp/continuity-waybar.json"),
        }
    }

    pub fn with_telemetry_path(my_device_id: String, path: std::path::PathBuf) -> Self {
        Self {
            my_device_id,
            sequence_number: AtomicU64::new(0),
            latest_telemetry: Arc::new(RwLock::new(None)),
            active_call_notifications: Arc::new(Mutex::new(HashMap::new())),
            connected_peer_name: Arc::new(RwLock::new(None)),
            telemetry_file_path: path,
        }
    }

    pub fn wrap_packet(&self, msg: TelephonyMessage) -> Packet {
        let mut bytes = Vec::new();
        msg.encode(&mut bytes).unwrap();
        Packet {
            session_id: self.my_device_id.clone(),
            sequence_number: self.sequence_number.fetch_add(1, Ordering::SeqCst),
            channel: Channel::Telephony as i32,
            payload: bytes,
        }
    }

    pub async fn set_peer_name(&self, name: String) {
        *self.connected_peer_name.write().await = Some(name);
        self.refresh_waybar_status().await;
    }

    pub async fn get_latest_telemetry(&self) -> Option<DeviceTelemetry> {
        self.latest_telemetry.read().await.clone()
    }

    pub async fn handle_message(
        &self,
        msg: TelephonyMessage,
        peer_name: &str,
        peer_tx: mpsc::Sender<Packet>,
    ) -> Option<Packet> {
        let inner = msg.msg?;
        match inner {
            Msg::CallEvent(call) => {
                self.handle_call_event(call, peer_name, peer_tx).await;
                None
            }
            Msg::CallAction(action) => {
                debug!("Received remote CallAction: {:?}", action);
                None
            }
            Msg::SmsMessage(sms) => {
                self.handle_sms_message(sms, peer_name).await;
                None
            }
            Msg::SendSms(req) => {
                debug!("Received SendSms request for: {}", req.phone_number);
                None
            }
            Msg::SendSmsResp(resp) => {
                if resp.success {
                    info!("SMS delivered successfully to recipient! (Req ID: {})", resp.request_id);
                } else {
                    warn!("SMS delivery failed: {} (Req ID: {})", resp.error_message, resp.request_id);
                }
                None
            }
            Msg::Telemetry(telemetry) => {
                self.handle_telemetry(telemetry, peer_name).await;
                None
            }
        }
    }

    async fn handle_call_event(
        &self,
        call: CallEvent,
        peer_name: &str,
        peer_tx: mpsc::Sender<Packet>,
    ) {
        let state = CallState::try_from(call.state).unwrap_or(CallState::Unknown);
        let caller_display = if !call.contact_name.is_empty() {
            format!("{} ({})", call.contact_name, call.phone_number)
        } else if !call.phone_number.is_empty() {
            call.phone_number.clone()
        } else {
            "Unknown Caller".to_string()
        };

        match state {
            CallState::Ringing => {
                info!("Incoming Phone Call on {}: {}", peer_name, caller_display);

                let call_id = call.call_id.clone();
                let phone_num = call.phone_number.clone();
                let active_map = Arc::clone(&self.active_call_notifications);
                let my_device_id = self.my_device_id.clone();

                tokio::spawn(async move {
                    let mut notification = notify_rust::Notification::new();
                    notification
                        .appname("Continuity Telephony")
                        .summary(&format!("Incoming Call: {}", caller_display))
                        .body("Phone is ringing...")
                        .icon("call-start")
                        .urgency(notify_rust::Urgency::Critical)
                        .timeout(notify_rust::Timeout::Never);

                    notification.action("mute", "Mute Ringer");
                    notification.action("reject", "Decline Call");
                    notification.action("sms", "Decline & Send SMS");

                    match notification.show_async().await {
                        Ok(handle) => {
                            let dbus_id = handle.id();
                            active_map.lock().await.insert(call_id.clone(), dbus_id);

                            let call_id_clone = call_id.clone();
                            let phone_num_clone = phone_num.clone();
                            let peer_tx_clone = peer_tx.clone();
                            let active_map_cleanup = Arc::clone(&active_map);

                            handle.wait_for_action(move |action| {
                                debug!("Call notification action chosen: {}", action);

                                let call_action = match action {
                                    "mute" => Some(CallAction::Mute),
                                    "reject" => Some(CallAction::Reject),
                                    "sms" => Some(CallAction::RejectWithSms),
                                    _ => None,
                                };

                                if let Some(act) = call_action {
                                    let sms_reply = if act == CallAction::RejectWithSms {
                                        "I'm currently busy, will call you back later.".to_string()
                                    } else {
                                        String::new()
                                    };

                                    let req = CallActionRequest {
                                        call_id: call_id_clone.clone(),
                                        action: act as i32,
                                        sms_reply,
                                        phone_number: phone_num_clone,
                                    };

                                    let mut bytes = Vec::new();
                                    TelephonyMessage {
                                        msg: Some(Msg::CallAction(req)),
                                    }
                                    .encode(&mut bytes)
                                    .unwrap();

                                    let packet = Packet {
                                        session_id: my_device_id,
                                        sequence_number: 1,
                                        channel: Channel::Telephony as i32,
                                        payload: bytes,
                                    };

                                    let _ = peer_tx_clone.try_send(packet);
                                }

                                let active_cleanup = Arc::clone(&active_map_cleanup);
                                let c_id = call_id_clone.clone();
                                tokio::spawn(async move {
                                    active_cleanup.lock().await.remove(&c_id);
                                });
                            });
                        }
                        Err(e) => {
                            error!("Failed to show incoming call notification: {}", e);
                        }
                    }
                });
            }
            CallState::Idle | CallState::Offhook => {
                debug!("Call {} state changed to {:?}, closing ringing alert", call.call_id, state);
                if let Some(dbus_id) = self.active_call_notifications.lock().await.remove(&call.call_id) {
                    Self::close_desktop_notification(dbus_id).await;
                }
            }
            CallState::Missed => {
                info!("Missed Phone Call from {}", caller_display);
                if let Some(dbus_id) = self.active_call_notifications.lock().await.remove(&call.call_id) {
                    Self::close_desktop_notification(dbus_id).await;
                }

                tokio::spawn(async move {
                    let mut notification = notify_rust::Notification::new();
                    notification
                        .appname("Continuity Telephony")
                        .summary("Missed Call")
                        .body(&format!("Missed call from {}", caller_display))
                        .icon("call-missed")
                        .urgency(notify_rust::Urgency::Normal)
                        .timeout(notify_rust::Timeout::Milliseconds(7000));
                    let _ = notification.show_async().await;
                });
            }
            _ => {}
        }
    }

    async fn handle_sms_message(&self, sms: SmsMessage, _peer_name: &str) {
        if sms.is_incoming {
            info!("Incoming SMS from {} ({}): {}", sms.contact_name, sms.phone_number, sms.body);

            let sender = if !sms.contact_name.is_empty() {
                format!("{} ({})", sms.contact_name, sms.phone_number)
            } else {
                sms.phone_number.clone()
            };

            let body = sms.body.clone();
            tokio::spawn(async move {
                let mut notification = notify_rust::Notification::new();
                notification
                    .appname("Continuity SMS")
                    .summary(&format!("SMS from {}", sender))
                    .body(&body)
                    .icon("mail-message-new")
                    .timeout(notify_rust::Timeout::Milliseconds(8000));
                let _ = notification.show_async().await;
            });
        }
    }

    async fn handle_telemetry(&self, telemetry: DeviceTelemetry, peer_name: &str) {
        info!(
            "Device Telemetry from {}: {}% battery (charging: {}), Net: {:?}",
            peer_name,
            telemetry.battery_percentage,
            telemetry.is_charging,
            NetworkType::try_from(telemetry.network_type).unwrap_or(NetworkType::NetworkUnknown)
        );

        *self.latest_telemetry.write().await = Some(telemetry);
        *self.connected_peer_name.write().await = Some(peer_name.to_string());

        self.refresh_waybar_status().await;
    }

    pub async fn refresh_waybar_status(&self) {
        let telemetry_opt = self.latest_telemetry.read().await.clone();
        let peer_name = self.connected_peer_name.read().await.clone().unwrap_or_else(|| "Phone".to_string());

        if let Some(t) = telemetry_opt {
            let charging_icon = if t.is_charging { " ⚡" } else { "" };
            let battery_icon = match t.battery_percentage {
                90..=100 => "󰁹",
                70..=89 => "󰂀",
                50..=69 => "󰁾",
                30..=49 => "󰁼",
                10..=29 => "󰁺",
                _ => "󰂃",
            };

            let text = format!("{} {}%{}", battery_icon, t.battery_percentage, charging_icon);

            let charging_str = match ChargingType::try_from(t.charging_type) {
                Ok(ChargingType::ChargingAc) => "AC Charging",
                Ok(ChargingType::ChargingUsb) => "USB Charging",
                Ok(ChargingType::ChargingWireless) => "Wireless Charging",
                Ok(ChargingType::ChargingFast) => "Fast Charging",
                _ => if t.is_charging { "Charging" } else { "Discharging" },
            };

            let net_str = match NetworkType::try_from(t.network_type) {
                Ok(NetworkType::NetworkWifi) => {
                    if !t.wifi_ssid.is_empty() {
                        format!("Wi-Fi ({})", t.wifi_ssid)
                    } else {
                        "Wi-Fi".to_string()
                    }
                }
                Ok(NetworkType::NetworkCellular5g) => {
                    if !t.cellular_carrier.is_empty() {
                        format!("5G ({}, {} bars)", t.cellular_carrier, t.signal_bars)
                    } else {
                        format!("5G ({} bars)", t.signal_bars)
                    }
                }
                Ok(NetworkType::NetworkCellular4g) => {
                    if !t.cellular_carrier.is_empty() {
                        format!("4G/LTE ({}, {} bars)", t.cellular_carrier, t.signal_bars)
                    } else {
                        format!("4G/LTE ({} bars)", t.signal_bars)
                    }
                }
                _ => "No Cellular/WiFi".to_string(),
            };

            let tooltip = format!(
                "{}\nBattery: {}% ({})\nTemperature: {:.1}°C\nNetwork: {}",
                peer_name, t.battery_percentage, charging_str, t.battery_temperature_c, net_str
            );

            let class = if t.is_charging {
                "charging"
            } else if t.battery_percentage <= 20 {
                "critical"
            } else if t.battery_percentage <= 40 {
                "warning"
            } else {
                "good"
            };

            let output = WaybarOutput {
                text,
                alt: class.to_string(),
                tooltip,
                class: class.to_string(),
                percentage: t.battery_percentage,
            };

            if let Ok(json) = serde_json::to_string(&output) {
                let _ = tokio::fs::write(&self.telemetry_file_path, json).await;
            }
        }
    }

    pub fn create_send_sms_packet(&self, phone_number: &str, body: &str) -> Packet {
        let req = SendSmsRequest {
            request_id: Uuid::new_v4().to_string(),
            phone_number: phone_number.to_string(),
            body: body.to_string(),
        };

        self.wrap_packet(TelephonyMessage {
            msg: Some(Msg::SendSms(req)),
        })
    }

    async fn close_desktop_notification(id: u32) {
        if let Ok(connection) = zbus::connection::Connection::session().await {
            let _ = connection
                .call_method(
                    Some("org.freedesktop.Notifications"),
                    "/org/freedesktop/Notifications",
                    Some("org.freedesktop.Notifications"),
                    "CloseNotification",
                    &(id),
                )
                .await;
        }
    }
}
