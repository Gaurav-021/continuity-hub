# Continuity Hub — Windows Support & Architecture Guide (`WINDOWS.md`)

This guide outlines the architecture, portability design, and build instructions for running the **Continuity Hub** daemon on **Microsoft Windows (Windows 10/11 x86_64 / ARM64)**.

---

## 1. Cross-Platform Architectural Design

Continuity Hub was designed from the ground up to isolate OS-dependent subsystems into the `daemon/src/platform/` abstraction layer while keeping the core transport and protocol engine 100% portable pure Rust.

```
┌────────────────────────────────────────────────────────────────────────┐
│                   Continuity Hub Core Engine (100% Cross-Platform)     │
│  - Tokio Multi-threaded Async Runtime                                  │
│  - Rustls TLS 1.3 Mutual Authentication (mTLS) & TOFU Store            │
│  - Length-delimited Protobuf Packet Framing (`proto/message.proto`)    │
│  - Zero-Config mDNS Service Discovery (`mdns-sd`)                      │
│  - High-Throughput 64KB P2P File Transfer Engine                       │
│  - Axum HTTP MJPEG Live Webcam Server (`http://127.0.0.1:52802`)       │
└────────────────────────────────────┬───────────────────────────────────┘
                                     │
           ┌─────────────────────────┴─────────────────────────┐
           ▼                                                   ▼
┌────────────────────────────────────┐   ┌────────────────────────────────────┐
│      Linux Platform Backend        │   │     Windows Platform Backend       │
│  (Wayland / Hyprland / D-Bus)      │   │          (Win32 / MSVC)            │
├────────────────────────────────────┤   ├────────────────────────────────────┤
│ • Clipboard: wl-clipboard / FIFO   │   │ • Clipboard: Win32 OpenClipboard   │
│ • Input: zwlr virtual pointer      │   │ • Input: Win32 SendInput (Mouse,   │
│ • Keys: wtype (Wayland virtual)    │   │          Wheel, Unicode Text, Keys)│
│ • Media: MPRIS 2.2 D-Bus (zbus)    │   │ • Media: Local state & sync        │
│ • Alerts: notify-rust / D-Bus      │   │ • Alerts: Toast / Event fallback   │
└────────────────────────────────────┘   └────────────────────────────────────┘
```

---

## 2. Windows Subsystem Implementations

### A. Clipboard Synchronization (`daemon/src/platform/windows/clipboard.rs`)
- Uses native Win32 APIs: `OpenClipboard`, `GetClipboardData(CF_UNICODETEXT)`, `GlobalLock`, and `SetClipboardData`.
- Polling via `GetClipboardSequenceNumber()` every 150ms ensures sub-millisecond detection of system clipboard changes with zero CPU overhead.
- Preserves SHA-256 echo suppression cache (`DedupCache`) to prevent infinite clipboard loops between Windows and Android.

### B. Remote Trackpad & Virtual Keyboard (`daemon/src/input/mod.rs`)
- Uses Win32 `SendInput` from `windows-sys` (`Win32_UI_Input_KeyboardAndMouse`):
  - **Relative Mouse Gestures:** `MOUSEEVENTF_MOVE` with sub-pixel rounding and dynamic velocity curves.
  - **Mouse Clicks:** `MOUSEEVENTF_LEFTDOWN` / `UP`, `MOUSEEVENTF_RIGHTDOWN` / `UP`, and `MOUSEEVENTF_MIDDLEDOWN` / `UP`.
  - **2-Finger Kinetic Scrolling:** `MOUSEEVENTF_WHEEL` and `MOUSEEVENTF_HWHEEL`.
  - **Unicode Typing:** `KEYEVENTF_UNICODE` for instantaneous multi-lingual text pasting without layout mismatches.
  - **Modifier & Hotkey Combinations:** Virtual Key codes (`VK_RETURN`, `VK_BACK`, `VK_TAB`, `VK_ESCAPE`, `VK_CONTROL`, `VK_MENU`, `VK_SHIFT`, `VK_LWIN`).

### C. File Transfer & Webcam Streaming
- Both `FileTransferManager` (progressive SHA-256 chunking) and `CameraManager` (HTTP MJPEG `/live.mjpg`) run natively on Windows with identical performance to Linux.

---

## 3. Building for Windows

### Option 1: Native Windows Build (Recommended)
On a Windows machine with Rust and Visual Studio C++ Build Tools installed:

```powershell
# 1. Clone repository
git clone https://github.com/gaurav/continuity-hub.git
cd continuity-hub\daemon

# 2. Check compilation
cargo check

# 3. Build optimized release binary
cargo build --release

# Output binary:
# target\release\continuity-daemon.exe
```

### Option 2: Cross-Compiling from Linux to Windows
When cross-compiling from Linux targeting Windows:

```bash
# Add Windows MSVC or GNU target
rustup target add x86_64-pc-windows-msvc

# Build with cross-compilation toolchain
cargo build --release --target x86_64-pc-windows-msvc
```

---

## 4. Running the Windows Daemon

To run the daemon and connect to your phone:

```powershell
# Run with automatic mDNS peer discovery:
.\continuity-daemon.exe

# Or directly connect to phone over local Wi-Fi / USB tethering:
.\continuity-daemon.exe --connect 192.168.1.150:52800

# Send a file to phone:
.\continuity-daemon.exe --send-file "C:\Users\username\Documents\report.pdf"
```

---

## 5. Summary of Platform Dependencies

In `daemon/Cargo.toml`, platform-specific dependencies are strictly separated:

```toml
# Linux Wayland / D-Bus dependencies
[target.'cfg(target_os = "linux")'.dependencies]
libc = "0.2"
notify-rust = { version = "4.11", default-features = false, features = ["z"] }
zbus = "5.19"
wayland-client = "0.31"
wayland-protocols-wlr = { version = "0.3", features = ["client"] }

# Windows Win32 API dependencies
[target.'cfg(target_os = "windows")'.dependencies]
windows-sys = { version = "0.59", features = [
    "Win32_UI_Input_KeyboardAndMouse",
    "Win32_UI_WindowsAndMessaging",
    "Win32_System_DataExchange",
    "Win32_System_Memory",
    "Win32_Foundation"
] }
```
