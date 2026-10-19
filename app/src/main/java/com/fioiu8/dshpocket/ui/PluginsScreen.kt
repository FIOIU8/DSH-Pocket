package com.fioiu8.dshpocket.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.fioiu8.dshpocket.engine.EngineState
import com.fioiu8.dshpocket.ui.state.PluginRepository
import com.fioiu8.dshpocket.ui.state.PluginRow
import com.fioiu8.dshpocket.ui.state.PluginParser as parser
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.fioiu8.dshpocket.ui.theme.DshSpacing

/**
 * 插件管理（m3e-canvas.json 的 plugins 帧）。
 *
 * 数据来自 `pluginManager/listPlugins`（PluginInventoryEntry[]：entryId/moduleName/
 * meta{title,description}/enabled/readOnlyReason）；启用开关走 `setPluginEnabled`。
 * 安装/卸载（installBundle/removeBundle，依赖 pnpm 网络）暂缓，见页尾说明。
 *
 * 读取全部经 [PluginRepository]；本屏自持一个 15s 轮询循环（每次进入页面开始，切走
 * 即取消），与 shipped 行为一致。本地化标题在渲染期解析：模型只存原始
 * [com.fioiu8.dshpocket.ui.state.LocalizedText]，排序键是解析后的标题。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsScreen(state: EngineState, onMenu: () -> Unit, pluginRepository: PluginRepository) {
    val ready = state as? EngineState.Ready
    val pluginState by pluginRepository.state.collectAsState()
    var refreshing by remember { mutableStateOf(false) }
    var busyId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Read in composition scope: LocalConfiguration recomposes on locale change, and
    // being part of the key below it restarts the poll on the new locale, matching the
    // per-iteration fresh read the shipped context.resources loop gave.
    val localeTag = LocalConfiguration.current.locales[0].toLanguageTag()

    LaunchedEffect(state, localeTag) {
        while (true) {
            if (state is EngineState.Ready) {
                pluginRepository.reload()
            }
            delay(15_000)
        }
    }

    val refresh: () -> Unit = {
        val s = state as? EngineState.Ready
        if (s != null) {
            refreshing = true
            scope.launch {
                pluginRepository.reload()
                refreshing = false
            }
        }
    }

    val toggle: (PluginRow, Boolean) -> Unit = { row, enabled ->
        val s = state as? EngineState.Ready
        if (s != null) {
            busyId = row.id
            scope.launch {
                pluginRepository.setPluginEnabled(row.id, enabled)
                busyId = null
                refresh()
            }
        }
    }

    Scaffold(
        topBar = { DshTopAppBar(title = stringResource(com.fioiu8.dshpocket.R.string.nav_plugins), onMenu = onMenu) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val visible = pluginState.rows
            when {
                state !is EngineState.Ready -> CenteredMessage(
                    title = stringResource(com.fioiu8.dshpocket.R.string.engine_not_ready),
                    body = (state as? EngineState.Busy)?.message ?: stringResource(com.fioiu8.dshpocket.R.string.engine_start_to_manage_plugins),
                    busy = state is EngineState.Busy,
                )

                pluginState.error != null && visible == null -> CenteredMessage(stringResource(com.fioiu8.dshpocket.R.string.plugins_load_failed), pluginState.error.orEmpty(), busy = false)
                visible == null -> CenteredMessage(stringResource(com.fioiu8.dshpocket.R.string.loading), "", busy = true)
                visible.isEmpty() -> CenteredMessage(stringResource(com.fioiu8.dshpocket.R.string.plugins_empty), "", busy = false)

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
                            SectionHeader(stringResource(com.fioiu8.dshpocket.R.string.plugins_installed))
                            GroupedList(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = DshSpacing.page),
                                items = parser.sortedByTitle(visible, localeTag).map { row ->
                                    {
                                        PluginRowItem(
                                            row = row,
                                            localeTag = localeTag,
                                            busy = busyId == row.id,
                                            onToggle = { enabled -> toggle(row, enabled) },
                                        )
                                    }
                                },
                            )
                            Text(
                                text = stringResource(com.fioiu8.dshpocket.R.string.plugins_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(DshSpacing.page),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginRowItem(row: PluginRow, localeTag: String, busy: Boolean, onToggle: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(parser.titleFor(row, localeTag), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val subtitle = parser.descriptionFor(row, localeTag).ifBlank { row.moduleName }
            Text(
                if (row.readOnlyReason != null) stringResource(com.fioiu8.dshpocket.R.string.plugins_read_only, subtitle, row.readOnlyReason) else subtitle,
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
        modifier = Modifier.heightIn(min = DshSpacing.touchTarget),
    )
}
