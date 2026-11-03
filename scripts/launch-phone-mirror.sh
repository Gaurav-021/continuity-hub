#!/usr/bin/env bash
# Continuity Hub — Native Floating Phone Mirror Window Launcher for Hyprland
set -euo pipefail

MIRROR_URL="http://127.0.0.1:52802/phone"
WINDOW_CLASS="continuity-mirror"

echo "📱 Launching Continuity Hub Phone Mirror window..."

# 1. Apply Hyprland floating and borderless window rules if running on Hyprland
if command -v hyprctl >/dev/null 2>&1; then
    hyprctl keyword windowrulev2 "float,class:^(${WINDOW_CLASS})$" >/dev/null 2>&1 || true
    hyprctl keyword windowrulev2 "size 380 820,class:^(${WINDOW_CLASS})$" >/dev/null 2>&1 || true
    hyprctl keyword windowrulev2 "noborder,class:^(${WINDOW_CLASS})$" >/dev/null 2>&1 || true
    hyprctl keyword windowrulev2 "rounding 24,class:^(${WINDOW_CLASS})$" >/dev/null 2>&1 || true
fi

# 2. Launch Chromium/Chrome with native Wayland hardware acceleration and app mode
for browser in chromium google-chrome brave zen-browser; do
    if command -v "$browser" >/dev/null 2>&1; then
        exec "$browser" \
            "--app=${MIRROR_URL}" \
            "--class=${WINDOW_CLASS}" \
            "--name=${WINDOW_CLASS}" \
            "--window-size=380,820" \
            "--ozone-platform=wayland" \
            "--enable-features=UseOzonePlatform" \
            "$@"
    fi
done

# 3. Fallback to default browser
echo "No standalone Chromium/Chrome detected, falling back to xdg-open..."
exec xdg-open "${MIRROR_URL}"
