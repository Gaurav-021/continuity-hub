#!/bin/bash
# Continuity Hub — Setup Virtual Webcam (v4l2loopback)
# Configures a native hardware video device (/dev/video10) for Zoom, Meet, Teams, Discord, OBS

set -e

echo "=== Continuity Hub Virtual Webcam Setup ==="

# Check if v4l2loopback is loaded
if lsmod | grep -q v4l2loopback; then
    echo "✔ v4l2loopback module is already loaded."
else
    echo "Loading v4l2loopback module for Continuity Camera..."
    sudo modprobe v4l2loopback devices=1 video_nr=10 card_label="Continuity Camera (Pixel 9 Pro)" exclusive_caps=1
    echo "✔ Created /dev/video10 as 'Continuity Camera (Pixel 9 Pro)'."
fi

# Ensure user is in video group for permissions
if groups | grep -q "\bvideo\b"; then
    echo "✔ Current user is already in the 'video' group."
else
    echo "Adding $USER to 'video' group..."
    sudo usermod -aG video "$USER"
    echo "✔ Added $USER to 'video' group (may require re-login)."
fi

# Create persistent modprobe config if requested
if [ "$1" == "--persist" ]; then
    echo "Persisting v4l2loopback across system reboots..."
    echo "options v4l2loopback devices=1 video_nr=10 card_label=\"Continuity Camera (Pixel 9 Pro)\" exclusive_caps=1" | sudo tee /etc/modprobe.d/continuity-v4l2loopback.conf
    echo "v4l2loopback" | sudo tee /etc/modules-load.d/continuity-v4l2loopback.conf
    echo "✔ Persistent config written to /etc/modprobe.d and /etc/modules-load.d."
fi

echo ""
echo "Setup complete! Available video devices:"
ls -l /dev/video10 2>/dev/null || ls -l /dev/video*
echo ""
echo "Launch Continuity Hub Daemon, and any browser/video app can now select 'Continuity Camera' directly!"
