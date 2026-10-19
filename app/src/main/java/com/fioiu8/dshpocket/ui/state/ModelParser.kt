package com.fioiu8.dshpocket.ui.state

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * The parsing and validation rules behind the model screen.
 *
 * All of this used to live inside a 1,400-line composable, where none of it could be
 * tested. None of it touches Android or the engine — it is JSON and string handling — so
 * it sits here and is covered directly.
 */
object ModelParser {

    /** Walks the `settings/describe` namespaces the model screen cares about. */
    fun settings(namespaces: JSONArray?): SettingsSnapshot {
        var provider: String? = null
        var model: String? = null
        var modelRevision: Long? = null
        var providerRevision: Long? = null
        var providers: List<ProviderInfo> = emptyList()
        var officialKeyRef = SettingsSnapshot.DEFAULT_OFFICIAL_KEY_REF

        for (i in 0 until (namespaces?.length() ?: 0)) {
            val ns = namespaces?.optJSONObject(i) ?: continue
            when (ns.optString("ns")) {
                "agent-default-model" -> {
                    val value = ns.optJSONObject("value")
                    provider = value?.optString("provider")?.takeIf { it.isNotBlank() }
                    model = value?.optString("model")?.takeIf { it.isNotBlank() }
                    if (ns.has("revision")) modelRevision = ns.optLong("revision")
                }

                "llm-pi-ai" -> {
                    if (ns.has("revision")) providerRevision = ns.optLong("revision")
                    providers = readProviders(ns.optJSONObject("value")?.optJSONObject("providers"))
                }

                "llm-deepseek" -> {
                    officialKeyRef = ns.optJSONObject("value")?.optString("apiKeyEnv")
                        ?.takeIf { it.isNotBlank() } ?: SettingsSnapshot.DEFAULT_OFFICIAL_KEY_REF
                }
            }
        }

        return SettingsSnapshot(provider, model, modelRevision, providerRevision, providers, officialKeyRef)
    }

    private fun readProviders(configs: JSONObject?): List<ProviderInfo> = buildList {
        configs?.keys()?.forEach { id ->
            val p = configs.optJSONObject(id) ?: return@forEach
            val modelConfigs = p.optJSONArray("models")
            val modelEntries = modelConfigs?.let { models ->
                (0 until models.length()).mapNotNull { k ->
                    val model = models.optJSONObject(k) ?: return@mapNotNull null
                    model.optString("id").takeIf { it.isNotBlank() }?.let { it to model.optString("name") }
                }
            }.orEmpty()
            val modelDetails = modelConfigs?.let { models ->
                buildMap {
                    for (k in 0 until models.length()) {
                        val model = models.optJSONObject(k) ?: continue
                        val modelId = model.optString("id").takeIf { it.isNotBlank() } ?: continue
                        put(modelId, JSONObject(model.toString()))
                    }
                }
            }.orEmpty()
            add(
                ProviderInfo(
                    id = id,
                    displayName = p.optString("displayName").ifBlank { id },
                    baseURL = p.optString("baseURL").takeIf { it.isNotBlank() },
                    api = p.optString("api").ifBlank { DEFAULT_API },
                    apiKeyEnv = p.optString("apiKeyEnv").takeIf { it.isNotBlank() },
                    models = modelEntries,
                    modelDetails = modelDetails,
                    headers = p.optJSONObject("headers")?.let { headerObject ->
                        buildMap { headerObject.keys().forEach { key -> put(key, headerObject.optString(key)) } }
                    }.orEmpty(),
                ),
            )
        }
    }

    const val DEFAULT_API = "openai-completions"

    fun providerList(items: JSONArray?): List<Pair<String, String>> {
        val length = items?.length() ?: 0
        return (0 until length).mapNotNull { i ->
            val p = items?.optJSONObject(i) ?: return@mapNotNull null
            p.optString("id").takeIf { it.isNotBlank() }?.let { it to p.optString("name") }
        }
    }

    /**
     * Which credential slots to ask about: every provider's env var, plus the built-in
     * DeepSeek slot when that provider is actually available.
     */
    fun keyRefs(
        providers: List<ProviderInfo>,
        officialKeyRef: String,
        builtinProviders: List<Pair<String, String>>,
    ): List<String> = (
        providers.mapNotNull { it.apiKeyEnv } +
            if (builtinProviders.any { it.first == OFFICIAL_PROVIDER_ID }) listOf(officialKeyRef) else emptyList()
        ).distinct()

    const val OFFICIAL_PROVIDER_ID = "deepseek-official"

    /** Maps a `credentials/describe` reply onto the refs that were asked about. */
    fun keyStates(reply: Any?, refs: List<String>): Map<String, Boolean> {
        val obj = reply as? JSONObject ?: return emptyMap()
        return refs.associateWith { obj.optJSONObject(it)?.optBoolean("configured") ?: false }
    }
}

/** `id` or `id|Name`, one per line. Null means the text cannot be used. */
fun parseProviderModels(value: String): List<Pair<String, String>>? {
    val entries = value.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
        val parts = line.split('|', limit = 2)
        parts[0].trim() to parts.getOrNull(1).orEmpty().trim()
    }.toList()
    if (entries.any { it.first.isBlank() } || entries.map { it.first }.distinct().size != entries.size) return null
    return entries
}

fun encodeProviderModels(models: List<Pair<String, String>>): String =
    models.joinToString("\n") { (id, name) -> if (name.isBlank()) id else "$id|$name" }.trimEnd()

/** `Name: value` per line, RFC 7230 token rules on the name. Null when malformed. */
fun parseProviderHeaders(value: String): Map<String, String>? {
    val entries = mutableListOf<Pair<String, String>>()
    for (line in value.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }) {
        val separator = line.indexOf(':')
        if (separator <= 0) return null
        val name = line.substring(0, separator).trim()
        val content = line.substring(separator + 1).trim()
        if (!name.matches(Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")) || content.isBlank()) return null
        entries += name to content
    }
    if (entries.map { it.first.lowercase(Locale.ROOT) }.distinct().size != entries.size) return null
    return entries.toMap()
}

fun isHttpEndpoint(value: String): Boolean = runCatching {
    val endpoint = java.net.URI(value.trim())
    (endpoint.scheme == "https" || endpoint.scheme == "http") && !endpoint.host.isNullOrBlank()
}.getOrDefault(false)

fun providerCredentialRef(id: String): String =
    "${id.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]+"), "_")}_API_KEY"
