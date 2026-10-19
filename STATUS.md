# DSH Pocket — Status

This file replaces `docs/status.md`. It lives at the repository root because
`.gitignore` excludes `/docs/`, so anything written there never reaches version
control. `docs/status.md` is stale (it predates the native chat screen) and is
deprecated; keep it only as local scratch.

Last updated: 2026-10-19

## Build state

| Task | Result |
|---|---|
| `assembleDebug` | pass |
| `testDebugUnitTest` | pass (65 tests) |
| `lintDebug` | **43 errors**, all `LocalContext*` in Compose |

The 43 are not cosmetic. They mark every place that reads a resource or a locale
through `LocalContext.current` inside a composable, which means the value is
captured once and never recomposes. The fix is tracked as Phase 1 below.

## Architecture rules

These are decisions, not preferences. Change them only deliberately.

### Navigation stays in `rememberSaveable`; data goes in a ViewModel

`ViewModel` does not survive process death; `rememberSaveable` does. Navigation
state (`destination`, `settingsSection`, the settings sub-pages) must survive
being killed in the background, so it stays where it is. Anything the engine
produces — session lists, plugin rows, provider config — belongs in a ViewModel
backed by a repository, because it is asynchronous and must not be cancelled by
navigating away.

### Models hold raw values, not rendered strings

One principle, two types:

- **`@StringRes Int`** for app-owned text. The UI calls `stringResource`.
- **`LocalizedText`** for engine-supplied text that arrives as either a plain
  string or a `{zh-CN, en}` object. The UI resolves it against
  `LocalConfiguration.current` at render time.

Baking a resolved `String` into a model is the bug both lint checks are pointing
at: the language gets frozen at parse time and goes stale when the user switches
locale.

### The engine layer is a seam; the UI layer was not

`engine/` has real seams — `EngineApi` (RPC), `EnginePaths` (layout),
`RuntimeInstaller` (unpack), `EngineController` (lifecycle). `ui/` had none:
five screens each ran their own `while (true)` poll loop and each hand-parsed
JSON. Phase 1 adds the missing layer.

## Phase 1 — state layer (in progress)

Four exit criteria:

1. **Green.** `testDebugUnitTest` and `lintDebug` both pass; the 43
   `LocalContext*` / `NonObservableLocale` errors reach zero.
2. **Behaviour equivalence.** The Session vertical slice keeps the current
   behaviour of six behaviours — list, search, filter, grouping, archive,
   restore — one repository test each, each calling the shipped implementation
   rather than a copy of it.
3. **Poll convergence.** `SessionsScreen` no longer owns a `while (true)` loop.
   Verified by re-entering the same screen a second time and observing that
   `session/list` is not fetched again.
4. **Boundary written down.** The rules above are stated here and in `AppShell`,
   so the remaining screens have one pattern to follow rather than four
   interpretations.

Sequence: `SessionRepository` → `SessionViewModel` → `SessionsScreen` → tests →
lint → `HomeScreen` as the second consumer, which is what actually proves the
shared data works. The Home slice adds two more checks: with both screens
composed, `session/list` is still called once, and `HomeScreen` does not gain a
poll loop of its own.

### The lint baseline is 43, measured by a clean run

`clean lintDebug` reports 43 errors: 39 `LocalContextGetResourceValueCall`,
2 `LocalContextConfigurationRead` (PluginsScreen), 2 `NonObservableLocale`
(NativeChatScreen). Both extra kinds sit on pre-existing lines — removing the
`lifecycle-viewmodel-compose` added in this phase leaves the count unchanged at
43 — so they were masked rather than introduced by an incremental run.

## Known gaps

### Archived-set state is lost across engine restarts — deliberately not fixed

`session/list` does not report archived flags. The archived set is therefore
maintained from the responses to `workspace/archiveSession` and
`workspace/unarchiveSession`, so it starts empty after an engine restart.

**Left as is on purpose.** Phase 1 is a behaviour-equivalence refactor; closing
this gap would change behaviour and remove the baseline the six equivalence
tests are measured against. Fix it in its own change, once the new tests can hold
the fixed behaviour.

### A nested managed marker leaks text in `AGENTS.md`

`AgentBriefing.mergeManagedInstructions` pairs from the *inner* start marker when
one is nested inside another, so the outer block's text falls outside the
replacement and is preserved as user content — where it will never be refreshed.
Preserving is the safer failure: deleting text from a file the app only partly
owns is the one outcome the user cannot undo. Covered by
`nestedStartMarkerKeepsTextRatherThanDeletingIt`.

### Not implemented

- Termux / Shizuku execution bridges. `Capabilities.snapshot()` returns
  `Unavailable` unconditionally; `CompanionStatus.AUTHORIZED`, `RUNNING` and
  `BRIDGE_CONNECTED` are currently unreachable. Architecture decision D11 called
  for an `ExecutionBackend` seam up front; it does not exist yet.
- Plugin install / remove (`installBundle` / `removeBundle`) and schema-driven
  configuration forms.
- An agent-initiated permission request tool. The app only *states* capabilities
  via `AGENTS.md`.
- Remote Host mode (SSH tunnel).

## Execution constraints

### Do not edit files through a PowerShell text pipeline

Twice now, a `Get-Content -Raw` / `Set-Content` round trip has corrupted source in this
repo: it injects a UTF-8 BOM where the file had none, and it mangles non-ASCII
characters — an em dash in a comment came back as `鈥?`.

Use the editor tools, which write UTF-8 without a BOM. If a shell command must touch a
file, read the bytes, strip `U+FEFF`, and write back through
`[System.IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding($false)))`
— then verify the first bytes are not `239,187,191`.

## Known technical debt

### `SessionRepository.createForTest` exists because the real getter is a process singleton

`SessionRepository.get()` caches one instance per process, which is what lets the home
card and the session screen share one source of truth. A process-wide cache cannot be
reset from a test, so the class exposes a second constructor for tests.

The clean fix is to hold the cache in a container and inject it, which would delete this
method. That is not worth a DI framework in a single-module app, so the seam stays and
this note records why it exists.



`app/build.gradle.kts` and `RuntimeInstaller.kt` cite `docs/architecture-review.md`
as the source of their design decisions, but `/docs/` is gitignored so that file
is not in the repository. Recorded rather than fixed: bringing the architecture
log into version control, or repointing the comments, are both larger decisions
than Phase 1 should make on its own.
