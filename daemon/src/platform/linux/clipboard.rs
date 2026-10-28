use std::collections::VecDeque;
use std::path::PathBuf;
use std::process::Stdio;
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use async_trait::async_trait;
use sha2::{Digest, Sha256};
use tokio::fs::OpenOptions;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::process::Command;
use tokio::sync::{mpsc, Mutex};
use tracing::{debug, error, info, warn};
use uuid::Uuid;

use crate::platform::traits::{ClipboardBackend, ClipboardError, ClipboardItem};

pub fn hash_data(data: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(data);
    hex::encode(hasher.finalize())
}

pub fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

#[derive(Debug)]
struct DedupCache {
    capacity: usize,
    entries: VecDeque<(String, u64)>, // (hash, timestamp_ms)
}

impl DedupCache {
    fn new(capacity: usize) -> Self {
        Self {
            capacity,
            entries: VecDeque::with_capacity(capacity),
        }
    }

    fn contains(&self, hash: &str) -> bool {
        self.entries.iter().any(|(h, _)| h == hash)
    }

    fn insert(&mut self, hash: String, ts: u64) {
        if self.contains(&hash) {
            return;
        }
        if self.entries.len() >= self.capacity {
            self.entries.pop_front();
        }
        self.entries.push_back((hash, ts));
    }
}

struct FifoCleanup(PathBuf);
impl Drop for FifoCleanup {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
    }
}

pub struct WaylandClipboardBackend {
    dedup: Arc<Mutex<DedupCache>>,
}

impl Default for WaylandClipboardBackend {
    fn default() -> Self {
        Self::new()
    }
}

impl WaylandClipboardBackend {
    pub fn new() -> Self {
        Self {
            dedup: Arc::new(Mutex::new(DedupCache::new(64))),
        }
    }

    pub async fn mark_seen(&self, hash: &str) {
        let mut cache = self.dedup.lock().await;
        cache.insert(hash.to_string(), now_ms());
    }

    async fn detect_mime_type(&self) -> String {
        let output = Command::new("wl-paste")
            .arg("--list-types")
            .output()
            .await;

        match output {
            Ok(out) if out.status.success() => {
                let text = String::from_utf8_lossy(&out.stdout);
                let types: Vec<&str> = text.lines().map(|s| s.trim()).collect();

                // Prefer common image types if offered
                if types.contains(&"image/png") {
                    return "image/png".to_string();
                } else if types.contains(&"image/jpeg") {
                    return "image/jpeg".to_string();
                }

                // Default to UTF-8 text
                for t in &types {
                    if t.starts_with("text/plain") {
                        return t.to_string();
                    }
                }
                "text/plain".to_string()
            }
            _ => "text/plain".to_string(),
        }
    }
}

#[async_trait]
impl ClipboardBackend for WaylandClipboardBackend {
    async fn get_content(&self) -> Result<ClipboardItem, ClipboardError> {
        let mime_type = self.detect_mime_type().await;
        let mut cmd = Command::new("wl-paste");
        cmd.arg("--no-newline");
        cmd.arg("--type").arg(&mime_type);

        let output = cmd.output().await.map_err(|e| {
            ClipboardError::ReadError(format!("Failed to execute wl-paste: {}", e))
        })?;

        if !output.status.success() {
            let err = String::from_utf8_lossy(&output.stderr);
            return Err(ClipboardError::ReadError(format!(
                "wl-paste returned error code: {}",
                err
            )));
        }

        let data = output.stdout;
        let content_hash = hash_data(&data);
        let timestamp_ms = now_ms();

        Ok(ClipboardItem {
            mime_type,
            data,
            timestamp_ms,
            content_hash,
        })
    }

    async fn set_content(&self, item: &ClipboardItem) -> Result<(), ClipboardError> {
        // Mark hash in deduplication cache before updating compositor
        self.mark_seen(&item.content_hash).await;

        let mut child = Command::new("wl-copy")
            .arg("--type")
            .arg(&item.mime_type)
            .stdin(Stdio::piped())
            .stdout(Stdio::null())
            .stderr(Stdio::piped())
            .spawn()
            .map_err(|e| {
                ClipboardError::WriteError(format!("Failed to spawn wl-copy: {}", e))
            })?;

        if let Some(mut stdin) = child.stdin.take() {
            stdin.write_all(&item.data).await.map_err(|e| {
                ClipboardError::WriteError(format!("Failed to write data to wl-copy stdin: {}", e))
            })?;
            stdin.flush().await.map_err(|e| {
                ClipboardError::WriteError(format!("Failed to flush wl-copy stdin: {}", e))
            })?;
            drop(stdin);
        }

        let status = child.wait().await.map_err(|e| {
            ClipboardError::WriteError(format!("Failed waiting for wl-copy: {}", e))
        })?;

        if !status.success() {
            return Err(ClipboardError::WriteError(
                "wl-copy finished with non-zero exit code".to_string(),
            ));
        }

        debug!(
            "WaylandClipboardBackend: Successfully updated system clipboard with {} bytes (hash: {})",
            item.data.len(),
            item.content_hash
        );
        Ok(())
    }

    async fn watch(&self) -> Result<mpsc::Receiver<ClipboardItem>, ClipboardError> {
        let (tx, rx) = mpsc::channel(32);
        let dedup = Arc::clone(&self.dedup);

        let runtime_dir = std::env::var("XDG_RUNTIME_DIR").unwrap_or_else(|_| "/tmp".to_string());
        let fifo_name = format!("continuity-clip-{}.fifo", Uuid::new_v4());
        let fifo_path = PathBuf::from(runtime_dir).join(fifo_name);

        // Create FIFO for non-blocking notification
        let c_fifo_path = std::ffi::CString::new(fifo_path.to_str().unwrap()).map_err(|e| {
            ClipboardError::WatchError(format!("Invalid path for FIFO: {}", e))
        })?;

        unsafe {
            if libc::mkfifo(c_fifo_path.as_ptr(), 0o600) != 0 {
                return Err(ClipboardError::WatchError(
                    std::io::Error::last_os_error().to_string(),
                ));
            }
        }

        tokio::spawn(async move {
            let _fifo_guard = FifoCleanup(fifo_path.clone());
            let mut watch_child = match Command::new("wl-paste")
                .arg("--watch")
                .arg("sh")
                .arg("-c")
                .arg(format!("echo NOTIFY > \"{}\" 2>/dev/null", fifo_path.display()))
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .spawn()
            {
                Ok(child) => child,
                Err(e) => {
                    error!("Failed to spawn wl-paste --watch: {}", e);
                    let _ = tokio::fs::remove_file(&fifo_path).await;
                    return;
                }
            };

            let backend = WaylandClipboardBackend {
                dedup: Arc::clone(&dedup),
            };

            info!("Wayland clipboard watcher started on {:?}", fifo_path);

            loop {
                // Open FIFO for reading in read-write mode so it doesn't close on EOF
                let file = match OpenOptions::new()
                    .read(true)
                    .write(true) // prevent EOF when writer disconnects
                    .open(&fifo_path)
                    .await
                {
                    Ok(f) => f,
                    Err(e) => {
                        error!("Failed to open notification FIFO: {}", e);
                        tokio::time::sleep(Duration::from_millis(500)).await;
                        continue;
                    }
                };

                let mut reader = BufReader::new(file);
                let mut line = String::new();

                while let Ok(n) = reader.read_line(&mut line).await {
                    if n == 0 {
                        break;
                    }
                    line.clear();

                    // Debounce tiny bursts (5ms)
                    tokio::time::sleep(Duration::from_millis(5)).await;

                    match backend.get_content().await {
                        Ok(item) => {
                            let mut cache = dedup.lock().await;
                            if cache.contains(&item.content_hash) {
                                debug!(
                                    "Wayland watch: Suppressing duplicate content (hash: {})",
                                    item.content_hash
                                );
                            } else {
                                cache.insert(item.content_hash.clone(), item.timestamp_ms);
                                drop(cache);
                                debug!(
                                    "Wayland watch: New content detected (hash: {}, size: {} bytes)",
                                    item.content_hash,
                                    item.data.len()
                                );
                                if tx.send(item).await.is_err() {
                                    info!("Clipboard receiver closed, exiting watcher loop");
                                    let _ = watch_child.kill().await;
                                    let _ = tokio::fs::remove_file(&fifo_path).await;
                                    return;
                                }
                            }
                        }
                        Err(e) => {
                            warn!("Failed to read clipboard on watch trigger: {}", e);
                        }
                    }
                }
            }
        });

        Ok(rx)
    }
}
