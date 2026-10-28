use tokio::sync::mpsc;
use prost::Message;

use continuity_daemon::media::MediaManager;
use continuity_daemon::proto::{
    media_control_command::Command as MediaCommand,
    media_message::Msg,
    media_playback_state::State as PlaybackProtoState,
    Channel, MediaMessage, MediaMetadata, MediaPlaybackState, Packet,
};

#[tokio::test]
async fn test_media_manager_metadata_and_playback_state() {
    let temp_art = std::env::temp_dir().join(format!("continuity_test_art_{}.jpg", uuid::Uuid::new_v4()));
    let media_mgr = MediaManager::with_art_cache("desktop-test".to_string(), temp_art.clone());

    let (peer_tx, _peer_rx) = mpsc::channel::<Packet>(16);

    // 1. Send Metadata
    let dummy_art = vec![0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46]; // JPEG header bytes
    let meta_msg = MediaMessage {
        msg: Some(Msg::Metadata(MediaMetadata {
            track_id: "spotify:track:4cOdK2wGLETKBW3PvgPWqT".to_string(),
            title: "Starboy".to_string(),
            artist: "The Weeknd".to_string(),
            album: "Starboy".to_string(),
            duration_ms: 230000,
            art_bytes: dummy_art.clone(),
            player_name: "Spotify".to_string(),
        })),
    };

    media_mgr
        .handle_message(meta_msg, "Pixel 9 Pro", peer_tx.clone())
        .await;

    let state = media_mgr.get_state().await;
    assert_eq!(state.track_title, "Starboy");
    assert_eq!(state.track_artist, "The Weeknd");
    assert_eq!(state.track_album, "Starboy");
    assert!(state.metadata.contains_key("mpris:trackid"));
    assert!(state.metadata.contains_key("xesam:title"));
    assert!(state.metadata.contains_key("xesam:artist"));
    assert!(state.metadata.contains_key("xesam:album"));
    assert!(state.metadata.contains_key("mpris:length"));
    assert!(state.metadata.contains_key("mpris:artUrl"));

    // Verify album art file was written
    let file_bytes = tokio::fs::read(&temp_art).await.expect("Art file must exist");
    assert_eq!(file_bytes, dummy_art);

    // 2. Send PlaybackState: Playing
    let play_msg = MediaMessage {
        msg: Some(Msg::PlaybackState(MediaPlaybackState {
            state: PlaybackProtoState::Playing as i32,
            position_ms: 45000,
            playback_speed: 1.0,
            can_play: true,
            can_pause: true,
            can_skip_next: true,
            can_skip_previous: true,
            can_seek: true,
            update_time_ms: 1700000000000,
        })),
    };

    media_mgr
        .handle_message(play_msg, "Pixel 9 Pro", peer_tx.clone())
        .await;

    let state = media_mgr.get_state().await;
    assert_eq!(state.playback_status, "Playing");
    assert_eq!(state.position_us, 45_000_000);
    assert!(state.can_play);
    assert!(state.can_pause);
    assert!(state.can_go_next);
    assert!(state.can_go_previous);

    // 3. Send PlaybackState: Paused
    let pause_msg = MediaMessage {
        msg: Some(Msg::PlaybackState(MediaPlaybackState {
            state: PlaybackProtoState::Paused as i32,
            position_ms: 50000,
            playback_speed: 1.0,
            can_play: true,
            can_pause: false,
            can_skip_next: true,
            can_skip_previous: true,
            can_seek: true,
            update_time_ms: 1700000005000,
        })),
    };

    media_mgr
        .handle_message(pause_msg, "Pixel 9 Pro", peer_tx)
        .await;

    let state = media_mgr.get_state().await;
    assert_eq!(state.playback_status, "Paused");
    assert_eq!(state.position_us, 50_000_000);

    let _ = tokio::fs::remove_file(&temp_art).await;
}

#[tokio::test]
async fn test_create_control_packet() {
    let media_mgr = MediaManager::new("desktop-test".to_string());
    let packet = media_mgr.create_control_packet(MediaCommand::Next, 0);

    assert_eq!(packet.channel, Channel::Media as i32);
    let decoded = MediaMessage::decode(&packet.payload[..]).expect("Must decode MediaMessage");
    match decoded.msg {
        Some(Msg::Control(cmd)) => {
            assert_eq!(cmd.command(), MediaCommand::Next);
        }
        _ => panic!("Expected Control message"),
    }
}
