package com.fioiu8.dshpocket

import com.fioiu8.dshpocket.ui.NativeChatMessage
import com.fioiu8.dshpocket.ui.applySessionEvent
import com.fioiu8.dshpocket.ui.parseNativeChatMessages
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the pocket message-edit parsing: the developer sentinel never renders, the
 * corrected assistant message carries the edited flag, and a live replace event drops
 * the messages it shadowed.
 */
class ChatEditParseTest {

    private fun message(id: String, text: String): JSONObject =
        JSONObject().put("id", id).put("role", "assistant")
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))

    private fun replaceEvent(seq: Int, shadowedSeq: Int, text: String): JSONObject = JSONObject()
        .put("type", "developer/message")
        .put("seq", seq)
        .put("time", 1L)
        .put("surfaceOp", JSONObject().put("op", "replace").put("startSeq", shadowedSeq).put("endSeq", shadowedSeq))
        .put("sourceEventSeqs", JSONArray().put(shadowedSeq))
        .put("data", JSONObject().put("message", message("note-$seq", text)))

    private fun parsed(vararg events: JSONObject): List<NativeChatMessage> {
        val records = JSONArray()
        events.forEach { records.put(JSONObject().put("event", it)) }
        return parseNativeChatMessages(records, "[图片]", "[文件]")
    }

    @Test
    fun `edit sentinel developer note is hidden and marks the corrected assistant message`() {
        val corrected = JSONObject()
            .put("type", "assistant/message")
            .put("seq", 6)
            .put("time", 2L)
            .put("data", JSONObject().put("message", message("msg-2", "corrected answer")))

        val messages = parsed(replaceEvent(5, 3, "edited by user"), corrected)

        assertFalse(messages.any { it.role == "system" })
        val assistant = messages.single { it.role == "assistant" }
        assertEquals("corrected answer", assistant.text)
        assertTrue(assistant.edited)
    }

    @Test
    fun `ordinary developer and assistant messages stay unflagged`() {
        val developer = JSONObject()
            .put("type", "developer/message")
            .put("seq", 1)
            .put("time", 1L)
            .put("data", JSONObject().put("message", message("dev-1", "tool registry changed")))
        val assistant = JSONObject()
            .put("type", "assistant/message")
            .put("seq", 2)
            .put("time", 2L)
            .put("data", JSONObject().put("message", message("msg-1", "hello")))

        val messages = parsed(developer, assistant)

        assertNull(messages.single { it.role == "assistant" }.takeIf { it.edited })
    }

    @Test
    fun `live replace event drops the shadowed message before appending`() {
        val old = NativeChatMessage("msg-1", "assistant", "old text", 1L, seq = 3)

        val replaced = applySessionEvent(listOf(old), replaceEvent(5, 3, "sentinel"), "[图片]", "[文件]")

        assertFalse(replaced.any { it.seq == 3L && it.role != "system" })
    }
}
