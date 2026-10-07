package com.fioiu8.dshpocket

import com.fioiu8.dshpocket.ui.archivedIdsFromValues
import com.fioiu8.dshpocket.ui.NativeChatMessage
import com.fioiu8.dshpocket.ui.latestNativeSuggestions
import com.fioiu8.dshpocket.ui.isNativeSystemPrompt
import com.fioiu8.dshpocket.ui.parseNativeChatMessages
import com.fioiu8.dshpocket.ui.parseNativeModelCatalog
import com.fioiu8.dshpocket.ui.markdownToAnnotatedString
import com.fioiu8.dshpocket.ui.assistantStreamSnapshotText
import com.fioiu8.dshpocket.ui.parseProviderModels
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class SessionsScreenTest {

    @Test
    fun archivedIdsFromValues_readsTheCompleteSet() {
        assertEquals(setOf("session-a", "session-b"), archivedIdsFromValues(listOf("session-a", "session-b")))
    }

    @Test
    fun archivedIdsFromValues_distinguishesMissingFieldFromEmptySet() {
        assertNull(archivedIdsFromValues(null))
        assertEquals(emptySet<String>(), archivedIdsFromValues(emptyList()))
    }

    @Test
    fun nativeSystemPromptDetectionLeavesNormalTextVisible() {
        assertTrue(isNativeSystemPrompt("Current runtime context."))
        assertFalse(isNativeSystemPrompt("你好，请介绍一下自己。"))
    }

    @Test
    fun developerEventsAreNotProjectedAsUserMessages() {
        val developerMessage = org.json.JSONObject().put(
            "content",
            JSONArray().put(org.json.JSONObject().put("type", "text").put("text", "internal instruction")),
        )
        val event = org.json.JSONObject()
            .put("seq", 1)
            .put("time", 1_700_000_000_000L)
            .put("type", "developer/message")
            .put("data", org.json.JSONObject().put("message", developerMessage))
        val records = JSONArray().put(org.json.JSONObject().put("event", event))

        val message = parseNativeChatMessages(records).single()
        assertEquals("system", message.role)
        assertTrue(message.isSystemPrompt)
    }

    @Test
    fun markdownRendererFormatsHeadingsEmphasisAndFencedCode() {
        val rendered = markdownToAnnotatedString("# Title\n\n**bold** and `inline`\n\n```js\nconst x = 1\n```", androidx.compose.ui.graphics.Color.Black).text

        assertTrue(rendered.contains("Title"))
        assertTrue(rendered.contains("bold"))
        assertTrue(rendered.contains("const x = 1"))
        assertFalse(rendered.contains("**"))
        assertFalse(rendered.contains("```"))
    }

    @Test
    fun modelCatalogOptionsKeepProviderAndReasoningEfforts() {
        val model = org.json.JSONObject()
            .put("id", "model-a")
            .put("reasoning", org.json.JSONObject().put("efforts", JSONArray().put(
                org.json.JSONObject().put("id", "high").put("name", "High"),
            )))
        val group = org.json.JSONObject()
            .put("id", "provider-a")
            .put("name", "Provider A")
            .put("models", JSONArray().put(model))
        val catalog = org.json.JSONObject().put("groups", JSONArray().put(group))

        val option = parseNativeModelCatalog(catalog).single()
        assertEquals("provider-a", option.providerId)
        assertEquals("model-a", option.modelId)
        assertEquals(listOf("default" to "模型默认", "high" to "High"), option.efforts)
    }

    @Test
    fun liveAssistantSnapshotRestoresTextAlreadyStreamed() {
        val stream = JSONArray().put(
            org.json.JSONObject().put("type", "text-chunks").put("texts", JSONArray().put("Hello").put(" world")),
        )

        assertEquals("Hello world", assistantStreamSnapshotText(stream))
    }

    @Test
    fun nativeSuggestionsStayVisibleUntilTheNextUserMessage() {
        val suggestionMessage = NativeChatMessage(
            id = "suggestions-1",
            role = "suggestions",
            text = "",
            time = 1,
            suggestions = listOf("继续解释", "举个例子"),
        )
        assertEquals(listOf("继续解释", "举个例子"), latestNativeSuggestions(listOf(suggestionMessage)))
        assertTrue(
            latestNativeSuggestions(
                listOf(suggestionMessage, NativeChatMessage("user-2", "user", "继续解释", 2)),
            ).isEmpty(),
        )
    }

    @Test
    fun nativeSuggestionsAreReadFromToolResultPresentationMetadata() {
        val records = JSONArray().put(
            org.json.JSONObject()
                .put(
                    "event",
                    org.json.JSONObject()
                        .put("seq", 12)
                        .put("time", 1_700_000_000_000L)
                        .put("type", "tool/result")
                        .put(
                            "data",
                            org.json.JSONObject().put(
                                "meta",
                                org.json.JSONObject()
                                    .put("kind", "dsh-pocket/native-chat-suggestions")
                                    .put("suggestions", JSONArray().put("继续解释细节").put("给出一个例子")),
                            ),
                        ),
                ),
        )

        assertEquals(
            listOf("继续解释细节", "给出一个例子"),
            latestNativeSuggestions(parseNativeChatMessages(records)),
        )
    }

    @Test
    fun providerModelLinesCanBeAddedAndRemovedWithoutDuplicates() {
        assertEquals(
            listOf("model-a" to "Model A", "model-b" to ""),
            parseProviderModels("model-a|Model A\nmodel-b"),
        )
        assertNull(parseProviderModels("model-a\nmodel-a"))
        assertNull(parseProviderModels("|missing-id"))
    }
}
