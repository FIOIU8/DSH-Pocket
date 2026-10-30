package com.fioiu8.dshpocket.ui

import androidx.compose.ui.graphics.Color
import org.json.JSONObject

internal fun defaultNativeChatStyle(dark: Boolean) = NativeChatStyle(
    userBubble = if (dark) Color(0xFF34495E) else Color(0xFFDDE3FF),
    assistantBubble = if (dark) Color(0xFF25282D) else Color(0xFFF1F3F6),
    cornerRadius = 18,
    fontScale = 1f,
)

internal fun parseNativeChatStyle(value: JSONObject?, dark: Boolean): NativeChatStyle? {
    value ?: return null
    return runCatching {
        val userHex = value.optString("userBubble")
        val assistantHex = value.optString("assistantBubble")
        require(userHex.matches(Regex("#[0-9A-Fa-f]{6}")))
        require(assistantHex.matches(Regex("#[0-9A-Fa-f]{6}")))
        val user = Color(android.graphics.Color.parseColor(userHex))
        val assistant = Color(android.graphics.Color.parseColor(assistantHex))
        val radius = value.optInt("cornerRadiusDp", 18).coerceIn(8, 28)
        val scale = value.optDouble("fontScale", 1.0).toFloat().coerceIn(0.9f, 1.25f)
        NativeChatStyle(user, assistant, radius, scale)
    }.getOrElse {
        defaultNativeChatStyle(dark)
    }
}
