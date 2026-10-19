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
 * Owns the model configuration read path.
 *
 * Writes stay in the screen: their optimistic-lock arguments, local form drafts and
 * per-step busy handling are UI concerns, and the shipped error messages are set and
 * cleared at explicit user-initiated points there — never by an automatic reload.
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

    /**
     * Reload, replicating the shipped LaunchedEffect(tick) read path line for line.
     *
     * The fields that are NOT updated on failure are significant, and they match the
     * original:
     * - a failed `settings/describe` sets [ModelUiState.error] and keeps all data;
     * - a failed `llm/listProviders` or `credentials/describe` keeps the previous
     *   builtin list / key states (same retention class as the archived-set gap);
     * - empty refs leave the key states untouched;
     * - `account/getState` is the one call that overwrites unconditionally, so a
     *   failure nulls it (`as?` chain in the original).
     * `error` itself is cleared at the start of every reload; it is the only field
     * that is. Write-path messages (actionError) live in the screen and are cleared
     * at explicit user-initiated points there, never by an automatic reload.
     */
    suspend fun reload() {
        val source = source() ?: return
        _state.update { it.copy(error = null) }

        val describe = source.describe()
        // RpcResult is sealed Ok/Err, so this cast always succeeds; the screen's
        // `?: unknown` fallback for the same situation is dead code.
        val describeErr = describe as? RpcResult.Err
        if (describeErr != null) {
            _state.update { it.copy(error = "${describeErr.code}: ${describeErr.message}") }
            return
        }

        // The Err case returned above; RpcResult is sealed Ok/Err, so this lookup always
        // holds. The original's `?: JSONArray()` fallback is covered by the parser, which
        // treats a null namespace array as "no namespaces".
        val namespaces = ((describe as? RpcResult.Ok)?.value as? JSONObject)?.optJSONArray("namespaces")
        val settings = ModelParser.settings(namespaces)
        _state.update { it.copy(settings = settings) }

        // A failing provider list keeps the previous one; the official-provider check
        // below then runs against that retained list, exactly like the original.
        (source.listProviders() as? RpcResult.Ok)?.let { ok ->
            val builtin = ModelParser.providerList(EngineApi.itemsOf(ok.value))
            _state.update { it.copy(builtinProviders = builtin) }
        }

        val account = (source.accountState() as? RpcResult.Ok)?.value as? JSONObject
        _state.update { it.copy(accountView = account) }

        val refs = ModelParser.keyRefs(
            settings.providers,
            settings.officialApiKeyRef,
            _state.value.builtinProviders,
        )
        if (refs.isNotEmpty()) {
            (source.describeCredentials(refs) as? RpcResult.Ok)?.let { ok ->
                val keyStates = ModelParser.keyStates(ok.value, refs)
                _state.update { it.copy(keyStates = keyStates) }
            }
        }
    }

    /**
     * `account/getState` on its own — what the login-poll loop and the login, cancel and
     * sign-out actions do. The one unconditional overwrite: a failed call nulls the
     * account view, exactly like the shipped `as?` chain.
     */
    suspend fun refreshAccount() {
        val source = source() ?: return
        val account = (source.accountState() as? RpcResult.Ok)?.value as? JSONObject
        _state.update { it.copy(accountView = account) }
    }

    /** Publishes an account state already in hand (an RPC reply the screen acts on). */
    fun publishAccount(value: Any?) {
        _state.update { it.copy(accountView = value as? JSONObject) }
    }
}
