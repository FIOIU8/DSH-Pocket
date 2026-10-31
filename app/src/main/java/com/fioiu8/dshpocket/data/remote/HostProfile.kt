package com.fioiu8.dshpocket.data.remote

import org.json.JSONObject

enum class HostAuthMethod { Password, PrivateKey }

/** Persisted host metadata. Secrets stay in Android Keystore under [credentialAlias]. */
data class HostProfile(
    val id: String,
    val name: String,
    val address: String,
    val port: Int = 22,
    val username: String,
    val authMethod: HostAuthMethod,
    val credentialAlias: String,
    val hostFingerprint: String? = null,
    val forwardedPort: Int? = null,
    val lastConnectedAt: Long? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("address", address)
        put("port", port)
        put("username", username)
        put("authMethod", authMethod.name)
        put("credentialAlias", credentialAlias)
        hostFingerprint?.let { put("hostFingerprint", it) }
        forwardedPort?.let { put("forwardedPort", it) }
        lastConnectedAt?.let { put("lastConnectedAt", it) }
    }

    companion object {
        fun fromJson(json: JSONObject): HostProfile? {
            val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
            val name = json.optString("name").takeIf { it.isNotBlank() } ?: return null
            val address = json.optString("address").takeIf { it.isNotBlank() } ?: return null
            val username = json.optString("username").takeIf { it.isNotBlank() } ?: return null
            val alias = json.optString("credentialAlias").takeIf { it.isNotBlank() } ?: return null
            val auth = runCatching { HostAuthMethod.valueOf(json.optString("authMethod")) }.getOrNull() ?: return null
            val port = json.optInt("port", 22).takeIf { it in 1..65535 } ?: return null
            return HostProfile(
                id = id,
                name = name,
                address = address,
                port = port,
                username = username,
                authMethod = auth,
                credentialAlias = alias,
                hostFingerprint = json.optString("hostFingerprint").takeIf { it.isNotBlank() && it != "null" },
                forwardedPort = json.optInt("forwardedPort").takeIf { it > 0 },
                lastConnectedAt = if (json.has("lastConnectedAt")) json.optLong("lastConnectedAt") else null,
            )
        }
    }
}
