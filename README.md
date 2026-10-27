# Continuity Hub (Phase 1)

**High-Performance, Low-Latency Cross-Platform Continuity Engine**  
Zero-config discovery (mDNS / DNS-SD), Trust-On-First-Use (TOFU) Mutual TLS (mTLS), and bidirectional clipboard synchronization for Linux (Pure Wayland / Hyprland) and Android.

---

## Workspace Architecture

```
continuity-hub/
├── proto/
│   └── message.proto                      # Single source of truth for Protobuf schemas
├── daemon/                                # Desktop Daemon (Rust Cargo Workspace Member)
│   ├── build.rs                           # Prost Protobuf codegen at build-time
│   ├── Cargo.toml
│   └── src/
│       ├── main.rs                        # CLI entrypoint with graceful shutdown & connect flags
│       ├── config.rs                      # Identity, keypair persistence & SHA-256 fingerprinting
│       ├── proto.rs                       # prost generated bindings
│       ├── discovery/                     # mDNS / DNS-SD advertising & browsing via mdns-sd
│       ├── transport/                     # Framing, Rustls TLS 1.3 mTLS & TOFU cert verification
│       ├── platform/                      # Abstracted platform traits & Wayland Hyprland backend
│       └── engine/                        # Central synchronization orchestrator & deduplication
└── client-android/                        # Mobile Client (Android Kotlin + Jetpack Compose)
    ├── build.gradle.kts                   # Root Gradle build configuration
    ├── settings.gradle.kts
    └── app/
        ├── build.gradle.kts               # Protobuf Gradle plugin & Jetpack Compose setup
        └── src/main/
            ├── AndroidManifest.xml        # Foreground service permissions & declarations
            ├── proto -> ../../../../proto # Direct symlink to root proto directory
            └── java/com/continuityhub/
                ├── MainActivity.kt        # Jetpack Compose UI (Identity card, status, live sync log)
                ├── service/               # Foreground Service (ContinuityService)
                ├── crypto/                # RSA keypair & X.509 self-signed cert generation
                ├── transport/             # mTLS SSLSocket/SSLServerSocket & TOFU TrustManager
                ├── discovery/             # Android NsdManager (DNS-SD) service advertising & resolving
                └── clipboard/             # Android ClipboardManager hook & deduplication cache
```

---

## Key Subsystems

### 1. Protobuf Pipeline (`proto/message.proto`)
- `Packet`: Length-delimited stream envelope (`session_id`, `sequence_number`, `channel`, `payload`).
- `HandshakeRequest` / `HandshakeResponse`: Device pairing metadata and certificate fingerprint exchange.
- `ClipboardPayload`: Mime type (`text/plain`, `image/png`), timestamp, content bytes, SHA-256 content hash, and origin device ID.
- Shared between Rust (`prost-build` in `/daemon/build.rs`) and Android (`com.google.protobuf` Gradle plugin in `/client-android/app/build.gradle.kts`).

### 2. Discovery Subsystem (mDNS / DNS-SD)
- Advertises service `_continuity._tcp.local.` on port `52800` (desktop) and `52801` (Android).
- Broadcasts TXT records: `device_name`, `device_id`, and `version=1`.
- Concurrently browses for peers advertising `_continuity._tcp.local.`, automatically extracting host IP addresses and ports while ignoring self-broadcasts.

### 3. Secure Transport Layer (mTLS + TOFU)
- **Key Generation:** Generates self-signed X.509 certificates and private keys on first boot (`rcgen` on Rust, BouncyCastle on Android).
- **TOFU Pinning:** Uses custom `ServerCertVerifier` & `ClientCertVerifier` (Rust) and `X509TrustManager` (Android) to calculate SHA-256 fingerprints of certificates and record them in `known_devices.json`.
- **mTLS Enforcement:** Both sides mandate client authentication with mutual verification.
- **Deterministic Tie-Breaking:** If two devices discover each other simultaneously, the node with lexicographically smaller `device_id` initiates the connection to avoid dual-connection race conditions.
- **Sub-10ms Latency:** `TCP_NODELAY` is enabled on both endpoints to bypass Nagle's algorithm.

### 4. Bidirectional Clipboard Engine & Deduplication
- **Wayland / Hyprland Backend:** Implements `ClipboardBackend` utilizing Wayland's `wlr-data-control` protocol without requiring X11 or window focus.
- **Echo Suppression:** Implements an LRU ring buffer of SHA-256 content hashes. Updates reflected back from a peer match cached hashes and are immediately suppressed, preventing infinite sync feedback loops.

---

## Building & Running

### Desktop Daemon (Rust)
```bash
# Build binary
cargo build -p continuity-daemon

# Run daemon (default port: 52800, Wayland clipboard watcher)
./target/debug/continuity-daemon

# Optional flags:
# ./target/debug/continuity-daemon --port 52880 --name "My-Desktop" --connect 192.168.1.50:52801
```

### Android Client
```bash
cd client-android
./gradlew assembleDebug

# Output APK: client-android/app/build/outputs/apk/debug/app-debug.apk
```
