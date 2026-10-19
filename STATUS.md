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
| `testDebugUnitTest` | pass (134 tests) |
| `lintDebug` | **0 errors**, 51 warnings (all pre-existing families) |

The 134 is what a `clean` run reports: 122 before Phase 2, plus the 12 seam
tests. The earlier "121 tests" was stale by one, and the earlier "52
warnings, W family `UseKtx` / `AppBundleLocaleChanges`" misdescribed the set
— the families are `UnusedResources` (20), `TypographyEllipsis` (10),
`PluralsCandidate` (7), `UseKtx` (7), `NewerVersionAvailable` (4),
`AppBundleLocaleChanges` (1), `RedundantLabel` (1) and `GradleDependency` (1).
None of them is in a file Phase 2 touched.

Criterion 1 of Phase 1 is closed: the 43 `LocalContext*` / `NonObservableLocale`
errors from the start of the phase reached 0 through the repository slices
below, with no Suppress anywhere.

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

## Phase 1 — state layer (closed 2026-10-19)

Four exit criteria, **all four met**:

1. ~~**Green.**~~ ✔ `testDebugUnitTest` 122 tests pass; `lintDebug` 0 errors
   (down from 43 at the start of the phase; 51 warnings, all pre-existing).
2. ✔ **Behaviour equivalence.** The Session slice pinned its six behaviours —
   list, search, filter, grouping, archive, restore — with one test each
   against shipped code. The Models and Plugins repos pin their own read
   paths the same way: every retaining-on-failure field (builtin list, key
   states, plugin rows), the banner lifecycle, the unconditional accountView
   overwrite, the toggle-then-reload clearing order.
3. ✔ **Poll convergence.** `SessionsScreen` no longer owns a `while (true)`
   loop; the AppShell-owned poll starts once (idempotent). Plugins keeps its
   own 15s in-screen poll and Models keeps a login-phase account poll — both
   shipped behaviours recorded in the AppShell header rules, not regressions.
4. ✔ **Boundary written down.** Stated here and in `AppShell`, including the
   three sanctioned `LocalContext*` resolutions (hoisted `stringResource`,
   `LocalResources` formatter, `LocalConfiguration` in poll keys).

What the phase produced:

- `ui/state/` — `SessionRepository`, `ModelParser`/`ModelRepository`,
  `PluginParser`/`PluginRepository` (`LocalizedText` type introduced here
  first), each a `get()` singleton created in `AppShell` with a live source
  lambda plus a `createForTest` seam. Screens are renderers; `ModelsScreen`
  keeps its write paths (persist, delete, key set/unset, DeepSeek login)
  locally by design.
- `ModelsScreen` lost its nine remember'd data variables and its inline
  JSON walk; the "模型只存原始值" principle is implemented in `PluginRow`
  first, others to follow when touched.

Sequence history: Session slice, Home as second consumer, 17 errors from the
label pass, then the two repository slices. One correction along the way is
worth noting: the first extraction of `ModelRepository` invented semantics the
shipped screen doesn't have (clear-on-reload, reset-on-failure, a
`credentialError` pseudo-state, a repository-owned busy flag). Commit
`6d97005` replaced them with line-for-line replicas and the tests were re-
pinned to the shipped behaviour — each test name names the line it protects.

## Phase 2 — the execution seam (closed 2026-10-19)

Architecture decision D11 called for an `ExecutionBackend` seam "up front",
because the Termux and Shizuku bridges are two implementations of one thing
and retrofitting that costs more than declaring it. The seam now exists.

What landed:

- `capability/ExecutionBackend.kt` — the seam itself: `ExecutionBackend` (a
  backend reports one companion's state), `DeviceQuery` (the narrow port the
  backend looks at, so the seam is testable without a device), the shipped
  `PackagePresenceBackend`, and the `CompanionBackends` registry.
- `capability/CompanionStateMachine.kt` — the one rule that decides what two
  companion states mean for process execution. It used to be inline in
  `Capabilities.snapshot`, which is why no rule about it could be tested.
- `Capabilities.snapshotOf(...)` — the pure core of a snapshot, no Android
  types in it, so every rule the snapshot encodes is testable on the JVM.
  `Capabilities.snapshot(...)` now only gathers the inputs and delegates.
- The shipped `ExecutionBackend` enum was renamed `ExecutionBackendKind` to
  free the name for the interface. The wire value (`"termux"` / `"shizuku"`),
  the JSON key (`processExecutionBackendKind`) and the settings label are
  unchanged — the enum was the odd one out, the wire field already said
  "Kind".

**No runtime value moved.** The production backend does exactly the
package-presence check the shipped code did, so `processExecution` is still
`Unavailable` and every status is what it was. What the phase bought is that
the rungs `AUTHORIZED`, `RUNNING` and `BRIDGE_CONNECTED` — and `UNSUPPORTED`,
which was unreachable too — now have a code path that can produce them: a
backend that reports `BRIDGE_CONNECTED` really does yield `Available`
execution, the `"bridge_connected"` wire value, and the agent briefing that
says the bridge is connected. The 12 tests in `ExecutionBackendTest` drive
that path end to end; a real bridge is what makes it reachable on a device.

Two decisions were taken against the note this phase started from, and the
reasons are worth keeping:

1. **The `CompanionStatus` enum was not replaced.** The proposal was a sealed
   type carrying payloads (`Authorized(binaryVersion)`,
   `Running(binaryVersion, bridgeConnected)`). The shipped enum already models
   the ladder correctly, and the proposal would have dropped two live states
   (`INSTALLED` and `UNSUPPORTED` — both render in the settings screen, and
   both are written to `capabilities.json`) and changed the wire contract.
   `binaryVersion` has no source today. The rules moved *behind* the enum as
   pure functions instead.
2. **The seam is live in the production path, not a mock.** A mock backend
   would either have shipped as dead code or changed what the user sees. The
   production registry is exercised on every read, so the seam cannot rot.

One deviation from the plan this phase ran on: `AgentBriefing.renderCapabilities`
went from `private` to `internal`, so the test can read the briefing text the
agent actually gets. `AgentBriefing.write` could not be used instead — it goes
through `android.util.AtomicFile`, which is a stub in a JVM unit test — and
`internal` is already how `capabilitiesJson` and `mergeManagedInstructions`
are declared in that file. The rendered text is unchanged.

## Next plan

### Immediate — the Termux / Shizuku bridge itself

The seam is in place; what is missing is a backend that can see past package
presence. The first real one should be Shizuku, because its ladder
(`installed` → `authorized` → `running` → `bridge connected`) maps onto the
existing rungs one for one, and Termux's bridge needs a socket the app does
not have yet.

Two things that phase has to decide, and that this phase deliberately did
not:

- **What "a probe that could not answer" means.** The ladder answers it
  today with `RUNNING` — "running, execution bridge is not connected". A
  bridge that flaps would rather keep the last known status, which needs a
  holder with a lifetime and its own tests.
- **Whether the capability snapshot should move into
  `ui/state/CapabilityRepository`**, so the status can change without a
  navigation change. The direction is right; it was not this phase, because
  the plan for it said "no screen changes".

### Then the rest of Phase 1+ backlog, in order

1. **Untitled-session resolution** (small, non-behavioural): hoist the
   hardcoded `"Untitled session"` / `"模型默认"` labels in
   `NativeChatScreen.parseNativeChatSessions` / `parseNativeModelCatalog`
   (lines ~874, ~879), and give `formatMessageTime` a locale-safe pattern —
   recorded below as localisation debt.
2. **Typed RPC layer** (`engine/rpc/`): the six scattered `parse*` mappers
   become one typed seam. DSH is pinned at `0.2.0-rc.2` in
   `tools/collect_runtime.py` and is developer-preview, so field shapes can
   shift — typed mappers with the existing retention tests are the buffer.
3. **Schema-driven configuration forms** and **plugin install/remove**
   (`installBundle` / `removeBundle`, pnpm-network-dependent) — still
   deliberately deferred, see the Plugins screen note.
4. **Remote Host mode** (SSH tunnel) — last.
5. **Archived-set persistence across restarts** — the known gap left as
   baseline deliberately; fix it as its own change and un-pin nothing.

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

- Termux / Shizuku execution bridges. The `ExecutionBackend` seam D11 called
  for now exists, and the rungs `AUTHORIZED`, `RUNNING` and `BRIDGE_CONNECTED`
  have a code path that reaches the snapshot, the wire format and the agent
  briefing. But no backend produces them yet: the shipped
  `PackagePresenceBackend` only sees whether a companion's package is
  installed, so at runtime `processExecution` is still `Unavailable`.
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
