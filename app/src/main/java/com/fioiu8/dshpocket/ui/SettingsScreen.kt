package com.fioiu8.dshpocket.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.TouchApp
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import android.net.Uri
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.fioiu8.dshpocket.backup.BackupManager
import com.fioiu8.dshpocket.capability.CapabilitySnapshot
import com.fioiu8.dshpocket.core.AppLanguage
import com.fioiu8.dshpocket.capability.CapabilityState
import com.fioiu8.dshpocket.capability.CompanionStatus
import com.fioiu8.dshpocket.capability.ProcessExecutionCapability
import com.fioiu8.dshpocket.engine.EnginePaths
import com.fioiu8.dshpocket.engine.EngineState
import com.fioiu8.dshpocket.engine.RuntimeInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    ScreenControl("screen_control", com.fioiu8.dshpocket.R.string.settings_screen_control, com.fioiu8.dshpocket.R.string.settings_screen_control_supporting, Icons.Filled.TouchApp),
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
    onOpenTerminal: () -> Unit,
    onOpenScreenControl: () -> Unit,
    onGrantAllFiles: () -> Unit,
    onRestart: () -> Unit,
    onStop: () -> Unit,
    onScreenControlChanged: () -> Unit,
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
                            { SectionRow(stringResource(com.fioiu8.dshpocket.R.string.nav_terminal), stringResource(com.fioiu8.dshpocket.R.string.terminal_supporting), Icons.Filled.Terminal, onOpenTerminal) },
                            { SectionRow(stringResource(com.fioiu8.dshpocket.R.string.settings_screen_control), stringResource(com.fioiu8.dshpocket.R.string.settings_screen_control_supporting), Icons.Filled.TouchApp, onOpenScreenControl) },
                        ),
                    )
                    SectionHeader(stringResource(com.fioiu8.dshpocket.R.string.settings_device_capabilities))
                    CapabilityCard(capabilities, onGrantAllFiles)
                    HelperText(stringResource(com.fioiu8.dshpocket.R.string.settings_engine_default_permission))
                }
                SettingsSection.ScreenControl -> ScreenControlSection(onChanged = onScreenControlChanged)
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
                    SectionHeader(stringResource(com.fioiu8.dshpocket.R.string.settings_backup_section))
                    BackupSection(state)
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
        is EngineState.Busy -> stringResource(com.fioiu8.dshpocket.R.string.settings_status_starting, state.message.asString())
        is EngineState.Failed -> stringResource(com.fioiu8.dshpocket.R.string.settings_status_failed, state.message.asString())
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

/**
 * 备份与恢复（设置 → 运行时）。
 *
 * 导出走 SAF 的 CreateDocument（无需存储权限），把 `$DSH_HOME` 打成 zip——凭据目录、
 * `.credentials*`、`.ssh`、`.gnupg`、`*.key` 与日志被排除（[BackupManager.isExcluded]）。
 * 恢复走 OpenDocument，要求引擎已停止：整个恢复先解到临时目录，全部校验通过才原子
 * 换入（[BackupManager.restore]），坏档不会毁掉现有数据。API 密钥不在备份里，恢复后
 * 需要重新输入——这是"凭据不出设备"的代价，在帮助文案里明说。
 */
@Composable
private fun BackupSection(state: EngineState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var pendingRestore by remember { mutableStateOf<Uri?>(null) }
    // Format-arg variants: the arguments (counts, engine reasons) only exist inside the
    // result lambdas, so what is captured is a formatter over LocalResources — which
    // recomposes on configuration change — rather than LocalContext.getString, which
    // would freeze the locale (the LocalContextGetResourceValueCall lint rule).
    val resources = LocalResources.current

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        status = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        BackupManager.export(
                            EnginePaths.dshHome(context),
                            output,
                            BackupManager.Manifest(
                                schemaVersion = BackupManager.SCHEMA_VERSION,
                                createdAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                                    .format(Date()),
                                appVersion = runCatching {
                                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                                }.getOrNull().orEmpty(),
                                dshVersion = EnginePaths.dshVersion(EnginePaths.root(context)),
                                fileCount = 0,
                            ),
                        )
                    } ?: error("could not open the chosen document")
                }
            }
            busy = false
            status = outcome.fold(
                onSuccess = { result ->
                    if (result.fileCount == 0) {
                        resources.getString(com.fioiu8.dshpocket.R.string.backup_empty)
                    } else {
                        resources.getString(com.fioiu8.dshpocket.R.string.backup_export_done, result.fileCount)
                    }
                },
                onFailure = { resources.getString(com.fioiu8.dshpocket.R.string.backup_failed, it.message.orEmpty()) },
            )
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) pendingRestore = uri
    }

    fun doRestore(uri: Uri) {
        busy = true
        status = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        BackupManager.restore(input, EnginePaths.dshHome(context))
                    } ?: error("could not open the chosen document")
                }
            }
            busy = false
            status = outcome.fold(
                onSuccess = { result ->
                    when (result) {
                        is BackupManager.RestoreOutcome.Done ->
                            resources.getString(com.fioiu8.dshpocket.R.string.backup_restore_done, result.fileCount)
                        is BackupManager.RestoreOutcome.BadArchive ->
                            resources.getString(com.fioiu8.dshpocket.R.string.backup_bad_archive, result.reason)
                    }
                },
                onFailure = { resources.getString(com.fioiu8.dshpocket.R.string.backup_failed, it.message.orEmpty()) },
            )
        }
    }

    val engineStopped = state is EngineState.Idle || state is EngineState.Failed
    Column(Modifier.fillMaxWidth().padding(horizontal = DshSpacing.page)) {
        ListItem(
            headlineContent = { Text(stringResource(com.fioiu8.dshpocket.R.string.backup_export)) },
            supportingContent = { Text(stringResource(com.fioiu8.dshpocket.R.string.backup_export_supporting)) },
            leadingContent = { Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            modifier = Modifier
                .heightIn(min = DshSpacing.touchTarget)
                .clickable(enabled = !busy) {
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                    exportLauncher.launch("dsh-pocket-backup-$stamp.zip")
                },
        )
        ListItem(
            headlineContent = { Text(stringResource(com.fioiu8.dshpocket.R.string.backup_restore)) },
            supportingContent = {
                Text(
                    if (engineStopped) stringResource(com.fioiu8.dshpocket.R.string.backup_restore_supporting)
                    else stringResource(com.fioiu8.dshpocket.R.string.backup_restore_needs_stop),
                    color = if (engineStopped) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            },
            leadingContent = { Icon(Icons.Filled.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            modifier = Modifier
                .heightIn(min = DshSpacing.touchTarget)
                .clickable(enabled = !busy && engineStopped) {
                    importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed"))
                },
        )
        if (busy) {
            HelperText(stringResource(com.fioiu8.dshpocket.R.string.backup_working))
        }
        status?.let { HelperText(it) }
    }

    pendingRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text(stringResource(com.fioiu8.dshpocket.R.string.backup_restore_confirm_title)) },
            text = { Text(stringResource(com.fioiu8.dshpocket.R.string.backup_restore_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRestore = null
                        doRestore(uri)
                    },
                ) { Text(stringResource(com.fioiu8.dshpocket.R.string.backup_restore)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null }) { Text(stringResource(com.fioiu8.dshpocket.R.string.cancel)) }
            },
        )
    }
}

/**
 * 设置 → AI 操作屏幕（plan §2.6）。Reads and writes [ScreenControlSettings] directly —
 * the same UI-owned-prefs convention as the chat preferences. Any change invokes
 * [onChanged] so the shell can refresh the agent briefing without an engine restart.
 */
@Composable
private fun ScreenControlSection(onChanged: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val settings = remember { com.fioiu8.dshpocket.screen.ScreenControlSettings(context) }
    val audit = remember { com.fioiu8.dshpocket.screen.ScreenAuditLog(context) }
    val scope = rememberCoroutineScope()

    var enabled by remember { mutableStateOf(settings.enabled) }
    var accessibilityReady by remember { mutableStateOf(com.fioiu8.dshpocket.screen.PocketAccessibilityService.peek() != null) }
    var overlayGranted by remember { mutableStateOf(android.provider.Settings.canDrawOverlays(context)) }
    var reviewEnabled by remember { mutableStateOf(settings.reviewEnabled) }
    var askEveryTime by remember { mutableStateOf(settings.askEveryTime) }
    var thoughtOverlay by remember { mutableStateOf(settings.thoughtOverlayEnabled) }
    var reviewBaseUrl by remember { mutableStateOf(settings.reviewBaseUrl) }
    var reviewModel by remember { mutableStateOf(settings.reviewModel) }
    var reviewKeyDraft by remember { mutableStateOf("") }
    var reviewKeyConfigured by remember { mutableStateOf(settings.reviewApiKey.isNotEmpty()) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var denyDraft by remember { mutableStateOf<String?>(null) }
    var auditEntries by remember { mutableStateOf(audit.entries()) }

    SectionHeader(stringResource(com.fioiu8.dshpocket.R.string.sc_section_master))
    GroupedList(
        modifier = Modifier.fillMaxWidth().padding(horizontal = DshSpacing.page),
        items = listOf {
            ListItem(
                headlineContent = { Text(stringResource(com.fioiu8.dshpocket.R.string.settings_screen_control), fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text(stringResource(com.fioiu8.dshpocket.R.string.sc_master_supporting)) },
                leadingContent = { Icon(Icons.Filled.TouchApp, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                trailingContent = {
                    Switch(
                        checked = enabled,
                        onCheckedChange = { checked ->
                            enabled = checked
                            settings.enabled = checked
                            onChanged()
                        },
                    )
                },
                modifier = Modifier.heightIn(min = DshSpacing.touchTarget),
            )
        },
    )

    SectionHeader(stringResource(com.fioiu8.dshpocket.R.string.sc_section_prereq))
    GroupedList(
        modifier = Modifier.fillMaxWidth().padding(horizontal = DshSpacing.page),
        items = listOf(
            {
                PermissionGuideRow(
                    title = stringResource(com.fioiu8.dshpocket.R.string.sc_accessibility_title),
                    supporting = stringResource(com.fioiu8.dshpocket.R.string.sc_accessibility_supporting),
                    granted = accessibilityReady,
                    actionLabel = stringResource(com.fioiu8.dshpocket.R.string.capability_grant),
                    onAction = { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                )
            },
            {
                PermissionGuideRow(
                    title = stringResource(com.fioiu8.dshpocket.R.string.sc_overlay_title),
                    supporting = stringResource(com.fioiu8.dshpocket.R.string.sc_overlay_supporting),
                    granted = overlayGranted,
                    actionLabel = stringResource(com.fioiu8.dshpocket.R.string.capability_grant),
                    onAction = {
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    },
                )
            },
        ),
    )
    HelperText(stringResource(com.fioiu8.dshpocket.R.string.sc_prereq_hint))

    SectionHeader(stringResource(com.fioiu8.dshpocket.R.string.sc_section_review))
    GroupedList(
        modifier = Modifier.fillMaxWidth().padding(horizontal = DshSpacing.page),
        items = listOf(
            {
                ListItem(
                    headlineContent = { Text(stringResource(com.fioiu8.dshpocket.R.string.sc_review_switch)) },
                    supportingContent = { Text(stringResource(com.fioiu8.dshpocket.R.string.sc_review_switch_supporting)) },
                    trailingContent = {
                        Switch(
                            checked = reviewEnabled,
                            onCheckedChange = { checked ->
                                reviewEnabled = checked
                                settings.reviewEnabled = checked
                                onChanged()
                            },
                        )
                    },
                    modifier = Modifier.heightIn(min = DshSpacing.touchTarget),
                )
            },
            {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = DshSpacing.rowVertical)) {
                    OutlinedTextField(
                        value = reviewBaseUrl,
                        onValueChange = { reviewBaseUrl = it },
                        label = { Text(stringResource(com.fioiu8.dshpocket.R.string.sc_review_base_url)) },
                        placeholder = { Text("https://api.example.com/v1") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = reviewModel,
                        onValueChange = { reviewModel = it },
                        label = { Text(stringResource(com.fioiu8.dshpocket.R.string.sc_review_model)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    OutlinedTextField(
                        value = reviewKeyDraft,
                        onValueChange = { reviewKeyDraft = it },
                        label = { Text(stringResource(com.fioiu8.dshpocket.R.string.models_api_key)) },
                        supportingText = {
                            Text(
                                if (reviewKeyConfigured) {
                                    stringResource(com.fioiu8.dshpocket.R.string.models_api_key_configured)
                                } else {
                                    stringResource(com.fioiu8.dshpocket.R.string.models_api_key_missing)
                                },
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            enabled = !testing,
                            onClick = {
                                scope.launch {
                                    testing = true
                                    testResult = withContext(Dispatchers.IO) {
                                        settings.reviewBaseUrl = reviewBaseUrl.trim()
                                        settings.reviewModel = reviewModel.trim()
                                        if (reviewKeyDraft.isNotBlank()) {
                                            settings.reviewApiKey = reviewKeyDraft
                                            reviewKeyConfigured = true
                                            reviewKeyDraft = ""
                                        }
                                        val verdict = com.fioiu8.dshpocket.screen.ScreenReviewClient(settings).review(
                                            com.fioiu8.dshpocket.screen.ScreenOpRequest(
                                                com.fioiu8.dshpocket.screen.ScreenAction.TAP,
                                                "连接测试",
                                                x = 100,
                                                y = 100,
                                            ),
                                            "com.example.test",
                                            null,
                                        )
                                        if (verdict.available) {
                                            resources.getString(com.fioiu8.dshpocket.R.string.sc_review_test_ok, verdict.dangerous.toString())
                                        } else {
                                            resources.getString(com.fioiu8.dshpocket.R.string.sc_review_test_fail, verdict.reason)
                                        }
                                    }
                                    testing = false
                                }
                            },
                            modifier = Modifier.dshControl(),
                        ) {
                            Text(if (testing) stringResource(com.fioiu8.dshpocket.R.string.sc_review_testing) else stringResource(com.fioiu8.dshpocket.R.string.sc_review_test))
                        }
                    }
                    testResult?.let { HelperText(it) }
                }
            },
        ),
    )

    SectionHeader(stringResource(com.fioiu8.dshpocket.R.string.sc_section_gate))
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = DshSpacing.page)) {
        val modes = listOf(
            stringResource(com.fioiu8.dshpocket.R.string.sc_ask_danger),
            stringResource(com.fioiu8.dshpocket.R.string.sc_ask_always),
        )
        modes.forEachIndexed { index, label ->
            SegmentedButton(
                selected = askEveryTime == (index == 1),
                onClick = {
                    askEveryTime = index == 1
                    settings.askEveryTime = index == 1
                    onChanged()
                },
                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                modifier = Modifier.heightIn(min = DshSpacing.controlHeight),
            ) {
                Text(label)
            }
        }
    }
    HelperText(stringResource(com.fioiu8.dshpocket.R.string.sc_ask_hint))

    GroupedList(
        modifier = Modifier.fillMaxWidth().padding(horizontal = DshSpacing.page),
        items = listOf {
            ListItem(
                headlineContent = { Text(stringResource(com.fioiu8.dshpocket.R.string.sc_thought_overlay_title)) },
                supportingContent = { Text(stringResource(com.fioiu8.dshpocket.R.string.sc_thought_overlay_summary)) },
                leadingContent = { Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                trailingContent = {
                    Switch(
                        checked = thoughtOverlay,
                        onCheckedChange = { checked ->
                            thoughtOverlay = checked
                            settings.thoughtOverlayEnabled = checked
                        },
                    )
                },
                modifier = Modifier.heightIn(min = DshSpacing.touchTarget),
            )
        },
    )

    SectionHeader(stringResource(com.fioiu8.dshpocket.R.string.sc_section_deny))
    GroupedList(
        modifier = Modifier.fillMaxWidth().padding(horizontal = DshSpacing.page),
        items = listOf(
            {
                SectionRow(
                    stringResource(com.fioiu8.dshpocket.R.string.sc_deny_row, settings.denyList().size),
                    stringResource(com.fioiu8.dshpocket.R.string.sc_deny_supporting),
                    Icons.Filled.Block,
                    onClick = { denyDraft = settings.denyList().sorted().joinToString("\n") },
                )
            },
        ),
    )

    SectionHeader(stringResource(com.fioiu8.dshpocket.R.string.sc_section_audit))
    GroupedList(
        modifier = Modifier.fillMaxWidth().padding(horizontal = DshSpacing.page),
        items = listOf(
            {
                SectionRow(
                    stringResource(com.fioiu8.dshpocket.R.string.sc_audit_view, auditEntries.size),
                    stringResource(com.fioiu8.dshpocket.R.string.sc_audit_supporting),
                    Icons.Filled.Schedule,
                    onClick = { auditEntries = audit.entries() },
                )
            },
            {
                SectionRow(
                    stringResource(com.fioiu8.dshpocket.R.string.sc_audit_clear),
                    stringResource(com.fioiu8.dshpocket.R.string.sc_audit_clear_supporting),
                    Icons.Filled.Delete,
                    onClick = {
                        audit.clear()
                        auditEntries = emptyList()
                    },
                )
            },
        ),
    )
    if (auditEntries.isNotEmpty()) {
        val locale = LocalConfiguration.current.locales[0]
        Column(Modifier.padding(horizontal = DshSpacing.page)) {
            auditEntries.take(8).forEach { entry ->
                val time = SimpleDateFormat("MM-dd HH:mm:ss", locale).format(Date(entry.timestamp))
                Text(
                    "$time · ${entry.action} · ${entry.outcome} · ${entry.reason.take(24)}" + (entry.message?.let { " · ${it.take(40)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }

    SectionHeader(stringResource(com.fioiu8.dshpocket.R.string.sc_section_disclosure))
    listOf(
        com.fioiu8.dshpocket.R.string.sc_disclosure_bridge,
        com.fioiu8.dshpocket.R.string.sc_disclosure_review,
        com.fioiu8.dshpocket.R.string.sc_disclosure_session,
        com.fioiu8.dshpocket.R.string.sc_disclosure_audit,
        com.fioiu8.dshpocket.R.string.sc_disclosure_marquee,
    ).forEach { HelperText(stringResource(it)) }

    denyDraft?.let { draft ->
        AlertDialog(
            onDismissRequest = { denyDraft = null },
            title = { Text(stringResource(com.fioiu8.dshpocket.R.string.sc_deny_edit_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { denyDraft = it },
                        label = { Text(stringResource(com.fioiu8.dshpocket.R.string.sc_deny_hint)) },
                        minLines = 6,
                        maxLines = 12,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = {
                        denyDraft = com.fioiu8.dshpocket.screen.ScreenControlSettings.DEFAULT_DENY_LIST.sorted().joinToString("\n")
                    }) {
                        Text(stringResource(com.fioiu8.dshpocket.R.string.sc_deny_restore))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val entries = draft.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                    settings.setDenyList(entries)
                    denyDraft = null
                }) { Text(stringResource(com.fioiu8.dshpocket.R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { denyDraft = null }) { Text(stringResource(com.fioiu8.dshpocket.R.string.cancel)) } },
        )
    }
}

@Composable
private fun PermissionGuideRow(
    title: String,
    supporting: String,
    granted: Boolean,
    actionLabel: String,
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
            Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = if (granted) stringResource(com.fioiu8.dshpocket.R.string.capability_granted) else stringResource(com.fioiu8.dshpocket.R.string.capability_available),
                style = MaterialTheme.typography.labelMedium,
                color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
            )
        }
        if (!granted) {
            OutlinedButton(onClick = onAction, modifier = Modifier.dshControl()) { Text(actionLabel) }
        }
    }
}
