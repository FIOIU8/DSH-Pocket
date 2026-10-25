package com.fioiu8.dshpocket

import com.fioiu8.dshpocket.screen.PocketBridgeProtocol
import com.fioiu8.dshpocket.screen.ScreenAction
import com.fioiu8.dshpocket.screen.ScreenOpCodec
import com.fioiu8.dshpocket.screen.ScreenOpRequest
import com.fioiu8.dshpocket.screen.ScreenOpResult
import com.fioiu8.dshpocket.screen.ScreenReviewClient
import java.io.ByteArrayInputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wire rules of the loopback bridge, locked without any sockets. */
class PocketBridgeProtocolTest {

    private fun head(vararg lines: String): ByteArrayInputStream {
        val text = lines.joinToString("\r\n") + "\r\n\r\n"
        return ByteArrayInputStream(text.toByteArray(Charsets.ISO_8859_1))
    }

    @Test
    fun `readHead parses request line and lowercases header names`() {
        val head = PocketBridgeProtocol.readHead(
            head("POST /screen HTTP/1.1", "Host: 127.0.0.1:3091", "Content-Length: 5", "Authorization: Bearer abc"),
        )!!
        assertEquals("POST /screen HTTP/1.1", head.requestLine)
        assertEquals("5", head.headers["content-length"])
        assertEquals("Bearer abc", head.headers["authorization"])
    }

    @Test
    fun `readHead fails on EOF before terminator and on oversize`() {
        val unterminated = ByteArrayInputStream("POST /screen HTTP/1.1\r\n".toByteArray())
        assertNull(PocketBridgeProtocol.readHead(unterminated))
        val oversized = "a".repeat(PocketBridgeProtocol.MAX_HEAD_BYTES + 1) + "\r\n\r\n"
        assertNull(PocketBridgeProtocol.readHead(ByteArrayInputStream(oversized.toByteArray(Charsets.ISO_8859_1))))
    }

    @Test
    fun `readBody reads exactly content-length and rejects oversize`() {
        val payload = "{\"a\":1}".toByteArray(Charsets.UTF_8)
        assertEquals(payload.size, PocketBridgeProtocol.readBody(ByteArrayInputStream(payload), payload.size)!!.size)
        assertNull(PocketBridgeProtocol.readBody(ByteArrayInputStream(payload), PocketBridgeProtocol.MAX_BODY_BYTES + 1))
        assertNull(PocketBridgeProtocol.readBody(ByteArrayInputStream(byteArrayOf()), 10))
    }

    @Test
    fun `reply carries status line content-length and utf-8 body`() {
        val bytes = PocketBridgeProtocol.reply(403, "{\"ok\":false}")
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("HTTP/1.1 403 Forbidden\r\n"))
        assertTrue(text.contains("Content-Type: application/json"))
        assertTrue(text.contains("Connection: close"))
        // The JSON body is pure ASCII here; the byte length must match the header.
        val body = String(bytes, Charsets.UTF_8).substringAfter("\r\n\r\n")
        assertEquals(body.toByteArray(Charsets.UTF_8).size, text.substringAfter("Content-Length: ").substringBefore("\r\n").toInt())
    }

    @Test
    fun `reply handles multibyte utf-8 bodies with byte-accurate content-length`() {
        val bytes = PocketBridgeProtocol.reply(200, "{\"message\":\"操作被拒绝\"}")
        val body = String(bytes, Charsets.UTF_8).substringAfter("\r\n\r\n")
        val declared = String(bytes, Charsets.ISO_8859_1).substringAfter("Content-Length: ").substringBefore("\r\n").toInt()
        assertEquals(body.toByteArray(Charsets.UTF_8).size, declared)
    }
}

class ScreenOpCodecTest {

    @Test
    fun `parses a full tap request including meta session`() {
        val request = ScreenOpCodec.parseRequest(
            """{"action":"tap","reason":"点击搜索框","x":540,"y":1200,"meta":{"sessionId":"s-1"}}""",
        )!!
        assertEquals(ScreenAction.TAP, request.action)
        assertEquals("点击搜索框", request.reason)
        assertEquals(540, request.x)
        assertEquals(1200, request.y)
        assertEquals("s-1", request.sessionId)
    }

    @Test
    fun `accepts numeric strings for coordinates`() {
        val request = ScreenOpCodec.parseRequest("""{"action":"swipe","reason":"r","x":"10","y":"20","x2":"30","y2":"40"}""")!!
        assertEquals(10, request.x)
        assertEquals(40, request.y2)
    }

    @Test
    fun `rejects unknown action missing reason bad key and garbage`() {
        assertNull(ScreenOpCodec.parseRequest("""{"action":"fly","reason":"r"}"""))
        assertNull(ScreenOpCodec.parseRequest("""{"action":"tap","reason":"  "}"""))
        assertNull(ScreenOpCodec.parseRequest("""{"action":"key","reason":"r","key":"menu"}"""))
        assertNull(ScreenOpCodec.parseRequest("not json at all"))
        assertNull(ScreenOpCodec.parseRequest("""{"action":"tap","reason":"${"x".repeat(201)}"}"""))
    }

    @Test
    fun `parses a permission request and rejects an unknown permission`() {
        val request = ScreenOpCodec.parseRequest(
            """{"action":"request_permission","reason":"需要操作屏幕","permission":"accessibility"}""",
        )!!
        assertEquals(ScreenAction.REQUEST_PERMISSION, request.action)
        assertEquals(com.fioiu8.dshpocket.screen.ScreenPermission.ACCESSIBILITY, request.permission)
        assertNull(ScreenOpCodec.parseRequest("""{"action":"request_permission","reason":"r","permission":"root"}"""))
    }

    @Test
    fun `result json is exactly the tool contract`() {
        val ok = ScreenOpResult.ok("performed", true).toJson()
        assertTrue(ok.getBoolean("ok"))
        assertTrue(ok.getJSONObject("data").getBoolean("performed"))

        val failure = ScreenOpResult.Failure("RULE_BLOCKED", "原因").toJson()
        assertFalse(failure.getBoolean("ok"))
        assertEquals("RULE_BLOCKED", failure.getString("code"))
        assertEquals("原因", failure.getString("message"))
    }

    @Test
    fun `review user payload stays under the promised size and caps target text`() {
        val request = ScreenOpRequest(ScreenAction.TAP, "点击搜索框", x = 1, y = 2)
        val payload = ScreenReviewClient.buildUserPayload(request, "com.example.app", "x".repeat(1_000))
        val parsed = JSONObject(payload)
        assertEquals("tap", parsed.getString("action"))
        assertEquals("com.example.app", parsed.getString("foregroundPackage"))
        assertEquals(ScreenReviewClient.MAX_TARGET_SUMMARY, parsed.getString("targetText").length)
        assertTrue(payload.length < 800)
    }
}
