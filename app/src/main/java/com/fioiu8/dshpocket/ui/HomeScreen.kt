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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.fioiu8.dshpocket.engine.EngineApi
import com.fioiu8.dshpocket.ui.state.SessionLoadState
import com.fioiu8.dshpocket.ui.state.SessionRepository
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
    repository: SessionRepository,
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
        // A local one-second clock for the uptime readout. This is not a data poll - it
        // issues no RPC and belongs to this composable rather than a repository.
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
            LatestSessionSection(repository, onOpenChat)

            SectionHeader(stringResource(com.fioiu8.dshpocket.R.string.home_quick_entries))
            QuickEntryList(onOpenChat, onOpenSessions, onOpenPlugins, onOpenModels)

            SectionHeader(stringResource(com.fioiu8.dshpocket.R.string.home_runtime_status))
            CoreStatusCard(state, now, onRestart)

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text(
                text = stringResource(com.fioiu8.dshpocket.R.string.home_local_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun subtitleFor(state: EngineState): String = when (state) {
    is EngineState.Ready -> stringResource(com.fioiu8.dshpocket.R.string.home_node_running)
    is EngineState.Busy -> stringResource(com.fioiu8.dshpocket.R.string.home_starting) + " · ${state.message.asString()}"
    is EngineState.Failed -> stringResource(com.fioiu8.dshpocket.R.string.home_engine_error)
    EngineState.Idle -> stringResource(com.fioiu8.dshpocket.R.string.home_engine_stopped)
}

/**
 * 首页顶部的「最新会话」filled 卡（canvas home_latest）+「继续查看」tonal 按钮。
 *
 * Reads the shared [SessionRepository] rather than calling `session/list` again. This is
 * the second consumer that proves the sharing works: with both screens composed, the
 * engine is still asked exactly once, and no second poll loop appears here.
 */
@Composable
private fun LatestSessionSection(repository: SessionRepository, onOpenChat: () -> Unit) {
    val ui by repository.state.collectAsState()

    // `session/list` is ordered most-recent-first and the repository preserves that, so
    // the first row is the latest. A null title stays null; the label is a @StringRes
    // resolved at draw time.
    val latest = (ui.load as? SessionLoadState.Ready)?.rows?.firstOrNull()?.let { row ->
        LatestSession(
            title = row.title,
            running = row.running,
            turns = row.steps,
        )
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
                        text = when (latestTitleState(session)) {
                            LatestTitle.NoSession -> stringResource(com.fioiu8.dshpocket.R.string.home_no_session)
                            LatestTitle.Untitled -> stringResource(com.fioiu8.dshpocket.R.string.home_untitled_session)
                            LatestTitle.Titled -> session!!.title!!
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        session?.let {
                            listOfNotNull(
                                if (it.running) stringResource(com.fioiu8.dshpocket.R.string.home_session_running) else stringResource(com.fioiu8.dshpocket.R.string.home_session_ready),
                                pluralStringResource(com.fioiu8.dshpocket.R.plurals.home_turns_count, it.turns, it.turns),
                            ).joinToString(" · ")
                        } ?: stringResource(com.fioiu8.dshpocket.R.string.home_start_session),
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
            Text(stringResource(com.fioiu8.dshpocket.R.string.home_continue))
            Icon(Icons.Filled.ChevronRight, contentDescription = null)
        }
    }
}

internal data class LatestSession(val title: String?, val running: Boolean, val turns: Int)

/**
 * The three states the latest-session card can show.
 *
 * They look like two - "has a title or not" - but they are three, and collapsing them
 * into `session?.title ?: noSession` made an untitled session render as "no session".
 * Modelled separately so the middle case can be asserted.
 */
internal enum class LatestTitle { NoSession, Untitled, Titled }

internal fun latestTitleState(session: LatestSession?): LatestTitle = when {
    session == null -> LatestTitle.NoSession
    // isNullOrBlank rather than == null: the parser already normalises blanks away, but
    // this function should not depend on that having happened upstream.
    session.title.isNullOrBlank() -> LatestTitle.Untitled
    else -> LatestTitle.Titled
}

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
            { QuickRow(stringResource(com.fioiu8.dshpocket.R.string.home_new_chat), stringResource(com.fioiu8.dshpocket.R.string.home_new_chat_supporting), Icons.Filled.Add, onOpenChat) },
            { QuickRow(stringResource(com.fioiu8.dshpocket.R.string.home_session_list), stringResource(com.fioiu8.dshpocket.R.string.home_session_list_supporting), Icons.Filled.Forum, onOpenSessions) },
            { QuickRow(stringResource(com.fioiu8.dshpocket.R.string.home_plugins), stringResource(com.fioiu8.dshpocket.R.string.home_plugins_supporting), Icons.Filled.Extension, onOpenPlugins) },
            { QuickRow(stringResource(com.fioiu8.dshpocket.R.string.home_models), stringResource(com.fioiu8.dshpocket.R.string.home_models_supporting), Icons.Filled.Tune, onOpenModels) },
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
            title = stringResource(com.fioiu8.dshpocket.R.string.home_node_running)
            detail = stringResource(com.fioiu8.dshpocket.R.string.home_node_detail, EnginePaths.PORT, formatDuration(now - state.since), RuntimeInstaller.BUNDLED_ABI)
        }

        is EngineState.Busy -> {
            title = stringResource(com.fioiu8.dshpocket.R.string.home_starting)
            detail = state.message.asString()
        }

        is EngineState.Failed -> {
            title = stringResource(com.fioiu8.dshpocket.R.string.home_engine_error)
            detail = state.message.asString()
        }

        EngineState.Idle -> {
            title = stringResource(com.fioiu8.dshpocket.R.string.home_engine_stopped)
            detail = stringResource(com.fioiu8.dshpocket.R.string.home_restart_hint)
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
            TextButton(onClick = onRestart, modifier = Modifier.dshControl()) { Text(stringResource(com.fioiu8.dshpocket.R.string.restart)) }
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
