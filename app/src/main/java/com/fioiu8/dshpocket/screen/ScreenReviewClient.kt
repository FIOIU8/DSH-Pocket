package com.fioiu8.dshpocket.screen

import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * The model stage of the adjudication pipeline (plan §2.3.2): a user-configured
 * OpenAI-compatible endpoint judges whether the requested op is dangerous.
 *
 * Deliberately separate from the agent's model config — the engine holds that key as a
 * credential-ref the app cannot read, and a cheap reviewer model is a feature. Anything
 * wrong here (unreachable, slow, garbage output) is fail-closed: the op counts as
 * dangerous and the user dialog decides.
 */
class ScreenReviewClient(private val settings: ScreenControlSettings) {

    data class Verdict(
        val dangerous: Boolean,
        val reason: String,
        /** False when the model could not be consulted — the caller then fail-closes. */
        val available: Boolean,
    ) {
        companion object {
            fun unavailable(reason: String): Verdict = Verdict(dangerous = true, reason = reason, available = false)
        }
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /** Blocking; runs on the bridge thread (the whole pipeline is sequential anyway). */
    fun review(request: ScreenOpRequest, foregroundPackage: String?, targetSummary: String?): Verdict {
        if (!settings.hasReviewConfig()) {
            return Verdict.unavailable("审查模型未配置")
        }
        val payload = buildUserPayload(request, foregroundPackage, targetSummary)
        val body = buildRequestBody(settings.reviewModel, payload)
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val httpRequest = try {
            Request.Builder()
                .url(settings.reviewBaseUrl.trimEnd('/') + "/chat/completions")
                .header("Authorization", "Bearer ${settings.reviewApiKey}")
                .post(body)
                .build()
        } catch (t: Throwable) {
            return Verdict.unavailable("审查地址无效：${t.message ?: ""}")
        }
        return try {
            http.newCall(httpRequest).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return Verdict.unavailable("HTTP ${response.code}")
                }
                val content = extractMessageContent(raw)
                    ?: return Verdict.unavailable("响应缺少 choices")
                parseReviewOutput(content)
            }
        } catch (t: Throwable) {
            Verdict.unavailable("审查模型不可达：${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun extractMessageContent(raw: String): String? = try {
        JSONObject(raw)
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
        null
    }

    companion object {

        private const val TIMEOUT_SECONDS = 5L
        const val MAX_TARGET_SUMMARY = 200

        const val SYSTEM_PROMPT =
            "你是 Android 屏幕操作的安全审查器。判断 AI 请求的屏幕操作是否危险：涉及资金交易、" +
                "密码/凭据输入、支付或授权确认、隐私敏感数据、不可逆删除、或明显违背用户意图的操作都算危险。" +
                "只输出一个 JSON 对象，不要输出任何其他内容：{\"dangerous\": true, \"reason\": \"简短中文原因\"}"

        /**
         * The user-turn payload sent to the reviewer. Only action / reason / foreground
         * package / a short target-text summary ever leave the device (plan §4).
         */
        fun buildUserPayload(request: ScreenOpRequest, foregroundPackage: String?, targetSummary: String?): String {
            val root = JSONObject()
                .put("action", request.action.wire)
                .put("reason", request.reason)
                .put("foregroundPackage", foregroundPackage ?: "unknown")
            if (targetSummary != null) {
                root.put("targetText", targetSummary.take(MAX_TARGET_SUMMARY))
            }
            return root.toString()
        }

        fun buildRequestBody(model: String, userPayload: String): String = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                    .put(JSONObject().put("role", "user").put("content", userPayload)),
            )
            .put("temperature", 0)
            .put("max_tokens", 100)
            .toString()

        /**
         * Parses the reviewer's answer. Models love to wrap JSON in code fences or add
         * prose, so fences are stripped and the outermost braces are extracted. Missing
         * or unparseable output means dangerous=true (fail-closed) with available=false.
         */
        fun parseReviewOutput(raw: String): Verdict {
            val cleaned = raw
                .replace("```json", "```")
                .substringAfter("```", raw)
                .substringBefore("```")
                .trim()
            val start = cleaned.indexOf('{')
            val end = cleaned.lastIndexOf('}')
            if (start < 0 || end <= start) {
                return Verdict.unavailable("输出不是 JSON")
            }
            return try {
                val parsed = JSONObject(cleaned.substring(start, end + 1))
                Verdict(
                    dangerous = parsed.optBoolean("dangerous", true),
                    reason = parsed.optString("reason", "").ifBlank { "未给出原因" },
                    available = true,
                )
            } catch (_: Throwable) {
                Verdict.unavailable("输出不是合法 JSON")
            }
        }
    }
}
