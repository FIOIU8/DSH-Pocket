package com.fioiu8.dshpocket.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fioiu8.dshpocket.engine.EngineApi
import com.fioiu8.dshpocket.engine.EngineApi.RpcResult
import com.fioiu8.dshpocket.engine.EngineState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Outcome of one [SessionViewModel.sendPrompt] call. The chat screen consumes each
 * outcome exactly once to update its own state (error banner, draft, session id).
 */
sealed interface PromptOutcome {
    /** The prompt reached the engine queue; [sessionId] is the session it landed in. */
    data class Delivered(val sessionId: String) : PromptOutcome

    /**
     * The engine refused or the transport failed. [text] is the undelivered prompt so
     * the screen can put it back into the draft box — except when the prompt was never
     * user-authored ([restoreDraft] false, e.g. the assist button), where restoring it
     * would be noise.
     */
    data class Failed(
        val sessionId: String?,
        val text: String,
        val message: String,
        val restoreDraft: Boolean,
        val code: String = "unknown",
        val details: String = message,
        val requestId: String? = null,
        val retryable: Boolean = false,
    ) : PromptOutcome
}

private data class PendingPrompt(
    val text: String,
    val sessionId: String?,
    val requestId: String,
    val restoreDraft: Boolean,
    val workspacePath: String?,
)

/**
 * Adapter between [SessionRepository] and the composables.
 *
 * The repository holds the data and survives the screen; this only turns UI events into
 * repository calls and keeps [viewModelScope] as the owner so an in-flight request is not
 * cancelled by navigating away — which is what `rememberCoroutineScope` used to do.
 *
 * Navigation state (which tab, which sub-page, the search box contents) stays in
 * `rememberSaveable`: a ViewModel does not survive process death, and navigation does.
 */
class SessionViewModel(
    private val repository: SessionRepository,
) : ViewModel() {

    val state = repository.state

    private val _promptInFlight = MutableStateFlow(false)
    val promptInFlight: StateFlow<Boolean> = _promptInFlight.asStateFlow()

    private val _promptOutcome = MutableStateFlow<PromptOutcome?>(null)
    val promptOutcome: StateFlow<PromptOutcome?> = _promptOutcome.asStateFlow()

    private val _createdSessionId = MutableStateFlow<String?>(null)
    val createdSessionId: StateFlow<String?> = _createdSessionId.asStateFlow()
    private var pendingPrompt: PendingPrompt? = null

    fun refresh() {
        viewModelScope.launch { repository.refresh() }
    }

    fun refreshUserInitiated() {
        viewModelScope.launch { repository.refreshUserInitiated() }
    }

    fun archive(sessionId: String) {
        viewModelScope.launch { repository.archive(sessionId) }
    }

    fun unarchive(sessionId: String) {
        viewModelScope.launch { repository.unarchive(sessionId) }
    }

    /**
     * Sends a chat prompt, creating a session first when needed. Runs here rather than in
     * a screen scope so a rotation between tapping send and delivery neither cancels the
     * request nor silently swallows the message and its draft; the composer keeps showing
     * busy through [promptInFlight] across the recreation.
     *
     * [engine] comes from the caller's current composition, so a restarted engine is seen
     * immediately. No-op while another prompt is in flight.
     */
    fun sendPrompt(
        engine: EngineState.Ready,
        text: String,
        sessionId: String?,
        workspacePath: String?,
        restoreDraft: Boolean = true,
    ) {
        if (_promptInFlight.value) return
        pendingPrompt = PendingPrompt(text, sessionId, UUID.randomUUID().toString(), restoreDraft, workspacePath)
        submitPrompt(engine)
    }

    /** Reuses the original request id, allowing the engine to deduplicate an uncertain retry. */
    fun retryPrompt(engine: EngineState.Ready) {
        if (_promptInFlight.value || pendingPrompt == null) return
        submitPrompt(engine)
    }

    private fun submitPrompt(engine: EngineState.Ready) {
        val pending = pendingPrompt ?: return
        viewModelScope.launch {
            _promptInFlight.value = true
            try {
                var targetId = pending.sessionId
                if (targetId == null) {
                    val create = safeRpc {
                        EngineApi.rpc(
                                    engine.baseUrl,
                                    "session/create",
                                    EngineApi.requestArgs(JSONObject().put("cwd", pending.workspacePath)),
                                    engine.token,
                        )
                    }
                    targetId = ((create as? RpcResult.Ok)?.value as? JSONObject)
                        ?.optString("sessionId")
                        ?.takeIf { it.isNotBlank() }
                    if (targetId == null) {
                        _promptOutcome.value = PromptOutcome.Failed(
                            pending.sessionId,
                            pending.text,
                            (create as? RpcResult.Err)?.message ?: "session/create returned no sessionId",
                            pending.restoreDraft,
                            code = (create as? RpcResult.Err)?.code ?: "protocol",
                            details = (create as? RpcResult.Err)?.details ?: "session/create returned no sessionId",
                            requestId = (create as? RpcResult.Err)?.requestId ?: pending.requestId,
                            retryable = (create as? RpcResult.Err)?.retryable ?: false,
                        )
                        return@launch
                    }
                    _createdSessionId.value = targetId
                    pendingPrompt = pending.copy(sessionId = targetId)
                }
                val result = safeRpc {
                    EngineApi.rpc(
                        engine.baseUrl,
                        "session/prompt",
                        EngineApi.requestArgs(
                            JSONObject()
                                .put("requestId", pending.requestId)
                                .put("sessionId", targetId)
                                .put("mode", "queue")
                                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", pending.text)))
                                .put("clientTimeZone", validClientTimeZone()),
                        ),
                        engine.token,
                    )
                }
                _promptOutcome.value = when (result) {
                    is RpcResult.Ok -> {
                        pendingPrompt = null
                        PromptOutcome.Delivered(targetId)
                    }
                    is RpcResult.Err -> PromptOutcome.Failed(
                        targetId,
                        pending.text,
                        result.message,
                        pending.restoreDraft,
                        result.code,
                        result.details,
                        pending.requestId,
                        result.retryable,
                    )
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _promptOutcome.value = PromptOutcome.Failed(
                    pending.sessionId,
                    pending.text,
                    failure.message ?: failure.javaClass.simpleName,
                    pending.restoreDraft,
                    "transport",
                    failure.message ?: failure.javaClass.simpleName,
                    pending.requestId,
                    true,
                )
            } finally {
                _promptInFlight.value = false
            }
        }
    }

    private suspend fun safeRpc(block: suspend () -> RpcResult): RpcResult = try {
        block()
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        RpcResult.Err("transport", failure.message ?: failure.javaClass.simpleName, retryable = true)
    }

    /** One-shot consumption hooks; the screen clears state after acting on it. */
    fun consumePromptOutcome() {
        _promptOutcome.value = null
    }

    fun consumeDeliveredPrompt() {
        if (_promptOutcome.value is PromptOutcome.Delivered) pendingPrompt = null
    }

    fun consumeCreatedSession() {
        _createdSessionId.value = null
    }
}

/** Area/Location IDs are what the engine's time handling expects; anything else degrades to UTC. */
private fun validClientTimeZone(): String {
    val id = java.util.TimeZone.getDefault().id
    val isAreaLocation = id.matches(Regex("[A-Za-z_+-]+(/[A-Za-z0-9._+-]+)+"))
    return if (id == "UTC" || (isAreaLocation && id in java.util.TimeZone.getAvailableIDs())) id else "UTC"
}
