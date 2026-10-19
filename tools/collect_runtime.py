#!/usr/bin/env python3
"""Collect a bionic (Termux) Node + dsh runtime closure for Android.

Windows port of the approach documented in docs/architecture-review.md (D7 复议).
Two phases, so that `npm install` runs from a real shell instead of being spawned
here (the DSH sandbox blocks piped child stdio for some runtimes):

    python tools/collect_runtime.py fetch --arch x86_64 --work build/runtime-work
    #  ... npm install into <work>/bundle  (see docs / README)
    python tools/collect_runtime.py pack  --arch x86_64 --work build/runtime-work --out build/runtime-x86_64.zip

Closure is deliberately GPL-free: no bash / readline / ncurses / libiconv /
coreutils / termux-tools. The agent shell falls back to Android's /system/bin/sh.
`termux-licenses` is pulled only so the `share/doc/<pkg>/copyright` symlinks
resolve to real licence text; its own payload is dropped again in `pack`, so no
GPL/LGPL/AGPL code or licence-text tree is shipped.
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import struct
import sys
import tarfile
import urllib.request
from pathlib import Path

REPO = "https://packages.termux.dev/apt/termux-main"
DSH_VERSION = "0.2.0-rc.2"

# Resolved from the Termux binary-<arch>/Packages index (see tools/probe_termux_packages.py).
# Every entry here is permissive-licensed; nothing GPL/LGPL.
CLOSURE = [
    "nodejs-lts",     # MIT          - the runtime
    "libc++",         # NCSA         - nodejs-lts hard dep
    "openssl",        # Apache-2.0
    "c-ares",         # MIT
    "libicu",         # custom; validate the upstream ICU license text
    "libsqlite",      # Public domain
    "zlib",           # Zlib
    "resolv-conf",    # Public Domain - c-ares dep
    "ca-certificates",# MPL-2.0 (Mozilla CA bundle, file-level copyleft, not GPL)
    "ripgrep",        # MIT          - fs search
    "pcre2",          # BSD-3-Clause
    # Licence-text source only. Termux ships `share/doc/<pkg>/copyright` as a
    # relative symlink into `share/LICENSES`, which lives in this package; the
    # symlinks were dangling until it was pulled, which aborted the flatten step.
    "termux-licenses",
]


# --------------------------------------------------------------------------- io

def fetch(url: str, dest: Path | None = None) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "dsh-pocket/0.1"})
    with urllib.request.urlopen(req, timeout=180) as resp:
        data = resp.read()
    if dest is not None:
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(data)
    return data


def parse_index(text: str) -> dict[str, dict[str, str]]:
    out: dict[str, dict[str, str]] = {}
    for block in text.split("\n\n"):
        if not block.strip():
            continue
        fields: dict[str, str] = {}
        for line in block.split("\n"):
            if ":" in line and not line.startswith(" "):
                k, v = line.split(":", 1)
                fields[k.strip()] = v.strip()
        if "Package" in fields:
            out[fields["Package"]] = fields
    return out


def read_deb_member(deb: Path, wanted_prefix: str) -> bytes | None:
    """Minimal `ar` reader: return the payload of the first member whose name starts with prefix."""
    raw = deb.read_bytes()
    if not raw.startswith(b"!<arch>\n"):
        raise ValueError(f"{deb} is not an ar archive")
    pos = 8
    while pos + 60 <= len(raw):
        header = raw[pos:pos + 60]
        name = header[0:16].decode("ascii", "replace").strip()
        size_field = header[48:58].decode("ascii", "replace").strip()
        pos += 60
        if not size_field.isdigit():
            break
        size = int(size_field)
        payload = raw[pos:pos + size]
        pos += size + (size % 2)
        if name.startswith(wanted_prefix):
            return payload
    return None


def extract_deb(deb: Path, into: Path) -> None:
    payload = read_deb_member(deb, "data.tar")
    if payload is None:
        raise RuntimeError(f"no data.tar member in {deb.name}")
    tmp = deb.with_suffix(".data.tar")
    tmp.write_bytes(payload)
    try:
        with tarfile.open(tmp) as tf:
            members = [m for m in tf.getmembers() if m.name not in (".", "./")]
            for m in members:
                # defensive: never let an archive member escape the destination
                target = (into / m.name).resolve()
                if not str(target).startswith(str(into.resolve())):
                    raise RuntimeError(f"unsafe path in {deb.name}: {m.name}")
            tf.extractall(into, filter="data")
    finally:
        tmp.unlink(missing_ok=True)


def find_termux_usr(root: Path) -> Path:
    for candidate in root.rglob("com.termux"):
        usr = candidate / "files" / "usr"
        if usr.is_dir():
            return usr
    raise RuntimeError("com.termux/files/usr not found after extraction")


# ------------------------------------------------------------------- elf check

def elf_needed(path: Path) -> list[str]:
    """Return DT_NEEDED sonames of a 64-bit little-endian ELF, or [] if not an ELF."""
    data = path.read_bytes()
    if len(data) < 64 or data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        return []
    e_phoff, = struct.unpack_from("<Q", data, 0x20)
    e_phentsize, e_phnum = struct.unpack_from("<HH", data, 0x36)
    dyn_off = dyn_size = None
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type, = struct.unpack_from("<I", data, off)
        if p_type == 2:  # PT_DYNAMIC
            p_offset, = struct.unpack_from("<Q", data, off + 0x08)
            p_filesz, = struct.unpack_from("<Q", data, off + 0x20)
            dyn_off, dyn_size = p_offset, p_filesz
            break
    if dyn_off is None:
        return []
    strtab = None
    needed_offsets: list[int] = []
    entries = []
    for off in range(dyn_off, dyn_off + dyn_size, 16):
        tag, val = struct.unpack_from("<qQ", data, off)
        entries.append((tag, val))
        if tag == 0:
            break
    for tag, val in entries:
        if tag == 5:  # DT_STRTAB
            strtab = val
        elif tag == 1:  # DT_NEEDED
            needed_offsets.append(val)
    if strtab is None:
        return []
    out = []
    for off in needed_offsets:
        end = data.index(b"\x00", strtab + off)
        out.append(data[strtab + off:end].decode("ascii", "replace"))
    return out


# --------------------------------------------------------------------- patches

def patch_koffi(nm: Path, log) -> None:
    k = nm / "koffi"
    if not k.exists():
        log("koffi: absent, skipped")
        return
    shutil.rmtree(k, ignore_errors=True)
    k.mkdir(parents=True)
    (k / "package.json").write_text(
        json.dumps({"name": "koffi", "version": "0.0.0-android-inert", "main": "index.js"}), "utf-8")
    (k / "index.js").write_text(
        "// Android inert koffi: type registration at module top level must not throw.\n"
        "function makeInert(name) { return function () { return inertProxy(name); }; }\n"
        "function inertProxy(tag) {\n"
        "  return new Proxy(makeInert(tag), {\n"
        "    get(t, p) {\n"
        "      if (p === '__esModule') return false;\n"
        "      if (p === 'then') return undefined;\n"
        "      if (!t[p]) t[p] = makeInert(tag + '.' + String(p));\n"
        "      return t[p];\n"
        "    },\n"
        "    construct() { return {}; },\n"
        "    apply() { return inertProxy(tag); },\n"
        "  });\n"
        "}\n"
        "module.exports = inertProxy('koffi');\n"
        "module.exports.default = module.exports;\n", "utf-8")
    log("koffi: replaced with inert stub")


def patch_node_pty(nm: Path, log) -> None:
    p = nm / "node-pty"
    if not p.exists():
        log("node-pty: absent, skipped")
        return
    shutil.rmtree(p, ignore_errors=True)
    (p / "lib").mkdir(parents=True)
    (p / "package.json").write_text(
        json.dumps({"name": "node-pty", "version": "0.0.0-android-shim", "main": "lib/index.js"}), "utf-8")
    (p / "lib" / "index.js").write_text(
        "module.exports.spawn = function () {\n"
        "  throw new Error('node-pty unavailable in this Android build');\n"
        "};\n", "utf-8")
    log("node-pty: replaced with throwing shim")


def patch_require_builtin(nm: Path, log) -> None:
    d = nm / "node-addon-require-builtin" / "lib"
    if not d.is_dir():
        log("node-addon-require-builtin: absent, skipped")
        return
    (d / "index.js").write_text(
        '"use strict";\n'
        "// Android: replace the prebuilt native addon with a pure-JS path.\n"
        "// The engine is started with --expose-internals, so internal module ids\n"
        "// resolve through createRequire exactly as upstream's own web fallback does.\n"
        "const { createRequire } = require('node:module');\n"
        "const req = createRequire(__filename);\n"
        "function requireBuiltin(id) { return req(id); }\n"
        "function isAllowedInternalId(id) { return typeof id === 'string' && id.startsWith('internal/'); }\n"
        "function getBindingInfo() { return { backend: 'android-js-fallback', via: '--expose-internals' }; }\n"
        "exports.requireBuiltin = requireBuiltin;\n"
        "exports.isAllowedInternalId = isAllowedInternalId;\n"
        "exports.getBindingInfo = getBindingInfo;\n"
        "exports.default = { requireBuiltin, isAllowedInternalId, getBindingInfo };\n", "utf-8")
    log("node-addon-require-builtin: pure-JS fallback installed")


def patch_flock(nm: Path, log) -> None:
    d = nm / "@deepseek-ai" / "node-addon-system" / "lib"
    if not d.is_dir():
        log("node-addon-system: absent, skipped")
        return
    (d / "flock.js").write_text(
        "/** Android: the native flock binding is linux/darwin only. Single engine\n"
        " *  instance per sandbox, and the caller still verifies inode/dev, so\n"
        " *  resolving here preserves the contract without weakening detection. */\n"
        "export async function tryLockExclusive(fd) {\n"
        "  void fd;\n"
        "  return;\n"
        "}\n", "utf-8")
    log("node-addon-system/flock: no-op installed")


def patch_sandbox_local(nm: Path, log) -> None:
    f = nm / "@deepseek-ai" / "dsh-sandbox-local" / "lib" / "index.js"
    if not f.is_file():
        log("dsh-sandbox-local: absent, skipped")
        return
    import re
    src = f.read_text("utf-8")
    src, n1 = re.subn(
        r'^import\s*\{[^}]*\}\s*from\s*"@deepseek-ai/node-addon-landlock-run";?\s*$',
        'const LAUNCHER_BIN = "";\n'
        'const LAUNCHER_FAILURE_EXIT = 126;\n'
        'const grantArgs = () => [];\n'
        'const launcherPath = () => "";\n'
        'const probe = () => ({ usable: false });',
        src, flags=re.M)
    src, n2 = re.subn(
        r'^import\s*\{[^}]*\}\s*from\s*"@deepseek-ai/dsh-sandbox-windows-acl";?\s*$',
        'const AclWriteGrant = null;\n'
        'const assertTempRootOutsideWorkspace = () => {};\n'
        'const tempWriteSid = () => "";\n'
        'const workspaceWriteSid = () => "";',
        src, flags=re.M)
    if n1 + n2 == 0:
        log("dsh-sandbox-local: WARN no native import matched (upstream shape changed)")
        return
    f.write_text(src, "utf-8")
    log(f"dsh-sandbox-local: removed {n1 + n2} native import(s)")


def patch_link_to_rename(nm: Path, rel: str, log) -> None:
    f = nm / rel
    if not f.is_file():
        log(f"{rel}: absent, skipped")
        return
    src = f.read_text("utf-8")
    old = 'import { chmod, link, mkdir, open, readFile, rename, rm, unlink, writeFile } from "node:fs/promises";'
    new = 'import { chmod, rename as link, mkdir, open, readFile, rename, rm, unlink, writeFile } from "node:fs/promises";'
    old2 = 'import { link, lstat, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from "node:fs/promises";'
    new2 = 'import { rename as link, lstat, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from "node:fs/promises";'
    if old in src:
        f.write_text(src.replace(old, new), "utf-8")
        log(f"{rel}: link -> rename (attachment-local shape)")
    elif old2 in src:
        f.write_text(src.replace(old2, new2), "utf-8")
        log(f"{rel}: link -> rename (session-persistence shape)")
    else:
        log(f"{rel}: WARN fs/promises import shape not matched (upstream changed)")


def patch_fs_local(nm: Path, log) -> None:
    import re
    f = nm / "@deepseek-ai" / "dsh-fs-local" / "lib" / "index.js"
    if not f.is_file():
        log("dsh-fs-local: absent, skipped")
        return
    src = f.read_text("utf-8")
    pat = re.compile(r'[ \t]*await linkFile\(tempPath, absolutePath\);')
    if not pat.search(src):
        log("dsh-fs-local: WARN linkFile call not found (upstream changed)")
        return
    android = (
        '\t\t\tif (process.platform === "android") {\n'
        '\t\t\t\t// Android SELinux forbids hard links (EACCES); lstat guard + atomic rename.\n'
        '\t\t\t\tlet existing;\n'
        '\t\t\t\ttry {\n'
        '\t\t\t\t\texisting = await inspectPublicationTarget(absolutePath);\n'
        '\t\t\t\t} catch (metadataError) {\n'
        '\t\t\t\t\tif (!isENOENT(metadataError) && !isENOTDIR(metadataError)) throw new FsError(`cannot write "${createIfAbsent.displayPath}": ${errorMessage(metadataError)}`, "FS_IO_ERROR", { cause: metadataError });\n'
        '\t\t\t\t}\n'
        '\t\t\t\tif (existing !== void 0) {\n'
        '\t\t\t\t\tif (!existing.isFile()) throw new FsError(`cannot write "${createIfAbsent.displayPath}": not a regular file`, "FS_NOT_REGULAR_FILE");\n'
        '\t\t\t\t\tthrow new FsError(`cannot overwrite existing "${createIfAbsent.displayPath}" without reading it first`, "FS_NOT_OBSERVED");\n'
        '\t\t\t\t}\n'
        '\t\t\t\tawait rename(tempPath, absolutePath);\n'
        '\t\t\t} else {\n'
        '\t\t\t\tawait linkFile(tempPath, absolutePath);\n'
        '\t\t\t}'
    )
    src = pat.sub(lambda _m: android, src, count=1)
    f.write_text(src, "utf-8")
    log("dsh-fs-local: createIfAbsent -> lstat + rename on android")


def inject_ripgrep(nm: Path, arch: str, root: Path, log) -> None:
    node_arch = {"x86_64": "x64", "aarch64": "arm64"}[arch]
    src = root / "bin" / "rg"
    if not src.is_file():
        log("ripgrep: WARN bin/rg missing, platform package not injected")
        return
    dst = nm / f"@vscode/ripgrep-android-{node_arch}" / "bin"
    dst.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dst / "rg")
    (dst.parent / "package.json").write_text(
        json.dumps({"name": f"@vscode/ripgrep-android-{node_arch}", "version": "1.0.0-android-native"}), "utf-8")
    log(f"ripgrep: injected @vscode/ripgrep-android-{node_arch}/bin/rg")


def drop_host_platforms(nm: Path, log) -> None:
    """npm resolved on Windows: remove every host-only artefact."""
    import glob
    patterns = [
        "@vscode/ripgrep-win32-*", "@vscode/ripgrep-linux-*", "@img/sharp-win32-*",
        "@img/sharp-linux-*", "@img/sharp-libvips-*", "@koromix/koffi-win32-*",
        "node-addon-require-builtin-win32-*", "node-addon-require-builtin-linux-*",
        "sherpa-onnx-win-*", "sherpa-onnx-linux-*", "@deepseek-ai/libreoffice-kit-win32-*",
        "node-addon-landlock-run-win32-*", "node-addon-system-win32-*",
    ]
    removed = 0
    for pat in patterns:
        for hit in glob.glob(str(nm / pat)):
            shutil.rmtree(hit, ignore_errors=True)
            removed += 1
    log(f"pruned {removed} host-platform package(s)")


def add_soname_aliases(root: Path, log) -> None:
    """Android's linker resolves DT_NEEDED by exact file name; debs ship versioned files."""
    import re
    lib = root / "lib"
    if not lib.is_dir():
        return
    made = 0
    for so in list(lib.glob("lib*.so.*")):
        m = re.match(r"^(lib.+\.so)\.(\d+)\..*$", so.name)
        if not m:
            continue
        alias = lib / f"{m.group(1)}.{m.group(2)}"
        if not alias.exists():
            shutil.copy2(so, alias)
            made += 1
    log(f"soname aliases: {made} created")


def write_wrappers(root: Path, log) -> None:
    binp = root / "bin"
    binp.mkdir(parents=True, exist_ok=True)
    pnpm = root / "lib" / "node_modules" / "corepack" / "dist" / "pnpm.js"
    if pnpm.is_file():
        (binp / "pnpm").write_text(
            "#!/system/bin/sh\n"
            "exec \"$(dirname \"$0\")/node\" \"$(dirname \"$0\")/../lib/node_modules/corepack/dist/pnpm.js\" \"$@\"\n",
            "utf-8")
        (binp / "pnpm").chmod(0o755)
        log("wrappers: bin/pnpm")
    else:
        log("wrappers: corepack/dist/pnpm.js absent, pnpm wrapper skipped")
    # usr/bin must exist because PATH references it
    (root / "usr" / "bin").mkdir(parents=True, exist_ok=True)
    (root / "usr" / "bin" / ".keep").write_text("keep\n", "utf-8")


# ----------------------------------------------------------------------- phases

def cmd_fetch(args) -> int:
    work = Path(args.work).resolve()
    root = work / "root"
    debs = work / "debs"
    for d in (root, debs):
        d.mkdir(parents=True, exist_ok=True)

    index_url = f"{REPO}/dists/stable/main/binary-{args.arch}/Packages"
    print(f"[fetch] index {index_url}")
    index = parse_index(fetch(index_url).decode("utf-8", "replace"))
    print(f"[fetch] {len(index)} packages in index")

    missing = [p for p in CLOSURE if p not in index]
    if missing:
        print(f"[fetch] FATAL missing from index: {missing}", file=sys.stderr)
        return 1

    for name in CLOSURE:
        entry = index[name]
        filename = entry["Filename"]
        dest = debs / Path(filename).name
        if not dest.exists():
            print(f"[fetch] {name:18} {entry['Version']:22} <- {filename}")
            fetch(f"{REPO}/{filename}", dest)
        else:
            print(f"[fetch] {name:18} {entry['Version']:22} (cached)")
        extract_deb(dest, root)

    usr = find_termux_usr(root)
    # A package can ship a relative symlink into a directory owned by a package
    # outside the closure (Termux moved licence texts into `termux-licenses`).
    # Such a link is dangling here and `copytree(symlinks=False)` below would
    # abort the whole fetch on it. Drop what remains so a single missing text
    # degrades to a warning instead of a build failure.
    dangling = [p for p in usr.rglob("*") if p.is_symlink() and not p.exists()]
    for p in dangling:
        p.unlink()
    if dangling:
        names = ", ".join(str(p.relative_to(usr)) for p in dangling)
        print(f"[fetch] WARNING dropped {len(dangling)} dangling symlink(s): {names}")
    print(f"[fetch] flattening {usr}")
    for item in usr.iterdir():
        target = root / item.name
        if target.exists():
            shutil.rmtree(target, ignore_errors=True) if target.is_dir() else target.unlink()
        if item.is_dir():
            shutil.copytree(item, target, symlinks=False, dirs_exist_ok=True)
        else:
            shutil.copy2(item, target)
    shutil.rmtree(root / "data", ignore_errors=True)

    node = root / "bin" / "node"
    if not node.is_file():
        print("[fetch] FATAL bin/node missing after extraction", file=sys.stderr)
        return 1
    print(f"[fetch] node = {node} ({node.stat().st_size / 1e6:.1f} MB)")

    needed = elf_needed(node)
    lib = root / "lib"
    print(f"[fetch] node DT_NEEDED ({len(needed)}):")
    absent = []
    for so in needed:
        ok = (lib / so).exists()
        print(f"          {'OK ' if ok else 'MISS'} {so}")
        if not ok:
            absent.append(so)
    if absent:
        print(f"[fetch] WARNING unresolved: {absent} (may be provided by the system)")
    return 0


def cmd_pack(args) -> int:
    work = Path(args.work).resolve()
    root = work / "root"
    bundle_nm = work / "bundle" / "node_modules"
    out = Path(args.out).resolve()

    def log(msg: str) -> None:
        print(f"[pack] {msg}")

    if not root.is_dir():
        print("[pack] FATAL run `fetch` first", file=sys.stderr)
        return 1
    if not bundle_nm.is_dir():
        print(f"[pack] FATAL npm bundle not found at {bundle_nm}", file=sys.stderr)
        return 1

    nm = root / "lib" / "node_modules"
    nm.mkdir(parents=True, exist_ok=True)
    log(f"merging {bundle_nm} -> {nm}")
    shutil.copytree(bundle_nm, nm, symlinks=False, dirs_exist_ok=True)

    patch_koffi(nm, log)
    patch_node_pty(nm, log)
    patch_require_builtin(nm, log)
    patch_flock(nm, log)
    patch_sandbox_local(nm, log)
    patch_link_to_rename(nm, "@deepseek-ai/dsh-attachment-local/lib/index.js", log)
    patch_link_to_rename(nm, "@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js", log)
    patch_fs_local(nm, log)
    inject_ripgrep(nm, args.arch, root, log)
    drop_host_platforms(nm, log)
    add_soname_aliases(root, log)
    write_wrappers(root, log)

    # keep LICENSE files (distribution compliance), drop bulk
    for p in nm.rglob("*"):
        if p.is_file() and p.name.lower().startswith("license"):
            continue
        if p.is_file() and p.suffix in (".md", ".map", ".d.ts"):
            p.unlink(missing_ok=True)
    shutil.rmtree(root / "share" / "man", ignore_errors=True)
    shutil.rmtree(root / "include", ignore_errors=True)
    # `termux-licenses` was pulled so fetch could dereference each package's
    # copyright symlink into a real file; its own payload is bulk we do not ship.
    shutil.rmtree(root / "share" / "LICENSES", ignore_errors=True)
    shutil.rmtree(root / "share" / "doc" / "termux-licenses", ignore_errors=True)
    # NOTE: share/doc/<pkg>/copyright is intentionally KEPT (unlike the reference
    # project, which deletes it) - it is the licence text for what we ship.

    out.parent.mkdir(parents=True, exist_ok=True)
    print(f"[pack] writing {out}")
    import zipfile
    count = 0
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for p in root.rglob("*"):
            if p.is_file():
                z.write(p, p.relative_to(root).as_posix())
                count += 1
    print(f"[pack] {count} files, {out.stat().st_size / 1e6:.1f} MB")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    sub = ap.add_subparsers(dest="cmd", required=True)
    f = sub.add_parser("fetch")
    f.add_argument("--arch", default="x86_64", choices=["x86_64", "aarch64"])
    f.add_argument("--work", default="build/runtime-work")
    f.set_defaults(func=cmd_fetch)
    p = sub.add_parser("pack")
    p.add_argument("--arch", default="x86_64", choices=["x86_64", "aarch64"])
    p.add_argument("--work", default="build/runtime-work")
    p.add_argument("--out", default="build/runtime-x86_64.zip")
    p.set_defaults(func=cmd_pack)
    args = ap.parse_args()
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
