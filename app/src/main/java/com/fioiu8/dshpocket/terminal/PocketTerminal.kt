package com.fioiu8.dshpocket.terminal

import android.content.Context
import android.util.Log
import com.fioiu8.dshpocket.engine.EnginePaths
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import java.io.File
import java.lang.ref.WeakReference

/**
 * 进程级单例的 shell 会话：切页不杀进程，回到终端页即回到原会话。
 *
 * Shell 用系统的 `/system/bin/sh`（mksh）：runtime.zip 里没有 shell，而 mksh 在所有
 * Android 设备上都存在。环境变量与引擎完全同源（[EnginePaths.environment]：PREFIX、
 * LD_LIBRARY_PATH、DSH_HOME），所以在终端里跑 `node`、`rg` 就是引擎在用的那一份。
 * 工作目录也是引擎的 workspace。
 *
 * 已知边界：Android 14+ 的 W^X 策略对 JNI fork 的限制未在真机回归过；termux-app 自身
 * 在 14+ 正常工作，理论风险低，但要在真机验收清单上保留一项。
 */
object PocketTerminal : TerminalSessionClient {

    private const val TAG = "PocketTerminal"

    @Volatile
    private var session: TerminalSession? = null

    /** The terminal view currently on screen; text changes repaint it. Weak so a
     *  disposed composition never keeps the view alive through this singleton. */
    @Volatile
    private var attachedView: WeakReference<TerminalView>? = null

    /** Set by the screen to observe an exit; the singleton outlives the composition. */
    @Volatile
    var exitListener: (() -> Unit)? = null

    /** Returns the running session, or null after the shell exited (a stale one is not returned). */
    fun current(): TerminalSession? = session?.takeIf { it.isRunning }

    /** The live session even when the shell inside it is gone (watcher stuck). */
    fun sessionOrNull(): TerminalSession? = session

    /** Child pid of the current session, for the /proc liveness check. */
    fun pid(): Int? = session?.let { if (it.isRunning) it.pid else null }

    /**
     * Liveness of the child as seen by the kernel, independent of the termux watcher.
     *
     * On at least one cloud-phone ROM the pty master never reports EOF when the child
     * dies, so `onSessionFinished` never fires and the child lingers as a zombie — the
     * session looks alive while the shell is gone. Reading `/proc/<pid>/stat` directly
     * is the ground truth the ROM cannot hide.
     */
    enum class Liveness { ALIVE, ZOMBIE, GONE, NO_SESSION }

    fun liveness(): Liveness {
        val pid = pid() ?: return if (session == null) Liveness.NO_SESSION else Liveness.GONE
        return try {
            val stat = File("/proc/$pid/stat").readText()
            // comm may contain spaces and parentheses; the state is the first char after ')'.
            val state = stat.substringAfterLast(')').trim().firstOrNull()
            when (state) {
                'Z' -> Liveness.ZOMBIE
                null -> Liveness.GONE
                else -> Liveness.ALIVE
            }
        } catch (_: Exception) {
            // /proc/<pid> gone means reaped; the session is dead even if the watcher
            // never noticed.
            Liveness.GONE
        }
    }

    /**
     * Drops the current session without expecting its watcher to cooperate.
     *
     * `finishIfRunning()` is still attempted (SIGKILL to an already-dead pid is a
     * no-op); on the stuck-watcher ROM the reader thread stays parked on the pty read
     * and cannot be joined — that is one leaked thread and fd per occurrence, accepted
     * here because the alternative is a silently dead terminal with no recovery path.
     */
    fun abandon() {
        val stale = session
        session = null
        stale?.finishIfRunning()
        Log.w(TAG, "session abandoned (watcher did not report the exit)")
    }

    fun attachView(view: TerminalView?) {
        attachedView = view?.let { WeakReference(it) }
    }

    /** Returns the running session or starts a fresh one; idempotent per composition. */
    fun ensureStarted(context: Context): TerminalSession {
        current()?.let { return it }
        return start(context)
    }

    /** Kills whatever is running and starts a fresh shell in the workspace. */
    fun start(context: Context): TerminalSession {
        session?.finishIfRunning()
        val root = EnginePaths.root(context)
        val dshHome = EnginePaths.dshHome(context)
        val env = (EnginePaths.environment(root, dshHome) + mapOf(
            "TERM" to "xterm-256color",
            "SHELL" to "/system/bin/sh",
        )).entries.map { "${it.key}=${it.value}" }.toTypedArray()
        val started = TerminalSession(
            "/system/bin/sh",
            EnginePaths.workspace(context).absolutePath,
            null,
            env,
            null,
            this,
        )
        session = started
        return started
    }

    fun write(bytes: ByteArray) {
        session?.write(bytes, 0, bytes.size)
    }

    fun writeCodePoint(ctrlHeld: Boolean, codePoint: Int) {
        session?.writeCodePoint(ctrlHeld, codePoint)
    }

    // ---- TerminalSessionClient: repaint the attached view, stay quiet otherwise ---------

    override fun onTextChanged(session: TerminalSession) {
        attachedView?.get()?.onScreenUpdated()
    }

    override fun onTitleChanged(session: TerminalSession) {}

    override fun onSessionFinished(finishedSession: TerminalSession) {
        // The exit status distinguishes a normal exit (user typed exit) from a signal
        // kill (SIGKILL=9 shows as 137 via 128+n in some paths; termux reports the raw
        // wait status family) — this is the one place a silent shell death explains itself.
        Log.w(TAG, "shell session finished; exitStatus=${finishedSession.getExitStatus()} isRunning=${finishedSession.isRunning()}")
        attachedView?.get()?.onScreenUpdated()
        exitListener?.invoke()
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}

    override fun onPasteTextFromClipboard(session: TerminalSession) {}

    override fun onBell(session: TerminalSession) {}

    override fun onColorsChanged(session: TerminalSession) {
        attachedView?.get()?.onScreenUpdated()
    }

    override fun onTerminalCursorStateChange(state: Boolean) {}

    override fun getTerminalCursorStyle(): Int = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

    override fun logError(tag: String, msg: String) { Log.e(TAG, "$tag: $msg") }
    override fun logWarn(tag: String, msg: String) { Log.w(TAG, "$tag: $msg") }
    override fun logInfo(tag: String, msg: String) { Log.i(TAG, "$tag: $msg") }
    override fun logDebug(tag: String, msg: String) {}
    override fun logVerbose(tag: String, msg: String) {}
    override fun logStackTraceWithMessage(tag: String, msg: String, e: Exception) { Log.e(TAG, "$tag: $msg", e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(TAG, tag, e) }
}
