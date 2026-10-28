use prost::Message;
use std::sync::Arc;
use tokio::sync::mpsc;

use continuity_daemon::proto::{
    call_action_request::Action as CallAction,
    call_event::CallState,
    device_telemetry::{ChargingType, NetworkType},
    telephony_message::Msg,
    CallActionRequest, CallEvent, Channel, DeviceTelemetry, Packet,
    SendSmsResponse, SmsMessage, TelephonyMessage,
};
use continuity_daemon::telephony::TelephonyManager;

#[tokio::test]
async fn test_telemetry_waybar_json_generation() {
    let temp_dir = std::env::temp_dir().join(format!("continuity_waybar_test_{}", uuid::Uuid::new_v4()));
    std::fs::create_dir_all(&temp_dir).unwrap();
    let waybar_file = temp_dir.join("waybar.json");

    let telephony_mgr = TelephonyManager::with_telemetry_path(
        "desktop-123".to_string(),
        waybar_file.clone(),
    );

    let (peer_tx, _peer_rx) = mpsc::channel::<Packet>(16);

    let telemetry = DeviceTelemetry {
        battery_percentage: 85,
        is_charging: true,
        charging_type: ChargingType::ChargingFast as i32,
        battery_temperature_c: 32.4,
        network_type: NetworkType::NetworkCellular5g as i32,
        wifi_ssid: "HomeFiber-5G".to_string(),
        cellular_carrier: "T-Mobile".to_string(),
        signal_bars: 4,
        timestamp_ms: 1700000000000,
    };

    let msg = TelephonyMessage {
        msg: Some(Msg::Telemetry(telemetry)),
    };

    telephony_mgr.handle_message(msg, "Pixel 9 Pro", peer_tx).await;

    // Verify waybar file was written
    let content = tokio::fs::read_to_string(&waybar_file)
        .await
        .expect("Waybar status file must exist");

    println!("Generated Waybar JSON: {}", content);

    assert!(content.contains("\"percentage\":85"));
    assert!(content.contains("Pixel 9 Pro"));
    assert!(content.contains("Fast Charging"));
    assert!(content.contains("T-Mobile"));
    assert!(content.contains("32.4°C"));
    assert!(content.contains("charging"));

    let _ = std::fs::remove_dir_all(&temp_dir);
}

#[tokio::test]
async fn test_call_event_and_sms_handling() {
    let telephony_mgr = Arc::new(TelephonyManager::new("desktop-123".to_string()));
    let (peer_tx, _peer_rx) = mpsc::channel::<Packet>(16);

    // 1. Ringing Call Event
    let call_ringing = CallEvent {
        call_id: "call-999".to_string(),
        phone_number: "+1 (555) 019-2834".to_string(),
        contact_name: "Bruce Wayne".to_string(),
        state: CallState::Ringing as i32,
        timestamp_ms: 1700000001000,
    };
    let ring_msg = TelephonyMessage {
        msg: Some(Msg::CallEvent(call_ringing)),
    };
    telephony_mgr.handle_message(ring_msg, "Pixel 9 Pro", peer_tx.clone()).await;

    // 2. Call Idle Event (closing ringing alert)
    let call_idle = CallEvent {
        call_id: "call-999".to_string(),
        phone_number: "+1 (555) 019-2834".to_string(),
        contact_name: "Bruce Wayne".to_string(),
        state: CallState::Idle as i32,
        timestamp_ms: 1700000005000,
    };
    let idle_msg = TelephonyMessage {
        msg: Some(Msg::CallEvent(call_idle)),
    };
    telephony_mgr.handle_message(idle_msg, "Pixel 9 Pro", peer_tx.clone()).await;

    // 3. Incoming SMS Event
    let sms = SmsMessage {
        message_id: "sms-555".to_string(),
        phone_number: "+1 (555) 019-2834".to_string(),
        contact_name: "Bruce Wayne".to_string(),
        body: "Check the Batcomputer logs.".to_string(),
        timestamp_ms: 1700000010000,
        is_incoming: true,
    };
    let sms_msg = TelephonyMessage {
        msg: Some(Msg::SmsMessage(sms)),
    };
    telephony_mgr.handle_message(sms_msg, "Pixel 9 Pro", peer_tx.clone()).await;

    // 4. Outbound SMS packet creation
    let send_packet = telephony_mgr.create_send_sms_packet("+1 (555) 019-2834", "On it.");
    assert_eq!(send_packet.channel, Channel::Telephony as i32);

    let decoded_tel = TelephonyMessage::decode(&send_packet.payload[..]).unwrap();
    if let Some(Msg::SendSms(req)) = decoded_tel.msg {
        assert_eq!(req.phone_number, "+1 (555) 019-2834");
        assert_eq!(req.body, "On it.");
    } else {
        panic!("Decoded packet did not contain SendSms message");
    }
}

#[test]
fn test_telephony_protobuf_roundtrip_call_action() {
    let action_req = CallActionRequest {
        call_id: "call-test-1".to_string(),
        action: CallAction::RejectWithSms as i32,
        sms_reply: "Driving, cannot talk.".to_string(),
        phone_number: "+15551234567".to_string(),
    };

    let original = TelephonyMessage {
        msg: Some(Msg::CallAction(action_req)),
    };

    let mut buf = Vec::new();
    original.encode(&mut buf).unwrap();

    let decoded = TelephonyMessage::decode(&buf[..]).unwrap();
    if let Some(Msg::CallAction(a)) = decoded.msg {
        assert_eq!(a.call_id, "call-test-1");
        assert_eq!(a.action, CallAction::RejectWithSms as i32);
        assert_eq!(a.sms_reply, "Driving, cannot talk.");
        assert_eq!(a.phone_number, "+15551234567");
    } else {
        panic!("Decoded message was not CallAction");
    }
}

#[test]
fn test_telephony_protobuf_roundtrip_send_sms_resp() {
    let resp = SendSmsResponse {
        request_id: "req-abc".to_string(),
        success: true,
        error_message: String::new(),
    };

    let original = TelephonyMessage {
        msg: Some(Msg::SendSmsResp(resp)),
    };

    let mut buf = Vec::new();
    original.encode(&mut buf).unwrap();

    let decoded = TelephonyMessage::decode(&buf[..]).unwrap();
    if let Some(Msg::SendSmsResp(r)) = decoded.msg {
        assert_eq!(r.request_id, "req-abc");
        assert!(r.success);
    } else {
        panic!("Decoded message was not SendSmsResp");
    }
}
