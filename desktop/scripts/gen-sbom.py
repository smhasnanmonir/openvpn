#!/usr/bin/env python3
"""Write a CycloneDX 1.5 SBOM for the desktop Go module.

Usage: scripts/gen-sbom.py [-o build/sbom.cdx.json]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
import uuid
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def modules() -> list[dict]:
    out = subprocess.check_output(
        ["go", "list", "-m", "-json", "all"], cwd=ROOT, text=True
    )
    decoder = json.JSONDecoder()
    i = 0
    result = []
    while i < len(out):
        while i < len(out) and out[i].isspace():
            i += 1
        if i >= len(out):
            break
        value, end = decoder.raw_decode(out, i)
        result.append(value)
        i = end
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("-o", "--output", default="build/sbom.cdx.json")
    args = parser.parse_args()

    cfg = json.loads((ROOT / "mygo.json").read_text())
    components = []
    for mod in modules():
        path = mod.get("Path", "")
        version = mod.get("Version", "")
        if not path or not version:
            continue
        purl = f"pkg:golang/{path}@{version}"
        component = {
            "type": "library",
            "bom-ref": purl,
            "name": path,
            "version": version,
            "purl": purl,
        }
        if mod.get("Indirect"):
            component["scope"] = "optional"
        if mod.get("Dir"):
            component["properties"] = [{"name": "go:moduleDir", "value": mod["Dir"]}]
        components.append(component)

    digest = hashlib.sha256(
        json.dumps(components, sort_keys=True).encode()
    ).hexdigest()
    bom = {
        "bomFormat": "CycloneDX",
        "specVersion": "1.5",
        "serialNumber": "urn:uuid:" + str(uuid.uuid5(uuid.NAMESPACE_URL, digest)),
        "version": 1,
        "metadata": {
            "timestamp": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "tools": {
                "components": [
                    {
                        "type": "application",
                        "name": "openvpn-desktop-sbom",
                        "version": "1",
                    }
                ]
            },
            "component": {
                "type": "application",
                "bom-ref": cfg["identifier"],
                "name": cfg["name"],
                "version": cfg["version"],
                "purl": f"pkg:generic/{cfg['slug']}@{cfg['version']}",
            },
        },
        "components": sorted(components, key=lambda c: c["bom-ref"]),
    }
    output = ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(bom, indent=2) + "\n")
    print(f"wrote {output} ({len(components)} components)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
