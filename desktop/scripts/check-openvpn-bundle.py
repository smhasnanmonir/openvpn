#!/usr/bin/env python3
"""Check that the bundled OpenVPN installer is pinned consistently.

scripts/fetch-openvpn-msi.sh downloads and verifies the installer; the helper
refuses to run anything whose digest differs from its compiled-in pin. If the
two drift apart, every Windows install of the bundle fails closed, so this
check runs in CI and in scripts/check.sh.
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent

FETCH = ROOT / "scripts/fetch-openvpn-msi.sh"
BUNDLE = ROOT / "internal/helper/openvpn_bundle.go"


def script_pins() -> dict[str, str]:
    text = FETCH.read_text()
    return dict(re.findall(r'^\s*(amd64|arm64)\)\s*echo\s*"([0-9a-f]{64})"', text, re.M))


def script_version() -> str | None:
    match = re.search(r'^VERSION="([^"]+)"', FETCH.read_text(), re.M)
    return match.group(1) if match else None


def go_pins() -> dict[str, str]:
    text = BUNDLE.read_text()
    return dict(re.findall(r'case "(amd64|arm64)":\s*\n\s*return "([0-9a-f]{64})", true', text))


def go_version() -> str | None:
    match = re.search(r'bundledOpenVPNVersion\s*=\s*"([^"]+)"', BUNDLE.read_text())
    return match.group(1) if match else None


def main() -> int:
    wanted = script_pins()
    have = go_pins()
    if not wanted:
        raise SystemExit(f"{FETCH}: no pinned digests found")
    if wanted != have:
        raise SystemExit(
            "OpenVPN installer pins differ\n"
            f"  fetch script: {wanted}\n"
            f"  helper:       {have}"
        )

    sv, gv = script_version(), go_version()
    if not sv or not gv:
        raise SystemExit("could not read the bundled OpenVPN version")
    if sv != gv:
        raise SystemExit(f"OpenVPN version differs: fetch script {sv}, helper {gv}")

    for arch, digest in sorted(wanted.items()):
        print(f"OpenVPN {sv} {arch}: {digest}")
    print("Bundled OpenVPN pins are consistent.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
