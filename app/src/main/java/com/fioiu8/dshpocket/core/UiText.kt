package com.fioiu8.dshpocket.core

import android.content.Context
import androidx.annotation.StringRes

/** A user-visible message that stays unresolved until it is rendered. */
sealed interface UiText {
    data class Resource(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Raw(val value: String) : UiText

    fun resolve(context: Context): String = when (this) {
        is Resource -> context.getString(id, *args.toTypedArray())
        is Raw -> value
    }
}

fun text(@StringRes id: Int, vararg args: Any): UiText = UiText.Resource(id, args.toList())
