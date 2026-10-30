package com.fioiu8.dshpocket.ui

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fioiu8.dshpocket.data.chat.NativeChatMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
internal fun NativeMessageBubble(
    message: NativeChatMessage,
    style: NativeChatStyle,
    preferences: NativeChatPreferences,
    agentLabel: String,
    onAssist: (() -> Unit)?,
    onEdit: (() -> Unit)? = null,
) {
    val fromUser = message.role == "user"
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = if (fromUser) Modifier.widthIn(max = 360.dp) else Modifier.fillMaxWidth(),
            horizontalAlignment = if (fromUser) Alignment.End else Alignment.Start,
        ) {
            if (fromUser) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(bottom = 8.dp, end = 2.dp),
                ) {
                    Text(stringResource(com.fioiu8.dshpocket.R.string.native_user), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(Color(0xFF9AD85F), Color(0xFF42B8B0)))),
                    )
                }
            }
            if (fromUser) {
                Surface(
                    color = style.userBubble,
                    // MD3 token instead of the engine-driven radius, so every bubble
                    // matches the app-wide shape scale; the engine still picks the color.
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.widthIn(max = 340.dp).wrapContentWidth(),
                ) {
                    SelectionContainer {
                        MarkdownText(
                            markdown = message.text,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 13.dp),
                            fontSize = (MaterialTheme.typography.bodyLarge.fontSize.value * style.fontScale).sp,
                            lineHeight = (26f * style.fontScale).sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Text(agentLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                SelectionContainer {
                    MarkdownText(
                        markdown = message.text,
                        modifier = Modifier.fillMaxWidth(),
                        fontSize = (MaterialTheme.typography.bodyLarge.fontSize.value * style.fontScale).sp,
                        lineHeight = (27f * style.fontScale).sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            MessageMetadata(message, preferences, onAssist, onEdit) {
                scope.launch {
                    clipboard.setClipEntry(
                        ClipEntry(ClipData.newPlainText("message", message.text)),
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageMetadata(
    message: NativeChatMessage,
    preferences: NativeChatPreferences,
    onAssist: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    onCopy: () -> Unit,
) {
    val fromUser = message.role == "user"
    // Read the locale observably. Locale.getDefault() would not recompose when the user
    // switches the app language, leaving numbers formatted in the old locale.
    val locale = LocalConfiguration.current.locales[0]
    val editedLabel = stringResource(com.fioiu8.dshpocket.R.string.native_message_edited)
    val metadata = buildList {
        if (preferences.showMessageTime && message.time > 0) add(formatMessageTime(message.time, locale))
        if (preferences.showTokenUsage) {
            message.inputTokens?.let { add("↑ $it") }
            message.outputTokens?.let { add("↓ $it") }
        }
        if (preferences.showDuration) {
            message.durationMs?.let { duration ->
                add(if (duration >= 1000) "%.1fs".format(locale, duration / 1000.0) else "${duration}ms")
                if (message.outputTokens != null && duration > 0) {
                    add("%.1f tok/s".format(locale, message.outputTokens * 1000.0 / duration))
                }
            }
        }
        if (message.edited) add(editedLabel)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        if (onEdit != null) {
            IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Edit, contentDescription = stringResource(com.fioiu8.dshpocket.R.string.native_message_edit), modifier = Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(com.fioiu8.dshpocket.R.string.copy), modifier = Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onAssist != null) {
            IconButton(onClick = onAssist, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = stringResource(com.fioiu8.dshpocket.R.string.settings_assist_reply), modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (metadata.isNotEmpty()) {
            Text(
                metadata.joinToString("  ·  "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatMessageTime(rawTime: Long, locale: Locale): String {
    val millis = if (rawTime in 1..100_000_000_000L) rawTime * 1000 else rawTime
    // A localized skeleton instead of a hardcoded "MM月dd日" pattern, which leaked Chinese
    // date glyphs into every other locale.
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd HHmmss")
    return SimpleDateFormat(pattern, locale).format(Date(millis))
}
