use std::collections::{HashMap, HashSet};
use std::path::PathBuf;
use std::sync::Arc;
use prost::Message;
use tokio::sync::{mpsc, Mutex, RwLock};
use tracing::{debug, error, info, warn};

#[cfg(target_os = "linux")]
use zbus::interface;
#[cfg(target_os = "linux")]
use zbus::zvariant::{ObjectPath, OwnedObjectPath, OwnedValue, Value};

use crate::proto::{
    media_control_command::Command as MediaCommand,
    media_message::Msg,
    media_playback_state::State as PlaybackProtoState,
    Channel, MediaControlCommand, MediaMessage, MediaMetadata, MediaPlaybackState, Packet,
};

#[cfg(target_os = "linux")]
pub struct MprisRoot;

#[cfg(target_os = "linux")]
#[interface(name = "org.mpris.MediaPlayer2")]
impl MprisRoot {
    #[zbus(property)]
    fn can_quit(&self) -> bool {
        false
    }

    #[zbus(property)]
    fn can_raise(&self) -> bool {
        false
    }

    #[zbus(property)]
    fn has_track_list(&self) -> bool {
        false
    }

    #[zbus(property)]
    fn identity(&self) -> &str {
        "Continuity Phone"
    }

    #[zbus(property)]
    fn desktop_entry(&self) -> &str {
        "continuity"
    }

    #[zbus(property)]
    fn supported_uri_schemes(&self) -> Vec<String> {
        vec![]
    }

    #[zbus(property)]
    fn supported_mime_types(&self) -> Vec<String> {
        vec![]
    }

    fn raise(&self) {}
    fn quit(&self) {}
}

#[derive(Clone, Debug)]
pub struct PlayerState {
    pub playback_status: String,
    #[cfg(target_os = "linux")]
    pub metadata: HashMap<String, OwnedValue>,
    #[cfg(not(target_os = "linux"))]
    pub metadata: HashMap<String, String>,
    pub position_us: i64,
    pub volume: f64,
    pub can_control: bool,
    pub can_play: bool,
    pub can_pause: bool,
    pub can_go_next: bool,
    pub can_go_previous: bool,
    pub can_seek: bool,
    pub track_title: String,
    pub track_artist: String,
    pub track_album: String,
}

impl Default for PlayerState {
    fn default() -> Self {
        Self {
            playback_status: "Stopped".to_string(),
            metadata: HashMap::new(),
            position_us: 0,
            volume: 1.0,
            can_control: true,
            can_play: true,
            can_pause: true,
            can_go_next: true,
            can_go_previous: true,
            can_seek: true,
            track_title: String::new(),
            track_artist: String::new(),
            track_album: String::new(),
        }
    }
}

#[cfg(target_os = "linux")]
pub struct MprisPlayer {
    state: Arc<RwLock<PlayerState>>,
    command_tx: mpsc::Sender<MediaControlCommand>,
}

#[cfg(target_os = "linux")]
#[interface(name = "org.mpris.MediaPlayer2.Player")]
impl MprisPlayer {
    async fn next(&self) {
        let _ = self
            .command_tx
            .send(MediaControlCommand {
                command: MediaCommand::Next as i32,
                seek_position_ms: 0,
                volume: 1.0,
                target_player: String::new(),
            })
            .await;
    }

    async fn previous(&self) {
        let _ = self
            .command_tx
            .send(MediaControlCommand {
                command: MediaCommand::Previous as i32,
                seek_position_ms: 0,
                volume: 1.0,
                target_player: String::new(),
            })
            .await;
    }

    async fn pause(&self) {
        let _ = self
            .command_tx
            .send(MediaControlCommand {
                command: MediaCommand::Pause as i32,
                seek_position_ms: 0,
                volume: 1.0,
                target_player: String::new(),
            })
            .await;
    }

    async fn play_pause(&self) {
        let _ = self
            .command_tx
            .send(MediaControlCommand {
                command: MediaCommand::PlayPause as i32,
                seek_position_ms: 0,
                volume: 1.0,
                target_player: String::new(),
            })
            .await;
    }

    async fn stop(&self) {
        let _ = self
            .command_tx
            .send(MediaControlCommand {
                command: MediaCommand::Stop as i32,
                seek_position_ms: 0,
                volume: 1.0,
                target_player: String::new(),
            })
            .await;
    }

    async fn play(&self) {
        let _ = self
            .command_tx
            .send(MediaControlCommand {
                command: MediaCommand::Play as i32,
                seek_position_ms: 0,
                volume: 1.0,
                target_player: String::new(),
            })
            .await;
    }

    async fn seek(&self, offset_us: i64) {
        let offset_ms = offset_us / 1000;
        let _ = self
            .command_tx
            .send(MediaControlCommand {
                command: MediaCommand::Seek as i32,
                seek_position_ms: offset_ms,
                volume: 1.0,
                target_player: String::new(),
            })
            .await;
    }

    async fn set_position(&self, _track_id: OwnedObjectPath, position_us: i64) {
        let position_ms = position_us / 1000;
        let _ = self
            .command_tx
            .send(MediaControlCommand {
                command: MediaCommand::Seek as i32,
                seek_position_ms: position_ms,
                volume: 1.0,
                target_player: String::new(),
            })
            .await;
    }

    fn open_uri(&self, _uri: String) {}

    #[zbus(property)]
    async fn playback_status(&self) -> String {
        self.state.read().await.playback_status.clone()
    }

    #[zbus(property)]
    async fn metadata(&self) -> HashMap<String, OwnedValue> {
        self.state.read().await.metadata.clone()
    }

    #[zbus(property)]
    async fn position(&self) -> i64 {
        self.state.read().await.position_us
    }

    #[zbus(property)]
    async fn volume(&self) -> f64 {
        self.state.read().await.volume
    }

    #[zbus(property)]
    async fn set_volume(&mut self, volume: f64) {
        self.state.write().await.volume = volume;
        let _ = self
            .command_tx
            .send(MediaControlCommand {
                command: MediaCommand::SetVolume as i32,
                seek_position_ms: 0,
                volume: volume as f32,
                target_player: String::new(),
            })
            .await;
    }

    #[zbus(property)]
    fn rate(&self) -> f64 {
        1.0
    }

    #[zbus(property)]
    fn can_control(&self) -> bool {
        true
    }

    #[zbus(property)]
    async fn can_play(&self) -> bool {
        self.state.read().await.can_play
    }

    #[zbus(property)]
    async fn can_pause(&self) -> bool {
        self.state.read().await.can_pause
    }

    #[zbus(property)]
    async fn can_go_next(&self) -> bool {
        self.state.read().await.can_go_next
    }

    #[zbus(property)]
    async fn can_go_previous(&self) -> bool {
        self.state.read().await.can_go_previous
    }

    #[zbus(property)]
    async fn can_seek(&self) -> bool {
        self.state.read().await.can_seek
    }
}

pub struct MediaManager {
    my_device_id: String,
    state: Arc<RwLock<PlayerState>>,
    command_tx: mpsc::Sender<MediaControlCommand>,
    active_peer_tx: Arc<RwLock<Option<mpsc::Sender<Packet>>>>,
    ducked_players: Arc<Mutex<HashSet<String>>>,
    art_cache_path: PathBuf,
    #[cfg(target_os = "linux")]
    dbus_conn: Arc<RwLock<Option<zbus::Connection>>>,
}

impl MediaManager {
    pub fn new(my_device_id: String) -> Arc<Self> {
        let temp_art = std::env::temp_dir().join("continuity-media-art.jpg");
        Self::with_art_cache(my_device_id, temp_art)
    }

    pub fn with_art_cache(my_device_id: String, art_cache_path: PathBuf) -> Arc<Self> {
        let (command_tx, mut command_rx) = mpsc::channel::<MediaControlCommand>(32);
        let active_peer_tx: Arc<RwLock<Option<mpsc::Sender<Packet>>>> = Arc::new(RwLock::new(None));
        let active_peer_for_task = Arc::clone(&active_peer_tx);
        let my_id_clone = my_device_id.clone();

        // Forward local MPRIS commands to connected remote phone
        tokio::spawn(async move {
            while let Some(cmd) = command_rx.recv().await {
                debug!("Dispatching MPRIS command {:?} to remote phone", cmd.command());
                let msg = MediaMessage {
                    msg: Some(Msg::Control(cmd)),
                };
                let mut bytes = Vec::new();
                if let Err(e) = msg.encode(&mut bytes) {
                    error!("Failed to encode MediaMessage: {}", e);
                    continue;
                }
                let packet = Packet {
                    session_id: my_id_clone.clone(),
                    sequence_number: 1,
                    channel: Channel::Media as i32,
                    payload: bytes,
                };

                let peer_guard = active_peer_for_task.read().await;
                if let Some(ref tx) = *peer_guard {
                    let _ = tx.send(packet).await;
                }
            }
        });

        Arc::new(Self {
            my_device_id,
            state: Arc::new(RwLock::new(PlayerState::default())),
            command_tx,
            active_peer_tx,
            ducked_players: Arc::new(Mutex::new(HashSet::new())),
            art_cache_path,
            #[cfg(target_os = "linux")]
            dbus_conn: Arc::new(RwLock::new(None)),
        })
    }

    #[cfg(target_os = "linux")]
    pub async fn start_dbus_server(self: &Arc<Self>) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        let mpris_player = MprisPlayer {
            state: Arc::clone(&self.state),
            command_tx: self.command_tx.clone(),
        };
        let mpris_root = MprisRoot;

        match zbus::connection::Builder::session() {
            Ok(builder) => {
                let conn = builder
                    .name("org.mpris.MediaPlayer2.ContinuityPhone")?
                    .serve_at("/org/mpris/MediaPlayer2", mpris_root)?
                    .serve_at("/org/mpris/MediaPlayer2", mpris_player)?
                    .build()
                    .await?;

                info!("MPRIS D-Bus service registered successfully as 'org.mpris.MediaPlayer2.ContinuityPhone'");
                *self.dbus_conn.write().await = Some(conn);
            }
            Err(e) => {
                warn!("Could not connect to D-Bus session bus for MPRIS: {}", e);
            }
        }

        Ok(())
    }

    #[cfg(not(target_os = "linux"))]
    pub async fn start_dbus_server(self: &Arc<Self>) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        Ok(())
    }

    pub async fn set_peer_tx(&self, tx: mpsc::Sender<Packet>) {
        *self.active_peer_tx.write().await = Some(tx);
    }

    pub async fn get_state(&self) -> PlayerState {
        self.state.read().await.clone()
    }

    pub async fn handle_message(
        &self,
        msg: MediaMessage,
        peer_name: &str,
        peer_tx: mpsc::Sender<Packet>,
    ) -> Option<Packet> {
        self.set_peer_tx(peer_tx).await;

        let inner = msg.msg?;
        match inner {
            Msg::PlaybackState(playback) => {
                self.handle_playback_state(playback, peer_name).await;
                None
            }
            Msg::Metadata(meta) => {
                self.handle_metadata(meta, peer_name).await;
                None
            }
            Msg::Control(control) => {
                // Desktop-to-Phone control reverse direction: phone controlling desktop media player!
                self.control_desktop_player(&control).await;
                None
            }
        }
    }

    async fn handle_playback_state(&self, playback: MediaPlaybackState, peer_name: &str) {
        let proto_state = PlaybackProtoState::try_from(playback.state).unwrap_or(PlaybackProtoState::Unknown);
        let status_str = match proto_state {
            PlaybackProtoState::Playing => "Playing",
            PlaybackProtoState::Paused => "Paused",
            PlaybackProtoState::Stopped | PlaybackProtoState::Unknown => "Stopped",
        };

        debug!("Phone media playback update from {}: status={}", peer_name, status_str);

        {
            let mut state = self.state.write().await;
            state.playback_status = status_str.to_string();
            state.position_us = playback.position_ms * 1000;
            state.can_play = playback.can_play;
            state.can_pause = playback.can_pause;
            state.can_go_next = playback.can_skip_next;
            state.can_go_previous = playback.can_skip_previous;
            state.can_seek = playback.can_seek;
        }

        // Notify D-Bus properties changed
        self.emit_playback_status_changed().await;
    }

    async fn handle_metadata(&self, meta: MediaMetadata, peer_name: &str) {
        info!(
            "Received Media Metadata from {}: '{}' by '{}' ({})",
            peer_name, meta.title, meta.artist, meta.album
        );

        let mut art_url = String::new();
        if !meta.art_bytes.is_empty() {
            if let Err(e) = tokio::fs::write(&self.art_cache_path, &meta.art_bytes).await {
                warn!("Failed to cache media art: {}", e);
            } else {
                art_url = format!("file://{}", self.art_cache_path.display());
            }
        }

        #[cfg(target_os = "linux")]
        let mut dbus_metadata = HashMap::new();
        #[cfg(target_os = "linux")]
        {
            let track_id_path = if meta.track_id.is_empty() {
                "/org/continuity/current_track".to_string()
            } else {
                let sanitized: String = meta
                    .track_id
                    .chars()
                    .map(|c| if c.is_ascii_alphanumeric() { c } else { '_' })
                    .collect();
                format!("/org/continuity/track/{}", sanitized)
            };

            if let Ok(path) = ObjectPath::try_from(track_id_path) {
                if let Ok(ov) = OwnedValue::try_from(Value::from(path)) {
                    dbus_metadata.insert("mpris:trackid".to_string(), ov);
                }
            }

            if !meta.title.is_empty() {
                if let Ok(ov) = OwnedValue::try_from(Value::from(meta.title.as_str())) {
                    dbus_metadata.insert("xesam:title".to_string(), ov);
                }
            }

            if !meta.artist.is_empty() {
                let artists = vec![meta.artist.clone()];
                if let Ok(ov) = OwnedValue::try_from(Value::from(artists)) {
                    dbus_metadata.insert("xesam:artist".to_string(), ov);
                }
            }

            if !meta.album.is_empty() {
                if let Ok(ov) = OwnedValue::try_from(Value::from(meta.album.as_str())) {
                    dbus_metadata.insert("xesam:album".to_string(), ov);
                }
            }

            if meta.duration_ms > 0 {
                let length_us = meta.duration_ms * 1000;
                dbus_metadata.insert("mpris:length".to_string(), OwnedValue::from(length_us));
            }

            if !art_url.is_empty() {
                if let Ok(ov) = OwnedValue::try_from(Value::from(art_url.as_str())) {
                    dbus_metadata.insert("mpris:artUrl".to_string(), ov);
                }
            }
        }

        {
            let mut state = self.state.write().await;
            state.track_title = meta.title;
            state.track_artist = meta.artist;
            state.track_album = meta.album;
            #[cfg(target_os = "linux")]
            {
                state.metadata = dbus_metadata;
            }
        }

        // Emit PropertiesChanged signal
        self.emit_metadata_changed().await;
    }

    #[cfg(target_os = "linux")]
    async fn emit_playback_status_changed(&self) {
        let guard = self.dbus_conn.read().await;
        if let Some(ref conn) = *guard {
            if let Ok(iface_ref) = conn
                .object_server()
                .interface::<_, MprisPlayer>("/org/mpris/MediaPlayer2")
                .await
            {
                let player = iface_ref.get().await;
                let emitter = iface_ref.signal_emitter();
                let _ = player.playback_status_changed(emitter).await;
                let _ = player.can_play_changed(emitter).await;
                let _ = player.can_pause_changed(emitter).await;
                let _ = player.can_go_next_changed(emitter).await;
                let _ = player.can_go_previous_changed(emitter).await;
                let _ = player.can_seek_changed(emitter).await;
            }
        }
    }

    #[cfg(not(target_os = "linux"))]
    async fn emit_playback_status_changed(&self) {}

    #[cfg(target_os = "linux")]
    async fn emit_metadata_changed(&self) {
        let guard = self.dbus_conn.read().await;
        if let Some(ref conn) = *guard {
            if let Ok(iface_ref) = conn
                .object_server()
                .interface::<_, MprisPlayer>("/org/mpris/MediaPlayer2")
                .await
            {
                let _ = iface_ref
                    .get()
                    .await
                    .metadata_changed(iface_ref.signal_emitter())
                    .await;
            }
        }
    }

    #[cfg(not(target_os = "linux"))]
    async fn emit_metadata_changed(&self) {}

    /// Smart Call Ducking: pause active desktop MPRIS players when a phone call rings
    #[cfg(target_os = "linux")]
    pub async fn duck_media(&self) {
        let conn = match zbus::connection::Connection::session().await {
            Ok(c) => c,
            Err(e) => {
                warn!("Cannot duck media, D-Bus session unavailable: {}", e);
                return;
            }
        };

        let dbus_proxy = match zbus::fdo::DBusProxy::new(&conn).await {
            Ok(p) => p,
            Err(e) => {
                warn!("Cannot duck media, DBusProxy failed: {}", e);
                return;
            }
        };

        let names = match dbus_proxy.list_names().await {
            Ok(n) => n,
            Err(e) => {
                warn!("Cannot duck media, list_names failed: {}", e);
                return;
            }
        };

        let mut to_duck = Vec::new();
        for name in names {
            let name_str = name.as_str();
            if name_str.starts_with("org.mpris.MediaPlayer2.")
                && name_str != "org.mpris.MediaPlayer2.ContinuityPhone"
            {
                // Check playback status
                let status_res: Result<zbus::zvariant::OwnedValue, _> = conn
                    .call_method(
                        Some(name_str),
                        "/org/mpris/MediaPlayer2",
                        Some("org.freedesktop.DBus.Properties"),
                        "Get",
                        &("org.mpris.MediaPlayer2.Player", "PlaybackStatus"),
                    )
                    .await
                    .and_then(|r| r.body().deserialize());

                if let Ok(val) = status_res {
                    if let Ok(status) = <&str>::try_from(&val) {
                        if status == "Playing" {
                            to_duck.push(name_str.to_string());
                        }
                    }
                }
            }
        }

        for player_name in to_duck {
            info!("Smart Call Ducking: Pausing desktop player '{}' during incoming call", player_name);
            let _ = conn
                .call_method(
                    Some(player_name.as_str()),
                    "/org/mpris/MediaPlayer2",
                    Some("org.mpris.MediaPlayer2.Player"),
                    "Pause",
                    &(),
                )
                .await;
            self.ducked_players.lock().await.insert(player_name);
        }
    }

    #[cfg(not(target_os = "linux"))]
    pub async fn duck_media(&self) {}

    /// Smart Call Ducking: resume previously ducked desktop players after call ends
    #[cfg(target_os = "linux")]
    pub async fn unduck_media(&self) {
        let conn = match zbus::connection::Connection::session().await {
            Ok(c) => c,
            Err(_) => return,
        };

        let mut ducked = self.ducked_players.lock().await;
        for player_name in ducked.iter() {
            info!("Smart Call Ducking: Resuming desktop player '{}' after call ended", player_name);
            let _ = conn
                .call_method(
                    Some(player_name.as_str()),
                    "/org/mpris/MediaPlayer2",
                    Some("org.mpris.MediaPlayer2.Player"),
                    "Play",
                    &(),
                )
                .await;
        }
        ducked.clear();
    }

    #[cfg(not(target_os = "linux"))]
    pub async fn unduck_media(&self) {}

    /// Desktop-to-Phone: forward remote media commands from phone to desktop MPRIS player
    #[cfg(target_os = "linux")]
    pub async fn control_desktop_player(&self, cmd: &MediaControlCommand) {
        let conn = match zbus::connection::Connection::session().await {
            Ok(c) => c,
            Err(e) => {
                warn!("Cannot forward media command to desktop, D-Bus session unavailable: {}", e);
                return;
            }
        };

        let dbus_proxy = match zbus::fdo::DBusProxy::new(&conn).await {
            Ok(p) => p,
            Err(_) => return,
        };

        let names = match dbus_proxy.list_names().await {
            Ok(n) => n,
            Err(_) => return,
        };

        // Find an active player
        let target_player = if !cmd.target_player.is_empty() {
            cmd.target_player.clone()
        } else {
            names
                .into_iter()
                .map(|n| n.to_string())
                .find(|n| {
                    n.starts_with("org.mpris.MediaPlayer2.")
                        && n != "org.mpris.MediaPlayer2.ContinuityPhone"
                })
                .unwrap_or_default()
        };

        if target_player.is_empty() {
            debug!("No desktop MPRIS players found to control");
            return;
        }

        let cmd_type = MediaCommand::try_from(cmd.command).unwrap_or(MediaCommand::Unknown);
        debug!("Controlling desktop player '{}' with action {:?}", target_player, cmd_type);

        match cmd_type {
            MediaCommand::Play => {
                let _ = conn
                    .call_method(
                        Some(target_player.as_str()),
                        "/org/mpris/MediaPlayer2",
                        Some("org.mpris.MediaPlayer2.Player"),
                        "Play",
                        &(),
                    )
                    .await;
            }
            MediaCommand::Pause => {
                let _ = conn
                    .call_method(
                        Some(target_player.as_str()),
                        "/org/mpris/MediaPlayer2",
                        Some("org.mpris.MediaPlayer2.Player"),
                        "Pause",
                        &(),
                    )
                    .await;
            }
            MediaCommand::PlayPause => {
                let _ = conn
                    .call_method(
                        Some(target_player.as_str()),
                        "/org/mpris/MediaPlayer2",
                        Some("org.mpris.MediaPlayer2.Player"),
                        "PlayPause",
                        &(),
                    )
                    .await;
            }
            MediaCommand::Next => {
                let _ = conn
                    .call_method(
                        Some(target_player.as_str()),
                        "/org/mpris/MediaPlayer2",
                        Some("org.mpris.MediaPlayer2.Player"),
                        "Next",
                        &(),
                    )
                    .await;
            }
            MediaCommand::Previous => {
                let _ = conn
                    .call_method(
                        Some(target_player.as_str()),
                        "/org/mpris/MediaPlayer2",
                        Some("org.mpris.MediaPlayer2.Player"),
                        "Previous",
                        &(),
                    )
                    .await;
            }
            MediaCommand::Stop => {
                let _ = conn
                    .call_method(
                        Some(target_player.as_str()),
                        "/org/mpris/MediaPlayer2",
                        Some("org.mpris.MediaPlayer2.Player"),
                        "Stop",
                        &(),
                    )
                    .await;
            }
            MediaCommand::Seek => {
                let offset_us = cmd.seek_position_ms * 1000;
                let _ = conn
                    .call_method(
                        Some(target_player.as_str()),
                        "/org/mpris/MediaPlayer2",
                        Some("org.mpris.MediaPlayer2.Player"),
                        "Seek",
                        &(offset_us),
                    )
                    .await;
            }
            MediaCommand::SetVolume => {
                let vol = cmd.volume as f64;
                let _ = conn
                    .call_method(
                        Some(target_player.as_str()),
                        "/org/mpris/MediaPlayer2",
                        Some("org.freedesktop.DBus.Properties"),
                        "Set",
                        &("org.mpris.MediaPlayer2.Player", "Volume", zbus::zvariant::Value::from(vol)),
                    )
                    .await;
            }
            MediaCommand::Unknown => {}
        }
    }

    #[cfg(not(target_os = "linux"))]
    pub async fn control_desktop_player(&self, _cmd: &MediaControlCommand) {}

    pub fn create_control_packet(&self, command: MediaCommand, seek_position_ms: i64) -> Packet {
        let cmd = MediaControlCommand {
            command: command as i32,
            seek_position_ms,
            volume: 1.0,
            target_player: String::new(),
        };
        let msg = MediaMessage {
            msg: Some(Msg::Control(cmd)),
        };
        let mut bytes = Vec::new();
        msg.encode(&mut bytes).unwrap();
        Packet {
            session_id: self.my_device_id.clone(),
            sequence_number: 1,
            channel: Channel::Media as i32,
            payload: bytes,
        }
    }
}
