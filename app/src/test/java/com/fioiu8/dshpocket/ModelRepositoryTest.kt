package com.fioiu8.dshpocket

import com.fioiu8.dshpocket.engine.EngineApi.RpcResult
import com.fioiu8.dshpocket.ui.state.ModelRepository
import com.fioiu8.dshpocket.ui.state.ModelSource
import com.fioiu8.dshpocket.ui.state.SettingsSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reload sequence behind the model screen, against a fake engine.
 *
 * Every test here mirrors what the shipped `LaunchedEffect(tick)` did, in particular
 * the fields it deliberately does NOT touch on failure. The repository replicates that
 * retention rather than resetting to empty — the same class of preserved behavior as
 * the archived-set gap in `SessionRepository`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelRepositoryTest {

    private class FakeSource : ModelSource {
        var describeResult: RpcResult = RpcResult.Ok(JSONObject())
        var providersResult: RpcResult = RpcResult.Ok(JSONArray())
        var accountResult: RpcResult = RpcResult.Ok(JSONObject())
        var credentialsResult: RpcResult = RpcResult.Ok(JSONObject())

        val credentialRefsAsked = mutableListOf<List<String>>()

        override suspend fun describe(): RpcResult = describeResult

        override suspend fun listProviders(): RpcResult = providersResult

        override suspend fun accountState(): RpcResult = accountResult

        override suspend fun describeCredentials(refs: List<String>): RpcResult {
            credentialRefsAsked += refs
            return credentialsResult
        }

        override suspend fun updateSettings(namespace: String, patch: JSONObject, expectedRevision: Long?): RpcResult =
            RpcResult.Ok(JSONObject())

        override suspend fun mutate(namespace: String, ops: JSONArray, expectedRevision: Long?): RpcResult =
            RpcResult.Ok(JSONObject())

        override suspend fun setCredential(ref: String, value: String) = RpcResult.Ok(JSONObject())

        override suspend fun unsetCredential(ref: String) = RpcResult.Ok(JSONObject())
    }

    /** One `settings/describe` reply carrying a single namespace. */
    private fun ns(name: String, value: JSONObject, revision: Long? = null) =
        JSONObject().put("ns", name).put("value", value).apply { revision?.let { put("revision", it) } }

    private val gwProviders = JSONObject().put(
        "providers",
        JSONObject().put("gw", JSONObject().put("apiKeyEnv", "GW_API_KEY")),
    )

    @Test
    fun `a successful reload publishes the snapshot`() = runTest {
        val source = FakeSource().apply {
            describeResult = RpcResult.Ok(JSONObject().put("namespaces", JSONArray()
                .put(ns("agent-default-model", JSONObject().put("provider", "qwen").put("model", "qwen3.8"), revision = 7))
                .put(ns("llm-pi-ai", gwProviders, revision = 3))))
            providersResult = RpcResult.Ok(JSONArray().put(JSONObject().put("id", "deepseek-official").put("name", "DeepSeek")))
            credentialsResult = RpcResult.Ok(JSONObject().put("GW_API_KEY", JSONObject().put("configured", true)))
        }

        val repository = ModelRepository.createForTest { source }
        repository.reload()

        val state = repository.state.value
        assertEquals("qwen3.8", state.settings.defaultModel)
        assertEquals(7L, state.settings.modelRevision)
        assertEquals(3L, state.settings.providerRevision)
        assertEquals("gw", state.settings.providers.single().id)
        assertEquals(listOf("deepseek-official" to "DeepSeek"), state.builtinProviders)
        assertEquals(mapOf("GW_API_KEY" to true, "DEEPSEEK_API_KEY" to false), state.keyStates)
        assertNull("a clean reload leaves no load banner", state.error)
    }

    @Test
    fun `a failed describe shows the banner and keeps all previous data`() = runTest {
        val source = FakeSource().apply {
            describeResult = RpcResult.Ok(JSONObject().put("namespaces", JSONArray()
                .put(ns("agent-default-model", JSONObject().put("provider", "p").put("model", "m")))))
        }
        val repository = ModelRepository.createForTest { source }
        repository.reload()

        source.apply {
            describeResult = RpcResult.Err("protocol", "engine said no")
            credentialRefsAsked.clear()
        }
        repository.reload()

        val state = repository.state.value
        assertEquals("protocol: engine said no", state.error)
        assertEquals("last good settings survive under the banner", "m", state.settings.defaultModel)
        assertEquals("no follow-up is attempted after a failed describe", 0, source.credentialRefsAsked.size)
    }

    @Test
    fun `a failing provider list keeps the previous builtin providers`() = runTest {
        val source = FakeSource().apply {
            providersResult = RpcResult.Ok(JSONArray().put(JSONObject().put("id", "deepseek-official").put("name", "DeepSeek")))
        }
        val repository = ModelRepository.createForTest { source }
        repository.reload()
        assertEquals(listOf("deepseek-official" to "DeepSeek"), repository.state.value.builtinProviders)

        source.providersResult = RpcResult.Err("http/500", "list failed")
        repository.reload()

        assertEquals(
            "retained — the same class of gap as the archived session set",
            listOf("deepseek-official" to "DeepSeek"),
            repository.state.value.builtinProviders,
        )
    }

    @Test
    fun `the official-credential decision uses the retained builtin list`() = runTest {
        // First reload succeeds with deepseek-official present; the second one fails the
        // provider list but reports a new provider. The retained list still decides
        // whether the official key ref is asked about, exactly like the shipped screen.
        val source = FakeSource().apply {
            describeResult = RpcResult.Ok(JSONObject().put("namespaces", JSONArray().put(ns("llm-pi-ai", gwProviders))))
            providersResult = RpcResult.Ok(JSONArray().put(JSONObject().put("id", "deepseek-official")))
        }
        val repository = ModelRepository.createForTest { source }
        repository.reload()
        assertEquals(listOf("GW_API_KEY", "DEEPSEEK_API_KEY"), source.credentialRefsAsked.single())

        source.apply {
            providersResult = RpcResult.Err("http/500", "list failed")
            credentialRefsAsked.clear()
        }
        repository.reload()

        assertEquals(listOf("GW_API_KEY", "DEEPSEEK_API_KEY"), source.credentialRefsAsked.single())
    }

    @Test
    fun `a failing credentials describe keeps the previous key states`() = runTest {
        val source = FakeSource().apply {
            describeResult = RpcResult.Ok(JSONObject().put("namespaces", JSONArray().put(ns("llm-pi-ai", gwProviders))))
            credentialsResult = RpcResult.Ok(JSONObject().put("GW_API_KEY", JSONObject().put("configured", true)))
        }
        val repository = ModelRepository.createForTest { source }
        repository.reload()
        assertEquals(mapOf("GW_API_KEY" to true), repository.state.value.keyStates)

        source.credentialsResult = RpcResult.Err("http/500", "describe failed")
        repository.reload()

        assertEquals(mapOf("GW_API_KEY" to true), repository.state.value.keyStates)
    }

    @Test
    fun `empty refs leave the previous key states untouched`() = runTest {
        val source = FakeSource().apply {
            describeResult = RpcResult.Ok(JSONObject().put("namespaces", JSONArray().put(ns("llm-pi-ai", gwProviders))))
            credentialsResult = RpcResult.Ok(JSONObject().put("GW_API_KEY", JSONObject().put("configured", true)))
        }
        val repository = ModelRepository.createForTest { source }
        repository.reload()
        assertEquals(mapOf("GW_API_KEY" to true), repository.state.value.keyStates)

        source.describeResult = RpcResult.Ok(JSONObject().put("namespaces", JSONArray().put(ns("llm-pi-ai", JSONObject().put("providers", JSONObject())))))
        repository.reload()

        assertEquals("no refs, no call, no reset — as shipped", mapOf("GW_API_KEY" to true), repository.state.value.keyStates)
        assertEquals("the second reload issued no credentials call", 1, source.credentialRefsAsked.size)
    }

    @Test
    fun `no providers means no credentials call at all`() = runTest {
        val source = FakeSource()
        val repository = ModelRepository.createForTest { source }

        repository.reload()

        assertTrue(repository.state.value.keyStates.isEmpty())
        assertEquals(0, source.credentialRefsAsked.size)
    }

    @Test
    fun `account state is overwritten unconditionally, including to null on failure`() = runTest {
        val source = FakeSource()
        val repository = ModelRepository.createForTest { source }
        repository.reload()
        assertNotNull(repository.state.value.accountView)

        source.accountResult = RpcResult.Err("http/500", "account failed")
        repository.reload()

        assertNull(repository.state.value.accountView)
    }

    @Test
    fun `the error banner is cleared and re-set at the start of every reload`() = runTest {
        val source = FakeSource().apply { describeResult = RpcResult.Err("protocol", "first failure") }
        val repository = ModelRepository.createForTest { source }
        repository.reload()
        assertEquals("protocol: first failure", repository.state.value.error)

        source.describeResult = RpcResult.Err("protocol", "second failure")
        repository.reload()

        assertEquals("only the latest failure shows", "protocol: second failure", repository.state.value.error)
    }

    @Test
    fun `a successful reload after a failed one clears the banner`() = runTest {
        val source = FakeSource().apply {
            describeResult = RpcResult.Ok(JSONObject().put("namespaces", JSONArray()
                .put(ns("agent-default-model", JSONObject().put("provider", "p").put("model", "m")))))
        }
        val repository = ModelRepository.createForTest { source }
        repository.reload()
        assertEquals("m", repository.state.value.settings.defaultModel)

        source.describeResult = RpcResult.Err("http/500", "gone")
        repository.reload()
        assertEquals("http/500: gone", repository.state.value.error)

        source.describeResult = RpcResult.Ok(JSONObject().put("namespaces", JSONArray()
            .put(ns("agent-default-model", JSONObject().put("provider", "p2").put("model", "m2")))))
        repository.reload()

        val state = repository.state.value
        assertNull(state.error)
        assertEquals("m2", state.settings.defaultModel)
    }

    @Test
    fun `a missing engine leaves the repository untouched`() = runTest {
        val source = FakeSource()
        val repository = ModelRepository.createForTest { null }

        repository.reload()

        assertEquals(SettingsSnapshot(), repository.state.value.settings)
        assertTrue(repository.state.value.keyStates.isEmpty())
        assertNull(repository.state.value.error)
        assertEquals(0, source.credentialRefsAsked.size)
    }

    @Test
    fun `the default official key ref is used until llm-deepseek reports otherwise`() = runTest {
        val source = FakeSource().apply {
            describeResult = RpcResult.Ok(
                JSONObject().put("namespaces", JSONArray()
                    .put(ns("llm-pi-ai", gwProviders))
                    .put(ns("llm-deepseek", JSONObject().put("apiKeyEnv", "CUSTOM_DEEPSEEK")))),
            )
            providersResult = RpcResult.Ok(JSONArray().put(JSONObject().put("id", "deepseek-official")))
        }
        val repository = ModelRepository.createForTest { source }
        repository.reload()

        assertEquals("CUSTOM_DEEPSEEK", repository.state.value.settings.officialApiKeyRef)
        assertEquals(listOf("GW_API_KEY", "CUSTOM_DEEPSEEK"), source.credentialRefsAsked.single())
    }

    @Test
    fun `empty describe yields defaults`() = runTest {
        val repository = ModelRepository.createForTest { FakeSource() }
        repository.reload()

        assertEquals(SettingsSnapshot(), repository.state.value.settings)
        assertNull(repository.state.value.error)
    }
}
