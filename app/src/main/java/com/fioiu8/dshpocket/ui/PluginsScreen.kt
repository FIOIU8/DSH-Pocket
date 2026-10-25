package com.fioiu8.dshpocket.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.fioiu8.dshpocket.engine.EngineState
import com.fioiu8.dshpocket.ui.state.PluginBundle
import com.fioiu8.dshpocket.ui.state.PluginChange
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
 * meta{title,description}/enabled/readOnlyReason）与 `pluginManager/listBundles`
 * （BundleInfo[]：name/version/removable/readOnlyReason）。启用开关走
 * `setPluginEnabled`；包级安装/卸载走 `installBundle`/`removeBundle`（引擎侧跑 pnpm，
 * 可能耗时数分钟，因此请求预算是 PACKAGE_READ_TIMEOUT_MS，进行中可取消安装）。
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
    var spec by remember { mutableStateOf("") }
    var specError by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<PluginBundle?>(null) }
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
                // Silent reload: the pull-to-refresh indicator belongs to explicit
                // refresh gestures, not to every toggle flip.
                pluginRepository.reload()
            }
        }
    }

    val install: () -> Unit = {
        val s = state as? EngineState.Ready
        val trimmed = spec.trim()
        if (s != null && trimmed.isNotEmpty() && pluginState.action == null) {
            specError = false
            scope.launch { pluginRepository.install(trimmed) }
            // The outcome banner reports success or failure; a finished install leaves
            // the field so the user can see what they installed, but a blank re-submit
            // is what the specError flag is for.
        } else if (trimmed.isEmpty()) {
            specError = true
        }
    }

    val remove: (PluginBundle) -> Unit = { bundle ->
        val s = state as? EngineState.Ready
        if (s != null && pluginState.action == null) {
            scope.launch { pluginRepository.remove(bundle.name) }
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
                            pluginState.lastChange?.let { change ->
                                ChangeBanner(
                                    change = change,
                                    onDismiss = { pluginRepository.dismissChange() },
                                )
                            }

                            InstallCard(
                                spec = spec,
                                onSpecChange = { spec = it; specError = false },
                                specError = specError,
                                busy = pluginState.action != null,
                                onInstall = install,
                            )

                            pluginState.action?.let { action ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = DshSpacing.page),
                                ) {
                                    LinearProgressIndicator(
                                        Modifier
                                            .weight(1f)
                                            .padding(end = DshSpacing.page),
                                    )
                                    if (action.requestId.isNotBlank()) {
                                        TextButton(onClick = { scope.launch { pluginRepository.cancelInstall(action.requestId) } }) {
                                            Text(stringResource(com.fioiu8.dshpocket.R.string.cancel))
                                        }
                                    }
                                }
                            }

                            val bundles = pluginState.bundles
                            if (bundles != null && bundles.isNotEmpty()) {
                                SectionHeader(stringResource(com.fioiu8.dshpocket.R.string.plugins_bundles))
                                GroupedList(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = DshSpacing.page),
                                    items = parser.sortedBundles(bundles, localeTag).map { bundle ->
                                        {
                                            BundleRowItem(
                                                bundle = bundle,
                                                busy = pluginState.action?.target == bundle.name,
                                                onRemove = { confirmRemove = bundle },
                                            )
                                        }
                                    },
                                )
                            }

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

                    confirmRemove?.let { bundle ->
                        AlertDialog(
                            onDismissRequest = { confirmRemove = null },
                            title = { Text(stringResource(com.fioiu8.dshpocket.R.string.plugins_remove_confirm_title, parser.bundleTitle(bundle, localeTag))) },
                            text = { Text(stringResource(com.fioiu8.dshpocket.R.string.plugins_remove_confirm_body)) },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        confirmRemove = null
                                        remove(bundle)
                                    },
                                ) { Text(stringResource(com.fioiu8.dshpocket.R.string.plugins_remove)) }
                            },
                            dismissButton = {
                                TextButton(onClick = { confirmRemove = null }) {
                                    Text(stringResource(com.fioiu8.dshpocket.R.string.cancel))
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** One-line outcome banner for the last install/remove, with its own dismiss. */
@Composable
private fun ChangeBanner(change: PluginChange, onDismiss: () -> Unit) {
    val (textRes, arg) = when (change) {
        is PluginChange.Applied -> com.fioiu8.dshpocket.R.string.plugins_change_applied to ""
        is PluginChange.RestartRequired -> com.fioiu8.dshpocket.R.string.plugins_change_restart to ""
        is PluginChange.Cancelled -> com.fioiu8.dshpocket.R.string.plugins_change_cancelled to ""
        is PluginChange.Failed -> com.fioiu8.dshpocket.R.string.plugins_change_failed to change.code
    }
    ListItem(
        headlineContent = {
            Text(
                if (arg.isEmpty()) stringResource(textRes)
                else stringResource(textRes, arg) + (change as? PluginChange.Failed)?.detail?.let { " — $it" }.orEmpty(),
                color = if (change is PluginChange.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        },
        trailingContent = {
            TextButton(onClick = onDismiss) { Text(stringResource(com.fioiu8.dshpocket.R.string.plugins_dismiss)) }
        },
        modifier = Modifier.padding(horizontal = DshSpacing.page),
    )
}

@Composable
private fun InstallCard(
    spec: String,
    onSpecChange: (String) -> Unit,
    specError: Boolean,
    busy: Boolean,
    onInstall: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = DshSpacing.page, vertical = DshSpacing.section)) {
        OutlinedTextField(
            value = spec,
            onValueChange = onSpecChange,
            isError = specError,
            enabled = !busy,
            singleLine = true,
            label = { Text(stringResource(com.fioiu8.dshpocket.R.string.plugins_install_title)) },
            placeholder = { Text(stringResource(com.fioiu8.dshpocket.R.string.plugins_install_hint)) },
            supportingText = {
                Text(
                    if (specError) stringResource(com.fioiu8.dshpocket.R.string.plugins_spec_required)
                    else stringResource(com.fioiu8.dshpocket.R.string.plugins_install_supporting),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(
            onClick = onInstall,
            enabled = !busy,
            modifier = Modifier.padding(top = DshSpacing.compact),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(stringResource(com.fioiu8.dshpocket.R.string.plugins_install_action), Modifier.padding(start = DshSpacing.compact))
        }
    }
}

@Composable
private fun BundleRowItem(bundle: PluginBundle, busy: Boolean, onRemove: () -> Unit) {
    ListItem(
        headlineContent = { Text(bundle.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                buildList {
                    bundle.version?.let { add(it) }
                    bundle.readOnlyReason?.let { add(it) }
                }.joinToString(" · ").ifBlank { "" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            Icon(
                Icons.Filled.Extension,
                contentDescription = null,
                tint = if (bundle.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            if (bundle.removable) {
                IconButton(onClick = onRemove, enabled = !busy) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(com.fioiu8.dshpocket.R.string.plugins_remove))
                }
            }
        },
        modifier = Modifier.heightIn(min = DshSpacing.touchTarget),
    )
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
