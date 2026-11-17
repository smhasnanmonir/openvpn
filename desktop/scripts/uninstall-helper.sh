#!/usr/bin/env bash
# Remove the OpenVPN Desktop privileged helper and its authorization rules.
set -euo pipefail

if [[ ${EUID:-$(id -u)} -ne 0 ]]; then
  echo "uninstall-helper.sh: run with sudo or as root" >&2
  exit 1
fi

PREFIX="${PREFIX:-/usr/local}"
HELPER="${PREFIX}/libexec/openvpn-desktop/openvpn-desktop-helper"
ACTION_ID="com.smhasnanmonir.openvpndesktop.helper"
POLKIT_FILE="/usr/share/polkit-1/actions/${ACTION_ID}.policy"
SUDOERS_FILE="/etc/sudoers.d/openvpn-desktop"

if [[ -x "$HELPER" ]]; then
  "$HELPER" cleanup 2>/dev/null || true
fi
rm -f "$HELPER" \
      "${PREFIX}/libexec/openvpn-desktop/privilege-mode" \
      "${PREFIX}/libexec/openvpn-desktop/helper.version" \
      "$POLKIT_FILE" \
      "$SUDOERS_FILE"
rmdir "${PREFIX}/libexec/openvpn-desktop" 2>/dev/null || true
echo "Removed the OpenVPN Desktop privileged helper and authorization rules."
echo "Profiles and the vault are untouched: ${HOME}/.config/openvpn-desktop"
