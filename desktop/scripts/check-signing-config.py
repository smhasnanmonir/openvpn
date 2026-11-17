#!/usr/bin/env python3
"""Check the SignPath workflow's static configuration.

The staging and release installers must generate the same uninstaller code,
otherwise the signed Uninstall.exe from the staging pass would not match the
release installer. The artifact configurations must be well-formed XML.
"""
from __future__ import annotations

import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent


def uninstall_macro(path: pathlib.Path) -> str:
    text = path.read_text()
    match = re.search(r"!macro customUnInstall(.*?)!macroend", text, re.S)
    if not match:
        raise SystemExit(f"{path}: customUnInstall macro not found")
    return match.group(1).strip()


def main() -> int:
    release = uninstall_macro(ROOT / "packaging/installer.nsh")
    staging = uninstall_macro(ROOT / "packaging/installer-staging.nsh")
    if release != staging:
        raise SystemExit(
            "packaging/installer.nsh and packaging/installer-staging.nsh "
            "have different customUnInstall macros"
        )

    for path in sorted((ROOT.parent / ".signpath/artifact-configurations").glob("*.xml")):
        ET.parse(path)
        print(f"valid XML: {path.relative_to(ROOT.parent)}")

    script = (ROOT / "packaging/signpath-inject.ps1").read_text()
    for marker in (
        "SIGNPATH_SIGNED_APP",
        "SIGNPATH_SIGNED_HELPER",
        "SIGNPATH_SIGNED_UNINSTALLER",
    ):
        if marker not in script:
            raise SystemExit(f"signpath-inject.ps1 does not handle {marker}")

    print("SignPath signing configuration is consistent.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
