package com.fioiu8.dshpocket

import com.fioiu8.dshpocket.ui.state.archivedIdsFromValues
import com.fioiu8.dshpocket.ui.NativeChatMessage
import com.fioiu8.dshpocket.ui.latestNativeSuggestions
import com.fioiu8.dshpocket.ui.isNativeSystemPrompt
import com.fioiu8.dshpocket.ui.parseNativeChatMessages
import com.fioiu8.dshpocket.ui.parseNativeModelCatalog
import com.fioiu8.dshpocket.ui.markdownToAnnotatedString
import com.fioiu8.dshpocket.ui.assistantStreamSnapshotText
import com.fioiu8.dshpocket.ui.state.parseProviderModels
import com.fioiu8.dshpocket.ui.localized
import com.fioiu8.dshpocket.capability.AgentBriefing
import com.fioiu8.dshpocket.capability.CapabilitySnapshot
import com.fioiu8.dshpocket.capability.CapabilityState
import com.fioiu8.dshpocket.capability.CompanionStatus
import com.fioiu8.dshpocket.capability.isInstalled
import com.fioiu8.dshpocket.capability.ExecutionBackend
import com.fioiu8.dshpocket.capability.ProcessExecutionCapability
import org.json.JSONArray
import org.json.JSONObject
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

    @Test
    fun pluginLocalizedTextFollowsConfiguredLanguageWithFallback() {
        val value = JSONObject().put("zh-CN", "中文标题").put("en", "English title")
        assertEquals("English title", localized(value, "en-US"))
        assertEquals("中文标题", localized(value, "zh-CN"))
        assertEquals("中文标题", localized(value, "fr-FR"))
    }

    @Test
    fun companionLifecycleStatusesRemainInstalledAfterInstallation() {
        assertFalse(CompanionStatus.NOT_INSTALLED.isInstalled)
        assertFalse(CompanionStatus.UNSUPPORTED.isInstalled)
        assertTrue(CompanionStatus.INSTALLED.isInstalled)
        assertTrue(CompanionStatus.AUTHORIZED.isInstalled)
        assertTrue(CompanionStatus.RUNNING.isInstalled)
        assertTrue(CompanionStatus.BRIDGE_CONNECTED.isInstalled)
    }

    @Test
    fun managedInstructionsUpdatePreservesUserOwnedContent() {
        val previous = "User global instructions\n\n<!-- DSH Pocket managed capabilities: start -->\nold generated facts\n<!-- DSH Pocket managed capabilities: end -->\n\nUser-specific footer"
        val replacement = "<!-- DSH Pocket managed capabilities: start -->\nnew generated facts\n<!-- DSH Pocket managed capabilities: end -->"

        val result = AgentBriefing.mergeManagedInstructions(previous, replacement)

        assertTrue(result.contains("User global instructions\n\n"))
        assertTrue(result.contains("new generated facts"))
        assertFalse(result.contains("old generated facts"))
        // User text is preserved byte for byte. The app rewrites AGENTS.md on every boot,
        // so normalising the author's own spacing here would mean editing their file on
        // every upgrade — the opposite of what "preserving user instructions" means.
        assertTrue(result.endsWith("\n\nUser-specific footer"))
    }

    @Test
    fun managedInstructionsRemoveDuplicateAndOrphanMarkers() {
        val existing = "before\n<!-- DSH Pocket managed capabilities: end -->\n" +
            "<!-- DSH Pocket managed capabilities: start -->\none\n<!-- DSH Pocket managed capabilities: end -->\n" +
            "middle\n<!-- DSH Pocket managed capabilities: start -->\norphan text\n" +
            "<!-- DSH Pocket managed capabilities: start -->\ntwo\n<!-- DSH Pocket managed capabilities: end -->\ntail"
        val replacement = "<!-- DSH Pocket managed capabilities: start -->\ncurrent\n<!-- DSH Pocket managed capabilities: end -->"

        val result = AgentBriefing.mergeManagedInstructions(existing, replacement)

        assertTrue(result.contains("before\n\n"))
        assertTrue(result.contains("current"))
        // Dropping the orphan start marker leaves the line it sat on as a blank line.
        // The text the user wrote is still there; only the marker disappeared.
        assertTrue(result.contains("middle\n\norphan text\n"))
        assertTrue(result.endsWith("tail"))
        assertFalse(result.contains("one"))
        assertFalse(result.contains("two"))
        assertEquals(1, Regex("DSH Pocket managed capabilities: start").findAll(result).count())
        assertEquals(1, Regex("DSH Pocket managed capabilities: end").findAll(result).count())
    }

    @Test
    fun managedBlockReplacementSeparatesFollowingTextWhenSourceHasNoNewline() {
        val existing = "before<!-- DSH Pocket managed capabilities: start -->old<!-- DSH Pocket managed capabilities: end -->after"
        val replacement = "<!-- DSH Pocket managed capabilities: start -->current<!-- DSH Pocket managed capabilities: end -->"

        val result = AgentBriefing.mergeManagedInstructions(existing, replacement)

        assertTrue(result.contains("<!-- DSH Pocket managed capabilities: end -->\nafter"))
    }

    @Test
    fun capabilitiesJsonEscapesWorkspaceAndReflectsExecutionSnapshot() {
        val workspace = "C:\\Users\\name\\a\"b\nworkspace"
        val snapshot = CapabilitySnapshot(
            workspace = workspace,
            allFiles = CapabilityState.GRANTED,
            termux = CompanionStatus.RUNNING,
            shizuku = CompanionStatus.NOT_INSTALLED,
            processExecution = ProcessExecutionCapability.Available(ExecutionBackend.TERMUX),
        )

        val json = org.json.JSONObject(AgentBriefing.capabilitiesJson(snapshot))

        assertEquals(workspace, json.getString("workspace"))
        assertEquals(1, json.getInt("schemaVersion"))
        assertEquals("running", json.getString("termuxStatus"))
        assertTrue(json.getBoolean("termuxInstalled"))
        assertTrue(json.getBoolean("processExecution"))
        assertEquals("termux", json.getString("processExecutionBackendKind"))
    }
}
