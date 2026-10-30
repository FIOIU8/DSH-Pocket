package com.fioiu8.dshpocket

import com.fioiu8.dshpocket.data.chat.NativeChatMessage
import com.fioiu8.dshpocket.data.chat.applySessionEvent
import com.fioiu8.dshpocket.ui.formatSecondsValue
import com.fioiu8.dshpocket.data.chat.parseNativeChatMessages
import com.fioiu8.dshpocket.ui.prettyToolArguments
import com.fioiu8.dshpocket.ui.toolCallLabel
import com.fioiu8.dshpocket.ui.toolCallIcon
import com.fioiu8.dshpocket.R
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the tool-call / thinking rendering pipeline: durable-event pairing, reasoning
 * extraction with duration, the live-result fold, and the collapsed-row labels.
 */
class NativeChatCardsTest {

    private fun event(type: String, data: JSONObject, seq: Long, time: Long = 0L): JSONObject =
        JSONObject().put("event", JSONObject().put("type", type).put("seq", seq).put("time", time).put("data", data))

    private fun parse(vararg events: JSONObject): List<NativeChatMessage> {
        val records = JSONArray()
        events.forEach { records.put(it) }
        return parseNativeChatMessages(records, "[图片]", "[文件]")
    }

    private fun textResult(callId: String, text: String, isError: Boolean = false): JSONObject =
        JSONObject().put(
            "message",
            JSONObject()
                .put("toolCallId", callId)
                .put("isError", isError)
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text))),
        )

    @Test
    fun `tool call pairs with its result and drops the suggestions noise`() {
        val messages = parse(
            event(
                "tool/call",
                JSONObject().put("callId", "c1").put("name", "read").put("arguments", """{"file_path":"/tmp/a.md"}"""),
                1,
            ),
            event("tool/result", textResult("c1", "文件内容"), 2),
            event(
                "tool/call",
                JSONObject().put("callId", "c2").put("name", "show_chat_suggestions").put("arguments", "{}"),
                3,
            ),
            event(
                "tool/result",
                JSONObject().put("meta", JSONObject().put("kind", "dsh-pocket/native-chat-suggestions").put("suggestions", JSONArray().put("一是").put("二是"))),
                4,
            ),
        )
        val calls = messages.filter { it.role == "tool_call" }
        assertEquals(1, calls.size)
        assertEquals("read", calls[0].toolName)
        assertEquals("c1", calls[0].callId)
        assertEquals("文件内容", calls[0].resultText)
        assertFalse(calls[0].isRunningToolCall)
        assertEquals(1, messages.count { it.role == "suggestions" })
    }

    @Test
    fun `unpaired call stays running`() {
        val messages = parse(
            event(
                "tool/call",
                JSONObject().put("callId", "c1").put("name", "bash").put("arguments", """{"command":"ls\npwd"}"""),
                1,
            ),
        )
        val call = messages.single()
        assertTrue(call.isRunningToolCall)
        assertNull(call.resultText)
        // The bash label shows the first command line only.
        assertEquals(R.string.native_tool_bash, toolCallLabel(call).labelRes)
        assertEquals("ls", toolCallLabel(call).arg)
    }

    @Test
    fun `error results flag the card`() {
        val messages = parse(
            event("tool/call", JSONObject().put("callId", "c1").put("name", "read").put("arguments", "{}"), 1),
            event("tool/result", textResult("c1", "boom", isError = true), 2),
        )
        val call = messages.filter { it.role == "tool_call" }.single()
        assertTrue(call.isError)
        assertEquals("boom", call.resultText)
    }

    @Test
    fun `live result folds into the pending call row`() {
        val running = NativeChatMessage("tool-call-1", "tool_call", "", 0L, toolName = "pocket_screen_control", callId = "c9", argumentsText = """{"action":"read","reason":"看看"}""")
        val resultEvent = event(
            "tool/result",
            JSONObject().put("message", JSONObject().put("toolCallId", "c9").put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "树内容")))),
            2,
        )
        val merged = applySessionEvent(listOf(running), resultEvent.optJSONObject("event")!!, "[图片]", "[文件]")
        assertEquals(1, merged.size)
        assertEquals("树内容", merged.single().resultText)
        assertFalse(merged.single().isRunningToolCall)
    }

    @Test
    fun `attempt events become thinking cards with duration`() {
        val stream = JSONArray()
            .put(JSONObject().put("time", 1_000).put("chunk", JSONObject().put("type", "reasoning-delta").put("text", "先读屏再决定点哪里")))
            .put(JSONObject().put("time", 1_500).put("chunk", JSONObject().put("type", "reasoning-delta").put("text", "。")))
            .put(JSONObject().put("time", 2_000).put("chunk", JSONObject().put("type", "text-delta").put("text", "好的")))
        val messages = parse(event("assistant/attempt", JSONObject().put("turn", 1).put("step", 1).put("stream", stream), 5))
        val thinking = messages.filter { it.role == "thinking" }
        assertEquals(1, thinking.size)
        assertEquals("先读屏再决定点哪里。", thinking[0].text)
        assertEquals(500L, thinking[0].durationMs)
    }

    @Test
    fun `assistant message stream is the fallback when no attempt event carried thinking`() {
        val stream = JSONArray()
            .put(JSONObject().put("time", 5_000).put("chunk", JSONObject().put("type", "reasoning-delta").put("text", "想一想")))
            .put(JSONObject().put("time", 6_000).put("chunk", JSONObject().put("type", "reasoning-delta").put("text", "答案")))
            .put(JSONObject().put("time", 6_100).put("chunk", JSONObject().put("type", "text-delta").put("text", "答案正文")))
        val assistant = JSONObject()
            .put("message", JSONObject().put("role", "assistant").put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "答案正文"))))
            .put("stream", stream)
        val messages = parse(event("assistant/message", assistant, 7))
        assertEquals(1, messages.count { it.role == "thinking" })
        assertEquals("想一想答案", messages.filter { it.role == "thinking" }[0].text)
        assertEquals(1, messages.count { it.role == "assistant" })
    }

    @Test
    fun `durable assistant stream reasoning blocks produce thinking with duration`() {
        // The journal's batched form: block-start(reasoning) → reasoning-chunks → block-end(reasoning).
        val stream = JSONArray()
            .put(JSONObject().put("type", "chunk").put("time", 1_000).put("chunk", JSONObject().put("type", "block-start").put("index", 0).put("blockType", "reasoning")))
            .put(JSONObject().put("type", "reasoning-chunks").put("time0", 1_000).put("dt", JSONArray().put(200).put(300)).put("texts", JSONArray().put("第一段").put("第二段")))
            .put(JSONObject().put("type", "chunk").put("time", 2_000).put("chunk", JSONObject().put("type", "block-end").put("index", 0).put("block", JSONObject().put("type", "reasoning").put("text", "第一段第二段"))))
            .put(JSONObject().put("type", "chunk").put("time", 2_100).put("chunk", JSONObject().put("type", "block-end").put("index", 1).put("block", JSONObject().put("type", "text").put("text", "正文"))))
        val assistant = JSONObject()
            .put("message", JSONObject().put("role", "assistant").put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "正文"))))
            .put("stream", stream)
        val messages = parse(event("assistant/message", assistant, 9))
        val thinking = messages.filter { it.role == "thinking" }
        assertEquals(1, thinking.size)
        assertEquals("第一段第二段", thinking[0].text)
        assertEquals(1_000L, thinking[0].durationMs)
    }

    @Test
    fun `overlay tool calls merge into history by journal sequence`() {
        val user = event(
            "user/message",
            JSONObject().put("role", "user").put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "hi"))),
            10,
        )
        val toolResult = event("tool/result", textResult("c1", "树内容"), 12)
        val assistant = event(
            "assistant/message",
            JSONObject().put("message", JSONObject().put("role", "assistant").put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "done")))),
            13,
        )
        val overlay = listOf(
            com.fioiu8.dshpocket.data.chat.NativeChatToolCallStore.Entry(
                11,
                "c1",
                "pocket_screen_control",
                """{"action":"read","reason":"看看"}""",
            ),
        )
        val messages = parseNativeChatMessages(JSONArray().put(user).put(toolResult).put(assistant), "[图片]", "[文件]", overlay)
        val calls = messages.filter { it.role == "tool_call" }
        assertEquals(1, calls.size)
        assertEquals("pocket_screen_control", calls[0].toolName)
        assertEquals("树内容", calls[0].resultText)
        // Journal order: user(10) → call(11) → assistant(13).
        assertEquals(listOf("user", "tool_call", "assistant"), messages.map { it.role })
    }

    @Test
    fun `assistant message stream does not duplicate an attempt-derived card`() {
        val stream = JSONArray()
            .put(JSONObject().put("time", 5_000).put("chunk", JSONObject().put("type", "reasoning-delta").put("text", "想一想")))
            .put(JSONObject().put("time", 6_100).put("chunk", JSONObject().put("type", "text-delta").put("text", "答案正文")))
        val attempt = event(
            "assistant/attempt",
            JSONObject().put("turn", 1).put("step", 1).put("stream", stream),
            7,
        )
        val assistant = event(
            "assistant/message",
            JSONObject()
                .put("message", JSONObject().put("role", "assistant").put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "答案正文"))))
                .put("stream", stream),
            8,
        )
        assertEquals(1, parse(attempt, assistant).count { it.role == "thinking" })
    }

    @Test
    fun `computer use labels read the real action`() {
        fun call(args: String) = NativeChatMessage("t", "tool_call", "", 0L, toolName = "pocket_screen_control", callId = "c", argumentsText = args)

        assertEquals(
            R.string.native_tool_screen_read,
            toolCallLabel(call("""{"action":"read","reason":"看看"}""")).labelRes,
        )
        assertEquals(
            R.string.native_tool_screen_tap,
            toolCallLabel(call("""{"action":"tap","x":540,"y":1200,"reason":"点"}""")).labelRes,
        )
        assertEquals("540, 1200", toolCallLabel(call("""{"action":"tap","x":540,"y":1200}""")).arg)
        assertEquals(
            R.string.native_tool_screen_swipe,
            toolCallLabel(call("""{"action":"swipe","x":1,"y":2,"x2":3,"y2":4}""")).labelRes,
        )
        assertEquals(
            R.string.native_tool_screen_key,
            toolCallLabel(call("""{"action":"key","key":"back"}""")).labelRes,
        )
        assertEquals("back", toolCallLabel(call("""{"action":"key","key":"back"}""")).arg)
    }

    @Test
    fun `file tools surface the path and unknown tools fall back to the generic label`() {
        val read = NativeChatMessage("t", "tool_call", "", 0L, toolName = "read", callId = "c", argumentsText = """{"file_path":"/data/workspace/notes.md"}""")
        assertEquals(R.string.native_tool_read, toolCallLabel(read).labelRes)
        assertEquals("/data/workspace/notes.md", toolCallLabel(read).arg)

        val mystery = NativeChatMessage("t", "tool_call", "", 0L, toolName = "mcp_tool", callId = "c", argumentsText = "{}")
        assertEquals(R.string.native_tool_generic, toolCallLabel(mystery).labelRes)
        assertEquals("mcp_tool", toolCallLabel(mystery).arg)

        assertEquals(R.string.native_tool_todo, toolCallLabel(NativeChatMessage("t", "tool_call", "", 0L, toolName = "todo_write")).labelRes)
    }

    @Test
    fun `icons separate screen tools from file and shell tools`() {
        assertTrue(toolCallIcon("pocket_screen_control") != toolCallIcon("read"))
        assertTrue(toolCallIcon("bash") != toolCallIcon("read"))
        assertEquals(toolCallIcon("unknown_tool"), toolCallIcon("another_unknown"))
    }

    @Test
    fun `seconds formatting and pretty json edge cases`() {
        assertEquals("3.2", formatSecondsValue(3_200))
        assertEquals("30", formatSecondsValue(30_400))
        // org.json only breaks lines around nested containers.
        assertEquals("{\"a\": 1}", prettyToolArguments("""{"a":1}"""))
        assertEquals("not json", prettyToolArguments("not json"))
    }
}
