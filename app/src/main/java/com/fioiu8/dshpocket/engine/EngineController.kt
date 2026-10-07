package com.fioiu8.dshpocket.engine

import android.content.Context
import android.util.Log
import com.fioiu8.dshpocket.capability.AgentBriefing
import kotlinx.coroutines.CancellationException
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

sealed interface EngineState {
    data object Idle : EngineState
    data class Busy(val message: String) : EngineState
    data class Ready(
        val baseUrl: String,
        val token: String,
        val since: Long = System.currentTimeMillis(),
    ) : EngineState
    data class Failed(val message: String) : EngineState
}

/**
 * Owns the bundled engine: unpack the runtime, spawn it, and surface the launch URL
 * printed on stdout.
 *
 * The URL carries a launch token that is minted fresh on every boot and printed exactly
 * once (see D10), so it must be captured here rather than typed by anyone.
 */
class EngineController(private val context: Context) {

    companion object {
        private const val TAG = "EngineController"
        private val LAUNCH_URL = Regex("""(http://127\.0\.0\.1:(\d+)/\?token=([A-Za-z0-9_\-]+))""")
        private const val MAX_LOG_LINES = 300

        /**
         * One engine per process. An Activity recreation must not spawn a second engine
         * onto the same port, so the controller is shared rather than re-created.
         */
        @Volatile
        private var shared: EngineController? = null

        fun get(context: Context): EngineController = shared ?: synchronized(this) {
            shared ?: EngineController(context.applicationContext).also { shared = it }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val started = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    private val lifecycleLock = Any()
    private val processLock = Any()
    @Volatile
    private var process: Process? = null
    @Volatile
    private var bootJob: Job? = null

    fun start() {
        synchronized(lifecycleLock) {
            if (!started.compareAndSet(false, true)) return
            val runId = generation.incrementAndGet()
            bootJob = scope.launch { boot(runId) }
        }
    }

    fun restart() {
        stop()
        start()
    }

    fun stop() {
        synchronized(lifecycleLock) {
            started.set(false)
            generation.incrementAndGet()
            bootJob?.cancel()
            bootJob = null
        }
        synchronized(processLock) {
            process?.destroy()
            process = null
        }
        _state.value = EngineState.Idle
    }

    fun dispose() {
        stop()
        scope.cancel()
    }

    private suspend fun boot(runId: Long) {
        val root = EnginePaths.root(context)
        try {
            if (!isCurrent(runId)) return
            if (!RuntimeInstaller.isInstalled(context)) {
                _state.value = EngineState.Busy("准备运行时…")
                RuntimeInstaller.install(context) { msg ->
                    if (isCurrent(runId)) _state.value = EngineState.Busy(msg)
                }
            }
            if (!isCurrent(runId)) return
            _state.value = EngineState.Busy("启动引擎…")
            spawn(root, runId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            Log.e(TAG, "boot failed", t)
            if (isCurrent(runId)) {
                started.set(false)
                _state.value = EngineState.Failed(t.message ?: t.toString())
            }
        }
    }

    private fun spawn(root: File, runId: Long) {
        if (!isCurrent(runId)) return
        val dshHome = EnginePaths.dshHome(context)
        val workspace = EnginePaths.workspace(context)
        dshHome.mkdirs()
        workspace.mkdirs()
        val nativeChatSuggestionsModule = EnginePaths.installNativeChatSuggestionsTool(context, root)
        // Point the engine's default workspace at a directory Android actually has.
        EnginePaths.writeHomePatch(dshHome, workspace, nativeChatSuggestionsModule)
        // Brief the agent on its current capabilities (loaded from $DSH_HOME/AGENTS.md).
        AgentBriefing.write(context, dshHome, workspace)

        val command = EnginePaths.command(root)
        appendLog("$ ${command.joinToString(" ")}")
        appendLog("[dsh-home]  ${dshHome.absolutePath}")
        appendLog("[workspace] ${workspace.absolutePath}")

        val pb = ProcessBuilder(command)
        // The invoking directory is the fallback workspace root for sessions without one.
        pb.directory(workspace)
        pb.environment().putAll(EnginePaths.environment(root, dshHome))
        pb.redirectErrorStream(false)

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
                    if (_state.value is EngineState.Ready) {
                        "引擎已退出（退出码 $code）"
                    } else {
                        "引擎退出，退出码 $code"
                    },
                )
            }
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
