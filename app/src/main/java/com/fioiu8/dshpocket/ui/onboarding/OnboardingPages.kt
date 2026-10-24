package com.fioiu8.dshpocket.ui.onboarding

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fioiu8.dshpocket.R
import com.fioiu8.dshpocket.capability.CapabilityState
import com.fioiu8.dshpocket.engine.EnginePaths
import com.fioiu8.dshpocket.engine.EngineState
import com.fioiu8.dshpocket.ui.theme.DshShapes
import com.fioiu8.dshpocket.ui.theme.DshSpacing
import com.fioiu8.dshpocket.ui.theme.dshControl
import com.fioiu8.dshpocket.ui.theme.dshSpring
import com.fioiu8.dshpocket.ui.theme.systemAnimationsEnabled
import kotlinx.coroutines.delay

/**
 * The onboarding flow. Pages are data, so new steps slot in anywhere in the list and
 * the host picks up paging, transitions and persistence without further wiring.
 */
val onboardingPages: List<OnboardingPage> = listOf(
    OnboardingPage(
        key = "welcome",
        icon = Icons.Filled.RocketLaunch,
        content = { WelcomePage(it) },
    ),
    OnboardingPage(
        key = "agreement",
        icon = Icons.Filled.Description,
        canAdvance = { it.agreed },
        content = { AgreementPage(it) },
    ),
    OnboardingPage(
        key = "permissions",
        // Reaching this page is the trigger: the runtime extraction and the engine
        // boot start in the background while the user reads.
        icon = Icons.Filled.Folder,
        showsEngineProgress = true,
        content = { PermissionsPage(it) },
    ),
    OnboardingPage(
        key = "finish",
        icon = Icons.Filled.Celebration,
        showsEngineProgress = true,
        canAdvance = { it.engineState is EngineState.Ready },
        ctaLabel = R.string.onboarding_finish_cta,
        onCta = { it.finish() },
        content = { FinishPage(it) },
    ),
)

@Composable
private fun WelcomePage(actions: OnboardingActions) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = DshSpacing.page + 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Weighted spacers park the text block a little above center; they shrink
        // first, so longer content never overflows and the header icon stays put.
        Spacer(Modifier.weight(0.8f))
        Text(
            stringResource(R.string.onboarding_welcome_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(DshSpacing.compact))
        Text(
            stringResource(R.string.onboarding_welcome_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(DshSpacing.section))
        Surface(
            shape = RoundedCornerShape(DshShapes.surface),
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Row(
                Modifier.padding(DshSpacing.row),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.width(DshSpacing.compact))
                Text(
                    stringResource(R.string.onboarding_welcome_exit_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        Spacer(Modifier.weight(1.2f))
    }
}

@Composable
private fun AgreementPage(actions: OnboardingActions) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = DshSpacing.page),
    ) {
        Spacer(Modifier.height(DshSpacing.section))
        Text(
            stringResource(R.string.onboarding_agreement_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(DshSpacing.section))
        // Only the terms scroll. The consent row below is pinned outside the scroll
        // area, so a small screen can never hide the control the continue button
        // depends on.
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            TermRow(Icons.Filled.Storage, stringResource(R.string.onboarding_terms_local))
            TermRow(Icons.Filled.CloudUpload, stringResource(R.string.onboarding_terms_provider))
            TermRow(Icons.Filled.Extension, stringResource(R.string.onboarding_terms_thirdparty))
            TermRow(Icons.Filled.Settings, stringResource(R.string.onboarding_terms_control))
            Spacer(Modifier.height(DshSpacing.section))
        }
        // Pinned below the scroll, plain and always visible on any screen size.
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(
                    value = actions.env.agreed,
                    role = Role.Checkbox,
                    onValueChange = actions.setAgreed,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = actions.env.agreed, onCheckedChange = null)
            Text(stringResource(R.string.onboarding_agree_checkbox), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun TermRow(icon: ImageVector, text: String) {
    Row(
        Modifier.padding(vertical = DshSpacing.rowVertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(DshSpacing.row))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PermissionsPage(actions: OnboardingActions) {
    // Idempotent on both layers; also re-fires when the user walks back and returns.
    LaunchedEffect(Unit) {
        actions.startEngine()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DshSpacing.page),
    ) {
        Spacer(Modifier.height(DshSpacing.page))
        Text(
            stringResource(R.string.onboarding_permissions_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(DshSpacing.section))
        AllFilesCard(actions)
        Spacer(Modifier.height(DshSpacing.section))
        Surface(
            shape = RoundedCornerShape(DshShapes.surface),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Row(
                Modifier.padding(DshSpacing.row),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(DshSpacing.compact))
                Text(
                    stringResource(R.string.onboarding_permissions_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(DshSpacing.page))
    }
}

@Composable
private fun AllFilesCard(actions: OnboardingActions) {
    val allFiles = actions.env.capabilities.allFiles
    Surface(
        shape = RoundedCornerShape(DshShapes.surface),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(DshSpacing.row)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(DshSpacing.compact))
                Text(
                    stringResource(R.string.settings_all_files),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                when (allFiles) {
                    CapabilityState.GRANTED -> {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = stringResource(R.string.onboarding_all_files_granted),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    else -> {}
                }
            }
            Spacer(Modifier.height(DshSpacing.compact))
            Text(
                stringResource(R.string.onboarding_all_files_body, EnginePaths.SHARED_WORKSPACE),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (allFiles == CapabilityState.AVAILABLE) {
                Spacer(Modifier.height(DshSpacing.compact))
                OutlinedButton(onClick = actions.grantAllFiles, modifier = Modifier.dshControl()) {
                    Text(stringResource(R.string.capability_grant))
                }
            }
        }
    }
}

@Composable
private fun FinishPage(actions: OnboardingActions) {
    val animationsEnabled = systemAnimationsEnabled()
    when (val state = actions.env.engineState) {
        is EngineState.Ready -> {
            var revealed by remember { mutableStateOf(!animationsEnabled) }
            LaunchedEffect(Unit) {
                if (!revealed) {
                    delay(150)
                    revealed = true
                }
            }
            val scale by animateFloatAsState(
                targetValue = if (revealed) 1f else 0.6f,
                animationSpec = dshSpring(animationsEnabled),
                label = "onboarding-check-scale",
            )
            val alpha by animateFloatAsState(
                targetValue = if (revealed) 1f else 0f,
                animationSpec = dshSpring(animationsEnabled),
                label = "onboarding-check-alpha",
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = DshSpacing.page),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(88.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            this.alpha = alpha
                        },
                )
                Spacer(Modifier.height(DshSpacing.section))
                Text(
                    stringResource(R.string.onboarding_finish_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(DshSpacing.compact))
                Text(
                    stringResource(R.string.onboarding_finish_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(DshSpacing.section))
                Surface(
                    shape = RoundedCornerShape(DshShapes.surface),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(DshSpacing.row),
                        verticalArrangement = Arrangement.spacedBy(DshSpacing.compact),
                    ) {
                        SummaryRow(
                            label = stringResource(R.string.settings_local_core),
                            value = stringResource(R.string.onboarding_finish_engine_value, EnginePaths.PORT),
                        )
                        SummaryRow(
                            label = stringResource(R.string.settings_workspace),
                            value = actions.env.capabilities.workspace,
                        )
                        SummaryRow(
                            label = stringResource(R.string.settings_all_files),
                            value = when (actions.env.capabilities.allFiles) {
                                CapabilityState.GRANTED -> stringResource(R.string.onboarding_all_files_granted)
                                else -> stringResource(R.string.capability_available)
                            },
                        )
                    }
                }
            }
        }
        is EngineState.Failed -> {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = DshSpacing.page),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(56.dp),
                )
                Spacer(Modifier.height(DshSpacing.section))
                Text(
                    stringResource(R.string.engine_failed_to_start),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(DshSpacing.compact))
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(DshSpacing.section))
                Button(onClick = actions.retryEngine, modifier = Modifier.dshControl()) {
                    Text(stringResource(R.string.restart))
                }
            }
        }
        else -> {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = DshSpacing.page),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(DshSpacing.section))
                Text(
                    stringResource(R.string.onboarding_finish_waiting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
        )
    }
}
