package com.fioiu8.dshpocket.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fioiu8.dshpocket.data.chat.ChatReconnectPolicy
import com.fioiu8.dshpocket.data.chat.LiveThinking
import com.fioiu8.dshpocket.data.chat.NativeChatMessage
import com.fioiu8.dshpocket.data.chat.NativeChatSession
import com.fioiu8.dshpocket.data.chat.NativeChatToolCallStore
import com.fioiu8.dshpocket.data.chat.NativeModelOption
import com.fioiu8.dshpocket.data.chat.applySessionEvent
import com.fioiu8.dshpocket.data.chat.assistantStreamSnapshotText
import com.fioiu8.dshpocket.data.chat.chatRpc
import com.fioiu8.dshpocket.data.chat.latestNativeSuggestions
import com.fioiu8.dshpocket.data.chat.parseNativeChatMessages
import com.fioiu8.dshpocket.data.chat.parseNativeChatSessions
import com.fioiu8.dshpocket.data.chat.parseNativeModelCatalog
import com.fioiu8.dshpocket.data.chat.streamReasoningText
import com.fioiu8.dshpocket.data.chat.visibleNativeMessages
import com.fioiu8.dshpocket.data.session.SessionRepository
import com.fioiu8.dshpocket.engine.EngineApi
import com.fioiu8.dshpocket.engine.EngineApi.RpcResult
import com.fioiu8.dshpocket.engine.EnginePaths
import com.fioiu8.dshpocket.engine.EngineState
import com.fioiu8.dshpocket.overlay.ThoughtStreamBus
import com.fioiu8.dshpocket.ui.state.PromptOutcome
import com.fioiu8.dshpocket.ui.state.SessionViewModel
import com.fioiu8.dshpocket.ui.state.SessionViewModelFactory
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Draft slot for a conversation that has not yet been created. */
internal const val DRAFT_NEW_SLOT = "new"

/**
 * Composition-scoped state and actions. Sending remains in SessionViewModel;
 * sessionId stays saveable; connection/cursor slots keep their original keys.
 */
internal class NativeChatState(
    private val sessionsState: MutableState<List<NativeChatSession>>,
    private val sessionIdState: MutableState<String?>,
    private val runningState: MutableState<Boolean>,
    private val liveAssistantTextState: MutableState<String>,
    private val liveThinkingState: MutableState<LiveThinking?>,
    private val loadingState: MutableState<Boolean>,
    private val chatErrorState: MutableState<ChatErrorState?>,
    private val streamReconnectAttemptState: MutableState<Int>,
    private val streamConnectedState: MutableState<Boolean>,
    private val showSessionsState: MutableState<Boolean>,
    private val showModelPickerState: MutableState<Boolean>,
    private val pickerModelState: MutableState<NativeModelOption?>,
    private val modelOptionsState: MutableState<List<NativeModelOption>>,
    private val currentModelNameState: MutableState<String?>,
    private val editingMessageIdState: MutableState<String?>,
    private val editInFlightState: MutableState<Boolean>,
    private val styleState: MutableState<NativeChatStyle>,
    val ready: EngineState.Ready?,
    val promptInFlight: Boolean,
    val visibleMessages: List<NativeChatMessage>,
    val suggestions: List<String>,
    val draft: String,
    val labels: NativeChatLabels,
    val updateDraft: (String) -> Unit,
    val canEditMessage: (NativeChatMessage) -> Boolean,
    val applyUserEdit: (NativeChatMessage, String) -> Unit,
    val applyAssistantEdit: (NativeChatMessage, String) -> Unit,
    val regenerate: (NativeChatMessage) -> Unit,
    val cancelTurn: () -> Unit,
    val sendDraft: () -> Unit,
    val startNewChat: () -> Unit,
    val openSession: (NativeChatSession) -> Unit,
    val selectModel: (NativeModelOption, String) -> Unit,
    val assistReply: () -> Unit,
    val retryFailure: (ChatErrorState) -> Unit,
) {
    val sessions by sessionsState
    val sessionId by sessionIdState
    val running by runningState
    val liveAssistantText by liveAssistantTextState
    val liveThinking by liveThinkingState
    val loading by loadingState
    val chatError by chatErrorState
    val streamReconnectAttempt by streamReconnectAttemptState
    val streamConnected by streamConnectedState
    var showSessions by showSessionsState
    var showModelPicker by showModelPickerState
    var pickerModel by pickerModelState
    val modelOptions by modelOptionsState
    val currentModelName by currentModelNameState
    var editingMessageId by editingMessageIdState
    val editInFlight by editInFlightState
    val style by styleState
}

@Composable
internal fun rememberNativeChatState(
    state: EngineState,
    repository: SessionRepository,
    pendingOpenSessionId: String?,
    onPendingSessionConsumed: () -> Unit,
    initialSessionId: String?,
    onSessionIdChanged: (String?) -> Unit,
    chatDrafts: Map<String, String>,
    onChatDraftChange: (String, String) -> Unit,
): NativeChatState {
    val context = LocalContext.current
    val ready = state as? EngineState.Ready
    val darkTheme = isSystemInDarkTheme()
    val scope = rememberCoroutineScope()
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
    val sessionsState = remember { mutableStateOf<List<NativeChatSession>>(emptyList()) }
    var sessions by sessionsState
    // Seeded from NavigationMemory so an exit-reopen returns to the same conversation.
    val sessionIdState = rememberSaveable { mutableStateOf<String?>(initialSessionId) }
    var sessionId by sessionIdState
    LaunchedEffect(sessionId) { onSessionIdChanged(sessionId) }
    val messagesState = remember { mutableStateOf<List<NativeChatMessage>>(emptyList()) }
    var messages by messagesState
    // Composer drafts live per conversation slot ("new" for a fresh chat), so typing in
    // one conversation never leaks into another and a new chat auto-fills its slot.
    val draft = chatDrafts[sessionId ?: DRAFT_NEW_SLOT].orEmpty()
    fun updateDraft(text: String) = onChatDraftChange(sessionId ?: DRAFT_NEW_SLOT, text)
    val runningState = remember { mutableStateOf(false) }
    var running by runningState
    val liveAssistantTextState = remember { mutableStateOf("") }
    var liveAssistantText by liveAssistantTextState
    val liveThinkingState = remember { mutableStateOf<LiveThinking?>(null) }
    var liveThinking by liveThinkingState
    val loadingState = remember { mutableStateOf(false) }
    var loading by loadingState
    val chatErrorState = remember { mutableStateOf<ChatErrorState?>(null) }
    var chatError by chatErrorState
    val streamReconnectAttemptState = remember(sessionId) { mutableStateOf(0) }
    var streamReconnectAttempt by streamReconnectAttemptState
    val streamConnectedState = remember(sessionId) { mutableStateOf(false) }
    var streamConnected by streamConnectedState
    val showSessionsState = remember { mutableStateOf(false) }
    var showSessions by showSessionsState
    val showModelPickerState = remember { mutableStateOf(false) }
    var showModelPicker by showModelPickerState
    val pickerModelState = remember { mutableStateOf<NativeModelOption?>(null) }
    var pickerModel by pickerModelState
    val modelOptionsState = remember { mutableStateOf<List<NativeModelOption>>(emptyList()) }
    var modelOptions by modelOptionsState
    // Display name for the composer's model chip; read from the modelSelection projection.
    val currentModelNameState = remember { mutableStateOf<String?>(null) }
    var currentModelName by currentModelNameState
    // Message editing (user-named behavior): a user message edit forks the session before
    // that message and re-prompts (everything after it is discarded); an assistant message
    // edit rewrites the message in place through session/editMessage.
    val editingMessageIdState = remember { mutableStateOf<String?>(null) }
    var editingMessageId by editingMessageIdState
    val editInFlightState = remember { mutableStateOf(false) }
    var editInFlight by editInFlightState
    val loadedCursorState = remember(sessionId) { mutableLongStateOf(-2L) }
    var loadedCursor by loadedCursorState
    val styleState = remember(darkTheme) { mutableStateOf(defaultNativeChatStyle(darkTheme)) }
    var style by styleState
    // `session/page` never serves tool/call events; this store keeps the ones the live
    // stream delivered so history renders the rows after every refresh and relaunch.
    val toolCallStore = remember { NativeChatToolCallStore(context) }
    val visibleMessages = remember(messages, liveAssistantText, liveThinking) {
        visibleNativeMessages(messages, liveAssistantText, liveThinking)
    }
    val suggestions = remember(messages) { latestNativeSuggestions(messages) }

    val labels = nativeChatLabels()

    suspend fun loadSessions(engine: EngineState.Ready) {
        val result = chatRpc(
            engine.baseUrl,
            "session/list",
            EngineApi.emptyArgs().put("_request", JSONObject()),
            engine.token,
        )
        if (result is RpcResult.Ok) {
            sessions = parseNativeChatSessions(EngineApi.itemsOf(result.value), labels.labelUntitledSession)
            if (sessionId == null) sessionId = sessions.firstOrNull()?.id
            if (chatError?.source == ChatErrorSource.Load) chatError = null
        } else if (result is RpcResult.Err) {
            chatError = rpcError(ChatErrorSource.Load, labels.labelSessionLoadFailed, result)
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
                chatError = rpcError(ChatErrorSource.Load, labels.labelSessionLoadFailed, projectionResult)
                return
            }
            is RpcResult.Ok -> projectionResult.value as? JSONObject
        }
        val cursor = projection?.optLong("asOfSeq", Long.MIN_VALUE) ?: Long.MIN_VALUE
        if (cursor == Long.MIN_VALUE) {
            chatError = ChatErrorState(ChatErrorSource.Load, labels.labelSessionLoadFailed, "response cursor missing", "protocol", "session/projections response did not include asOfSeq.", retryable = true)
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
                    chatError = ChatErrorState(ChatErrorSource.Load, labels.labelSessionLoadFailed, "message records missing", "protocol", "session/page response did not include records.", retryable = true)
                    return
                }
                val parsed = parseNativeChatMessages(records, labels.labelImageAttachment, labels.labelFileAttachment, toolCallStore.entries(id))
                messages = parsed
                loadedCursor = cursor
                if (chatError?.source == ChatErrorSource.Load) chatError = null
            }
            is RpcResult.Err -> chatError = rpcError(ChatErrorSource.Load, labels.labelSessionLoadFailed, pageResult)
        }
    }

    LaunchedEffect(ready) {
        val engine = ready ?: return@LaunchedEffect
        val catalog = chatRpc(engine.baseUrl, "session/modelCatalog", EngineApi.emptyArgs(), engine.token)
        if (catalog is RpcResult.Ok) modelOptions = parseNativeModelCatalog(
            catalog.value as? JSONObject,
            labels.labelEffortDefault,
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
                sessions = parseNativeChatSessions(EngineApi.itemsOf(sessionResult.value), labels.labelUntitledSession)
                if (sessionId == null) sessionId = sessions.firstOrNull()?.id
                if (chatError?.source == ChatErrorSource.Load) chatError = null
            }
            is RpcResult.Err -> chatError = rpcError(ChatErrorSource.Load, labels.labelLoadingTitle, sessionResult)
        }
        loading = false
    }

    val reconnectRequestState = remember(sessionId) { mutableLongStateOf(0L) }
    var reconnectRequest by reconnectRequestState
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
                                labels.labelImageAttachment,
                                labels.labelFileAttachment,
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
                            messages = applySessionEvent(messages, event, labels.labelImageAttachment, labels.labelFileAttachment)
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
                            labels.labelSessionLoadFailed,
                            streamFailure?.message ?: failure.message.orEmpty().ifBlank { labels.labelSessionLoadFailed },
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
                val failed = rpcError(ChatErrorSource.Send, labels.labelSendTitle, RpcResult.Err(outcome.code, outcome.message, outcome.details, outcome.requestId, outcome.retryable), outcome.restoreDraft)
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
                    chatError = (forked as? RpcResult.Err)?.let { rpcError(ChatErrorSource.Edit, labels.labelEditTitle, it) }
                        ?: ChatErrorState(ChatErrorSource.Edit, labels.labelEditTitle, "no sessionId returned", "protocol", "session fork/create response did not include sessionId.", retryable = true)
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
                    labels.labelEditTitle,
                    EngineApi.safeDiagnostic(failure.message.orEmpty().ifBlank { labels.labelEditFailed }),
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
                    is RpcResult.Err -> chatError = rpcError(ChatErrorSource.Edit, labels.labelEditTitle, result)
                    is RpcResult.Ok -> {
                        editingMessageId = null
                        chatError = null
                        refreshMessages(engine, currentId)
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                chatError = ChatErrorState(ChatErrorSource.Edit, labels.labelEditTitle, EngineApi.safeDiagnostic(failure.message.orEmpty().ifBlank { labels.labelEditFailed }), "transport", EngineApi.safeDiagnostic("${failure.javaClass.simpleName}: ${failure.message.orEmpty()}"), retryable = true)
            } finally {
                editInFlight = false
            }
        }
    }

    fun regenerate(message: NativeChatMessage) {
        val engine = ready ?: return
        val failedUserMessage = messages.lastOrNull { it.role == "user" && it.turn == message.turn }
        if (message.retryable && failedUserMessage != null && !promptInFlight && !running) {
            sessionViewModel.sendPrompt(
                engine = engine,
                text = failedUserMessage.text,
                sessionId = sessionId,
                workspacePath = EnginePaths.workspace(context).absolutePath,
            )
        }
    }

    fun cancelTurn() {
        val id = sessionId
        val engine = ready ?: return
        if (id != null) {
            scope.launch {
                val result = chatRpc(
                    engine.baseUrl,
                    "session/cancel",
                    EngineApi.requestArgs(JSONObject().put("sessionId", id)),
                    engine.token,
                )
                if (result is RpcResult.Err) chatError = rpcError(ChatErrorSource.Stop, labels.labelStopTitle, result)
                loadSessions(engine)
                refreshMessages(engine, id)
            }
        }
    }

    fun sendDraft() {
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
    }

    fun startNewChat() {
        // 用户命名行为：输入框有内容时新建会话，内容保留给新会话槽，
        // 下次新建会话自动填充；当前会话槽清空，避免回切时残留。
        val currentSlot = sessionId ?: DRAFT_NEW_SLOT
        if (currentSlot != DRAFT_NEW_SLOT && draft.isNotBlank()) {
            onChatDraftChange(DRAFT_NEW_SLOT, draft)
            onChatDraftChange(currentSlot, "")
        }
        sessionId = null; messages = emptyList(); liveAssistantText = ""; chatError = null
    }

    fun openSession(session: NativeChatSession) {
        showSessions = false
        sessionId = session.id
        messages = emptyList()
        liveAssistantText = ""
        loading = true
        chatError = null
    }

    fun selectModel(option: NativeModelOption, effortId: String) {
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
                    chatError = (created as? RpcResult.Err)?.let { rpcError(ChatErrorSource.Model, labels.labelModelTitle, it) }
                        ?: ChatErrorState(ChatErrorSource.Model, labels.labelCreateSessionFailed, "session/create returned no sessionId", "protocol", "session/create response did not include sessionId.", retryable = true)
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
                is RpcResult.Err -> chatError = rpcError(ChatErrorSource.Model, labels.labelModelTitle, result)
                is RpcResult.Ok -> {
                    chatError = null
                    currentModelName = option.name
                    showModelPicker = false
                    pickerModel = null
                }
            }
        }
    }

    fun assistReply() {
        // Same ViewModel path as the composer so the
        // assist prompt survives a rotation too, and so
        // it follows the app language instead of being
        // hardcoded Chinese.
        if (!promptInFlight && ready != null) {
            chatError = null
            sessionViewModel.sendPrompt(
                engine = ready,
                text = labels.labelAssistPrompt,
                sessionId = sessionId,
                workspacePath = EnginePaths.workspace(context).absolutePath,
                restoreDraft = false,
            )
        }
    }

    fun retryFailure(failure: ChatErrorState) {
        val engine = ready ?: return
        when (failure.source) {
            ChatErrorSource.Load -> scope.launch {
                loadSessions(engine)
                sessionId?.let { refreshMessages(engine, it) }
            }
            ChatErrorSource.Stream -> reconnectRequest++
            ChatErrorSource.Send -> {
                if (draft == failure.recoveryText) updateDraft("")
                sessionViewModel.retryPrompt(engine)
            }
            else -> Unit
        }
    }

    return NativeChatState(
        sessionsState = sessionsState,
        sessionIdState = sessionIdState,
        runningState = runningState,
        liveAssistantTextState = liveAssistantTextState,
        liveThinkingState = liveThinkingState,
        loadingState = loadingState,
        chatErrorState = chatErrorState,
        streamReconnectAttemptState = streamReconnectAttemptState,
        streamConnectedState = streamConnectedState,
        showSessionsState = showSessionsState,
        showModelPickerState = showModelPickerState,
        pickerModelState = pickerModelState,
        modelOptionsState = modelOptionsState,
        currentModelNameState = currentModelNameState,
        editingMessageIdState = editingMessageIdState,
        editInFlightState = editInFlightState,
        styleState = styleState,
        ready = ready,
        promptInFlight = promptInFlight,
        visibleMessages = visibleMessages,
        suggestions = suggestions,
        draft = draft,
        labels = labels,
        updateDraft = ::updateDraft,
        canEditMessage = ::canEditMessage,
        applyUserEdit = ::applyUserEdit,
        applyAssistantEdit = ::applyAssistantEdit,
        regenerate = ::regenerate,
        cancelTurn = ::cancelTurn,
        sendDraft = ::sendDraft,
        startNewChat = ::startNewChat,
        openSession = ::openSession,
        selectModel = ::selectModel,
        assistReply = ::assistReply,
        retryFailure = ::retryFailure,
    )
}
