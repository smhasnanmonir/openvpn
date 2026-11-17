#!/usr/bin/env bash
# Smoke-test the macOS launchd helper install/uninstall without a VPN.
# Requires an administrator account and OpenVPN 2.7+ installed.
set -euo pipefail

if [[ ${EUID:-$(id -u)} -ne 0 ]]; then
  echo "test-macos-service.sh: run with sudo" >&2
  exit 1
fi
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HELPER="$ROOT/build/darwin-universal/OpenVPN Desktop.app/Contents/Resources/openvpn-desktop-helper"
if [[ ! -x "$HELPER" ]]; then
  HELPER="$ROOT/resources/darwin-arm64/openvpn-desktop-helper"
fi
if [[ ! -x "$HELPER" ]]; then
  echo "build the macOS app or run scripts/build-helpers.sh first" >&2
  exit 1
fi
USER_NAME="${SUDO_USER:-${USER}}"
USER_HOME="$(dscl . -read "/Users/${USER_NAME}" NFSHomeDirectory | awk '{print $2}')"
USER_CONFIG="${USER_HOME}/Library/Application Support/openvpn-desktop/service.json"

cleanup() {
  "$HELPER" uninstall-service --purge --user-config "$USER_CONFIG" || true
}
trap cleanup EXIT

echo "==> install"
"$HELPER" install-service --helper "$HELPER" --user-config "$USER_CONFIG" --user "$USER_NAME"
launchctl print system/com.smhasnanmonir.openvpndesktop.helper >/dev/null
[[ -f "$USER_CONFIG" ]] || { echo "per-user token missing" >&2; exit 1; }
[[ -x "/Library/PrivilegedHelperTools/com.smhasnanmonir.openvpndesktop.helper" ]] || {
  echo "installed helper missing" >&2; exit 1
}
echo "==> helper daemon is loaded; token: $USER_CONFIG"
echo "macOS helper service smoke test passed."
