package com.fioiu8.dshpocket.ui

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color

data class NativeChatPreferences(
    val showMessageTime: Boolean = true,
    val showTokenUsage: Boolean = true,
    val showDuration: Boolean = true,
    val showAssistReply: Boolean = false,
)

data class NativeChatStyle(
    val userBubble: Color,
    val assistantBubble: Color,
    val cornerRadius: Int,
    val fontScale: Float,
)

enum class ChatPresentation(val key: String, @param:StringRes val labelRes: Int) {
    Web("web", com.fioiu8.dshpocket.R.string.native_web_chat),
    Native("native", com.fioiu8.dshpocket.R.string.native_native_chat),
    ;

    companion object {
        fun fromKey(key: String): ChatPresentation? = entries.firstOrNull { it.key == key }
    }
}
