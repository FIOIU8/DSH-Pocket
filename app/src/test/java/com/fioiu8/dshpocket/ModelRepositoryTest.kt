package com.fioiu8.dshpocket

import com.fioiu8.dshpocket.engine.EngineApi.RpcResult
import com.fioiu8.dshpocket.ui.state.ModelLoadState
import com.fioiu8.dshpocket.ui.state.ModelRepository
import com.fioiu8.dshpocket.ui.state.ModelSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reload sequence behind the model screen, against a fake engine.
 *
 * Locks in two things that were easy to break and hard to see: the optimistic-lock
 * revision is threaded through writes, and a failing follow-up degrades that one panel
 * instead of failing the whole screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelRepositoryTest {

    private class FakeSource : ModelSource {
        var describeResult: RpcResult = RpcResult.Ok(JSONObject())
        var providersResult: RpcResult = RpcResult.Ok(JSONArray())
        var accountResult: RpcResult = RpcResult.Ok(JSONObject())
        var credentialsResult: RpcResult = RpcResult.Ok(JSONObject())

        val describeCalls = mutableListOf<Pair<String, Long?>>()
        val credentialRefsAsked = mutableListOf<List<String>>()

        override suspend fun describe(): RpcResult = describeResult

        override suspend fun listProviders(): RpcResult = providersResult

        override suspend fun accountState(): RpcResult = accountResult

        override suspend fun describeCredentials(refs: List<String>): RpcResult {
            credentialRefsAsked += refs
            return credentialsResult
        }

        override suspend fun updateSettings(namespace: String, patch: JSONObject, expectedRevision: Long?): RpcResult {
            describeCalls += namespace to expectedRevision
            return RpcResult.Ok(JSONObject())
        }

        override suspend fun mutate(namespace: String, ops: JSONArray, expectedRevision: Long?): RpcResult {
            describeCalls += namespace to expectedRevision
            return RpcResult.Ok(JSONObject())
        }

        override suspend fun setCredential(ref: String, value: String) = RpcResult.Ok(JSONObject())

        override suspend fun unsetCredential(ref: String) = RpcResult.Ok(JSONObject())
    }

    private fun describeWith(vararg namespaces: JSONObject) = RpcResult.Ok(
        JSONObject().put("namespaces", JSONArray().apply { namespaces.forEach { put(it) } }),
    )

    private fun ns(name: String, value: JSONObject, revision: Long? = null) =
        JSONObject().put("ns", name).put("value", value).apply { revision?.let { put("revision", it) } }

    @Test
    fun `a successful reload publishes the snapshot`() = runTest {
        val source = FakeSource().apply {
            describeResult = describeWith(
                ns("agent-default-model", JSONObject().put("provider", "qwen").put("model", "qwen3.8"), revision = 7),
                ns("llm-pi-ai", JSONObject().put("providers", JSONObject().put(
                    "gw", JSONObject().put("apiKeyEnv", "GW_API_KEY"),
                )), revision = 3),
            )
            providersResult = RpcResult.Ok(JSONArray().put(JSONObject().put("id", "deepseek-official").put("name", "DeepSeek")))
            credentialsResult = RpcResult.Ok(JSONObject().put("GW_API_KEY", JSONObject().put("configured", true)))
        }

        val repository = ModelRepository.createForTest { source }
        repository.reload()

        val state = repository.state.value
        assertTrue(state.load is ModelLoadState.Ready)
        assertEquals("qwen3.8", state.settings.defaultModel)
        assertEquals(7L, state.settings.modelRevision)
        assertEquals(3L, state.settings.providerRevision)
        assertEquals("gw", state.settings.providers.single().id)
        assertEquals(listOf("deepseek-official" to "DeepSeek"), state.builtinProviders)
        assertEquals(mapOf("GW_API_KEY" to true, "DEEPSEEK_API_KEY" to false), state.keyStates)
        assertEquals(false, state.busy)
    }

    @Test
    fun `the official credential is only asked about when its provider exists`() = runTest {
        val withOfficial = FakeSource().apply {
            describeResult = describeWith(
                ns("llm-pi-ai", JSONObject().put("providers", JSONObject().put(
                    "gw", JSONObject().put("apiKeyEnv", "GW_API_KEY"),
                ))),
            )
            providersResult = RpcResult.Ok(JSONArray().put(JSONObject().put("id", "deepseek-official")))
        }
        ModelRepository.createForTest { withOfficial }.reload()
        assertEquals(listOf(listOf("GW_API_KEY", "DEEPSEEK_API_KEY")), withOfficial.credentialRefsAsked)

        val withoutOfficial = FakeSource().apply {
            describeResult = describeWith(
                ns("llm-pi-ai", JSONObject().put("providers", JSONObject().put(
                    "gw", JSONObject().put("apiKeyEnv", "GW_API_KEY"),
                ))),
            )
        }
        ModelRepository.createForTest { withoutOfficial }.reload()
        assertEquals(listOf(listOf("GW_API_KEY")), withoutOfficial.credentialRefsAsked)
    }

    @Test
    fun `a failing follow-up degrades one panel instead of the screen`() = runTest {
        val source = FakeSource().apply {
            describeResult = describeWith(ns("llm-pi-ai", JSONObject().put("providers", JSONObject().put(
                "gw", JSONObject().put("apiKeyEnv", "GW_API_KEY"),
            ))))
            providersResult = RpcResult.Err("http/500", "list failed")
            credentialsResult = RpcResult.Err("http/500", "describe failed")
        }

        val repository = ModelRepository.createForTest { source }
        repository.reload()

        val state = repository.state.value
        assertTrue("the screen still loads", state.load is ModelLoadState.Ready)
        assertEquals("gw", state.settings.providers.single().id)
        assertEquals(emptyList<Pair<String, String>>(), state.builtinProviders)
        assertEquals(emptyMap<String, Boolean>(), state.keyStates)
    }

    @Test
    fun `a failing describe fails the screen and stops there`() = runTest {
        val source = FakeSource().apply {
            describeResult = RpcResult.Err("protocol", "engine said no")
        }

        val repository = ModelRepository.createForTest { source }
        repository.reload()

        val load = repository.state.value.load
        assertTrue(load is ModelLoadState.Failed)
        assertEquals("protocol: engine said no", (load as ModelLoadState.Failed).message)
        assertEquals(false, repository.state.value.busy)
        assertEquals("no follow-up is attempted after a failed describe", 0, source.credentialRefsAsked.size)
    }

    @Test
    fun `no credentials means no credentials call at all`() = runTest {
        val source = FakeSource().apply {
            describeResult = describeWith(ns("llm-pi-ai", JSONObject().put("providers", JSONObject())))
        }

        ModelRepository.createForTest { source }.reload()

        assertEquals(0, source.credentialRefsAsked.size)
    }

    @Test
    fun `a missing engine leaves the repository untouched`() = runTest {
        val source = FakeSource()
        val repository = ModelRepository.createForTest { null }

        repository.reload()

        assertEquals(ModelLoadState.Loading, repository.state.value.load)
        assertEquals(0, source.credentialRefsAsked.size)
    }

    @Test
    fun `action and credential errors are transient and cleared by a reload`() = runTest {
        val repository = ModelRepository.createForTest { FakeSource() }

        repository.setActionError("save failed")
        repository.setCredentialError("key rejected")
        assertEquals("save failed", repository.state.value.actionError)

        repository.reload()
        assertEquals(null, repository.state.value.actionError)
        assertEquals(null, repository.state.value.credentialError)
    }
}
