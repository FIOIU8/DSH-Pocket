package com.fioiu8.dshpocket.ui

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fioiu8.dshpocket.data.session.SessionRepository
import com.fioiu8.dshpocket.engine.EngineState
import com.fioiu8.dshpocket.ui.theme.DshSpacing
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

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
    val chat = rememberNativeChatState(
        state = state,
        repository = repository,
        pendingOpenSessionId = pendingOpenSessionId,
        onPendingSessionConsumed = onPendingSessionConsumed,
        initialSessionId = initialSessionId,
        onSessionIdChanged = onSessionIdChanged,
        chatDrafts = chatDrafts,
        onChatDraftChange = onChatDraftChange,
    )
    val listState = rememberLazyListState()
    with(chat) {
        with(labels) {
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
                                onClick = { startNewChat() },
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
                                    ChatErrorSource.Load, ChatErrorSource.Stream, ChatErrorSource.Send -> ({ retryFailure(failure) })
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
                                                    onRegenerate = { regenerate(message) },
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
                                                        { assistReply() }
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
                                onCancel = { cancelTurn() },
                                onSend = { sendDraft() },
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
                                    onClick = { openSession(session) },
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
                                        onClick = { selectModel(option, effortId) },
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
    }
}
