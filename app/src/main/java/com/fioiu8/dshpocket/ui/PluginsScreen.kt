package com.fioiu8.dshpocket.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fioiu8.dshpocket.engine.EngineApi
import com.fioiu8.dshpocket.engine.EngineApi.RpcResult
import com.fioiu8.dshpocket.engine.EngineState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 插件管理（m3e-canvas.json 的 plugins 帧）。
 *
 * 数据来自 `pluginManager/listPlugins`（PluginInventoryEntry[]：entryId/moduleName/
 * meta{title,description}/enabled/readOnlyReason）；启用开关走 `setPluginEnabled`。
 * 安装/卸载（installBundle/removeBundle，依赖 pnpm 网络）暂缓，见页尾说明。
 */
data class PluginRow(
    val id: String,
    val moduleName: String,
    val title: String,
    val description: String,
    val enabled: Boolean,
    val readOnlyReason: String?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsScreen(state: EngineState, onMenu: () -> Unit) {
    val ready = state as? EngineState.Ready
    var rows by remember { mutableStateOf<List<PluginRow>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var busyId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(state) {
        while (true) {
            if (state is EngineState.Ready) {
                load(state.baseUrl, state.token,
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
                load(s.baseUrl, s.token,
                    onLoaded = { rows = it; error = null },
                    onFailed = { error = it },
                )
                refreshing = false
            }
        }
    }

    val toggle: (PluginRow, Boolean) -> Unit = { row, enabled ->
        val s = state as? EngineState.Ready
        if (s != null) {
            busyId = row.id
            scope.launch {
                val result = EngineApi.rpc(
                    s.baseUrl,
                    "pluginManager/setPluginEnabled",
                    JSONObject().put("id", row.id).put("enabled", enabled),
                    s.token,
                )
                busyId = null
                if (result is RpcResult.Err) error = "${result.code}: ${result.message}"
                refresh()
            }
        }
    }

    Scaffold(
        topBar = { DshTopAppBar(title = "插件管理", onMenu = onMenu) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val visible = rows
            when {
                state !is EngineState.Ready -> CenteredMessage(
                    title = "引擎未就绪",
                    body = (state as? EngineState.Busy)?.message ?: "启动引擎后即可管理插件。",
                    busy = state is EngineState.Busy,
                )

                error != null && visible == null -> CenteredMessage("插件列表加载失败", "$error", busy = false)
                visible == null -> CenteredMessage("加载中", "", busy = true)
                visible.isEmpty() -> CenteredMessage("没有可管理的插件", "", busy = false)

                else -> {
                    PullToRefreshBox(
                        isRefreshing = refreshing,
                        onRefresh = refresh,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                        ) {
                            visible.forEach { row ->
                                PluginRowItem(
                                    row = row,
                                    busy = busyId == row.id,
                                    onToggle = { enabled -> toggle(row, enabled) },
                                )
                                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            }
                            Text(
                                text = "安装与卸载需要联网拉取插件包，将在后续版本接入；" +
                                    "当前可在此启停已随包安装的插件，改动即时生效。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private suspend fun load(
    baseUrl: String,
    token: String,
    onLoaded: (List<PluginRow>) -> Unit,
    onFailed: (String) -> Unit,
) {
    val result = EngineApi.rpc(baseUrl, "pluginManager/listPlugins", EngineApi.emptyArgs(), token)
    when (result) {
        is RpcResult.Ok -> onLoaded(parseRows(EngineApi.itemsOf(result.value)))
        is RpcResult.Err -> onFailed("${result.code}: ${result.message}")
    }
}

private fun parseRows(items: org.json.JSONArray): List<PluginRow> = buildList {
    for (i in 0 until items.length()) {
        val item = items.optJSONObject(i) ?: continue
        val meta = item.optJSONObject("meta")
        val title = localized(meta?.opt("title")) ?: item.optString("moduleName").substringAfterLast('/')
        val description = localized(meta?.opt("description")).orEmpty()
        add(
            PluginRow(
                id = item.optString("entryId"),
                moduleName = item.optString("moduleName"),
                title = title,
                description = description,
                enabled = item.optBoolean("enabled"),
                readOnlyReason = item.optString("readOnlyReason").takeIf { it.isNotBlank() },
            ),
        )
    }
}.sortedBy { it.title.lowercase() }

/** LocalizedText 可能是字符串，也可能是按语言键的对象；宽松解析。 */
private fun localized(value: Any?): String? = when (value) {
    null -> null
    is String -> value.takeIf { it.isNotBlank() }
    is JSONObject -> {
        val zh = value.optString("zh-CN").ifBlank { value.optString("zh") }
        val en = value.optString("en")
        (zh.ifBlank { en }).takeIf { it.isNotBlank() }
    }

    else -> value.toString().takeIf { it.isNotBlank() && it != "null" }
}

@Composable
private fun PluginRowItem(row: PluginRow, busy: Boolean, onToggle: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val subtitle = row.description.ifBlank { row.moduleName }
            Text(
                if (row.readOnlyReason != null) "$subtitle · 只读（${row.readOnlyReason}）" else subtitle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            Icon(
                Icons.Filled.Extension,
                contentDescription = null,
                tint = if (row.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Switch(
                checked = row.enabled,
                onCheckedChange = onToggle,
                enabled = row.readOnlyReason == null && !busy,
            )
        },
    )
}
