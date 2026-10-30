package com.fioiu8.dshpocket.ui

import android.content.ClipData
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.fioiu8.dshpocket.data.chat.NativeChatMessage
import com.fioiu8.dshpocket.ui.theme.DshSpacing
import kotlinx.coroutines.launch

@Composable
internal fun NativeChatErrorBanner(
    error: ChatErrorState,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    copyLabel: String,
    copiedLabel: String,
    detailsLabel: String,
) {
    var expanded by remember(error) { mutableStateOf(false) }
    var copied by remember(error) { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copyText = buildString {
        append(error.title).append('\n').append(error.message)
        append("\ncode: ").append(error.code)
        error.requestId?.let { append("\nrequestId: ").append(it) }
        error.details.takeIf(String::isNotBlank)?.let { append("\n\n").append(it) }
    }
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = DshSpacing.page, vertical = 6.dp),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Column(Modifier.weight(1f)) {
                    Text(error.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onErrorContainer)
                    Text(error.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    Text(error.code, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                }
                IconButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("error", copyText)))
                            copied = true
                        }
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = copyLabel, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
                }
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.ExpandMore,
                        contentDescription = detailsLabel,
                        modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = if (expanded) 180f else 0f },
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            if (copied) Text(copiedLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer)
            if (onAction != null && actionLabel != null) {
                TextButton(onClick = onAction, modifier = Modifier.align(Alignment.End)) { Text(actionLabel) }
            }
            AnimatedVisibility(visible = expanded) {
                Text(
                    text = buildString {
                        error.requestId?.let { append("requestId: ").append(it).append('\n') }
                        append(error.details.ifBlank { error.message })
                    },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp).heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

@Composable
internal fun NativeErrorTimelineNode(
    message: NativeChatMessage,
    turnFailedLabel: String,
    blockedLabel: String,
    maxTokensLabel: String,
    interruptedLabel: String,
    copyLabel: String,
    detailsLabel: String,
    regenerateLabel: String,
    onRegenerate: (() -> Unit)?,
) {
    var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
    var copied by remember(message.id) { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copyText = buildString {
        append(message.errorCode.orEmpty()).append('\n').append(message.text)
        message.requestId?.let { append("\nrequestId: ").append(it) }
        message.errorDetails?.let { append("\n\n").append(it) }
    }
    val reasonTitle = when (message.errorCode) {
        "turn/blocked" -> blockedLabel
        "turn/max-tokens" -> maxTokensLabel
        "turn/interrupted" -> interruptedLabel
        else -> turnFailedLabel
    }
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Column(Modifier.weight(1f)) {
                    Text(reasonTitle, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                    Text(message.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    message.errorCode?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer) }
                }
                IconButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("turn-error", copyText)))
                            copied = true
                        }
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = copyLabel, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.ExpandMore, contentDescription = detailsLabel, modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = if (expanded) 180f else 0f })
                }
            }
            if (copied) Text(stringResource(com.fioiu8.dshpocket.R.string.native_error_copied), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer)
            if (message.retryable && onRegenerate != null) {
                TextButton(onClick = onRegenerate, modifier = Modifier.align(Alignment.End)) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(regenerateLabel)
                }
            }
            AnimatedVisibility(visible = expanded) {
                Text(
                    text = buildString {
                        append(message.errorDetails.orEmpty())
                        message.requestId?.let { append("\nrequestId: ").append(it) }
                    },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}
