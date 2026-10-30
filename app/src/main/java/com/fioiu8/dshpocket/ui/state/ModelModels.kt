package com.fioiu8.dshpocket.ui.state

import org.json.JSONObject

/**
 * The provider configuration as `settings/describe` reports it.
 *
 * Moved out of the screen so the parsing can be tested. It carries raw values — an empty
 * display name stays empty until the UI decides what to show — for the same reason
 * [com.fioiu8.dshpocket.data.session.SessionRow.title] is nullable.
 */
data class ProviderInfo(
    val id: String,
    val displayName: String,
    val baseURL: String?,
    val api: String,
    val apiKeyEnv: String?,
    val models: List<Pair<String, String>>,
    val modelDetails: Map<String, JSONObject>,
    val headers: Map<String, String>,
)

/** The editable form of a provider, as the editor page holds it while being typed into. */
data class ProviderDraft(
    val id: String,
    val displayName: String,
    val baseURL: String,
    val api: String,
    val apiKey: String,
    val models: String,
    val headers: String,
    val modelDetails: Map<String, JSONObject>,
)

val PROVIDER_PROTOCOLS = listOf(
    "openai-completions" to "OpenAI Chat Completions",
    "openai-responses" to "OpenAI Responses",
    "anthropic-messages" to "Anthropic Messages",
)

/** Provider ids are used in env var names and URLs, so the shape is constrained. */
private val PROVIDER_ID = Regex("^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$")

internal fun isValidProviderId(id: String): Boolean = id.matches(PROVIDER_ID)

/**
 * Everything one `settings/describe` yielded.
 *
 * Nulls mean "the engine did not report this namespace", which is different from
 * "reported it as empty" — the screen shows a different message for each.
 */
data class SettingsSnapshot(
    val defaultProvider: String? = null,
    val defaultModel: String? = null,
    val modelRevision: Long? = null,
    val providerRevision: Long? = null,
    val providers: List<ProviderInfo> = emptyList(),
    val officialApiKeyRef: String = DEFAULT_OFFICIAL_KEY_REF,
) {
    companion object {
        const val DEFAULT_OFFICIAL_KEY_REF = "DEEPSEEK_API_KEY"
    }
}

data class ModelUiState(
    val settings: SettingsSnapshot = SettingsSnapshot(),
    val builtinProviders: List<Pair<String, String>> = emptyList(),
    val keyStates: Map<String, Boolean> = emptyMap(),
    val accountView: JSONObject? = null,
    /**
     * The load-error banner. Cleared at the start of every reload and set when
     * `settings/describe` fails. Everything below survives a failed reload — the
     * shipped screen keeps showing the last good data under the banner, and that is
     * deliberately preserved here rather than "improved".
     */
    val error: String? = null,
)
