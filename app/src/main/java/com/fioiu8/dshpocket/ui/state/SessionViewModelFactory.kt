package com.fioiu8.dshpocket.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.fioiu8.dshpocket.engine.EngineState

/**
 * Supplies the repository, which is shared per process, without pulling in a DI framework.
 *
 * [EngineState] is read through a lambda rather than captured, so the ViewModel always sees
 * the current value instead of the one that existed when it was created.
 */
class SessionViewModelFactory(
    private val repository: SessionRepository,
    private val engineState: () -> EngineState,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(SessionViewModel::class.java)) {
            "Unsupported ViewModel: $modelClass"
        }
        return SessionViewModel(repository, engineState) as T
    }
}
