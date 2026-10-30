package com.fioiu8.dshpocket.engine

import android.app.Application
import android.os.Build
import android.util.Log
import com.fioiu8.dshpocket.R
import com.fioiu8.dshpocket.capability.AgentBriefing
import com.fioiu8.dshpocket.capability.Capabilities
import com.fioiu8.dshpocket.screen.ScreenControlManager
import com.fioiu8.dshpocket.ui.UiText
import com.fioiu8.dshpocket.ui.text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

sealed interface EngineState {
    data object Idle : EngineState

    /**
     * Working towards Ready. [fraction] is 0..1 when the phase has measurable progress
     * (runtime extraction); null means indeterminate — the UI shows a spinner-style
     * indicator instead of a percentage.
     */
    data class Busy(val message: UiText, val fraction: Float? = null) : EngineState
    data class Ready(
        val baseUrl: String,
        val token: String,
        val since: Long = System.currentTimeMillis(),
    ) : EngineState
    data class Failed(val message: UiText) : EngineState
}

/**
 * Owns the bundled engine: unpack the runtime, spawn it, and surface the launch URL
 * printed on stdout.
 *
 * The URL carries a launch token that is minted fresh on every boot and printed exactly
 * once (see D10), so it must be captured here rather than typed by anyone.
 */
/**
 * Holds the application context deliberately: this object outlives every Activity and
 * lives in a static field, so an Activity context here would leak. Typing the field as
 * [Application] makes that unrepresentable instead of merely discouraged — lint's
 * StaticFieldLeak cannot be satisfied by an @Suppress, but it goes quiet for a real
 * reason once the type no longer admits a leak.
 */
class EngineController(private val context: Application) {

    companion object {
        private const val TAG = "EngineController"
        private val LAUNCH_URL = Regex("""(http://127\.0\.0\.1:(\d+)/\?token=([A-Za-z0-9_\-]+))""")
        private const val MAX_LOG_LINES = 300

        /**
         * How long a SIGTERM'd engine gets to exit on its own before it is killed. The
         * engine binds a fixed port, so a restart that does not wait for the old process
         * would race it for the port and die with EADDRINUSE.
         */
        private const val STOP_GRACE_SECONDS = 5L

        /**
         * Share of the bootstrap progress bar taken by runtime extraction; spawning the
         * engine has no measurable progress, so it fills the rest only when Ready.
         */
        private const val EXTRACTION_PROGRESS_WEIGHT = 0.95f

        /**
         * One engine per process. An Activity recreation must not spawn a second engine
         * onto the same port, so the controller is shared rather than re-created.
         */
        @Volatile
        private var shared: EngineController? = null

        /**
         * Callers pass the process-wide [Application], never an Activity or Service, so
         * the static instance cannot outlive its context.
         */
        fun get(application: Application): EngineController = shared ?: synchronized(this) {
            shared ?: EngineController(application).also { shared = it }
        }
    }

    // Last-resort guard: a crash in a fire-and-forget coroutine (log pumping, the stop
    // watcher) must not take the process down. Callers of `rpc` handle their own errors;
    // this only catches what escapes everything else.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            Log.e(TAG, "uncaught failure in engine scope", throwable)
            appendLog("[error] ${throwable.message ?: throwable.javaClass.simpleName}")
        },
    )

    /**
     * Scope for work that outlives any one screen, such as the shared session repository.
     * Exposed so callers can own long-lived coroutines without creating their own scope.
     */
    val applicationScope: CoroutineScope get() = scope

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val started = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    // Set by the settings screen's explicit stop. Activity recreations must not silently
    // undo that choice: onCreate unconditionally calls EngineService.start → controller
    // start, which would otherwise resurrect the engine the user just stopped. A real
    // restart (home card / settings restart button) clears it again.
    @Volatile
    private var userStopped = false
    private val lifecycleLock = Any()
    private val processLock = Any()
    @Volatile
    private var process: Process? = null
    @Volatile
    private var bootJob: Job? = null

    fun start() {
        if (userStopped) return
        synchronized(lifecycleLock) {
            if (!started.compareAndSet(false, true)) return
            val runId = generation.incrementAndGet()
            bootJob = scope.launch { boot(runId) }
        }
    }

    fun restart() {
        userStopped = false
        stop()
        start()
    }

    fun stop(byUser: Boolean = false) {
        if (byUser) userStopped = true
        synchronized(lifecycleLock) {
            started.set(false)
            generation.incrementAndGet()
            bootJob?.cancel()
            bootJob = null
        }
        val stopping = synchronized(processLock) { process }
        if (stopping != null) {
            stopping.destroy()
            // SIGTERM alone is not an exit: a node busy in a request can shrug it off.
            // Wait off the calling thread (onDestroy runs on main), then escalate.
            scope.launch {
                awaitProcessExit(stopping)
                synchronized(processLock) {
                    if (process === stopping) process = null
                }
            }
        }
        _state.value = EngineState.Idle
    }

    /**
     * Current engine run id. Screen-control "allow for this session" decisions are keyed
     * on it: a stop/start (which always bumps the generation) invalidates them.
     */
    fun generationValue(): Long = generation.get()

    fun dispose() {
        stop()
        scope.cancel()
    }

    /**
     * Rewrites the agent briefing files without restarting the engine. The engine reads
     * AGENTS.md when a session starts, so a capability granted mid-run reaches the agent
     * on its next turn instead of after the next spawn. Only meaningful while the engine
     * is running; [spawn] writes the same files on every boot.
     */
    fun refreshBriefing(snapshot: com.fioiu8.dshpocket.capability.CapabilitySnapshot) {
        if (_state.value !is EngineState.Ready) return
        scope.launch {
            runCatching { AgentBriefing.write(EnginePaths.dshHome(context), snapshot, screenControlFacts()) }
                .onFailure { Log.w(TAG, "briefing refresh failed", it) }
        }
    }

    /** Gathers the screen-control facts the briefing block renders. */
    private fun screenControlFacts(): AgentBriefing.ScreenControlFacts {
        val manager = ScreenControlManager.get(context)
        return AgentBriefing.ScreenControlFacts(
            enabled = manager.settings.enabled,
            accessibilityReady = com.fioiu8.dshpocket.screen.PocketAccessibilityService.peek() != null,
            overlayGranted = android.provider.Settings.canDrawOverlays(context),
        )
    }

    private suspend fun boot(runId: Long) {
        val root = EnginePaths.root(context)
        try {
            if (!isCurrent(runId)) return
            if (!RuntimeInstaller.isInstalled(context)) {
                var message: UiText = text(R.string.engine_preparing)
                var fraction = 0f
                fun publish() {
                    if (isCurrent(runId)) {
                        _state.value = EngineState.Busy(message, fraction * EXTRACTION_PROGRESS_WEIGHT)
                    }
                }
                publish()
                RuntimeInstaller.install(
                    context,
                    onProgress = { msg ->
                        message = msg
                        publish()
                    },
                    onFraction = { value ->
                        fraction = value
                        publish()
                    },
                )
            }
            if (!isCurrent(runId)) return
            _state.value = EngineState.Busy(text(R.string.engine_starting))
            spawn(root, runId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            Log.e(TAG, "boot failed", t)
            if (isCurrent(runId)) {
                started.set(false)
                _state.value = EngineState.Failed(UiText.Raw(t.message ?: t.toString()))
            }
        }
    }

    private fun spawn(root: File, runId: Long) {
        if (!isCurrent(runId)) return
        val dshHome = EnginePaths.dshHome(context)
        val workspace = EnginePaths.workspace(context)
        val capabilities = Capabilities.snapshot(context, workspace)
        dshHome.mkdirs()
        workspace.mkdirs()
        val nativeChatSuggestionsModule = EnginePaths.installNativeChatSuggestionsTool(context, root)
        val screenControlModule = EnginePaths.installScreenControlTool(context, root)
        // Point the engine's default workspace at a directory Android actually has.
        EnginePaths.writeHomePatch(dshHome, workspace, nativeChatSuggestionsModule, screenControlModule)
        // Brief the agent on its current capabilities (loaded from $DSH_HOME/AGENTS.md).
        AgentBriefing.write(dshHome, capabilities, screenControlFacts())

        val command = EnginePaths.command(root)
        appendLog("$ ${command.joinToString(" ")}")
        appendLog("[dsh-home]  ${dshHome.absolutePath}")
        appendLog("[workspace] ${workspace.absolutePath}")

        val pb = ProcessBuilder(command)
        // The invoking directory is the fallback workspace root for sessions without one.
        pb.directory(workspace)
        pb.environment().putAll(EnginePaths.environment(root, dshHome))
        pb.environment().putAll(ScreenControlManager.get(context).bridgeEnvironment())
        pb.redirectErrorStream(false)

        awaitPreviousEngineExit()

        val proc = synchronized(processLock) {
            if (!isCurrent(runId)) return
            pb.start().also { process = it }
        }

        // The URL appears exactly once on stdout; consume both streams so neither blocks.
        scope.launch { pump(proc.inputStream.bufferedReader(), true, runId, proc) }
        scope.launch { pump(proc.errorStream.bufferedReader(), false, runId, proc) }
        scope.launch {
            val code = proc.waitFor()
            appendLog("[engine exited with code $code]")
            synchronized(processLock) {
                if (process === proc) process = null
            }
            if (isCurrent(runId)) {
                started.set(false)
                _state.value = EngineState.Failed(
                    text(if (_state.value is EngineState.Ready) R.string.engine_exit_ready else R.string.engine_exit_before_ready, code),
                )
            }
        }
    }

    /**
     * A restart can arrive while the previous engine is still draining its SIGTERM; the
     * fixed port stays bound until that process is gone, so starting a new node before
     * then would make it die with EADDRINUSE. Runs on the boot coroutine (IO), never on
     * main.
     */
    private fun awaitPreviousEngineExit() {
        val previous = synchronized(processLock) { process } ?: return
        awaitProcessExit(previous)
    }

    /**
     * Waits [STOP_GRACE_SECONDS] for the process to react to the SIGTERM that
     * [stop]/destroy sent, then escalates to SIGKILL.
     *
     * The timed Process APIs are API 26+; on 24/25 the plain blocking waitFor stands in.
     * Both call sites already run on background threads, and a node that never exits on
     * SIGTERM is pathological, so the unbounded wait there is the least-bad fallback.
     */
    private fun awaitProcessExit(process: Process) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!process.waitFor(STOP_GRACE_SECONDS, TimeUnit.SECONDS)) {
                appendLog("[engine ignored SIGTERM for ${STOP_GRACE_SECONDS}s, killing]")
                process.destroyForcibly()
                process.waitFor(STOP_GRACE_SECONDS, TimeUnit.SECONDS)
            }
        } else {
            process.waitFor()
        }
    }

    private fun pump(reader: BufferedReader, isStdout: Boolean, runId: Long, proc: Process) {
        reader.useLines { lines ->
            lines.forEach { line ->
                appendLog(if (isStdout) redactLaunchToken(line) else "[stderr] $line")
                if (isStdout && isCurrent(runId) && process === proc) {
                    val m = LAUNCH_URL.find(line)
                    if (m != null && _state.value !is EngineState.Ready) {
                        val base = "http://127.0.0.1:${m.groupValues[2]}"
                        _state.value = EngineState.Ready(base, m.groupValues[3])
                    }
                }
            }
        }
    }

    private fun isCurrent(runId: Long): Boolean =
        started.get() && generation.get() == runId

    private fun redactLaunchToken(line: String): String =
        line.replace(LAUNCH_URL) { match ->
            "http://127.0.0.1:${match.groupValues[2]}/?token=<redacted>"
        }

    private fun appendLog(line: String) {
        _log.update { (it + line).takeLast(MAX_LOG_LINES) }
    }
}

/**
 * Performs the launch-token exchange (D10) outside the WebView and hands the resulting
 * signed cookie to the WebView cookie jar.
 *
 * Doing it here rather than letting the WebView follow `/?token=…` avoids depending on
 * how a browser treats a `SameSite=Strict` cookie on a redirect it did not initiate.
 */
object SessionCookie {

    private const val TAG = "SessionCookie"

    suspend fun establish(baseUrl: String, token: String): Boolean = withContext(Dispatchers.IO) {
        val exchangeUrl = "$baseUrl/?token=$token"
        try {
            val conn = (URL(exchangeUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10_000
                readTimeout = 10_000
                requestMethod = "GET"
            }
            try {
                val code = conn.responseCode
                val raw = conn.headerFields["Set-Cookie"]?.firstOrNull()
                    ?: conn.getHeaderField("Set-Cookie")
                Log.i(TAG, "token exchange -> $code, cookie=${raw?.take(24)}")
                if (raw.isNullOrBlank()) return@withContext false
                val cookie = raw.substringBefore(';').trim()
                withContext(Dispatchers.Main) {
                    android.webkit.CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        setCookie(baseUrl, cookie)
                        flush()
                    }
                }
                true
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "token exchange failed", t)
            false
        }
    }

    /** Convenience for the failure path: what the cookie jar currently holds. */
    suspend fun describe(baseUrl: String): String = withContext(Dispatchers.IO) {
        android.webkit.CookieManager.getInstance().getCookie(baseUrl)?.take(40) ?: "(none)"
    }
}
