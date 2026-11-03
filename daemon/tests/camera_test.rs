use std::sync::Arc;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::sync::mpsc;
use prost::Message;

use continuity_daemon::camera::CameraManager;
use continuity_daemon::proto::{
    camera_config::Lens,
    camera_control_command::Action as CameraAction,
    camera_message::Msg,
    CameraConfig, CameraFrame, CameraMessage, CameraSnapshot, Channel, Packet,
};

#[tokio::test]
async fn test_camera_manager_frame_and_config() {
    let camera_mgr = CameraManager::with_port("desktop-cam-test".to_string(), 52891);
    let (peer_tx, _peer_rx) = mpsc::channel::<Packet>(16);

    // 1. Send CameraConfig
    let config = CameraConfig {
        width: 1920,
        height: 1080,
        target_fps: 30,
        lens: Lens::Back as i32,
        format: 0,
        torch_enabled: true,
        zoom_ratio: 1.0,
        rotation_degrees: 0,
    };

    camera_mgr
        .handle_message(
            CameraMessage {
                msg: Some(Msg::Config(config)),
            },
            "Pixel 9 Pro",
            peer_tx.clone(),
        )
        .await;

    let status = camera_mgr.get_status().await;
    assert_eq!(status.width, 1920);
    assert_eq!(status.height, 1080);
    assert_eq!(status.lens, "Back");

    // 2. Send CameraFrame
    let dummy_jpeg = vec![0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x00, 0x01];
    let frame = CameraFrame {
        frame_index: 1,
        timestamp_us: 1000000,
        width: 1920,
        height: 1080,
        frame_data: dummy_jpeg.clone(),
        is_keyframe: true,
        is_phone_screen: false,
    };

    camera_mgr
        .handle_message(
            CameraMessage {
                msg: Some(Msg::Frame(frame)),
            },
            "Pixel 9 Pro",
            peer_tx.clone(),
        )
        .await;

    let status = camera_mgr.get_status().await;
    assert!(status.is_streaming);

    // 3. Send CameraSnapshot
    let snapshot = CameraSnapshot {
        snapshot_id: "snap-001".to_string(),
        timestamp_ms: 1700000000000,
        image_bytes: dummy_jpeg.clone(),
        width: 1920,
        height: 1080,
    };

    camera_mgr
        .handle_message(
            CameraMessage {
                msg: Some(Msg::Snapshot(snapshot)),
            },
            "Pixel 9 Pro",
            peer_tx,
        )
        .await;

    // 4. Test Control packet creation
    let packet = camera_mgr.create_control_packet(CameraAction::Start, Lens::Front, false, 2.0);
    assert_eq!(packet.channel, Channel::Camera as i32);
    let decoded = CameraMessage::decode(&packet.payload[..]).unwrap();
    match decoded.msg {
        Some(Msg::Control(cmd)) => {
            assert_eq!(cmd.action(), CameraAction::Start);
            assert_eq!(cmd.lens(), Lens::Front);
            assert_eq!(cmd.zoom_ratio, 2.0);
        }
        _ => panic!("Expected Control message"),
    }
}

#[tokio::test]
async fn test_camera_http_stream_server() {
    let port = 52892;
    let camera_mgr = CameraManager::with_port("desktop-http-cam-test".to_string(), port);
    camera_mgr
        .start_http_stream_server()
        .await
        .expect("Server must start");

    // Allow socket to bind
    tokio::time::sleep(tokio::time::Duration::from_millis(50)).await;

    // 1. Test GET /status
    let mut stream = TcpStream::connect(format!("127.0.0.1:{}", port))
        .await
        .expect("Must connect to HTTP stream server");

    stream
        .write_all(b"GET /status HTTP/1.1\r\nHost: localhost\r\n\r\n")
        .await
        .unwrap();

    let mut response = Vec::new();
    let mut buf = [0u8; 1024];
    let n = stream.read(&mut buf).await.unwrap();
    response.extend_from_slice(&buf[..n]);

    let response_str = String::from_utf8_lossy(&response);
    assert!(response_str.contains("HTTP/1.1 200 OK"));
    assert!(response_str.contains("\"mjpeg_url\""));
    assert!(response_str.contains("\"snapshot_url\""));
}

#[tokio::test]
async fn test_phone_mirror_endpoints_and_input() {
    let port = 52893;
    let camera_mgr = CameraManager::with_port("desktop-phone-mirror-test".to_string(), port);
    camera_mgr
        .start_http_stream_server()
        .await
        .expect("Server must start");

    tokio::time::sleep(tokio::time::Duration::from_millis(50)).await;

    // 1. Send Phone Screen Frame
    let (peer_tx, mut peer_rx) = mpsc::channel::<Packet>(16);
    camera_mgr.set_peer_tx(peer_tx.clone()).await;

    let dummy_screen_jpeg = vec![0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x00, 0x01];
    let frame = CameraFrame {
        frame_index: 42,
        timestamp_us: 2000000,
        width: 1080,
        height: 2400,
        frame_data: dummy_screen_jpeg.clone(),
        is_keyframe: true,
        is_phone_screen: true,
    };

    camera_mgr
        .handle_message(
            CameraMessage {
                msg: Some(Msg::Frame(frame)),
            },
            "Pixel 9 Pro",
            peer_tx,
        )
        .await;

    // 2. Query GET /phone/status
    let mut stream = TcpStream::connect(format!("127.0.0.1:{}", port))
        .await
        .expect("Must connect to HTTP server");

    stream
        .write_all(b"GET /phone/status HTTP/1.1\r\nHost: localhost\r\n\r\n")
        .await
        .unwrap();

    let mut buf = [0u8; 1024];
    let n = stream.read(&mut buf).await.unwrap();
    let resp = String::from_utf8_lossy(&buf[..n]);
    assert!(resp.contains("HTTP/1.1 200 OK"));
    assert!(resp.contains("\"is_mirroring\":true"));
    assert!(resp.contains("\"width\":1080"));
    assert!(resp.contains("\"height\":2400"));

    // 3. Query GET /phone HTML5 Viewer
    let mut stream2 = TcpStream::connect(format!("127.0.0.1:{}", port)).await.unwrap();
    stream2
        .write_all(b"GET /phone HTTP/1.1\r\nHost: localhost\r\n\r\n")
        .await
        .unwrap();
    let n2 = stream2.read(&mut buf).await.unwrap();
    let resp2 = String::from_utf8_lossy(&buf[..n2]);
    assert!(resp2.contains("HTTP/1.1 200 OK"));
    assert!(resp2.contains("Phone Mirror"));

    // 4. Send POST /phone/input with tap JSON
    let mut stream3 = TcpStream::connect(format!("127.0.0.1:{}", port)).await.unwrap();
    let body = r#"{"type":"tap","x":0.5,"y":0.8}"#;
    let req = format!(
        "POST /phone/input HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nContent-Length: {}\r\n\r\n{}",
        body.len(),
        body
    );
    stream3.write_all(req.as_bytes()).await.unwrap();

    let n3 = stream3.read(&mut buf).await.unwrap();
    let resp3 = String::from_utf8_lossy(&buf[..n3]);
    assert!(resp3.contains("HTTP/1.1 200 OK"));
    assert!(resp3.contains("\"ok\":true"));

    // Verify packet was dispatched to peer receiver channel
    let packet = peer_rx.recv().await.expect("Must receive input packet");
    assert_eq!(packet.channel, Channel::Input as i32);

    // 5. Test POST /phone/privacy endpoint
    let mut stream4 = TcpStream::connect(format!("127.0.0.1:{}", port)).await.unwrap();
    stream4.write_all(b"POST /phone/privacy?enabled=true HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n").await.unwrap();
    let n4 = stream4.read(&mut buf).await.unwrap();
    let resp4 = String::from_utf8_lossy(&buf[..n4]);
    assert!(resp4.contains("HTTP/1.1 200 OK"));
    assert!(resp4.contains("\"privacy_mode\":true"));

    // Verify privacy control packet was sent to peer
    let privacy_packet = peer_rx.recv().await.expect("Must receive privacy packet");
    assert_eq!(privacy_packet.channel, Channel::Camera as i32);

    // 6. Test POST /phone/drop endpoint
    let file_transfer_mgr = Arc::new(continuity_daemon::transfer::FileTransferManager::new(None, "desktop-test".to_string()));
    camera_mgr.set_file_transfer_manager(file_transfer_mgr).await;

    let mut stream5 = TcpStream::connect(format!("127.0.0.1:{}", port)).await.unwrap();
    let file_payload = b"PK\x03\x04dummy APK file contents for continuity testing";
    let drop_req = format!(
        "POST /phone/drop?filename=test_app.apk HTTP/1.1\r\nHost: localhost\r\nContent-Length: {}\r\n\r\n",
        file_payload.len()
    );
    stream5.write_all(drop_req.as_bytes()).await.unwrap();
    stream5.write_all(file_payload).await.unwrap();

    let n5 = stream5.read(&mut buf).await.unwrap();
    let resp5 = String::from_utf8_lossy(&buf[..n5]);
    assert!(resp5.contains("HTTP/1.1 200 OK"));
    assert!(resp5.contains("test_app.apk"));
    assert!(resp5.contains("\"ok\":true"));
}
