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
import com.fioiu8.dshpocket.core.AppLanguagePreferences
import com.fioiu8.dshpocket.core.text
import com.fioiu8.dshpocket.screen.ScreenControlManager
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
        @Volatile
        private var activeService: EngineService? = null
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

        fun refresh(context: Context) {
            activeService?.let { service ->
                service.updateNotification(service.controller.state.value)
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var controller: EngineController

    override fun onCreate() {
        super.onCreate()
        activeService = this
        controller = EngineController.get(application)
        createNotificationChannel()
        try {
            startForeground(NOTIFICATION_ID, notification(EngineState.Busy(text(R.string.engine_preparing))))
        } catch (t: Throwable) {
            Log.e(TAG, "unable to enter foreground", t)
            stopSelf()
            return
        }

        serviceScope.launch {
            controller.state.collectLatest { updateNotification(it) }
        }
        serviceScope.launch(Dispatchers.IO) { monitorHealth() }
        // The screen-control bridge must be listening (and its token minted) before the
        // engine process reads its environment — hence before controller.start().
        ScreenControlManager.get(application).start()
        controller.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Deliberately idempotent: a START_STICKY restart delivers a null intent and must
        // still bring the engine back. Stopping goes through stopService(), which runs
        // onDestroy() — there is no intent-based stop channel.
        controller.start()
        return START_STICKY
    }

    override fun onDestroy() {
        if (activeService === this) activeService = null
        serviceScope.cancel()
        ScreenControlManager.get(application).stop()
        if (::controller.isInitialized) controller.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun monitorHealth() {
        var lastRestartAt = 0L
        while (serviceScope.isActive) {
            delay(HEALTH_INTERVAL_MS)
            val state = controller.state.value
            // Failed covers a dead engine: the process exit watcher transitions the
            // controller out of Ready the moment the process dies, so a Ready-only
            // reachability check would never fire for a crash or a kill.
            // Idle means the user stopped the engine on purpose — leave it stopped.
            val unhealthy = when (state) {
                is EngineState.Ready -> !EngineHealth.isReachable(state.baseUrl)
                is EngineState.Failed -> true
                else -> false
            }
            if (unhealthy) {
                val now = System.currentTimeMillis()
                if (now - lastRestartAt >= RESTART_COOLDOWN_MS) {
                    lastRestartAt = now
                    val reason = (state as? EngineState.Failed)?.message?.resolve(AppLanguagePreferences.localizedContext(this@EngineService))
                        ?: "unreachable while Ready"
                    Log.w(TAG, "engine unhealthy ($reason); restarting")
                    controller.restart()
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            localizedContext().getString(R.string.engine_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = localizedContext().getString(R.string.engine_notification_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun updateNotification(state: EngineState) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification(state))
    }

    private fun notification(state: EngineState): Notification =
        NotificationCompat.Builder(localizedContext(), CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(localizedContext().getString(R.string.app_name))
            .setContentText(state.label(localizedContext()))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()

    private fun localizedContext(): Context = AppLanguagePreferences.localizedContext(this)

    private fun EngineState.label(context: Context): String = when (this) {
        EngineState.Idle -> context.getString(R.string.engine_status_stopped)
        is EngineState.Busy -> message.resolve(context)
        is EngineState.Ready -> context.getString(R.string.engine_status_running)
        is EngineState.Failed -> context.getString(R.string.engine_status_failed, message.resolve(context))
    }
}

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
