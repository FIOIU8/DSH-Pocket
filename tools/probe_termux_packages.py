#!/usr/bin/env python3
"""Probe the Termux apt index for a given arch and print the entries we care about.

Read-only reconnaissance: feeds the package closure used by collect_runtime.py.
"""
from __future__ import annotations

import sys
import urllib.request

REPO = "https://packages.termux.dev/apt/termux-main"

INTERESTING = [
    "nodejs-lts", "nodejs", "ripgrep", "pcre2", "openssl", "c-ares", "libicu",
    "libsqlite", "zlib", "libc++", "libuv", "brotli", "libandroid-support",
    "ca-certificates", "libiconv", "termux-tools",
    # GPL/LGPL ones we intend to DROP unless proven necessary:
    "bash", "readline", "ncurses", "coreutils",
]


def fetch(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "dsh-pocket-probe/0.1"})
    with urllib.request.urlopen(req, timeout=90) as resp:
        return resp.read()


def parse_index(text: str) -> dict[str, dict[str, str]]:
    pkgs: dict[str, dict[str, str]] = {}
    for block in text.split("\n\n"):
        if not block.strip():
            continue
        fields: dict[str, str] = {}
        for line in block.split("\n"):
            if ":" in line and not line.startswith(" "):
                key, value = line.split(":", 1)
                fields[key.strip()] = value.strip()
        if "Package" in fields:
            pkgs[fields["Package"]] = fields
    return pkgs


def main() -> int:
    arch = sys.argv[1] if len(sys.argv) > 1 else "x86_64"
    index_url = f"{REPO}/dists/stable/main/binary-{arch}/Packages"
    print(f"# index: {index_url}")
    pkgs = parse_index(fetch(index_url).decode("utf-8", "replace"))
    print(f"# packages in index: {len(pkgs)}\n")

    for name in INTERESTING:
        entry = pkgs.get(name)
        if entry is None:
            print(f"{name:20} MISSING")
            continue
        print(f"{name:20} v{entry.get('Version', '?')}")
        print(f"{'':20} file    = {entry.get('Filename', '?')}")
        print(f"{'':20} depends = {entry.get('Depends', '-')}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
