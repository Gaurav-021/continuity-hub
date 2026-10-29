use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use prost::Message;
use sha2::{Digest, Sha256};
use tokio::fs::{self, File, OpenOptions};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::sync::{mpsc, Mutex, RwLock};
use tracing::{debug, error, info, warn};
use uuid::Uuid;

use crate::proto::{
    file_transfer_ack::Status as AckStatus,
    file_transfer_message::Msg,
    Channel, FileChunk, FileTransferAck, FileTransferHeader, FileTransferMessage, Packet,
};

pub const DEFAULT_CHUNK_SIZE: usize = 64 * 1024; // 64 KB

pub fn format_size(bytes: u64) -> String {
    const KB: u64 = 1024;
    const MB: u64 = KB * 1024;
    const GB: u64 = MB * 1024;

    if bytes >= GB {
        format!("{:.2} GB", bytes as f64 / GB as f64)
    } else if bytes >= MB {
        format!("{:.2} MB", bytes as f64 / MB as f64)
    } else if bytes >= KB {
        format!("{:.1} KB", bytes as f64 / KB as f64)
    } else {
        format!("{} B", bytes)
    }
}

pub fn sanitize_filename(name: &str) -> String {
    let file_name = Path::new(name)
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("received_file");

    let sanitized: String = file_name
        .chars()
        .map(|c| if c == '/' || c == '\\' || c == '\0' { '_' } else { c })
        .collect();

    if sanitized.is_empty() {
        "received_file".to_string()
    } else {
        sanitized
    }
}

pub fn show_file_received_notification(
    file_name: &str,
    file_size: u64,
    sender_name: &str,
    file_path: &Path,
) {
    let size_str = format_size(file_size);
    let summary = format!("Received '{}' from {}", file_name, sender_name);
    let body = format!("Size: {}\nSaved to: {}", size_str, file_path.display());

    #[cfg(target_os = "linux")]
    {
        let res = notify_rust::Notification::new()
            .appname("Continuity Hub")
            .summary(&summary)
            .body(&body)
            .icon("document-save")
            .timeout(notify_rust::Timeout::Milliseconds(6000))
            .show();

        if res.is_err() {
            let _ = std::process::Command::new("notify-send")
                .arg("-a")
                .arg("Continuity Hub")
                .arg(&summary)
                .arg(&body)
                .spawn();
        }
    }

    #[cfg(not(target_os = "linux"))]
    {
        tracing::info!("Continuity File Transfer: {} - {}", summary, body);
    }
}

struct ActiveIncomingTransfer {
    header: FileTransferHeader,
    file: File,
    part_path: PathBuf,
    final_path: PathBuf,
    hasher: Sha256,
    bytes_received: u64,
    sender_name: String,
}

#[derive(Debug, Clone)]
pub struct TransferProgress {
    pub transfer_id: String,
    pub file_name: String,
    pub bytes_transferred: u64,
    pub total_bytes: u64,
    pub is_completed: bool,
    pub error: Option<String>,
}

pub struct FileTransferManager {
    download_dir: PathBuf,
    my_device_id: String,
    sequence_number: AtomicU64,
    incoming: Arc<Mutex<HashMap<String, ActiveIncomingTransfer>>>,
    outgoing_ack_tx: Arc<RwLock<HashMap<String, mpsc::Sender<FileTransferAck>>>>,
}

impl FileTransferManager {
    pub fn new(download_dir: Option<PathBuf>, my_device_id: String) -> Self {
        let dir = download_dir.unwrap_or_else(|| {
            let home = std::env::var("HOME").unwrap_or_else(|_| ".".to_string());
            PathBuf::from(home).join("Downloads").join("Continuity")
        });

        Self {
            download_dir: dir,
            my_device_id,
            sequence_number: AtomicU64::new(0),
            incoming: Arc::new(Mutex::new(HashMap::new())),
            outgoing_ack_tx: Arc::new(RwLock::new(HashMap::new())),
        }
    }

    pub fn download_dir(&self) -> &Path {
        &self.download_dir
    }

    fn wrap_packet(&self, msg: FileTransferMessage) -> Packet {
        let mut bytes = Vec::new();
        msg.encode(&mut bytes).unwrap();
        Packet {
            session_id: self.my_device_id.clone(),
            sequence_number: self.sequence_number.fetch_add(1, Ordering::SeqCst),
            channel: Channel::FileTransfer as i32,
            payload: bytes,
        }
    }

    pub async fn handle_message(
        &self,
        msg: FileTransferMessage,
        _peer_id: &str,
        peer_name: &str,
    ) -> Option<Packet> {
        match msg.msg? {
            Msg::Header(header) => {
                debug!(
                    "Incoming FileTransferHeader for '{}' ({} bytes, transfer_id: {}) from {}",
                    header.file_name, header.file_size, header.transfer_id, peer_name
                );

                if let Err(e) = fs::create_dir_all(&self.download_dir).await {
                    error!("Failed to create downloads directory: {}", e);
                    let ack = FileTransferAck {
                        transfer_id: header.transfer_id,
                        status: AckStatus::Rejected as i32,
                        last_received_chunk: 0,
                        message: format!("Cannot create downloads directory: {}", e),
                    };
                    return Some(self.wrap_packet(FileTransferMessage { msg: Some(Msg::Ack(ack)) }));
                }

                let safe_name = sanitize_filename(&header.file_name);
                let mut final_path = self.download_dir.join(&safe_name);
                if final_path.exists() {
                    let stem = Path::new(&safe_name)
                        .file_stem()
                        .and_then(|s| s.to_str())
                        .unwrap_or("file");
                    let ext = Path::new(&safe_name)
                        .extension()
                        .and_then(|e| e.to_str())
                        .map(|e| format!(".{}", e))
                        .unwrap_or_default();
                    let mut counter = 1;
                    while final_path.exists() {
                        final_path = self.download_dir.join(format!("{} ({}){}", stem, counter, ext));
                        counter += 1;
                    }
                }
                let part_name = format!("{}.{}.part", safe_name, Uuid::new_v4());
                let part_path = self.download_dir.join(part_name);

                match OpenOptions::new()
                    .create(true)
                    .write(true)
                    .truncate(true)
                    .open(&part_path)
                    .await
                {
                    Ok(file) => {
                        let incoming_entry = ActiveIncomingTransfer {
                            header: header.clone(),
                            file,
                            part_path,
                            final_path,
                            hasher: Sha256::new(),
                            bytes_received: 0,
                            sender_name: peer_name.to_string(),
                        };

                        self.incoming
                            .lock()
                            .await
                            .insert(header.transfer_id.clone(), incoming_entry);

                        info!(
                            "Accepted file transfer for '{}' ({}) from {}",
                            safe_name,
                            format_size(header.file_size),
                            peer_name
                        );

                        let ack = FileTransferAck {
                            transfer_id: header.transfer_id,
                            status: AckStatus::Accepted as i32,
                            last_received_chunk: 0,
                            message: "Ready for chunks".to_string(),
                        };
                        Some(self.wrap_packet(FileTransferMessage { msg: Some(Msg::Ack(ack)) }))
                    }
                    Err(e) => {
                        error!("Failed to create part file for transfer: {}", e);
                        let ack = FileTransferAck {
                            transfer_id: header.transfer_id,
                            status: AckStatus::Rejected as i32,
                            last_received_chunk: 0,
                            message: format!("Failed to create part file: {}", e),
                        };
                        Some(self.wrap_packet(FileTransferMessage { msg: Some(Msg::Ack(ack)) }))
                    }
                }
            }

            Msg::Chunk(chunk) => {
                let mut incoming_map = self.incoming.lock().await;
                if let Some(transfer) = incoming_map.get_mut(&chunk.transfer_id) {
                    if let Err(e) = transfer.file.write_all(&chunk.data).await {
                        error!("Error writing chunk to part file: {}", e);
                        let ack = FileTransferAck {
                            transfer_id: chunk.transfer_id.clone(),
                            status: AckStatus::Cancelled as i32,
                            last_received_chunk: chunk.chunk_index,
                            message: format!("Disk write error: {}", e),
                        };
                        incoming_map.remove(&chunk.transfer_id);
                        return Some(self.wrap_packet(FileTransferMessage { msg: Some(Msg::Ack(ack)) }));
                    }

                    transfer.hasher.update(&chunk.data);
                    transfer.bytes_received += chunk.data.len() as u64;

                    if chunk.is_last_chunk {
                        let _ = transfer.file.flush().await;
                        let hasher = std::mem::replace(&mut transfer.hasher, Sha256::new());
                        let calculated_checksum = hex::encode(hasher.finalize());

                        if calculated_checksum.eq_ignore_ascii_case(&transfer.header.sha256_checksum) {
                            // Checksum verified! Atomic rename with copy fallback across mount boundaries
                            let rename_result = match fs::rename(&transfer.part_path, &transfer.final_path).await {
                                Ok(()) => Ok(()),
                                Err(e) => {
                                    debug!("fs::rename failed ({}), falling back to copy & remove", e);
                                    if fs::copy(&transfer.part_path, &transfer.final_path).await.is_ok() {
                                        let _ = fs::remove_file(&transfer.part_path).await;
                                        Ok(())
                                    } else {
                                        Err(e)
                                    }
                                }
                            };

                            if let Err(e) = rename_result {
                                error!("Failed to rename completed part file: {}", e);
                                let ack = FileTransferAck {
                                    transfer_id: chunk.transfer_id.clone(),
                                    status: AckStatus::Cancelled as i32,
                                    last_received_chunk: chunk.chunk_index,
                                    message: format!("Failed saving completed file: {}", e),
                                };
                                incoming_map.remove(&chunk.transfer_id);
                                return Some(self.wrap_packet(FileTransferMessage { msg: Some(Msg::Ack(ack)) }));
                            }

                            info!(
                                "File transfer COMPLETED successfully: '{}' ({} bytes, checksum verified) from {}",
                                transfer.header.file_name, transfer.bytes_received, transfer.sender_name
                            );

                            show_file_received_notification(
                                &transfer.header.file_name,
                                transfer.bytes_received,
                                &transfer.sender_name,
                                &transfer.final_path,
                            );

                            let ack = FileTransferAck {
                                transfer_id: chunk.transfer_id.clone(),
                                status: AckStatus::Completed as i32,
                                last_received_chunk: chunk.chunk_index,
                                message: "File received and verified successfully".to_string(),
                            };
                            incoming_map.remove(&chunk.transfer_id);
                            Some(self.wrap_packet(FileTransferMessage { msg: Some(Msg::Ack(ack)) }))
                        } else {
                            error!(
                                "Checksum verification FAILED for '{}'! Expected: {}, Got: {}",
                                transfer.header.file_name,
                                transfer.header.sha256_checksum,
                                calculated_checksum
                            );
                            let _ = fs::remove_file(&transfer.part_path).await;
                            let ack = FileTransferAck {
                                transfer_id: chunk.transfer_id.clone(),
                                status: AckStatus::ChecksumFailed as i32,
                                last_received_chunk: chunk.chunk_index,
                                message: "SHA-256 checksum mismatch".to_string(),
                            };
                            incoming_map.remove(&chunk.transfer_id);
                            Some(self.wrap_packet(FileTransferMessage { msg: Some(Msg::Ack(ack)) }))
                        }
                    } else {
                        // Periodic ack or silently continue streaming
                        None
                    }
                } else {
                    warn!("Received chunk for unknown transfer_id: {}", chunk.transfer_id);
                    None
                }
            }

            Msg::Ack(ack) => {
                debug!(
                    "Received FileTransferAck for transfer_id: {} (status: {:?})",
                    ack.transfer_id, ack.status()
                );
                let map = self.outgoing_ack_tx.read().await;
                if let Some(tx) = map.get(&ack.transfer_id) {
                    let _ = tx.send(ack).await;
                }
                None
            }
        }
    }

    pub async fn send_file(
        &self,
        file_path: &Path,
        peer_tx: mpsc::Sender<Packet>,
    ) -> Result<String, Box<dyn std::error::Error + Send + Sync>> {
        if !file_path.exists() {
            return Err(format!("File does not exist: {:?}", file_path).into());
        }

        let metadata = fs::metadata(file_path).await?;
        let file_size = metadata.len();
        let file_name = file_path
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or("file")
            .to_string();

        info!(
            "Preparing to send file: '{}' ({})",
            file_name,
            format_size(file_size)
        );

        // 1. Calculate SHA-256
        let mut file = File::open(file_path).await?;
        let mut hasher = Sha256::new();
        let mut buffer = vec![0u8; DEFAULT_CHUNK_SIZE];
        loop {
            let n = file.read(&mut buffer).await?;
            if n == 0 {
                break;
            }
            hasher.update(&buffer[..n]);
        }
        let sha256_checksum = hex::encode(hasher.finalize());

        let total_chunks = if file_size == 0 {
            1
        } else {
            file_size.div_ceil(DEFAULT_CHUNK_SIZE as u64)
        };

        let transfer_id = Uuid::new_v4().to_string();

        let header = FileTransferHeader {
            transfer_id: transfer_id.clone(),
            file_name: file_name.clone(),
            file_size,
            mime_type: "application/octet-stream".to_string(),
            total_chunks,
            chunk_size: DEFAULT_CHUNK_SIZE as u32,
            sha256_checksum,
            source_device_id: self.my_device_id.clone(),
        };

        let (ack_tx, mut ack_rx) = mpsc::channel::<FileTransferAck>(16);
        {
            let mut map = self.outgoing_ack_tx.write().await;
            map.insert(transfer_id.clone(), ack_tx);
        }

        // Send Header
        let header_packet = self.wrap_packet(FileTransferMessage {
            msg: Some(Msg::Header(header)),
        });
        peer_tx.send(header_packet).await?;

        // Wait for Accept Ack
        let ack = match tokio::time::timeout(std::time::Duration::from_secs(10), ack_rx.recv()).await {
            Ok(Some(a)) => a,
            _ => {
                self.outgoing_ack_tx.write().await.remove(&transfer_id);
                return Err("Timeout waiting for peer acceptance of file transfer".into());
            }
        };

        if ack.status() != AckStatus::Accepted {
            self.outgoing_ack_tx.write().await.remove(&transfer_id);
            return Err(format!("Peer rejected file transfer: {}", ack.message).into());
        }

        info!("Peer accepted file transfer. Streaming chunks...");

        // Stream chunks
        let mut file = File::open(file_path).await?;
        let mut chunk_index: u64 = 0;
        let mut offset: u64 = 0;

        loop {
            let n = file.read(&mut buffer).await?;
            let is_last = (offset + n as u64 >= file_size) || n == 0;

            let chunk = FileChunk {
                transfer_id: transfer_id.clone(),
                chunk_index,
                offset,
                data: buffer[..n].to_vec(),
                is_last_chunk: is_last,
                chunk_hash: String::new(),
            };

            let packet = self.wrap_packet(FileTransferMessage {
                msg: Some(Msg::Chunk(chunk)),
            });
            peer_tx.send(packet).await?;

            offset += n as u64;
            chunk_index += 1;

            if is_last {
                break;
            }
        }

        // Wait for Final Completion Ack
        let timeout_duration = std::cmp::max(
            std::time::Duration::from_secs(30),
            std::time::Duration::from_secs(file_size / (512 * 1024) + 15),
        );
        let completion_ack = match tokio::time::timeout(timeout_duration, ack_rx.recv()).await {
            Ok(Some(a)) => a,
            _ => {
                self.outgoing_ack_tx.write().await.remove(&transfer_id);
                return Err("Timeout waiting for completion acknowledgement".into());
            }
        };

        self.outgoing_ack_tx.write().await.remove(&transfer_id);

        if completion_ack.status() == AckStatus::Completed {
            info!("File '{}' successfully delivered and verified by peer!", file_name);
            Ok(transfer_id)
        } else {
            Err(format!("File transfer failed: {:?}", completion_ack.status()).into())
        }
    }
}
