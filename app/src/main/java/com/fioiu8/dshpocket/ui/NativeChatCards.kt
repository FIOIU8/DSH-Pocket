package com.fioiu8.dshpocket.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fioiu8.dshpocket.data.chat.NativeChatMessage
import org.json.JSONArray
import org.json.JSONObject

// ---------------------------------------------------------------------------
// Tool-call and thinking cards
// ---------------------------------------------------------------------------

/** One collapsed tool-call row: which icon to draw and which localized label + arg. */
internal data class ToolCallLabel(@param:StringRes val labelRes: Int, val arg: String? = null)

private fun JSONObject.firstString(vararg keys: String): String? {
    keys.forEach { key ->
        val value = optString(key).takeIf { it.isNotBlank() }
        if (value != null && value != "null") return value
    }
    return null
}

private fun JSONObject.pathArg(): String? = firstString("file_path", "path", "absolutePath", "file")?.take(140)

/** Pure collapsed-row label; JVM-tested so the wording and the arg extraction stay locked. */
internal fun toolCallLabel(message: NativeChatMessage): ToolCallLabel {
    val args = message.argumentsText?.let { raw ->
        runCatching { JSONObject(raw) }.getOrNull()
    }
    return when (message.toolName) {
        "read" -> ToolCallLabel(com.fioiu8.dshpocket.R.string.native_tool_read, args?.pathArg())
        "write" -> ToolCallLabel(com.fioiu8.dshpocket.R.string.native_tool_write, args?.pathArg())
        "edit", "str_replace_editor" -> ToolCallLabel(com.fioiu8.dshpocket.R.string.native_tool_edit, args?.pathArg())
        "bash", "shell" -> ToolCallLabel(
            com.fioiu8.dshpocket.R.string.native_tool_bash,
            args?.firstString("command", "script", "description")?.lines()?.firstOrNull()?.take(90),
        )
        "glob", "grep", "list" -> ToolCallLabel(com.fioiu8.dshpocket.R.string.native_tool_search, args?.firstString("pattern", "query", "path")?.take(90))
        "fetch", "web_fetch" -> ToolCallLabel(com.fioiu8.dshpocket.R.string.native_tool_fetch, args?.firstString("url", "target", "address")?.take(90))
        "pocket_screen_control" -> screenToolLabel(args)
        "todo_write", "todo" -> ToolCallLabel(com.fioiu8.dshpocket.R.string.native_tool_todo)
        else -> ToolCallLabel(com.fioiu8.dshpocket.R.string.native_tool_generic, message.toolName)
    }
}

/** Computer Use rows read the real action out so the user sees what the AI is doing. */
private fun screenToolLabel(args: JSONObject?): ToolCallLabel {
    val action = args?.optString("action").orEmpty()
    return when (action) {
        "read", "screenshot" -> ToolCallLabel(com.fioiu8.dshpocket.R.string.native_tool_screen_read)
        "tap" -> ToolCallLabel(
            com.fioiu8.dshpocket.R.string.native_tool_screen_tap,
            "${args?.optInt("x") ?: 0}, ${args?.optInt("y") ?: 0}",
        )
        "swipe" -> ToolCallLabel(com.fioiu8.dshpocket.R.string.native_tool_screen_swipe)
        "text" -> ToolCallLabel(com.fioiu8.dshpocket.R.string.native_tool_screen_text, args?.optString("text")?.take(30))
        "key" -> ToolCallLabel(com.fioiu8.dshpocket.R.string.native_tool_screen_key, args?.optString("key"))
        "request_permission" -> ToolCallLabel(
            com.fioiu8.dshpocket.R.string.native_tool_screen_permission,
            args?.optString("permission"),
        )
        else -> ToolCallLabel(com.fioiu8.dshpocket.R.string.native_tool_generic, "Computer Use")
    }
}

internal fun toolCallIcon(name: String?): ImageVector = when (name) {
    "read" -> Icons.Filled.Description
    "write", "edit", "str_replace_editor" -> Icons.Filled.Edit
    "bash", "shell" -> Icons.Filled.Terminal
    "glob", "grep", "list" -> Icons.Filled.Search
    "fetch", "web_fetch" -> Icons.Filled.Public
    "pocket_screen_control" -> Icons.Filled.TouchApp
    "todo_write", "todo" -> Icons.Filled.Checklist
    else -> Icons.Filled.Build
}

/** Pretty-print a raw tool-arguments JSON string; falls back to the original text. */
internal fun prettyToolArguments(raw: String): String = try {
    when {
        raw.trim().startsWith("{") -> JSONObject(raw).toString(2)
        raw.trim().startsWith("[") -> org.json.JSONArray(raw).toString(2)
        else -> raw
    }
} catch (_: Throwable) {
    raw
}

/** "3" / "3.2" — the unit lives in the resource so locales can place it. */
internal fun formatSecondsValue(durationMs: Long): String {
    val seconds = durationMs / 1000.0
    return if (seconds >= 10) "%.0f".format(seconds) else "%.1f".format(seconds)
}

@Composable
internal fun ToolCallCard(message: NativeChatMessage) {
    var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
    val label = toolCallLabel(message)
    val argText = label.arg
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 38.dp)
                    .clickable { expanded = !expanded },
            ) {
                Icon(
                    if (message.isError) Icons.Filled.ErrorOutline else toolCallIcon(message.toolName),
                    contentDescription = null,
                    tint = if (message.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = if (argText.isNullOrBlank()) stringResource(label.labelRes) else stringResource(label.labelRes, argText),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (message.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (message.isRunningToolCall) {
                    CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.5.dp)
                } else {
                    if (message.isError) {
                        Text(
                            stringResource(com.fioiu8.dshpocket.R.string.native_tool_failed),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Icon(
                        Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(18.dp)
                            .graphicsLayer { rotationZ = if (expanded) 180f else 0f },
                    )
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(bottom = 8.dp)) {
                    message.errorDetails?.takeIf { it.isNotBlank() }?.let { details ->
                        Text(
                            text = details,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
                        )
                    }
                    message.argumentsText?.takeIf { it.isNotBlank() }?.let { args ->
                        Text(
                            text = prettyToolArguments(args),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 180.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                    message.resultText?.takeIf { it.isNotBlank() }?.let { result ->
                        Text(
                            text = result.take(4_000),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = if (message.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ThinkingCard(message: NativeChatMessage, live: Boolean) {
    var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 38.dp)
                    .clickable { expanded = !expanded },
            ) {
                Icon(
                    Icons.Filled.Psychology,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = when {
                        live -> stringResource(com.fioiu8.dshpocket.R.string.native_thinking)
                        message.durationMs != null ->
                            stringResource(com.fioiu8.dshpocket.R.string.native_thought_for, formatSecondsValue(message.durationMs))
                        else -> stringResource(com.fioiu8.dshpocket.R.string.native_thought_done)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(18.dp)
                        .graphicsLayer { rotationZ = if (expanded) 180f else 0f },
                )
            }
            // While the model is still thinking, the collapsed row shows its latest
            // thought line; once finished only the duration stays visible.
            if (live && !expanded) {
                Text(
                    text = message.text.lines().lastOrNull { it.isNotBlank() }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                SelectionContainer(
                    Modifier
                        .padding(bottom = 8.dp)
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
