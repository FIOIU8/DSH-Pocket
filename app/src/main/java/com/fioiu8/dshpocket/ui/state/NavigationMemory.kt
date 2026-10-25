package com.fioiu8.dshpocket.ui.state

import android.content.Context

/**
 * Last-visited screen, persisted so reopening the app lands where the user left off.
 *
 * `rememberSaveable` covers rotation and system-kill restores, but a deliberate exit
 * (back out of the app, swipe from Recents) discards the saved instance state by
 * design — while the engine's foreground service keeps the process alive. The user's
 * rule (2026-10-25): the background still running means the task is still wanted, so
 * the last tab (and the open chat session) return from disk.
 */
class NavigationMemory(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun destinationName(): String? = prefs.getString(KEY_DESTINATION, null)

    fun settingsSection(): String? = prefs.getString(KEY_SECTION, null)?.takeIf { it.isNotEmpty() }

    fun chatSessionId(): String? = prefs.getString(KEY_SESSION, null)?.takeIf { it.isNotEmpty() }

    fun save(destinationName: String, settingsSection: String?, chatSessionId: String?) {
        prefs.edit()
            .putString(KEY_DESTINATION, destinationName)
            .putString(KEY_SECTION, settingsSection.orEmpty())
            .putString(KEY_SESSION, chatSessionId.orEmpty())
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "navigation_state"
        const val KEY_DESTINATION = "destination"
        const val KEY_SECTION = "settings_section"
        const val KEY_SESSION = "chat_session_id"
    }
}
