package com.fioiu8.dshpocket.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the app's own UI is in the foreground, reported by AppShell's lifecycle.
 *
 * The thought overlay only makes sense OUTSIDE the app — inside, the chat screen
 * already shows the thinking card. Default false: a process revived by the engine
 * service without any Activity means the user is, by definition, elsewhere.
 */
object AppForegroundTracker {

    private val _inForeground = MutableStateFlow(false)
    val inForeground: StateFlow<Boolean> = _inForeground.asStateFlow()

    fun setForeground(value: Boolean) {
        _inForeground.value = value
    }
}
