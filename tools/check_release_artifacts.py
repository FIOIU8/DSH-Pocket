#!/usr/bin/env python3
"""Validate the generated runtime archive and report reproducible release metadata."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import zipfile
from pathlib import Path


ABI_MARKERS = {
    "aarch64": "ripgrep-android-arm64",
    "x86_64": "ripgrep-android-x64",
}


def archive_report(path: Path, expected_arch: str) -> dict[str, object]:
    if expected_arch not in ABI_MARKERS:
        raise ValueError(f"unsupported architecture: {expected_arch}")
    if not path.is_file():
        raise FileNotFoundError(path)
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
    wrong_markers = {
        marker
        for other_arch, markers in ABI_MARKERS.items()
        if other_arch != expected_arch
        if any(markers in name.lower() for name in names)
    }
    if wrong_markers:
        raise ValueError(f"runtime archive contains markers for another ABI: {sorted(wrong_markers)}")
    expected_marker = ABI_MARKERS[expected_arch]
    if not any(expected_marker in name.lower() for name in names):
        raise ValueError(f"runtime archive does not contain the expected ABI marker: {expected_marker}")
    if not any("node" in name.lower() for name in names):
        raise ValueError("runtime archive does not contain a Node runtime")
    return {
        "path": str(path),
        "sha256": digest,
        "bytes": path.stat().st_size,
        "files": len(names),
        "abi": expected_arch,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--runtime", required=True, type=Path)
    parser.add_argument("--arch", required=True, choices=sorted(ABI_MARKERS))
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--baseline-runtime-mb", type=float, default=121.2)
    parser.add_argument("--max-growth", type=float, default=0.10)
    args = parser.parse_args()

    report = archive_report(args.runtime, args.arch)
    baseline = args.baseline_runtime_mb * 1024 * 1024
    if report["bytes"] > baseline * (1 + args.max_growth):
        raise ValueError(
            f"runtime grew beyond {args.max_growth:.0%}: "
            f"{report['bytes']} > {int(baseline * (1 + args.max_growth))} bytes"
        )
    if args.apk:
        if not args.apk.is_file():
            raise FileNotFoundError(args.apk)
        report["apkBytes"] = args.apk.stat().st_size
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, zipfile.BadZipFile) as exc:
        print(f"release artifact check failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
