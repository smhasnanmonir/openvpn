#!/usr/bin/env bash
# Install (or remove) the OpenVPN Desktop privileged helper daemon on macOS.
#
# The helper runs as root under launchd, listens on a token-authenticated
# loopback endpoint, and launches OpenVPN. The GUI reads a per-user token file.
#
# Requirements: macOS, OpenVPN 2.7+ (Homebrew or official), Go 1.27+ to build,
# and an administrator account.
set -euo pipefail

if [[ ${EUID:-$(id -u)} -ne 0 ]]; then
  echo "install-macos-service.sh: run with sudo" >&2
  exit 1
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LABEL="com.smhasnanmonir.openvpndesktop.helper"
HELPER="/Library/PrivilegedHelperTools/${LABEL}"
SYSTEM_DIR="/Library/Application Support/OpenVPN Desktop"
SYSTEM_CONFIG="${SYSTEM_DIR}/service.json"
PLIST="/Library/LaunchDaemons/${LABEL}.plist"
LISTEN="127.0.0.1:51940"
USER_NAME="${SUDO_USER:-${USER}}"
USER_HOME="$(dscl . -read "/Users/${USER_NAME}" NFSHomeDirectory | awk '{print $2}')"
USER_DIR="${USER_HOME}/Library/Application Support/openvpn-desktop"
USER_CONFIG="${USER_DIR}/service.json"

if [[ ${1:-} == "--uninstall" ]]; then
  launchctl bootout "system/${LABEL}" 2>/dev/null || true
  rm -f "${PLIST}" "${HELPER}" "${SYSTEM_CONFIG}" "${USER_CONFIG}"
  rmdir "${SYSTEM_DIR}" 2>/dev/null || true
  echo "Removed ${LABEL} and its configuration."
  exit 0
fi

if [[ -z "${USER_HOME}" || ! -d "${USER_HOME}" ]]; then
  echo "install-macos-service.sh: cannot find the home directory of ${USER_NAME}" >&2
  exit 1
fi

echo "==> building the helper"
export PATH="${PATH}:/usr/local/go/bin:/opt/homebrew/bin"
export GOTOOLCHAIN="${GOTOOLCHAIN:-local}"
mkdir -p "${ROOT}/bin"
go build -trimpath -o "${ROOT}/bin/openvpn-desktop-helper" ./cmd/openvpn-desktop-helper

echo "==> installing ${HELPER}"
install -d -m 0755 /Library/PrivilegedHelperTools
install -m 0755 "${ROOT}/bin/openvpn-desktop-helper" "${HELPER}"

# Check OpenVPN. Homebrew binaries are user-owned; the helper accepts them only
# when they are not group/world writable (documented residual trust). A
# root-owned install is preferred.
OPENVPN=""
for candidate in /usr/local/sbin/openvpn /opt/homebrew/sbin/openvpn /usr/local/bin/openvpn /opt/homebrew/bin/openvpn; do
  if [[ -x "${candidate}" ]]; then
    OPENVPN="${candidate}"
    break
  fi
done
if [[ -z "${OPENVPN}" ]]; then
  echo "warning: OpenVPN 2.7+ was not found; install it before connecting" >&2
else
  owner="$(stat -f '%Su' "${OPENVPN}")"
  echo "==> using OpenVPN: ${OPENVPN} (owner ${owner})"
fi

echo "==> writing the service token"
TOKEN="$(openssl rand -hex 32)"
mkdir -p "${SYSTEM_DIR}" "${USER_DIR}"
printf '{"addr":"%s","token":"%s"}\n' "${LISTEN}" "${TOKEN}" > "${SYSTEM_CONFIG}"
chmod 0600 "${SYSTEM_CONFIG}"
chown root:wheel "${SYSTEM_CONFIG}"

printf '{"addr":"%s","token":"%s"}\n' "${LISTEN}" "${TOKEN}" > "${USER_CONFIG}"
chmod 0600 "${USER_CONFIG}"
chown "${USER_NAME}" "${USER_CONFIG}"

echo "==> installing the launchd daemon"
cat > "${PLIST}" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>${LABEL}</string>
  <key>ProgramArguments</key>
  <array>
    <string>${HELPER}</string>
    <string>serve</string>
    <string>--config</string>
    <string>${SYSTEM_CONFIG}</string>
  </array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ProcessType</key><string>Interactive</string>
</dict>
</plist>
EOF
chmod 0644 "${PLIST}"
chown root:wheel "${PLIST}"

launchctl bootout "system/${LABEL}" 2>/dev/null || true
launchctl bootstrap system "${PLIST}"
launchctl enable "system/${LABEL}"

echo
echo "Installed ${LABEL}."
echo "  helper: ${HELPER}"
echo "  config: ${SYSTEM_CONFIG} (root only)"
echo "  token:  ${USER_CONFIG} (${USER_NAME} only)"
echo "Restart OpenVPN Desktop, then connect. Remove with:"
echo "  sudo $0 --uninstall"
