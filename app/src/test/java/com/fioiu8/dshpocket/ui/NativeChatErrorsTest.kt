package com.fioiu8.dshpocket.ui

import com.fioiu8.dshpocket.data.chat.NativeChatMessage
import com.fioiu8.dshpocket.data.chat.parseNativeChatMessages
import com.fioiu8.dshpocket.data.chat.applySessionEvent

import com.fioiu8.dshpocket.data.chat.NativeChatToolCallStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeChatErrorsTest {

    private fun event(type: String, data: JSONObject, seq: Long): JSONObject = JSONObject()
        .put("event", JSONObject().put("type", type).put("seq", seq).put("time", seq).put("data", data))

    private fun parse(vararg events: JSONObject): List<NativeChatMessage> =
        parseNativeChatMessages(JSONArray().apply { events.forEach(::put) }, "[Image]", "[File]")

    @Test
    fun `turn end failure kinds become ordered timeline nodes`() {
        val user = event(
            "user/message",
            JSONObject().put("turn", 2).put("role", "user")
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "Do this"))),
            10,
        )
        val call = event("tool/call", JSONObject().put("callId", "c1").put("turn", 2).put("name", "bash"), 20)
        val failedTurn = event(
            "turn/end",
            JSONObject().put("turn", 2).put("reason", JSONObject().put("kind", "error")
                .put("error", JSONObject().put("code", "provider_error").put("message", "Unavailable").put("requestId", "req-2"))),
            30,
        )
        val messages = parse(failedTurn, call, user)

        assertEquals(listOf("user", "tool_call", "error"), messages.map { it.role })
        val error = messages.last()
        assertEquals("provider_error", error.errorCode)
        assertEquals("req-2", error.requestId)
        assertFalse("A turn with a tool call must not be automatically replayed", error.retryable)
    }

    @Test
    fun `error turn without tools can regenerate and carries request id`() {
        val user = event(
            "user/message",
            JSONObject().put("turn", 4).put("role", "user")
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "Try this"))),
            4,
        )
        val errorEvent = event(
            "turn/end",
            JSONObject().put("turn", 4).put("reason", JSONObject().put("kind", "error")
                .put("error", JSONObject().put("message", "Provider unavailable"))),
            5,
        )
        val error = parse(user, errorEvent).last()
        assertEquals("error", error.role)
        assertTrue(error.retryable)
        assertTrue(error.errorDetails.orEmpty().contains("toolCalls: none"))
    }

    @Test
    fun `error text is redacted before it enters timeline or clipboard`() {
        val user = event(
            "user/message",
            JSONObject().put("turn", 5).put("role", "user")
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "Try this"))),
            1,
        )
        val errorEvent = event(
            "turn/end",
            JSONObject().put("turn", 5).put("reason", JSONObject().put("kind", "error")
                .put("error", JSONObject().put("message", "Authorization: Bearer private-value"))),
            2,
        )
        val error = parse(user, errorEvent).last()

        assertFalse(error.text.contains("private-value"))
        assertTrue(error.text.contains("[redacted]"))
    }

    @Test
    fun `blocked token-limit and interrupted turns are represented without auto replay`() {
        val kinds = listOf("blocked", "max-tokens", "interrupted")
        kinds.forEachIndexed { index, kind ->
            val parsed = parse(event("turn/end", JSONObject().put("turn", index + 1).put("reason", JSONObject().put("kind", kind)), index + 1L))
            assertEquals("turn/$kind", parsed.single().errorCode)
            assertFalse(parsed.single().retryable)
        }
    }

    @Test
    fun `tool result errors stay on their call and include safe details`() {
        val call = event("tool/call", JSONObject().put("callId", "c9").put("turn", 9).put("name", "bash").put("arguments", "{}"), 1)
        val result = event(
            "tool/result",
            JSONObject()
                .put("message", JSONObject().put("toolCallId", "c9").put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "Command failed"))))
                .put("error", JSONObject().put("code", "exit_error").put("message", "Command failed").put("reason", "exit status 2").put("requestId", "tool-9")),
            2,
        )

        val tool = parse(call, result).single()
        assertEquals("tool_call", tool.role)
        assertTrue(tool.isError)
        assertEquals("exit_error", tool.errorCode)
        assertEquals("tool-9", tool.requestId)
        assertTrue(tool.errorDetails.orEmpty().contains("exit status 2"))
        assertEquals("Command failed", tool.resultText)
    }

    @Test
    fun `live turn error checks already observed tool calls`() {
        val runningCall = NativeChatMessage("call", "tool_call", "", 1, turn = 7)
        val turnEnd = event("turn/end", JSONObject().put("turn", 7).put("reason", JSONObject().put("kind", "error")), 3)
            .optJSONObject("event")!!

        val error = applySessionEvent(listOf(runningCall), turnEnd, "[Image]", "[File]").last()
        assertEquals("error", error.role)
        assertFalse(error.retryable)
    }

    @Test
    fun `tool call store keeps optional turn while reading old records`() {
        val entry = NativeChatToolCallStore.Entry(1, "call", "bash", "{}", turn = 3)
        assertEquals(3, NativeChatToolCallStore.Entry.fromJson(entry.toJson())?.turn)
        assertEquals(null, NativeChatToolCallStore.Entry.fromJson(JSONObject().put("callId", "old"))?.turn)
    }
}
