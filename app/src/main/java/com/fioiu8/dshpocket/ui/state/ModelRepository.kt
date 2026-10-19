package com.fioiu8.dshpocket.ui.state

import com.fioiu8.dshpocket.engine.EngineApi
import com.fioiu8.dshpocket.engine.EngineApi.RpcResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the model screen needs from the engine, and nothing about how it is drawn.
 */
interface ModelSource {
    suspend fun describe(): RpcResult
    suspend fun listProviders(): RpcResult
    suspend fun accountState(): RpcResult
    suspend fun describeCredentials(refs: List<String>): RpcResult
    suspend fun updateSettings(namespace: String, patch: JSONObject, expectedRevision: Long?): RpcResult
    suspend fun mutate(namespace: String, ops: JSONArray, expectedRevision: Long?): RpcResult
    suspend fun setCredential(ref: String, value: String): RpcResult
    suspend fun unsetCredential(ref: String): RpcResult
}

class EngineModelSource(private val baseUrl: String, private val token: String) : ModelSource {
    private suspend fun call(endpoint: String, args: JSONObject) = EngineApi.rpc(baseUrl, endpoint, args, token)

    override suspend fun describe(): RpcResult = call("settings/describe", EngineApi.emptyArgs())

    override suspend fun listProviders(): RpcResult = call("llm/listProviders", EngineApi.emptyArgs())

    override suspend fun accountState(): RpcResult = call("account/getState", EngineApi.emptyArgs())

    override suspend fun describeCredentials(refs: List<String>): RpcResult =
        call("credentials/describe", JSONObject().put("refs", JSONArray().apply { refs.forEach { put(it) } }))

    override suspend fun updateSettings(namespace: String, patch: JSONObject, expectedRevision: Long?): RpcResult =
        call(
            "settings/update",
            JSONObject().put("ns", namespace).put("patch", patch)
                .apply { expectedRevision?.let { put("expectedRevision", it) } },
        )

    override suspend fun mutate(namespace: String, ops: JSONArray, expectedRevision: Long?): RpcResult =
        call(
            "settings/mutate",
            JSONObject().put("ns", namespace).put("ops", ops)
                .apply { expectedRevision?.let { put("expectedRevision", it) } },
        )

    override suspend fun setCredential(ref: String, value: String): RpcResult =
        call("credentials/set", JSONObject().put("ref", ref).put("value", value))

    override suspend fun unsetCredential(ref: String): RpcResult =
        call("credentials/unset", JSONObject().put("ref", ref))
}

/**
 * Owns model configuration and the reload that produces it.
 *
 * The screen used to run this as a `LaunchedEffect(tick)` that wrote into a dozen local
 * `mutableStateOf`s. Everything below is the same sequence, moved somewhere it can be
 * observed and tested.
 */
class ModelRepository(private val source: () -> ModelSource?) {

    companion object {
        @Volatile
        private var shared: ModelRepository? = null

        fun get(source: () -> ModelSource?): ModelRepository =
            shared ?: synchronized(this) {
                shared ?: ModelRepository(source).also { shared = it }
            }

        /** Test seam; see the note on `SessionRepository.createForTest`. */
        fun createForTest(source: () -> ModelSource?): ModelRepository = ModelRepository(source)
    }

    private val _state = MutableStateFlow(ModelUiState())
    val state: StateFlow<ModelUiState> = _state.asStateFlow()

    /** Transient message shown after a write; cleared by the next [reload]. */
    fun setActionError(message: String?) = _state.update { it.copy(actionError = message) }

    fun setCredentialError(message: String?) = _state.update { it.copy(credentialError = message) }

    /**
     * Reload: describe, then the three follow-ups it feeds.
     *
     * Each follow-up is independent. A failure of `llm/listProviders` or
     * `credentials/describe` leaves that part empty rather than failing the whole screen,
     * which is how it behaved before this moved.
     */
    suspend fun reload() {
        val source = source() ?: return
        _state.update { it.copy(busy = true, actionError = null, credentialError = null) }

        val describe = source.describe()
        if (describe !is RpcResult.Ok) {
            _state.update {
                it.copy(
                    busy = false,
                    load = ModelLoadState.Failed((describe as RpcResult.Err).describe()),
                )
            }
            return
        }

        val namespaces = (describe.value as? JSONObject)?.optJSONArray("namespaces")
        val settings = ModelParser.settings(namespaces)

        val builtin = (source.listProviders() as? RpcResult.Ok)
            ?.let { ModelParser.providerList(EngineApi.itemsOf(it.value)) }
            .orEmpty()

        val account = (source.accountState() as? RpcResult.Ok)?.value as? JSONObject

        val refs = ModelParser.keyRefs(settings.providers, settings.officialApiKeyRef, builtin)
        val keyStates = if (refs.isEmpty()) {
            emptyMap()
        } else {
            (source.describeCredentials(refs) as? RpcResult.Ok)
                ?.let { ModelParser.keyStates(it.value, refs) }
                .orEmpty()
        }

        _state.update {
            it.copy(
                busy = false,
                load = ModelLoadState.Ready(settings),
                settings = settings,
                builtinProviders = builtin,
                keyStates = keyStates,
                accountView = account,
            )
        }
    }

    private fun RpcResult.Err.describe(): String = "$code: $message"
}
