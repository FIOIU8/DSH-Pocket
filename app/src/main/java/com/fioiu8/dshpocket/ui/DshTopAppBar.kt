package com.fioiu8.dshpocket.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.fioiu8.dshpocket.ui.theme.DshSpacing

/**
 * 各屏共用的顶栏（设计稿 topAppBar，variant filled）：菜单图标开抽屉；
 * [subtitle] 用于首页的实时引擎状态与对话页的模型信息。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DshTopAppBar(
    title: String,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    TopAppBar(
        modifier = modifier,
        navigationIcon = {
            IconButton(
                onClick = onBack ?: onMenu,
                modifier = Modifier.size(DshSpacing.touchTarget),
            ) {
                Icon(
                    imageVector = if (onBack == null) Icons.Filled.Menu else Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(
                        if (onBack == null) com.fioiu8.dshpocket.R.string.open_navigation_drawer
                        else com.fioiu8.dshpocket.R.string.back_to_settings,
                    ),
                )
            }
        },
        title = {
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        actions = { actions() },
    )
}
