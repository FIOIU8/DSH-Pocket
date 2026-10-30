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
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
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
import com.fioiu8.dshpocket.data.session.SessionRepository
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
    val errorDetails: String? = null,
    val errorCode: String? = null,
    val requestId: String? = null,
    val retryable: Boolean = false,
    // Set on an assistant message re-inserted by a surface replace (user edit).
    val edited: Boolean = false,
    val turn: Int? = null,
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
    var chatError by remember { mutableStateOf<ChatErrorState?>(null) }
    var streamReconnectAttempt by remember(sessionId) { mutableStateOf(0) }
    var streamConnected by remember(sessionId) { mutableStateOf(false) }
    var showSessions by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var pickerModel by remember { mutableStateOf<NativeModelOption?>(null) }
    var modelOptions by remember { mutableStateOf<List<NativeModelOption>>(emptyList()) }
    // Display name for the composer's model chip; read from the modelSelection projection.
    var currentModelName by remember { mutableStateOf<String?>(null) }
    // Message editing (user-named behavior): a user message edit forks the session before
    // that message and re-prompts (everything after it is discarded); an assistant message
    // edit rewrites the message in place through session/editMessage.
    var editingMessageId by remember { mutableStateOf<String?>(null) }
    var editInFlight by remember { mutableStateOf(false) }
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
    val labelCreateSessionFailed = stringResource(com.fioiu8.dshpocket.R.string.native_create_session_failed)
    val labelUntitledSession = stringResource(com.fioiu8.dshpocket.R.string.home_untitled_session)
    val labelImageAttachment = stringResource(com.fioiu8.dshpocket.R.string.native_image_attachment)
    val labelFileAttachment = stringResource(com.fioiu8.dshpocket.R.string.native_file_attachment)
    val labelEditFailed = stringResource(com.fioiu8.dshpocket.R.string.native_edit_failed)
    val labelEditResend = stringResource(com.fioiu8.dshpocket.R.string.native_message_edit_resend)
    val labelEditSave = stringResource(com.fioiu8.dshpocket.R.string.native_message_edit_save)
    val labelRetry = stringResource(com.fioiu8.dshpocket.R.string.native_retry)
    val labelReconnect = stringResource(com.fioiu8.dshpocket.R.string.native_reconnect)
    val labelRegenerate = stringResource(com.fioiu8.dshpocket.R.string.native_regenerate)
    val labelCopyError = stringResource(com.fioiu8.dshpocket.R.string.native_copy_error)
    val labelErrorCopied = stringResource(com.fioiu8.dshpocket.R.string.native_error_copied)
    val labelErrorDetails = stringResource(com.fioiu8.dshpocket.R.string.native_error_details)
    val labelStreamConnected = stringResource(com.fioiu8.dshpocket.R.string.native_stream_connected)
    val labelStreamDisconnected = stringResource(com.fioiu8.dshpocket.R.string.native_stream_disconnected)
    val labelTurnFailed = stringResource(com.fioiu8.dshpocket.R.string.native_turn_failed)
    val labelSendTitle = stringResource(com.fioiu8.dshpocket.R.string.native_send_failed)
    val labelEditTitle = stringResource(com.fioiu8.dshpocket.R.string.native_edit_failed)
    val labelStopTitle = stringResource(com.fioiu8.dshpocket.R.string.native_stop_failed)
    val labelModelTitle = stringResource(com.fioiu8.dshpocket.R.string.native_switch_model_failed)
    val labelLoadingTitle = stringResource(com.fioiu8.dshpocket.R.string.native_session_load_failed)
    val labelAgent = stringResource(com.fioiu8.dshpocket.R.string.native_agent)
    val labelDraftRestored = stringResource(com.fioiu8.dshpocket.R.string.native_draft_restored)
    val labelTurnBlocked = stringResource(com.fioiu8.dshpocket.R.string.native_turn_blocked)
    val labelTurnMaxTokens = stringResource(com.fioiu8.dshpocket.R.string.native_turn_max_tokens)
    val labelTurnInterrupted = stringResource(com.fioiu8.dshpocket.R.string.native_turn_interrupted)

    fun rpcError(source: ChatErrorSource, title: String, result: RpcResult.Err, restoreDraft: Boolean = false) = ChatErrorState(
        source = source,
        title = title,
        message = result.message,
        code = result.code,
        details = result.details,
        requestId = result.requestId,
        retryable = result.retryable,
        restoreDraft = restoreDraft,
    )

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
            if (chatError?.source == ChatErrorSource.Load) chatError = null
        } else if (result is RpcResult.Err) {
            chatError = rpcError(ChatErrorSource.Load, labelSessionLoadFailed, result)
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
                chatError = rpcError(ChatErrorSource.Load, labelSessionLoadFailed, projectionResult)
                return
            }
            is RpcResult.Ok -> projectionResult.value as? JSONObject
        }
        val cursor = projection?.optLong("asOfSeq", Long.MIN_VALUE) ?: Long.MIN_VALUE
        if (cursor == Long.MIN_VALUE) {
            chatError = ChatErrorState(ChatErrorSource.Load, labelSessionLoadFailed, "response cursor missing", "protocol", "session/projections response did not include asOfSeq.", retryable = true)
            return
        }
        if (chatError?.source == ChatErrorSource.Load) chatError = null
        // The modelSelection projection rides the same response; `next` is pending ?? lastUsed.
        val selection = projection?.optJSONObject("values")?.optJSONObject("modelSelection")?.optJSONObject("next")
        currentModelName = selection?.optString("model")
            ?.takeIf(String::isNotBlank)
            ?.let { id -> modelOptions.firstOrNull { it.modelId == id }?.name ?: id }
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
                    chatError = ChatErrorState(ChatErrorSource.Load, labelSessionLoadFailed, "message records missing", "protocol", "session/page response did not include records.", retryable = true)
                    return
                }
                val parsed = parseNativeChatMessages(records, labelImageAttachment, labelFileAttachment, toolCallStore.entries(id))
                messages = parsed
                loadedCursor = cursor
                if (chatError?.source == ChatErrorSource.Load) chatError = null
            }
            is RpcResult.Err -> chatError = rpcError(ChatErrorSource.Load, labelSessionLoadFailed, pageResult)
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
        val sessionResult = chatRpc(engine.baseUrl, "session/list", EngineApi.emptyArgs().put("_request", JSONObject()), engine.token)
        when (sessionResult) {
            is RpcResult.Ok -> {
                sessions = parseNativeChatSessions(EngineApi.itemsOf(sessionResult.value), labelUntitledSession)
                if (sessionId == null) sessionId = sessions.firstOrNull()?.id
                if (chatError?.source == ChatErrorSource.Load) chatError = null
            }
            is RpcResult.Err -> chatError = rpcError(ChatErrorSource.Load, labelLoadingTitle, sessionResult)
        }
        loading = false
    }

    var reconnectRequest by remember(sessionId) { mutableLongStateOf(0L) }
    LaunchedEffect(ready, sessionId, reconnectRequest) {
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
            var failures = 0
            while (true) {
                try {
                    EngineApi.followSession(engine.baseUrl, id).collect { frame ->
                    when (frame.optString("type")) {
                        "snapshot" -> {
                            streamConnected = true
                            failures = 0
                            streamReconnectAttempt = 0
                            if (chatError?.source == ChatErrorSource.Stream) chatError = null
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
                                            callData.optInt("turn").takeIf { callData.has("turn") },
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
                    streamConnected = false
                    break
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    streamConnected = false
                    failures++
                    val wait = ChatReconnectPolicy.delayBeforeRetry(failures)
                    if (wait == null) {
                        streamReconnectAttempt = 0
                        val streamFailure = failure as? com.fioiu8.dshpocket.engine.SessionStreamException
                        chatError = ChatErrorState(
                            ChatErrorSource.Stream,
                            labelSessionLoadFailed,
                            streamFailure?.message ?: failure.message.orEmpty().ifBlank { labelSessionLoadFailed },
                            streamFailure?.code ?: "transport",
                            streamFailure?.details ?: EngineApi.safeDiagnostic("${failure.javaClass.simpleName}: ${failure.message.orEmpty()}"),
                            requestId = streamFailure?.requestId,
                            retryable = streamFailure?.let { EngineApi.isRetryableCode(it.code) } ?: true,
                        )
                        break
                    }
                    streamReconnectAttempt = failures
                    delay(wait)
                }
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
        chatError = null
        onPendingSessionConsumed()
    }

    // Outcome of a ViewModel-sent prompt: report errors, restore the draft on failure,
    // and adopt a session the ViewModel had to create.
    LaunchedEffect(promptOutcome) {
        when (val outcome = promptOutcome) {
            is PromptOutcome.Delivered -> {
                chatError = null
                val engine = ready
                if (engine != null) {
                    loadSessions(engine)
                    refreshMessages(engine, outcome.sessionId)
                }
            }
            is PromptOutcome.Failed -> {
                val failed = rpcError(ChatErrorSource.Send, labelSendTitle, RpcResult.Err(outcome.code, outcome.message, outcome.details, outcome.requestId, outcome.retryable), outcome.restoreDraft)
                    .copy(recoveryText = outcome.text)
                chatError = failed
                if (outcome.restoreDraft && draft.isBlank()) updateDraft(outcome.text)
            }
            null -> {}
        }
        if (promptOutcome is PromptOutcome.Delivered) sessionViewModel.consumeDeliveredPrompt()
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

    fun canEditMessage(message: NativeChatMessage): Boolean =
        !running && !promptInFlight && !editInFlight && sessionId != null &&
            (message.role == "user" || message.role == "assistant") &&
            message.seq >= 0L && message.id != "live-assistant"

    fun applyUserEdit(message: NativeChatMessage, newText: String) {
        val engine = ready ?: return
        val previousId = sessionId ?: return
        editInFlight = true
        scope.launch {
            try {
                // 丢弃被编辑消息之后的整段历史：从其前一个事件分叉出新会话（首条消息则
                // 直接开新会话），再把编辑后的文本作为新回合发出；旧会话随后归档，列表里
                // 只见续写的新会话。上下文即分叉日志的权威派生，不存在假修改。
                val forked = if (message.seq <= 0L) {
                    chatRpc(
                        engine.baseUrl,
                        "session/create",
                        EngineApi.requestArgs(JSONObject().put("cwd", EnginePaths.workspace(context).absolutePath)),
                        engine.token,
                    )
                } else {
                    chatRpc(
                        engine.baseUrl,
                        "session/fork",
                        EngineApi.requestArgs(
                            JSONObject().put("sessionId", previousId).put("atSeq", message.seq - 1),
                        ),
                        engine.token,
                    )
                }
                val targetId = ((forked as? RpcResult.Ok)?.value as? JSONObject)
                    ?.optString("sessionId")?.takeIf(String::isNotBlank)
                if (targetId == null) {
                    chatError = (forked as? RpcResult.Err)?.let { rpcError(ChatErrorSource.Edit, labelEditTitle, it) }
                        ?: ChatErrorState(ChatErrorSource.Edit, labelEditTitle, "no sessionId returned", "protocol", "session fork/create response did not include sessionId.", retryable = true)
                    return@launch
                }
                editingMessageId = null
                sessionId = targetId
                messages = emptyList()
                liveAssistantText = ""
                liveThinking = null
                chatError = null
                sessionViewModel.sendPrompt(
                    engine = engine,
                    text = newText,
                    sessionId = targetId,
                    workspacePath = EnginePaths.workspace(context).absolutePath,
                    restoreDraft = false,
                )
                sessionViewModel.archive(previousId)
                loadSessions(engine)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                chatError = ChatErrorState(
                    ChatErrorSource.Edit,
                    labelEditTitle,
                    EngineApi.safeDiagnostic(failure.message.orEmpty().ifBlank { labelEditFailed }),
                    "transport",
                    EngineApi.safeDiagnostic("${failure.javaClass.simpleName}: ${failure.message.orEmpty()}"),
                    retryable = true,
                )
            } finally {
                editInFlight = false
            }
        }
    }

    fun applyAssistantEdit(message: NativeChatMessage, newText: String) {
        val engine = ready ?: return
        val currentId = sessionId ?: return
        editInFlight = true
        scope.launch {
            try {
                val request = JSONObject()
                    .put("sessionId", currentId)
                    .put("atSeq", message.seq)
                    .put("content", newText)
                when (val result = chatRpc(engine.baseUrl, "session/editMessage", EngineApi.requestArgs(request), engine.token)) {
                    is RpcResult.Err -> chatError = rpcError(ChatErrorSource.Edit, labelEditTitle, result)
                    is RpcResult.Ok -> {
                        editingMessageId = null
                        chatError = null
                        refreshMessages(engine, currentId)
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                chatError = ChatErrorState(ChatErrorSource.Edit, labelEditTitle, EngineApi.safeDiagnostic(failure.message.orEmpty().ifBlank { labelEditFailed }), "transport", EngineApi.safeDiagnostic("${failure.javaClass.simpleName}: ${failure.message.orEmpty()}"), retryable = true)
            } finally {
                editInFlight = false
            }
        }
    }

    Scaffold(
        topBar = {
            DshTopAppBar(
                title = sessions.firstOrNull { it.id == sessionId }?.title ?: stringResource(com.fioiu8.dshpocket.R.string.native_new_chat),
                onMenu = onMenu,
                subtitle = when {
                    running -> stringResource(com.fioiu8.dshpocket.R.string.native_status_running)
                    promptInFlight -> stringResource(com.fioiu8.dshpocket.R.string.native_status_sending)
                    streamReconnectAttempt > 0 -> stringResource(com.fioiu8.dshpocket.R.string.native_stream_retrying_attempt, streamReconnectAttempt)
                    loading && sessionId != null -> stringResource(com.fioiu8.dshpocket.R.string.native_stream_connecting)
                    streamConnected -> labelStreamConnected
                    sessionId != null -> labelStreamDisconnected
                    else -> stringResource(com.fioiu8.dshpocket.R.string.native_status_ready)
                },
                actions = {
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
                            sessionId = null; messages = emptyList(); liveAssistantText = ""; chatError = null
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
                        is EngineState.Failed -> state.message.asString()
                        is EngineState.Busy -> state.message.asString()
                        else -> stringResource(com.fioiu8.dshpocket.R.string.engine_preparing)
                    },
                    busy = state !is EngineState.Failed,
                )
                else -> {
                    chatError?.let { failure ->
                        val retryAction: (() -> Unit)? = when (failure.source) {
                            ChatErrorSource.Load -> ({
                                scope.launch {
                                    loadSessions(ready)
                                    sessionId?.let { refreshMessages(ready, it) }
                                }
                            })
                            ChatErrorSource.Stream -> ({ reconnectRequest++ })
                            ChatErrorSource.Send -> ({
                                if (draft == failure.recoveryText) updateDraft("")
                                sessionViewModel.retryPrompt(ready)
                            })
                            else -> null
                        }
                        val actionLabel = when (failure.source) {
                            ChatErrorSource.Load -> labelRetry
                            ChatErrorSource.Stream -> labelReconnect
                            ChatErrorSource.Send -> labelRetry
                            else -> null
                        }
                        NativeChatErrorBanner(
                            error = failure,
                            actionLabel = actionLabel,
                            onAction = retryAction,
                            copyLabel = labelCopyError,
                            copiedLabel = labelErrorCopied,
                            detailsLabel = labelErrorDetails,
                        )
                    }
                    when {
                        loading && visibleMessages.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth()) {
                            CenteredMessage(stringResource(com.fioiu8.dshpocket.R.string.native_sessions_loading), "", busy = true)
                        }
                        sessionId == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(stringResource(com.fioiu8.dshpocket.R.string.native_start_message), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        else -> {
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
                                when {
                                    message.role == "tool_call" -> ToolCallCard(message)
                                    message.role == "thinking" -> ThinkingCard(message, live = message.id == "live-thinking")
                                    message.role == "error" -> NativeErrorTimelineNode(
                                        message = message,
                                        turnFailedLabel = labelTurnFailed,
                                        blockedLabel = labelTurnBlocked,
                                        maxTokensLabel = labelTurnMaxTokens,
                                        interruptedLabel = labelTurnInterrupted,
                                        copyLabel = labelCopyError,
                                        detailsLabel = labelErrorDetails,
                                        regenerateLabel = labelRegenerate,
                                        onRegenerate = {
                                            val failedUserMessage = messages.lastOrNull { it.role == "user" && it.turn == message.turn }
                                            if (message.retryable && failedUserMessage != null && !promptInFlight && !running) {
                                                sessionViewModel.sendPrompt(
                                                    engine = ready,
                                                    text = failedUserMessage.text,
                                                    sessionId = sessionId,
                                                    workspacePath = EnginePaths.workspace(context).absolutePath,
                                                )
                                            }
                                        },
                                    )
                                    editingMessageId == message.id -> MessageEditField(
                                        initial = message.text,
                                        confirmLabel = if (message.role == "user") labelEditResend else labelEditSave,
                                        busy = editInFlight,
                                        onConfirm = { text ->
                                            if (message.role == "user") applyUserEdit(message, text) else applyAssistantEdit(message, text)
                                        },
                                        onCancel = { editingMessageId = null },
                                    )
                                    else -> NativeMessageBubble(
                                        message = message,
                                        style = style,
                                        preferences = preferences,
                                        agentLabel = labelAgent,
                                        onAssist = if (preferences.showAssistReply && message.role == "assistant") {
                                        {
                                            // Same ViewModel path as the composer so the
                                            // assist prompt survives a rotation too, and so
                                            // it follows the app language instead of being
                                            // hardcoded Chinese.
                                            if (!promptInFlight && ready != null) {
                                                chatError = null
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
                                        onEdit = if (canEditMessage(message)) { { editingMessageId = message.id } } else null,
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
                    }

                    ChatComposer(
                        value = draft,
                        suggestions = suggestions,
                        modelName = currentModelName,
                        onValueChange = { updateDraft(it) },
                        // An in-flight edit (fork + re-prompt) blocks composer input too.
                        sending = promptInFlight || editInFlight,
                        running = running,
                        statusText = if (chatError?.source == ChatErrorSource.Send && chatError?.restoreDraft == true) labelDraftRestored else null,
                        // ready is smart-cast non-null inside this branch.
                        enabled = true,
                        onPickModel = { showModelPicker = true; pickerModel = null },
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
                                    if (result is RpcResult.Err) chatError = rpcError(ChatErrorSource.Stop, labelStopTitle, result)
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
                                val failedPrompt = chatError?.takeIf {
                                    it.source == ChatErrorSource.Send && it.recoveryText == text
                                }
                                chatError = null
                                if (failedPrompt != null) {
                                    sessionViewModel.retryPrompt(engine)
                                } else {
                                    sessionViewModel.sendPrompt(
                                        engine = engine,
                                        text = text,
                                        sessionId = sessionId,
                                        workspacePath = EnginePaths.workspace(context).absolutePath,
                                    )
                                }
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
                                chatError = null
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
                                                chatError = (created as? RpcResult.Err)?.let { rpcError(ChatErrorSource.Model, labelModelTitle, it) }
                                                    ?: ChatErrorState(ChatErrorSource.Model, labelCreateSessionFailed, "session/create returned no sessionId", "protocol", "session/create response did not include sessionId.", retryable = true)
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
                                            is RpcResult.Err -> chatError = rpcError(ChatErrorSource.Model, labelModelTitle, result)
                                            is RpcResult.Ok -> {
                                                chatError = null
                                                currentModelName = option.name
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
    agentLabel: String,
    onAssist: (() -> Unit)?,
    onEdit: (() -> Unit)? = null,
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
                Row(
                    modifier = Modifier.padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Text(agentLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
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
            MessageMetadata(message, preferences, onAssist, onEdit) {
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
    onEdit: (() -> Unit)?,
    onCopy: () -> Unit,
) {
    val fromUser = message.role == "user"
    // Read the locale observably. Locale.getDefault() would not recompose when the user
    // switches the app language, leaving numbers formatted in the old locale.
    val locale = LocalConfiguration.current.locales[0]
    val editedLabel = stringResource(com.fioiu8.dshpocket.R.string.native_message_edited)
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
        if (message.edited) add(editedLabel)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        if (onEdit != null) {
            IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Edit, contentDescription = stringResource(com.fioiu8.dshpocket.R.string.native_message_edit), modifier = Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
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
private fun NativeChatErrorBanner(
    error: ChatErrorState,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    copyLabel: String,
    copiedLabel: String,
    detailsLabel: String,
) {
    var expanded by remember(error) { mutableStateOf(false) }
    var copied by remember(error) { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copyText = buildString {
        append(error.title).append('\n').append(error.message)
        append("\ncode: ").append(error.code)
        error.requestId?.let { append("\nrequestId: ").append(it) }
        error.details.takeIf(String::isNotBlank)?.let { append("\n\n").append(it) }
    }
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = DshSpacing.page, vertical = 6.dp),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Column(Modifier.weight(1f)) {
                    Text(error.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onErrorContainer)
                    Text(error.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    Text(error.code, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                }
                IconButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("error", copyText)))
                            copied = true
                        }
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = copyLabel, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
                }
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.ExpandMore,
                        contentDescription = detailsLabel,
                        modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = if (expanded) 180f else 0f },
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            if (copied) Text(copiedLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer)
            if (onAction != null && actionLabel != null) {
                TextButton(onClick = onAction, modifier = Modifier.align(Alignment.End)) { Text(actionLabel) }
            }
            AnimatedVisibility(visible = expanded) {
                Text(
                    text = buildString {
                        error.requestId?.let { append("requestId: ").append(it).append('\n') }
                        append(error.details.ifBlank { error.message })
                    },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp).heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

@Composable
private fun NativeErrorTimelineNode(
    message: NativeChatMessage,
    turnFailedLabel: String,
    blockedLabel: String,
    maxTokensLabel: String,
    interruptedLabel: String,
    copyLabel: String,
    detailsLabel: String,
    regenerateLabel: String,
    onRegenerate: (() -> Unit)?,
) {
    var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
    var copied by remember(message.id) { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copyText = buildString {
        append(message.errorCode.orEmpty()).append('\n').append(message.text)
        message.requestId?.let { append("\nrequestId: ").append(it) }
        message.errorDetails?.let { append("\n\n").append(it) }
    }
    val reasonTitle = when (message.errorCode) {
        "turn/blocked" -> blockedLabel
        "turn/max-tokens" -> maxTokensLabel
        "turn/interrupted" -> interruptedLabel
        else -> turnFailedLabel
    }
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Column(Modifier.weight(1f)) {
                    Text(reasonTitle, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                    Text(message.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    message.errorCode?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer) }
                }
                IconButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("turn-error", copyText)))
                            copied = true
                        }
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = copyLabel, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.ExpandMore, contentDescription = detailsLabel, modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = if (expanded) 180f else 0f })
                }
            }
            if (copied) Text(stringResource(com.fioiu8.dshpocket.R.string.native_error_copied), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer)
            if (message.retryable && onRegenerate != null) {
                TextButton(onClick = onRegenerate, modifier = Modifier.align(Alignment.End)) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(regenerateLabel)
                }
            }
            AnimatedVisibility(visible = expanded) {
                Text(
                    text = buildString {
                        append(message.errorDetails.orEmpty())
                        message.requestId?.let { append("\nrequestId: ").append(it) }
                    },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

@Composable
private fun ChatComposer(
    value: String,
    suggestions: List<String>,
    modelName: String?,
    onValueChange: (String) -> Unit,
    sending: Boolean,
    running: Boolean,
    statusText: String?,
    enabled: Boolean,
    onCancel: () -> Unit,
    onPickModel: () -> Unit,
    onSend: () -> Unit,
) {
    Column {
        statusText?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp),
            )
        }
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
                    // Model switch lives here, where the conversation happens; the picker
                    // dialog keeps its two-step model → effort flow.
                    TextButton(
                        onClick = onPickModel,
                        modifier = Modifier.widthIn(max = 240.dp),
                    ) {
                        Icon(
                            Icons.Filled.Tune,
                            contentDescription = stringResource(com.fioiu8.dshpocket.R.string.native_select_model_effort),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            modelName ?: stringResource(com.fioiu8.dshpocket.R.string.native_select_model),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            Icons.Filled.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.weight(1f))
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

/** Inline message editing: a multiline field plus cancel/confirm, rendered in the bubble's place. */
@Composable
private fun MessageEditField(
    initial: String,
    confirmLabel: String,
    busy: Boolean,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var text by rememberSaveable(initial) { mutableStateOf(initial) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                enabled = !busy,
                minLines = 2,
                maxLines = 10,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
                TextButton(onClick = onCancel, enabled = !busy) {
                    Text(stringResource(com.fioiu8.dshpocket.R.string.close))
                }
                TextButton(onClick = { onConfirm(text.trim()) }, enabled = !busy && text.isNotBlank()) {
                    Text(confirmLabel)
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
    val turnsWithUsers = buildSet {
        for (index in 0 until records.length()) {
            val event = records.optJSONObject(index)?.optJSONObject("event") ?: continue
            if (event.optString("type") == "user/message") {
                val data = event.optJSONObject("data") ?: continue
                if (data.has("turn")) add(data.optInt("turn"))
            }
        }
    }
    val turnsWithTools = buildSet {
        for (index in 0 until records.length()) {
            val event = records.optJSONObject(index)?.optJSONObject("event") ?: continue
            if (event.optString("type") == "tool/call") {
                event.optJSONObject("data")?.optInt("turn")?.let(::add)
            }
        }
        toolCallOverlay.mapNotNullTo(this) { it.turn }
    }
    val hasUnattributedToolCalls = toolCallOverlay.any { it.turn == null }
    // callId -> index of the tool_call row still waiting for its result.
    val pendingCalls = HashMap<String, Int>()
    data class ToolFailure(val details: String?, val code: String?, val requestId: String?)
    // Results whose tool/call is not in the surface page (it lives in the overlay).
    val orphanResults = LinkedHashMap<String, Triple<String, Boolean, ToolFailure?>>()
    // True once this step's thinking already came from an `assistant/attempt` event, so
    // the assistant message's own stream must not produce a second, identical card.
    var attemptThinkingThisStep = false
    // The pocket edit flow shadows an assistant message with a sentinel developer note
    // (a surface replace); the appended assistant message right after it is the edited one.
    var lastEventWasEditShadow = false
    for (index in 0 until records.length()) {
        val event = records.optJSONObject(index)?.optJSONObject("event") ?: continue
        val type = event.optString("type")
        val data = event.optJSONObject("data") ?: continue
        val seq = event.optLong("seq")
        if (type == "turn/end") {
            val reason = data.optJSONObject("reason") ?: continue
            val kind = reason.optString("kind")
            if (kind in setOf("error", "blocked", "max-tokens", "interrupted")) {
                val failure = reason.optJSONObject("error")
                val code = when (kind) {
                    "error" -> failure?.optString("code")?.takeIf(String::isNotBlank) ?: "turn/error"
                    else -> "turn/$kind"
                }
                val message = failure?.optString("message")?.takeIf(String::isNotBlank)
                    ?: reason.optString("message").takeIf(String::isNotBlank)
                    ?: kind
                val safeMessage = EngineApi.safeDiagnostic(message)
                val turn = data.optInt("turn").takeIf { data.has("turn") }
                val turnNumber = turn ?: 0
                val hasTools = turn != null && turn in turnsWithTools
                val requestId = failure?.optString("requestId")?.takeIf { it.isNotBlank() && it != "null" }
                    ?: data.optString("requestId").takeIf { it.isNotBlank() && it != "null" }
                val detail = buildString {
                    append("turn: ").append(turnNumber)
                    append("\nreason: ").append(kind)
                    failure?.optString("code")?.takeIf(String::isNotBlank)?.let { append("\ncode: ").append(it) }
                    append("\nmessage: ").append(safeMessage)
                    failure?.optInt("status")?.takeIf { failure.has("status") }?.let { append("\nstatus: ").append(it) }
                    requestId?.let { append("\nrequestId: ").append(it) }
                    append("\ntoolCalls: ").append(if (hasTools) "present" else "none")
                }
                messages.add(
                    NativeChatMessage(
                        id = "turn-error-$seq",
                        role = "error",
                        text = safeMessage,
                        time = event.optLong("time"),
                        seq = seq,
                        errorDetails = com.fioiu8.dshpocket.engine.EngineApi.safeDiagnostic(detail),
                        errorCode = code,
                        requestId = requestId,
                        retryable = kind == "error" && turn != null && turn in turnsWithUsers && !hasUnattributedToolCalls && canRegenerateTurn(hasTools),
                        turn = turn,
                    ),
                )
            }
            continue
        }
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
                    turn = data.optInt("turn").takeIf { data.has("turn") },
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
            val structuredError = data.optJSONObject("error")
            val rawError = data.opt("error")?.takeUnless { it == JSONObject.NULL }
            val isError = message?.optBoolean("isError", false) == true || rawError != null
            val errorDetails = structuredError?.let { error ->
                buildList {
                    error.optString("code").takeIf(String::isNotBlank)?.let { add("code: $it") }
                    error.optString("message").takeIf(String::isNotBlank)?.let { add("message: $it") }
                    error.optString("reason").takeIf(String::isNotBlank)?.let { add("reason: $it") }
                    error.optString("requestId").takeIf(String::isNotBlank)?.let { add("requestId: $it") }
                }.joinToString("\n").ifBlank { null }
            } ?: rawError?.toString()?.takeIf { isError }
            val errorCode = structuredError?.optString("code")?.takeIf(String::isNotBlank)
            val requestId = structuredError?.optString("requestId")?.takeIf(String::isNotBlank)
            val safeErrorDetails = errorDetails?.let(EngineApi::safeDiagnostic)
            val safeResult = if (isError) EngineApi.safeDiagnostic(result) else result
            val toolFailure = if (isError) ToolFailure(safeErrorDetails, errorCode, requestId) else null
            val target = pendingCalls.remove(callId)
            if (target != null && target < messages.size) {
                val call = messages[target]
                if (call.callId == callId) {
                    messages[target] = call.copy(resultText = safeResult, isError = isError, errorDetails = safeErrorDetails, errorCode = errorCode, requestId = requestId)
                    continue
                }
            }
            if (callId.isNotBlank()) orphanResults[callId] = Triple(safeResult, isError, toolFailure)
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
        // The edit sentinel (developer note replacing a shadowed assistant message) never
        // renders; it only marks the appended assistant message as the edited one.
        val shadowing = event.optJSONObject("surfaceOp")?.optString("op") == "replace"
        if (type == "developer/message" && shadowing) {
            lastEventWasEditShadow = true
            continue
        }
        val editedMessage = type == "assistant/message" && lastEventWasEditShadow
        lastEventWasEditShadow = false
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
                edited = editedMessage,
                turn = data.optInt("turn").takeIf { data.has("turn") },
            ),
        )
    }
    if (toolCallOverlay.isEmpty()) return messages.sortedBy { it.seq }
    // The overlay carries the tool/call events `session/page` never serves. But the
    // follow snapshot's records DO include raw events on some paths — skip any call the
    // parsed history already produced, or the row would appear twice under one key.
    val existingCallIds = messages.mapNotNull { it.callId }.toSet()
    val overlayRows = toolCallOverlay
        .filter { it.callId !in existingCallIds }
        .map { entry ->
            val orphan = orphanResults[entry.callId]
            NativeChatMessage(
                id = "tool-call-${entry.seq}",
                role = "tool_call",
                text = "",
                time = 0L,
                seq = entry.seq,
                toolName = entry.name,
                callId = entry.callId,
                argumentsText = entry.arguments,
                resultText = orphan?.first,
                isError = orphan?.second == true,
                errorDetails = orphan?.third?.details,
                errorCode = orphan?.third?.code,
                requestId = orphan?.third?.requestId,
                turn = entry.turn,
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
    // A surface replace shadows older nodes out of the model-visible history; drop the
    // messages it cites so the live list matches what the next refresh will return.
    var base = existing
    if (event.optJSONObject("surfaceOp")?.optString("op") == "replace") {
        val sources = event.optJSONArray("sourceEventSeqs")
        if (sources != null && sources.length() > 0) {
            val shadowed = HashSet<Long>()
            for (index in 0 until sources.length()) shadowed.add(sources.optLong(index))
            base = base.filterNot { it.seq in shadowed }
        }
    }
    if (type == "tool/result") {
        val message = data.optJSONObject("message")
        val callId = message?.optString("toolCallId").orEmpty()
        if (callId.isBlank()) return existing
        val result = toolResultText(data, imagePlaceholder, filePlaceholder)
        val structuredError = data.optJSONObject("error")
        val rawError = data.opt("error")?.takeUnless { it == JSONObject.NULL }
        val isError = message?.optBoolean("isError", false) == true || rawError != null
        val errorDetails = structuredError?.let { error ->
            buildList {
                error.optString("code").takeIf(String::isNotBlank)?.let { add("code: $it") }
                error.optString("message").takeIf(String::isNotBlank)?.let { add("message: $it") }
                error.optString("reason").takeIf(String::isNotBlank)?.let { add("reason: $it") }
                error.optString("requestId").takeIf(String::isNotBlank)?.let { add("requestId: $it") }
            }.joinToString("\n").ifBlank { null }
        } ?: rawError?.toString()?.takeIf { isError }
        val errorCode = structuredError?.optString("code")?.takeIf(String::isNotBlank)
        val requestId = structuredError?.optString("requestId")?.takeIf(String::isNotBlank)
        val index = existing.indexOfLast { it.role == "tool_call" && it.callId == callId && it.resultText == null }
        if (index < 0) return existing
        val safeResult = if (isError) EngineApi.safeDiagnostic(result) else result
        val safeErrorDetails = errorDetails?.let(EngineApi::safeDiagnostic)
        return existing.mapIndexed { i, item -> if (i == index) item.copy(resultText = safeResult, isError = isError, errorDetails = safeErrorDetails, errorCode = errorCode, requestId = requestId) else item }
    }
    val records = JSONArray().put(JSONObject().put("event", event))
    var incoming = parseNativeChatMessages(records, imagePlaceholder, filePlaceholder)
    if (type == "turn/end") {
        val turn = data.optInt("turn").takeIf { data.has("turn") }
        val hadTools = turn != null && existing.any { it.role == "tool_call" && it.turn == turn }
        incoming = incoming.map { message ->
            if (message.role == "error") message.copy(retryable = message.retryable && canRegenerateTurn(hadTools)) else message
        }
    }
    if (incoming.isEmpty()) return base
    return (base + incoming).distinctBy { it.id }.sortedBy { it.seq }
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
                    if (message.isError) Icons.Filled.ErrorOutline else toolCallIcon(message.toolName),
                    contentDescription = null,
                    tint = if (message.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
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
                    if (message.isError) {
                        Text(
                            stringResource(com.fioiu8.dshpocket.R.string.native_tool_failed),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
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
                    message.errorDetails?.takeIf { it.isNotBlank() }?.let { details ->
                        Text(
                            text = details,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
                        )
                    }
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
