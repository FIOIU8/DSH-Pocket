package com.fioiu8.dshpocket.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fioiu8.dshpocket.capability.CapabilityState
import com.fioiu8.dshpocket.engine.EnginePaths
import com.fioiu8.dshpocket.engine.EngineState
import kotlinx.coroutines.delay

/**
 * Adapted from the 首页 frame of `docs/ui-md3.json`.
 *
 * The reference frame's "最新会话" card and its workspace grouping need the session
 * protocol, which is not wired yet; what the card shows here is the thing we *do* know
 * the truth about — the bundled engine and what it is allowed to touch.
 */
@Composable
fun HomeScreen(
    state: EngineState,
    log: List<String>,
    capabilities: CapabilitySnapshot,
    onOpenChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onGrantAllFiles: () -> Unit,
    onRestart: () -> Unit,
) {
    var showLog by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        EngineCard(state, now, onOpenChat, onRestart)

        SectionHeader("快捷入口")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            QuickEntry("进入对话", "原生外壳里的官方界面", Modifier.weight(1f), onOpenChat)
            QuickEntry("能力与权限", "决定 agent 够得着什么", Modifier.weight(1f), onOpenSettings)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            QuickEntry("重启引擎", "插件或配置改动后", Modifier.weight(1f), onRestart)
            QuickEntry("引擎日志", if (showLog) "点击收起" else "点击展开", Modifier.weight(1f)) {
                showLog = !showLog
            }
        }

        SectionHeader("能力档位")
        CapabilityCard(capabilities, onGrantAllFiles)

        if (showLog) {
            SectionHeader("引擎输出")
            LogPanel(log, Modifier.fillMaxWidth())
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text(
            text = "引擎与会话全部留在本机；数据是否离开设备，取决于你配置的模型服务地址。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun EngineCard(
    state: EngineState,
    now: Long,
    onOpenChat: () -> Unit,
    onRestart: () -> Unit,
) {
    val title: String
    val detail: String
    when (state) {
        is EngineState.Ready -> {
            title = "引擎运行中"
            detail = "${state.baseUrl} · 已运行 ${formatDuration(now - state.since)}"
        }

        is EngineState.Busy -> {
            title = "正在启动"
            detail = state.message
        }

        is EngineState.Failed -> {
            title = "启动失败"
            detail = state.message
        }

        EngineState.Idle -> {
            title = "未启动"
            detail = "引擎尚未运行"
        }
    }

    Card(
        Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state is EngineState.Ready) {
                    TextButton(onClick = onOpenChat) { Text("进入对话") }
                }
                TextButton(onClick = onRestart) { Text("重启引擎") }
            }
        }
    }
}

@Composable
private fun QuickEntry(
    title: String,
    supporting: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, modifier = modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun CapabilityCard(capabilities: CapabilitySnapshot, onGrantAllFiles: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(vertical = 8.dp)) {
            CapabilityRow(
                title = "所有文件访问",
                supporting = "授予后 agent 才能操作手机上的真实文件，工作区也会从应用私有目录切到 " +
                    EnginePaths.SHARED_WORKSPACE + "（需重启引擎）。Node 打不开 SAF 的 content:// 路径，所以只能走这条权限。",
                state = capabilities.allFiles,
                actionLabel = "去授权",
                onAction = onGrantAllFiles,
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            CapabilityRow(
                title = "Termux",
                supporting = "提供完整的 Linux 用户态与 apt；装上后可获得不受限的 shell 环境。",
                state = capabilities.termux,
                actionLabel = null,
                onAction = {},
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            CapabilityRow(
                title = "Shizuku",
                supporting = "提供提权执行（绕开幽灵进程杀手）。它只提升身份，本身不提供运行时。",
                state = capabilities.shizuku,
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
    state: CapabilityState,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
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
                text = state.label(),
                style = MaterialTheme.typography.labelMedium,
                color = when (state) {
                    CapabilityState.GRANTED -> MaterialTheme.colorScheme.primary
                    CapabilityState.INSTALLED -> MaterialTheme.colorScheme.tertiary
                    CapabilityState.NOT_INSTALLED, CapabilityState.UNSUPPORTED ->
                        MaterialTheme.colorScheme.onSurfaceVariant
                    CapabilityState.AVAILABLE -> MaterialTheme.colorScheme.tertiary
                },
            )
        }
        if (actionLabel != null && state == CapabilityState.AVAILABLE) {
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

fun formatDuration(millis: Long): String {
    val total = (millis / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
