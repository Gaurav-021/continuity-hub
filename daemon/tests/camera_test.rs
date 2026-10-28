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
