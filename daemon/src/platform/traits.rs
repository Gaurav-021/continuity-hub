use std::collections::VecDeque;
use std::time::{SystemTime, UNIX_EPOCH};
use async_trait::async_trait;
use sha2::{Digest, Sha256};
use tokio::sync::mpsc;

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
pub struct DedupCache {
    capacity: usize,
    entries: VecDeque<(String, u64)>, // (hash, timestamp_ms)
}

impl DedupCache {
    pub fn new(capacity: usize) -> Self {
        Self {
            capacity,
            entries: VecDeque::with_capacity(capacity),
        }
    }

    pub fn contains(&self, hash: &str) -> bool {
        self.entries.iter().any(|(h, _)| h == hash)
    }

    pub fn insert(&mut self, hash: String, ts: u64) {
        if self.contains(&hash) {
            return;
        }
        if self.entries.len() >= self.capacity {
            self.entries.pop_front();
        }
        self.entries.push_back((hash, ts));
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ClipboardItem {
    pub mime_type: String,
    pub data: Vec<u8>,
    pub timestamp_ms: u64,
    pub content_hash: String,
}

#[derive(thiserror::Error, Debug)]
pub enum ClipboardError {
    #[error("Platform clipboard read error: {0}")]
    ReadError(String),
    #[error("Platform clipboard write error: {0}")]
    WriteError(String),
    #[error("Platform clipboard watch error: {0}")]
    WatchError(String),
    #[error("Unsupported MIME type: {0}")]
    UnsupportedType(String),
}

#[async_trait]
pub trait ClipboardBackend: Send + Sync {
    async fn get_content(&self) -> Result<ClipboardItem, ClipboardError>;
    async fn set_content(&self, item: &ClipboardItem) -> Result<(), ClipboardError>;
    async fn watch(&self) -> Result<mpsc::Receiver<ClipboardItem>, ClipboardError>;
}
