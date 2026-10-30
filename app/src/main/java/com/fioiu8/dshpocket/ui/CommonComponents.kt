package com.fioiu8.dshpocket.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fioiu8.dshpocket.ui.theme.DshMotion
import com.fioiu8.dshpocket.ui.theme.DshShapes
import com.fioiu8.dshpocket.ui.theme.DshSpacing
import com.fioiu8.dshpocket.ui.theme.motionDuration

@Composable
fun CenteredMessage(title: String, body: String, busy: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(DshSpacing.page),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (busy) CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        if (body.isNotBlank()) {
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = DshSpacing.page, vertical = DshSpacing.section),
    )
}

/** Shared grouped list container with stable keys and a staggered entrance. */
@Composable
fun GroupedList(
    modifier: Modifier = Modifier,
    items: List<@Composable () -> Unit>,
    keys: List<Any>? = null,
) {
    val shape = RoundedCornerShape(DshShapes.surface)
    val listAnimationDuration = motionDuration(180)
    Surface(
        modifier = modifier.border(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
            shape,
        ),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Column {
            items.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(
                    modifier = Modifier.padding(horizontal = DshSpacing.page),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                )
                val entrance = remember { MutableTransitionState(false).apply { targetState = true } }
                AnimatedVisibility(
                    visibleState = entrance,
                    enter = fadeIn(
                        animationSpec = tween(
                            durationMillis = listAnimationDuration,
                            delayMillis = if (listAnimationDuration == 0) 0 else {
                                index.coerceAtMost(DshMotion.maxStaggerItems - 1) * DshMotion.staggerStepMillis
                            },
                        ),
                    ) + slideInVertically(
                        animationSpec = tween(
                            durationMillis = listAnimationDuration,
                            delayMillis = if (listAnimationDuration == 0) 0 else {
                                index.coerceAtMost(DshMotion.maxStaggerItems - 1) * DshMotion.staggerStepMillis
                            },
                        ),
                        initialOffsetY = { it / 32 },
                    ),
                ) {
                    if (keys != null && index < keys.size) {
                        key(keys[index]) { item() }
                    } else {
                        item()
                    }
                }
            }
        }
    }
}

@Composable
fun LogPanel(log: List<String>, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DshSpacing.page, vertical = DshSpacing.compact),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        log.takeLast(120).forEach {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
fun KeyValueRow(key: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = DshSpacing.touchTarget)
            .padding(horizontal = DshSpacing.row, vertical = DshSpacing.rowVertical),
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.row),
        verticalAlignment = Alignment.Top,
    ) {
        Text(key, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.8f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1.2f),
        )
    }
}
