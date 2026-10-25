package com.fioiu8.dshpocket

import com.fioiu8.dshpocket.screen.ScreenAuditEntry
import com.fioiu8.dshpocket.screen.ScreenAuditLog
import com.fioiu8.dshpocket.screen.ScreenDenyMessages
import com.fioiu8.dshpocket.screen.ScreenReviewClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The refusal wording is a user-named behavior (plan §2.1): the AI must be able to read
 * why it was denied and be told not to retry. These tests lock the exact templates so a
 * refactor cannot silently blur them.
 */
class ScreenDenyMessagesTest {

    @Test
    fun `review-denied message names the verdict reason and forbids retrying`() {
        val message = ScreenDenyMessages.reviewDenied("涉及支付确认")
        assertTrue(message.startsWith("操作被拒绝：内容审查判定为敏感操作"))
        assertTrue(message.contains("涉及支付确认"))
        assertTrue(message.contains("请勿重试相同操作"))
        assertTrue(message.contains("自然语言向用户解释并取得同意"))
    }

    @Test
    fun `unavailable-review message is fail-closed phrasing`() {
        val message = ScreenDenyMessages.reviewUnavailable("HTTP 500")
        assertTrue(message.contains("审查模型不可用"))
        assertTrue(message.contains("按策略不执行"))
        assertTrue(message.contains("请勿重试相同操作"))
    }

    @Test
    fun `ask-mode denial mentions the every-time mode`() {
        val message = ScreenDenyMessages.userDeniedAskMode()
        assertTrue(message.contains("每次屏幕操作都需用户确认"))
        assertTrue(message.contains("请勿重试相同操作"))
    }

    @Test
    fun `long model reasons are truncated to keep tool results bounded`() {
        val message = ScreenDenyMessages.reviewDenied("长".repeat(500))
        assertTrue(message.length < 400)
    }
}

/** The reviewer's output contract: clean JSON, fenced JSON, prose-wrapped JSON, garbage. */
class ScreenReviewOutputTest {

    @Test
    fun `clean json parses with the model verdict`() {
        val verdict = ScreenReviewClient.parseReviewOutput("""{"dangerous": false, "reason": "普通点击"}""")
        assertTrue(verdict.available)
        assertFalse(verdict.dangerous)
        assertEquals("普通点击", verdict.reason)
    }

    @Test
    fun `code-fenced json parses`() {
        val fenced = "```json\n{\"dangerous\": true, \"reason\": \"输入密码\"}\n```"
        val verdict = ScreenReviewClient.parseReviewOutput(fenced)
        assertTrue(verdict.available)
        assertTrue(verdict.dangerous)
        assertEquals("输入密码", verdict.reason)
    }

    @Test
    fun `prose-wrapped json parses`() {
        val wrapped = "审查结论：{\"dangerous\":true,\"reason\":\"支付授权\"} 请参考。"
        val verdict = ScreenReviewClient.parseReviewOutput(wrapped)
        assertTrue(verdict.available)
        assertTrue(verdict.dangerous)
    }

    @Test
    fun `garbage missing field and empty answers all fail closed`() {
        for (raw in listOf("not json", "{\"reason\":\"没有字段\"}", "", "  ", "{\"dangerous\": null}")) {
            val verdict = ScreenReviewClient.parseReviewOutput(raw)
            assertTrue("'$raw' must fail closed", verdict.dangerous)
        }
    }

    @Test
    fun `request body is an openai-compatible chat completion`() {
        val body = JSONObject(ScreenReviewClient.buildRequestBody("reviewer-model", "{}"))
        assertEquals("reviewer-model", body.getString("model"))
        val messages = body.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals(ScreenReviewClient.SYSTEM_PROMPT, messages.getJSONObject(0).getString("content"))
        assertEquals(100, body.getInt("max_tokens"))
    }
}

class ScreenAuditRingTest {

    private fun entry(ts: Long, action: String = "tap") = ScreenAuditEntry(ts, action, "reason", "com.example", "OK", null)

    @Test
    fun `newest first and capped at two hundred`() {
        val filled = (0 until 250).fold(emptyList<ScreenAuditEntry>()) { acc, index ->
            ScreenAuditLog.appendCapped(acc, entry(index.toLong()))
        }
        assertEquals(ScreenAuditLog.MAX_ENTRIES, filled.size)
        assertEquals(249L, filled.first().timestamp)
        assertEquals(50L, filled.last().timestamp)
    }

    @Test
    fun `entry json round trips with nullable fields`() {
        val withNulls = ScreenAuditEntry(1L, "read", "看看", null, "OK", null)
        val back = ScreenAuditEntry.fromJson(JSONObject(withNulls.toJson().toString()))
        assertEquals(withNulls, back)

        val withMessage = ScreenAuditEntry(2L, "tap", "点", "com.example", "RULE_BLOCKED", "锁屏")
        val back2 = ScreenAuditEntry.fromJson(JSONObject(withMessage.toJson().toString()))
        assertEquals(withMessage, back2)
    }
}
