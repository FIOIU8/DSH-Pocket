package com.fioiu8.dshpocket.data.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores host metadata only; passwords and private keys are kept by [KeystoreSecretStore]. */
class HostProfileStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("remote_hosts", Context.MODE_PRIVATE)

    @Synchronized fun list(): List<HostProfile> {
        val json = prefs.getString(KEY, null) ?: return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                HostProfile.fromJson(array.optJSONObject(index) ?: continue)?.let(::add)
            }
        }
    }

    @Synchronized fun save(profile: HostProfile) {
        val updated = list().filterNot { it.id == profile.id } + profile
        val array = JSONArray()
        updated.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    @Synchronized fun remove(id: String) {
        val array = JSONArray()
        list().filterNot { it.id == id }.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    companion object {
        private const val KEY = "profiles"
        @Volatile private var shared: HostProfileStore? = null

        fun get(context: Context): HostProfileStore = shared ?: synchronized(this) {
            shared ?: HostProfileStore(context.applicationContext).also { shared = it }
        }
    }
}

/** AES-GCM wrapper around Android Keystore for SSH passwords and private keys. */
class KeystoreSecretStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("remote_credentials", Context.MODE_PRIVATE)

    @Synchronized fun put(alias: String, secret: String) {
        require(alias.isNotBlank()) { "credential alias is required" }
        val key = key(alias, create = true)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(secret.toByteArray(StandardCharsets.UTF_8))
        prefs.edit()
            .putString("$alias.iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("$alias.data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    @Synchronized fun get(alias: String): String? {
        val iv = prefs.getString("$alias.iv", null) ?: return null
        val data = prefs.getString("$alias.data", null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(alias, create = false), GCMParameterSpec(TAG_BITS, Base64.decode(iv, Base64.DEFAULT)))
            String(cipher.doFinal(Base64.decode(data, Base64.DEFAULT)), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    @Synchronized fun remove(alias: String) {
        prefs.edit().remove("$alias.iv").remove("$alias.data").apply()
        runCatching { keyStore.deleteEntry(alias) }
    }

    private val keyStore: KeyStore
        get() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun key(alias: String, create: Boolean): SecretKey {
        val store = keyStore
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "credential key is missing" }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        @Volatile private var shared: KeystoreSecretStore? = null

        fun get(context: Context): KeystoreSecretStore = shared ?: synchronized(this) {
            shared ?: KeystoreSecretStore(context.applicationContext).also { shared = it }
        }
    }
}
