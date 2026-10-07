package com.fioiu8.dshpocket.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import com.fioiu8.dshpocket.engine.EngineApi
import com.fioiu8.dshpocket.engine.EnginePaths
import com.fioiu8.dshpocket.engine.EngineState
import com.fioiu8.dshpocket.engine.RuntimeInstaller
import com.fioiu8.dshpocket.ui.theme.DshShapes
import com.fioiu8.dshpocket.ui.theme.DshSpacing
import com.fioiu8.dshpocket.ui.theme.dshControl
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * 首页（m3e-canvas.json 的 home 帧）：最新会话卡、快捷入口、本地核心运行状态。
 * 能力档位卡移至设置页；重启入口收进「本地核心」卡。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: EngineState,
    onOpenChat: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenPlugins: () -> Unit,
    onOpenModels: () -> Unit,
    onMenu: () -> Unit,
    onRestart: () -> Unit,
) {
    Scaffold(
        topBar = {
            DshTopAppBar(
                title = "DSH Pocket",
                onMenu = onMenu,
                subtitle = subtitleFor(state),
            )
        },
    ) { padding ->
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(1_000)
                now = System.currentTimeMillis()
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(DshSpacing.compact),
        ) {
            LatestSessionSection(state, onOpenChat)

            SectionHeader("快捷入口")
            QuickEntryList(onOpenChat, onOpenSessions, onOpenPlugins, onOpenModels)

            SectionHeader("运行状态")
            CoreStatusCard(state, now, onRestart)

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text(
                text = "引擎与会话全部留在本机；数据是否离开设备，取决于你配置的模型服务地址。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

private fun subtitleFor(state: EngineState): String = when (state) {
    is EngineState.Ready -> "本地运行 · 已连接"
    is EngineState.Busy -> "正在启动 · ${state.message}"
    is EngineState.Failed -> "引擎异常"
    EngineState.Idle -> "引擎未启动"
}

/** 首页顶部的「最新会话」filled 卡（canvas home_latest）+「继续查看」tonal 按钮。 */
@Composable
private fun LatestSessionSection(state: EngineState, onOpenChat: () -> Unit) {
    var latest by remember { mutableStateOf<LatestSession?>(null) }

    LaunchedEffect(state) {
        val ready = state as? EngineState.Ready ?: return@LaunchedEffect
        val result = EngineApi.rpc(
            ready.baseUrl,
            "session/list",
            EngineApi.emptyArgs().put("_request", JSONObject()),
            ready.token,
        )
        if (result is EngineApi.RpcResult.Ok) {
            latest = EngineApi.itemsOf(result.value)
                .let { items ->
                    buildList {
                        for (i in 0 until items.length()) {
                            val item = items.optJSONObject(i) ?: continue
                            if (item.optBoolean("blank")) continue
                            val values = item.optJSONObject("projections")?.optJSONObject("values")
                            val title = values?.optString("title")
                                ?.takeIf { it.isNotBlank() && it != "null" }
                                ?: "（无标题会话）"
                            add(
                                LatestSession(
                                    title = title,
                                    running = item.optBoolean("running"),
                                    turns = values?.optJSONArray("turnOutline")?.length() ?: 0,
                                ),
                            )
                        }
                    }
                }
                .firstOrNull() // session/list 已按最近更新排序
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = DshSpacing.page, vertical = DshSpacing.compact)) {
        val session = latest
        Card(
            onClick = onOpenChat,
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            shape = RoundedCornerShape(DshShapes.surface),
        ) {
            Row(
                Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Filled.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column {
                    Text(
                        session?.title ?: "还没有会话",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        session?.let {
                            listOfNotNull(
                                if (it.running) "进行中" else "已就绪",
                                "${it.turns} 轮",
                            ).joinToString(" · ")
                        } ?: "到对话页发第一条消息即可开始",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        FilledTonalButton(
            onClick = onOpenChat,
            modifier = Modifier
                .padding(top = DshSpacing.compact)
                .dshControl(),
            shape = RoundedCornerShape(DshShapes.control),
        ) {
            Text("继续查看")
            Icon(Icons.Filled.ChevronRight, contentDescription = null)
        }
    }
}

private data class LatestSession(val title: String, val running: Boolean, val turns: Int)

/** 快捷入口（canvas quick_*）：纵向 ListItem，图标 + chevron。 */
@Composable
private fun QuickEntryList(
    onOpenChat: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenPlugins: () -> Unit,
    onOpenModels: () -> Unit,
) {
    GroupedList(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DshSpacing.page),
        items = listOf(
            { QuickRow("新建对话", "开始一段新对话", Icons.Filled.Add, onOpenChat) },
            { QuickRow("会话列表", "浏览与管理工作区会话", Icons.Filled.Forum, onOpenSessions) },
            { QuickRow("插件管理", "安装、启用与配置插件", Icons.Filled.Extension, onOpenPlugins) },
            { QuickRow("模型配置", "切换模型与 Agent 预设", Icons.Filled.Tune, onOpenModels) },
        ),
    )
}

@Composable
private fun QuickRow(
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

/** 「本地核心」elevated 卡（canvas home_core），重启入口在此。 */
@Composable
private fun CoreStatusCard(state: EngineState, now: Long, onRestart: () -> Unit) {
    val title: String
    val detail: String
    when (state) {
        is EngineState.Ready -> {
            title = "Node.js 运行中"
            detail = "端口 ${EnginePaths.PORT} · 已运行 ${formatDuration(now - state.since)} · ABI ${RuntimeInstaller.BUNDLED_ABI}"
        }

        is EngineState.Busy -> {
            title = "正在启动"
            detail = state.message
        }

        is EngineState.Failed -> {
            title = "引擎异常"
            detail = state.message
        }

        EngineState.Idle -> {
            title = "引擎未启动"
            detail = "到对话页或这里重启引擎"
        }
    }

    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DshSpacing.page),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(DshShapes.surface),
    ) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Filled.Memory,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRestart, modifier = Modifier.dshControl()) { Text("重启") }
        }
    }
}

fun formatDuration(millis: Long): String {
    val total = (millis / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
