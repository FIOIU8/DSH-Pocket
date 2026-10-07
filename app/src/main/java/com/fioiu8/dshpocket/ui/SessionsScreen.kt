package com.fioiu8.dshpocket.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fioiu8.dshpocket.engine.EngineApi
import com.fioiu8.dshpocket.engine.EngineApi.RpcResult
import com.fioiu8.dshpocket.engine.EngineState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import com.fioiu8.dshpocket.ui.theme.DshSpacing

/**
 * 会话管理（m3e-canvas.json 的 sessions 帧）：四段筛选、按工作区分组、搜索、归档/恢复。
 *
 * 列表来自引擎 RPC `session/list`；归档集合没有一次性查询接口（workspace/follow 是
 * 订阅流），archiveSession/unarchiveSession 的返回值都带全量 archivedSessionIds，
 * 因此由这两次写操作的回包在本地维护归档集合。新建/重命名/分叉仍由官方 Web UI
 * 承载（D1：原生只做高频子集），点击条目跳到对话页。
 */
private enum class SessionFilter(val label: String) {
    All("全部"),
    Running("进行中"),
    Finished("已完成"),
    Archived("已归档"),
}

data class SessionRow(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val running: Boolean,
    val provider: String?,
    val model: String?,
    val steps: Int,
    val cwd: String?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(state: EngineState, onMenu: () -> Unit, onOpenChat: () -> Unit) {
    val ready = state as? EngineState.Ready
    var rows by remember { mutableStateOf<List<SessionRow>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var searchActive by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(SessionFilter.All) }
    var refreshing by remember { mutableStateOf(false) }
    var pendingArchive by remember { mutableStateOf<SessionRow?>(null) }
    var archiving by remember { mutableStateOf(false) }
    var restoringId by remember { mutableStateOf<String?>(null) }
    // 归档集合由归档/恢复的回包维护（见文件头注释）。
    var archivedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val scope = rememberCoroutineScope()

    val unarchive: (SessionRow) -> Unit = { row ->
        val s = ready
        if (s != null) {
            restoringId = row.id
            scope.launch {
                val result = EngineApi.rpc(
                    s.baseUrl,
                    "workspace/unarchiveSession",
                    EngineApi.requestArgs(JSONObject().put("sessionId", row.id)),
                    s.token,
                )
                restoringId = null
                when (result) {
                    is RpcResult.Ok -> {
                        error = null
                        archivedIds = archivedIdsFromResponse(result.value, "archivedSessionIds") ?: archivedIds
                    }
                    is RpcResult.Err -> error = "${result.code}: ${result.message}"
                }
            }
        }
    }

    // 进入页面加载一次；此后每 15 秒静默刷新。
    LaunchedEffect(state) {
        while (true) {
            if (state is EngineState.Ready) {
                load(
                    state.baseUrl,
                    state.token,
                    onLoaded = { rows = it; error = null },
                    onFailed = { error = it },
                )
            }
            delay(15_000)
        }
    }

    val refresh: () -> Unit = {
        val s = state as? EngineState.Ready
        if (s != null) {
            refreshing = true
            scope.launch {
                load(
                    s.baseUrl,
                    s.token,
                    onLoaded = { rows = it; error = null },
                    onFailed = { error = it },
                )
                refreshing = false
            }
        }
    }

    Scaffold(
        topBar = {
            DshTopAppBar(
                title = "会话管理",
                onMenu = onMenu,
                actions = {
                    IconButton(onClick = {
                        searchActive = !searchActive
                        if (!searchActive) query = ""
                    }, modifier = Modifier.size(DshSpacing.touchTarget)) {
                        Icon(Icons.Filled.Search, contentDescription = "搜索", modifier = Modifier.size(DshSpacing.icon))
                    }
                    IconButton(onClick = { refresh() }, modifier = Modifier.size(DshSpacing.touchTarget)) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新", modifier = Modifier.size(DshSpacing.icon))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (searchActive) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                    .padding(horizontal = DshSpacing.page, vertical = DshSpacing.compact),
                    singleLine = true,
                    placeholder = { Text("按标题搜索") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }, modifier = Modifier.size(DshSpacing.touchTarget)) {
                                Icon(Icons.Filled.Close, contentDescription = "清空", modifier = Modifier.size(DshSpacing.icon))
                            }
                        }
                    },
                )
            }

            SingleChoiceSegmentedButtonRow(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DshSpacing.page, vertical = DshSpacing.compact),
            ) {
                SessionFilter.entries.forEachIndexed { index, entry ->
                    SegmentedButton(
                        selected = filter == entry,
                        onClick = { filter = entry },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = SessionFilter.entries.size),
                        modifier = Modifier.heightIn(min = DshSpacing.controlHeight),
                    ) {
                        Text(entry.label)
                    }
                }
            }

            val visible = rows
            when {
                state !is EngineState.Ready -> CenteredMessage(
                    title = "引擎未就绪",
                    body = (state as? EngineState.Busy)?.message ?: "启动引擎后即可管理会话。",
                    busy = state is EngineState.Busy,
                )

                error != null -> CenteredMessage("会话列表加载失败", "$error\n下拉或点右上角刷新重试。", busy = false)
                visible == null -> CenteredMessage("加载中", "", busy = true)
                visible.isEmpty() -> CenteredMessage(
                    title = "还没有会话",
                    body = "到对话页发第一条消息即可创建。",
                    busy = false,
                )

                else -> {
                    val filtered = visible.filter { row ->
                        val archived = row.id in archivedIds
                        when (filter) {
                            SessionFilter.All -> true
                            SessionFilter.Running -> !archived && row.running
                            SessionFilter.Finished -> !archived && !row.running
                            SessionFilter.Archived -> archived
                        } && (query.isBlank() || row.title.contains(query, ignoreCase = true))
                    }
                    if (filtered.isEmpty()) {
                        CenteredMessage("没有匹配的会话", "", busy = false)
                    } else {
                        PullToRefreshBox(
                            isRefreshing = refreshing,
                            onRefresh = refresh,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            LazyColumn(Modifier.fillMaxSize()) {
                                val groups = filtered.groupBy { it.cwd?.substringAfterLast('/') ?: "默认工作区" }
                                groups.forEach { (workspace, items) ->
                                    item(key = "header-$workspace") {
                                        Text(
                                            "工作区：$workspace",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                             modifier = Modifier.padding(start = DshSpacing.page, top = 16.dp, bottom = 8.dp),
                                        )
                                    }
                                    item(key = "group-$workspace") {
                                        GroupedList(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp),
                                            items = items.map { row ->
                                                {
                                                    SessionRowItem(
                                                        row = row,
                                                        archived = row.id in archivedIds,
                                                        restoring = restoringId == row.id,
                                                        onOpen = onOpenChat,
                                                        onArchive = { pendingArchive = row },
                                                        onUnarchive = unarchive,
                                                    )
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingArchive?.let { row ->
        AlertDialog(
            onDismissRequest = { if (!archiving) pendingArchive = null },
            title = { Text("归档会话") },
            text = { Text("「${row.title}」会从列表移入归档，数据保留，可随时找回。") },
            confirmButton = {
                TextButton(enabled = !archiving, onClick = {
                    val s = ready ?: return@TextButton
                    archiving = true
                    scope.launch {
                        val result = EngineApi.rpc(
                            s.baseUrl,
                            "workspace/archiveSession",
                            EngineApi.requestArgs(JSONObject().put("sessionId", row.id)),
                            s.token,
                        )
                        archiving = false
                        pendingArchive = null
                        when (result) {
                            is RpcResult.Ok -> {
                                error = null
                                archivedIds = archivedIdsFromResponse(result.value, "archivedSessionIds") ?: archivedIds
                            }
                            is RpcResult.Err -> error = "${result.code}: ${result.message}"
                        }
                        refresh()
                    }
                }) { Text(if (archiving) "归档中…" else "归档") }
            },
            dismissButton = {
                TextButton(onClick = { pendingArchive = null }) { Text("取消") }
            },
        )
    }
}

/** 从 `{archivedSessionIds: [...]}` 回包读出字符串集合；缺字段时返回 null 以保持现状。 */
internal fun archivedIdsFromResponse(value: Any?, key: String): Set<String>? {
    val array = (value as? JSONObject)?.optJSONArray(key) ?: return null
    return archivedIdsFromValues(buildList {
        for (i in 0 until array.length()) add(array.optString(i))
    })
}

/** Keeps a missing response distinct from a valid empty archive list. */
internal fun archivedIdsFromValues(values: List<String>?): Set<String>? = values?.toSet()

private suspend fun load(
    baseUrl: String,
    token: String,
    onLoaded: (List<SessionRow>) -> Unit,
    onFailed: (String) -> Unit,
) {
    val result = EngineApi.rpc(
        baseUrl,
        "session/list",
        EngineApi.emptyArgs().put("_request", JSONObject()),
        token,
    )
    when (result) {
        is RpcResult.Ok -> onLoaded(parseRows(EngineApi.itemsOf(result.value)))
        is RpcResult.Err -> onFailed("${result.code}: ${result.message}")
    }
}

private fun parseRows(items: org.json.JSONArray): List<SessionRow> = buildList {
    for (i in 0 until items.length()) {
        val item = items.optJSONObject(i) ?: continue
        // blank = 刚创建还没有消息（官方 Web UI 打开时也会自动建一个）；跳过。
        if (item.optBoolean("blank")) continue
        val values = item.optJSONObject("projections")?.optJSONObject("values")
        val model = values?.optJSONObject("modelSelection")?.optJSONObject("lastUsed")
        val stats = values?.optJSONObject("sessionStats")
        // JSON null 经 optString 会变成字符串 "null"，与缺失一并按无标题处理。
        val title = values?.optString("title")
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?: "（无标题会话）"
        add(
            SessionRow(
                id = item.optString("sessionId"),
                title = title,
                updatedAt = item.optLong("updatedAt"),
                running = item.optBoolean("running"),
                provider = model?.optString("provider")?.takeIf { it.isNotBlank() && it != "null" },
                model = model?.optString("model")?.takeIf { it.isNotBlank() && it != "null" },
                steps = stats?.optInt("steps") ?: values?.optJSONArray("turnOutline")?.length() ?: 0,
                cwd = item.optString("cwd").takeIf { it.isNotBlank() },
            ),
        )
    }
}.sortedByDescending { it.updatedAt }

@Composable
private fun SessionRowItem(
    row: SessionRow,
    archived: Boolean,
    restoring: Boolean,
    onOpen: () -> Unit,
    onArchive: () -> Unit,
    onUnarchive: (SessionRow) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val (statusIcon, statusLabel, circleColor, iconTint) = when {
        archived -> Quad(Icons.Filled.Archive, "已归档", colors.surfaceVariant, colors.onSurfaceVariant)
        row.running -> Quad(Icons.AutoMirrored.Filled.Chat, "进行中", colors.primary, colors.onPrimary)
        else -> Quad(Icons.Filled.Check, "已完成", colors.primary, colors.onPrimary)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .heightIn(min = DshSpacing.touchTarget)
            .padding(horizontal = 12.dp, vertical = DshSpacing.rowVertical),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .background(circleColor, CircleShape),
        ) {
            Icon(statusIcon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                row.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    statusLabel,
                    "${row.steps} 步",
                    relativeTime(row.updatedAt),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(DshSpacing.touchTarget)) {
                Icon(Icons.Filled.MoreVert, contentDescription = "更多操作", modifier = Modifier.size(DshSpacing.icon))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("打开") },
                    onClick = {
                        menuOpen = false
                        onOpen()
                    },
                    modifier = Modifier.heightIn(min = DshSpacing.touchTarget),
                )
                if (archived) {
                    DropdownMenuItem(
                        text = { Text(if (restoring) "恢复中…" else "恢复") },
                        leadingIcon = { Icon(Icons.Filled.Unarchive, contentDescription = null) },
                        enabled = !restoring,
                        onClick = {
                            menuOpen = false
                            onUnarchive(row)
                        },
                        modifier = Modifier.heightIn(min = DshSpacing.touchTarget),
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text("归档") },
                        leadingIcon = { Icon(Icons.Filled.Archive, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onArchive()
                        },
                        modifier = Modifier.heightIn(min = DshSpacing.touchTarget),
                    )
                }
            }
        }
    }
}

private data class Quad(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    val circleColor: androidx.compose.ui.graphics.Color,
    val iconTint: androidx.compose.ui.graphics.Color,
)

private fun relativeTime(epochMillis: Long): String {
    if (epochMillis <= 0) return ""
    val diff = System.currentTimeMillis() - epochMillis
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        minutes < 60 * 24 -> "${minutes / 60} 小时前"
        else -> "${minutes / (60 * 24)} 天前"
    }
}
