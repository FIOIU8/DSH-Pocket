package com.fioiu8.dshpocket.screen

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * User-visible configuration of the screen-control feature, plus the bridge token.
 *
 * Toggles and the deny list live in SharedPreferences (the project-wide store). The
 * bridge token deliberately does not: the plan (§2.2) keeps it in a plain file under
 * `filesDir/screen-control/`, which is outside `$DSH_HOME` and therefore never enters
 * the backup zip, and out of the prefs blob that is easy to dump wholesale.
 */
class ScreenControlSettings(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** True = the user dialog appears for every op; false = only when the review flags danger. */
    var askEveryTime: Boolean
        get() = prefs.getBoolean(KEY_ASK_ALWAYS, false)
        set(value) = prefs.edit().putBoolean(KEY_ASK_ALWAYS, value).apply()

    /** 思维链悬浮窗（overlay/ThoughtOverlayService）；随包默认开启，AI 操作屏幕时自动隐藏。 */
    var thoughtOverlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_THOUGHT_OVERLAY, true)
        set(value) = prefs.edit().putBoolean(KEY_THOUGHT_OVERLAY, value).apply()

    /** False turns the review model off; per plan §4 every op then asks the user instead. */
    var reviewEnabled: Boolean
        get() = prefs.getBoolean(KEY_REVIEW_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_REVIEW_ENABLED, value).apply()

    var reviewBaseUrl: String
        get() = prefs.getString(KEY_REVIEW_BASE_URL, null)?.trim().orEmpty()
        set(value) = prefs.edit().putString(KEY_REVIEW_BASE_URL, value.trim()).apply()

    var reviewModel: String
        get() = prefs.getString(KEY_REVIEW_MODEL, null)?.trim().orEmpty()
        set(value) = prefs.edit().putString(KEY_REVIEW_MODEL, value.trim()).apply()

    /** The review API key lives next to the bridge token, never in prefs. */
    var reviewApiKey: String
        get() {
            val file = secretFile(REVIEW_KEY_FILE)
            return if (file.isFile) file.readText().trim().takeIf { it.isNotEmpty() }.orEmpty() else ""
        }
        set(value) {
            val file = secretFile(REVIEW_KEY_FILE)
            if (value.isBlank()) {
                file.delete()
            } else {
                file.parentFile?.mkdirs()
                file.writeText(value.trim())
            }
        }

    fun hasReviewConfig(): Boolean = reviewBaseUrl.isNotEmpty() && reviewModel.isNotEmpty() && reviewApiKey.isNotEmpty()

    /**
     * Foreground packages that rule filtering refuses outright for "act" operations.
     * The default set is a conservative banking / payment / password-manager list; the
     * settings screen lets the user edit or empty it entirely.
     */
    fun denyList(): Set<String> {
        val stored = prefs.getStringSet(KEY_DENY_LIST, null)
        return (stored ?: DEFAULT_DENY_LIST).toSet()
    }

    fun setDenyList(entries: Set<String>) {
        prefs.edit().putStringSet(KEY_DENY_LIST, entries.map { it.trim() }.filter { it.isNotEmpty() }.toSet()).apply()
    }

    /**
     * The bearer token shared with the engine process. Minted on first use and reused
     * for the lifetime of the installation; rotating it is only needed if it leaks.
     */
    fun bridgeToken(): String {
        val file = secretFile(BRIDGE_TOKEN_FILE)
        file.parentFile?.mkdirs()
        val existing = if (file.isFile) file.readText().trim().takeIf { it.isNotEmpty() } else null
        if (existing != null) return existing
        val minted = UUID.randomUUID().toString().replace("-", "")
        file.writeText(minted)
        return minted
    }

    private fun secretFile(name: String): File = File(File(appContext.filesDir, SECRETS_DIR), name)

    companion object {
        private const val PREFS_NAME = "screen_control"
        private const val SECRETS_DIR = "screen-control"
        private const val BRIDGE_TOKEN_FILE = "bridge-token"
        private const val REVIEW_KEY_FILE = "review-api-key"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_ASK_ALWAYS = "ask_always"
        private const val KEY_THOUGHT_OVERLAY = "thought_overlay"
        private const val KEY_REVIEW_ENABLED = "review_enabled"
        private const val KEY_REVIEW_BASE_URL = "review_base_url"
        private const val KEY_REVIEW_MODEL = "review_model"
        private const val KEY_DENY_LIST = "deny_list"

        /**
         * Best-effort package names, editable by the user. An entry that matches nothing
         * is harmless; a missing entry for a sensitive app can be added in settings.
         */
        val DEFAULT_DENY_LIST: Set<String> = setOf(
            // 支付 / 钱包
            "com.eg.android.AlipayGphone",
            "com.unionpay",
            "com.google.android.apps.nbu.paisa.user",
            "com.paypal.android.p2pmobile",
            "com.squareup.cash",
            "com.venmo",
            // 银行
            "com.chinamworld.main",
            "com.chinamworld.bocmbci",
            "com.icbc",
            "com.android.bankabc",
            "cmb.pb",
            "com.bankcomm.Bankcomm",
            "com.chase.sig.android",
            "com.wellsfargo.mobile.android",
            // 密码管理器
            "com.agilebits.onepassword-android",
            "com.x8bit.bitwarden",
            "com.lastpass.lpandroid",
            "com.dashlane",
            "com.dashlane.passwordmanager",
            "keepass2android.keepass2android",
            "com.kunzisoft.keepass.free",
            // 验证器
            "com.google.android.apps.authenticator2",
            "com.azure.authenticator",
            "com.duosecurity.duomobile",
        )
    }
}
