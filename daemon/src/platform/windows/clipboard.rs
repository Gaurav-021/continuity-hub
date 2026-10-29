use std::sync::Arc;
use std::time::Duration;
use async_trait::async_trait;
use tokio::sync::{mpsc, Mutex};
use tracing::{debug, info};

use crate::platform::traits::{
    hash_data, now_ms, ClipboardBackend, ClipboardError, ClipboardItem, DedupCache,
};

pub struct WindowsClipboardBackend {
    dedup: Arc<Mutex<DedupCache>>,
}

impl Default for WindowsClipboardBackend {
    fn default() -> Self {
        Self::new()
    }
}

impl WindowsClipboardBackend {
    pub fn new() -> Self {
        Self {
            dedup: Arc::new(Mutex::new(DedupCache::new(64))),
        }
    }

    pub async fn mark_seen(&self, hash: &str) {
        let mut cache = self.dedup.lock().await;
        cache.insert(hash.to_string(), now_ms());
    }

    #[cfg(target_os = "windows")]
    unsafe fn read_win32_clipboard() -> Result<Option<(String, Vec<u8>)>, ClipboardError> {
        use windows_sys::Win32::System::DataExchange::*;
        use windows_sys::Win32::System::Memory::*;

        if OpenClipboard(0) == 0 {
            return Err(ClipboardError::ReadError("OpenClipboard failed".to_string()));
        }

        let result = (|| {
            let handle = GetClipboardData(CF_UNICODETEXT);
            if handle != 0 {
                let ptr = GlobalLock(handle) as *const u16;
                if !ptr.is_null() {
                    let mut len = 0;
                    while *ptr.add(len) != 0 {
                        len += 1;
                    }
                    let slice = std::slice::from_raw_parts(ptr, len);
                    let text = String::from_utf16_lossy(slice);
                    GlobalUnlock(handle);
                    return Ok(Some(("text/plain".to_string(), text.into_bytes())));
                }
            }
            Ok(None)
        })();

        CloseClipboard();
        result
    }

    #[cfg(not(target_os = "windows"))]
    unsafe fn read_win32_clipboard() -> Result<Option<(String, Vec<u8>)>, ClipboardError> {
        Ok(None)
    }

    #[cfg(target_os = "windows")]
    unsafe fn write_win32_clipboard(mime: &str, data: &[u8]) -> Result<(), ClipboardError> {
        use windows_sys::Win32::System::DataExchange::*;
        use windows_sys::Win32::System::Memory::*;

        if OpenClipboard(0) == 0 {
            return Err(ClipboardError::WriteError("OpenClipboard failed".to_string()));
        }

        EmptyClipboard();

        let res = if mime.starts_with("text/") {
            let text = String::from_utf8_lossy(data);
            let wide: Vec<u16> = text.encode_utf16().chain(std::iter::once(0)).collect();
            let size = wide.len() * std::mem::size_of::<u16>();

            let h_mem = GlobalAlloc(GMEM_MOVEABLE, size);
            if h_mem != 0 {
                let ptr = GlobalLock(h_mem) as *mut u16;
                if !ptr.is_null() {
                    std::ptr::copy_nonoverlapping(wide.as_ptr(), ptr, wide.len());
                    GlobalUnlock(h_mem);
                    SetClipboardData(CF_UNICODETEXT, h_mem);
                    Ok(())
                } else {
                    GlobalFree(h_mem);
                    Err(ClipboardError::WriteError("GlobalLock failed".to_string()))
                }
            } else {
                Err(ClipboardError::WriteError("GlobalAlloc failed".to_string()))
            }
        } else {
            Ok(())
        };

        CloseClipboard();
        res
    }

    #[cfg(not(target_os = "windows"))]
    unsafe fn write_win32_clipboard(_mime: &str, _data: &[u8]) -> Result<(), ClipboardError> {
        Ok(())
    }
}

#[async_trait]
impl ClipboardBackend for WindowsClipboardBackend {
    async fn get_content(&self) -> Result<ClipboardItem, ClipboardError> {
        let (mime_type, data) = unsafe { Self::read_win32_clipboard() }?
            .unwrap_or_else(|| ("text/plain".to_string(), Vec::new()));

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
        self.mark_seen(&item.content_hash).await;
        unsafe { Self::write_win32_clipboard(&item.mime_type, &item.data) }?;
        debug!("WindowsClipboardBackend: Updated system clipboard (hash: {})", item.content_hash);
        Ok(())
    }

    async fn watch(&self) -> Result<mpsc::Receiver<ClipboardItem>, ClipboardError> {
        let (tx, rx) = mpsc::channel(32);
        let dedup = Arc::clone(&self.dedup);

        tokio::spawn(async move {
            info!("Windows clipboard watcher started");
            let mut last_seq: u32 = 0;

            loop {
                tokio::time::sleep(Duration::from_millis(150)).await;

                #[cfg(target_os = "windows")]
                let seq = unsafe { windows_sys::Win32::System::DataExchange::GetClipboardSequenceNumber() };
                #[cfg(not(target_os = "windows"))]
                let seq = 0u32;

                if seq == 0 || seq == last_seq {
                    continue;
                }
                last_seq = seq;

                match unsafe { Self::read_win32_clipboard() } {
                    Ok(Some((mime_type, data))) => {
                        let content_hash = hash_data(&data);
                        let mut cache = dedup.lock().await;
                        if cache.contains(&content_hash) {
                            debug!("Windows clipboard: Suppressing duplicate content (hash: {})", content_hash);
                        } else {
                            let timestamp_ms = now_ms();
                            cache.insert(content_hash.clone(), timestamp_ms);
                            drop(cache);

                            let item = ClipboardItem {
                                mime_type,
                                data,
                                timestamp_ms,
                                content_hash,
                            };
                            if tx.send(item).await.is_err() {
                                break;
                            }
                        }
                    }
                    _ => {}
                }
            }
        });

        Ok(rx)
    }
}
