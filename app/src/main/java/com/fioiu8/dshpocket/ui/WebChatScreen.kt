package com.fioiu8.dshpocket.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.fioiu8.dshpocket.engine.EngineState

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun WebChatScreen(
    state: EngineState,
    onMenu: () -> Unit,
    webView: androidx.compose.runtime.MutableState<WebView?>,
    loading: Boolean,
    loadError: String?,
    onPageStarted: () -> Unit,
    onPageFinished: (WebView) -> Unit,
    onPageError: (String) -> Unit,
) {
    androidx.compose.material3.Scaffold(
        topBar = { DshTopAppBar(title = stringResource(com.fioiu8.dshpocket.R.string.nav_chat), onMenu = onMenu) },
    ) { padding ->
        when (state) {
            is EngineState.Ready -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background),
            ) {
                val webBackground = MaterialTheme.colorScheme.background.toArgb()
                val webOrigin = (state as? EngineState.Ready)?.baseUrl?.let { baseUrl ->
                    Uri.parse(baseUrl).let { uri -> "${uri.scheme}://${uri.authority}" }
                }
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        webView.value ?: WebView(ctx).apply {
                            setBackgroundColor(webBackground)
                            var timeZoneScriptInstalled = false
                            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                                timeZoneScriptInstalled = runCatching {
                                    WebViewCompat.addDocumentStartJavaScript(
                                        this,
                                        TIME_ZONE_FIX_SCRIPT,
                                        setOfNotNull(webOrigin),
                                    )
                                    true
                                }.getOrDefault(false)
                            }
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
                                    if (!timeZoneScriptInstalled) {
                                        view.evaluateJavascript(TIME_ZONE_FIX_SCRIPT, null)
                                    }
                                    onPageStarted()
                                }

                                override fun onPageFinished(view: WebView, url: String) {
                                    view.evaluateJavascript(
                                        "$TIME_ZONE_FIX_SCRIPT\nIntl.DateTimeFormat().resolvedOptions().timeZone",
                                    ) { rawTimeZone ->
                                        val timeZone = rawTimeZone?.trim('"')
                                        if (timeZone != null && !isValidWebTimeZone(timeZone)) {
                                            Log.w("DshWebView", "Invalid browser time zone '$timeZone'; UTC fallback applied")
                                        }
                                        onPageFinished(view)
                                    }
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

                                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                                    val uri = Uri.parse(url)
                                    // The engine's own UI stays inside the app.
                                    if (uri.host == "127.0.0.1" || uri.host == "localhost") return false
                                    // Anything else hands off to the system browser; returning
                                    // true alone would just make the tap do nothing.
                                    runCatching {
                                        view.context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                    }
                                    return true
                                }
                            }
                            webView.value = this
                        }
                    },
                )

                when {
                    loadError != null -> Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        CenteredMessage(
                            title = stringResource(com.fioiu8.dshpocket.R.string.chat_load_failed),
                            body = stringResource(com.fioiu8.dshpocket.R.string.chat_retry_hint) + "\n" + loadError,
                            busy = false,
                        )
                    }
                    loading -> Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        CenteredMessage(
                            title = stringResource(com.fioiu8.dshpocket.R.string.chat_loading),
                            body = stringResource(com.fioiu8.dshpocket.R.string.chat_connecting),
                            busy = true,
                        )
                    }
                }
            }

            is EngineState.Failed -> CenteredMessage(
                title = stringResource(com.fioiu8.dshpocket.R.string.engine_failed_to_start),
                body = state.message.asString(),
                busy = false,
            )

            else -> CenteredMessage(
                title = stringResource(com.fioiu8.dshpocket.R.string.engine_starting),
                body = (state as? EngineState.Busy)?.message?.asString() ?: stringResource(com.fioiu8.dshpocket.R.string.engine_preparing),
                busy = true,
            )
        }
    }
}

private val TIME_ZONE_FIX_SCRIPT = """
(() => {
  const prototype = Intl.DateTimeFormat.prototype;
  if (prototype.__dshPocketTimeZoneFixed) return;
  const original = prototype.resolvedOptions;
  prototype.resolvedOptions = function() {
    const options = original.apply(this, arguments);
    const zone = options.timeZone;
    const valid = zone === 'UTC' || isAreaLocationTimeZone(zone);
    if (!valid) options.timeZone = 'UTC';
    return options;
  };
  Object.defineProperty(prototype, '__dshPocketTimeZoneFixed', { value: true });
  function isAreaLocationTimeZone(zone) {
    if (typeof zone !== 'string' || !/^[A-Za-z_+-]+(/[A-Za-z0-9._+-]+)+$/.test(zone)) return false;
    try { new Intl.DateTimeFormat('en', { timeZone: zone }); return true; }
    catch (_) { return false; }
  }
})();
""".trimIndent()

private fun isValidWebTimeZone(timeZone: String): Boolean =
    timeZone == "UTC" || timeZone.matches(Regex("[A-Za-z_+-]+(/[A-Za-z0-9._+-]+)+"))

internal val VIEWPORT_FIX_SCRIPT = """
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
