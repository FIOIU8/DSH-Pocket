package com.fioiu8.dshpocket.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fioiu8.dshpocket.engine.EngineState
import com.fioiu8.dshpocket.ui.state.SessionLoadState
import com.fioiu8.dshpocket.ui.state.SessionFilter
import com.fioiu8.dshpocket.ui.state.SessionQuery
import com.fioiu8.dshpocket.ui.state.SessionRepository
import com.fioiu8.dshpocket.ui.state.SessionRow
import com.fioiu8.dshpocket.ui.state.SessionViewModel
import com.fioiu8.dshpocket.ui.state.SessionViewModelFactory
import java.util.concurrent.TimeUnit
import com.fioiu8.dshpocket.ui.theme.DshSpacing

/**
 * 会话管理（m3e-canvas.json 的 sessions 帧）：四段筛选、按工作区分组、搜索、归档/恢复。
 *
 * 本文件只负责渲染。数据在 [SessionRepository]，经 [SessionViewModel] 暴露；屏幕自己那圈
 * `while (true)` 轮询已经移除，这也是再次进入本页不会重新拉取的原因。
 *
 * 此处真正要守住的分工：
 * - **导航状态**（搜索框、筛选、待确认项）用 `rememberSaveable`，因为它要熬过进程死亡；
 * - **数据状态**（列表、归档集合、忙碌标记）归 ViewModel，因为它是异步的，不该因切页被取消。
 */
/** Labels for the state layer's [SessionFilter]; the logic lives there, not here. */
private val SessionFilter.labelRes: Int
    get() = when (this) {
        SessionFilter.All -> com.fioiu8.dshpocket.R.string.sessions_all
        SessionFilter.Running -> com.fioiu8.dshpocket.R.string.sessions_running
        SessionFilter.Finished -> com.fioiu8.dshpocket.R.string.sessions_finished
        SessionFilter.Archived -> com.fioiu8.dshpocket.R.string.sessions_archived
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    state: EngineState,
    onMenu: () -> Unit,
    onOpenChat: () -> Unit,
    repository: SessionRepository,
) {
    // Read through a lambda so the ViewModel sees the current engine state rather than the
    // value captured when it was constructed.
    val engineState: () -> EngineState = { state }
    val sessionViewModel: SessionViewModel = viewModel(
        key = "sessions",
        factory = SessionViewModelFactory(repository, engineState),
    )
    val ui by sessionViewModel.state.collectAsState()

    // 导航状态：熬过进程死亡。
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var filterName by rememberSaveable { mutableStateOf(SessionFilter.All.name) }
    var pendingArchiveId by rememberSaveable { mutableStateOf<String?>(null) }
    val filter = SessionFilter.valueOf(filterName)

    // Polling is owned by AppShell so there is exactly one loop for the app; the
    // repository's startPolling is idempotent either way.

    val pendingArchive = (ui.load as? SessionLoadState.Ready)
        ?.rows?.firstOrNull { it.id == pendingArchiveId }

    Scaffold(
        topBar = {
            DshTopAppBar(
                title = stringResource(com.fioiu8.dshpocket.R.string.nav_sessions),
                onMenu = onMenu,
                actions = {
                    IconButton(onClick = {
                        searchActive = !searchActive
                        if (!searchActive) query = ""
                    }, modifier = Modifier.size(DshSpacing.touchTarget)) {
                        Icon(Icons.Filled.Search, contentDescription = stringResource(com.fioiu8.dshpocket.R.string.search), modifier = Modifier.size(DshSpacing.icon))
                    }
                    IconButton(onClick = { sessionViewModel.refresh() }, modifier = Modifier.size(DshSpacing.touchTarget)) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(com.fioiu8.dshpocket.R.string.refresh), modifier = Modifier.size(DshSpacing.icon))
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
                    placeholder = { Text(stringResource(com.fioiu8.dshpocket.R.string.sessions_search_placeholder)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }, modifier = Modifier.size(DshSpacing.touchTarget)) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(com.fioiu8.dshpocket.R.string.clear), modifier = Modifier.size(DshSpacing.icon))
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
                        onClick = { filterName = entry.name },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = SessionFilter.entries.size),
                        modifier = Modifier.heightIn(min = DshSpacing.controlHeight),
                    ) {
                        Text(stringResource(entry.labelRes))
                    }
                }
            }

            when (val load = ui.load) {
                is SessionLoadState.Failed -> CenteredMessage(
                    stringResource(com.fioiu8.dshpocket.R.string.sessions_load_failed),
                    "${load.message}\n${stringResource(com.fioiu8.dshpocket.R.string.sessions_refresh_hint)}",
                    busy = false,
                )

                SessionLoadState.Loading -> if (state !is EngineState.Ready) {
                    CenteredMessage(
                        title = stringResource(com.fioiu8.dshpocket.R.string.engine_not_ready),
                        body = (state as? EngineState.Busy)?.message ?: stringResource(com.fioiu8.dshpocket.R.string.engine_start_to_manage_sessions),
                        busy = state is EngineState.Busy,
                    )
                } else {
                    CenteredMessage(stringResource(com.fioiu8.dshpocket.R.string.loading), "", busy = true)
                }

                is SessionLoadState.Ready -> {
                    val untitled = stringResource(com.fioiu8.dshpocket.R.string.home_untitled_session)
                    val defaultWorkspace = stringResource(com.fioiu8.dshpocket.R.string.sessions_default_workspace)
                    val filtered = SessionQuery.visible(load.rows, ui.archivedIds, filter, query)
                    if (filtered.isEmpty()) {
                        CenteredMessage(stringResource(com.fioiu8.dshpocket.R.string.sessions_no_match), "", busy = false)
                    } else {
                        PullToRefreshBox(
                            isRefreshing = ui.refreshing,
                            onRefresh = { sessionViewModel.refreshUserInitiated() },
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            LazyColumn(Modifier.fillMaxSize()) {
                                SessionQuery.byWorkspace(filtered, defaultWorkspace)
                                    .forEach { (workspace, items) ->
                                        item(key = "header-$workspace") {
                                            Text(
                                                stringResource(com.fioiu8.dshpocket.R.string.sessions_workspace, workspace),
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
                                                            untitled = untitled,
                                                            archived = SessionQuery.isArchived(row, ui.archivedIds),
                                                            restoring = ui.restoringId == row.id,
                                                            onOpen = onOpenChat,
                                                            onArchive = { pendingArchiveId = row.id },
                                                            onUnarchive = { sessionViewModel.unarchive(row.id) },
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
        val title = row.title ?: stringResource(com.fioiu8.dshpocket.R.string.home_untitled_session)
        AlertDialog(
            onDismissRequest = { if (!ui.archiving) pendingArchiveId = null },
            title = { Text(stringResource(com.fioiu8.dshpocket.R.string.sessions_archive)) },
            text = { Text(stringResource(com.fioiu8.dshpocket.R.string.sessions_archive_message, title)) },
            confirmButton = {
                TextButton(enabled = !ui.archiving, onClick = {
                    pendingArchiveId = null
                    sessionViewModel.archive(row.id)
                }) { Text(if (ui.archiving) stringResource(com.fioiu8.dshpocket.R.string.sessions_archiving) else stringResource(com.fioiu8.dshpocket.R.string.sessions_archive)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingArchiveId = null }) { Text(stringResource(com.fioiu8.dshpocket.R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun SessionRowItem(
    row: SessionRow,
    untitled: String,
    archived: Boolean,
    restoring: Boolean,
    onOpen: () -> Unit,
    onArchive: () -> Unit,
    onUnarchive: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val (statusIcon, statusLabel, circleColor, iconTint) = when {
        archived -> Quad(Icons.Filled.Archive, stringResource(com.fioiu8.dshpocket.R.string.sessions_archived), colors.surfaceVariant, colors.onSurfaceVariant)
        row.running -> Quad(Icons.AutoMirrored.Filled.Chat, stringResource(com.fioiu8.dshpocket.R.string.sessions_running), colors.primary, colors.onPrimary)
        else -> Quad(Icons.Filled.Check, stringResource(com.fioiu8.dshpocket.R.string.sessions_finished), colors.primary, colors.onPrimary)
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
                row.title ?: untitled,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    statusLabel,
                    stringResource(com.fioiu8.dshpocket.R.string.sessions_steps, row.steps),
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
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(com.fioiu8.dshpocket.R.string.more_actions), modifier = Modifier.size(DshSpacing.icon))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(com.fioiu8.dshpocket.R.string.open)) },
                    onClick = {
                        menuOpen = false
                        onOpen()
                    },
                    modifier = Modifier.heightIn(min = DshSpacing.touchTarget),
                )
                if (archived) {
                    DropdownMenuItem(
                        text = { Text(if (restoring) stringResource(com.fioiu8.dshpocket.R.string.sessions_restoring) else stringResource(com.fioiu8.dshpocket.R.string.sessions_restore)) },
                        leadingIcon = { Icon(Icons.Filled.Unarchive, contentDescription = null) },
                        enabled = !restoring,
                        onClick = {
                            menuOpen = false
                            onUnarchive()
                        },
                        modifier = Modifier.heightIn(min = DshSpacing.touchTarget),
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text(stringResource(com.fioiu8.dshpocket.R.string.sessions_archive)) },
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
    val icon: ImageVector,
    val label: String,
    val circleColor: Color,
    val iconTint: Color,
)

@Composable
private fun relativeTime(epochMillis: Long): String {
    if (epochMillis <= 0) return ""
    val diff = System.currentTimeMillis() - epochMillis
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    return when {
        minutes < 1 -> stringResource(com.fioiu8.dshpocket.R.string.sessions_just_now)
        minutes < 60 -> stringResource(com.fioiu8.dshpocket.R.string.sessions_minutes_ago, minutes)
        minutes < 60 * 24 -> stringResource(com.fioiu8.dshpocket.R.string.sessions_hours_ago, minutes / 60)
        else -> stringResource(com.fioiu8.dshpocket.R.string.sessions_days_ago, minutes / (60 * 24))
    }
}
