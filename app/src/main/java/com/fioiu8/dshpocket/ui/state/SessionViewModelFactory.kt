package com.fioiu8.dshpocket.ui.state

import com.fioiu8.dshpocket.data.session.SessionRepository

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * Supplies the repository, which is shared per process, without pulling in a DI framework.
 */
class SessionViewModelFactory(
    private val repository: SessionRepository,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(SessionViewModel::class.java)) {
            "Unsupported ViewModel: $modelClass"
        }
        return SessionViewModel(repository) as T
    }
}
