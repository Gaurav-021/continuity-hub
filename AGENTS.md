# Continuity Hub — Agent & Developer Guide (`AGENTS.md`)

Welcome to **Continuity Hub**! This document provides AI coding agents and contributors with essential architecture context, build/test quality gates, engineering constraints, and subsystem references for this repository.

---

## 1. Project Overview & Architecture

Continuity Hub is a high-performance, low-latency cross-platform continuity engine bridging **Linux (Pure Wayland / Hyprland)** and **Android 11+ (API 30+)**.

The system operates peer-to-peer (P2P) over the local network using zero-configuration mDNS discovery, Mutual TLS 1.3 (mTLS) with Trust-On-First-Use (TOFU) certificate pinning, 6-digit Short Authentication String (SAS) pairing, and length-delimited Protobuf packets.

### Repository Layout

```
continuity-hub/
├── proto/
│   └── message.proto                      # Single source of truth for Protobuf schemas
├── daemon/                                # Desktop Daemon (Rust Cargo Workspace Member)
│   ├── build.rs                           # Prost Protobuf codegen at build-time
│   ├── Cargo.toml
│   ├── src/
│   │   ├── main.rs                        # CLI entrypoint & argument parsing
│   │   ├── config.rs                      # Identity, keypair persistence & SHA-256 fingerprinting
│   │   ├── proto.rs                       # Prost generated bindings inclusion
│   │   ├── discovery/                     # mDNS advertising & browsing (mdns-sd)
│   │   ├── transport/                     # Rustls TLS 1.3 mTLS, framing, TOFU store & peer sessions
│   │   ├── platform/                      # Abstracted platform traits & Wayland Hyprland backend
│   │   ├── transfer/                      # High-throughput P2P file transfer (64KB streaming chunks)
│   │   ├── notifications/                 # D-Bus desktop notification mirroring & remote actions
│   │   ├── input/                         # Native Wayland virtual pointer (zwlr) & keyboard (wtype)
│   │   ├── telephony/                     # Call alerts, SMS bridge, remote actions & Waybar telemetry
│   │   ├── media/                         # MPRIS 2.2 D-Bus interface, playback sync & Smart Call Ducking
│   │   ├── camera/                        # HTTP MJPEG stream, V4L2 loopback, desktop screencast & Sidecar
│   │   │   ├── mod.rs                     # Camera manager & stream routing
│   │   │   ├── v4l2.rs                    # Direct V4L2 loopback hardware webcam injection
│   │   │   ├── screencast.rs              # Wayland desktop screencopy streaming
│   │   │   └── sidecar.rs                 # Headless extended monitor & touch-to-click mapping
│   │   ├── tray/                          # D-Bus StatusNotifierItem system tray applet
│   │   └── engine/                        # Central synchronization orchestrator (SyncEngine)
│   └── tests/                             # Rust integration tests (input, telephony, media, camera, sidecar)
├── client-android/                        # Mobile Client (Android Kotlin + Jetpack Compose)
│   ├── build.gradle.kts                   # Root Gradle build configuration
│   ├── settings.gradle.kts
│   └── app/
│       ├── build.gradle.kts               # Protobuf Gradle plugin & Jetpack Compose setup
│       └── src/main/
│           ├── AndroidManifest.xml        # Foreground & Notification Listener service declarations
│           ├── proto/message.proto        # Hardlinked to root proto/message.proto
│           └── java/com/continuityhub/
│               ├── MainActivity.kt        # Jetpack Compose UI (Bottom Navigation: Hub, Trackpad, Media, Camera)
│               ├── ui/                    # TrackpadScreen, MediaScreen, CameraScreen & M3 Theme
│               ├── camera/                # AndroidCameraManager (Camera2 API, Sidecar & Desktop mirror)
│               ├── media/                 # AndroidMediaManager (MediaSessionManager & transport controls)
│               ├── telephony/             # Call state monitor, SMS receiver & telemetry monitor
│               ├── service/               # Foreground Service (ContinuityService)
│               ├── crypto/                # RSA keypair & X.509 self-signed cert generation (BouncyCastle)
│               ├── transport/             # mTLS SSLSocket/SSLServerSocket & TOFU TrustManager
│               ├── discovery/             # Android NsdManager (DNS-SD) service advertising & resolving
│               ├── clipboard/             # Android ClipboardManager hook & deduplication cache
│               ├── transfer/              # Android P2P file drop, MediaScanner & FileProvider
│               └── notifications/         # NotificationListenerService with remote quick reply execution
├── assets/                                # Desktop launcher, SVG icon, systemd service & context menus
├── scripts/                               # Automation scripts (install-linux-app.sh, setup-v4l2loopback.sh, setup-hooks.sh)
└── .github/workflows/                     # GitHub Actions CI & automated release pipelines
```

---

## 2. Quality Gates & Verification Commands

Before concluding any work, every change must strictly satisfy the following quality gates:

### Local Pre-Commit Hook
Contributors should install the pre-commit hook by running:
```bash
./scripts/setup-hooks.sh
```
This automatically verifies schema parity, compilation, clippy warnings, Rust tests, and Android unit tests before every `git commit`.

### Linux Daemon (Rust)
```bash
# 1. Check compilation across all targets (lib, bin, tests)
cargo check -p continuity-daemon --all-targets

# 2. Run all unit and integration tests
cargo test -p continuity-daemon

# 3. Enforce zero compiler/clippy warnings
cargo clippy -p continuity-daemon --all-targets -- -D warnings

# 4. Run the daemon locally (or connect directly to a phone)
cargo run -p continuity-daemon -- --connect 127.0.0.1:52801
```

### Android Client (Kotlin / Gradle)
```bash
# 1. Run all Android unit tests
cd client-android && ./gradlew testDebugUnitTest

# 2. Build debug APK
cd client-android && ./gradlew assembleDebug

# Output APK path:
# client-android/app/build/outputs/apk/debug/app-debug.apk
```

### ADB Device Deployment (When Device is Connected)
```bash
# Install APK to device
adb install -r client-android/app/build/outputs/apk/debug/app-debug.apk

# Launch app directly
adb shell am start -n com.continuityhub/.MainActivity

# Set up USB port tunnels for direct localhost connection (optional but recommended for USB testing)
adb reverse tcp:52800 tcp:52800 && adb forward tcp:52801 tcp:52801
```

---

## 3. Engineering Invariants & Constraints

1. **Pure Wayland Only:**
   - Absolutely **no X11 dependencies** (do not use `xdotool`, `xclip`, `xsel`, `XTest`, or XWayland workarounds).
   - Mouse input: Binds natively to `zwlr_virtual_pointer_manager_v1` via `wayland-client` / `wayland-protocols-wlr`.
   - Keyboard input: Uses `wtype` or virtual keyboard protocols.
   - Screen capture: Uses `grim` or `wlr-screencopy-v1`.
   - Clipboard: Uses `wl-clipboard` / `wlr-data-control` via standard Wayland mechanisms.

2. **Hyprland Headless Output & Lua Dispatch (Sidecar):**
   - Headless monitor creation: `hyprctl output create headless Sidecar` (standard resolution: 1920x1080 @ 60Hz).
   - Clean destruction on disconnect: `hyprctl output remove Sidecar`.
   - In Hyprland v0.56.2+, dispatchers with arguments use Lua eval format:
     - Relocate window: `hyprctl eval 'hl.dsp.window.move({ workspace = sidecar_ws })'`
     - Move cursor: `hyprctl eval 'hl.dsp.cursor.move({ x = X, y = Y })'`

3. **Async Tokio Engine:**
   - The desktop daemon runs on multi-threaded Tokio (`tokio = { version = "1.43", features = ["full"] }`).
   - Never block Tokio worker threads with synchronous file I/O or sleep calls; use `tokio::fs`, `tokio::time::sleep`, and non-blocking channels (`tokio::sync::mpsc`, `broadcast`, `RwLock`).

4. **Protobuf Schema Invariant:**
   - Source of truth: `proto/message.proto`.
   - `client-android/app/src/main/proto/message.proto` is hardlinked to `proto/message.proto`. Verify with `diff -u proto/message.proto client-android/app/src/main/proto/message.proto` whenever modifying schema.
   - **Prost Enum Mangling Awareness:** Prost strips redundant prefixes in generated Rust code (e.g. `LENS_BACK` becomes `Lens::Back`, `ACTION_START` becomes `Action::Start`, `CALL_STATE_RINGING` becomes `CallState::Ringing`, `ACTION_START_SIDECAR` becomes `Action::StartSidecar`). Always verify variants in `target/debug/build/continuity-daemon-*/out/continuity.rs` if compiler complains of missing enum variants.

5. **Zero-Warning Policy:**
   - Treat compiler and clippy warnings as fatal errors (`-D warnings`).
   - Use `#[allow(clippy::too_many_arguments)]` only where constructors legitimately require dependency injection across multiple subsystems.

---

## 4. Subsystems Reference

| Channel ID | Channel Name | Protocol / Mechanism | Description |
|---|---|---|---|
| `1` | `CHANNEL_HANDSHAKE` | TLS 1.3 / TOFU | Authenticates device name, ID, and certificate fingerprint. Displays 6-digit SAS pairing code. Pins to `known_devices.json`. |
| `2` | `CHANNEL_CLIPBOARD` | `wl-clipboard` / Android | Sub-millisecond text/image clipboard sync with SHA-256 deduplication and echo suppression. |
| `3` | `CHANNEL_FILE_TRANSFER` | 64KB Chunks | High-throughput file drop with progressive SHA-256 verification and atomic `.part` rename. |
| `4` | `CHANNEL_NOTIFICATIONS` | D-Bus / Android Listener | Cross-device notification mirroring with native D-Bus actions (Quick Reply, Dismiss). |
| `5` | `CHANNEL_HEARTBEAT` | Periodic Ping | Connection keepalive and latency monitoring. |
| `6` | `CHANNEL_INPUT` | `zwlr` pointer / `wtype` | Relative trackpad gestures (1-finger drag, tap, 2-finger scroll) and virtual keyboard shortcuts. |
| `7` | `CHANNEL_TELEPHONY` | Telecom / SmsReceiver | Ringing alerts (Mute/Decline/SMS-decline), SMS bridge, and `/tmp/continuity-waybar.json` telemetry. |
| `8` | `CHANNEL_MEDIA` | MPRIS 2.2 / MediaSession | Virtual D-Bus player (`org.mpris.MediaPlayer2.ContinuityPhone`), metadata sync, and Smart Call Ducking. |
| `9` | `CHANNEL_CAMERA` | Camera2 / HTTP MJPEG / Sidecar | Wireless webcam (`http://127.0.0.1:52802/live.mjpg`), V4L2 virtual webcam (`/dev/video10`), desktop screencast, and Apple Sidecar extended display. |

---

## 5. Adding New Features or Phases

When implementing a new phase or feature:
1. **Define Schema:** Add message types and assign a new `Channel` enum variant in `proto/message.proto`.
2. **Synchronize Schema:** Hardlink or copy schema to `client-android/app/src/main/proto/message.proto` and verify `diff -u`.
3. **Implement Daemon Module:** Add a new module under `daemon/src/<feature>/mod.rs` and register it in `daemon/src/lib.rs` and `daemon/src/engine/sync.rs`.
4. **Route Packets:** Add packet decode/dispatch logic in `daemon/src/transport/peer.rs`.
5. **Implement Android Module:** Add subsystem under `client-android/app/src/main/java/com/continuityhub/<feature>/` and wire into `ContinuityService.kt`.
6. **Add Compose UI:** Add screens/cards under `client-android/app/src/main/java/com/continuityhub/ui/` and expose navigation in `MainActivity.kt`.
7. **Write Unit/Integration Tests:** Add Rust tests in `daemon/tests/<feature>_test.rs` and Android tests in `client-android/app/src/test/java/com/continuityhub/<Feature>ProtoTest.kt`.
8. **Verify Quality Gates:** Run all Rust and Android quality gates listed in Section 2.
9. **Update Docs:** Update `README.md`, `AGENTS.md`, and `WINDOWS.md` as needed.
