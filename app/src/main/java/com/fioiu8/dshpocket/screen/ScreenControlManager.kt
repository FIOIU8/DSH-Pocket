package com.fioiu8.dshpocket.screen

import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.fioiu8.dshpocket.engine.EngineController
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The user's answer to a flagged operation. No "always allow" exists by design. */
enum class GateDecision { ALLOW_ONCE, ALLOW_SESSION, DENY }

/** One pending user confirmation, passed to PermissionDialogActivity via the manager. */
class GateRequest(
    val id: String,
    val headline: String,
    val reason: String,
    val verdictReason: String,
) {
    val decision = CompletableFuture<GateDecision>()
}

/** What the permission dialog reports back: still-missing permissions when it left the screen. */
data class PermissionAskOutcome(
    val granted: List<String>,
    val missing: List<String>,
    val cancelled: Boolean,
)

/** One pending permission ask, passed to PermissionRequestActivity via the manager. */
class PermissionAskRequest(
    val id: String,
    val permissions: List<ScreenPermission>,
    val reason: String,
) {
    val outcome = CompletableFuture<PermissionAskOutcome>()
}

/**
 * Process-wide owner of the screen-control feature: runs the loopback bridge, exposes
 * the engine environment variables, drives the marquee pulse with its debounce, and
 * keeps the in-session allow cache plus the emergency-stop flag.
 *
 * Lifecycle follows EngineService (plan §2.2): started before the engine spawns (so the
 * token exists when the child env is assembled), stopped with it. The session key for
 * "allow for this session" is the engine generation — an engine restart invalidates
 * everything, and no allow decision is ever persisted.
 */
class ScreenControlManager private constructor(private val app: Application) {

    companion object {
        private const val TAG = "ScreenControl"
        const val PORT = 3091
        const val PULSE_DEBOUNCE_MS = 500L
        private const val OVERLAY_ATTACH_TIMEOUT_MS = 1_000L
        private const val GATE_TIMEOUT_MS = 45_000L
        private const val PERMISSION_ASK_TIMEOUT_MS = 200_000L

        @Volatile
        private var shared: ScreenControlManager? = null

        fun get(application: Application): ScreenControlManager = shared ?: synchronized(this) {
            shared ?: ScreenControlManager(application).also { shared = it }
        }

        fun peek(): ScreenControlManager? = shared
    }

    val settings = ScreenControlSettings(app)
    val audit = ScreenAuditLog(app)

    private val executor = ScreenOpExecutor(
        app,
        settings,
        audit,
        pulse = object : ScreenOpExecutor.Pulse {
            override fun start() = pulseStart()
            override fun end() = pulseEnd()
        },
        sessionEpoch = { engineGeneration() },
        emergencyStopped = { emergencyStoppedGeneration == engineGeneration() },
        confirmGate = { confirmGate(it) },
        isSessionAllowed = { isSessionAllowed() },
        rememberSessionAllow = { rememberSessionAllow() },
        askPermissions = { askPermissions(it) },
    )

    private var bridge: PocketBridge? = null
    private val pulseHandler = Handler(Looper.getMainLooper())
    private val activePulses = AtomicInteger(0)
    private var hideRunnable: Runnable? = null

    /** Counted down by ScreenControlService once the marquee window is really on screen. */
    @Volatile
    private var overlayLatch: CountDownLatch? = null

    @Volatile
    private var emergencyStoppedGeneration: Long = Long.MIN_VALUE

    private val gateRequests = ConcurrentHashMap<String, GateRequest>()
    private val permissionAsks = ConcurrentHashMap<String, PermissionAskRequest>()

    @Volatile
    private var allowEpoch: Long = Long.MIN_VALUE

    @Volatile
    private var sessionAllowed = false

    /** True while any screen operation (including its dialog) is in flight; the thought overlay hides on it. */
    val screenOpsActive = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** Idempotent; called from EngineService.onCreate before the engine spawns. */
    fun start() {
        settings.bridgeToken() // mint before EngineController assembles the child env
        if (bridge == null) {
            val candidate = PocketBridge(PORT, { settings.bridgeToken() }) { executor.execute(it) }
            if (candidate.start()) {
                bridge = candidate
            } else {
                Log.e(TAG, "bridge could not bind 127.0.0.1:$PORT; screen ops will report BRIDGE_UNAVAILABLE")
            }
        }
    }

    fun stop() {
        bridge?.stop()
        bridge = null
        activePulses.set(0)
        screenOpsActive.value = false
        pulseHandler.removeCallbacksAndMessages(null)
        hideRunnable = null
        ScreenControlService.hide(app)
    }

    /** Environment injected into the engine process; read by the host tool. */
    fun bridgeEnvironment(): Map<String, String> = mapOf(
        "DSH_POCKET_BRIDGE_URL" to "http://127.0.0.1:$PORT",
        "DSH_POCKET_BRIDGE_TOKEN" to settings.bridgeToken(),
    )

    /**
     * Notification-bar emergency stop: the current engine generation is flagged so every
     * later operation is refused, and the allow cache dies with it.
     */
    fun emergencyStop() {
        emergencyStoppedGeneration = engineGeneration()
        activePulses.set(0)
        pulseHandler.removeCallbacksAndMessages(null)
        hideRunnable = null
        abandonGateRequests()
        abandonPermissionAsks()
        synchronized(this) { sessionAllowed = false }
        ScreenControlService.hide(app)
    }

    fun isEmergencyStopped(): Boolean = emergencyStoppedGeneration == engineGeneration()

    // ---------------------------------------------------------------- user gate

    /** Blocking: launches the native dialog and waits for the user's answer. Bridge thread only. */
    fun confirmGate(request: GateRequest): GateDecision {
        gateRequests[request.id] = request
        try {
            app.startActivity(
                PermissionDialogActivity.intent(app, request.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "gate dialog could not launch; failing closed", t)
            gateRequests.remove(request.id)
            return GateDecision.DENY
        }
        return try {
            request.decision.get(GATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            GateDecision.DENY
        } finally {
            gateRequests.remove(request.id)
        }
    }

    /** The dialog activity pulls its pending request at creation. */
    fun takeGateRequest(id: String): GateRequest? = gateRequests[id]

    fun completeGateRequest(id: String, decision: GateDecision) {
        gateRequests.remove(id)?.decision?.complete(decision)
    }

    /** The dialog died without a decision (back, recents swipe, system kill) — deny. */
    fun abandonGateRequests() {
        gateRequests.values.forEach { it.decision.complete(GateDecision.DENY) }
        gateRequests.clear()
    }

    // ---------------------------------------------------------------- permission asks

    /**
     * Blocking: launches the native permission dialog for the AI's ask and waits for the
     * user to finish (cancel, or grant everything). Granting happens in system settings,
     * so the budget is generous; the JS side gives this action its own longer timeout.
     */
    fun askPermissions(request: ScreenOpRequest): PermissionAskOutcome? {
        val permissions = when (request.permission) {
            ScreenPermission.ACCESSIBILITY, ScreenPermission.OVERLAY ->
                // Both are prerequisites of every screen op; asking for one offers both.
                listOf(ScreenPermission.ACCESSIBILITY, ScreenPermission.OVERLAY)
            ScreenPermission.ALL_FILES -> listOf(ScreenPermission.ALL_FILES)
            null -> return null
        }
        val ask = PermissionAskRequest(
            id = java.util.UUID.randomUUID().toString(),
            permissions = permissions,
            reason = request.reason,
        )
        permissionAsks[ask.id] = ask
        try {
            app.startActivity(
                PermissionRequestActivity.intent(app, ask.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "permission dialog could not launch", t)
            permissionAsks.remove(ask.id)
            return null
        }
        return try {
            ask.outcome.get(PERMISSION_ASK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            PermissionAskOutcome(granted = emptyList(), missing = permissions.map { it.wire }, cancelled = true)
        } finally {
            permissionAsks.remove(ask.id)
        }
    }

    /** The dialog activity pulls its pending ask at creation. */
    fun takePermissionAsk(id: String): PermissionAskRequest? = permissionAsks[id]

    fun completePermissionAsk(id: String, outcome: PermissionAskOutcome) {
        permissionAsks.remove(id)?.outcome?.complete(outcome)
    }

    /** The dialog died (back, recents, kill): resolve with whatever is still missing. */
    fun abandonPermissionAsks() {
        permissionAsks.values.forEach { ask ->
            ask.outcome.complete(PermissionAskOutcome(emptyList(), ask.permissions.map { it.wire }, cancelled = true))
        }
        permissionAsks.clear()
    }

    // ---------------------------------------------------------------- in-session allow

    /**
     * Scoped to the current engine generation only: one "allow for this session" click
     * covers every later operation of that engine run.
     *
     * The first cut keyed the cache by (action, foreground package) — and re-asked
     * constantly, because the detected foreground flickers between the chat app itself,
     * the IME and system UI within a single AI turn (proven by the audit log on
     * 2026-10-25). Deny-list, keyguard and emergency stop keep their own protection
     * regardless of this flag.
     */
    fun isSessionAllowed(): Boolean = synchronized(this) {
        allowEpoch == engineGeneration() && sessionAllowed
    }

    fun rememberSessionAllow() = synchronized(this) {
        allowEpoch = engineGeneration()
        sessionAllowed = true
    }

    /** Called by ScreenControlService when the marquee attach attempt finished (either way). */
    fun onOverlayAttached() {
        overlayLatch?.countDown()
    }

    private fun pulseStart() {
        activePulses.incrementAndGet()
        screenOpsActive.value = true
        hideRunnable?.let { pulseHandler.removeCallbacks(it) }
        hideRunnable = null
        // The hint must be visible while the op runs, so starting a pulse blocks until
        // the overlay window is really attached (bounded — a slow WM never blocks ops).
        val latch = CountDownLatch(1)
        overlayLatch = latch
        ScreenControlService.show(app)
        try {
            latch.await(OVERLAY_ATTACH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
        }
        overlayLatch = null
    }

    private fun pulseEnd() {
        if (activePulses.decrementAndGet() > 0) return
        screenOpsActive.value = false
        val runnable = Runnable { ScreenControlService.hide(app) }
        hideRunnable = runnable
        pulseHandler.postDelayed(runnable, PULSE_DEBOUNCE_MS)
    }

    private fun engineGeneration(): Long = EngineController.get(app).generationValue()
}
