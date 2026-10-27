use async_trait::async_trait;
use tokio::sync::mpsc;

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
