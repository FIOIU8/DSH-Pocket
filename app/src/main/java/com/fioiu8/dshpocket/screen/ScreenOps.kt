package com.fioiu8.dshpocket.screen

import org.json.JSONObject

/**
 * Wire contract of the screen-control bridge (`POST /screen`), shared by the bridge
 * server, the executor pipeline and the JVM tests that lock the semantics down.
 *
 * The AI sees exactly these shapes: `{ok:true,data}` on success, `{ok:false,code,message}`
 * on every refusal — the tool result is the verdict, no engine cooperation involved.
 */
enum class ScreenAction(val wire: String) {
    READ("read"),
    TAP("tap"),
    SWIPE("swipe"),
    TEXT("text"),
    KEY("key"),
    SCREENSHOT("screenshot"),
    REQUEST_PERMISSION("request_permission");

    companion object {
        fun fromWire(value: String): ScreenAction? = entries.firstOrNull { it.wire == value }
    }
}

/** App-level permissions the AI may ask the user to grant via `request_permission`. */
enum class ScreenPermission(val wire: String) {
    ACCESSIBILITY("accessibility"),
    OVERLAY("overlay"),
    ALL_FILES("all_files");

    companion object {
        fun fromWire(value: String): ScreenPermission? = entries.firstOrNull { it.wire == value }
    }
}

enum class ScreenKey(val wire: String) {
    BACK("back"),
    HOME("home"),
    RECENTS("recents");

    companion object {
        fun fromWire(value: String): ScreenKey? = entries.firstOrNull { it.wire == value }
    }
}

data class ScreenOpRequest(
    val action: ScreenAction,
    val reason: String,
    val x: Int? = null,
    val y: Int? = null,
    val x2: Int? = null,
    val y2: Int? = null,
    val text: String? = null,
    val key: ScreenKey? = null,
    /** Only meaningful for REQUEST_PERMISSION: which app-level permission the AI is asking for. */
    val permission: ScreenPermission? = null,
    /** Best effort: the engine may not hand a session id to the host tool; the app falls back to its engine generation. */
    val sessionId: String? = null,
)

/** Refusal / failure codes surfaced verbatim to the AI. */
object ScreenOpCodes {
    const val USER_DENIED = "USER_DENIED"
    const val REVIEW_BLOCKED = "REVIEW_BLOCKED"
    const val RULE_BLOCKED = "RULE_BLOCKED"
    const val BRIDGE_UNAVAILABLE = "BRIDGE_UNAVAILABLE"
    const val EXEC_FAILED = "EXEC_FAILED"
}

sealed interface ScreenOpResult {
    data class Ok(val data: JSONObject) : ScreenOpResult
    data class Failure(val code: String, val message: String) : ScreenOpResult

    fun toJson(): JSONObject = when (this) {
        is Ok -> JSONObject().put("ok", true).put("data", data)
        is Failure -> JSONObject().put("ok", false).put("code", code).put("message", message)
    }

    companion object {
        fun ok(key: String, value: Any): Ok = Ok(JSONObject().put(key, value))
        fun ruleBlocked(message: String): Failure = Failure(ScreenOpCodes.RULE_BLOCKED, message)
        fun execFailed(message: String): Failure = Failure(ScreenOpCodes.EXEC_FAILED, message)
    }
}

/**
 * The fixed refusal wording demanded by the plan (§2.1): the AI must be able to read
 * why it was denied and be told not to retry. Locked by ScreenDenyMessagesTest.
 */
object ScreenDenyMessages {

    /** Shown when the review model flags the op and the user declines the gate dialog. */
    fun reviewDenied(modelReason: String): String =
        "操作被拒绝：内容审查判定为敏感操作（${modelReason.take(120)}）。" +
            "用户已明确拒绝授权，本次调用失败。请勿重试相同操作；" +
            "如确有需要，请先用自然语言向用户解释并取得同意。"

    /** Shown when the review model is unreachable or answers garbage — fail-closed without a dialog. */
    fun reviewUnavailable(detail: String): String =
        "操作被拒绝：审查模型不可用或返回无法解析的结果（${detail.take(120)}），按策略不执行。" +
            "本次调用失败。请勿重试相同操作；如确有需要，请先用自然语言向用户解释并取得同意。"

    /** Shown when the user denies in "ask every time" mode — no review verdict involved. */
    fun userDeniedAskMode(): String =
        "操作被拒绝：当前设置为每次屏幕操作都需用户确认，用户已明确拒绝授权，本次调用失败。" +
            "请勿重试相同操作；如确有需要，请先用自然语言向用户解释并取得同意。"
}

/**
 * The read tree's node budget is spent window by window; walking a busy launcher or a
 * system overlay first would starve the app the user is actually looking at. Moving the
 * active window to the front fixes that; the sort is stable, so every other window keeps
 * the system order.
 */
internal fun <T> prioritizeActive(items: List<T>, isActive: (T) -> Boolean): List<T> =
    items.sortedBy { if (isActive(it)) 0 else 1 }

/** Decodes the JSON body the host tool sends. Malformed bodies become a 400 at the bridge. */
object ScreenOpCodec {

    const val MAX_TEXT_LENGTH = 512
    const val MAX_REASON_LENGTH = 200

    fun parseRequest(body: String): ScreenOpRequest? {
        val root = try {
            JSONObject(body)
        } catch (_: Throwable) {
            return null
        }
        val action = ScreenAction.fromWire(root.optString("action")) ?: return null
        val reason = root.optString("reason").trim()
        if (reason.isEmpty() || reason.length > MAX_REASON_LENGTH) return null
        val text = root.optString("text").takeIf { it.isNotEmpty() }
        val key = root.optString("key").takeIf { it.isNotEmpty() }?.let { ScreenKey.fromWire(it) }
            .let { parsed -> if (parsed == null && root.optString("key").isNotEmpty()) return null else parsed }
        val permission = root.optString("permission").takeIf { it.isNotEmpty() }?.let { ScreenPermission.fromWire(it) }
            .let { parsed -> if (parsed == null && root.optString("permission").isNotEmpty()) return null else parsed }
        return ScreenOpRequest(
            action = action,
            reason = reason,
            x = optInt(root, "x"),
            y = optInt(root, "y"),
            x2 = optInt(root, "x2"),
            y2 = optInt(root, "y2"),
            text = text,
            key = key,
            permission = permission,
            sessionId = root.optJSONObject("meta")?.optString("sessionId")?.takeIf { it.isNotEmpty() },
        )
    }

    private fun optInt(root: JSONObject, name: String): Int? = when (val value = root.opt(name)) {
        null, JSONObject.NULL -> null
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }
}
