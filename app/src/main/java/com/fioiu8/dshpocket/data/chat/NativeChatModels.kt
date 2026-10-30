package com.fioiu8.dshpocket.data.chat

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
    // Journal sequence interleaves tool calls and surface messages.
    val seq: Long = -1L,
    // Durable tool/call event, paired with its tool/result.
    val toolName: String? = null,
    val callId: String? = null,
    val argumentsText: String? = null,
    val resultText: String? = null,
    val isError: Boolean = false,
    val errorDetails: String? = null,
    val errorCode: String? = null,
    val requestId: String? = null,
    val retryable: Boolean = false,
    // Assistant message reinserted by a user edit surface replacement.
    val edited: Boolean = false,
    val turn: Int? = null,
) {
    val isRunningToolCall: Boolean get() = role == "tool_call" && resultText == null
}

/** Live reasoning between the first reasoning delta and answer text. */
data class LiveThinking(
    val text: String,
    val startedAtMs: Long,
    val finishedAtMs: Long? = null,
) {
    val durationMs: Long? get() = finishedAtMs?.let { it - startedAtMs }
    val latestLine: String? get() = text.lines().lastOrNull { it.isNotBlank() }?.take(160)
}

data class NativeChatSession(val id: String, val title: String, val running: Boolean)

data class NativeModelOption(
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val name: String,
    val efforts: List<Pair<String, String>>,
)
