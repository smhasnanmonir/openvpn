#!/usr/bin/env python3
"""Extract one version's section from CHANGELOG.md for release notes.

Usage: scripts/release-notes.py 1.2.3 > notes.md
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: release-notes.py <version>", file=sys.stderr)
        return 2
    version = sys.argv[1]
    text = (ROOT / "CHANGELOG.md").read_text()
    pattern = re.compile(
        rf"^##\s+\[?{re.escape(version)}\]?(?:\s+-.*)?\s*$", re.MULTILINE
    )
    match = pattern.search(text)
    if not match:
        print(f"# OpenVPN Desktop {version}\n\nSee CHANGELOG.md.")
        return 0
    start = match.end()
    next_match = re.search(r"^##\s+", text[start:], re.MULTILINE)
    section = text[start : start + next_match.start()] if next_match else text[start:]
    print(section.strip())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
