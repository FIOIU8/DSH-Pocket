package com.fioiu8.dshpocket.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.fioiu8.dshpocket.MainActivity
import com.fioiu8.dshpocket.R
import com.fioiu8.dshpocket.engine.EngineApi
import com.fioiu8.dshpocket.engine.EngineApi.RpcResult
import com.fioiu8.dshpocket.engine.EngineState
import com.fioiu8.dshpocket.core.AppLanguagePreferences
import com.fioiu8.dshpocket.data.session.EngineSessionSource
import com.fioiu8.dshpocket.data.session.SessionRow
import com.fioiu8.dshpocket.data.session.parseRows
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 回合完成通知：轮询 `session/list`，把"从运行中变为已停止"的会话推成系统通知。
 *
 * 为什么轮询而不是订阅流：会话流（session/follow）只覆盖当前打开的那一个会话，而通知
 * 恰恰要在用户**没有**盯着会话时到达；15s 级的列表轮询在回环接口上的代价可以忽略。
 * 2026-10-25 起不再因"对话页可见"而静音——用户点名要每回合结束都有提示。引擎未就绪时
 * 循环停止，运行集清空（引擎重启后旧状态没有意义）。
 */
object TurnNotifier {

    const val CHANNEL_TURNS = "turns"
    const val POLL_INTERVAL_MS = 10_000L

    private var job: Job? = null
    private val runningIds = mutableSetOf<String>()

    /** Transition rule, kept pure for tests: rows that were running and now are not. */
    internal fun newlyFinished(previousRunning: Set<String>, rows: List<SessionRow>): List<SessionRow> =
        rows.filter { !it.running && it.id in previousRunning }

    /** (Re)starts watching. Cancels any previous loop; a fresh [Ready] starts from silence. */
    fun watch(scope: CoroutineScope, context: Context, ready: EngineState.Ready) {
        stop()
        val localized = AppLanguagePreferences.localizedContext(context)
        ensureChannel(localized)
        job = scope.launch {
            val source = EngineSessionSource(ready.baseUrl, ready.token)
            while (isActive) {
                pollOnce(localized, source)
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** Stops the loop and forgets the running set; the next [watch] starts clean. */
    fun stop() {
        job?.cancel()
        job = null
        runningIds.clear()
    }

    private suspend fun pollOnce(context: Context, source: EngineSessionSource) {
        val rows = when (val result = source.list()) {
            is RpcResult.Ok -> parseRows(EngineApi.itemsOf(result.value))
            // The engine being mid-restart is routine; the next tick re-checks.
            is RpcResult.Err -> return
        }
        val finished = newlyFinished(runningIds.toSet(), rows)
        runningIds.clear()
        rows.forEach { if (it.running) runningIds.add(it.id) }
        finished.forEach { row -> post(context, row) }
    }

    private fun post(context: Context, row: SessionRow) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = row.title ?: context.getString(R.string.notify_turn_untitled)
        // The channel-id constructor is API 26+; on 24/25 the deprecated no-channel
        // builder is the only option and the channel call above is a no-op there.
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_TURNS)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        val notification = builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.notify_turn_title))
            .setContentText(context.getString(R.string.notify_turn_body, title))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        // One id per session: a second finished turn replaces, it does not pile up.
        manager.notify(row.id.hashCode(), notification)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_TURNS,
                context.getString(R.string.notify_channel_turns),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.notify_channel_turns_desc) },
        )
    }
}
