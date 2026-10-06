package com.fioiu8.dshpocket.ui

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fioiu8.dshpocket.capability.CapabilityState
import com.fioiu8.dshpocket.engine.EnginePaths
import com.fioiu8.dshpocket.engine.EngineState
import com.fioiu8.dshpocket.engine.RuntimeInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 设置（m3e-canvas.json 的 settings 帧）：引擎、核心（模型/插件入口 + 能力权限 +
 * 运行时诊断）、通用（外观/语言/关于）。画布里的连接区块（扫码/局域网/隧道）属于
 * 远程 Host 模式，未实现前不展示。
 */
@Composable
fun SettingsScreen(
    state: EngineState,
    log: List<String>,
    capabilities: CapabilitySnapshot,
    onMenu: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenPlugins: () -> Unit,
    onGrantAllFiles: () -> Unit,
    onRestart: () -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
        topBar = { DshTopAppBar(title = "设置", onMenu = onMenu) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SectionHeader("引擎")
            EngineCard(state, showLog, onRestart, onStop) { showLog = !showLog }

            SectionHeader("核心")
            SectionList {
                SectionRow("模型配置", "切换模型与 Agent 预设", Icons.Filled.Tune, onOpenModels)
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                SectionRow("插件管理", "安装、启用与配置插件", Icons.Filled.Extension, onOpenPlugins)
            }
            CapabilityCard(capabilities, onGrantAllFiles)
            Text(
                text = "默认档以 App 自身的权限运行引擎，因此它够得着的范围就等于本 App 被授予的范围。" +
                    "shell 类工具默认不加载——装上 Termux 或 Shizuku 后才会热载。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            SectionHeader("运行时")
            SectionList {
                KeyValueRow("内置 ABI", RuntimeInstaller.BUNDLED_ABI)
                KeyValueRow("设备 ABI", Build.SUPPORTED_ABIS.joinToString(", "))
                KeyValueRow("Node", nodeVersion ?: "读取中…")
                KeyValueRow("DSH", dshVersion ?: "读取中…")
                KeyValueRow("占用空间", runtimeSize?.let { "%.0f MB".format(it / 1024.0 / 1024.0) } ?: "统计中…")
                KeyValueRow("路径", EnginePaths.root(context).absolutePath)
            }

            SectionHeader("工作区")
            SectionList {
                KeyValueRow("当前", workspacePath.ifBlank { "读取中…" })
            }
            Text(
                text = "agent 在这里读写文件。「所有文件访问」一旦授予，工作区会自动切到 " +
                    EnginePaths.SHARED_WORKSPACE + "（需重启引擎生效）；未授予时只能落在应用私有目录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            SectionHeader("通用")
            SectionList {
                ListItem(
                    headlineContent = { Text("外观") },
                    supportingContent = { Text("跟随系统 · Material 3 teal") },
                    leadingContent = { Icon(Icons.Filled.Palette, contentDescription = null) },
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                ListItem(
                    headlineContent = { Text("语言") },
                    supportingContent = { Text("简体中文") },
                    leadingContent = { Icon(Icons.Filled.Info, contentDescription = null) },
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                ListItem(
                    headlineContent = { Text("关于 DSH Pocket") },
                    supportingContent = { Text("v1.0 · MIT · 引擎运行时来自 permissive 许可的 Termux 包") },
                    leadingContent = { Icon(Icons.Filled.Memory, contentDescription = null) },
                )
            }

            if (showLog) {
                SectionHeader("引擎日志")
                LogPanel(log, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun EngineCard(state: EngineState, showLog: Boolean, onRestart: () -> Unit, onStop: () -> Unit, onToggleLog: () -> Unit) {
    val statusText = when (state) {
        is EngineState.Ready -> "运行中 · ${state.baseUrl} · 已运行 ${formatDuration(System.currentTimeMillis() - state.since)}"
        is EngineState.Busy -> "启动中 · ${state.message}"
        is EngineState.Failed -> "失败 · ${state.message}"
        EngineState.Idle -> "未启动"
    }
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
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
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(onClick = onRestart) { Text("重启") }
                TextButton(onClick = onStop) { Text("停止") }
                TextButton(onClick = onToggleLog) { Text(if (showLog) "收起日志" else "查看日志") }
            }
        }
    }
}

/** 画布 settings 帧的分组列表容器：surfaceContainerLow 卡 + 纵向行。 */
@Composable
private fun SectionList(content: @Composable () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column { content() }
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
        modifier = Modifier.clickable(onClick = onClick),
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
            .padding(horizontal = 16.dp, vertical = 10.dp),
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
            OutlinedButton(onClick = onAction) { Text(actionLabel) }
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
