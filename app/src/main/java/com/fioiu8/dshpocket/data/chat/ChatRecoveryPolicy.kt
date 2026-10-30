package com.fioiu8.dshpocket.data.chat



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
