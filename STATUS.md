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
| `testDebugUnitTest` | pass (121 tests) |
| `lintDebug` | **0 errors**, 52 warnings (W family: `UseKtx`, `AppBundleLocaleChanges`) |

Criterion 1 of Phase 1 is closed: the 43 `LocalContext*` / `NonObservableLocale`
errors from the start of the phase reached 0 through the repository slices
below, with no Suppress anywhere. The 52 warnings are all pre-existing
`UseKtx` / `AppBundleLocaleChanges` families, none flagged in this phase.

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

Sequence: Session repository and screen are done, Home is the second consumer
and is done, and the 17 errors outside any repository slice are cleared. What
remains, in order:

1. ~~`ModelsScreen` slice~~ — done. Parser, models and read path in
   `ui/state/Model*`; screen wired through `collectAsState`; the write paths
   (persist, delete, key set/unset, DeepSeek login flow) stay in the screen with
   their own busy/error handling, and the repository gained `refreshAccount`
   and `publishAccount` for the login poll.
2. ~~`PluginRepository` slice~~ — done, closed criterion 1. `PluginRow` now
   holds raw `LocalizedText`; module-name fallback, locale resolution and the
   localized-title sort moved to render time.
3. ✔ Criterion 1 closed at 0 errors.
4. `ExecutionBackend` skeleton, after this phase.

### The lint baseline is 43, and the target is 0

`clean lintDebug` reported 43 errors at the start of Phase 1: 39
`LocalContextGetResourceValueCall`, 2 `LocalContextConfigurationRead`
(PluginsScreen), 2 `NonObservableLocale` (NativeChatScreen). The target is
**0 errors**, not "clear the ones we know about".

**No Suppress is used anywhere for these, and none is warranted.** All were
real: each read a resource, a locale or a configuration through
`LocalContext.current` inside a composable, so the value was captured once and
the composable did not recompose when it should.

**Target reached: 0 errors.** The three resolution patterns the fixes produced,
worth remembering:

- Finished strings usable at composition time: `stringResource(...)`, hoisted
  and captured.
- Strings that need engine-supplied format arguments only known inside a
  lambda: a formatter built on `LocalResources.current`, which itself
  recomposes on configuration change (`context.resources` reached through
  `LocalContext.current` does not, and is still flagged).
- Locale/localeTag values: `LocalConfiguration.current.locales[0]` at
  composition scope, included in the key of anything polling with it so the
  loop restarts on a locale change instead of reading a stale context.

### Localisation debt the lint checks do not catch

Found while fixing the above; none of it is reported by lint.

- `NativeChatScreen.parseNativeChatSessions` defaults an untitled session to the
  hardcoded English `"Untitled session"`, and `parseNativeModelCatalog` to the
  hardcoded `"模型默认"`. Both are non-Composable helpers, so a literal is the
  only thing they can currently do; the fix is to hoist the label the way the
  composable now hoists its own.
- `formatMessageTime` builds `SimpleDateFormat("MM月dd日 HH:mm:ss", Locale.getDefault())`
  — a Chinese date pattern hardcoded in a non-Composable function, and the only
  one an English user will never see in the right shape.

### Incremental lint counts are not trustworthy here

Two of these findings were wrong before they were measured:

- The two `NonObservableLocale` errors on pre-existing `NativeChatScreen` lines
  were absent from an earlier incremental report. Removing the
  `lifecycle-viewmodel-compose` added in this phase leaves the count at 43, so
  they were masked rather than introduced.
- **Phase 0's `StaticFieldLeak` conclusion needs re-checking under a full run.**
  The original judgement — that there was no leak, because both call sites
  already passed an `Application` — was reasoned from the source and then
  confirmed by the warning disappearing after the type was tightened. That
  confirmation came from an incremental run. A `clean lintDebug` after the
  change reported zero `StaticFieldLeak`, so the conclusion holds, but it
  should have been established this way the first time.

Treat a lint count as real only when it comes from `clean`.

## Known gaps

### Archived-set state is lost across engine restarts — deliberately not fixed

`session/list` does not report archived flags. The archived set is therefore
maintained from the responses to `workspace/archiveSession` and
`workspace/unarchiveSession`, so it starts empty after an engine restart.

**Left as is on purpose.** Phase 1 is a behaviour-equivalence refactor; closing
this gap would change behaviour and remove the baseline the six equivalence
tests are measured against. Fix it in its own change, once the new tests can hold
the fixed behaviour.

Same retention class, replicated rather than fixed, in the other two
repositories: a failed `llm/listProviders` keeps the previous builtin list (and
the official-credential check runs against it), a failed
`credentials/describe` keeps the previous key states, and a failed plugin list
keeps the previous rows. Each named test pins it.

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

`ModelRepository` and `PluginRepository` take the same shape (`get()` singleton in
AppShell + `createForTest()` seam), because their screens have the analogous coupled
concerns — retained rows across re-entry, retained key states and builtin list across
failed reloads.

The clean fix is to hold the cache in a container and inject it, which would delete these
methods. That is not worth a DI framework in a single-module app, so the seam stays and
this note records why it exists.



`app/build.gradle.kts` and `RuntimeInstaller.kt` cite `docs/architecture-review.md`
as the source of their design decisions, but `/docs/` is gitignored so that file
is not in the repository. Recorded rather than fixed: bringing the architecture
log into version control, or repointing the comments, are both larger decisions
than Phase 1 should make on its own.
