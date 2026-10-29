use std::io::ErrorKind;
use std::sync::Arc;
use prost::Message;
use tokio::io::{AsyncRead, AsyncWrite};
use tokio::sync::mpsc;
use tracing::{debug, error, info, warn};

use crate::camera::CameraManager;
use crate::input::InputManager;
use crate::media::MediaManager;
use crate::notifications::NotificationManager;
use crate::proto::{
    CameraMessage, Channel, ClipboardPayload, FileTransferMessage, HandshakeRequest,
    HandshakeResponse, InputMessage, MediaMessage, NotificationMessage, Packet, TelephonyMessage,
};
use crate::telephony::TelephonyManager;
use crate::transfer::FileTransferManager;
use crate::transport::framing::PacketFramed;
use crate::transport::tofu::TofuStore;

#[derive(Debug, Clone)]
pub struct PeerInfo {
    pub device_id: String,
    pub device_name: String,
    pub cert_fingerprint: String,
}

#[derive(Debug)]
pub struct PeerSession {
    pub info: PeerInfo,
    pub tx: mpsc::Sender<Packet>,
}

#[derive(Debug)]
pub enum PeerSessionEvent {
    Register(PeerSession),
    Unregister(String),
}

#[allow(clippy::too_many_arguments)]
pub async fn run_peer_connection<S>(
    stream: S,
    my_info: PeerInfo,
    is_initiator: bool,
    expected_peer_fingerprint: Option<String>,
    clipboard_tx: mpsc::Sender<ClipboardPayload>,
    register_peer_tx: mpsc::Sender<PeerSessionEvent>,
    tofu_store: TofuStore,
    file_transfer_mgr: Arc<FileTransferManager>,
    notification_mgr: Arc<NotificationManager>,
    input_mgr: Arc<InputManager>,
    telephony_mgr: Arc<TelephonyManager>,
    media_mgr: Arc<MediaManager>,
    camera_mgr: Arc<CameraManager>,
) -> Result<(), Box<dyn std::error::Error + Send + Sync>>
where
    S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    let mut framed = PacketFramed::new(stream);
    let mut sequence_number: u64 = 0;

    let my_handshake_req = HandshakeRequest {
        device_name: my_info.device_name.clone(),
        device_id: my_info.device_id.clone(),
        cert_fingerprint: my_info.cert_fingerprint.clone(),
        protocol_version: 1,
    };

    let mut req_bytes = Vec::new();
    my_handshake_req.encode(&mut req_bytes)?;

    let mut peer_info = PeerInfo {
        device_id: String::new(),
        device_name: String::new(),
        cert_fingerprint: String::new(),
    };

    if is_initiator {
        // Send handshake request
        sequence_number += 1;
        let packet = Packet {
            session_id: my_info.device_id.clone(),
            sequence_number,
            channel: Channel::Handshake as i32,
            payload: req_bytes,
        };
        framed.send_packet(&packet).await?;

        // Wait for handshake response
        let resp_packet = framed
            .next_packet()
            .await
            .ok_or_else(|| std::io::Error::new(ErrorKind::UnexpectedEof, "Connection closed during handshake"))??;

        let response = HandshakeResponse::decode(&resp_packet.payload[..])?;
        if response.status() != crate::proto::handshake_response::Status::Accepted {
            return Err(format!("Peer rejected handshake: {}", response.message).into());
        }

        peer_info.device_id = response.device_id;
        peer_info.device_name = response.device_name;
        peer_info.cert_fingerprint = response.cert_fingerprint;
    } else {
        // Wait for handshake request
        let req_packet = framed
            .next_packet()
            .await
            .ok_or_else(|| std::io::Error::new(ErrorKind::UnexpectedEof, "Connection closed waiting for handshake"))??;

        let request = HandshakeRequest::decode(&req_packet.payload[..])?;
        peer_info.device_id = request.device_id;
        peer_info.device_name = request.device_name;
        peer_info.cert_fingerprint = request.cert_fingerprint;

        // Send handshake response
        let handshake_resp = HandshakeResponse {
            status: crate::proto::handshake_response::Status::Accepted as i32,
            device_name: my_info.device_name.clone(),
            device_id: my_info.device_id.clone(),
            cert_fingerprint: my_info.cert_fingerprint.clone(),
            message: "OK".to_string(),
        };

        let mut resp_bytes = Vec::new();
        handshake_resp.encode(&mut resp_bytes)?;

        sequence_number += 1;
        let packet = Packet {
            session_id: my_info.device_id.clone(),
            sequence_number,
            channel: Channel::Handshake as i32,
            payload: resp_bytes,
        };
        framed.send_packet(&packet).await?;
    }

    if let Some(ref expected_fp) = expected_peer_fingerprint {
        if !expected_fp.eq_ignore_ascii_case(&peer_info.cert_fingerprint) {
            return Err(format!(
                "Handshake fingerprint mismatch: presented TLS cert {}, but claims {} in handshake payload!",
                expected_fp, peer_info.cert_fingerprint
            ).into());
        }
    }

    let sas_code = crate::transport::tofu::calculate_sas_code(
        &my_info.cert_fingerprint,
        &peer_info.cert_fingerprint,
    );

    info!(
        "Handshake completed successfully with peer '{}' ({}). SAS Security Verification Code: [ {} ]",
        peer_info.device_name, peer_info.device_id, sas_code
    );

    #[cfg(target_os = "linux")]
    {
        let dev_name = peer_info.device_name.clone();
        let code = sas_code.clone();
        tokio::spawn(async move {
            let mut notification = notify_rust::Notification::new();
            notification
                .appname("Continuity Hub")
                .summary("Device Connected")
                .body(&format!("Connected to '{}'\nSecurity Code: {}", dev_name, code))
                .icon("security-high")
                .timeout(notify_rust::Timeout::Milliseconds(7000));
            let _ = notification.show_async().await;
        });
    }

    tofu_store.update_device_info(
        &peer_info.cert_fingerprint,
        &peer_info.device_id,
        &peer_info.device_name,
    );

    let (out_tx, mut out_rx) = mpsc::channel::<Packet>(64);
    let session = PeerSession {
        info: peer_info.clone(),
        tx: out_tx.clone(),
    };

    if register_peer_tx.send(PeerSessionEvent::Register(session)).await.is_err() {
        return Ok(());
    }

    // Bidirectional transport loop
    loop {
        tokio::select! {
            // Outbound packets from sync engine
            Some(out_packet) = out_rx.recv() => {
                if let Err(e) = framed.send_packet(&out_packet).await {
                    error!("Error sending packet to peer {}: {}", peer_info.device_name, e);
                    break;
                }
            }
            // Inbound packets from peer
            in_result = framed.next_packet() => {
                match in_result {
                    Some(Ok(in_packet)) => {
                        match Channel::try_from(in_packet.channel) {
                            Ok(Channel::Clipboard) => {
                                match ClipboardPayload::decode(&in_packet.payload[..]) {
                                    Ok(payload) => {
                                        let _ = clipboard_tx.send(payload).await;
                                    }
                                    Err(e) => {
                                        warn!("Failed to decode ClipboardPayload from peer {}: {}", peer_info.device_name, e);
                                    }
                                }
                            }
                            Ok(Channel::FileTransfer) => {
                                match FileTransferMessage::decode(&in_packet.payload[..]) {
                                    Ok(msg) => {
                                        if let Some(reply) = file_transfer_mgr
                                            .handle_message(msg, &peer_info.device_id, &peer_info.device_name)
                                            .await
                                        {
                                            if let Err(e) = framed.send_packet(&reply).await {
                                                error!("Failed to send file transfer reply: {}", e);
                                            }
                                        }
                                    }
                                    Err(e) => {
                                        warn!("Failed to decode FileTransferMessage from peer {}: {}", peer_info.device_name, e);
                                    }
                                }
                            }
                            Ok(Channel::Notifications) => {
                                match NotificationMessage::decode(&in_packet.payload[..]) {
                                    Ok(msg) => {
                                        if let Some(reply) = notification_mgr
                                            .handle_message(msg, &peer_info.device_name, out_tx.clone())
                                            .await
                                        {
                                            if let Err(e) = framed.send_packet(&reply).await {
                                                error!("Failed to send notification reply: {}", e);
                                            }
                                        }
                                    }
                                    Err(e) => {
                                        warn!("Failed to decode NotificationMessage from peer {}: {}", peer_info.device_name, e);
                                    }
                                }
                            }
                            Ok(Channel::Input) => {
                                match InputMessage::decode(&in_packet.payload[..]) {
                                    Ok(msg) => {
                                        input_mgr.handle_message(msg).await;
                                    }
                                    Err(e) => {
                                        warn!("Failed to decode InputMessage from peer {}: {}", peer_info.device_name, e);
                                    }
                                }
                            }
                            Ok(Channel::Telephony) => {
                                match TelephonyMessage::decode(&in_packet.payload[..]) {
                                    Ok(msg) => {
                                        if let Some(reply) = telephony_mgr
                                            .handle_message(msg, &peer_info.device_name, out_tx.clone())
                                            .await
                                        {
                                            if let Err(e) = framed.send_packet(&reply).await {
                                                error!("Failed to send telephony reply: {}", e);
                                            }
                                        }
                                    }
                                    Err(e) => {
                                        warn!("Failed to decode TelephonyMessage from peer {}: {}", peer_info.device_name, e);
                                    }
                                }
                            }
                            Ok(Channel::Media) => {
                                match MediaMessage::decode(&in_packet.payload[..]) {
                                    Ok(msg) => {
                                        if let Some(reply) = media_mgr
                                            .handle_message(msg, &peer_info.device_name, out_tx.clone())
                                            .await
                                        {
                                            if let Err(e) = framed.send_packet(&reply).await {
                                                error!("Failed to send media reply: {}", e);
                                            }
                                        }
                                    }
                                    Err(e) => {
                                        warn!("Failed to decode MediaMessage from peer {}: {}", peer_info.device_name, e);
                                    }
                                }
                            }
                            Ok(Channel::Camera) => {
                                match CameraMessage::decode(&in_packet.payload[..]) {
                                    Ok(msg) => {
                                        if let Some(reply) = camera_mgr
                                            .handle_message(msg, &peer_info.device_name, out_tx.clone())
                                            .await
                                        {
                                            if let Err(e) = framed.send_packet(&reply).await {
                                                error!("Failed to send camera reply: {}", e);
                                            }
                                        }
                                    }
                                    Err(e) => {
                                        warn!("Failed to decode CameraMessage from peer {}: {}", peer_info.device_name, e);
                                    }
                                }
                            }
                            Ok(Channel::Heartbeat) => {
                                debug!("Received heartbeat from peer {}", peer_info.device_name);
                            }
                            _ => {
                                debug!("Unhandled channel {} from peer {}", in_packet.channel, peer_info.device_name);
                            }
                        }
                    }
                    Some(Err(e)) => {
                        warn!("Error reading packet from peer {}: {}", peer_info.device_name, e);
                        break;
                    }
                    None => {
                        info!("Peer {} disconnected", peer_info.device_name);
                        break;
                    }
                }
            }
        }
    }

    info!("Peer '{}' ({}) connection terminated, unregistering from active sessions", peer_info.device_name, peer_info.device_id);
    let _ = register_peer_tx.send(PeerSessionEvent::Unregister(peer_info.device_id)).await;

    Ok(())
}
