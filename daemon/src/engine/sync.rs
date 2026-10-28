use std::collections::{HashMap, HashSet};
use std::net::SocketAddr;
use std::path::Path;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use prost::Message;
use tokio::net::TcpStream;
use tokio::sync::{mpsc, Mutex, RwLock};
use tokio_rustls::TlsConnector;
use tracing::{debug, error, info, warn};

use crate::camera::CameraManager;
use crate::config::Identity;
use crate::discovery::{DiscoveredDevice, DiscoveryManager};
use crate::input::InputManager;
use crate::media::MediaManager;
use crate::notifications::NotificationManager;
use crate::platform::{ClipboardBackend, ClipboardItem};
use crate::proto::{Channel, ClipboardPayload, Packet};
use crate::telephony::TelephonyManager;
use crate::transfer::FileTransferManager;
use crate::transport::peer::{run_peer_connection, PeerInfo, PeerSession};
use crate::transport::server::TlsServer;
use crate::transport::tls::{create_client_config, create_server_config};
use crate::transport::tofu::TofuStore;

pub struct SyncEngine {
    identity: Arc<Identity>,
    tofu_store: TofuStore,
    clipboard_backend: Arc<dyn ClipboardBackend>,
    file_transfer_mgr: Arc<FileTransferManager>,
    notification_mgr: Arc<NotificationManager>,
    input_mgr: Arc<InputManager>,
    telephony_mgr: Arc<TelephonyManager>,
    media_mgr: Arc<MediaManager>,
    camera_mgr: Arc<CameraManager>,
    active_peers: Arc<RwLock<HashMap<String, mpsc::Sender<Packet>>>>,
    connecting_peers: Arc<Mutex<HashSet<String>>>,
    sequence_number: AtomicU64,
    clip_tx: mpsc::Sender<ClipboardPayload>,
    peer_tx: mpsc::Sender<PeerSession>,
    clip_rx: Mutex<Option<mpsc::Receiver<ClipboardPayload>>>,
    peer_rx: Mutex<Option<mpsc::Receiver<PeerSession>>>,
}

impl SyncEngine {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        identity: Arc<Identity>,
        tofu_store: TofuStore,
        clipboard_backend: Arc<dyn ClipboardBackend>,
        file_transfer_mgr: Arc<FileTransferManager>,
        notification_mgr: Arc<NotificationManager>,
        input_mgr: Arc<InputManager>,
        telephony_mgr: Arc<TelephonyManager>,
        media_mgr: Arc<MediaManager>,
        camera_mgr: Arc<CameraManager>,
    ) -> Self {
        let (clip_tx, clip_rx) = mpsc::channel::<ClipboardPayload>(64);
        let (peer_tx, peer_rx) = mpsc::channel::<PeerSession>(32);

        Self {
            identity,
            tofu_store,
            clipboard_backend,
            file_transfer_mgr,
            notification_mgr,
            input_mgr,
            telephony_mgr,
            media_mgr,
            camera_mgr,
            active_peers: Arc::new(RwLock::new(HashMap::new())),
            connecting_peers: Arc::new(Mutex::new(HashSet::new())),
            sequence_number: AtomicU64::new(0),
            clip_tx,
            peer_tx,
            clip_rx: Mutex::new(Some(clip_rx)),
            peer_rx: Mutex::new(Some(peer_rx)),
        }
    }

    pub fn file_transfer_manager(&self) -> Arc<FileTransferManager> {
        Arc::clone(&self.file_transfer_mgr)
    }

    pub fn notification_manager(&self) -> Arc<NotificationManager> {
        Arc::clone(&self.notification_mgr)
    }

    pub fn telephony_manager(&self) -> Arc<TelephonyManager> {
        Arc::clone(&self.telephony_mgr)
    }

    pub fn media_manager(&self) -> Arc<MediaManager> {
        Arc::clone(&self.media_mgr)
    }

    pub fn camera_manager(&self) -> Arc<CameraManager> {
        Arc::clone(&self.camera_mgr)
    }

    pub async fn send_sms(&self, phone_number: &str, body: &str) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        let packet = self.telephony_mgr.create_send_sms_packet(phone_number, body);
        let peers = self.active_peers.read().await;
        if peers.is_empty() {
            return Err("No active mobile peers connected to route SMS".into());
        }
        for (peer_id, tx) in peers.iter() {
            tx.send(packet.clone()).await.map_err(|e| format!("Failed to send SMS packet to peer {}: {}", peer_id, e))?;
        }
        info!("Dispatched SMS request to {} via connected phone", phone_number);
        Ok(())
    }

    pub async fn start(self: Arc<Self>) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        let server_config = create_server_config(&self.identity, self.tofu_store.clone())?;
        let client_config = create_client_config(&self.identity, self.tofu_store.clone())?;

        // Link media manager to telephony manager for smart call ducking
        self.telephony_mgr.set_media_manager(Arc::clone(&self.media_mgr)).await;

        // Start MPRIS D-Bus player
        let _ = self.media_mgr.start_dbus_server().await;

        // Start Continuity Camera HTTP MJPEG stream server
        let _ = self.camera_mgr.start_http_stream_server().await;

        let my_info = PeerInfo {
            device_id: self.identity.config.device_id.clone(),
            device_name: self.identity.config.device_name.clone(),
            cert_fingerprint: self.identity.fingerprint.clone(),
        };

        let mut clip_in_rx = self.clip_rx.lock().await.take().unwrap();
        let mut peer_reg_rx = self.peer_rx.lock().await.take().unwrap();

        // 1. Start TLS TCP Server
        let bind_addr = SocketAddr::from(([0, 0, 0, 0], self.identity.config.port));
        let server = TlsServer::bind(
            bind_addr,
            server_config,
            my_info.clone(),
            self.tofu_store.clone(),
            Arc::clone(&self.file_transfer_mgr),
            Arc::clone(&self.notification_mgr),
            Arc::clone(&self.input_mgr),
            Arc::clone(&self.telephony_mgr),
            Arc::clone(&self.media_mgr),
            Arc::clone(&self.camera_mgr),
        )
        .await?;

        let server_clip_tx = self.clip_tx.clone();
        let server_peer_tx = self.peer_tx.clone();
        tokio::spawn(async move {
            server.run(server_clip_tx, server_peer_tx).await;
        });

        // 2. Start Discovery (mDNS)
        let discovery_mgr = DiscoveryManager::new(Arc::new(self.identity.config.clone()))?;
        discovery_mgr.advertise()?;
        let mut discovery_rx = discovery_mgr.browse()?;

        // 3. Handle discovered devices
        let engine_clone = Arc::clone(&self);
        let client_cfg = Arc::clone(&client_config);
        let my_info_clone = my_info.clone();
        let disc_clip_tx = self.clip_tx.clone();
        let disc_peer_tx = self.peer_tx.clone();

        tokio::spawn(async move {
            while let Some(device) = discovery_rx.recv().await {
                engine_clone
                    .handle_discovered_device(
                        device,
                        Arc::clone(&client_cfg),
                        my_info_clone.clone(),
                        disc_clip_tx.clone(),
                        disc_peer_tx.clone(),
                    )
                    .await;
            }
        });

        // 4. Handle registered peer sessions
        let active_peers = Arc::clone(&self.active_peers);
        let telephony_for_reg = Arc::clone(&self.telephony_mgr);
        tokio::spawn(async move {
            while let Some(session) = peer_reg_rx.recv().await {
                info!(
                    "Peer '{}' ({}) successfully registered in active sessions pool",
                    session.info.device_name, session.info.device_id
                );
                telephony_for_reg.set_peer_name(session.info.device_name.clone()).await;
                let mut peers = active_peers.write().await;
                peers.insert(session.info.device_id, session.tx);
            }
        });

        // 5. Watch local clipboard and broadcast updates
        let mut local_clip_rx = self.clipboard_backend.watch().await?;
        let engine_for_local = Arc::clone(&self);
        tokio::spawn(async move {
            while let Some(item) = local_clip_rx.recv().await {
                engine_for_local.broadcast_clipboard_item(item).await;
            }
        });

        // 6. Handle incoming remote clipboard payloads
        let clipboard_backend = Arc::clone(&self.clipboard_backend);
        let my_device_id = self.identity.config.device_id.clone();
        tokio::spawn(async move {
            while let Some(payload) = clip_in_rx.recv().await {
                if payload.source_device_id == my_device_id {
                    continue;
                }

                info!(
                    "Received remote clipboard update from device '{}' ({} bytes, mime: {}, hash: {})",
                    payload.source_device_id,
                    payload.data.len(),
                    payload.mime_type,
                    payload.content_hash
                );

                let item = ClipboardItem {
                    mime_type: payload.mime_type,
                    data: payload.data,
                    timestamp_ms: payload.timestamp_ms,
                    content_hash: payload.content_hash,
                };

                if let Err(e) = clipboard_backend.set_content(&item).await {
                    error!("Failed to write remote clipboard to system clipboard: {}", e);
                }
            }
        });

        info!(
            "Continuity Daemon Phase 3 fully operational. Device ID: {}, Fingerprint: {}",
            self.identity.config.device_id, self.identity.fingerprint
        );

        Ok(())
    }

    pub async fn connect_to_peer(
        &self,
        addr: SocketAddr,
    ) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        let client_config = create_client_config(&self.identity, self.tofu_store.clone())?;
        let my_info = PeerInfo {
            device_id: self.identity.config.device_id.clone(),
            device_name: self.identity.config.device_name.clone(),
            cert_fingerprint: self.identity.fingerprint.clone(),
        };

        info!("Directly initiating TLS connection to peer at {}", addr);
        let tcp_stream = TcpStream::connect(addr).await?;
        let _ = tcp_stream.set_nodelay(true);
        let connector = TlsConnector::from(client_config);
        let server_name = rustls_pki_types::ServerName::try_from("localhost".to_string()).unwrap();

        let tls_stream = connector.connect(server_name, tcp_stream).await?;
        info!("mTLS Handshake established to {}", addr);

        let clip_tx = self.clip_tx.clone();
        let peer_tx = self.peer_tx.clone();
        let tofu_store = self.tofu_store.clone();
        let file_transfer_mgr = Arc::clone(&self.file_transfer_mgr);
        let notification_mgr = Arc::clone(&self.notification_mgr);
        let input_mgr = Arc::clone(&self.input_mgr);
        let telephony_mgr = Arc::clone(&self.telephony_mgr);
        let media_mgr = Arc::clone(&self.media_mgr);
        let camera_mgr = Arc::clone(&self.camera_mgr);

        tokio::spawn(async move {
            if let Err(e) = run_peer_connection(
                tls_stream,
                my_info,
                true,
                clip_tx,
                peer_tx,
                tofu_store,
                file_transfer_mgr,
                notification_mgr,
                input_mgr,
                telephony_mgr,
                media_mgr,
                camera_mgr,
            )
            .await
            {
                warn!("Connection with {} terminated: {}", addr, e);
            }
        });

        Ok(())
    }

    async fn handle_discovered_device(
        &self,
        device: DiscoveredDevice,
        client_config: Arc<rustls::ClientConfig>,
        my_info: PeerInfo,
        clip_tx: mpsc::Sender<ClipboardPayload>,
        peer_tx: mpsc::Sender<PeerSession>,
    ) {
        let peer_id = device.device_id.clone();

        // Check if already connected
        {
            let peers = self.active_peers.read().await;
            if peers.contains_key(&peer_id) {
                debug!("Peer {} is already connected", peer_id);
                return;
            }
        }

        // Deterministic connection tie-breaker:
        if my_info.device_id > peer_id {
            debug!(
                "Deterministic tie-breaker: letting peer {} initiate connection to us (my ID is larger)",
                peer_id
            );
            return;
        }

        // Check if currently attempting connection
        {
            let mut connecting = self.connecting_peers.lock().await;
            if connecting.contains(&peer_id) {
                return;
            }
            connecting.insert(peer_id.clone());
        }

        let connecting_peers = Arc::clone(&self.connecting_peers);
        let tofu_store = self.tofu_store.clone();
        let file_transfer_mgr = Arc::clone(&self.file_transfer_mgr);
        let notification_mgr = Arc::clone(&self.notification_mgr);
        let input_mgr = Arc::clone(&self.input_mgr);
        let telephony_mgr = Arc::clone(&self.telephony_mgr);
        let media_mgr = Arc::clone(&self.media_mgr);
        let camera_mgr = Arc::clone(&self.camera_mgr);

        tokio::spawn(async move {
            let mut connected = false;
            for addr in &device.addresses {
                info!("Attempting outbound mTLS connection to {} at {}", device.device_name, addr);
                match TcpStream::connect(addr).await {
                    Ok(tcp_stream) => {
                        let _ = tcp_stream.set_nodelay(true);
                        let connector = TlsConnector::from(Arc::clone(&client_config));
                        let server_name = rustls_pki_types::ServerName::try_from(
                            device.device_name.clone(),
                        )
                        .unwrap_or_else(|_| {
                            rustls_pki_types::ServerName::try_from("localhost".to_string()).unwrap()
                        });

                        match connector.connect(server_name, tcp_stream).await {
                            Ok(tls_stream) => {
                                info!("Outbound TLS connection established to {}", device.device_name);
                                connected = true;
                                if let Err(e) = run_peer_connection(
                                    tls_stream,
                                    my_info.clone(),
                                    true,
                                    clip_tx.clone(),
                                    peer_tx.clone(),
                                    tofu_store.clone(),
                                    Arc::clone(&file_transfer_mgr),
                                    Arc::clone(&notification_mgr),
                                    Arc::clone(&input_mgr),
                                    Arc::clone(&telephony_mgr),
                                    Arc::clone(&media_mgr),
                                    Arc::clone(&camera_mgr),
                                )
                                .await
                                {
                                    warn!("Connection with {} error: {}", device.device_name, e);
                                }
                                break;
                            }
                            Err(e) => {
                                warn!("TLS handshake failed to {}: {}", addr, e);
                            }
                        }
                    }
                    Err(e) => {
                        debug!("Failed to connect to {}: {}", addr, e);
                    }
                }
            }

            if !connected {
                warn!("Could not connect to peer {} at any advertised address", device.device_name);
            }

            let mut connecting = connecting_peers.lock().await;
            connecting.remove(&peer_id);
        });
    }

    pub async fn broadcast_clipboard_item(&self, item: ClipboardItem) {
        let payload = ClipboardPayload {
            timestamp_ms: item.timestamp_ms,
            mime_type: item.mime_type,
            data: item.data,
            content_hash: item.content_hash,
            source_device_id: self.identity.config.device_id.clone(),
        };

        let mut payload_bytes = Vec::new();
        if let Err(e) = payload.encode(&mut payload_bytes) {
            error!("Failed to encode ClipboardPayload: {}", e);
            return;
        }

        let seq = self.sequence_number.fetch_add(1, Ordering::SeqCst);
        let packet = Packet {
            session_id: self.identity.config.device_id.clone(),
            sequence_number: seq,
            channel: Channel::Clipboard as i32,
            payload: payload_bytes,
        };

        let peers = self.active_peers.read().await;
        if peers.is_empty() {
            debug!("No active peers connected; clipboard update stored locally only");
            return;
        }

        info!("Broadcasting clipboard update to {} connected peer(s)", peers.len());
        for (peer_id, tx) in peers.iter() {
            if let Err(e) = tx.send(packet.clone()).await {
                warn!("Failed to queue clipboard packet to peer {}: {}", peer_id, e);
            }
        }
    }

    pub async fn broadcast_notification(&self, title: &str, body: &str, app_name: &str) {
        let packet = self.notification_mgr.create_alert_packet(title, body, app_name);
        let peers = self.active_peers.read().await;
        for (peer_id, tx) in peers.iter() {
            if let Err(e) = tx.send(packet.clone()).await {
                warn!("Failed to send notification alert to peer {}: {}", peer_id, e);
            }
        }
    }

    pub async fn send_file_to_peer(
        &self,
        file_path: &Path,
        target_peer_id: Option<&str>,
    ) -> Result<String, Box<dyn std::error::Error + Send + Sync>> {
        let peers = self.active_peers.read().await;
        let peer_tx = match target_peer_id {
            Some(id) => peers.get(id).cloned().ok_or_else(|| {
                format!("Peer ID '{}' is not currently connected", id)
            })?,
            None => {
                if peers.is_empty() {
                    return Err("No active peer connected to receive file".into());
                }
                peers.values().next().unwrap().clone()
            }
        };
        drop(peers);

        self.file_transfer_mgr.send_file(file_path, peer_tx).await
    }
}
