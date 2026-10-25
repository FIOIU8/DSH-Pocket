package com.fioiu8.dshpocket.ui

import android.content.ClipData
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fioiu8.dshpocket.engine.EngineApi
import com.fioiu8.dshpocket.engine.EngineApi.RpcResult
import com.fioiu8.dshpocket.engine.EnginePaths
import com.fioiu8.dshpocket.engine.EngineState
import com.fioiu8.dshpocket.overlay.ThoughtStreamBus
import com.fioiu8.dshpocket.ui.state.PromptOutcome
import com.fioiu8.dshpocket.ui.state.NativeChatToolCallStore
import com.fioiu8.dshpocket.ui.state.SessionRepository
import com.fioiu8.dshpocket.ui.state.SessionViewModel
import com.fioiu8.dshpocket.ui.state.SessionViewModelFactory
import com.fioiu8.dshpocket.ui.theme.DshSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class NativeChatMessage(
    val id: String,
    val role: String,
    val text: String,
    val time: Long,
    val isSystemPrompt: Boolean = false,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val durationMs: Long? = null,
    val suggestions: List<String> = emptyList(),
    // Journal sequence used to interleave the tool-call overlay with surface messages.
    val seq: Long = -1L,
    // tool_call rows: the durable `tool/call` event plus its paired `tool/result`.
    val toolName: String? = null,
    val callId: String? = null,
    val argumentsText: String? = null,
    val resultText: String? = null,
    val isError: Boolean = false,
) {
    val isRunningToolCall: Boolean get() = role == "tool_call" && resultText == null
}

/** Live reasoning state between the stream's first reasoning delta and the answer text. */
data class LiveThinking(
    val text: String,
    val startedAtMs: Long,
    val finishedAtMs: Long? = null,
) {
    val durationMs: Long? get() = finishedAtMs?.let { it - startedAtMs }

    /** The snippet the collapsed live card shows: the model's latest thought line. */
    val latestLine: String? get() = text.lines().lastOrNull { it.isNotBlank() }?.take(160)
}

data class NativeChatPreferences(
    val showMessageTime: Boolean = true,
    val showTokenUsage: Boolean = true,
    val showDuration: Boolean = true,
    val showAssistReply: Boolean = false,
)

data class NativeChatSession(val id: String, val title: String, val running: Boolean)

data class NativeModelOption(
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val name: String,
    val efforts: List<Pair<String, String>>,
)

data class NativeChatStyle(
    val userBubble: Color,
    val assistantBubble: Color,
    val cornerRadius: Int,
    val fontScale: Float,
)

enum class ChatPresentation(val key: String, val labelRes: Int) {
    Web("web", com.fioiu8.dshpocket.R.string.native_web_chat),
    Native("native", com.fioiu8.dshpocket.R.string.native_native_chat),
    ;

    companion object {
        fun fromKey(key: String): ChatPresentation? = entries.firstOrNull { it.key == key }
    }
}

private fun defaultNativeChatStyle(dark: Boolean) = NativeChatStyle(
    userBubble = if (dark) Color(0xFF34495E) else Color(0xFFDDE3FF),
    assistantBubble = if (dark) Color(0xFF25282D) else Color(0xFFF1F3F6),
    cornerRadius = 18,
    fontScale = 1f,
)

/** Draft slot key for a fresh, not-yet-created conversation. */
private const val DRAFT_NEW_SLOT = "new"

@Composable
fun NativeChatScreen(
    state: EngineState,
    onMenu: () -> Unit,
    repository: SessionRepository,
    preferences: NativeChatPreferences = NativeChatPreferences(),
    pendingOpenSessionId: String? = null,
    onPendingSessionConsumed: () -> Unit = {},
    initialSessionId: String? = null,
    onSessionIdChanged: (String?) -> Unit = {},
    chatDrafts: Map<String, String> = emptyMap(),
    onChatDraftChange: (String, String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val ready = state as? EngineState.Ready
    val darkTheme = isSystemInDarkTheme()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    // The prompt send lives in a ViewModel on purpose: a rotation between tapping send
    // and delivery must not cancel the request — the screen-scoped coroutine used to die
    // mid-flight and silently swallow both the message and the cleared draft.
    val sessionViewModel: SessionViewModel = viewModel(
        key = "native-chat",
        factory = SessionViewModelFactory(repository),
    )
    val promptInFlight by sessionViewModel.promptInFlight.collectAsState()
    val promptOutcome by sessionViewModel.promptOutcome.collectAsState()
    val createdSessionId by sessionViewModel.createdSessionId.collectAsState()
    var sessions by remember { mutableStateOf<List<NativeChatSession>>(emptyList()) }
    // Seeded from NavigationMemory so an exit-reopen returns to the same conversation.
    var sessionId by rememberSaveable { mutableStateOf<String?>(initialSessionId) }
    LaunchedEffect(sessionId) { onSessionIdChanged(sessionId) }
    var messages by remember { mutableStateOf<List<NativeChatMessage>>(emptyList()) }
    // Composer drafts live per conversation slot ("new" for a fresh chat), so typing in
    // one conversation never leaks into another and a new chat auto-fills its slot.
    val draft = chatDrafts[sessionId ?: DRAFT_NEW_SLOT].orEmpty()
    fun updateDraft(text: String) = onChatDraftChange(sessionId ?: DRAFT_NEW_SLOT, text)
    var running by remember { mutableStateOf(false) }
    var liveAssistantText by remember { mutableStateOf("") }
    var liveThinking by remember { mutableStateOf<LiveThinking?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showSessions by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var pickerModel by remember { mutableStateOf<NativeModelOption?>(null) }
    var modelOptions by remember { mutableStateOf<List<NativeModelOption>>(emptyList()) }
    var loadedCursor by remember(sessionId) { mutableLongStateOf(-2L) }
    var style by remember(darkTheme) { mutableStateOf(defaultNativeChatStyle(darkTheme)) }
    // `session/page` never serves tool/call events; this store keeps the ones the live
    // stream delivered so history renders the rows after every refresh and relaunch.
    val toolCallStore = remember { NativeChatToolCallStore(context) }
    val visibleMessages = remember(messages, liveAssistantText, liveThinking) {
        val live = liveThinking?.takeIf { it.text.isNotBlank() }
        var settled = messages
        if (live != null) {
            // The in-flight step lands in history through its attempt event too; while it
            // streams, the live card is the single source so the tail never doubles up.
            while (settled.lastOrNull()?.role == "thinking") settled = settled.dropLast(1)
        }
        (settled +
            listOfNotNull(
                live?.let { NativeChatMessage("live-thinking", "thinking", it.text, 0L, durationMs = it.durationMs) },
                if (liveAssistantText.isBlank()) null else NativeChatMessage("live-assistant", "assistant", liveAssistantText, 0L),
            )).filterNot { it.isSystemPrompt || it.role == "suggestions" }
    }
    val suggestions = remember(messages) { latestNativeSuggestions(messages) }

    // Resolved here, in composable scope, then captured. `stringResource` cannot be
    // called from a LaunchedEffect block or a callback lambda, and reading the string
    // through LocalContext there would not recompose when the language changes.
    val labelSessionLoadFailed = stringResource(com.fioiu8.dshpocket.R.string.native_session_load_failed)
    val labelEffortDefault = stringResource(com.fioiu8.dshpocket.R.string.native_effort_default)
    val labelAssistPrompt = stringResource(com.fioiu8.dshpocket.R.string.native_assist_prompt)
    val labelStopFailed = stringResource(com.fioiu8.dshpocket.R.string.native_stop_failed)
    val labelSendFailed = stringResource(com.fioiu8.dshpocket.R.string.native_send_failed)
    val labelCreateSessionFailed = stringResource(com.fioiu8.dshpocket.R.string.native_create_session_failed)
    val labelSwitchModelFailed = stringResource(com.fioiu8.dshpocket.R.string.native_switch_model_failed)
    val labelUntitledSession = stringResource(com.fioiu8.dshpocket.R.string.home_untitled_session)
    val labelImageAttachment = stringResource(com.fioiu8.dshpocket.R.string.native_image_attachment)
    val labelFileAttachment = stringResource(com.fioiu8.dshpocket.R.string.native_file_attachment)

    suspend fun loadSessions(engine: EngineState.Ready) {
        val result = chatRpc(
            engine.baseUrl,
            "session/list",
            EngineApi.emptyArgs().put("_request", JSONObject()),
            engine.token,
        )
        if (result is RpcResult.Ok) {
            sessions = parseNativeChatSessions(EngineApi.itemsOf(result.value), labelUntitledSession)
            if (sessionId == null) sessionId = sessions.firstOrNull()?.id
        }
    }

    suspend fun refreshMessages(engine: EngineState.Ready, id: String) {
        val projectionResult = chatRpc(
            engine.baseUrl,
            "session/projections",
            EngineApi.requestArgs(JSONObject().put("sessionId", id)),
            engine.token,
        )
        val projection = when (projectionResult) {
            is RpcResult.Err -> {
                error = labelSessionLoadFailed + ": ${projectionResult.message}"
                return
            }
            is RpcResult.Ok -> projectionResult.value as? JSONObject
        }
        val cursor = projection?.optLong("asOfSeq", Long.MIN_VALUE) ?: Long.MIN_VALUE
        if (cursor == Long.MIN_VALUE) {
            error = labelSessionLoadFailed + ": response cursor missing"
            return
        }
        running = sessions.firstOrNull { it.id == id }?.running ?: false
        if (cursor == loadedCursor) return
        val pageResult = chatRpc(
            engine.baseUrl,
            "session/page",
            EngineApi.requestArgs(
                JSONObject()
                    .put("address", JSONObject().put("kind", "session").put("sessionId", id))
                    .put("throughSeq", cursor)
                    .put("maxMessages", 100),
            ),
            engine.token,
        )
        when (pageResult) {
            is RpcResult.Ok -> {
                val page = pageResult.value as? JSONObject
                val records = page?.optJSONArray("records")
                if (records == null) {
                    error = labelSessionLoadFailed + ": message records missing"
                    return
                }
                val parsed = parseNativeChatMessages(records, labelImageAttachment, labelFileAttachment, toolCallStore.entries(id))
                messages = parsed
                loadedCursor = cursor
                error = null
            }
            is RpcResult.Err -> error = labelSessionLoadFailed + ": ${pageResult.message}"
        }
    }

    LaunchedEffect(ready) {
        val engine = ready ?: return@LaunchedEffect
        val catalog = chatRpc(engine.baseUrl, "session/modelCatalog", EngineApi.emptyArgs(), engine.token)
        if (catalog is RpcResult.Ok) modelOptions = parseNativeModelCatalog(
            catalog.value as? JSONObject,
            labelEffortDefault,
        )
        val themeResult = chatRpc(engine.baseUrl, "dshPocketUi/getNativeChatTheme", EngineApi.emptyArgs(), engine.token)
        if (themeResult is RpcResult.Ok) {
            val raw = themeResult.value as? JSONObject
            style = parseNativeChatStyle(raw?.optJSONObject("theme") ?: raw, darkTheme) ?: defaultNativeChatStyle(darkTheme)
        }
        loading = true
        loadSessions(engine)
        loading = false
    }

    LaunchedEffect(ready, sessionId) {
        val engine = ready ?: return@LaunchedEffect
        val id = sessionId ?: return@LaunchedEffect
        loading = true
        refreshMessages(engine, id)
        loading = false
        liveAssistantText = ""
        liveThinking = null
        coroutineScope {
            launch {
                var poll = 0
                while (true) {
                    delay(1_500)
                    poll++
                    if (poll % 5 == 0) loadSessions(engine)
                    refreshMessages(engine, id)
                }
            }
            try {
                EngineApi.followSession(engine.baseUrl, id).collect { frame ->
                    when (frame.optString("type")) {
                        "snapshot" -> {
                            val snapshotSessionId = sessionId
                            messages = parseNativeChatMessages(
                                frame.optJSONArray("records") ?: JSONArray(),
                                labelImageAttachment,
                                labelFileAttachment,
                                snapshotSessionId?.let { toolCallStore.entries(it) } ?: emptyList(),
                            )
                            loadedCursor = frame.optLong("cursor", -1L)
                            liveAssistantText = assistantStreamSnapshotText(
                                frame.optJSONObject("assistantStream")?.optJSONObject("activeAttempt")?.optJSONArray("stream"),
                            )
                            // Reconnect mid-reasoning: restore the in-flight thinking card.
                            val reasoning = streamReasoningText(
                                frame.optJSONObject("assistantStream")?.optJSONObject("activeAttempt")?.optJSONArray("stream"),
                            )
                            liveThinking = if (reasoning.isBlank()) null else LiveThinking(reasoning, System.currentTimeMillis())
                            if (reasoning.isNotBlank()) ThoughtStreamBus.reasoningRestored(reasoning)
                        }
                        "event" -> {
                            val event = frame.optJSONObject("event") ?: return@collect
                            if (event.optString("type") == "assistant/message") {
                                liveAssistantText = ""
                                liveThinking = null
                            }
                            if (event.optString("type") == "tool/call") {
                                val callSessionId = sessionId
                                val callData = event.optJSONObject("data")
                                if (callSessionId != null && callData != null) {
                                    toolCallStore.append(
                                        callSessionId,
                                        NativeChatToolCallStore.Entry(
                                            event.optLong("seq"),
                                            callData.optString("callId"),
                                            callData.optString("name"),
                                            callData.optString("arguments"),
                                        ),
                                    )
                                }
                            }
                            messages = applySessionEvent(messages, event, labelImageAttachment, labelFileAttachment)
                        }
                        "assistant-stream" -> {
                            val streamFrame = frame.optJSONObject("frame") ?: return@collect
                            when (streamFrame.optString("type")) {
                                "start" -> {
                                    liveAssistantText = ""
                                    liveThinking = LiveThinking("", System.currentTimeMillis())
                                    ThoughtStreamBus.turnStarted()
                                }
                                "chunk" -> {
                                    val chunk = streamFrame.optJSONObject("chunk") ?: return@collect
                                    when (chunk.optString("type")) {
                                        "reasoning-delta" -> {
                                            val current = liveThinking ?: LiveThinking("", System.currentTimeMillis())
                                            liveThinking = current.copy(text = current.text + chunk.optString("text"))
                                            ThoughtStreamBus.reasoningDelta(chunk.optString("text"))
                                        }
                                        "text-delta" -> {
                                            // The answer has started; the thinking phase is over.
                                            liveThinking = liveThinking?.let {
                                                if (it.finishedAtMs == null) it.copy(finishedAtMs = System.currentTimeMillis()) else it
                                            }
                                            liveAssistantText += chunk.optString("text")
                                            ThoughtStreamBus.answerStarted()
                                        }
                                        "tool-call-delta" -> {
                                            liveThinking = liveThinking?.let {
                                                if (it.finishedAtMs == null) it.copy(finishedAtMs = System.currentTimeMillis()) else it
                                            }
                                            ThoughtStreamBus.answerStarted()
                                        }
                                    }
                                }
                                "end" -> {
                                    refreshMessages(engine, id)
                                    liveAssistantText = ""
                                    liveThinking = null
                                    ThoughtStreamBus.turnEnded()
                                    loadSessions(engine)
                                }
                            }
                        }
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = "${labelSessionLoadFailed}: ${failure.message.orEmpty()}"
            }
        }
    }

    // One-shot: the sessions screen asked to open a specific conversation here.
    LaunchedEffect(pendingOpenSessionId) {
        val id = pendingOpenSessionId ?: return@LaunchedEffect
        sessionId = id
        messages = emptyList()
        liveAssistantText = ""
        loading = true
        error = null
        onPendingSessionConsumed()
    }

    // Outcome of a ViewModel-sent prompt: report errors, restore the draft on failure,
    // and adopt a session the ViewModel had to create.
    LaunchedEffect(promptOutcome) {
        when (val outcome = promptOutcome) {
            is PromptOutcome.Delivered -> {
                error = null
                val engine = ready
                if (engine != null) {
                    loadSessions(engine)
                    refreshMessages(engine, outcome.sessionId)
                }
            }
            is PromptOutcome.Failed -> {
                error = "${labelSendFailed}: ${outcome.message}"
                if (outcome.restoreDraft && draft.isBlank()) updateDraft(outcome.text)
            }
            null -> {}
        }
        sessionViewModel.consumePromptOutcome()
    }

    LaunchedEffect(createdSessionId) {
        createdSessionId?.let { id ->
            sessionId = id
            sessionViewModel.consumeCreatedSession()
        }
    }

    // Follow the tail only while the user is actually reading it: a manual scroll up must
    // survive polling updates and stream chunks. Stickiness is only re-evaluated while the
    // user themselves is dragging or flinging — programmatic scrolls (the load jump, the
    // streaming pins) used to flip it off mid-flight and leave the list stuck in the middle.
    val stickToBottom = remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        var userScrolling = false
        coroutineScope {
            launch {
                listState.interactionSource.interactions.collect { interaction ->
                    if (interaction is DragInteraction.Start) userScrolling = true
                }
            }
            snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
                if (!scrolling) {
                    userScrolling = false
                    return@collect
                }
                if (!userScrolling) return@collect
                val info = listState.layoutInfo
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                stickToBottom.value = lastVisible >= info.totalItemsCount - 2
            }
        }
    }
    // Opening a conversation must land on the newest message instantly; the flag is
    // consumed by the first size change after the switch (the actual message load).
    var jumpToBottomOnLoad by remember { mutableStateOf(true) }
    LaunchedEffect(sessionId) {
        stickToBottom.value = true
        jumpToBottomOnLoad = true
    }

    LaunchedEffect(visibleMessages.size, promptInFlight) {
        if (visibleMessages.isNotEmpty() && stickToBottom.value) {
            if (jumpToBottomOnLoad) {
                jumpToBottomOnLoad = false
                // Instant jump with the bottom-pin offset: a plain scrollToItem aligns
                // the item's TOP with the viewport, which leaves tall tail messages
                // showing their beginning instead of the newest content.
                listState.scrollToItem(visibleMessages.lastIndex, scrollOffset = 1_000_000)
            } else {
                listState.animateScrollToItem(visibleMessages.lastIndex)
            }
        }
    }
    // Streaming grows the tail bubble in place; scrolling to the item would keep its top
    // pinned and leave the newest text below the fold, so pin the list bottom instead.
    LaunchedEffect(liveAssistantText) {
        if (liveAssistantText.isNotBlank() && stickToBottom.value && visibleMessages.isNotEmpty()) {
            listState.scrollToItem(visibleMessages.lastIndex, scrollOffset = 1_000_000)
        }
    }
    // Growing reasoning changes the last card's height without changing the item count;
    // the same bottom pin keeps the newest thought line visible.
    LaunchedEffect(liveThinking?.text) {
        if (liveThinking?.text?.isNotBlank() == true && stickToBottom.value && visibleMessages.isNotEmpty()) {
            listState.scrollToItem(visibleMessages.lastIndex, scrollOffset = 1_000_000)
        }
    }

    Scaffold(
        topBar = {
            DshTopAppBar(
                title = sessions.firstOrNull { it.id == sessionId }?.title ?: stringResource(com.fioiu8.dshpocket.R.string.native_new_chat),
                onMenu = onMenu,
                subtitle = stringResource(com.fioiu8.dshpocket.R.string.native_subtitle),
                actions = {
                    IconButton(
                        onClick = { showModelPicker = true; pickerModel = null },
                        modifier = Modifier.size(DshSpacing.touchTarget),
                    ) {
                        Icon(Icons.Filled.Tune, contentDescription = stringResource(com.fioiu8.dshpocket.R.string.native_select_model_effort), modifier = Modifier.size(DshSpacing.icon))
                    }
                    IconButton(onClick = { showSessions = true }, modifier = Modifier.size(DshSpacing.touchTarget)) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = stringResource(com.fioiu8.dshpocket.R.string.native_select_session), modifier = Modifier.size(DshSpacing.icon))
                    }
                    IconButton(
                        onClick = {
                            // 用户命名行为：输入框有内容时新建会话，内容保留给新会话槽，
                            // 下次新建会话自动填充；当前会话槽清空，避免回切时残留。
                            val currentSlot = sessionId ?: DRAFT_NEW_SLOT
                            if (currentSlot != DRAFT_NEW_SLOT && draft.isNotBlank()) {
                                onChatDraftChange(DRAFT_NEW_SLOT, draft)
                                onChatDraftChange(currentSlot, "")
                            }
                            sessionId = null; messages = emptyList(); liveAssistantText = ""; error = null
                        },
                        modifier = Modifier.size(DshSpacing.touchTarget),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(com.fioiu8.dshpocket.R.string.native_new_chat), modifier = Modifier.size(DshSpacing.icon))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            when {
                ready == null -> CenteredMessage(
                    title = if (state is EngineState.Failed) stringResource(com.fioiu8.dshpocket.R.string.engine_failed_to_start) else stringResource(com.fioiu8.dshpocket.R.string.engine_starting),
                    body = when (state) {
                        is EngineState.Failed -> state.message
                        is EngineState.Busy -> state.message
                        else -> stringResource(com.fioiu8.dshpocket.R.string.engine_preparing)
                    },
                    busy = state !is EngineState.Failed,
                )
                else -> {
                    if (error != null && visibleMessages.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            CenteredMessage(stringResource(com.fioiu8.dshpocket.R.string.native_session_load_failed), error.orEmpty(), busy = false)
                        }
                    } else if (loading && visibleMessages.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            CenteredMessage(stringResource(com.fioiu8.dshpocket.R.string.native_sessions_loading), "", busy = true)
                        }
                    } else if (sessionId == null) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(stringResource(com.fioiu8.dshpocket.R.string.native_start_message), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        if (error != null) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = DshSpacing.page, vertical = 4.dp),
                                shape = MaterialTheme.shapes.medium,
                            ) {
                                Text(
                                    error.orEmpty(),
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                )
                            }
                        }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = DshSpacing.page,
                                vertical = DshSpacing.section,
                            ),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(visibleMessages, key = { it.id }) { message ->
                                when (message.role) {
                                    "tool_call" -> ToolCallCard(message)
                                    "thinking" -> ThinkingCard(message, live = message.id == "live-thinking")
                                    else -> NativeMessageBubble(
                                        message = message,
                                        style = style,
                                        preferences = preferences,
                                        onAssist = if (preferences.showAssistReply && message.role == "assistant") {
                                        {
                                            // Same ViewModel path as the composer so the
                                            // assist prompt survives a rotation too, and so
                                            // it follows the app language instead of being
                                            // hardcoded Chinese.
                                            if (!promptInFlight && ready != null) {
                                                error = null
                                                sessionViewModel.sendPrompt(
                                                    engine = ready,
                                                    text = labelAssistPrompt,
                                                    sessionId = sessionId,
                                                    workspacePath = EnginePaths.workspace(context).absolutePath,
                                                    restoreDraft = false,
                                                )
                                            }
                                        }
                                    } else null,
                                    )
                                }
                            }
                            if ((promptInFlight || running) && liveThinking?.text.isNullOrBlank()) {
                                item(key = "thinking") {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.padding(vertical = 8.dp),
                                    ) {
                                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                        Text(stringResource(com.fioiu8.dshpocket.R.string.native_thinking), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }

                    ChatComposer(
                        value = draft,
                        suggestions = suggestions,
                        onValueChange = { updateDraft(it) },
                        sending = promptInFlight,
                        running = running,
                        // ready is smart-cast non-null inside this branch.
                        enabled = true,
                        onCancel = {
                            val id = sessionId
                            val engine = ready
                            if (id != null) {
                                scope.launch {
                                    val result = chatRpc(
                                        engine.baseUrl,
                                        "session/cancel",
                                        EngineApi.requestArgs(JSONObject().put("sessionId", id)),
                                        engine.token,
                                    )
                                    if (result is RpcResult.Err) error = "${labelStopFailed}: ${result.message}"
                                    loadSessions(engine)
                                    refreshMessages(engine, id)
                                }
                            }
                        },
                        onSend = {
                            val text = draft.trim()
                            val engine = ready
                            if (text.isNotEmpty() && !promptInFlight && engine != null) {
                                updateDraft("")
                                error = null
                                sessionViewModel.sendPrompt(
                                    engine = engine,
                                    text = text,
                                    sessionId = sessionId,
                                    workspacePath = EnginePaths.workspace(context).absolutePath,
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    if (showSessions) {
        AlertDialog(
            onDismissRequest = { showSessions = false },
            title = { Text(stringResource(com.fioiu8.dshpocket.R.string.native_select_session)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    sessions.forEach { session ->
                        TextButton(
                            onClick = {
                                showSessions = false
                                sessionId = session.id
                                messages = emptyList()
                                liveAssistantText = ""
                                loading = true
                                error = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = session.title,
                                maxLines = 2,
                                color = if (session.id == sessionId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    if (sessions.isEmpty()) Text(stringResource(com.fioiu8.dshpocket.R.string.native_no_other_sessions))
                }
            },
            confirmButton = { TextButton(onClick = { showSessions = false }) { Text(stringResource(com.fioiu8.dshpocket.R.string.close)) } },
        )
    }

    if (showModelPicker) {
        AlertDialog(
            onDismissRequest = { showModelPicker = false; pickerModel = null },
            title = { Text(if (pickerModel == null) stringResource(com.fioiu8.dshpocket.R.string.native_select_model) else "${pickerModel?.name} · ${stringResource(com.fioiu8.dshpocket.R.string.native_select_model_effort)}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (pickerModel == null) {
                        modelOptions.forEach { option ->
                            TextButton(onClick = { pickerModel = option }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(option.name)
                                    Text("${option.providerName} · ${option.modelId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        if (modelOptions.isEmpty()) Text(stringResource(com.fioiu8.dshpocket.R.string.native_no_models))
                    } else {
                        TextButton(onClick = { pickerModel = null }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(com.fioiu8.dshpocket.R.string.native_back_to_models)) }
                        val option = pickerModel!!
                        val efforts = option.efforts.ifEmpty { listOf("default" to labelEffortDefault) }
                        efforts.forEach { (effortId, effortName) ->
                            TextButton(
                                onClick = {
                                    val engine = ready
                                    if (engine != null) scope.launch {
                                        var targetId = sessionId
                                        if (targetId == null) {
                                            val created = chatRpc(
                                                engine.baseUrl,
                                                "session/create",
                                                EngineApi.requestArgs(JSONObject().put("cwd", EnginePaths.workspace(context).absolutePath)),
                                                engine.token,
                                            )
                                            targetId = ((created as? RpcResult.Ok)?.value as? JSONObject)?.optString("sessionId")
                                                ?.takeIf(String::isNotBlank)
                                            if (targetId == null) {
                                                error = (created as? RpcResult.Err)?.message ?: labelCreateSessionFailed
                                                return@launch
                                            }
                                            sessionId = targetId
                                        }
                                        val request = JSONObject()
                                            .put("sessionId", targetId)
                                            .put("provider", option.providerId)
                                            .put("model", option.modelId)
                                            .apply { if (effortId != "default") put("reasoningEffort", effortId) }
                                        when (val result = chatRpc(engine.baseUrl, "session/selectModel", EngineApi.requestArgs(request), engine.token)) {
                                            is RpcResult.Err -> error = "${labelSwitchModelFailed}: ${result.message}"
                                            is RpcResult.Ok -> {
                                                error = null
                                                showModelPicker = false
                                                pickerModel = null
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(effortName) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showModelPicker = false; pickerModel = null }) { Text(stringResource(com.fioiu8.dshpocket.R.string.close)) } },
        )
    }
}

@Composable
private fun NativeMessageBubble(
    message: NativeChatMessage,
    style: NativeChatStyle,
    preferences: NativeChatPreferences,
    onAssist: (() -> Unit)?,
) {
    val fromUser = message.role == "user"
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = if (fromUser) Modifier.widthIn(max = 360.dp) else Modifier.fillMaxWidth(),
            horizontalAlignment = if (fromUser) Alignment.End else Alignment.Start,
        ) {
            if (fromUser) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(bottom = 8.dp, end = 2.dp),
                ) {
                    Text(stringResource(com.fioiu8.dshpocket.R.string.native_user), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(Color(0xFF9AD85F), Color(0xFF42B8B0)))),
                    )
                }
            }
            if (fromUser) {
                Surface(
                    color = style.userBubble,
                    // MD3 token instead of the engine-driven radius, so every bubble
                    // matches the app-wide shape scale; the engine still picks the color.
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.widthIn(max = 340.dp).wrapContentWidth(),
                ) {
                    SelectionContainer {
                        MarkdownText(
                            markdown = message.text,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 13.dp),
                            fontSize = (MaterialTheme.typography.bodyLarge.fontSize.value * style.fontScale).sp,
                            lineHeight = (26f * style.fontScale).sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            } else {
                SelectionContainer {
                    MarkdownText(
                        markdown = message.text,
                        modifier = Modifier.fillMaxWidth(),
                        fontSize = (MaterialTheme.typography.bodyLarge.fontSize.value * style.fontScale).sp,
                        lineHeight = (27f * style.fontScale).sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            MessageMetadata(message, preferences, onAssist) {
                scope.launch {
                    clipboard.setClipEntry(
                        ClipEntry(ClipData.newPlainText("message", message.text)),
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageMetadata(
    message: NativeChatMessage,
    preferences: NativeChatPreferences,
    onAssist: (() -> Unit)?,
    onCopy: () -> Unit,
) {
    val fromUser = message.role == "user"
    // Read the locale observably. Locale.getDefault() would not recompose when the user
    // switches the app language, leaving numbers formatted in the old locale.
    val locale = LocalConfiguration.current.locales[0]
    val metadata = buildList {
        if (preferences.showMessageTime && message.time > 0) add(formatMessageTime(message.time, locale))
        if (preferences.showTokenUsage) {
            message.inputTokens?.let { add("↑ $it") }
            message.outputTokens?.let { add("↓ $it") }
        }
        if (preferences.showDuration) {
            message.durationMs?.let { duration ->
                add(if (duration >= 1000) "%.1fs".format(locale, duration / 1000.0) else "${duration}ms")
                if (message.outputTokens != null && duration > 0) {
                    add("%.1f tok/s".format(locale, message.outputTokens * 1000.0 / duration))
                }
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(com.fioiu8.dshpocket.R.string.copy), modifier = Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onAssist != null) {
            IconButton(onClick = onAssist, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = stringResource(com.fioiu8.dshpocket.R.string.settings_assist_reply), modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (metadata.isNotEmpty()) {
            Text(
                metadata.joinToString("  ·  "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatMessageTime(rawTime: Long, locale: Locale): String {
    val millis = if (rawTime in 1..100_000_000_000L) rawTime * 1000 else rawTime
    // A localized skeleton instead of a hardcoded "MM月dd日" pattern, which leaked Chinese
    // date glyphs into every other locale.
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd HHmmss")
    return SimpleDateFormat(pattern, locale).format(Date(millis))
}

@Composable
private fun ChatComposer(
    value: String,
    suggestions: List<String>,
    onValueChange: (String) -> Unit,
    sending: Boolean,
    running: Boolean,
    enabled: Boolean,
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var showMore by remember { mutableStateOf(false) }
    Column {
        if (suggestions.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                suggestions.forEach { suggestion ->
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.58f),
                        shape = CircleShape,
                        modifier = Modifier.clickable { onValueChange(suggestion) },
                    ) {
                        Text(
                            suggestion,
                            modifier = Modifier.padding(horizontal = 15.dp, vertical = 9.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraLarge),
        ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        if (value.isEmpty()) {
                            Text(stringResource(com.fioiu8.dshpocket.R.string.native_input_placeholder), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        BasicTextField(
                            value = value,
                            onValueChange = onValueChange,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = enabled && !sending,
                            minLines = 1,
                            maxLines = 5,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(25.dp))
                    Spacer(Modifier.weight(1f))
                    Box {
                        IconButton(onClick = { showMore = true }, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Filled.Add, contentDescription = stringResource(com.fioiu8.dshpocket.R.string.more_actions), tint = MaterialTheme.colorScheme.onSurface)
                        }
                        DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(com.fioiu8.dshpocket.R.string.paste_clipboard)) },
                                onClick = {
                                    scope.launch {
                                        val pasted = clipboard.getClipEntry()?.clipData
                                            ?.takeIf { it.itemCount > 0 }
                                            ?.getItemAt(0)?.text
                                        if (pasted != null) onValueChange(value + pasted)
                                    }
                                    showMore = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(com.fioiu8.dshpocket.R.string.clear)) },
                                onClick = { onValueChange(""); showMore = false },
                            )
                        }
                    }
                    if (running) {
                        FilledIconButton(onClick = onCancel, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Filled.Stop, contentDescription = stringResource(com.fioiu8.dshpocket.R.string.stop))
                        }
                    } else {
                        FilledIconButton(
                            onClick = onSend,
                            enabled = enabled && value.isNotBlank() && !sending,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(com.fioiu8.dshpocket.R.string.send))
                        }
                    }
                }
            }
        }
    }
}

internal fun parseNativeChatSessions(items: JSONArray, untitledTitle: String): List<NativeChatSession> = buildList {
    for (index in 0 until items.length()) {
        val item = items.optJSONObject(index) ?: continue
        if (item.optBoolean("blank")) continue
        val values = item.optJSONObject("projections")?.optJSONObject("values")
        val title = values?.optString("title")?.takeIf { it.isNotBlank() && it != "null" } ?: untitledTitle
        add(NativeChatSession(item.optString("sessionId"), title, item.optBoolean("running")))
    }
}

internal fun parseNativeModelCatalog(catalog: JSONObject?, defaultEffortLabel: String): List<NativeModelOption> = buildList {
    val groups = catalog?.optJSONArray("groups") ?: return@buildList
    for (groupIndex in 0 until groups.length()) {
        val group = groups.optJSONObject(groupIndex) ?: continue
        val models = group.optJSONArray("models") ?: continue
        for (modelIndex in 0 until models.length()) {
            val model = models.optJSONObject(modelIndex) ?: continue
            val modelId = model.optString("id").takeIf(String::isNotBlank) ?: continue
            val effortValues = model.optJSONObject("reasoning")?.optJSONArray("efforts")
            val efforts = buildList {
                add("default" to defaultEffortLabel)
                if (effortValues != null) for (effortIndex in 0 until effortValues.length()) {
                    val effort = effortValues.optJSONObject(effortIndex) ?: continue
                    val id = effort.optString("id").takeIf(String::isNotBlank) ?: continue
                    add(id to effort.optString("name").ifBlank { id })
                }
            }
            add(
                NativeModelOption(
                    group.optString("id"),
                    group.optString("name").ifBlank { group.optString("id") },
                    modelId,
                    model.optString("name").ifBlank { modelId },
                    efforts,
                ),
            )
        }
    }
}

internal fun assistantStreamSnapshotText(stream: JSONArray?): String = buildString {
    if (stream == null) return@buildString
    for (index in 0 until stream.length()) {
        val record = stream.optJSONObject(index) ?: continue
        when (record.optString("type")) {
            "text-chunks" -> {
                val chunks = record.optJSONArray("texts") ?: continue
                for (chunkIndex in 0 until chunks.length()) append(chunks.optString(chunkIndex))
            }
            "chunk" -> {
                val chunk = record.optJSONObject("chunk") ?: continue
                if (chunk.optString("type") == "text-delta") append(chunk.optString("text"))
            }
        }
    }
}

/**
 * Reasoning text from one assistant stream record list. Three shapes exist:
 * - live accumulator / reconnect baseline: `{time, chunk:{type:"reasoning-delta", text}}`
 * - durable assistant/message stream: `{type:"chunk", time, chunk:{type:"block-end",
 *   block:{type:"reasoning", text}}}` (the final text) and batched
 *   `{type:"reasoning-chunks", texts:[...]}` records.
 */
internal fun streamReasoningText(stream: JSONArray?): String {
    if (stream == null) return ""
    val blocks = StringBuilder()
    val deltas = StringBuilder()
    var sawDelta = false
    val batched = StringBuilder()
    for (index in 0 until stream.length()) {
        val record = stream.optJSONObject(index) ?: continue
        when (record.optString("type")) {
            "chunk" -> {
                val chunk = record.optJSONObject("chunk") ?: continue
                when (chunk.optString("type")) {
                    "block-end" -> {
                        val block = chunk.optJSONObject("block") ?: continue
                        if (block.optString("type") == "reasoning") blocks.append(block.optString("text"))
                    }
                    "reasoning-delta" -> {
                        sawDelta = true
                        deltas.append(chunk.optString("text"))
                    }
                }
            }
            "reasoning-chunks" -> {
                val texts = record.optJSONArray("texts") ?: continue
                for (textIndex in 0 until texts.length()) batched.append(texts.optString(textIndex))
            }
            // Live accumulator records are bare `{time, chunk}` with no record type.
            else -> {
                val chunk = record.optJSONObject("chunk") ?: continue
                when (chunk.optString("type")) {
                    "block-end" -> {
                        val block = chunk.optJSONObject("block") ?: continue
                        if (block.optString("type") == "reasoning") blocks.append(block.optString("text"))
                    }
                    "reasoning-delta" -> {
                        sawDelta = true
                        deltas.append(chunk.optString("text"))
                    }
                }
            }
        }
    }
    return when {
        blocks.isNotBlank() -> blocks.toString()
        sawDelta -> deltas.toString()
        else -> batched.toString()
    }
}

/** Time span of the reasoning in one assistant stream record list; null when undatable. */
internal fun streamReasoningDurationMs(stream: JSONArray?): Long? {
    if (stream == null) return null
    var blockStart = -1L
    var blockEnd = -1L
    var deltaFirst = -1L
    var deltaLast = -1L
    var batchedStart = -1L
    var batchedSpan = 0L
    for (index in 0 until stream.length()) {
        val record = stream.optJSONObject(index) ?: continue
        val time = record.optLong("time", if (record.has("time0")) record.optLong("time0") else -1L)
        when (record.optString("type")) {
            "chunk" -> {
                val chunk = record.optJSONObject("chunk") ?: continue
                when (chunk.optString("type")) {
                    "block-start" -> if (chunk.optString("blockType") == "reasoning") blockStart = time
                    "block-end" -> if (chunk.optJSONObject("block")?.optString("type") == "reasoning") blockEnd = time
                    "reasoning-delta" -> {
                        if (deltaFirst < 0) deltaFirst = time
                        if (time > deltaLast) deltaLast = time
                    }
                }
            }
            "reasoning-chunks" -> {
                if (batchedStart < 0) batchedStart = time
                record.optJSONArray("dt")?.let { dt ->
                    for (dtIndex in 0 until dt.length()) batchedSpan += dt.optLong(dtIndex)
                }
            }
            else -> {
                val chunk = record.optJSONObject("chunk") ?: continue
                when (chunk.optString("type")) {
                    "block-start" -> if (chunk.optString("blockType") == "reasoning") blockStart = time
                    "block-end" -> if (chunk.optJSONObject("block")?.optString("type") == "reasoning") blockEnd = time
                    "reasoning-delta" -> {
                        if (deltaFirst < 0) deltaFirst = time
                        if (time > deltaLast) deltaLast = time
                    }
                }
            }
        }
    }
    return when {
        blockStart > 0 && blockEnd > blockStart -> blockEnd - blockStart
        deltaFirst > 0 && deltaLast > deltaFirst -> deltaLast - deltaFirst
        batchedStart > 0 && batchedSpan > 0 -> batchedSpan
        else -> null
    }
}

internal fun toolResultText(result: JSONObject, imagePlaceholder: String, filePlaceholder: String): String {
    val message = result.optJSONObject("message")
    val fromContent = contentText(message?.optJSONArray("content"), imagePlaceholder, filePlaceholder)
    return fromContent.ifBlank { message?.optString("text").orEmpty() }
}

internal fun parseNativeChatMessages(
    records: JSONArray,
    imagePlaceholder: String,
    filePlaceholder: String,
    toolCallOverlay: List<com.fioiu8.dshpocket.ui.state.NativeChatToolCallStore.Entry> = emptyList(),
): List<NativeChatMessage> {
    val messages = ArrayList<NativeChatMessage>()
    // callId -> index of the tool_call row still waiting for its result.
    val pendingCalls = HashMap<String, Int>()
    // Results whose tool/call is not in the surface page (it lives in the overlay).
    val orphanResults = LinkedHashMap<String, Pair<String, Boolean>>()
    // True once this step's thinking already came from an `assistant/attempt` event, so
    // the assistant message's own stream must not produce a second, identical card.
    var attemptThinkingThisStep = false
    for (index in 0 until records.length()) {
        val event = records.optJSONObject(index)?.optJSONObject("event") ?: continue
        val type = event.optString("type")
        val data = event.optJSONObject("data") ?: continue
        val seq = event.optLong("seq")
        if (type == "tool/call") {
            val name = data.optString("name")
            // The suggestions row is a rendered tool result already; its call would be noise.
            if (name == "show_chat_suggestions") continue
            attemptThinkingThisStep = false
            pendingCalls[data.optString("callId")] = messages.size
            messages.add(
                NativeChatMessage(
                    id = "tool-call-$seq",
                    role = "tool_call",
                    text = "",
                    time = event.optLong("time"),
                    seq = seq,
                    toolName = name,
                    callId = data.optString("callId"),
                    argumentsText = data.optString("arguments"),
                ),
            )
            continue
        }
        if (type == "tool/result") {
            val meta = data.optJSONObject("meta")
            if (meta?.optString("kind") == "dsh-pocket/native-chat-suggestions") {
                val suggestions = meta.optJSONArray("suggestions") ?: continue
                val values = buildList {
                    for (suggestionIndex in 0 until suggestions.length()) {
                        val suggestion = suggestions.optString(suggestionIndex).trim()
                        if (suggestion.length in 2..80 && suggestion !in this) add(suggestion)
                    }
                }.take(4)
                if (values.size >= 2) {
                    messages.add(
                        NativeChatMessage(
                            id = "suggestions-$seq",
                            role = "suggestions",
                            text = "",
                            time = event.optLong("time"),
                            seq = seq,
                            suggestions = values,
                        ),
                    )
                }
                continue
            }
            val message = data.optJSONObject("message")
            val callId = message?.optString("toolCallId").orEmpty()
            val result = toolResultText(data, imagePlaceholder, filePlaceholder)
            val isError = message?.optBoolean("isError", false) == true || data.has("error")
            val target = pendingCalls.remove(callId)
            if (target != null && target < messages.size) {
                val call = messages[target]
                if (call.callId == callId) {
                    messages[target] = call.copy(resultText = result, isError = isError)
                    continue
                }
            }
            if (callId.isNotBlank()) orphanResults[callId] = result to isError
            continue
        }
        if (type == "assistant/attempt") {
            val reasoning = streamReasoningText(data.optJSONArray("stream"))
            if (reasoning.isNotBlank()) {
                messages.add(
                    NativeChatMessage(
                        id = "thinking-$seq",
                        role = "thinking",
                        text = reasoning,
                        time = event.optLong("time"),
                        seq = seq,
                        durationMs = streamReasoningDurationMs(data.optJSONArray("stream")),
                    ),
                )
                attemptThinkingThisStep = true
            }
            continue
        }
        val message = when (type) {
            "user/message" -> data
            "system/message" -> data.optJSONObject("message") ?: data
            "developer/message" -> data.optJSONObject("message") ?: data
            "assistant/message" -> data.optJSONObject("message")
            else -> null
        } ?: continue
        val text = contentText(message.optJSONArray("content"), imagePlaceholder, filePlaceholder)
            .ifBlank { message.optString("text").takeIf { it != "null" }.orEmpty() }
        if (text.isBlank()) continue
        val isSystemPrompt = type == "system/message" || type == "developer/message" || isNativeSystemPrompt(text)
        val role = when {
            type == "assistant/message" -> "assistant"
            type == "user/message" && !isSystemPrompt -> "user"
            else -> "system"
        }
        // Fallback for engines that log no `assistant/attempt` events: lift the final
        // step's reasoning out of the assistant message's own stream record.
        if (role == "assistant" && !attemptThinkingThisStep) {
            val reasoning = streamReasoningText(data.optJSONArray("stream"))
            if (reasoning.isNotBlank()) {
                messages.add(
                    NativeChatMessage(
                        id = "thinking-$seq",
                        role = "thinking",
                        text = reasoning,
                        time = event.optLong("time"),
                        seq = seq,
                        durationMs = streamReasoningDurationMs(data.optJSONArray("stream")),
                    ),
                )
            }
        }
        if (role != "system") attemptThinkingThisStep = false
        val usage = listOfNotNull(
            data.optJSONObject("usage"),
            data.optJSONObject("tokenUsage"),
            data.optJSONObject("tokens"),
            message.optJSONObject("usage"),
            message.optJSONObject("metadata"),
        ).firstOrNull()
        val inputTokens = firstLong(usage, "inputTokens", "promptTokens", "input_tokens", "prompt_tokens")
        val outputTokens = firstLong(usage, "outputTokens", "completionTokens", "output_tokens", "completion_tokens")
        val durationMs = firstDurationMs(
            listOf(usage, data, message),
            "durationMs",
            "duration_ms",
            "elapsedMs",
            "latencyMs",
            "duration",
            "elapsed",
        )
        messages.add(
            NativeChatMessage(
                message.optString("id", "$seq"),
                role,
                text,
                event.optLong("time"),
                isSystemPrompt,
                inputTokens,
                outputTokens,
                durationMs,
                seq = seq,
            ),
        )
    }
    if (toolCallOverlay.isEmpty()) return messages
    // The overlay carries the tool/call events `session/page` never serves. But the
    // follow snapshot's records DO include raw events on some paths — skip any call the
    // parsed history already produced, or the row would appear twice under one key.
    val existingCallIds = messages.mapNotNull { it.callId }.toSet()
    val overlayRows = toolCallOverlay
        .filter { it.callId !in existingCallIds }
        .map { entry ->
            val (result, isError) = orphanResults[entry.callId] ?: (null to false)
            NativeChatMessage(
                id = "tool-call-${entry.seq}",
                role = "tool_call",
                text = "",
                time = 0L,
                seq = entry.seq,
                toolName = entry.name,
                callId = entry.callId,
                argumentsText = entry.arguments,
                resultText = result,
                isError = isError,
            )
        }
    if (overlayRows.isEmpty()) return messages
    return (messages + overlayRows).sortedBy { it.seq }
}

/**
 * Folds one live durable event into the current message list. `tool/result` updates its
 * pending `tool/call` row in place (a single-event parse cannot see it); everything else
 * appends with the usual id dedupe.
 */
internal fun applySessionEvent(
    existing: List<NativeChatMessage>,
    event: JSONObject,
    imagePlaceholder: String,
    filePlaceholder: String,
): List<NativeChatMessage> {
    val type = event.optString("type")
    val data = event.optJSONObject("data") ?: return existing
    if (type == "tool/result") {
        val message = data.optJSONObject("message")
        val callId = message?.optString("toolCallId").orEmpty()
        if (callId.isBlank()) return existing
        val result = toolResultText(data, imagePlaceholder, filePlaceholder)
        val isError = message?.optBoolean("isError", false) == true || data.has("error")
        val index = existing.indexOfLast { it.role == "tool_call" && it.callId == callId && it.resultText == null }
        if (index < 0) return existing
        return existing.mapIndexed { i, item -> if (i == index) item.copy(resultText = result, isError = isError) else item }
    }
    val records = JSONArray().put(JSONObject().put("event", event))
    val incoming = parseNativeChatMessages(records, imagePlaceholder, filePlaceholder)
    if (incoming.isEmpty()) return existing
    return (existing + incoming).distinctBy { it.id }
}

internal fun latestNativeSuggestions(messages: List<NativeChatMessage>): List<String> {
    var suggestions = emptyList<String>()
    messages.forEach { message ->
        when (message.role) {
            "user" -> suggestions = emptyList()
            "suggestions" -> suggestions = message.suggestions
        }
    }
    return suggestions
}

internal fun isNativeSystemPrompt(text: String): Boolean {
    val normalized = text.trim()
    return normalized.contains("<system-reminder>", ignoreCase = true) ||
        normalized.contains("</system-reminder>", ignoreCase = true) ||
        normalized.contains("Current runtime context.", ignoreCase = true) ||
        normalized.contains("Current DSH file policy:", ignoreCase = true) ||
        normalized.contains("Approval policy:", ignoreCase = true)
}

private fun firstLong(value: JSONObject?, vararg keys: String): Long? {
    value ?: return null
    keys.forEach { key ->
        if (value.has(key) && !value.isNull(key)) {
            val number = value.optLong(key, Long.MIN_VALUE)
            if (number != Long.MIN_VALUE) return number
        }
    }
    return null
}

private fun firstDurationMs(values: List<JSONObject?>, vararg keys: String): Long? {
    values.forEach { value ->
        value ?: return@forEach
        keys.forEach { key ->
            if (!value.has(key) || value.isNull(key)) return@forEach
            val raw = value.optLong(key, Long.MIN_VALUE)
            if (raw == Long.MIN_VALUE) return@forEach
            return if (key.contains("Ms", ignoreCase = true)) raw else if (raw < 1000) raw * 1000 else raw
        }
    }
    return null
}

private fun contentText(content: JSONArray?, imagePlaceholder: String, filePlaceholder: String): String = buildList {
    if (content == null) return@buildList
    for (index in 0 until content.length()) {
        val block = content.optJSONObject(index) ?: continue
        when (block.optString("type")) {
            "text" -> block.optString("text").takeIf { it.isNotBlank() }?.let(::add)
            "image" -> add(imagePlaceholder)
            "file" -> add(filePlaceholder)
        }
    }
}.joinToString("\n")

internal fun parseNativeChatStyle(value: JSONObject?, dark: Boolean): NativeChatStyle? {
    value ?: return null
    return runCatching {
        val userHex = value.optString("userBubble")
        val assistantHex = value.optString("assistantBubble")
        require(userHex.matches(Regex("#[0-9A-Fa-f]{6}")))
        require(assistantHex.matches(Regex("#[0-9A-Fa-f]{6}")))
        val user = Color(android.graphics.Color.parseColor(userHex))
        val assistant = Color(android.graphics.Color.parseColor(assistantHex))
        val radius = value.optInt("cornerRadiusDp", 18).coerceIn(8, 28)
        val scale = value.optDouble("fontScale", 1.0).toFloat().coerceIn(0.9f, 1.25f)
        NativeChatStyle(user, assistant, radius, scale)
    }.getOrElse {
        defaultNativeChatStyle(dark)
    }
}

private suspend fun chatRpc(
    baseUrl: String,
    endpoint: String,
    args: JSONObject,
    token: String,
): RpcResult = try {
    EngineApi.rpc(baseUrl, endpoint, args, token)
} catch (cancelled: kotlinx.coroutines.CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    RpcResult.Err("transport", failure.message ?: "Unable to connect to local engine")
}

// ---------------------------------------------------------------------------
// Tool-call and thinking cards
// ---------------------------------------------------------------------------

/** One collapsed tool-call row: which icon to draw and which localized label + arg. */
internal data class ToolCallLabel(@StringRes val labelRes: Int, val arg: String? = null)

private fun JSONObject.firstString(vararg keys: String): String? {
    keys.forEach { key ->
        val value = optString(key).takeIf { it.isNotBlank() }
        if (value != null && value != "null") return value
    }
    return null
}

private fun JSONObject.pathArg(): String? = firstString("file_path", "path", "absolutePath", "file")?.take(140)

/** Pure collapsed-row label; JVM-tested so the wording and the arg extraction stay locked. */
internal fun toolCallLabel(message: NativeChatMessage): ToolCallLabel {
    val args = message.argumentsText?.let { raw ->
        runCatching { JSONObject(raw) }.getOrNull()
    }
    return when (message.toolName) {
        "read" -> ToolCallLabel(com.fioiu8.dshpocket.R.string.native_tool_read, args?.pathArg())
        "write" -> ToolCallLabel(com.fioiu8.dshpocket.R.string.native_tool_write, args?.pathArg())
        "edit", "str_replace_editor" -> ToolCallLabel(com.fioiu8.dshpocket.R.string.native_tool_edit, args?.pathArg())
        "bash", "shell" -> ToolCallLabel(
            com.fioiu8.dshpocket.R.string.native_tool_bash,
            args?.firstString("command", "script", "description")?.lines()?.firstOrNull()?.take(90),
        )
        "glob", "grep", "list" -> ToolCallLabel(com.fioiu8.dshpocket.R.string.native_tool_search, args?.firstString("pattern", "query", "path")?.take(90))
        "fetch", "web_fetch" -> ToolCallLabel(com.fioiu8.dshpocket.R.string.native_tool_fetch, args?.firstString("url", "target", "address")?.take(90))
        "pocket_screen_control" -> screenToolLabel(args)
        "todo_write", "todo" -> ToolCallLabel(com.fioiu8.dshpocket.R.string.native_tool_todo)
        else -> ToolCallLabel(com.fioiu8.dshpocket.R.string.native_tool_generic, message.toolName)
    }
}

/** Computer Use rows read the real action out so the user sees what the AI is doing. */
private fun screenToolLabel(args: JSONObject?): ToolCallLabel {
    val action = args?.optString("action").orEmpty()
    return when (action) {
        "read", "screenshot" -> ToolCallLabel(com.fioiu8.dshpocket.R.string.native_tool_screen_read)
        "tap" -> ToolCallLabel(
            com.fioiu8.dshpocket.R.string.native_tool_screen_tap,
            "${args?.optInt("x") ?: 0}, ${args?.optInt("y") ?: 0}",
        )
        "swipe" -> ToolCallLabel(com.fioiu8.dshpocket.R.string.native_tool_screen_swipe)
        "text" -> ToolCallLabel(com.fioiu8.dshpocket.R.string.native_tool_screen_text, args?.optString("text")?.take(30))
        "key" -> ToolCallLabel(com.fioiu8.dshpocket.R.string.native_tool_screen_key, args?.optString("key"))
        "request_permission" -> ToolCallLabel(
            com.fioiu8.dshpocket.R.string.native_tool_screen_permission,
            args?.optString("permission"),
        )
        else -> ToolCallLabel(com.fioiu8.dshpocket.R.string.native_tool_generic, "Computer Use")
    }
}

internal fun toolCallIcon(name: String?): ImageVector = when (name) {
    "read" -> Icons.Filled.Description
    "write", "edit", "str_replace_editor" -> Icons.Filled.Edit
    "bash", "shell" -> Icons.Filled.Terminal
    "glob", "grep", "list" -> Icons.Filled.Search
    "fetch", "web_fetch" -> Icons.Filled.Public
    "pocket_screen_control" -> Icons.Filled.TouchApp
    "todo_write", "todo" -> Icons.Filled.Checklist
    else -> Icons.Filled.Build
}

/** Pretty-print a raw tool-arguments JSON string; falls back to the original text. */
internal fun prettyToolArguments(raw: String): String = try {
    when {
        raw.trim().startsWith("{") -> JSONObject(raw).toString(2)
        raw.trim().startsWith("[") -> org.json.JSONArray(raw).toString(2)
        else -> raw
    }
} catch (_: Throwable) {
    raw
}

/** "3" / "3.2" — the unit lives in the resource so locales can place it. */
internal fun formatSecondsValue(durationMs: Long): String {
    val seconds = durationMs / 1000.0
    return if (seconds >= 10) "%.0f".format(seconds) else "%.1f".format(seconds)
}

@Composable
private fun ToolCallCard(message: NativeChatMessage) {
    var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
    val label = toolCallLabel(message)
    val argText = label.arg
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 38.dp)
                    .clickable { expanded = !expanded },
            ) {
                Icon(
                    toolCallIcon(message.toolName),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = if (argText.isNullOrBlank()) stringResource(label.labelRes) else stringResource(label.labelRes, argText),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (message.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (message.isRunningToolCall) {
                    CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.5.dp)
                } else {
                    Icon(
                        Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(18.dp)
                            .graphicsLayer { rotationZ = if (expanded) 180f else 0f },
                    )
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(bottom = 8.dp)) {
                    message.argumentsText?.takeIf { it.isNotBlank() }?.let { args ->
                        Text(
                            text = prettyToolArguments(args),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 180.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                    message.resultText?.takeIf { it.isNotBlank() }?.let { result ->
                        Text(
                            text = result.take(4_000),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = if (message.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThinkingCard(message: NativeChatMessage, live: Boolean) {
    var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 38.dp)
                    .clickable { expanded = !expanded },
            ) {
                Icon(
                    Icons.Filled.Psychology,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = when {
                        live -> stringResource(com.fioiu8.dshpocket.R.string.native_thinking)
                        message.durationMs != null ->
                            stringResource(com.fioiu8.dshpocket.R.string.native_thought_for, formatSecondsValue(message.durationMs))
                        else -> stringResource(com.fioiu8.dshpocket.R.string.native_thought_done)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(18.dp)
                        .graphicsLayer { rotationZ = if (expanded) 180f else 0f },
                )
            }
            // While the model is still thinking, the collapsed row shows its latest
            // thought line; once finished only the duration stays visible.
            if (live && !expanded) {
                Text(
                    text = message.text.lines().lastOrNull { it.isNotBlank() }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                SelectionContainer(
                    Modifier
                        .padding(bottom = 8.dp)
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
