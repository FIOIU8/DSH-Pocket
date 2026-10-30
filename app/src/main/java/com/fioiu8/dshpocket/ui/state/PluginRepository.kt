package com.fioiu8.dshpocket.ui.state

import com.fioiu8.dshpocket.data.session.SessionRepository

import com.fioiu8.dshpocket.engine.EngineApi
import com.fioiu8.dshpocket.engine.EngineApi.RpcResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.util.UUID

/** What the plugin screen needs from the engine. */
interface PluginSource {
    suspend fun listPlugins(): RpcResult
    suspend fun setPluginEnabled(id: String, enabled: Boolean): RpcResult
    suspend fun listBundles(): RpcResult
    suspend fun installBundle(spec: String, requestId: String): RpcResult
    suspend fun cancelInstall(requestId: String): RpcResult
    suspend fun removeBundle(name: String): RpcResult
}

class EnginePluginSource(private val baseUrl: String, private val token: String) : PluginSource {
    private suspend fun call(endpoint: String, args: JSONObject) = EngineApi.rpc(baseUrl, endpoint, args, token)

    // Package operations run pnpm over the network; the ordinary RPC budget would cut
    // them off mid-run, which is the one outcome worse than a slow install.
    private suspend fun callPackage(endpoint: String, args: JSONObject) =
        EngineApi.rpc(baseUrl, endpoint, args, token, EngineApi.PACKAGE_READ_TIMEOUT_MS)

    override suspend fun listPlugins(): RpcResult = call("pluginManager/listPlugins", EngineApi.emptyArgs())

    override suspend fun setPluginEnabled(id: String, enabled: Boolean): RpcResult =
        call("pluginManager/setPluginEnabled", JSONObject().put("id", id).put("enabled", enabled))

    override suspend fun listBundles(): RpcResult = call("pluginManager/listBundles", EngineApi.emptyArgs())

    override suspend fun installBundle(spec: String, requestId: String): RpcResult =
        callPackage(
            "pluginManager/installBundle",
            JSONObject().put("spec", spec).put(
                "options",
                // The id the cancel RPC addresses; the engine also uses it to fence
                // concurrent installs.
                JSONObject().put("requestId", requestId),
            ),
        )

    override suspend fun cancelInstall(requestId: String): RpcResult =
        call("pluginManager/cancelInstall", JSONObject().put("requestId", requestId))

    override suspend fun removeBundle(name: String): RpcResult =
        callPackage("pluginManager/removeBundle", JSONObject().put("name", name))
}

/**
 * Owns the plugin inventory, the bundle list, and the install/remove lifecycle.
 *
 * Retention matches the shipped screen: a failed list keeps the previous rows and only
 * sets [PluginUiState.error]; the error only surfaces when there is nothing to show
 * yet, exactly like the old render branch. The same retention applies to bundles.
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
     *
     * The bundle list rides along on the same pass. A failed `listBundles` keeps the
     * previous bundles and does not touch the plugin rows — one endpoint failing must
     * not blank the other's data.
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
        when (val result = source.listBundles()) {
            is RpcResult.Ok ->
                _state.update { it.copy(bundles = PluginParser.bundles(EngineApi.itemsOf(result.value))) }

            is RpcResult.Err -> Unit
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

    /**
     * Installs the package [spec] names (npm name, git URL, archive URL — whatever the
     * engine's spec grammar accepts). Blocks until the engine reports a [PluginChange];
     * the screen keeps the [PluginInstall] in state so it can show progress and cancel.
     *
     * Cancelling is a separate concurrent RPC ([cancelInstall]) — it cannot be part of
     * this call, which the engine is holding open.
     */
    suspend fun install(spec: String) {
        val source = source() ?: return
        val requestId = "pocket-${UUID.randomUUID()}"
        _state.update { it.copy(action = PluginAction(spec, requestId), lastChange = null) }
        val result = try {
            source.installBundle(spec, requestId)
        } finally {
            _state.update { it.copy(action = null) }
        }
        _state.update { it.copy(lastChange = changeOf(result)) }
        reload()
    }

    /**
     * Cancels the in-flight install. The outcome arrives through the still-blocked
     * [install] call (`application: "cancelled"`), so this only sends the request;
     * `accepted` here means "the engine saw the cancel request", not that files are
     * restored yet.
     */
    suspend fun cancelInstall(requestId: String) {
        val source = source() ?: return
        source.cancelInstall(requestId)
    }

    /** Removes the package [name]. Same lifecycle as [install]; not cancellable. */
    suspend fun remove(name: String) {
        val source = source() ?: return
        _state.update { it.copy(action = PluginAction(name, ""), lastChange = null) }
        val result = try {
            source.removeBundle(name)
        } finally {
            _state.update { it.copy(action = null) }
        }
        _state.update { it.copy(lastChange = changeOf(result)) }
        reload()
    }

    /** Clears the outcome banner; the screen calls this when the user dismisses it. */
    fun dismissChange() {
        _state.update { it.copy(lastChange = null) }
    }

    /**
     * Maps the engine's `ChangeResult` (or an RPC-level failure) to what the user sees.
     * Every `application` value the engine defines has a branch, so an unknown value
     * cannot silently pass as success.
     */
    private fun changeOf(result: RpcResult): PluginChange = when (result) {
        is RpcResult.Err -> PluginChange.Failed(result.code, result.message)
        is RpcResult.Ok -> {
            val value = result.value as? JSONObject
            when (value?.optString("application")) {
                "applied" -> PluginChange.Applied
                "restart-required" -> PluginChange.RestartRequired
                "cancelled" -> PluginChange.Cancelled
                "failed", "overridden" -> PluginChange.Failed(
                    code = value.optJSONObject("error")?.optString("code").orEmpty().ifBlank { value.optString("application") },
                    detail = value.optJSONObject("error")?.optString("diagnostic")?.takeIf { it.isNotBlank() },
                )

                else -> PluginChange.Failed(
                    code = "protocol",
                    detail = value?.toString()?.take(200) ?: "Install result has no application field",
                )
            }
        }
    }
}
