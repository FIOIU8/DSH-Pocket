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


# The vendored engine exposes no way to rewrite a committed message, but its surface
# contract has everything the Pocket message-edit feature needs: a replace op shadows
# nodes out of the derived model context, and `session/fork` handles the user-message
# path. This patch adds one RPC, `session/editMessage`, for the in-place assistant
# edit: a developer/message sentinel replaces the target node (the UI hides all
# developer messages), then the corrected assistant/message is appended. Editing an
# assistant event directly is forbidden by the surface validators (assistant events
# cannot cite sourceEventSeqs), hence the sentinel dance.
SESSION_EDIT_SENTINEL = (
    "The assistant message that was here has been edited by the user; "
    "the following message is the corrected version."
)

def _tabs(snippet: str) -> str:
    """Re-indent a 4-space-indented snippet with tabs (index.js inline regions use tabs)."""
    return "\n".join(
        "\t" * ((len(line) - len(line.lstrip(" "))) // 4) + line.lstrip(" ")
        for line in snippet.split("\n")
    )


# `paginate` filters the page by `isAppendSurfaceEvent`, which drops the replacement
# event itself but keeps every event the replacement shadowed: the page then carries
# both the old and the corrected message. Fold the shadow set out of the same log so a
# page is the current message surface, not every append that ever happened.
SESSION_PAGE_SHADOW_HELPER_JS = """/**
 * Seqs a surface `replace` op has shadowed out of the current message surface.
 * The surface validators require a replacement to cite every node it shadows, so
 * `sourceEventSeqs` is the complete shadow set without folding the log.
 * @param events - complete contiguous raw event log.
 * @returns the shadowed seqs.
 */
function shadowedEventSeqs(events) {
\tconst shadowed = /* @__PURE__ */ new Set();
\tfor (const event of events) {
\t\tconst op = event.surfaceOp;
\t\tif (op === void 0 || op === null || typeof op !== "object" || op.op !== "replace") continue;
\t\tfor (const seq of event.sourceEventSeqs ?? []) shadowed.add(seq);
\t}
\treturn shadowed;
}

"""

SESSION_EDIT_METHOD_JS = """    async editMessage(request) {
        if (typeof request.content !== 'string' || request.content.trim().length === 0) {
            throw new RemoteError('gateway/bad-request', 'editMessage content must include non-whitespace text', {});
        }
        const atSeq = request.atSeq;
        if (!Number.isSafeInteger(atSeq) || atSeq < 0) {
            throw new RemoteError('gateway/bad-request', 'atSeq must be a non-negative safe integer', {});
        }
        let agent = this.ctx.agents.get(request.sessionId);
        if (agent === undefined) {
            const found = await this.agents.resolveAgent(request.sessionId);
            if ('error' in found) throw found.error;
            agent = found.agent;
        }
        if (hasApiSessionSubagentOwner(this.ctx, agent.session, agent)) {
            throw apiSessionSubagentOwnershipError(request.sessionId);
        }
        if (agent.status === 'running') {
            throw new RemoteError('session/agent-busy', 'cannot edit a message while the agent is running', { sessionId: request.sessionId });
        }
        const session = agent.session;
        const target = session.snapshotEvents()[atSeq];
        if (target === undefined || target.type !== 'assistant/message') {
            throw new RemoteError('session/edit-target-invalid', `event ${String(atSeq)} is not an assistant message in session "${request.sessionId}"`, { sessionId: request.sessionId });
        }
        const selection = this.agents.selectionFor(agent).current;
        const turn = target.data?.turn ?? 0;
        const step = target.data?.step ?? 0;
        try {
            const note = createDeveloperMessage({
                source: { kind: 'pocket-edit' },
                content: [{ type: 'text', text: %s }],
            });
            session.append('developer/message', { message: note }, {
                surfaceOp: { op: 'replace', startSeq: atSeq, endSeq: atSeq },
                sourceEventSeqs: [atSeq],
            });
            const message = createAssistantMessage({
                content: [{ type: 'text', text: request.content }],
                source: { provider: selection.provider, model: selection.model },
            });
            // `stream: []` is load-bearing: an Assistant settlement must embed its
            // provider stream array (the token-meter projections call
            // lastAssistantStreamChunk(data.stream) unconditionally). An edited
            // message has no live stream behind it, so it records an empty one and
            // the metering projections fold it as "no usage sample".
            const seq = session.append('assistant/message', { turn, step, message, stream: [] }, { surfaceOp: 'append' }).seq;
            return { seq };
        }
        catch (error) {
            throw new RemoteError('session/edit-unavailable', `failed to record the edit in session "${request.sessionId}": ${String(error)}`, { sessionId: request.sessionId });
        }
    }
""" % json.dumps(SESSION_EDIT_SENTINEL)

SESSION_EDIT_FORWARD_JS = """\t\t/**
\t\t* Rewrite one assistant surface message in place (DSH Pocket edit).
\t\t* @param request - Session identity, target event seq, and edited text.
\t\t* @returns acknowledgement with the new assistant message seq.
\t\t*/
\t\teditMessage(request) {
\t\t\treturn this.commands.editMessage(request);
\t\t}
"""

SESSION_EDIT_SCHEMAS_JS = """let _deepseek_ai_dsh_api_session_controller_session_editMessage_parameter_0$schema$value
const _deepseek_ai_dsh_api_session_controller_session_editMessage_parameter_0$schema = () => (_deepseek_ai_dsh_api_session_controller_session_editMessage_parameter_0$schema$value ??= z.object({
  'sessionId': z.intersection(z.string(), z.unknown()).readonly(),
  'atSeq': z.number().readonly(),
  'content': z.string().readonly(),
}))
let _deepseek_ai_dsh_api_session_controller_session_editMessage_result$schema$value
const _deepseek_ai_dsh_api_session_controller_session_editMessage_result$schema = () => (_deepseek_ai_dsh_api_session_controller_session_editMessage_result$schema$value ??= z.object({
  'seq': z.number().readonly(),
}))
"""

SESSION_EDIT_PROVIDER_JS = """    {
      id: '@deepseek-ai/dsh-api-session-controller#session/editMessage',
      service: 'sessionController',
      namespace: 'session',
      method: 'editMessage',
      invocation: { kind: 'direct' },
      parameters: [
        {
          name: 'request',
          wire: 'request',
          source: 'json',
          codec: {
            mode: 'strict',
            typeSymbol: '@deepseek-ai/dsh-api-session-controller/types#SessionEditMessageRequest',
            create: _deepseek_ai_dsh_api_session_controller_session_editMessage_parameter_0$schema,
          },
        },
      ],
      result: {
        mode: 'strict',
        typeSymbol: '@deepseek-ai/dsh-api-session-controller/types#SessionEditMessageValue',
        create: _deepseek_ai_dsh_api_session_controller_session_editMessage_result$schema,
      },
      sourceLocation: {"file":"packages/api/session-controller/src/index.ts","line":1,"column":1},
    },
"""


def patch_session_edit(nm: Path, log) -> None:
    """Expose session/editMessage for the Pocket in-place assistant-message edit.

    `lib/index.js` is the runtime bundle: it inlines `lib/types/commands.js` as a
    `//#region` block, so the controller that actually runs is the copy inside
    index.js. Patching only the standalone `types/commands.js` ships dead code and
    the RPC fails at call time with `this.commands.editMessage is not a function`.
    """
    pkg = nm / "@deepseek-ai" / "dsh-api-session-controller" / "lib"
    index = pkg / "index.js"
    host = pkg / "typert.host.js"
    if not (index.is_file() and host.is_file()):
        log("dsh-api-session-controller: expected files missing, skipped")
        return

    src = index.read_text("utf-8")
    if "shadowedEventSeqs" in src:
        log("session-edit: already patched")
        return

    llm_import = (
        'import { AssistantStreamAccumulator, ReasoningEffortId, assistantStreamChunks, '
        'createUserMessage, errorChain, freezeMessage } from "@deepseek-ai/dsh-llm";'
    )
    llm_import_patched = (
        'import { AssistantStreamAccumulator, ReasoningEffortId, assistantStreamChunks, '
        'createAssistantMessage, createDeveloperMessage, createUserMessage, errorChain, '
        'freezeMessage } from "@deepseek-ai/dsh-llm";'
    )
    # The commands class is the SECOND `resolveAgent` in the bundle (the first is the
    # Agent facade); this anchor's body tells them apart.
    resolve_anchor = (
        "\tasync resolveAgent(sessionId) {\n"
        "\t\tconst found = await this.agents.resolveAgent(sessionId);\n"
    )
    cancel_anchor = "\t\tcancel(request) {\n\t\t\treturn this.commands.cancel(request);\n\t\t}\n"
    paginate_anchor = (
        "function paginate(events, beforeSeq, maxMessages, throughSeq, turnWindow) {\n"
        "\tconst end = SessionLogOffset(Math.min(throughSeq + 1, beforeSeq ?? throughSeq + 1));\n"
    )
    paginate_patched = (
        SESSION_PAGE_SHADOW_HELPER_JS
        + "function paginate(events, beforeSeq, maxMessages, throughSeq, turnWindow) {\n"
        "\tconst end = SessionLogOffset(Math.min(throughSeq + 1, beforeSeq ?? throughSeq + 1));\n"
        "\tconst shadowed = shadowedEventSeqs(events);\n"
    )
    page_skip_anchor = (
        "\tfor (let index = end - 1; index >= 0; index--) {\n"
        "\t\tconst event = events[index];\n"
    )
    page_skip_patched = page_skip_anchor + (
        "\t\tif (shadowed.has(event.seq)) continue;\n"
    )
    fork_schema_anchor = "let _deepseek_ai_dsh_api_session_controller_session_fork_parameter_0$schema$value\n"
    fork_entry_anchor = (
        '      sourceLocation: {"file":"packages/api/session-controller/src/index.ts","line":415,"column":3},\n'
        "    },\n"
    )
    if not (
        llm_import in src
        and resolve_anchor in src
        and cancel_anchor in src
        and paginate_anchor in src
        and page_skip_anchor in src
    ):
        log("session-edit: WARN index.js anchors not matched (upstream changed)")
        return
    src = src.replace(llm_import, llm_import_patched, 1)
    src = src.replace(resolve_anchor, _tabs(SESSION_EDIT_METHOD_JS) + resolve_anchor, 1)
    src = src.replace(cancel_anchor, cancel_anchor + SESSION_EDIT_FORWARD_JS, 1)
    src = src.replace(paginate_anchor, paginate_patched, 1)
    src = src.replace(page_skip_anchor, page_skip_patched, 1)
    index.write_text(src, "utf-8")

    src = host.read_text("utf-8")
    if not (fork_schema_anchor in src and fork_entry_anchor in src):
        log("session-edit: WARN typert.host.js anchors not matched (upstream changed)")
        return
    src = src.replace(fork_schema_anchor, SESSION_EDIT_SCHEMAS_JS + fork_schema_anchor, 1)
    src = src.replace(fork_entry_anchor, fork_entry_anchor + SESSION_EDIT_PROVIDER_JS, 1)
    host.write_text(src, "utf-8")
    log("session-edit: session/editMessage RPC installed")


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
    """npm resolved on Windows: remove every host-only artefact.

    `@img/sharp-wasm32` must survive: it is the only image decoder the runtime has
    (Android has no native sharp prebuilt), which is why no pattern below matches it.
    ensure_sharp_wasm() re-asserts its presence after pruning.
    """
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


def ensure_sharp_wasm(nm: Path, log) -> None:
    """Fail the build when the WebAssembly sharp binding is missing.

    sharp's loader falls back to `@img/sharp-wasm32` when no native prebuilt matches
    (Android never does). Without it every image path on the device dies with
    `Could not load the "sharp" module using the android-* runtime`. The package is not
    a sharp optionalDependency — it must be installed explicitly alongside dsh (see
    README "Prepare runtime") — and a silent omission would only surface on the device.
    """
    glue = nm / "@img" / "sharp-wasm32" / "index.cjs"
    wasms = list((nm / "@img" / "sharp-wasm32" / "lib").glob("*.wasm")) if (nm / "@img" / "sharp-wasm32" / "lib").is_dir() else []
    if not glue.is_file() or not wasms:
        log("FATAL @img/sharp-wasm32 missing from the npm bundle — the runtime would have no image decoder")
        raise SystemExit(1)
    log(f"sharp wasm fallback present: {wasms[0].name} ({wasms[0].stat().st_size / 1e6:.1f} MB)")


ELF_MACHINES = {62: "x86_64", 183: "aarch64", 40: "arm", 3: "x86"}


def ensure_native_arch(root: Path, arch: str, log) -> None:
    """Fail the build when the extracted Termux prefix is not the requested arch.

    The work dir's `root/` is fully derived from the last `fetch`, and a fetch for a
    different arch (say a local arm64 build after an x86_64 one) leaves the prefix
    silently switched. Packing that with `--arch x86_64` produced a mixed-arch zip that
    crashed on device inside the ARM translator with a bare SIGSEGV — nothing at pack
    time caught it because the file layout looked perfectly normal.
    """
    expected = {"x86_64": 62, "aarch64": 183}[arch]
    for rel in ("bin/node", "lib/libc++_shared.so"):
        target = root / rel
        if not target.is_file():
            log(f"FATAL {rel} missing from the runtime root — run `fetch` first")
            raise SystemExit(1)
        with target.open("rb") as f:
            head = f.read(20)
        if head[:4] != b"\x7fELF":
            log(f"FATAL {rel} is not an ELF file")
            raise SystemExit(1)
        machine = struct.unpack_from("<H", head, 18)[0]
        if machine != expected:
            found = ELF_MACHINES.get(machine, str(machine))
            log(
                f"FATAL {rel} is built for {found}, but --arch {arch} was requested. "
                f"The work dir's root/ belongs to another architecture; delete <work>/root "
                f"and re-run `fetch --arch {arch}`."
            )
            raise SystemExit(1)
    log(f"native arch check passed ({arch})")


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

    # A work dir reused across architectures carries a prefix extracted for the other
    # arch; packing it with this arch ships a mixed-arch runtime (caught by
    # ensure_native_arch, but wiping here is the actual fix). root/ is fully derived
    # from the cached debs, so removing it costs only a re-extraction.
    arch_stamp = work / "root-arch"
    if arch_stamp.is_file() and arch_stamp.read_text("utf-8").strip() != args.arch:
        previous = arch_stamp.read_text("utf-8").strip()
        print(f"[fetch] arch changed ({previous} -> {args.arch}); wiping the stale root/")
        shutil.rmtree(root, ignore_errors=True)
        root.mkdir(parents=True, exist_ok=True)

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
        # Debian epochs put a colon in the version (e.g. "1:2026.10.20-1"), which is
        # not a legal Windows filename character: a naive path silently lands in an NTFS
        # alternate data stream of a different file, and a later exists() check on the
        # stream path reports the base file as cached. Sanitize before touching disk.
        safe_name = Path(filename).name.replace(":", "+")
        dest = debs / safe_name
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
    arch_stamp.write_text(args.arch, "utf-8")
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
    patch_session_edit(nm, log)
    inject_ripgrep(nm, args.arch, root, log)
    drop_host_platforms(nm, log)
    ensure_sharp_wasm(nm, log)
    ensure_native_arch(root, args.arch, log)
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
