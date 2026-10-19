package com.fioiu8.dshpocket.ui.state

import com.fioiu8.dshpocket.engine.EngineApi
import com.fioiu8.dshpocket.engine.EngineApi.RpcResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/** What the plugin screen needs from the engine. */
interface PluginSource {
    suspend fun listPlugins(): RpcResult
    suspend fun setPluginEnabled(id: String, enabled: Boolean): RpcResult
}

class EnginePluginSource(private val baseUrl: String, private val token: String) : PluginSource {
    private suspend fun call(endpoint: String, args: JSONObject) = EngineApi.rpc(baseUrl, endpoint, args, token)

    override suspend fun listPlugins(): RpcResult = call("pluginManager/listPlugins", EngineApi.emptyArgs())

    override suspend fun setPluginEnabled(id: String, enabled: Boolean): RpcResult =
        call("pluginManager/setPluginEnabled", JSONObject().put("id", id).put("enabled", enabled))
}

/**
 * Owns the plugin inventory and the enable toggle.
 *
 * Retention matches the shipped screen: a failed list keeps the previous rows and only
 * sets [PluginUiState.error]; the error only surfaces when there is nothing to show
 * yet, exactly like the old render branch.
 */
class PluginRepository(private val source: () -> PluginSource?) {

    companion object {
        @Volatile
        private var shared: PluginRepository? = null

        fun get(source: () -> PluginSource?): PluginRepository =
            shared ?: synchronized(this) {
                shared ?: PluginRepository(source).also { shared = it }
            }

        /** Test seam; see the note on `SessionRepository.createForTest`. */
        fun createForTest(source: () -> PluginSource?): PluginRepository = PluginRepository(source)
    }

    private val _state = MutableStateFlow(PluginUiState())
    val state: StateFlow<PluginUiState> = _state.asStateFlow()

    /**
     * Replaces the shipped `load()`: on success rows AND error (echoing
     * `onLoaded = { rows = it; error = null }`), on failure only the error.
     */
    suspend fun reload() {
        val source = source() ?: return
        when (val result = source.listPlugins()) {
            is RpcResult.Ok ->
                _state.update {
                    it.copy(rows = PluginParser.rows(EngineApi.itemsOf(result.value)), error = null)
                }

            is RpcResult.Err ->
                _state.update { it.copy(error = "${result.code}: ${result.message}") }
        }
    }

    /**
     * The toggle RPC. Deliberately does not reload — the screen always follows it with
     * [reload] (pulled through its own busy/refresh handling), and a successful reload
     * thereby clears a toggle error, which is what the shipped ordering produced.
     */
    suspend fun setPluginEnabled(id: String, enabled: Boolean) {
        val source = source() ?: return
        val result = source.setPluginEnabled(id, enabled)
        if (result is RpcResult.Err) {
            _state.update { it.copy(error = "${result.code}: ${result.message}") }
        }
    }
}
