package com.fioiu8.dshpocket.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fioiu8.dshpocket.engine.EnginePaths
import com.fioiu8.dshpocket.engine.EngineState
import com.fioiu8.dshpocket.engine.RuntimeInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Adapted from the 设置 frame of `docs/ui-md3.json`. The reference frame's connection
 * section (QR pairing / LAN / tunnel) belongs to remote-host mode, which is not built
 * yet, so this screen only shows what actually exists.
 */
@Composable
fun SettingsScreen(
    state: EngineState,
    log: List<String>,
    capabilities: CapabilitySnapshot,
    onGrantAllFiles: () -> Unit,
    onRestart: () -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    var runtimeSize by remember { mutableStateOf<Long?>(null) }
    var dshVersion by remember { mutableStateOf<String?>(null) }
    var nodeVersion by remember { mutableStateOf<String?>(null) }
    var workspacePath by remember { mutableStateOf("") }
    var showLog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val root = EnginePaths.root(context)
        withContext(Dispatchers.IO) {
            runtimeSize = RuntimeInstaller.sizeBytes(root)
            dshVersion = EnginePaths.dshVersion(root)
            nodeVersion = EnginePaths.probeNodeVersion(root, EnginePaths.dshHome(context))
            workspacePath = EnginePaths.workspace(context).absolutePath
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SectionHeader("引擎")
        KeyValueRow("状态", when (state) {
            is EngineState.Ready -> "运行中"
            is EngineState.Busy -> state.message
            is EngineState.Failed -> "失败"
            EngineState.Idle -> "未启动"
        })
        if (state is EngineState.Ready) {
            KeyValueRow("地址", state.baseUrl)
        }
        KeyValueRow("端口", EnginePaths.PORT.toString())
        if (state is EngineState.Failed) {
            KeyValueRow("原因", state.message)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onRestart) { Text("重启") }
            OutlinedButton(onClick = onStop) { Text("停止") }
            OutlinedButton(onClick = { showLog = !showLog }) { Text(if (showLog) "收起日志" else "查看日志") }
        }

        SectionHeader("能力与权限")
        CapabilityCard(capabilities, onGrantAllFiles)
        Text(
            text = "默认档以 App 自身的权限运行引擎，因此它够得着的范围就等于本 App 被授予的范围。" +
                "shell 类工具默认不加载——装上 Termux 或 Shizuku 后才会热载。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        SectionHeader("运行时")
        KeyValueRow("内置 ABI", RuntimeInstaller.BUNDLED_ABI)
        KeyValueRow("设备 ABI", Build.SUPPORTED_ABIS.joinToString(", "))
        KeyValueRow("Node", nodeVersion ?: "读取中…")
        KeyValueRow("DSH", dshVersion ?: "读取中…")
        KeyValueRow("占用空间", runtimeSize?.let { "%.0f MB".format(it / 1024.0 / 1024.0) } ?: "统计中…")
        KeyValueRow("路径", EnginePaths.root(context).absolutePath)

        SectionHeader("工作区")
        KeyValueRow("当前", workspacePath.ifBlank { "读取中…" })
        Text(
            text = "agent 在这里读写文件。「所有文件访问」一旦授予，工作区会自动切到 " +
                EnginePaths.SHARED_WORKSPACE + "（需重启引擎生效）；未授予时只能落在应用私有目录。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        SectionHeader("外观")
        KeyValueRow("主题", "跟随系统（teal 调色板）")

        SectionHeader("关于")
        KeyValueRow("应用", "DSH Pocket")
        KeyValueRow("许可证", "MIT")
        Text(
            text = "内置运行时取自 Termux 的 bionic 包闭包，全部为 permissive 许可；" +
                "DSH 引擎本身为 MIT。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        if (showLog) {
            SectionHeader("引擎日志")
            LogPanel(log, Modifier.fillMaxWidth())
        }
    }
}
