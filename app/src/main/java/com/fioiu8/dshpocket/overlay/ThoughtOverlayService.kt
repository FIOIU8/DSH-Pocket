package com.fioiu8.dshpocket.overlay

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
import android.view.Gravity
import android.view.WindowManager
import com.fioiu8.dshpocket.screen.ScreenControlManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Shows the AI's chain of thought as a touch-through overlay while a turn runs
 * (user request, 2026-10-25).
 *
 * Visibility rule: the card is up while the assistant turn is active AND the app's
 * own UI is NOT in the foreground (inside the app the chat screen already shows the
 * thinking card) AND no screen operation is in flight. Every screen op (read,
 * screenshot, tap, …) runs inside the marquee pulse, so hiding on
 * [ScreenControlManager.screenOpsActive] removes the card before the first pixel is
 * captured or the first node is read — the AI never sees its own thoughts, neither in
 * screenshots nor in the accessibility tree (a detached view contributes no nodes).
 *
 * Not a foreground service: the app process is already held alive by EngineService for
 * as long as the engine runs, which is exactly this service's lifetime.
 */
class ThoughtOverlayService : Service() {

    companion object {
        private const val TAG = "ThoughtOverlay"
        private const val DONE_CARD_AUTO_HIDE_MS = 15_000L

        fun start(context: Context) {
            try {
                context.startService(Intent(context, ThoughtOverlayService::class.java))
            } catch (t: Throwable) {
                Log.w(TAG, "thought overlay could not start", t)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, ThoughtOverlayService::class.java))
            } catch (_: Throwable) {
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var view: ThoughtOverlayView? = null
    private var doneView: TurnDoneView? = null
    private var doneAutoHide: Runnable? = null

    /** True while the observed turn is (or was just) running; the active->inactive edge is the "turn finished" event. */
    private var wasActive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val opsActive = ScreenControlManager.peek()?.screenOpsActive ?: MutableStateFlow(false)
        scope.launch {
            combine(
                ThoughtStreamBus.state,
                opsActive,
                AppForegroundTracker.inForeground,
            ) { thought, ops, appForeground -> Triple(thought, ops, appForeground) }
                .collect { (thought, ops, appForeground) ->
                    if (thought.active) {
                        wasActive = true
                        hideDoneCard()
                    }
                    render(thought, ops, appForeground)
                    if (!thought.active && wasActive) {
                        wasActive = false
                        // The user asked for this: away from the app when the turn ends
                        // → a tappable card that jumps straight back in.
                        if (!ops && !appForeground && Settings.canDrawOverlays(this@ThoughtOverlayService)) {
                            showDoneCard()
                        }
                    }
                    if (appForeground || ops) hideDoneCard()
                }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        detach()
        hideDoneCard()
        super.onDestroy()
    }

    private fun render(state: ThoughtState, opsActive: Boolean, appInForeground: Boolean) {
        val settings = ScreenControlManager.peek()?.settings
        val wanted = state.active &&
            !opsActive &&
            !appInForeground &&
            settings?.thoughtOverlayEnabled == true &&
            Settings.canDrawOverlays(this)
        when {
            wanted && view == null -> attach()
            !wanted && view != null -> detach()
        }
        view?.render(state)
        // No stopSelf while idle: the service must keep observing the bus so the card
        // can appear the moment a turn starts. AppShell stops it when the engine stops.
    }

    private fun attach() {
        val created = ThoughtOverlayView(this)
        try {
            getSystemService(WindowManager::class.java).addView(created, overlayParams())
            view = created
        } catch (t: Throwable) {
            Log.w(TAG, "thought overlay attach failed", t)
        }
    }

    private fun detach() {
        mainHandler.post {
            view?.let { current ->
                try {
                    getSystemService(WindowManager::class.java).removeView(current)
                } catch (_: Throwable) {
                }
            }
            view = null
        }
    }

    private fun showDoneCard() {
        if (doneView != null) return
        val card = TurnDoneView(this).apply {
            onReturn = {
                hideDoneCard()
                try {
                    startActivity(
                        Intent(this@ThoughtOverlayService, com.fioiu8.dshpocket.MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } catch (_: Throwable) {
                }
            }
            onDismiss = { hideDoneCard() }
            render()
        }
        try {
            getSystemService(WindowManager::class.java).addView(card, doneCardParams())
            doneView = card
            val autoHide = Runnable { hideDoneCard() }
            doneAutoHide = autoHide
            mainHandler.postDelayed(autoHide, DONE_CARD_AUTO_HIDE_MS)
        } catch (t: Throwable) {
            Log.w(TAG, "turn-done card attach failed", t)
        }
    }

    private fun hideDoneCard() {
        doneAutoHide?.let { mainHandler.removeCallbacks(it) }
        doneAutoHide = null
        mainHandler.post {
            doneView?.let { card ->
                try {
                    getSystemService(WindowManager::class.java).removeView(card)
                } catch (_: Throwable) {
                }
            }
            doneView = null
        }
    }

    /** Touchable (the buttons need clicks) but not focusable, and modal only over its own bounds. */
    private fun doneCardParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val metrics = resources.displayMetrics
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            width = (metrics.widthPixels * 0.86f).toInt()
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (metrics.density * 72f).toInt()
        }
    }

    private fun overlayParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val metrics = resources.displayMetrics
        return WindowManager.LayoutParams(
            (metrics.widthPixels * 0.78f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // Touch-through: no touchable surface at all, no focus, touches reach the app below.
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (metrics.density * 72f).toInt()
        }
    }
}
