#!/usr/bin/env python3
"""Select a published DSH npm version by offset in the official GitHub releases."""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

RELEASES_URL = "https://api.github.com/repos/deepseek-ai/deepseek-harness/releases"


def fetch_releases() -> list[dict[str, object]]:
    releases: list[dict[str, object]] = []
    token = os.environ.get("GH_TOKEN")
    page = 1
    while True:
        request = urllib.request.Request(
            f"{RELEASES_URL}?per_page=100&page={page}",
            headers={
                "Accept": "application/vnd.github+json",
                "User-Agent": "DSH-Pocket-release-workflow",
                **({"Authorization": f"Bearer {token}"} if token else {}),
            },
        )
        with urllib.request.urlopen(request, timeout=30) as response:
            batch = json.load(response)
        if not isinstance(batch, list):
            raise RuntimeError("GitHub returned an unexpected releases response")
        releases.extend(batch)
        if len(batch) < 100:
            return releases
        page += 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, help="GitHub Actions output file")
    args = parser.parse_args()

    raw_offset = os.environ.get("DSH_OFFSET", "").strip()
    if raw_offset and not re.fullmatch(r"-?\d+", raw_offset):
        parser.error("DSH offset must be blank, 0, or a negative integer such as -1")
    offset = int(raw_offset or "0")
    if offset > 0:
        parser.error("DSH offset must be 0 or negative")

    try:
        releases = fetch_releases()
    except (OSError, urllib.error.URLError, json.JSONDecodeError) as error:
        print(f"Could not fetch official DSH releases: {error}", file=sys.stderr)
        return 1

    candidates = [
        release
        for release in releases
        if isinstance(release, dict)
        and isinstance(release.get("tag_name"), str)
        and release["tag_name"].startswith("dsh-v")
        and isinstance(release.get("published_at"), str)
    ]
    candidates.sort(key=lambda release: release["published_at"], reverse=True)
    index = -offset
    if index >= len(candidates):
        print(
            f"DSH offset {offset} is outside the {len(candidates)} published DSH releases.",
            file=sys.stderr,
        )
        return 1

    release = candidates[index]
    tag = release["tag_name"]
    version = tag.removeprefix("dsh-v")
    if not version:
        print(f"Could not derive an npm version from release tag {tag!r}.", file=sys.stderr)
        return 1

    if args.output:
        with args.output.open("a", encoding="utf-8") as output:
            output.write(f"version={version}\n")
            output.write(f"release_tag={tag}\n")
            output.write(f"release_url={release.get('html_url', '')}\n")
    print(f"Selected official DSH release {tag} (npm @deepseek-ai/dsh@{version})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
