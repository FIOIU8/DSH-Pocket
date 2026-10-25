package com.fioiu8.dshpocket.screen

import android.content.Context
import android.provider.Settings
import android.app.KeyguardManager
import com.fioiu8.dshpocket.engine.EnginePaths
import java.util.ArrayDeque

/**
 * Deterministic first stage of the adjudication pipeline (plan §2.3.1). Pure — every
 * Android touch is gathered into [DeviceState] by the executor first — so the whole
 * rule set runs (and is locked down) in JVM unit tests.
 *
 * Order is fixed and short-circuiting; ScreenOpRulesTest locks both. "Look" operations
 * (read/screenshot) deliberately skip the deny-list check: watching is still reviewed by
 * the model stage, only touching is hard-refused by package name.
 */
object ScreenOpRules {

    const val OPS_PER_SECOND = 2
    const val SESSION_LIMIT = 60
    const val MAX_TEXT_LENGTH = ScreenOpCodec.MAX_TEXT_LENGTH

    data class DeviceState(
        val enabled: Boolean,
        val emergencyStopped: Boolean,
        val accessibilityReady: Boolean,
        val overlayAllowed: Boolean,
        val keyguardLocked: Boolean,
        val foregroundPackage: String?,
        val screenWidth: Int?,
        val screenHeight: Int?,
    )

    data class RateState(
        val now: Long,
        val recentTimestamps: List<Long>,
        val sessionCount: Int,
    )

    sealed interface Verdict {
        data object Pass : Verdict
        data class Block(val message: String) : Verdict
    }

    fun check(request: ScreenOpRequest, device: DeviceState, rate: RateState, denyList: Set<String>): Verdict {
        val permissionAsk = request.action == ScreenAction.REQUEST_PERMISSION
        // An all-files ask is a workspace concern, not a screen-control one: it stays
        // available even when the screen-control master switch is off.
        if (!device.enabled && !(permissionAsk && request.permission == ScreenPermission.ALL_FILES)) {
            return Verdict.Block("AI 屏幕操作已在设置中关闭")
        }
        if (device.emergencyStopped) {
            return Verdict.Block("用户已紧急停止本次引擎会话的屏幕操作；重启引擎后才会恢复")
        }
        // The permission ask exists precisely because these prerequisites can be off;
        // it bypasses them (and the deny list — it touches no other app).
        if (!permissionAsk) {
            if (!device.accessibilityReady) {
                return Verdict.Block("无障碍服务未开启；请在 设置 → AI 操作屏幕 中按引导开启")
            }
            if (!device.overlayAllowed) {
                return Verdict.Block("悬浮窗权限未授予；没有屏幕提示时应用会拒绝执行任何屏幕操作")
            }
            if (device.keyguardLocked) {
                return Verdict.Block("设备处于锁屏状态；解锁后才能执行屏幕操作")
            }
            val looks = request.action == ScreenAction.READ || request.action == ScreenAction.SCREENSHOT
            if (!looks && device.foregroundPackage != null && denyList.contains(device.foregroundPackage)) {
                return Verdict.Block("前台应用（${device.foregroundPackage}）在用户设置的拒绝名单中，禁止对其执行操作")
            }
        } else if (device.keyguardLocked) {
            return Verdict.Block("设备处于锁屏状态；解锁后才能处理授权请求")
        }

        val parameterProblem = validateParameters(request, device)
        if (parameterProblem != null) return Verdict.Block(parameterProblem)

        if (rate.sessionCount >= SESSION_LIMIT) {
            return Verdict.Block("本次引擎会话连续屏幕操作已达 $SESSION_LIMIT 次上限；请先结束回合")
        }
        val withinSecond = rate.recentTimestamps.count { rate.now - it < 1_000L }
        if (withinSecond >= OPS_PER_SECOND) {
            return Verdict.Block("操作频率超限（每秒最多 $OPS_PER_SECOND 次），请稍后再试")
        }
        return Verdict.Pass
    }

    private fun validateParameters(request: ScreenOpRequest, device: DeviceState): String? {
        fun coordinatesMissing(vararg values: Int?): Boolean = values.any { it == null }

        fun outOfBounds(value: Int, max: Int?): Boolean {
            if (value < 0) return true
            val resolvedMax = max ?: return false
            // displayMetrics can lag behind the real panel by system bars; a generous
            // margin keeps the check about "clearly off-screen" rather than about chrome.
            return value > resolvedMax + resolvedMax / 4
        }

        return when (request.action) {
            ScreenAction.TAP -> when {
                coordinatesMissing(request.x, request.y) -> "tap 需要提供 x 与 y 坐标"
                outOfBounds(request.x!!, device.screenWidth) || outOfBounds(request.y!!, device.screenHeight) ->
                    "tap 坐标（${request.x}, ${request.y}）超出屏幕范围"
                else -> null
            }
            ScreenAction.SWIPE -> when {
                coordinatesMissing(request.x, request.y, request.x2, request.y2) -> "swipe 需要提供 x、y 与 x2、y2 坐标"
                outOfBounds(request.x!!, device.screenWidth) || outOfBounds(request.y!!, device.screenHeight) ||
                    outOfBounds(request.x2!!, device.screenWidth) || outOfBounds(request.y2!!, device.screenHeight) ->
                    "swipe 坐标超出屏幕范围"
                else -> null
            }
            ScreenAction.TEXT -> when {
                request.text.isNullOrEmpty() -> "text 需要提供要输入的内容"
                request.text.length > MAX_TEXT_LENGTH -> "text 内容超过 $MAX_TEXT_LENGTH 字符上限"
                else -> null
            }
            ScreenAction.KEY -> if (request.key == null) "key 需要提供 back | home | recents 之一" else null
            ScreenAction.REQUEST_PERMISSION ->
                if (request.permission == null) "request_permission 需要提供 accessibility | overlay | all_files 之一" else null
            ScreenAction.READ, ScreenAction.SCREENSHOT -> null
        }
    }
}

/**
 * The adjudication pipeline (plan §2.3), in fixed order with short-circuiting:
 * deterministic rules → review model → user gate (dangerous/ask-always only) →
 * execute → audit. The marquee pulse spans everything after the rules so the hint is
 * up during review, the dialog and the gesture itself.
 */
class ScreenOpExecutor(
    private val appContext: Context,
    private val settings: ScreenControlSettings,
    private val audit: ScreenAuditLog,
    private val pulse: Pulse,
    private val sessionEpoch: () -> Long,
    private val emergencyStopped: () -> Boolean,
    private val confirmGate: (GateRequest) -> GateDecision,
    private val isSessionAllowed: () -> Boolean,
    private val rememberSessionAllow: () -> Unit,
    private val askPermissions: (ScreenOpRequest) -> PermissionAskOutcome?,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Visual hint lifecycle, implemented by ScreenControlManager (service-backed). */
    interface Pulse {
        fun start()
        fun end()
    }

    private val reviewClient = ScreenReviewClient(settings)

    private val lock = Any()
    private var countedEpoch: Long = Long.MIN_VALUE
    private val recentTimestamps = ArrayDeque<Long>()
    private var sessionCount = 0

    fun execute(request: ScreenOpRequest): ScreenOpResult {
        val service = PocketAccessibilityService.peek()
        val device = ScreenOpRules.DeviceState(
            enabled = settings.enabled,
            emergencyStopped = emergencyStopped(),
            accessibilityReady = service != null,
            overlayAllowed = Settings.canDrawOverlays(appContext),
            keyguardLocked = keyguardLocked(),
            foregroundPackage = service?.foregroundPackageNow(),
            screenWidth = service?.displaySize()?.first,
            screenHeight = service?.displaySize()?.second,
        )
        val verdict: ScreenOpRules.Verdict = synchronized(lock) {
            val now = clock()
            if (sessionEpoch() != countedEpoch) {
                countedEpoch = sessionEpoch()
                recentTimestamps.clear()
                sessionCount = 0
            }
            while (recentTimestamps.isNotEmpty() && now - recentTimestamps.first() >= 1_000L) {
                recentTimestamps.removeFirst()
            }
            val outcome = ScreenOpRules.check(request, device, ScreenOpRules.RateState(now, recentTimestamps.toList(), sessionCount), settings.denyList())
            if (outcome == ScreenOpRules.Verdict.Pass) {
                recentTimestamps.addLast(now)
                sessionCount++
            }
            outcome
        }
        if (verdict is ScreenOpRules.Verdict.Block) {
            audit.append(ScreenAuditEntry(clock(), request.action.wire, request.reason, device.foregroundPackage, ScreenOpCodes.RULE_BLOCKED, verdict.message))
            return ScreenOpResult.ruleBlocked(verdict.message)
        }

        // A permission ask touches no screen and passes no review: it only opens the
        // native dialog that walks the user to the system authorization page.
        if (request.action == ScreenAction.REQUEST_PERMISSION) {
            val outcome = askPermissions(request)
                ?: return auditAndFail(device, request, "授权请求无法弹出界面（应用可能被限制后台启动）")
            val result = if (outcome.missing.isEmpty()) {
                ScreenOpResult.Ok(org.json.JSONObject().put("granted", org.json.JSONArray(outcome.granted)))
            } else {
                ScreenOpResult.Failure(
                    ScreenOpCodes.USER_DENIED,
                    "用户${if (outcome.cancelled) "取消了授权请求" else "未完成全部授权"}（仍缺少：${outcome.missing.joinToString("、")}）。" +
                        "请勿立即重复请求；请用自然语言向用户说明需要哪项权限及用途，待用户开启后再试。",
                )
            }
            audit.append(
                ScreenAuditEntry(
                    clock(),
                    request.action.wire,
                    request.reason,
                    device.foregroundPackage,
                    if (outcome.missing.isEmpty()) "OK" else ScreenOpCodes.USER_DENIED,
                    "asked=${request.permission?.wire ?: "?"}; granted=${outcome.granted.joinToString(",")}; missing=${outcome.missing.joinToString(",")}",
                ),
            )
            return result
        }

        pulse.start()
        try {
            val activeService = service ?: return auditAndFail(device, request, "无障碍服务在执行前被关闭")

            // --- model review stage ("look" ops included; plan §2.3.1) ---
            val targetSummary = when (request.action) {
                ScreenAction.TAP -> if (request.x != null && request.y != null) activeService.targetTextAt(request.x, request.y) else null
                ScreenAction.READ -> activeService.activeWindowSummary()
                else -> null
            }
            val review = if (settings.reviewEnabled) {
                reviewClient.review(request, device.foregroundPackage, targetSummary)
            } else {
                null
            }
            val dangerous = review == null || !review.available || review.dangerous

            // --- user gate stage ---
            // The fingerprint still names the op in the dialog headline and the audit
            // chain; the allow itself covers the whole engine session (2026-10-25).
            val fingerprint = "${request.action.wire}:${device.foregroundPackage ?: "?"}"
            var decision: GateDecision? = null
            if ((settings.askEveryTime || dangerous) && !isSessionAllowed()) {
                val gateRequest = GateRequest(
                    id = java.util.UUID.randomUUID().toString(),
                    headline = "在 ${device.foregroundPackage ?: "未知应用"} 上执行 ${request.action.wire}",
                    reason = request.reason,
                    verdictReason = when {
                        review == null -> "模型审查已关闭；每次操作都需要确认"
                        !review.available -> "审查模型不可用（${review.reason.take(80)}），按危险处理"
                        else -> "审查模型判定为危险：${review.reason.take(80)}"
                    },
                )
                decision = confirmGate(gateRequest)
                when (decision) {
                    GateDecision.ALLOW_SESSION -> rememberSessionAllow()
                    GateDecision.DENY -> {
                        val (code, message) = when {
                            review == null -> ScreenOpCodes.USER_DENIED to ScreenDenyMessages.userDeniedAskMode()
                            !review.available -> ScreenOpCodes.REVIEW_BLOCKED to ScreenDenyMessages.reviewUnavailable(review.reason)
                            else -> ScreenOpCodes.USER_DENIED to ScreenDenyMessages.reviewDenied(review.reason)
                        }
                        audit.append(ScreenAuditEntry(clock(), request.action.wire, request.reason, device.foregroundPackage, code, chain(review, decision, message)))
                        return ScreenOpResult.Failure(code, message)
                    }
                    GateDecision.ALLOW_ONCE -> {}
                }
            }

            // --- execution stage ---
            val result = runOnService(request, activeService)
            audit.append(
                ScreenAuditEntry(
                    clock(),
                    request.action.wire,
                    request.reason,
                    device.foregroundPackage,
                    if (result is ScreenOpResult.Ok) "OK" else (result as ScreenOpResult.Failure).code,
                    chain(review, decision, (result as? ScreenOpResult.Failure)?.message),
                ),
            )
            return result
        } finally {
            pulse.end()
        }
    }

    private fun chain(review: ScreenReviewClient.Verdict?, decision: GateDecision?, tail: String?): String {
        val reviewPart = when {
            review == null -> "review=off"
            !review.available -> "review=unavailable(${review.reason.take(40)})"
            review.dangerous -> "review=dangerous(${review.reason.take(40)})"
            else -> "review=safe"
        }
        val userPart = "user=" + (decision?.name?.lowercase() ?: "not_asked")
        return if (tail == null) "$reviewPart; $userPart" else "$reviewPart; $userPart; ${tail.take(120)}"
    }

    private fun auditAndFail(device: ScreenOpRules.DeviceState, request: ScreenOpRequest, message: String): ScreenOpResult {
        audit.append(ScreenAuditEntry(clock(), request.action.wire, request.reason, device.foregroundPackage, ScreenOpCodes.EXEC_FAILED, message))
        return ScreenOpResult.execFailed(message)
    }

    private fun runOnService(request: ScreenOpRequest, service: PocketAccessibilityService): ScreenOpResult =
        when (request.action) {
            ScreenAction.READ -> service.readScreen()
            ScreenAction.TAP -> service.tap(checkNotNull(request.x), checkNotNull(request.y))
            ScreenAction.SWIPE -> service.swipe(checkNotNull(request.x), checkNotNull(request.y), checkNotNull(request.x2), checkNotNull(request.y2))
            ScreenAction.TEXT -> service.inputText(checkNotNull(request.text), request.x, request.y)
            ScreenAction.KEY -> service.pressKey(checkNotNull(request.key))
            ScreenAction.SCREENSHOT -> service.takeScreenshot(EnginePaths.workspace(appContext))
            // Handled before the pulse/review/gate pipeline; kept for exhaustiveness.
            ScreenAction.REQUEST_PERMISSION -> ScreenOpResult.ok("performed", true)
        }

    private fun keyguardLocked(): Boolean =
        (appContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
}
