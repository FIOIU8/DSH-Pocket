package com.fioiu8.dshpocket.screen

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fioiu8.dshpocket.ui.theme.DSHPocketTheme

/**
 * The native gate (plan §2.3.3): a translucent, non-dismissable-by-back activity over
 * whatever is on screen, asking the user to allow one flagged screen operation. Lives
 * only while a decision is pending; every exit path resolves the pending [GateRequest]
 * — destruction without a decision counts as DENY (fail-closed).
 *
 * Launched with NEW_TASK from the app context. The overlay permission this feature
 * already requires doubles as the background-activity-launch exemption.
 */
class PermissionDialogActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
        val request = requestId?.let { ScreenControlManager.peek()?.takeGateRequest(it) }
        if (request == null) {
            finish()
            return
        }
        setContent {
            DSHPocketTheme {
                GateContent(
                    request = request,
                    onDecision = { decision ->
                        ScreenControlManager.peek()?.completeGateRequest(request.id, decision)
                        finish()
                    },
                )
            }
        }
    }

    override fun onDestroy() {
        // Back button, recents swipe, or a system kill must never leave the bridge
        // thread waiting: an unfinished request is a denial.
        ScreenControlManager.peek()?.abandonGateRequests()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_REQUEST_ID = "com.fioiu8.dshpocket.screen.GATE_REQUEST_ID"

        fun intent(context: Context, requestId: String): Intent =
            Intent(context, PermissionDialogActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(EXTRA_REQUEST_ID, requestId)
    }
}

@Composable
private fun GateContent(request: GateRequest, onDecision: (GateDecision) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center,
    ) {
        Card(modifier = Modifier.padding(24.dp).widthIn(max = 480.dp)) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "AI 请求执行屏幕操作",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = request.headline,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "AI 说明：${request.reason}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "审查判定：${request.verdictReason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = "「本会话允许」只对相同应用、相同类型的后续操作生效，引擎重启后失效；屏幕操作没有永久豁免。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    androidx.compose.foundation.layout.Row {
                        TextButton(onClick = { onDecision(GateDecision.DENY) }) { Text("拒绝") }
                        TextButton(onClick = { onDecision(GateDecision.ALLOW_SESSION) }) { Text("本会话允许") }
                        TextButton(onClick = { onDecision(GateDecision.ALLOW_ONCE) }) { Text("仅本次允许") }
                    }
                }
            }
        }
    }
}
