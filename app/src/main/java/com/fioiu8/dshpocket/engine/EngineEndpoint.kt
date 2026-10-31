package com.fioiu8.dshpocket.engine

/** Connection target shared by local and future remote engine modes. */
sealed interface EngineEndpoint {
    data class Local(val ready: EngineState.Ready) : EngineEndpoint
    data class Remote(
        val hostProfileId: String,
        val forwardedPort: Int,
        val authState: AuthState,
    ) : EngineEndpoint

    enum class AuthState { Missing, Pending, Authenticated, Rejected }

    fun baseUrlOrNull(): String? = when (this) {
        is Local -> ready.baseUrl
        is Remote -> if (authState == AuthState.Authenticated) "http://127.0.0.1:$forwardedPort" else null
    }
}
