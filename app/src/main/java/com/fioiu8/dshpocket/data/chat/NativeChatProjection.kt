package com.fioiu8.dshpocket.data.chat

/** Combine durable messages with the active stream without rendering reasoning twice. */
internal fun visibleNativeMessages(
    messages: List<NativeChatMessage>,
    liveAssistantText: String,
    liveThinking: LiveThinking?,
): List<NativeChatMessage> {
    val live = liveThinking?.takeIf { it.text.isNotBlank() }
    var settled = messages
    if (live != null) {
        // The in-flight step also lands in history through its attempt event.
        while (settled.lastOrNull()?.role == "thinking") settled = settled.dropLast(1)
    }
    return (settled + listOfNotNull(
        live?.let { NativeChatMessage("live-thinking", "thinking", it.text, 0L, durationMs = it.durationMs) },
        if (liveAssistantText.isBlank()) null else NativeChatMessage("live-assistant", "assistant", liveAssistantText, 0L),
    )).filterNot { it.isSystemPrompt || it.role == "suggestions" }
}
