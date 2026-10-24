package com.fioiu8.dshpocket.ui.onboarding

import android.content.Context

/**
 * First-launch onboarding state, in the same prefs file as the other UI settings.
 *
 * The step index is saved so a process killed mid-onboarding (extraction can take a
 * while) resumes where the user left off instead of restarting from the welcome page.
 */
object OnboardingPreferences {
    private const val PREFS = "ui_preferences"
    private const val KEY_COMPLETED = "onboarding_completed"
    private const val KEY_STEP = "onboarding_step"

    fun isCompleted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_COMPLETED, false)

    fun setCompleted(context: Context, completed: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_COMPLETED, completed)
            .apply()
    }

    fun readStep(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_STEP, 0)

    fun saveStep(context: Context, step: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_STEP, step)
            .apply()
    }
}
