package com.fioiu8.dshpocket.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.isSystemInDarkTheme
import com.fioiu8.dshpocket.engine.EngineApi
import com.fioiu8.dshpocket.engine.EngineApi.RpcResult
import com.fioiu8.dshpocket.engine.EnginePaths
import com.fioiu8.dshpocket.engine.EngineState
import com.fioiu8.dshpocket.ui.theme.DshSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
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
)

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

@Composable
fun NativeChatScreen(
    state: EngineState,
    onMenu: () -> Unit,
    preferences: NativeChatPreferences = NativeChatPreferences(),
) {
    val context = LocalContext.current
    val ready = state as? EngineState.Ready
    val darkTheme = isSystemInDarkTheme()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var sessions by remember { mutableStateOf<List<NativeChatSession>>(emptyList()) }
    var sessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var messages by remember { mutableStateOf<List<NativeChatMessage>>(emptyList()) }
    var draft by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var liveAssistantText by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showSessions by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var pickerModel by remember { mutableStateOf<NativeModelOption?>(null) }
    var modelOptions by remember { mutableStateOf<List<NativeModelOption>>(emptyList()) }
    var loadedCursor by remember(sessionId) { mutableLongStateOf(-2L) }
    var style by remember(darkTheme) { mutableStateOf(defaultNativeChatStyle(darkTheme)) }
    val visibleMessages = remember(messages, liveAssistantText) {
        (messages + if (liveAssistantText.isBlank()) emptyList() else listOf(
            NativeChatMessage("live-assistant", "assistant", liveAssistantText, 0L),
        )).filterNot { it.isSystemPrompt || it.role == "suggestions" }
    }
    val suggestions = remember(messages) { latestNativeSuggestions(messages) }

    suspend fun loadSessions(engine: EngineState.Ready) {
        val result = chatRpc(
            engine.baseUrl,
            "session/list",
            EngineApi.emptyArgs().put("_request", JSONObject()),
            engine.token,
        )
        if (result is RpcResult.Ok) {
            sessions = parseNativeChatSessions(EngineApi.itemsOf(result.value))
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
                error = context.getString(com.fioiu8.dshpocket.R.string.native_session_load_failed) + ": ${projectionResult.message}"
                return
            }
            is RpcResult.Ok -> projectionResult.value as? JSONObject
        }
        val cursor = projection?.optLong("asOfSeq", Long.MIN_VALUE) ?: Long.MIN_VALUE
        if (cursor == Long.MIN_VALUE) {
            error = context.getString(com.fioiu8.dshpocket.R.string.native_session_load_failed) + ": response cursor missing"
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
                    error = context.getString(com.fioiu8.dshpocket.R.string.native_session_load_failed) + ": message records missing"
                    return
                }
                val parsed = parseNativeChatMessages(records)
                messages = parsed
                loadedCursor = cursor
                error = null
            }
            is RpcResult.Err -> error = context.getString(com.fioiu8.dshpocket.R.string.native_session_load_failed) + ": ${pageResult.message}"
        }
    }

    LaunchedEffect(ready) {
        val engine = ready ?: return@LaunchedEffect
        val catalog = chatRpc(engine.baseUrl, "session/modelCatalog", EngineApi.emptyArgs(), engine.token)
        if (catalog is RpcResult.Ok) modelOptions = parseNativeModelCatalog(
            catalog.value as? JSONObject,
            context.getString(com.fioiu8.dshpocket.R.string.native_effort_default),
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
            runCatching {
                EngineApi.followSession(engine.baseUrl, id).collect { frame ->
                    when (frame.optString("type")) {
                        "snapshot" -> {
                            messages = parseNativeChatMessages(frame.optJSONArray("records") ?: JSONArray())
                            loadedCursor = frame.optLong("cursor", -1L)
                            liveAssistantText = assistantStreamSnapshotText(
                                frame.optJSONObject("assistantStream")?.optJSONObject("activeAttempt")?.optJSONArray("stream"),
                            )
                        }
                        "event" -> {
                            val eventRecord = JSONObject().put("event", frame.optJSONObject("event") ?: JSONObject())
                            val incoming = parseNativeChatMessages(JSONArray().put(eventRecord))
                            if (incoming.isNotEmpty()) {
                                if (incoming.any { it.role == "assistant" }) liveAssistantText = ""
                                messages = (messages + incoming).distinctBy { it.id }
                            }
                        }
                        "assistant-stream" -> {
                            val streamFrame = frame.optJSONObject("frame") ?: return@collect
                            when (streamFrame.optString("type")) {
                                "start" -> liveAssistantText = ""
                                "chunk" -> {
                                    val chunk = streamFrame.optJSONObject("chunk") ?: return@collect
                                    if (chunk.optString("type") == "text-delta") {
                                        liveAssistantText += chunk.optString("text")
                                    }
                                }
                                "end" -> {
                                    refreshMessages(engine, id)
                                    liveAssistantText = ""
                                    loadSessions(engine)
                                }
                            }
                        }
                    }
                }
            }.onFailure { failure ->
                if (failure !is kotlinx.coroutines.CancellationException) {
                    error = "${context.getString(com.fioiu8.dshpocket.R.string.native_session_load_failed)}: ${failure.message.orEmpty()}"
                }
            }
        }
    }

    LaunchedEffect(visibleMessages.size, sending) {
        if (visibleMessages.isNotEmpty()) listState.animateScrollToItem(visibleMessages.lastIndex)
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
                        Icon(Icons.Filled.Chat, contentDescription = stringResource(com.fioiu8.dshpocket.R.string.native_select_session), modifier = Modifier.size(DshSpacing.icon))
                    }
                    IconButton(
                        onClick = { sessionId = null; messages = emptyList(); error = null },
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
                                shape = RoundedCornerShape(12.dp),
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
                                NativeMessageBubble(
                                    message = message,
                                    style = style,
                                    preferences = preferences,
                                    onAssist = if (preferences.showAssistReply && message.role == "assistant") {
                                            {
                                                val id = sessionId
                                                val engine = ready
                                                if (id != null && engine != null && !sending) {
                                                    scope.launch {
                                                        sending = true
                                                        error = null
                                                        val result = chatRpc(
                                                            engine.baseUrl,
                                                            "session/prompt",
                                                            EngineApi.requestArgs(
                                                                JSONObject()
                                                                    .put("requestId", UUID.randomUUID().toString())
                                                                    .put("sessionId", id)
                                                                    .put("mode", "queue")
                                                                    .put(
                                                                        "content",
                                                                        JSONArray().put(
                                                                            JSONObject()
                                                                                .put("type", "text")
                                                                                .put(
                                                                                    "text",
                                                                                    "请基于上一条回答和当前上下文，继续补充有帮助的新信息，避免重复已有内容。",
                                                                                ),
                                                                        ),
                                                                    )
                                                                    .put("clientTimeZone", validClientTimeZone()),
                                                            ),
                                                            engine.token,
                                                        )
                                                        if (result is RpcResult.Err) error = "${context.getString(com.fioiu8.dshpocket.R.string.native_assist_failed)}: ${result.message}"
                                                        loadSessions(engine)
                                                        refreshMessages(engine, id)
                                                        sending = false
                                                    }
                                                }
                                            }
                                    } else null,
                                )
                            }
                            if (sending || running) {
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
                        onValueChange = { draft = it },
                        sending = sending,
                        running = running,
                        enabled = ready != null,
                        onCancel = {
                            val id = sessionId
                            val engine = ready
                            if (id != null && engine != null) {
                                scope.launch {
                                    val result = chatRpc(
                                        engine.baseUrl,
                                        "session/cancel",
                                        EngineApi.requestArgs(JSONObject().put("sessionId", id)),
                                        engine.token,
                                    )
                                    if (result is RpcResult.Err) error = "${context.getString(com.fioiu8.dshpocket.R.string.native_stop_failed)}: ${result.message}"
                                    loadSessions(engine)
                                    refreshMessages(engine, id)
                                }
                            }
                        },
                        onSend = {
                            val text = draft.trim()
                            val engine = ready
                            if (text.isNotEmpty() && engine != null && !sending) {
                                draft = ""
                                sending = true
                                error = null
                                scope.launch {
                                    var targetId = sessionId
                                    if (targetId == null) {
                                        val create = chatRpc(
                                            engine.baseUrl,
                                            "session/create",
                                            EngineApi.requestArgs(
                                                JSONObject().put("cwd", EnginePaths.workspace(context).absolutePath),
                                            ),
                                            engine.token,
                                        )
                                        targetId = (((create as? RpcResult.Ok)?.value as? JSONObject)
                                            ?.optString("sessionId"))?.takeIf { it.isNotBlank() }
                                        if (targetId == null) {
                                            error = (create as? RpcResult.Err)?.message ?: context.getString(com.fioiu8.dshpocket.R.string.native_create_session_failed)
                                            draft = text
                                            sending = false
                                            return@launch
                                        }
                                        sessionId = targetId
                                    }
                                    val result = chatRpc(
                                        engine.baseUrl,
                                        "session/prompt",
                                        EngineApi.requestArgs(
                                            JSONObject()
                                                .put("requestId", UUID.randomUUID().toString())
                                                .put("sessionId", targetId)
                                                .put("mode", "queue")
                                                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
                                                .put("clientTimeZone", validClientTimeZone()),
                                        ),
                                        engine.token,
                                    )
                                    when (result) {
                                        is RpcResult.Ok -> {
                                            loadSessions(engine)
                                            refreshMessages(engine, targetId)
                                        }
                                        is RpcResult.Err -> {
                                            error = "${context.getString(com.fioiu8.dshpocket.R.string.native_send_failed)}: ${result.message}"
                                            draft = text
                                        }
                                    }
                                    sending = false
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
                Column {
                    sessions.forEach { session ->
                        TextButton(
                            onClick = {
                                showSessions = false
                                sessionId = session.id
                                messages = emptyList()
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
                        val efforts = option.efforts.ifEmpty { listOf("default" to context.getString(com.fioiu8.dshpocket.R.string.native_effort_default)) }
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
                                                error = (created as? RpcResult.Err)?.message ?: context.getString(com.fioiu8.dshpocket.R.string.native_create_session_failed)
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
                                            is RpcResult.Err -> error = "${context.getString(com.fioiu8.dshpocket.R.string.native_switch_model_failed)}: ${result.message}"
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

private fun validClientTimeZone(): String {
    val id = java.util.TimeZone.getDefault().id
    val isAreaLocation = id.matches(Regex("[A-Za-z_+-]+(/[A-Za-z0-9._+-]+)+"))
    return if (id == "UTC" || (isAreaLocation && id in java.util.TimeZone.getAvailableIDs())) id else "UTC"
}

@Composable
private fun NativeMessageBubble(
    message: NativeChatMessage,
    style: NativeChatStyle,
    preferences: NativeChatPreferences,
    onAssist: (() -> Unit)?,
) {
    val fromUser = message.role == "user"
    val clipboard = LocalClipboardManager.current
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
                    shape = RoundedCornerShape(style.cornerRadius.dp),
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
                clipboard.setText(AnnotatedString(message.text))
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
    val metadata = buildList {
        if (preferences.showMessageTime && message.time > 0) add(formatMessageTime(message.time))
        if (preferences.showTokenUsage) {
            message.inputTokens?.let { add("↑ $it") }
            message.outputTokens?.let { add("↓ $it") }
        }
        if (preferences.showDuration) {
            message.durationMs?.let { duration ->
                add(if (duration >= 1000) "%.1fs".format(Locale.getDefault(), duration / 1000.0) else "${duration}ms")
                if (message.outputTokens != null && duration > 0) {
                    add("%.1f tok/s".format(Locale.getDefault(), message.outputTokens * 1000.0 / duration))
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

private fun formatMessageTime(rawTime: Long): String {
    val millis = if (rawTime in 1..100_000_000_000L) rawTime * 1000 else rawTime
    return SimpleDateFormat("MM月dd日 HH:mm:ss", Locale.getDefault()).format(Date(millis))
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
    val clipboard = LocalClipboardManager.current
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
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(26.dp)),
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
                                    clipboard.getText()?.text?.let { onValueChange(value + it) }
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
                            Icon(Icons.Filled.Send, contentDescription = stringResource(com.fioiu8.dshpocket.R.string.send))
                        }
                    }
                }
            }
        }
    }
}

internal fun parseNativeChatSessions(items: JSONArray): List<NativeChatSession> = buildList {
    for (index in 0 until items.length()) {
        val item = items.optJSONObject(index) ?: continue
        if (item.optBoolean("blank")) continue
        val values = item.optJSONObject("projections")?.optJSONObject("values")
        val title = values?.optString("title")?.takeIf { it.isNotBlank() && it != "null" } ?: "Untitled session"
        add(NativeChatSession(item.optString("sessionId"), title, item.optBoolean("running")))
    }
}

internal fun parseNativeModelCatalog(catalog: JSONObject?, defaultEffortLabel: String = "模型默认"): List<NativeModelOption> = buildList {
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

internal fun parseNativeChatMessages(records: JSONArray): List<NativeChatMessage> = buildList {
    for (index in 0 until records.length()) {
        val event = records.optJSONObject(index)?.optJSONObject("event") ?: continue
        val type = event.optString("type")
        val data = event.optJSONObject("data") ?: continue
        if (type == "tool/result") {
            val meta = data.optJSONObject("meta") ?: continue
            if (meta.optString("kind") != "dsh-pocket/native-chat-suggestions") continue
            val suggestions = meta.optJSONArray("suggestions") ?: continue
            val values = buildList {
                for (suggestionIndex in 0 until suggestions.length()) {
                    val suggestion = suggestions.optString(suggestionIndex).trim()
                    if (suggestion.length in 2..80 && suggestion !in this) add(suggestion)
                }
            }.take(4)
            if (values.size >= 2) {
                add(
                    NativeChatMessage(
                        id = "suggestions-${event.optLong("seq")}",
                        role = "suggestions",
                        text = "",
                        time = event.optLong("time"),
                        suggestions = values,
                    ),
                )
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
        val text = contentText(message.optJSONArray("content"))
            .ifBlank { message.optString("text").takeIf { it != "null" }.orEmpty() }
        if (text.isBlank()) continue
        val isSystemPrompt = type == "system/message" || type == "developer/message" || isNativeSystemPrompt(text)
        val role = when {
            type == "assistant/message" -> "assistant"
            type == "user/message" && !isSystemPrompt -> "user"
            else -> "system"
        }
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
        add(
            NativeChatMessage(
                message.optString("id", "${event.optLong("seq")}"),
                role,
                text,
                event.optLong("time"),
                isSystemPrompt,
                inputTokens,
                outputTokens,
                durationMs,
            ),
        )
    }
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

private fun contentText(content: JSONArray?): String = buildList {
    if (content == null) return@buildList
    for (index in 0 until content.length()) {
        val block = content.optJSONObject(index) ?: continue
        when (block.optString("type")) {
            "text" -> block.optString("text").takeIf { it.isNotBlank() }?.let(::add)
            "image" -> add("[图片]")
            "file" -> add("[文件]")
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
