package com.fioiu8.dshpocket.screen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fioiu8.dshpocket.R
import com.fioiu8.dshpocket.core.AppLanguagePreferences

/**
 * Hosts the marquee overlay and the "stop the AI" notification while operations run
 * (plan §2.5). Started on the first operation pulse, stopped after the pulse ends plus
 * the debounce window, so the extra notification only exists while the border is on.
 *
 * The emergency stop clears the in-session allow cache and flags the current engine
 * generation as stopped — later operations from that engine are refused until it
 * restarts. A gesture already in flight is bounded by its own duration and completes.
 */
class ScreenControlService : Service() {

    companion object {
        private const val TAG = "ScreenControl"
        private const val CHANNEL_ID = "screen_control"
        const val NOTIFICATION_ID = 3091
        const val ACTION_SHOW = "com.fioiu8.dshpocket.screen.action.SHOW"
        const val ACTION_HIDE = "com.fioiu8.dshpocket.screen.action.HIDE"
        const val ACTION_STOP = "com.fioiu8.dshpocket.screen.action.STOP"

        fun show(context: Context) = send(context, ACTION_SHOW, foreground = true)

        fun hide(context: Context) = send(context, ACTION_HIDE, foreground = false)

        private fun send(context: Context, action: String, foreground: Boolean) {
            val intent = Intent(context, ScreenControlService::class.java).setAction(action)
            try {
                if (foreground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                // The app is kept foreground by EngineService, so this should not happen;
                // losing the visual hint must never crash the operation itself.
                Log.w(TAG, "screen control service request $action failed", t)
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlay: MarqueeOverlayView? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> attachOverlay()
            ACTION_HIDE -> {
                detachOverlay()
                stopSelf()
            }
            ACTION_STOP -> {
                ScreenControlManager.peek()?.emergencyStop()
                detachOverlay()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun attachOverlay() {
        // Every exit path confirms back to the manager — a pulse must not wait on a
        // latch that nobody will count down.
        fun confirm() {
            ScreenControlManager.peek()?.onOverlayAttached()
        }
        if (overlay != null) {
            confirm()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            // Rule filtering already refuses ops without the overlay grant; the service
            // just fails visibly here instead of showing a permission-less hint.
            Log.w(TAG, "overlay permission missing; not showing marquee")
            confirm()
            stopSelf()
            return
        }
        mainHandler.post {
            if (overlay != null) {
                confirm()
                return@post
            }
            val view = MarqueeOverlayView(AppLanguagePreferences.localizedContext(this))
            try {
                getSystemService(WindowManager::class.java).addView(view, overlayParams())
                overlay = view
            } catch (t: Throwable) {
                Log.e(TAG, "overlay attach failed", t)
                stopSelf()
            }
            confirm()
        }
    }

    private fun detachOverlay() {
        mainHandler.post {
            overlay?.let { view ->
                try {
                    getSystemService(WindowManager::class.java).removeView(view)
                } catch (_: Throwable) {
                }
            }
            overlay = null
        }
    }

    private fun overlayParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun notification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, ScreenControlService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val localized = AppLanguagePreferences.localizedContext(this)
        return NotificationCompat.Builder(localized, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(localized.getString(R.string.screen_control_active_title))
            .setContentText(localized.getString(R.string.screen_control_active_text))
            .addAction(0, localized.getString(R.string.screen_control_stop), stopIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val localized = AppLanguagePreferences.localizedContext(this)
        val channel = NotificationChannel(
            CHANNEL_ID,
            localized.getString(R.string.screen_control_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = localized.getString(R.string.screen_control_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
