#!/usr/bin/env bash
set -euo pipefail

# Continuity Hub - Local Pre-Commit Hook Installer
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
HOOK_PATH="${ROOT_DIR}/.git/hooks/pre-commit"

echo "=== Installing Continuity Hub Git Pre-Commit Hook ==="

cat << 'EOF' > "${HOOK_PATH}"
#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(git rev-parse --show-toplevel)"
echo "---------------------------------------------------------"
echo "🔍 Running Continuity Hub Pre-Commit Quality Gates..."
echo "---------------------------------------------------------"

# 1. Schema Hardlink Parity Check
echo "[1/4] Verifying Protobuf schema synchronization..."
if ! diff -u "${ROOT_DIR}/proto/message.proto" "${ROOT_DIR}/client-android/app/src/main/proto/message.proto" > /dev/null 2>&1; then
    echo "❌ ERROR: Schema drift detected between root proto and Android proto!"
    echo "   Fix: cp proto/message.proto client-android/app/src/main/proto/message.proto"
    exit 1
fi
echo "      Schema is in sync."

# 2. Rust Daemon Compilation Check
echo "[2/4] Checking Rust daemon targets (cargo check)..."
cargo check -p continuity-daemon --all-targets --quiet

# 3. Rust Daemon Unit & Integration Tests + Clippy
echo "[3/4] Running Rust tests & strict Clippy (-D warnings)..."
cargo test --quiet -p continuity-daemon
cargo clippy --quiet -p continuity-daemon --all-targets -- -D warnings

# 4. Android Client Unit Tests
echo "[4/4] Running Android client unit tests..."
(cd "${ROOT_DIR}/client-android" && ./gradlew testDebugUnitTest -q)

echo "---------------------------------------------------------"
echo "✅ All pre-commit quality gates PASSED! Committing code..."
echo "---------------------------------------------------------"
exit 0
EOF

chmod +x "${HOOK_PATH}"
echo "✅ Pre-commit hook successfully installed at: ${HOOK_PATH}"
echo "   It will automatically run quality gates before every 'git commit'."
