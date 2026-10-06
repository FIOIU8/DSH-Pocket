package com.fioiu8.dshpocket.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fioiu8.dshpocket.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

/** Keeps the local DSH process alive independently of the Activity lifecycle. */
class EngineService : Service() {

    companion object {
        private const val TAG = "EngineService"
        private const val CHANNEL_ID = "engine"
        private const val NOTIFICATION_ID = 3080
        private const val HEALTH_INTERVAL_MS = 15_000L
        private const val RESTART_COOLDOWN_MS = 30_000L

        fun start(context: Context) {
            try {
                val intent = Intent(context, EngineService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                // The Activity still starts the controller as a fallback when the
                // device rejects foreground-service startup.
                Log.e(TAG, "unable to start foreground service", t)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EngineService::class.java))
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var controller: EngineController

    override fun onCreate() {
        super.onCreate()
        controller = EngineController.get(applicationContext)
        createNotificationChannel()
        try {
            startForeground(NOTIFICATION_ID, notification(EngineState.Busy("准备中…")))
        } catch (t: Throwable) {
            Log.e(TAG, "unable to enter foreground", t)
            stopSelf()
            return
        }

        serviceScope.launch {
            controller.state.collectLatest { updateNotification(it) }
        }
        serviceScope.launch(Dispatchers.IO) { monitorHealth() }
        controller.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        controller.start()
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        if (::controller.isInitialized) controller.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun monitorHealth() {
        var lastRestartAt = 0L
        while (serviceScope.isActive) {
            delay(HEALTH_INTERVAL_MS)
            val state = controller.state.value
            if (state is EngineState.Ready && !EngineHealth.isReachable(state.baseUrl)) {
                val now = System.currentTimeMillis()
                if (now - lastRestartAt >= RESTART_COOLDOWN_MS) {
                    lastRestartAt = now
                    Log.w(TAG, "engine health check failed; restarting")
                    controller.restart()
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "DSH Pocket 引擎",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "保持本地 DSH 引擎运行"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun updateNotification(state: EngineState) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification(state))
    }

    private fun notification(state: EngineState): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("DSH Pocket")
            .setContentText(state.label())
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()

    private fun EngineState.label(): String = when (this) {
        EngineState.Idle -> "引擎已停止"
        is EngineState.Busy -> message
        is EngineState.Ready -> "引擎运行中"
        is EngineState.Failed -> "引擎异常：$message"
    }
}

private const val ACTION_STOP = "com.fioiu8.dshpocket.engine.STOP"

private object EngineHealth {

    fun isReachable(baseUrl: String): Boolean = try {
        val connection = (URL("$baseUrl/").openConnection() as HttpURLConnection).apply {
            connectTimeout = 3_000
            readTimeout = 3_000
            instanceFollowRedirects = false
            requestMethod = "GET"
        }
        try {
            connection.responseCode in 100..499
        } finally {
            connection.disconnect()
        }
    } catch (_: Throwable) {
        false
    }
}
