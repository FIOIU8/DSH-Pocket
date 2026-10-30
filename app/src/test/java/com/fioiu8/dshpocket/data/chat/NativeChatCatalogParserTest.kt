package com.fioiu8.dshpocket.data.chat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeChatCatalogParserTest {
    private fun session(id: String, title: Any?, running: Boolean = false): JSONObject =
        JSONObject().put("sessionId", id).put("running", running)
            .put("projections", JSONObject().put("values", JSONObject().put("title", title)))

    @Test
    fun `session list skips blank and non object rows while retaining engine order`() {
        val items = JSONArray()
            .put("invalid")
            .put(session("blank", "placeholder").put("blank", true))
            .put(session("second", "Second", running = true))
            .put(session("first", "First"))

        assertEquals(
            listOf(NativeChatSession("second", "Second", true), NativeChatSession("first", "First", false)),
            parseNativeChatSessions(items, "Untitled"),
        )
    }

    @Test
    fun `session titles use supplied locale fallback for null empty and missing titles`() {
        val items = JSONArray()
            .put(session("null", JSONObject.NULL))
            .put(session("empty", " "))
            .put(JSONObject().put("sessionId", "missing"))

        assertEquals(listOf("未命名", "未命名", "未命名"), parseNativeChatSessions(items, "未命名").map { it.title })
        assertEquals(listOf("Untitled", "Untitled", "Untitled"), parseNativeChatSessions(items, "Untitled").map { it.title })
    }

    @Test
    fun `catalog retains provider identifiers and effort order while ignoring missing identifiers`() {
        val group = JSONObject().put("id", "provider").put("models", JSONArray()
            .put(JSONObject())
            .put(JSONObject().put("id", "model").put("reasoning", JSONObject().put("efforts", JSONArray()
                .put(JSONObject().put("name", "missing id"))
                .put(JSONObject().put("id", "high"))
                .put(JSONObject().put("id", "low").put("name", "Low"))))))
        val option = parseNativeModelCatalog(JSONObject().put("groups", JSONArray().put(group)), "Default").single()

        assertEquals("provider", option.providerId)
        assertEquals("provider", option.providerName)
        assertEquals("model", option.modelId)
        assertEquals("model", option.name)
        assertEquals(listOf("default" to "Default", "high" to "high", "low" to "Low"), option.efforts)
    }

    @Test
    fun `absent catalog and malformed groups produce no options`() {
        assertTrue(parseNativeModelCatalog(null, "Default").isEmpty())
        assertTrue(parseNativeModelCatalog(JSONObject(), "Default").isEmpty())
        assertTrue(parseNativeModelCatalog(JSONObject().put("groups", JSONArray().put("invalid").put(JSONObject())), "Default").isEmpty())
    }
}
