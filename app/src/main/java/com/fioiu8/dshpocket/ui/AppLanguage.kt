package com.fioiu8.dshpocket.ui

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

enum class AppLanguage(val storedValue: String) {
    SYSTEM("system"),
    ZH_CN("zh-CN"),
    EN("en");

    fun resolve(systemLocale: Locale): AppLanguage = when (this) {
        SYSTEM -> if (systemLocale.language == "zh" && systemLocale.country.equals("CN", ignoreCase = true)) ZH_CN else EN
        else -> this
    }

    companion object {
        fun fromStoredValue(value: String?): AppLanguage =
            entries.firstOrNull { it.storedValue == value } ?: SYSTEM
    }
}

object AppLanguagePreferences {
    private const val PREFS = "ui_preferences"
    private const val KEY_LANGUAGE = "app_language"

    fun read(context: Context): AppLanguage =
        AppLanguage.fromStoredValue(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, null),
        )

    fun save(context: Context, language: AppLanguage) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, language.storedValue)
            .apply()
    }

    fun localizedContext(base: Context): Context {
        val language = read(base).resolve(base.resources.configuration.locales[0])
        val locale = Locale.forLanguageTag(language.storedValue)
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(locale)
        return base.createConfigurationContext(configuration)
    }

    fun selectedLanguage(context: Context): AppLanguage = read(context)
}

