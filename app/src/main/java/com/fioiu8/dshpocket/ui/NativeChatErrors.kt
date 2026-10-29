package com.fioiu8.dshpocket.ui

/** A user-facing chat failure with a safe, expandable diagnostic payload. */
data class ChatErrorState(
    val source: ChatErrorSource,
    val title: String,
    val message: String,
    val code: String,
    val details: String,
    val requestId: String? = null,
    val retryable: Boolean = false,
    val restoreDraft: Boolean = false,
    val recoveryText: String? = null,
)

enum class ChatErrorSource { Load, Send, Stream, Edit, Stop, Model, Turn }

/** Bounded reconnect schedule: 1s, 2s, 4s; the fourth failure needs a user action. */
internal object ChatReconnectPolicy {
    const val MAX_AUTOMATIC_RETRIES = 3

    fun delayBeforeRetry(retryNumber: Int): Long? = when (retryNumber) {
        1 -> 1_000L
        2 -> 2_000L
        3 -> 4_000L
        else -> null
    }
}

/** A regeneration is safe only when the failed turn has not invoked any tools. */
internal fun canRegenerateTurn(turnHadToolCalls: Boolean): Boolean = !turnHadToolCalls
