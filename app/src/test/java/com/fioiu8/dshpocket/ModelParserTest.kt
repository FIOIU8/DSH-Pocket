package com.fioiu8.dshpocket

import com.fioiu8.dshpocket.ui.state.ModelParser
import com.fioiu8.dshpocket.ui.state.ProviderInfo
import com.fioiu8.dshpocket.ui.state.SettingsSnapshot
import com.fioiu8.dshpocket.ui.state.encodeProviderModels
import com.fioiu8.dshpocket.ui.state.isHttpEndpoint
import com.fioiu8.dshpocket.ui.state.isValidProviderId
import com.fioiu8.dshpocket.ui.state.parseProviderHeaders
import com.fioiu8.dshpocket.ui.state.parseProviderModels
import com.fioiu8.dshpocket.ui.state.providerCredentialRef
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parsing and validation rules behind the model screen.
 *
 * These all used to sit inside a 1,400-line composable where nothing could reach them.
 * They are pure JSON and string handling, so they are tested directly rather than
 * inferred from the screen.
 */
class ModelParserTest {

    private fun ns(name: String, value: JSONObject, revision: Long? = null) =
        JSONObject().put("ns", name).put("value", value).apply { revision?.let { put("revision", it) } }

    // ---- settings/describe ---------------------------------------------------------------

    @Test
    fun `describe reads the default model and its revision`() {
        val namespaces = JSONArray().put(
            ns("agent-default-model", JSONObject().put("provider", "qwen").put("model", "qwen3.8"), revision = 7),
        )

        val snapshot = ModelParser.settings(namespaces)

        assertEquals("qwen", snapshot.defaultProvider)
        assertEquals("qwen3.8", snapshot.defaultModel)
        assertEquals(7L, snapshot.modelRevision)
        assertNull("an absent revision stays null", SettingsSnapshot().modelRevision)
    }

    @Test
    fun `describe reads providers with their models and headers`() {
        val providers = JSONObject().put(
            "gateway",
            JSONObject()
                .put("displayName", "My Gateway")
                .put("baseURL", "https://api.example.com/v1")
                .put("api", "openai-responses")
                .put("apiKeyEnv", "GATEWAY_API_KEY")
                .put(
                    "models",
                    JSONArray()
                        .put(JSONObject().put("id", "model-a").put("name", "Model A"))
                        .put(JSONObject().put("id", "model-b").put("name", "")),
                )
                .put("headers", JSONObject().put("X-Organization", "team-a")),
        )

        val snapshot = ModelParser.settings(JSONArray().put(ns("llm-pi-ai", JSONObject().put("providers", providers))))

        val provider = snapshot.providers.single()
        assertEquals("gateway", provider.id)
        assertEquals("My Gateway", provider.displayName)
        assertEquals("openai-responses", provider.api)
        assertEquals("GATEWAY_API_KEY", provider.apiKeyEnv)
        assertEquals(listOf("model-a" to "Model A", "model-b" to ""), provider.models)
        assertEquals("team-a", provider.headers["X-Organization"])
        assertEquals("raw model config is kept for the editor", "Model A", provider.modelDetails["model-a"]?.optString("name"))
    }

    @Test
    fun `a provider with no display name falls back to its id and default api`() {
        val providers = JSONObject().put("bare", JSONObject())

        val snapshot = ModelParser.settings(JSONArray().put(ns("llm-pi-ai", JSONObject().put("providers", providers))))

        val provider = snapshot.providers.single()
        assertEquals("bare", provider.displayName)
        assertEquals(ModelParser.DEFAULT_API, provider.api)
        assertNull(provider.baseURL)
        assertNull(provider.apiKeyEnv)
        assertEquals(emptyList<Pair<String, String>>(), provider.models)
        assertEquals(emptyMap<String, String>(), provider.headers)
    }

    @Test
    fun `the official key ref comes from llm-deepseek and defaults when absent`() {
        val withRef = ModelParser.settings(
            JSONArray().put(ns("llm-deepseek", JSONObject().put("apiKeyEnv", "CUSTOM_DEEPSEEK"))),
        )
        assertEquals("CUSTOM_DEEPSEEK", withRef.officialApiKeyRef)

        val withoutRef = ModelParser.settings(JSONArray().put(ns("llm-deepseek", JSONObject())))
        assertEquals(SettingsSnapshot.DEFAULT_OFFICIAL_KEY_REF, withoutRef.officialApiKeyRef)

        val noNamespace = ModelParser.settings(JSONArray())
        assertEquals(SettingsSnapshot.DEFAULT_OFFICIAL_KEY_REF, noNamespace.officialApiKeyRef)
    }

    @Test
    fun `unknown namespaces are ignored rather than failing the parse`() {
        val namespaces = JSONArray()
            .put(ns("something-else", JSONObject().put("x", 1)))
            .put(ns("agent-default-model", JSONObject().put("provider", "p").put("model", "m")))

        assertEquals("m", ModelParser.settings(namespaces).defaultModel)
    }

    @Test
    fun `a null namespace array yields defaults, not a crash`() {
        val snapshot = ModelParser.settings(null)
        assertEquals(SettingsSnapshot(), snapshot)
    }

    // ---- provider list -------------------------------------------------------------------

    @Test
    fun `provider list skips entries without an id`() {
        val items = JSONArray()
            .put(JSONObject().put("id", "deepseek-official").put("name", "DeepSeek"))
            .put(JSONObject().put("name", "nameless"))

        assertEquals(listOf("deepseek-official" to "DeepSeek"), ModelParser.providerList(items))
    }

    // ---- credential refs -----------------------------------------------------------------

    @Test
    fun `key refs cover every provider plus the official slot when available`() {
        val providers = listOf(
            ProviderInfo("a", "A", null, ModelParser.DEFAULT_API, "A_API_KEY", emptyList(), emptyMap(), emptyMap()),
            ProviderInfo("b", "B", null, ModelParser.DEFAULT_API, null, emptyList(), emptyMap(), emptyMap()),
        )

        val withoutOfficial = ModelParser.keyRefs(providers, "DEEPSEEK_API_KEY", emptyList())
        assertEquals(listOf("A_API_KEY"), withoutOfficial)

        val withOfficial = ModelParser.keyRefs(providers, "DEEPSEEK_API_KEY", listOf("deepseek-official" to "DeepSeek"))
        assertEquals(listOf("A_API_KEY", "DEEPSEEK_API_KEY"), withOfficial)
    }

    @Test
    fun `key refs are de-duplicated`() {
        val providers = listOf(
            ProviderInfo("a", "A", null, ModelParser.DEFAULT_API, "SHARED", emptyList(), emptyMap(), emptyMap()),
            ProviderInfo("b", "B", null, ModelParser.DEFAULT_API, "SHARED", emptyList(), emptyMap(), emptyMap()),
        )
        assertEquals(listOf("SHARED"), ModelParser.keyRefs(providers, "SHARED", emptyList()))
    }

    @Test
    fun `key states treat a missing entry as not configured`() {
        val reply = JSONObject().put("A_API_KEY", JSONObject().put("configured", true))

        assertEquals(mapOf("A_API_KEY" to true, "B_API_KEY" to false), ModelParser.keyStates(reply, listOf("A_API_KEY", "B_API_KEY")))
        assertEquals(emptyMap<String, Boolean>(), ModelParser.keyStates(null, listOf("A_API_KEY")))
    }

    // ---- provider id ---------------------------------------------------------------------

    @Test
    fun `provider ids are lower case and dash separated`() {
        assertTrue(isValidProviderId("gateway"))
        assertTrue(isValidProviderId("my-gateway-2"))
        assertFalse(isValidProviderId("Gateway"))
        assertFalse(isValidProviderId("2gateway"))
        assertFalse(isValidProviderId("my_gateway"))
        assertFalse(isValidProviderId(""))
    }

    @Test
    fun `a credential ref is the upper-cased id with separators collapsed`() {
        assertEquals("MY_GATEWAY_API_KEY", providerCredentialRef("my-gateway"))
        assertEquals("MY_GATEWAY_API_KEY", providerCredentialRef("my.gateway"))
    }

    // ---- model and header text ------------------------------------------------------------

    @Test
    fun `models parse as id or id-pipe-name and reject duplicates`() {
        assertEquals(listOf("a" to "A", "b" to ""), parseProviderModels("a|A\nb"))
        assertNull(parseProviderModels("a\n a"))
        assertNull(parseProviderModels("|name"))
    }

    @Test
    fun `models round-trip through the encoder`() {
        val models = listOf("a" to "A", "b" to "")
        assertEquals(models, parseProviderModels(encodeProviderModels(models)))
    }

    @Test
    fun `headers parse as name-colon-value and reject duplicates or bad names`() {
        assertEquals(mapOf("X-Organization" to "team-a"), parseProviderHeaders("X-Organization: team-a"))
        assertEquals(mapOf("X-A" to "team-a"), parseProviderHeaders("X-A:team-a"))
        assertNull(parseProviderHeaders("no separator"))
        assertNull(parseProviderHeaders("X-A: value\nx-a: other"))
        assertNull(parseProviderHeaders("X A: value"))
        assertNull(parseProviderHeaders("X-A:   "))
    }

    @Test
    fun `only http and https endpoints with a host are accepted`() {
        assertTrue(isHttpEndpoint("https://api.example.com/v1"))
        assertTrue(isHttpEndpoint("http://127.0.0.1:3000/v1"))
        assertFalse(isHttpEndpoint("ftp://api.example.com"))
        assertFalse(isHttpEndpoint("api.example.com"))
        assertFalse(isHttpEndpoint("https://"))
        assertFalse(isHttpEndpoint(""))
    }
}
