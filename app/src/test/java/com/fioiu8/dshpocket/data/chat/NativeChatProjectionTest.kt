package com.fioiu8.dshpocket.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NativeChatProjectionTest {
    private fun message(id: String, role: String, text: String = id) =
        NativeChatMessage(id, role, text, 1L)

    @Test
    fun `active reasoning replaces only trailing history reasoning and keeps earlier steps`() {
        val history = listOf(
            message("earlier-thinking", "thinking"),
            message("tool", "tool_call"),
            message("attempt-1", "thinking"),
            message("attempt-2", "thinking"),
        )
        val rows = visibleNativeMessages(history, "answer", LiveThinking("latest thought", 1_000L, 1_500L))

        assertEquals(listOf("earlier-thinking", "tool", "live-thinking", "live-assistant"), rows.map { it.id })
        assertEquals("latest thought", rows[2].text)
        assertEquals(500L, rows[2].durationMs)
        assertEquals("answer", rows.last().text)
        assertEquals(4, history.size)
    }

    @Test
    fun `blank live reasoning keeps durable reasoning and creates no empty live rows`() {
        val history = listOf(message("thinking", "thinking"))

        assertEquals(history, visibleNativeMessages(history, "  ", LiveThinking("\n ", 0L)))
        assertEquals(history, visibleNativeMessages(history, "", null))
    }

    @Test
    fun `system instructions and suggestions remain hidden without losing normal history`() {
        val history = listOf(
            message("user", "user"),
            message("instruction", "user").copy(isSystemPrompt = true),
            message("suggestions", "suggestions"),
            message("assistant", "assistant"),
        )
        val rows = visibleNativeMessages(history, "", null)

        assertEquals(listOf("user", "assistant"), rows.map { it.id })
        assertFalse(rows.any { it.id.startsWith("live-") })
    }
}
