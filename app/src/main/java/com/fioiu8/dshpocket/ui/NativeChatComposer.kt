package com.fioiu8.dshpocket.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun ChatComposer(
    value: String,
    suggestions: List<String>,
    modelName: String?,
    onValueChange: (String) -> Unit,
    sending: Boolean,
    running: Boolean,
    statusText: String?,
    enabled: Boolean,
    onCancel: () -> Unit,
    onPickModel: () -> Unit,
    onSend: () -> Unit,
) {
    Column {
        statusText?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp),
            )
        }
        if (suggestions.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                suggestions.forEach { suggestion ->
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.58f),
                        shape = CircleShape,
                        modifier = Modifier.clickable { onValueChange(suggestion) },
                    ) {
                        Text(
                            suggestion,
                            modifier = Modifier.padding(horizontal = 15.dp, vertical = 9.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraLarge),
        ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        if (value.isEmpty()) {
                            Text(stringResource(com.fioiu8.dshpocket.R.string.native_input_placeholder), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        BasicTextField(
                            value = value,
                            onValueChange = onValueChange,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = enabled && !sending,
                            minLines = 1,
                            maxLines = 5,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Model switch lives here, where the conversation happens; the picker
                    // dialog keeps its two-step model → effort flow.
                    TextButton(
                        onClick = onPickModel,
                        modifier = Modifier.widthIn(max = 240.dp),
                    ) {
                        Icon(
                            Icons.Filled.Tune,
                            contentDescription = stringResource(com.fioiu8.dshpocket.R.string.native_select_model_effort),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            modelName ?: stringResource(com.fioiu8.dshpocket.R.string.native_select_model),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            Icons.Filled.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (running) {
                        FilledIconButton(onClick = onCancel, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Filled.Stop, contentDescription = stringResource(com.fioiu8.dshpocket.R.string.stop))
                        }
                    } else {
                        FilledIconButton(
                            onClick = onSend,
                            enabled = enabled && value.isNotBlank() && !sending,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(com.fioiu8.dshpocket.R.string.send))
                        }
                    }
                }
            }
        }
    }
}

/** Inline message editing: a multiline field plus cancel/confirm, rendered in the bubble's place. */
@Composable
internal fun MessageEditField(
    initial: String,
    confirmLabel: String,
    busy: Boolean,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var text by rememberSaveable(initial) { mutableStateOf(initial) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                enabled = !busy,
                minLines = 2,
                maxLines = 10,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
                TextButton(onClick = onCancel, enabled = !busy) {
                    Text(stringResource(com.fioiu8.dshpocket.R.string.close))
                }
                TextButton(onClick = { onConfirm(text.trim()) }, enabled = !busy && text.isNotBlank()) {
                    Text(confirmLabel)
                }
            }
        }
    }
}
