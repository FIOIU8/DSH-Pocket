package com.fioiu8.dshpocket.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.animateContentSize
import android.os.Build
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.fioiu8.dshpocket.capability.CapabilitySnapshot
import com.fioiu8.dshpocket.capability.CapabilityState
import com.fioiu8.dshpocket.capability.CompanionStatus
import com.fioiu8.dshpocket.capability.ProcessExecutionCapability
import com.fioiu8.dshpocket.engine.EnginePaths
import com.fioiu8.dshpocket.engine.EngineState
import com.fioiu8.dshpocket.engine.RuntimeInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.fioiu8.dshpocket.ui.theme.DshShapes
import com.fioiu8.dshpocket.ui.theme.DshSpacing
import com.fioiu8.dshpocket.ui.theme.dshSpring
import com.fioiu8.dshpocket.ui.theme.dshControl
import com.fioiu8.dshpocket.ui.theme.systemAnimationsEnabled

// @param: keeps the annotations on the constructor parameters only. Without the
// explicit target, Kotlin 2.2 warns (KT-73255) that a future release will also
// apply them to the backing fields.
enum class SettingsSection(
    val key: String,
    @param:androidx.annotation.StringRes val labelRes: Int,
    @param:androidx.annotation.StringRes val supportingRes: Int,
    val icon: ImageVector,
) {
    Engine("engine", com.fioiu8.dshpocket.R.string.settings_engine, com.fioiu8.dshpocket.R.string.settings_engine_supporting, Icons.Filled.Memory),
    Core("core", com.fioiu8.dshpocket.R.string.settings_core, com.fioiu8.dshpocket.R.string.settings_core_supporting, Icons.Filled.Tune),
    Runtime("runtime", com.fioiu8.dshpocket.R.string.settings_runtime, com.fioiu8.dshpocket.R.string.settings_runtime_supporting, Icons.Filled.Folder),
    General("general", com.fioiu8.dshpocket.R.string.settings_general, com.fioiu8.dshpocket.R.string.settings_general_supporting, Icons.Filled.Info),
    ;

    companion object {
        fun fromKey(key: String): SettingsSection? = entries.firstOrNull { it.key == key }
    }
}

@Composable
fun SettingsHomeScreen(
    onMenu: () -> Unit,
    onOpenSection: (SettingsSection) -> Unit,
) {
    Scaffold(topBar = { DshTopAppBar(title = stringResource(com.fioiu8.dshpocket.R.string.nav_settings), onMenu = onMenu) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(DshSpacing.compact),
        ) {
            Text(
                stringResource(com.fioiu8.dshpocket.R.string.settings_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = DshSpacing.page, vertical = DshSpacing.section),
            )
            GroupedList(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DshSpacing.page),
                items = SettingsSection.entries.map { section ->
                    {
                        SettingsCategoryRow(section, onClick = { onOpenSection(section) })
                    }
                },
            )
        }
    }
}

@Composable
private fun SettingsCategoryRow(section: SettingsSection, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(section.labelRes)) },
        supportingContent = { Text(stringResource(section.supportingRes)) },
        leadingContent = {
            Icon(section.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        trailingContent = {
            Icon(Icons.Filled.ChevronRight, contentDescription = stringResource(com.fioiu8.dshpocket.R.string.settings_open_section, stringResource(section.labelRes)))
        },
        modifier = Modifier.heightIn(min = DshSpacing.touchTarget).clickable(onClick = onClick),
    )
}

@Composable
fun SettingsDetailScreen(
    section: SettingsSection,
    state: EngineState,
    log: List<String>,
    capabilities: CapabilitySnapshot,
    chatPresentation: ChatPresentation,
    onChatPresentationChange: (ChatPresentation) -> Unit,
    nativeChatPreferences: NativeChatPreferences,
    onNativeChatPreferencesChange: (NativeChatPreferences) -> Unit,
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenPlugins: () -> Unit,
    onGrantAllFiles: () -> Unit,
    onRestart: () -> Unit,
    onStop: () -> Unit,
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
) {
    val context = LocalContext.current
    val animationsEnabled = systemAnimationsEnabled()
    var runtimeSize by remember { mutableStateOf<Long?>(null) }
    var dshVersion by remember { mutableStateOf<String?>(null) }
    var nodeVersion by remember { mutableStateOf<String?>(null) }
    var workspacePath by remember { mutableStateOf("") }
    var showLog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val root = EnginePaths.root(context)
        val dshHome = EnginePaths.dshHome(context)
        // One IO hop for the whole probe; the outer effect coroutine suspends instead of
        // spawning a second, unmanaged scope for it.
        withContext(Dispatchers.IO) {
            runtimeSize = RuntimeInstaller.sizeBytes(root)
            dshVersion = EnginePaths.dshVersion(root)
            nodeVersion = EnginePaths.probeNodeVersion(root, dshHome)
            workspacePath = EnginePaths.workspace(context).absolutePath
        }
    }

    Scaffold(
        topBar = { DshTopAppBar(title = stringResource(section.labelRes), onMenu = onBack, onBack = onBack) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(DshSpacing.compact),
        ) {
            when (section) {
                SettingsSection.Engine -> {
                    SectionHeader(stringResource(com.fioiu8.dshpocket.R.string.settings_core_status))
                    EngineCard(state, showLog, onRestart, onStop) { showLog = !showLog }
                    AnimatedVisibility(
                        visible = showLog,
                        enter = fadeIn(animationSpec = dshSpring(animationsEnabled)) +
                            expandVertically(animationSpec = dshSpring(animationsEnabled)),
                        exit = fadeOut(animationSpec = dshSpring(animationsEnabled)) +
                            shrinkVertically(animationSpec = dshSpring(animationsEnabled)),
                    ) {
                        Column {
                            SectionHeader(stringResource(com.fioiu8.dshpocket.R.string.settings_engine_log))
                            LogPanel(log, Modifier.fillMaxWidth())
                        }
                    }
                }
                SettingsSection.Core -> {
                    SectionHeader(stringResource(com.fioiu8.dshpocket.R.string.settings_core_entry))
                    GroupedList(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = DshSpacing.page),
                        items = listOf(
                            { SectionRow(stringResource(com.fioiu8.dshpocket.R.string.nav_models), stringResource(com.fioiu8.dshpocket.R.string.home_models_supporting), Icons.Filled.Tune, onOpenModels) },
                            { SectionRow(stringResource(com.fioiu8.dshpocket.R.string.nav_plugins), stringResource(com.fioiu8.dshpocket.R.string.home_plugins_supporting), Icons.Filled.Extension, onOpenPlugins) },
                        ),
                    )
                    SectionHeader(stringResource(com.fioiu8.dshpocket.R.string.settings_device_capabilities))
                    CapabilityCard(capabilities, onGrantAllFiles)
                    HelperText(stringResource(com.fioiu8.dshpocket.R.string.settings_engine_default_permission))
                }
                SettingsSection.Runtime -> {
                    SectionHeader(stringResource(com.fioiu8.dshpocket.R.string.settings_runtime_section))
                    GroupedList(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = DshSpacing.page),
                        items = listOf(
                            { KeyValueRow(stringResource(com.fioiu8.dshpocket.R.string.settings_bundled_abi), RuntimeInstaller.BUNDLED_ABI) },
                            { KeyValueRow(stringResource(com.fioiu8.dshpocket.R.string.settings_device_abi), Build.SUPPORTED_ABIS.joinToString(", ")) },
                            { KeyValueRow(stringResource(com.fioiu8.dshpocket.R.string.settings_node), nodeVersion ?: stringResource(com.fioiu8.dshpocket.R.string.loading)) },
                            { KeyValueRow(stringResource(com.fioiu8.dshpocket.R.string.settings_dsh), dshVersion ?: stringResource(com.fioiu8.dshpocket.R.string.loading)) },
                            { KeyValueRow(stringResource(com.fioiu8.dshpocket.R.string.settings_storage), runtimeSize?.let { "%.0f MB".format(it / 1024.0 / 1024.0) } ?: stringResource(com.fioiu8.dshpocket.R.string.loading)) },
                            { KeyValueRow(stringResource(com.fioiu8.dshpocket.R.string.settings_path), EnginePaths.root(context).absolutePath) },
                        ),
                    )
                    SectionHeader(stringResource(com.fioiu8.dshpocket.R.string.settings_workspace))
                    GroupedList(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = DshSpacing.page),
                        items = listOf { KeyValueRow(stringResource(com.fioiu8.dshpocket.R.string.settings_current), workspacePath.ifBlank { stringResource(com.fioiu8.dshpocket.R.string.loading) }) },
                    )
                    HelperText(stringResource(com.fioiu8.dshpocket.R.string.settings_shared_workspace_hint, EnginePaths.SHARED_WORKSPACE))
                }
                SettingsSection.General -> {
                    SectionHeader(stringResource(com.fioiu8.dshpocket.R.string.settings_preferences))
                    Text(
                        stringResource(com.fioiu8.dshpocket.R.string.settings_chat_interface),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = DshSpacing.page, vertical = DshSpacing.compact),
                    )
                    SingleChoiceSegmentedButtonRow(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = DshSpacing.page),
                    ) {
                        ChatPresentation.entries.forEachIndexed { index, presentation ->
                            SegmentedButton(
                                selected = chatPresentation == presentation,
                                onClick = { onChatPresentationChange(presentation) },
                                shape = SegmentedButtonDefaults.itemShape(index, ChatPresentation.entries.size),
                                modifier = Modifier.heightIn(min = DshSpacing.controlHeight),
                            ) {
                                Text(stringResource(presentation.labelRes))
                            }
                        }
                    }
                    HelperText(stringResource(com.fioiu8.dshpocket.R.string.settings_chat_interface_hint))
                    HelperText(stringResource(com.fioiu8.dshpocket.R.string.settings_native_theme_hint))
                    SectionHeader(stringResource(com.fioiu8.dshpocket.R.string.settings_native_chat_info))
                    GroupedList(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = DshSpacing.page),
                        items = listOf(
                            {
                                ChatPreferenceRow(
                                    title = stringResource(com.fioiu8.dshpocket.R.string.settings_message_time),
                                    supporting = stringResource(com.fioiu8.dshpocket.R.string.settings_message_time_supporting),
                                    icon = Icons.Filled.Schedule,
                                    checked = nativeChatPreferences.showMessageTime,
                                    onCheckedChange = {
                                        onNativeChatPreferencesChange(nativeChatPreferences.copy(showMessageTime = it))
                                    },
                                )
                            },
                            {
                                ChatPreferenceRow(
                                    title = stringResource(com.fioiu8.dshpocket.R.string.settings_tokens),
                                    supporting = stringResource(com.fioiu8.dshpocket.R.string.settings_tokens_supporting),
                                    icon = Icons.Filled.DataUsage,
                                    checked = nativeChatPreferences.showTokenUsage,
                                    onCheckedChange = {
                                        onNativeChatPreferencesChange(nativeChatPreferences.copy(showTokenUsage = it))
                                    },
                                )
                            },
                            {
                                ChatPreferenceRow(
                                    title = stringResource(com.fioiu8.dshpocket.R.string.settings_duration),
                                    supporting = stringResource(com.fioiu8.dshpocket.R.string.settings_duration_supporting),
                                    icon = Icons.Filled.Schedule,
                                    checked = nativeChatPreferences.showDuration,
                                    onCheckedChange = {
                                        onNativeChatPreferencesChange(nativeChatPreferences.copy(showDuration = it))
                                    },
                                )
                            },
                            {
                                ChatPreferenceRow(
                                    title = stringResource(com.fioiu8.dshpocket.R.string.settings_assist_reply),
                                    supporting = stringResource(com.fioiu8.dshpocket.R.string.settings_assist_reply_supporting),
                                    icon = Icons.Filled.AutoAwesome,
                                    checked = nativeChatPreferences.showAssistReply,
                                    onCheckedChange = {
                                        onNativeChatPreferencesChange(nativeChatPreferences.copy(showAssistReply = it))
                                    },
                                )
                            },
                        ),
                    )
                    HelperText(stringResource(com.fioiu8.dshpocket.R.string.settings_native_chat_info_hint))
                    GroupedList(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = DshSpacing.page),
                        items = listOf(
                            {
                                ListItem(
                                    headlineContent = { Text(stringResource(com.fioiu8.dshpocket.R.string.settings_appearance)) },
                                    supportingContent = { Text(stringResource(com.fioiu8.dshpocket.R.string.settings_appearance_supporting)) },
                                    leadingContent = { Icon(Icons.Filled.Palette, contentDescription = null) },
                                )
                            },
                            {
                                ListItem(
                                    headlineContent = { Text(stringResource(com.fioiu8.dshpocket.R.string.settings_language)) },
                                    supportingContent = { Text(languageLabel(language)) },
                                    leadingContent = { Icon(Icons.Filled.Info, contentDescription = null) },
                                    modifier = Modifier.clickable { showLanguageDialog = true },
                                )
                            },
                            {
                                ListItem(
                                    headlineContent = { Text(stringResource(com.fioiu8.dshpocket.R.string.settings_about)) },
                                    supportingContent = { Text(stringResource(com.fioiu8.dshpocket.R.string.settings_about_supporting)) },
                                    leadingContent = { Icon(Icons.Filled.Memory, contentDescription = null) },
                                )
                            },
                        ),
                    )
                }
            }
        }
    }

    if (showLanguageDialog) {
        // Two steps on purpose: picking an entry only records the choice, the confirm
        // button applies it and recreates the activity. A stray tap used to rebuild the
        // whole app (WebView included) with no way back.
        var pendingLanguage by remember { mutableStateOf(language) }
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(stringResource(com.fioiu8.dshpocket.R.string.language_dialog_title)) },
            text = {
                Column {
                    AppLanguage.entries.forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { pendingLanguage = option }
                                .padding(vertical = 6.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = pendingLanguage == option, onClick = { pendingLanguage = option })
                            Text(languageLabel(option))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = pendingLanguage != language,
                    onClick = {
                        showLanguageDialog = false
                        onLanguageChange(pendingLanguage)
                    },
                ) { Text(stringResource(com.fioiu8.dshpocket.R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { showLanguageDialog = false }) { Text(stringResource(com.fioiu8.dshpocket.R.string.cancel)) } },
        )
    }
}

@Composable
private fun languageLabel(language: AppLanguage): String = when (language) {
    AppLanguage.SYSTEM -> stringResource(com.fioiu8.dshpocket.R.string.language_system)
    AppLanguage.ZH_CN -> stringResource(com.fioiu8.dshpocket.R.string.language_zh_cn)
    AppLanguage.EN -> stringResource(com.fioiu8.dshpocket.R.string.language_en)
}

@Composable
private fun ChatPreferenceRow(
    title: String,
    supporting: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(supporting) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
        modifier = Modifier.heightIn(min = DshSpacing.touchTarget),
    )
}

@Composable
private fun HelperText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = DshSpacing.page, vertical = DshSpacing.compact),
    )
}

@Composable
private fun EngineCard(state: EngineState, showLog: Boolean, onRestart: () -> Unit, onStop: () -> Unit, onToggleLog: () -> Unit) {
    val animationsEnabled = systemAnimationsEnabled()
    // One-second heartbeat so the "running for…" readout actually counts up instead of
    // freezing at the value of whatever recomposition last happened to render it.
    var uptimeNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state) {
        if (state !is EngineState.Ready) return@LaunchedEffect
        while (true) {
            delay(1_000)
            uptimeNow = System.currentTimeMillis()
        }
    }
    val statusText = when (state) {
        is EngineState.Ready -> stringResource(com.fioiu8.dshpocket.R.string.settings_status_running, state.baseUrl, formatDuration(uptimeNow - state.since))
        is EngineState.Busy -> stringResource(com.fioiu8.dshpocket.R.string.settings_status_starting, state.message)
        is EngineState.Failed -> stringResource(com.fioiu8.dshpocket.R.string.settings_status_failed, state.message)
        EngineState.Idle -> stringResource(com.fioiu8.dshpocket.R.string.settings_status_stopped)
    }
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DshSpacing.page)
            .animateContentSize(animationSpec = dshSpring(animationsEnabled)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(DshShapes.surface),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            ListItem(
                headlineContent = { Text(stringResource(com.fioiu8.dshpocket.R.string.settings_local_core), fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text(statusText) },
                leadingContent = {
                    Icon(
                        Icons.Filled.Memory,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                modifier = Modifier.heightIn(min = DshSpacing.touchTarget),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(onClick = onRestart, modifier = Modifier.dshControl()) { Text(stringResource(com.fioiu8.dshpocket.R.string.restart)) }
                TextButton(onClick = onStop, modifier = Modifier.dshControl()) { Text(stringResource(com.fioiu8.dshpocket.R.string.settings_stop)) }
                TextButton(onClick = onToggleLog, modifier = Modifier.dshControl()) { Text(if (showLog) stringResource(com.fioiu8.dshpocket.R.string.settings_hide_log) else stringResource(com.fioiu8.dshpocket.R.string.settings_view_log)) }
            }
        }
    }
}

@Composable
private fun SectionRow(
    title: String,
    supporting: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(supporting) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = Modifier.heightIn(min = DshSpacing.touchTarget).clickable(onClick = onClick),
    )
}

/** 能力档位卡（D12 阶梯的可见态；从首页移入设置页）。 */
@Composable
fun CapabilityCard(capabilities: CapabilitySnapshot, onGrantAllFiles: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            CapabilityRow(
                title = stringResource(com.fioiu8.dshpocket.R.string.settings_all_files),
                supporting = stringResource(com.fioiu8.dshpocket.R.string.settings_all_files_supporting),
                capabilityState = capabilities.allFiles,
                actionLabel = stringResource(com.fioiu8.dshpocket.R.string.capability_grant),
                onAction = onGrantAllFiles,
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            CompanionCapabilityRow(
                title = "Termux",
                supporting = stringResource(com.fioiu8.dshpocket.R.string.settings_termux_supporting),
                status = capabilities.termux,
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            CompanionCapabilityRow(
                title = "Shizuku",
                supporting = stringResource(com.fioiu8.dshpocket.R.string.settings_shizuku_supporting),
                status = capabilities.shizuku,
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            ProcessExecutionRow(capabilities)
        }
    }
}

@Composable
private fun CompanionCapabilityRow(title: String, supporting: String, status: CompanionStatus) {
    val label = when (status) {
        CompanionStatus.NOT_INSTALLED -> stringResource(com.fioiu8.dshpocket.R.string.capability_not_installed)
        CompanionStatus.INSTALLED -> stringResource(com.fioiu8.dshpocket.R.string.capability_installed_waiting)
        CompanionStatus.AUTHORIZED -> stringResource(com.fioiu8.dshpocket.R.string.capability_authorized_waiting)
        CompanionStatus.RUNNING -> stringResource(com.fioiu8.dshpocket.R.string.capability_running_disconnected)
        CompanionStatus.BRIDGE_CONNECTED -> stringResource(com.fioiu8.dshpocket.R.string.capability_bridge_connected)
        CompanionStatus.UNSUPPORTED -> stringResource(com.fioiu8.dshpocket.R.string.capability_unsupported)
    }
    val color = when (status) {
        CompanionStatus.BRIDGE_CONNECTED -> MaterialTheme.colorScheme.primary
        CompanionStatus.INSTALLED, CompanionStatus.AUTHORIZED, CompanionStatus.RUNNING -> MaterialTheme.colorScheme.tertiary
        CompanionStatus.NOT_INSTALLED, CompanionStatus.UNSUPPORTED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = DshSpacing.rowVertical),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun ProcessExecutionRow(capabilities: CapabilitySnapshot) {
    val execution = capabilities.processExecution
    val reason = (execution as? ProcessExecutionCapability.Unavailable)
        ?.reason
        ?.takeIf(String::isNotBlank)
    val available = execution is ProcessExecutionCapability.Available
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = DshSpacing.rowVertical),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(stringResource(com.fioiu8.dshpocket.R.string.settings_shell_execution), style = MaterialTheme.typography.titleSmall)
        Text(
            reason ?: if (available) {
                stringResource(com.fioiu8.dshpocket.R.string.settings_shell_supporting, (execution as ProcessExecutionCapability.Available).backend.displayName)
            } else {
                stringResource(com.fioiu8.dshpocket.R.string.settings_shell_unavailable)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (available) stringResource(com.fioiu8.dshpocket.R.string.capability_available_label) else stringResource(com.fioiu8.dshpocket.R.string.capability_unavailable_label),
            style = MaterialTheme.typography.labelMedium,
            color = if (available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CapabilityRow(
    title: String,
    supporting: String,
    capabilityState: CapabilityState,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = DshSpacing.touchTarget)
            .padding(horizontal = 16.dp, vertical = DshSpacing.rowVertical),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = capabilityState.label(),
                style = MaterialTheme.typography.labelMedium,
                color = when (capabilityState) {
                    CapabilityState.GRANTED -> MaterialTheme.colorScheme.primary
                    CapabilityState.INSTALLED, CapabilityState.AVAILABLE -> MaterialTheme.colorScheme.tertiary
                    CapabilityState.NOT_INSTALLED, CapabilityState.UNSUPPORTED ->
                        MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (actionLabel != null && capabilityState == CapabilityState.AVAILABLE) {
            OutlinedButton(onClick = onAction, modifier = Modifier.dshControl()) { Text(actionLabel) }
        }
    }
}

@Composable
fun CapabilityState.label(): String = when (this) {
    CapabilityState.GRANTED -> stringResource(com.fioiu8.dshpocket.R.string.capability_granted)
    CapabilityState.AVAILABLE -> stringResource(com.fioiu8.dshpocket.R.string.capability_available)
    CapabilityState.INSTALLED -> stringResource(com.fioiu8.dshpocket.R.string.capability_installed_pending)
    CapabilityState.NOT_INSTALLED -> stringResource(com.fioiu8.dshpocket.R.string.capability_not_installed)
    CapabilityState.UNSUPPORTED -> stringResource(com.fioiu8.dshpocket.R.string.capability_system_unsupported)
}
