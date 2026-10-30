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
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.fioiu8.dshpocket.ui.state.EngineModelSource
import com.fioiu8.dshpocket.ui.state.EnginePluginSource
import com.fioiu8.dshpocket.ui.state.EngineSessionSource
import com.fioiu8.dshpocket.ui.state.ModelRepository
import com.fioiu8.dshpocket.ui.state.PluginRepository
import com.fioiu8.dshpocket.ui.state.SessionRepository
import com.fioiu8.dshpocket.ui.state.SharedPreferencesArchiveStore
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import android.content.Intent
import com.fioiu8.dshpocket.capability.Capabilities
import com.fioiu8.dshpocket.capability.CapabilitySnapshot
import com.fioiu8.dshpocket.engine.EngineController
import com.fioiu8.dshpocket.engine.EnginePaths
import com.fioiu8.dshpocket.engine.EngineService
import com.fioiu8.dshpocket.notify.TurnNotifier
import com.fioiu8.dshpocket.engine.EngineState
import com.fioiu8.dshpocket.engine.SessionCookie
import com.fioiu8.dshpocket.ui.theme.DshMotion
import com.fioiu8.dshpocket.ui.theme.DshShapes
import com.fioiu8.dshpocket.ui.theme.DshSpacing
import com.fioiu8.dshpocket.ui.theme.motionDuration
import com.fioiu8.dshpocket.ui.theme.systemAnimationsEnabled
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * =============================================================================
 * 状态归属规则 —— Phase 1 放行条件④。写在文件头而非某个枚举上，
 * 因为它约束的是本包所有屏幕，不是某一段导航。
 *
 * - **导航状态留在 `rememberSaveable`**：`destination`、`settingsSection`、
 *   `chatPresentation`、各页面的搜索框/筛选/待确认项。它们必须熬过进程被系统杀死，
 *   而 ViewModel 做不到。
 * - **数据状态进 ViewModel + Repository**：引擎返回的一切（会话、插件、模型配置）。
 *   它们是异步的，不该因为切页被 `rememberCoroutineScope` 取消。
 * - **模型只存原始值**：`@StringRes Int` 与 `LocalizedText` 是两种类型，
 *   但遵循同一条原则——不要在模型里存渲染完成的字符串。语言在绘制时解析。
 * - **Composable 只画，不取数**：不手搓 `LocalContext.current.resources`。
 *   会话轮询由 AppShell 持有唯一一份（Repository 幂等启动）。两种屏幕内轮询，
 *   均为 shipped 行为并与等价基线一致，拥有第二消费者时才上移：插件页 15 秒
 *   清单轮询（无第二消费者）；模型页仅在 DeepSeek 登录进行中时的 1.5 秒
 *   account 轮询，其写回经 Repository。
 *
 * 例外：HomeScreen 里那个一秒 `while (true)` 是运行时长读数的本地时钟，
 * 不发 RPC，属于该 Composable，不归 Repository 管。
 * =============================================================================
 */

/**
 * 导航目标（设计稿抽屉：m3e-canvas.json 的 drawer_* 六项）。
 */
// @param: keeps the annotation on the constructor parameter only. Without the
// explicit target, Kotlin 2.2 warns (KT-73255) that a future release will also
// apply it to the backing field.
enum class Destination(@param:StringRes val labelRes: Int, val icon: ImageVector) {
    Home(com.fioiu8.dshpocket.R.string.nav_home, Icons.Filled.Home),
    Chat(com.fioiu8.dshpocket.R.string.nav_chat, Icons.Filled.Forum),
    Sessions(com.fioiu8.dshpocket.R.string.nav_sessions, Icons.Filled.Folder),
    Plugins(com.fioiu8.dshpocket.R.string.nav_plugins, Icons.Filled.Extension),
    Models(com.fioiu8.dshpocket.R.string.nav_models, Icons.Filled.Tune),
    Terminal(com.fioiu8.dshpocket.R.string.nav_terminal, Icons.Filled.Terminal),
    Settings(com.fioiu8.dshpocket.R.string.nav_settings, Icons.Filled.Settings),
}

/** Read one coherent capability view for UI and generated agent context. */
fun readCapabilities(context: android.content.Context): CapabilitySnapshot =
    Capabilities.snapshot(context, EnginePaths.workspace(context))

private fun loadNativeChatPreferences(context: android.content.Context): NativeChatPreferences {
    val prefs = context.getSharedPreferences("ui_preferences", android.content.Context.MODE_PRIVATE)
    return NativeChatPreferences(
        showMessageTime = prefs.getBoolean("native_chat_show_time", true),
        showTokenUsage = prefs.getBoolean("native_chat_show_tokens", true),
        showDuration = prefs.getBoolean("native_chat_show_duration", true),
        showAssistReply = prefs.getBoolean("native_chat_show_assist", false),
    )
}

private fun saveNativeChatPreferences(context: android.content.Context, preferences: NativeChatPreferences) {
    context.getSharedPreferences("ui_preferences", android.content.Context.MODE_PRIVATE)
        .edit()
        .putBoolean("native_chat_show_time", preferences.showMessageTime)
        .putBoolean("native_chat_show_tokens", preferences.showTokenUsage)
        .putBoolean("native_chat_show_duration", preferences.showDuration)
        .putBoolean("native_chat_show_assist", preferences.showAssistReply)
        .apply()
}

/** Flat key/value pairs survive process death; a list sidesteps nested-map saver edge cases. */
private val chatDraftsSaver = Saver<Map<String, String>, List<String>>(
    save = { map -> map.flatMap { listOf(it.key, it.value) } },
    restore = { pairs ->
        if (pairs.size % 2 == 0) pairs.chunked(2).associate { it[0] to it[1] } else emptyMap()
    },
)

@Composable
fun AppShell(
    controller: EngineController,
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
) {
    val context = LocalContext.current
    val state by controller.state.collectAsState()
    val log by controller.log.collectAsState()
    val animationsEnabled = systemAnimationsEnabled()
    val pageDuration = motionDuration(DshMotion.pageDurationMillis)
    val exitFadeDuration = motionDuration(140)
    val exitSlideDuration = motionDuration(160)
    val scope = rememberCoroutineScope()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    // The exit-restore rule (user-named): the background still running means the task is
    // still wanted, so the last screen comes back from disk instead of resetting to Home.
    val navigationMemory = remember { com.fioiu8.dshpocket.ui.state.NavigationMemory(context) }
    var destination by rememberSaveable {
        mutableStateOf(
            navigationMemory.destinationName()
                ?.let { stored -> Destination.entries.firstOrNull { it.name == stored } }
                ?: Destination.Home,
        )
    }
    var settingsSection by rememberSaveable { mutableStateOf(navigationMemory.settingsSection()) }
    var restoredChatSessionId by remember { mutableStateOf(navigationMemory.chatSessionId()) }
    LaunchedEffect(destination, settingsSection, restoredChatSessionId) {
        navigationMemory.save(destination.name, settingsSection, restoredChatSessionId)
    }
    var chatPresentation by remember {
        mutableStateOf(
            context.getSharedPreferences("ui_preferences", android.content.Context.MODE_PRIVATE)
                .getString("chat_presentation", ChatPresentation.Native.key)
                ?.let { ChatPresentation.fromKey(it) } ?: ChatPresentation.Native,
        )
    }
    var nativeChatPreferences by remember {
        mutableStateOf(loadNativeChatPreferences(context))
    }
    var capabilities by remember { mutableStateOf(readCapabilities(context)) }
    // A session picked on the sessions screen is parked here until the chat screen has
    // adopted it, so "打开" actually opens that conversation instead of just switching tabs.
    var pendingOpenSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    // Per-conversation composer drafts (user-named behavior): typing in one conversation
    // never leaks into another, and a new chat preserves the typed text into the "new"
    // slot so the next new chat auto-fills it. Hoisted here because the chat screen
    // leaves composition when another destination opens.
    var chatDrafts by rememberSaveable(stateSaver = chatDraftsSaver) { mutableStateOf(emptyMap<String, String>()) }

    // Shared per process, so the home card and the session screen read one source of
    // truth and the poll loop runs once instead of once per screen. The archive store
    // keeps the archived set across restarts (session/list does not report it).
    val sessionRepository = remember {
        SessionRepository.get(controller.applicationScope, {
            val ready = controller.state.value as? EngineState.Ready
            ready?.let { EngineSessionSource(it.baseUrl, it.token) }
        }, SharedPreferencesArchiveStore(context.getSharedPreferences("ui_preferences", android.content.Context.MODE_PRIVATE)))
    }

    // Reads-only repository for the model screen; writes stay in the screen because their
    // form drafts and busy handling are UI concerns there.
    val modelRepository = remember {
        ModelRepository.get {
            val ready = controller.state.value as? EngineState.Ready
            ready?.let { EngineModelSource(it.baseUrl, it.token) }
        }
    }

    // Reads-only repository for the plugin screen; the toggle RPC reports through it and
    // the screen keeps its own busy/refresh handling.
    val pluginRepository = remember {
        PluginRepository.get {
            val ready = controller.state.value as? EngineState.Ready
            ready?.let { EnginePluginSource(it.baseUrl, it.token) }
        }
    }

    // Turn-finished notifications. Keyed on engine state only: tab switches must not
    // restart the loop, and a non-Ready state stops it and forgets the running set.
    LaunchedEffect(state) {
        val ready = state as? EngineState.Ready
        if (ready == null) {
            TurnNotifier.stop()
            com.fioiu8.dshpocket.overlay.ThoughtOverlayService.stop(context)
        } else {
            TurnNotifier.watch(
                controller.applicationScope,
                context,
                ready,
            )
            com.fioiu8.dshpocket.overlay.ThoughtOverlayService.start(context)
        }
    }

    // The WebView instance outlives tab switches so returning to 对话 does not reload it.
    val webView = remember { mutableStateOf<WebView?>(null) }
    var webLoaded by remember { mutableStateOf(false) }
    var webLoading by remember { mutableStateOf(false) }
    var webLoadError by remember { mutableStateOf<String?>(null) }
    var blankRetryCount by remember { mutableIntStateOf(0) }
    // The empty-body check is posted onto the WebView with a delay; remembering the
    // runnable lets a page start (or disposal) cancel it instead of firing detached.
    val blankCheck = remember { mutableStateOf<Runnable?>(null) }

    val debugBuild = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    if (debugBuild) {
        LaunchedEffect(Unit) { WebView.setWebContentsDebuggingEnabled(true) }
    }

    // Native resources of the WebView are only reclaimed deterministically through
    // destroy(); without it every Activity recreation (rotation, language switch) pins
    // the old instance — renderer threads included — until GC feels like it.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val wv = webView.value ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_PAUSE -> wv.onPause()
                Lifecycle.Event.ON_RESUME -> wv.onResume()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            blankCheck.value?.let { webView.value?.removeCallbacks(it) }
            webView.value?.destroy()
            webView.value = null
        }
    }

    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }

    // The thought overlay only shows OUTSIDE the app; the chat screen already has the
    // thinking card. Started/stopped (not paused/resumed) so translucent overlays —
    // the gate and permission dialogs — do not count as "leaving the app".
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START ->
                    com.fioiu8.dshpocket.overlay.AppForegroundTracker.setForeground(true)
                androidx.lifecycle.Lifecycle.Event.ON_STOP ->
                    com.fioiu8.dshpocket.overlay.AppForegroundTracker.setForeground(false)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Resolved in composable scope and captured: stringResource cannot be called from
    // the evaluateJavascript callback, and LocalContext there would not recompose.
    val labelChatEmptyResponse = stringResource(com.fioiu8.dshpocket.R.string.chat_empty_response)
    val navigate: (Destination) -> Unit = { dest ->
        destination = dest
        if (dest != Destination.Settings) settingsSection = null
        scope.launch { drawerState.close() }
    }

    LaunchedEffect(Unit) { controller.start() }

    // One owner for the session poll: started when the engine becomes ready, stopped
    // when it leaves Ready — without the stop branch the loop would keep waking the
    // process every 15s in the background and after the engine was stopped on purpose.
    // startPolling is idempotent, so a later screen composing alongside this one adds
    // no extra fetches and no second loop.
    LaunchedEffect(controller) {
        controller.state.collect { ready ->
            if (ready is com.fioiu8.dshpocket.engine.EngineState.Ready) sessionRepository.startPolling()
            else sessionRepository.stopPolling()
        }
    }

    BackHandler(enabled = destination == Destination.Settings && settingsSection != null) {
        settingsSection = null
    }

    // The DSH Web UI is an SPA: once it pushed a sub-page onto the WebView history, the
    // system back button must walk that history instead of finishing the activity.
    BackHandler(
        enabled = destination == Destination.Chat &&
            chatPresentation == ChatPresentation.Web &&
            webView.value?.canGoBack() == true,
    ) {
        webView.value?.goBack()
    }

    // Re-read on resume, not only on navigation: returning from the system all-files
    // access page does not change any navigation state, so a destination-keyed effect
    // would never fire and the capability cards would stay stale.
    LaunchedEffect(destination) { capabilities = readCapabilities(context) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            val fresh = readCapabilities(context)
            if (fresh != capabilities) {
                capabilities = fresh
                // The agent briefs itself from AGENTS.md, which is only rewritten on
                // spawn; push the new capabilities so its briefing does not go stale
                // while the engine keeps running.
                controller.refreshBriefing(fresh)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state) {
        if (state !is EngineState.Ready) {
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
    // Only runs while the WebView is the visible chat presentation; in native mode the
    // detached WebView must not be poked once per second.
    LaunchedEffect(destination, state, webView.value, webLoaded, chatPresentation) {
        val s = state
        val wv = webView.value
        if (
            destination != Destination.Chat ||
            chatPresentation != ChatPresentation.Web ||
            s !is EngineState.Ready ||
            wv == null ||
            !webLoaded
        ) return@LaunchedEffect
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
                        webLoadError = labelChatEmptyResponse
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

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    text = "DSH Pocket",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 20.dp),
                )
                HorizontalDivider(Modifier.padding(bottom = 8.dp))
                Destination.entries.forEach { dest ->
                    NavigationDrawerItemLabel(
                        dest = dest,
                        selected = destination == dest,
                        onClick = { navigate(dest) },
                    )
                }
            }
        },
    ) {
        AnimatedContent(
            targetState = destination to if (destination == Destination.Settings) settingsSection else null,
            transitionSpec = {
                (
                    fadeIn(animationSpec = tween(pageDuration)) +
                        slideInHorizontally(
                            animationSpec = if (animationsEnabled) {
                                spring(dampingRatio = 0.86f, stiffness = 420f)
                            } else {
                                tween(0)
                            },
                            initialOffsetX = { it / 12 },
                        )
                    ) togetherWith (
                        fadeOut(animationSpec = tween(exitFadeDuration)) +
                            slideOutHorizontally(
                                animationSpec = tween(exitSlideDuration),
                                targetOffsetX = { -it / 20 },
                            )
                        ) using SizeTransform(clip = false)
            },
            label = "destination-transition",
        ) { navigationTarget ->
        when (navigationTarget.first) {
            Destination.Home -> HomeScreen(
                state = state,
                repository = sessionRepository,
                onOpenChat = { destination = Destination.Chat },
                onOpenSessions = { destination = Destination.Sessions },
                onOpenPlugins = { destination = Destination.Plugins },
                onOpenModels = { destination = Destination.Models },
                onMenu = openDrawer,
                onRestart = {
                    EngineService.start(context)
                    controller.restart()
                },
            )

            Destination.Chat -> {
                val readyState = state as? EngineState.Ready
                if (chatPresentation == ChatPresentation.Native) {
                    NativeChatScreen(
                        state = state,
                        onMenu = openDrawer,
                        repository = sessionRepository,
                        preferences = nativeChatPreferences,
                        pendingOpenSessionId = pendingOpenSessionId,
                        onPendingSessionConsumed = { pendingOpenSessionId = null },
                        initialSessionId = restoredChatSessionId,
                        onSessionIdChanged = { restoredChatSessionId = it },
                        chatDrafts = chatDrafts,
                        onChatDraftChange = { slot, text ->
                            chatDrafts = if (text.isEmpty()) chatDrafts - slot else chatDrafts + (slot to text)
                        },
                    )
                } else {
                    ChatScreen(
                        state = state,
                        onMenu = openDrawer,
                        webView = webView,
                        loading = webLoading,
                        loadError = webLoadError,
                        onPageStarted = {
                            // A new navigation invalidates any pending empty-body check.
                            blankCheck.value?.let { pending -> webView.value?.removeCallbacks(pending) }
                            blankCheck.value = null
                            webLoading = true
                            webLoadError = null
                        },
                        onPageFinished = { view ->
                            val check = Runnable {
                                view.evaluateJavascript(VIEWPORT_FIX_SCRIPT) { rawHeight ->
                                    val height = rawHeight.trim('"').toFloatOrNull() ?: 0f
                                    if (height < 1f && blankRetryCount < 1) {
                                        blankRetryCount += 1
                                        webLoaded = false
                                        webLoading = true
                                        Log.w("DshWebView", "page finished with empty body; retrying")
                                        view.loadUrl("${readyState?.baseUrl ?: ""}/?token=${readyState?.token ?: ""}")
                                    } else if (height < 1f) {
                                        webLoading = false
                                        webLoadError = labelChatEmptyResponse
                                        Log.e("DshWebView", "page finished with empty body after retry")
                                    } else {
                                        webLoading = false
                                        webLoaded = true
                                        webLoadError = null
                                    }
                                }
                            }
                            blankCheck.value = check
                            view.postDelayed(check, 350)
                        },
                        onPageError = { message ->
                            webLoaded = false
                            webLoading = false
                            webLoadError = message
                        },
                    )
                }
            }

            Destination.Sessions -> SessionsScreen(
                state = state,
                repository = sessionRepository,
                onMenu = openDrawer,
                onOpenChat = { sessionId ->
                    pendingOpenSessionId = sessionId
                    destination = Destination.Chat
                },
            )

            Destination.Plugins -> PluginsScreen(state = state, onMenu = openDrawer, pluginRepository = pluginRepository)

            Destination.Models -> ModelsScreen(state = state, onMenu = openDrawer, modelRepository = modelRepository)

            Destination.Terminal -> TerminalScreen(onMenu = openDrawer)

            Destination.Settings -> {
                val section = navigationTarget.second?.let { SettingsSection.fromKey(it) }
                if (section == null) {
                    SettingsHomeScreen(
                        onMenu = openDrawer,
                        onOpenSection = { settingsSection = it.key },
                    )
                } else {
                    SettingsDetailScreen(
                        section = section,
                        state = state,
                        log = log,
                        capabilities = capabilities,
                        chatPresentation = chatPresentation,
                        onChatPresentationChange = { presentation ->
                            chatPresentation = presentation
                            context.getSharedPreferences("ui_preferences", android.content.Context.MODE_PRIVATE)
                                .edit().putString("chat_presentation", presentation.key).apply()
                        },
                        nativeChatPreferences = nativeChatPreferences,
                        onNativeChatPreferencesChange = { preferences ->
                            nativeChatPreferences = preferences
                            saveNativeChatPreferences(context, preferences)
                        },
                        onBack = { settingsSection = null },
                        onOpenModels = { destination = Destination.Models; settingsSection = null },
                        onOpenPlugins = { destination = Destination.Plugins; settingsSection = null },
                        onOpenTerminal = { destination = Destination.Terminal; settingsSection = null },
                        onOpenScreenControl = { settingsSection = SettingsSection.ScreenControl.key },
                        onGrantAllFiles = { context.startActivity(Capabilities.allFilesAccessIntent(context)) },
                        onRestart = {
                            webLoaded = false
                            EngineService.start(context)
                            controller.restart()
                        },
                        onStop = {
                            // byUser marks the choice so the next activity recreation
                            // (which unconditionally calls controller.start()) does not
                            // quietly undo it.
                            controller.stop(byUser = true)
                            EngineService.stop(context)
                        },
                        onScreenControlChanged = {
                            // Rewrites AGENTS.md's managed screen-control block so the
                            // agent reads the new state on its next turn, no respawn.
                            controller.refreshBriefing(capabilities)
                        },
                            language = language,
                            onLanguageChange = onLanguageChange,
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun NavigationDrawerItemLabel(dest: Destination, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .background(
                if (selected) colors.secondaryContainer else Color.Transparent,
                RoundedCornerShape(DshShapes.control),
            )
            .clip(RoundedCornerShape(DshShapes.control))
            .clickable(onClick = onClick)
            .heightIn(min = DshSpacing.touchTarget)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .background(
                    if (selected) colors.primary else colors.surfaceContainerHighest,
                    CircleShape,
                ),
        ) {
            Icon(
                dest.icon,
                contentDescription = null,
                tint = if (selected) colors.onPrimary else colors.onSurfaceVariant,
                modifier = Modifier.size(DshSpacing.icon),
            )
        }
        Spacer(Modifier.size(12.dp))
        Text(
            stringResource(dest.labelRes),
        style = MaterialTheme.typography.bodyLarge,
        color = if (selected) colors.onSurface else colors.onSurfaceVariant,
        )
    }
}

/**
 * 各屏共用的顶栏（设计稿 topAppBar，variant filled）：菜单图标开抽屉；
 * [subtitle] 用于首页的实时引擎状态与对话页的模型信息。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DshTopAppBar(
    title: String,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    TopAppBar(
        modifier = modifier,
        navigationIcon = {
            IconButton(
                onClick = onBack ?: onMenu,
                modifier = Modifier.size(DshSpacing.touchTarget),
            ) {
                Icon(
                    imageVector = if (onBack == null) Icons.Filled.Menu else Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(
                        if (onBack == null) com.fioiu8.dshpocket.R.string.open_navigation_drawer
                        else com.fioiu8.dshpocket.R.string.back_to_settings,
                    ),
                )
            }
        },
        title = {
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        actions = { actions() },
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ChatScreen(
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
        modifier = Modifier
            .fillMaxSize()
            .padding(DshSpacing.page),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (busy) CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        if (body.isNotBlank()) {
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = DshSpacing.page, vertical = DshSpacing.section),
    )
}

/** 分组连续列表容器（m3e 拼接列表）：整组共用一个圆角面，行间自动加细分隔线。
 *
 *  [keys] gives rows a stable identity so per-row `remember` state (an open menu, a
 *  toggle) follows its row when the list reorders instead of staying with the position.
 */
@Composable
fun GroupedList(
    modifier: Modifier = Modifier,
    items: List<@Composable () -> Unit>,
    keys: List<Any>? = null,
) {
    val shape = RoundedCornerShape(DshShapes.surface)
    val listAnimationDuration = motionDuration(180)
    Surface(
        modifier = modifier.border(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
            shape,
        ),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Column {
            items.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(
                    modifier = Modifier.padding(horizontal = DshSpacing.page),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                )
                // MutableTransitionState(false→true) is what makes the entrance play at
                // all: AnimatedVisibility(visible = true) never leaves its initial state,
                // so the whole stagger used to be dead code.
                val entrance = remember { MutableTransitionState(false).apply { targetState = true } }
                AnimatedVisibility(
                    visibleState = entrance,
                    enter = fadeIn(
                        animationSpec = tween(
                            durationMillis = listAnimationDuration,
                            delayMillis = if (listAnimationDuration == 0) 0 else {
                                index.coerceAtMost(DshMotion.maxStaggerItems - 1) * DshMotion.staggerStepMillis
                            },
                        ),
                    ) + slideInVertically(
                        animationSpec = tween(
                            durationMillis = listAnimationDuration,
                            delayMillis = if (listAnimationDuration == 0) 0 else {
                                index.coerceAtMost(DshMotion.maxStaggerItems - 1) * DshMotion.staggerStepMillis
                            },
                        ),
                        initialOffsetY = { it / 32 },
                    ),
                ) {
                    if (keys != null && index < keys.size) {
                        key(keys[index]) { item() }
                    } else {
                        item()
                    }
                }
            }
        }
    }
}

@Composable
fun LogPanel(log: List<String>, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DshSpacing.page, vertical = DshSpacing.compact),
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
        Modifier
            .fillMaxWidth()
            .heightIn(min = DshSpacing.touchTarget)
            .padding(horizontal = DshSpacing.row, vertical = DshSpacing.rowVertical),
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.row),
        verticalAlignment = Alignment.Top,
    ) {
        Text(key, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.8f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1.2f),
        )
    }
}

