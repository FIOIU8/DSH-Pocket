package com.fioiu8.dshpocket.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fioiu8.dshpocket.ui.theme.DSHPocketTheme
import kotlinx.coroutines.delay

/**
 * The AI-initiated permission ask (user request, 2026-10-25): instead of relaying
 * "please dig through settings", the agent calls `request_permission` and this dialog
 * opens — what the AI wants at the top, one switch row per permission in the middle
 * (turning a switch on jumps straight to that permission's authorization page), and a
 * full-width cancel button at the bottom.
 *
 * The switches reflect the REAL permission state and re-check it while the dialog is
 * visible (the accessibility service binds asynchronously after the user toggles it in
 * system settings). When everything asked for is granted, the dialog resolves the ask
 * and finishes on its own; every other exit resolves with the still-missing list.
 */
class PermissionRequestActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
        val ask = requestId?.let { ScreenControlManager.peek()?.takePermissionAsk(it) }
        if (ask == null) {
            finish()
            return
        }
        setContent {
            DSHPocketTheme {
                PermissionAskContent(
                    ask = ask,
                    grantedStates = { ask.permissions.associateWith { isGranted(it) } },
                    onOpenSettings = { permission -> openAuthorization(permission) },
                    onCancel = {
                        ScreenControlManager.peek()?.completePermissionAsk(
                            ask.id,
                            PermissionAskOutcome(granted = emptyList(), missing = ask.permissions.map { it.wire }, cancelled = true),
                        )
                        finish()
                    },
                    onAllGranted = {
                        ScreenControlManager.peek()?.completePermissionAsk(
                            ask.id,
                            PermissionAskOutcome(granted = ask.permissions.map { it.wire }, missing = emptyList(), cancelled = false),
                        )
                        finish()
                    },
                )
            }
        }
    }

    override fun onDestroy() {
        // Back / recents / kill must never leave the bridge thread waiting.
        ScreenControlManager.peek()?.abandonPermissionAsks()
        super.onDestroy()
    }

    private fun isGranted(permission: ScreenPermission): Boolean = when (permission) {
        ScreenPermission.ACCESSIBILITY -> PocketAccessibilityService.peek() != null
        ScreenPermission.OVERLAY -> Settings.canDrawOverlays(this)
        ScreenPermission.ALL_FILES -> Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            Environment.isExternalStorageManager()
    }

    private fun openAuthorization(permission: ScreenPermission) {
        val intent = when (permission) {
            ScreenPermission.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            ScreenPermission.OVERLAY ->
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            ScreenPermission.ALL_FILES ->
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (_: Throwable) {
            // The plain settings root still gets the user to the right page.
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        const val EXTRA_REQUEST_ID = "com.fioiu8.dshpocket.screen.PERMISSION_ASK_ID"

        fun intent(context: Context, requestId: String): Intent =
            Intent(context, PermissionRequestActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(EXTRA_REQUEST_ID, requestId)
    }
}

@Composable
private fun PermissionAskContent(
    ask: PermissionAskRequest,
    grantedStates: () -> Map<ScreenPermission, Boolean>,
    onOpenSettings: (ScreenPermission) -> Unit,
    onCancel: () -> Unit,
    onAllGranted: () -> Unit,
) {
    var states by remember { mutableStateOf(grantedStates()) }

    // The accessibility service binds a beat after the user flips it in system
    // settings; poll the real state while the dialog is on screen so the switches
    // catch up and the ask can complete the moment everything is granted.
    androidx.compose.runtime.LaunchedEffect(ask.id) {
        while (true) {
            delay(700)
            states = grantedStates()
            if (states.values.all { it }) {
                onAllGranted()
                break
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center,
    ) {
        Card(modifier = Modifier.padding(24.dp).widthIn(max = 480.dp)) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(com.fioiu8.dshpocket.R.string.perm_ask_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(com.fioiu8.dshpocket.R.string.perm_ask_reason, ask.reason),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(com.fioiu8.dshpocket.R.string.perm_ask_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Column(modifier = Modifier.padding(top = 6.dp)) {
                    ask.permissions.forEachIndexed { index, permission ->
                        if (index > 0) HorizontalDivider()
                        val granted = states[permission] ?: false
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = permissionLabel(permission),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = permissionDescription(permission),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = granted,
                                onCheckedChange = { wanted ->
                                    // The switch is a jump trigger, not the grant itself:
                                    // state only ever comes from the real permission check.
                                    if (wanted && !granted) onOpenSettings(permission)
                                },
                            )
                        }
                    }
                }

                Button(
                    onClick = onCancel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                ) {
                    Text(stringResource(com.fioiu8.dshpocket.R.string.perm_ask_cancel))
                }
            }
        }
    }
}

@Composable
private fun permissionLabel(permission: ScreenPermission): String = when (permission) {
    ScreenPermission.ACCESSIBILITY -> stringResource(com.fioiu8.dshpocket.R.string.perm_name_accessibility)
    ScreenPermission.OVERLAY -> stringResource(com.fioiu8.dshpocket.R.string.perm_name_overlay)
    ScreenPermission.ALL_FILES -> stringResource(com.fioiu8.dshpocket.R.string.perm_name_all_files)
}

@Composable
private fun permissionDescription(permission: ScreenPermission): String = when (permission) {
    ScreenPermission.ACCESSIBILITY -> stringResource(com.fioiu8.dshpocket.R.string.perm_desc_accessibility)
    ScreenPermission.OVERLAY -> stringResource(com.fioiu8.dshpocket.R.string.perm_desc_overlay)
    ScreenPermission.ALL_FILES -> stringResource(com.fioiu8.dshpocket.R.string.perm_desc_all_files)
}
