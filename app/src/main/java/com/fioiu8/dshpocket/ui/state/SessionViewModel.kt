package com.fioiu8.dshpocket.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fioiu8.dshpocket.engine.EngineState
import kotlinx.coroutines.launch

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
    private val engineState: () -> EngineState,
) : ViewModel() {

    val state = repository.state

    /** Idempotent — safe to call on every composition, including on re-entry. */
    fun ensurePolling() {
        if (engineState() is EngineState.Ready) repository.startPolling()
    }

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
}
