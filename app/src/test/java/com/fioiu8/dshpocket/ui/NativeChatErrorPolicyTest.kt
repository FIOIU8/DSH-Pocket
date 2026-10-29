package com.fioiu8.dshpocket.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeChatErrorPolicyTest {
    @Test
    fun `stream reconnect backoff stops after three automatic retries`() {
        assertEquals(1_000L, ChatReconnectPolicy.delayBeforeRetry(1))
        assertEquals(2_000L, ChatReconnectPolicy.delayBeforeRetry(2))
        assertEquals(4_000L, ChatReconnectPolicy.delayBeforeRetry(3))
        assertNull(ChatReconnectPolicy.delayBeforeRetry(4))
    }

    @Test
    fun `regeneration requires a turn without tool calls`() {
        assertEquals(true, canRegenerateTurn(false))
        assertEquals(false, canRegenerateTurn(true))
    }
}
