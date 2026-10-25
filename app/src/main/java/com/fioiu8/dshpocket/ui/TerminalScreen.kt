package com.fioiu8.dshpocket.ui

import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.fioiu8.dshpocket.R
import com.fioiu8.dshpocket.terminal.PocketTerminal
import com.fioiu8.dshpocket.ui.theme.DshSpacing
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView

/**
 * 终端页：Termux TerminalView（真 PTY，xterm 模拟）+ 扩展键行。
 *
 * 会话属于 [PocketTerminal] 单例：切页/旋转不杀 shell，重进即回原会话；shell 退出后
 * 显示"重新启动"栏而不是黑屏。扩展键行补手机键盘缺的东西（Esc/Tab/Ctrl/^C/^D/方向键/
 * 管道符），v1 用固定按键而不是可配置布局。
 */
@Composable
fun TerminalScreen(onMenu: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val TAG = "TerminalScreen"
    // Initialised false, then driven by the liveness poll below: reading the singleton
    // during composition raced the factory that starts the shell, showing the exit bar
    // for a perfectly alive shell on first entry.
    var shellExited by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        PocketTerminal.exitListener = { shellExited = true }
        // Re-entering with a session whose shell died while we were away must show the
        // exit bar, not a frozen screen.
        if (PocketTerminal.liveness() != PocketTerminal.Liveness.ALIVE) shellExited = true
        onDispose { PocketTerminal.exitListener = null }
    }

    // Kernel-side liveness poll. Some ROMs never deliver pty EOF on child exit, so the
    // termux watcher (and its callback) stays silent while the shell is long dead; this
    // poll is what turns that into the visible, recoverable exit state.
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(2_000)
            when (PocketTerminal.liveness()) {
                PocketTerminal.Liveness.ZOMBIE, PocketTerminal.Liveness.GONE -> {
                    if (PocketTerminal.sessionOrNull() != null) {
                        PocketTerminal.abandon()
                        shellExited = true
                    }
                }
                PocketTerminal.Liveness.ALIVE -> if (shellExited) shellExited = false
                PocketTerminal.Liveness.NO_SESSION -> Unit
            }
        }
    }

    val viewClient = remember {
        object : com.termux.view.TerminalViewClient {
            override fun onScale(scale: Float): Float = 0f
            override fun onSingleTapUp(e: MotionEvent?) {}
            override fun shouldBackButtonBeMappedToEscape(): Boolean = false
            override fun shouldEnforceCharBasedInput(): Boolean = true
            override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
            override fun isTerminalViewSelected(): Boolean = true
            override fun copyModeChanged(copyMode: Boolean) {}
            override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
            override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
            override fun onLongPress(e: MotionEvent?): Boolean = false
            override fun readControlKey(): Boolean = false
            override fun readAltKey(): Boolean = false
            override fun readShiftKey(): Boolean = false
            override fun readFnKey(): Boolean = false
            override fun onCodePoint(codePoint: Int, ctrlHeld: Boolean, session: TerminalSession?): Boolean = false
            override fun onEmulatorSet() {}
            override fun logError(tag: String?, msg: String?) { PocketTerminal.logError(tag ?: TAG, msg ?: "") }
            override fun logWarn(tag: String?, msg: String?) { PocketTerminal.logWarn(tag ?: TAG, msg ?: "") }
            override fun logInfo(tag: String?, msg: String?) { PocketTerminal.logInfo(tag ?: TAG, msg ?: "") }
            override fun logDebug(tag: String?, msg: String?) {}
            override fun logVerbose(tag: String?, msg: String?) {}
            override fun logStackTraceWithMessage(tag: String?, msg: String?, e: Exception?) {
                PocketTerminal.logStackTraceWithMessage(tag ?: TAG, msg ?: "", e ?: IllegalStateException("unknown"))
            }
            override fun logStackTrace(tag: String?, e: Exception?) {
                PocketTerminal.logStackTrace(tag ?: TAG, e ?: IllegalStateException("unknown"))
            }
        }
    }

    Scaffold(
        topBar = { DshTopAppBar(title = stringResource(R.string.nav_terminal), onMenu = onMenu) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (shellExited) {
                ShellExitBar(onRestart = {
                    PocketTerminal.start(context)
                    shellExited = false
                })
            }
            AndroidView(
                factory = { ctx ->
                    TerminalView(ctx, null).apply {
                        // Termux's default color scheme is dark-on-dark; on the app's
                        // light theme the view would paint its text on whatever the
                        // parent left behind — white prompt on white page.
                        setBackgroundColor(android.graphics.Color.BLACK)
                        setTextSize(30)
                        setTerminalViewClient(viewClient)
                        attachSession(PocketTerminal.ensureStarted(context))
                        isFocusable = true
                        isFocusableInTouchMode = true
                        requestFocus()
                    }.also { PocketTerminal.attachView(it) }
                },
                update = { view ->
                    PocketTerminal.attachView(view)
                    PocketTerminal.current()?.let { if (view.mTermSession !== it) view.attachSession(it) }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            ExtraKeyRow()
        }
    }
}

@Composable
private fun ShellExitBar(onRestart: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DshSpacing.page, vertical = DshSpacing.compact),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.terminal_exited),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = onRestart) { Text(stringResource(R.string.terminal_restart)) }
    }
}

/** One physical extra key: a label and what it sends to the pty. */
private data class ExtraKey(val label: String, val bytes: ByteArray? = null, val codePoint: Int? = null)

private val EXTRA_KEYS = listOf(
    ExtraKey("Enter", codePoint = 13),
    ExtraKey("Esc", bytes = byteArrayOf(0x1b)),
    ExtraKey("Tab", codePoint = 9),
    ExtraKey("^C", codePoint = 3),
    ExtraKey("^D", codePoint = 4),
    ExtraKey("^L", codePoint = 12),
    ExtraKey("↑", bytes = "\u001B[A".toByteArray()),
    ExtraKey("↓", bytes = "\u001B[B".toByteArray()),
    ExtraKey("←", bytes = "\u001B[D".toByteArray()),
    ExtraKey("→", bytes = "\u001B[C".toByteArray()),
    ExtraKey("|", codePoint = '|'.code),
    ExtraKey("/", codePoint = '/'.code),
    ExtraKey("-", codePoint = '-'.code),
)

@Composable
private fun ExtraKeyRow() {
    androidx.compose.foundation.layout.Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = DshSpacing.compact, vertical = DshSpacing.compact),
    ) {
        EXTRA_KEYS.forEach { key ->
            TextButton(
                onClick = {
                    when {
                        key.bytes != null -> PocketTerminal.write(key.bytes)
                        key.codePoint != null -> PocketTerminal.writeCodePoint(false, key.codePoint)
                    }
                },
            ) { Text(key.label) }
        }
    }
}
