#!/usr/bin/env python3
"""Set mygo.json's version from a desktop release tag.

Usage: scripts/set-version.py desktop-v1.2.3
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: set-version.py desktop-v<version>", file=sys.stderr)
        return 2
    raw = sys.argv[1]
    version = raw[len("desktop-v"):] if raw.startswith("desktop-v") else raw
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?", version):
        print(f"invalid desktop version: {version}", file=sys.stderr)
        return 2
    path = ROOT / "mygo.json"
    cfg = json.loads(path.read_text())
    cfg["version"] = version
    path.write_text(json.dumps(cfg, indent=2, ensure_ascii=False) + "\n")
    print(f"mygo.json version = {version}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
