package com.fioiu8.dshpocket.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.fioiu8.dshpocket.capability.Capabilities
import com.fioiu8.dshpocket.capability.CapabilityState
import com.fioiu8.dshpocket.engine.EngineController
import com.fioiu8.dshpocket.engine.EngineService
import com.fioiu8.dshpocket.engine.EngineState
import com.fioiu8.dshpocket.engine.SessionCookie
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

enum class Destination(val label: String, val icon: ImageVector) {
    Home("首页", Icons.Filled.Home),
    Chat("对话", Icons.Filled.Email),
    Settings("设置", Icons.Filled.Settings),
}

/** Snapshot of the D12 capability ladder; read on demand rather than observed. */
data class CapabilitySnapshot(
    val allFiles: CapabilityState,
    val termux: CapabilityState,
    val shizuku: CapabilityState,
)

fun readCapabilities(context: android.content.Context): CapabilitySnapshot = CapabilitySnapshot(
    allFiles = Capabilities.allFilesAccess(context),
    termux = Capabilities.termux(context),
    shizuku = Capabilities.shizuku(context),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(controller: EngineController) {
    val context = LocalContext.current
    val state by controller.state.collectAsState()
    val log by controller.log.collectAsState()

    var destination by rememberSaveable { mutableStateOf(Destination.Home) }
    var capabilities by remember { mutableStateOf(readCapabilities(context)) }

    // The WebView instance outlives tab switches so returning to 对话 does not reload it.
    val webView = remember { mutableStateOf<WebView?>(null) }
    var webLoaded by remember { mutableStateOf(false) }
    var webLoading by remember { mutableStateOf(false) }
    var webLoadError by remember { mutableStateOf<String?>(null) }
    var blankRetryCount by remember { mutableStateOf(0) }

    val debugBuild = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    if (debugBuild) WebView.setWebContentsDebuggingEnabled(true)

    LaunchedEffect(Unit) { controller.start() }

    // Cheap re-read: returning from the system settings page lands back on a tab change.
    LaunchedEffect(destination) { capabilities = readCapabilities(context) }

    LaunchedEffect(state) {
        if (state !is EngineState.Ready) {
            webLoaded = false
            webLoading = false
            webLoadError = null
            blankRetryCount = 0
        }
    }

    LaunchedEffect(destination) {
        if (destination != Destination.Chat) {
            webLoaded = false
            webLoading = false
            webLoadError = null
            blankRetryCount = 0
        }
    }

    LaunchedEffect(state, destination, webView.value) {
        val s = state
        val wv = webView.value
        if (destination == Destination.Chat && s is EngineState.Ready && wv != null && !webLoaded && !webLoading) {
            webLoading = true
            webLoadError = null
            blankRetryCount = 0
            withContext(NonCancellable) {
                val ok = SessionCookie.establish(s.baseUrl, s.token)
                wv.loadUrl(if (ok) "${s.baseUrl}/" else "${s.baseUrl}/?token=${s.token}")
            }
        }
    }

    // DSH changes routes in-place. A client-side exception can therefore leave a
    // finished WebView with an empty document without triggering onPageFinished.
    LaunchedEffect(destination, state, webView.value, webLoaded) {
        val s = state
        val wv = webView.value
        if (destination != Destination.Chat || s !is EngineState.Ready || wv == null || !webLoaded) return@LaunchedEffect
        var retries = 0
        while (isActive) {
            delay(1_000)
            wv.evaluateJavascript(VIEWPORT_FIX_SCRIPT) { rawHeight ->
                val height = rawHeight.trim('"').toFloatOrNull() ?: 0f
                if (height < 1f) {
                    if (retries < 1) {
                        retries += 1
                        webLoading = true
                        Log.w("DshWebView", "SPA produced empty body; reloading")
                        // A client-side route can outlive the launch-token cookie. Re-enter
                        // through the token URL so the server can refresh its auth cookie.
                        wv.loadUrl("${s.baseUrl}/?token=${s.token}")
                    } else {
                        webLoading = false
                        webLoadError = "页面没有返回内容"
                        Log.e("DshWebView", "SPA remains empty after reload")
                    }
                } else {
                    retries = 0
                    webLoading = false
                    webLoadError = null
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(destination.label) },
                actions = {
                    TextButton(onClick = {
                        webLoaded = false
                        webLoading = false
                        webLoadError = null
                        blankRetryCount = 0
                        EngineService.start(context)
                        controller.restart()
                    }) { Text("重启引擎") }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = destination == entry,
                        onClick = { destination = entry },
                        icon = { Icon(entry.icon, contentDescription = entry.label) },
                        label = { Text(entry.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (destination) {
                Destination.Home -> HomeScreen(
                    state = state,
                    log = log,
                    capabilities = capabilities,
                    onOpenChat = { destination = Destination.Chat },
                    onOpenSettings = { destination = Destination.Settings },
                    onGrantAllFiles = { context.startActivity(Capabilities.allFilesAccessIntent(context)) },
                    onRestart = {
                        webLoaded = false
                        webLoading = false
                        webLoadError = null
                        blankRetryCount = 0
                        EngineService.start(context)
                        controller.restart()
                    },
                )

                Destination.Chat -> {
                    val readyState = state as EngineState.Ready
                    ChatScreen(
                        state = readyState,
                        webView = webView,
                        loadError = webLoadError,
                        onPageStarted = {
                            webLoading = true
                            webLoadError = null
                        },
                        onPageFinished = { view ->
                            view.postDelayed({
                            view.evaluateJavascript(VIEWPORT_FIX_SCRIPT) { rawHeight ->
                                val height = rawHeight.trim('"').toFloatOrNull() ?: 0f
                                if (height < 1f && blankRetryCount < 1) {
                                        blankRetryCount += 1
                                        webLoaded = false
                                        webLoading = true
                                        Log.w("DshWebView", "page finished with empty body; retrying")
                                        view.loadUrl("${readyState.baseUrl}/?token=${readyState.token}")
                                } else if (height < 1f) {
                                        webLoading = false
                                        webLoadError = "页面没有返回内容"
                                        Log.e("DshWebView", "page finished with empty body after retry")
                                    } else {
                                        webLoading = false
                                        webLoaded = true
                                        webLoadError = null
                                    }
                                }
                            }, 350)
                        },
                        onPageError = { message ->
                            webLoaded = false
                            webLoading = false
                            webLoadError = message
                        },
                    )
                }

                Destination.Settings -> SettingsScreen(
                    state = state,
                    log = log,
                    capabilities = capabilities,
                    onGrantAllFiles = { context.startActivity(Capabilities.allFilesAccessIntent(context)) },
                    onRestart = {
                        webLoaded = false
                        EngineService.start(context)
                        controller.restart()
                    },
                    onStop = {
                        controller.stop()
                        EngineService.stop(context)
                    },
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ChatScreen(
    state: EngineState,
    webView: androidx.compose.runtime.MutableState<WebView?>,
    loadError: String?,
    onPageStarted: () -> Unit,
    onPageFinished: (WebView) -> Unit,
    onPageError: (String) -> Unit,
) {
    when (state) {
        is EngineState.Ready -> Box(Modifier.fillMaxSize()) {
            AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                webView.value ?: WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    settings.mediaPlaybackRequiresUserGesture = false
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                            if ((ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                                Log.d(
                                    "DshWebView",
                                    "console ${message.messageLevel()}: ${message.message()} " +
                                        "(${message.sourceId()}:${message.lineNumber()})",
                                )
                            }
                            return true
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                            onPageStarted()
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            onPageFinished(view)
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) {
                            if (request.isForMainFrame) {
                                onPageError("${error.description} (${error.errorCode})")
                            }
                        }

                        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                            when (Uri.parse(url).host) {
                                // The engine's own UI stays inside the app.
                                "127.0.0.1", "localhost" -> false
                                // Anything else leaves for the system browser.
                                else -> true
                            }
                    }
                    webView.value = this
                }
            },
            )

            if (loadError != null) {
                CenteredMessage(
                    title = "界面加载失败",
                    body = "$loadError\n请点上方“重启引擎”后重试。",
                    busy = false,
                )
            }
        }

        is EngineState.Failed -> CenteredMessage(
            title = "引擎未能启动",
            body = state.message,
            busy = false,
        )

        else -> CenteredMessage(
            title = "正在启动引擎",
            body = (state as? EngineState.Busy)?.message ?: "准备中…",
            busy = true,
        )
    }
}

private val VIEWPORT_FIX_SCRIPT = """
(() => {
  const id = '__dshPocketViewportFix';
  let style = document.getElementById(id);
  if (!style) {
    style = document.createElement('style');
    style.id = id;
    document.head?.appendChild(style);
  }
  style.textContent = `
    html, body, #root {
      height: 100vh !important;
      min-height: 100vh !important;
      margin: 0 !important;
    }
    @media (max-width: 600px) {
      [role="dialog"] {
        flex-direction: column !important;
        width: calc(100vw - 24px) !important;
      }
      [role="dialog"] > nav {
        width: 100% !important;
        height: auto !important;
        flex: 0 0 auto !important;
      }
      [role="dialog"] > nav .VOzbGW_navTitle {
        display: none !important;
      }
      [role="dialog"] > nav .VOzbGW_navList {
        display: flex !important;
        flex-direction: row !important;
        width: 100% !important;
        height: auto !important;
        overflow-x: auto !important;
        padding: 8px 12px !important;
        gap: 4px !important;
      }
      [role="dialog"] > nav .VOzbGW_navCell {
        flex: 0 0 auto !important;
        width: auto !important;
        min-width: max-content !important;
        height: 40px !important;
        padding: 0 12px !important;
      }
      [role="dialog"] > .VOzbGW_content {
        width: 100% !important;
        min-width: 0 !important;
        min-height: 0 !important;
        flex: 1 1 auto !important;
      }
      [role="dialog"] > .VOzbGW_content .VOzbGW_options {
        padding: 0 16px 16px !important;
      }
    }
  `;
  const viewportHeight = `${'$'}{window.innerHeight}px`;
  for (const node of [document.documentElement, document.body, document.getElementById('root')]) {
    if (!node) continue;
    node.style.setProperty('height', viewportHeight, 'important');
    node.style.setProperty('min-height', viewportHeight, 'important');
  }

  // Android WebView reports 100vh as 0px for fixed-position overlays. DSH's
  // settings and model dialogs use that value for their panel height, so
  // restore the overlay and dialog dimensions from the actual WebView viewport.
  const panelHeight = `${'$'}{Math.max(1, Math.min(800, window.innerHeight - 48))}px`;
  const syncDialogs = () => {
    for (const dialog of document.querySelectorAll('[role="dialog"]')) {
      const overlay = dialog.parentElement;
      if (overlay) {
        overlay.style.setProperty('height', viewportHeight, 'important');
        overlay.style.setProperty('min-height', viewportHeight, 'important');
      }
      dialog.style.setProperty('height', panelHeight, 'important');
      dialog.style.setProperty('max-height', panelHeight, 'important');
    }
  };
  syncDialogs();
  if (!window.__dshPocketViewportObserver) {
    window.__dshPocketViewportObserver = new MutationObserver(syncDialogs);
    window.__dshPocketViewportObserver.observe(document.body, { childList: true, subtree: true });
  }
  return String(document.getElementById('root')?.getBoundingClientRect().height || 0);
})()
""".trimIndent()

@Composable
fun CenteredMessage(title: String, body: String, busy: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (busy) CircularProgressIndicator()
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
fun LogPanel(log: List<String>, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        log.takeLast(120).forEach {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
fun KeyValueRow(key: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(key, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun Divider() = HorizontalDivider(Modifier.padding(vertical = 4.dp))
