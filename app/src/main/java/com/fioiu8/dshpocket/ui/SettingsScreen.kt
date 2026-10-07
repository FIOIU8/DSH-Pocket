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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fioiu8.dshpocket.capability.CapabilityState
import com.fioiu8.dshpocket.engine.EnginePaths
import com.fioiu8.dshpocket.engine.EngineState
import com.fioiu8.dshpocket.engine.RuntimeInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.fioiu8.dshpocket.ui.theme.DshShapes
import com.fioiu8.dshpocket.ui.theme.DshSpacing
import com.fioiu8.dshpocket.ui.theme.dshSpring
import com.fioiu8.dshpocket.ui.theme.dshControl
import com.fioiu8.dshpocket.ui.theme.systemAnimationsEnabled

enum class SettingsSection(
    val key: String,
    val label: String,
    val supporting: String,
    val icon: ImageVector,
) {
    Engine("engine", "引擎", "启动、停止本地核心并查看日志", Icons.Filled.Memory),
    Core("core", "核心与能力", "模型、插件与设备权限", Icons.Filled.Tune),
    Runtime("runtime", "运行时与工作区", "版本、空间占用与文件路径", Icons.Filled.Folder),
    General("general", "通用", "外观、语言与版本信息", Icons.Filled.Info),
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
    Scaffold(topBar = { DshTopAppBar(title = "设置", onMenu = onMenu) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(DshSpacing.compact),
        ) {
            Text(
                "按用途整理本地核心与应用偏好",
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
        headlineContent = { Text(section.label) },
        supportingContent = { Text(section.supporting) },
        leadingContent = {
            Icon(section.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        trailingContent = {
            Icon(Icons.Filled.ChevronRight, contentDescription = "打开${section.label}")
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
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val animationsEnabled = systemAnimationsEnabled()
    var runtimeSize by remember { mutableStateOf<Long?>(null) }
    var dshVersion by remember { mutableStateOf<String?>(null) }
    var nodeVersion by remember { mutableStateOf<String?>(null) }
    var workspacePath by remember { mutableStateOf("") }
    var showLog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val root = EnginePaths.root(context)
        scope.launch(Dispatchers.IO) {
            runtimeSize = RuntimeInstaller.sizeBytes(root)
            dshVersion = EnginePaths.dshVersion(root)
            nodeVersion = EnginePaths.probeNodeVersion(root, EnginePaths.dshHome(context))
            workspacePath = EnginePaths.workspace(context).absolutePath
        }
    }

    Scaffold(
        topBar = { DshTopAppBar(title = section.label, onMenu = onBack, onBack = onBack) },
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
                    SectionHeader("核心状态")
                    EngineCard(state, showLog, onRestart, onStop) { showLog = !showLog }
                    AnimatedVisibility(
                        visible = showLog,
                        enter = fadeIn(animationSpec = dshSpring(animationsEnabled)) +
                            expandVertically(animationSpec = dshSpring(animationsEnabled)),
                        exit = fadeOut(animationSpec = dshSpring(animationsEnabled)) +
                            shrinkVertically(animationSpec = dshSpring(animationsEnabled)),
                    ) {
                        Column {
                            SectionHeader("引擎日志")
                            LogPanel(log, Modifier.fillMaxWidth())
                        }
                    }
                }
                SettingsSection.Core -> {
                    SectionHeader("核心入口")
                    GroupedList(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = DshSpacing.page),
                        items = listOf(
                            { SectionRow("模型配置", "切换模型与 Agent 预设", Icons.Filled.Tune, onOpenModels) },
                            { SectionRow("插件管理", "安装、启用与配置插件", Icons.Filled.Extension, onOpenPlugins) },
                        ),
                    )
                    SectionHeader("设备能力")
                    CapabilityCard(capabilities, onGrantAllFiles)
                    HelperText("默认以 App 自身的权限运行引擎；安装 Termux 或 Shizuku 后，相关能力会热载。")
                }
                SettingsSection.Runtime -> {
                    SectionHeader("运行时")
                    GroupedList(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = DshSpacing.page),
                        items = listOf(
                            { KeyValueRow("内置 ABI", RuntimeInstaller.BUNDLED_ABI) },
                            { KeyValueRow("设备 ABI", Build.SUPPORTED_ABIS.joinToString(", ")) },
                            { KeyValueRow("Node", nodeVersion ?: "读取中…") },
                            { KeyValueRow("DSH", dshVersion ?: "读取中…") },
                            { KeyValueRow("占用空间", runtimeSize?.let { "%.0f MB".format(it / 1024.0 / 1024.0) } ?: "统计中…") },
                            { KeyValueRow("路径", EnginePaths.root(context).absolutePath) },
                        ),
                    )
                    SectionHeader("工作区")
                    GroupedList(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = DshSpacing.page),
                        items = listOf { KeyValueRow("当前", workspacePath.ifBlank { "读取中…" }) },
                    )
                    HelperText("agent 在这里读写文件。授予所有文件访问后，工作区会切到 ${EnginePaths.SHARED_WORKSPACE}，需重启引擎生效。")
                }
                SettingsSection.General -> {
                    SectionHeader("应用偏好")
                    Text(
                        "对话界面",
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
                                Text(presentation.label)
                            }
                        }
                    }
                    HelperText("网页对话保留 DSH 原版界面；原生对话由 DSH Pocket 渲染。切换不会删除会话。")
                    HelperText("启用提供 dshPocketUi/getNativeChatTheme 的 DSH 插件，可定制原生对话气泡颜色、圆角和字号。")
                    SectionHeader("原生对话信息")
                    GroupedList(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = DshSpacing.page),
                        items = listOf(
                            {
                                ChatPreferenceRow(
                                    title = "消息时间",
                                    supporting = "显示每条消息的发送或生成时间",
                                    icon = Icons.Filled.Schedule,
                                    checked = nativeChatPreferences.showMessageTime,
                                    onCheckedChange = {
                                        onNativeChatPreferencesChange(nativeChatPreferences.copy(showMessageTime = it))
                                    },
                                )
                            },
                            {
                                ChatPreferenceRow(
                                    title = "Token 与速度",
                                    supporting = "显示输入、输出 token 和生成速度（引擎提供时）",
                                    icon = Icons.Filled.DataUsage,
                                    checked = nativeChatPreferences.showTokenUsage,
                                    onCheckedChange = {
                                        onNativeChatPreferencesChange(nativeChatPreferences.copy(showTokenUsage = it))
                                    },
                                )
                            },
                            {
                                ChatPreferenceRow(
                                    title = "时间消耗",
                                    supporting = "显示本次回答耗时（引擎提供时）",
                                    icon = Icons.Filled.Schedule,
                                    checked = nativeChatPreferences.showDuration,
                                    onCheckedChange = {
                                        onNativeChatPreferencesChange(nativeChatPreferences.copy(showDuration = it))
                                    },
                                )
                            },
                            {
                                ChatPreferenceRow(
                                    title = "辅助回答",
                                    supporting = "允许基于当前上下文继续生成补充内容，会增加 token 消耗",
                                    icon = Icons.Filled.AutoAwesome,
                                    checked = nativeChatPreferences.showAssistReply,
                                    onCheckedChange = {
                                        onNativeChatPreferencesChange(nativeChatPreferences.copy(showAssistReply = it))
                                    },
                                )
                            },
                        ),
                    )
                    HelperText("时间、token 和耗时没有对应数据时会自动隐藏。辅助回答只在你点击后发送请求。")
                    GroupedList(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = DshSpacing.page),
                        items = listOf(
                            {
                                ListItem(
                                    headlineContent = { Text("外观") },
                                    supportingContent = { Text("跟随系统 · 中性双色 Material 3") },
                                    leadingContent = { Icon(Icons.Filled.Palette, contentDescription = null) },
                                )
                            },
                            {
                                ListItem(
                                    headlineContent = { Text("语言") },
                                    supportingContent = { Text("简体中文") },
                                    leadingContent = { Icon(Icons.Filled.Info, contentDescription = null) },
                                )
                            },
                            {
                                ListItem(
                                    headlineContent = { Text("关于 DSH Pocket") },
                                    supportingContent = { Text("v1.0 · MIT · 本地引擎运行时") },
                                    leadingContent = { Icon(Icons.Filled.Memory, contentDescription = null) },
                                )
                            },
                        ),
                    )
                }
            }
        }
    }
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
    val statusText = when (state) {
        is EngineState.Ready -> "运行中 · ${state.baseUrl} · 已运行 ${formatDuration(System.currentTimeMillis() - state.since)}"
        is EngineState.Busy -> "启动中 · ${state.message}"
        is EngineState.Failed -> "失败 · ${state.message}"
        EngineState.Idle -> "未启动"
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
                headlineContent = { Text("本地核心", fontWeight = FontWeight.SemiBold) },
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
                TextButton(onClick = onRestart, modifier = Modifier.dshControl()) { Text("重启") }
                TextButton(onClick = onStop, modifier = Modifier.dshControl()) { Text("停止") }
                TextButton(onClick = onToggleLog, modifier = Modifier.dshControl()) { Text(if (showLog) "收起日志" else "查看日志") }
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
                title = "所有文件访问",
                supporting = "授予后 agent 才能操作手机上的真实文件，工作区也会切到共享存储。",
                capabilityState = capabilities.allFiles,
                actionLabel = "去授权",
                onAction = onGrantAllFiles,
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            CapabilityRow(
                title = "Termux",
                supporting = "提供完整的 Linux 用户态与 apt；装上后可获得不受限的 shell 环境。",
                capabilityState = capabilities.termux,
                actionLabel = null,
                onAction = {},
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            CapabilityRow(
                title = "Shizuku",
                supporting = "提供提权执行（绕开幽灵进程杀手）。它只提升身份，本身不提供运行时。",
                capabilityState = capabilities.shizuku,
                actionLabel = null,
                onAction = {},
            )
        }
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

fun CapabilityState.label(): String = when (this) {
    CapabilityState.GRANTED -> "已具备"
    CapabilityState.AVAILABLE -> "可申请"
    CapabilityState.INSTALLED -> "已安装，待连接"
    CapabilityState.NOT_INSTALLED -> "未安装"
    CapabilityState.UNSUPPORTED -> "当前系统不支持"
}
