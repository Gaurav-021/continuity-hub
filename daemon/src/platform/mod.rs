pub mod traits;

#[cfg(target_os = "linux")]
pub mod linux;

#[cfg(target_os = "windows")]
pub mod windows;

pub use traits::{hash_data, now_ms, ClipboardBackend, ClipboardError, ClipboardItem, DedupCache};

#[cfg(target_os = "linux")]
pub use linux::WaylandClipboardBackend as DefaultClipboardBackend;

#[cfg(target_os = "windows")]
pub use windows::WindowsClipboardBackend as DefaultClipboardBackend;
