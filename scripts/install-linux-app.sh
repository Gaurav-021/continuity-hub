#!/usr/bin/env bash
set -euo pipefail

# Continuity Hub - Linux Desktop Application Installer
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

echo "=== Continuity Hub: Installing Desktop Application ==="

# 1. Build release binary
echo "[1/6] Building release binary..."
cargo build --release --manifest-path "${ROOT_DIR}/daemon/Cargo.toml"

# 2. Install binary to ~/.local/bin
echo "[2/6] Installing binary to ~/.local/bin/continuity-daemon..."
mkdir -p "${HOME}/.local/bin"
cp -f "${ROOT_DIR}/target/release/continuity-daemon" "${HOME}/.local/bin/continuity-daemon"
chmod +x "${HOME}/.local/bin/continuity-daemon"
ln -sf "${HOME}/.local/bin/continuity-daemon" "${HOME}/.local/bin/continuity"

# 3. Install App Icon
echo "[3/6] Installing application icon..."
mkdir -p "${HOME}/.local/share/icons/hicolor/scalable/apps"
cp -f "${ROOT_DIR}/assets/continuity-hub.svg" "${HOME}/.local/share/icons/hicolor/scalable/apps/continuity-hub.svg"

# 4. Install Desktop Entry
echo "[4/6] Installing desktop launcher..."
mkdir -p "${HOME}/.local/share/applications"
cp -f "${ROOT_DIR}/assets/continuity-hub.desktop" "${HOME}/.local/share/applications/continuity-hub.desktop"
if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database "${HOME}/.local/share/applications" || true
fi

# 5. Install File Manager Integration
echo "[5/6] Installing file manager context menus..."
mkdir -p "${HOME}/.local/share/kio/servicemenus"
cp -f "${ROOT_DIR}/assets/continuity-send.desktop" "${HOME}/.local/share/kio/servicemenus/continuity-send.desktop"

# 6. Install Systemd User Service
echo "[6/6] Installing systemd user service..."
mkdir -p "${HOME}/.config/systemd/user"
cp -f "${ROOT_DIR}/assets/continuity-daemon.service" "${HOME}/.config/systemd/user/continuity-daemon.service"
if command -v systemctl >/dev/null 2>&1; then
    systemctl --user daemon-reload || true
fi

echo ""
echo "✅ Continuity Hub Desktop Application installed successfully!"
echo "   - Launcher: App menu / search for 'Continuity Hub'"
echo "   - CLI: continuity --help"
echo "   - Autostart on boot (optional): systemctl --user enable --now continuity-daemon"
echo "   - Run in terminal: continuity-daemon"
