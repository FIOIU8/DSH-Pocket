package com.fioiu8.dshpocket.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Live state of the assistant turn, as far as the thought overlay cares. */
data class ThoughtState(
    val active: Boolean = false,
    val thinking: Boolean = false,
    val text: String = "",
)

/**
 * Process-wide bus from the chat stream (NativeChatScreen's session/follow collector)
 * to [ThoughtOverlayService].
 *
 * The overlay must keep updating while the chat screen is merely backgrounded — the
 * whole point is watching the AI think from another app — and the native chat's follow
 * stream keeps running while its Activity is only stopped, so the screen is the natural
 * producer and this bus is the decoupling.
 *
 * The reasoning text lives in app memory only: it is never written to disk, the engine
 * or the workspace, so the AI cannot see its own thought overlay.
 */
object ThoughtStreamBus {

    const val MAX_TEXT_CHARS = 800

    private val _state = MutableStateFlow(ThoughtState())
    val state: StateFlow<ThoughtState> = _state.asStateFlow()

    fun turnStarted() {
        _state.value = ThoughtState(active = true, thinking = true)
    }

    /** A reasoning chunk; arriving without a start (reconnect) implies the turn is live. */
    fun reasoningDelta(text: String) {
        if (text.isEmpty()) return
        val base = _state.value.let { if (it.active) it else ThoughtState(active = true, thinking = true) }
        _state.value = base.copy(
            active = true,
            thinking = true,
            text = (base.text + text).takeLast(MAX_TEXT_CHARS),
        )
    }

    /** Snapshot reconnect mid-reasoning: replace the text with the stream's own replay. */
    fun reasoningRestored(text: String) {
        if (text.isBlank()) return
        _state.value = ThoughtState(active = true, thinking = true, text = text.takeLast(MAX_TEXT_CHARS))
    }

    /** The answer (or a tool call) has started; the thinking phase is over. */
    fun answerStarted() {
        val current = _state.value
        if (current.active && current.thinking) {
            _state.value = current.copy(thinking = false, text = "")
        }
    }

    fun turnEnded() {
        _state.value = ThoughtState()
    }
}
