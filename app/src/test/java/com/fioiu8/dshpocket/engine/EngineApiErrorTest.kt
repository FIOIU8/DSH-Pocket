package com.fioiu8.dshpocket.engine

import com.fioiu8.dshpocket.engine.EngineApi.RawResponse
import com.fioiu8.dshpocket.engine.EngineApi.RpcResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineApiErrorTest {

    @Test
    fun `HTTP failures preserve request id and only retry transient statuses`() {
        val transient = EngineApi.parse(RawResponse(503, "upstream unavailable", "rpc-503")) as RpcResult.Err
        assertEquals("http/503", transient.code)
        assertEquals("rpc-503", transient.requestId)
        assertTrue(transient.retryable)

        val permanent = EngineApi.parse(RawResponse(400, "bad request", "rpc-400")) as RpcResult.Err
        assertEquals("rpc-400", permanent.requestId)
        assertFalse(permanent.retryable)
    }

    @Test
    fun `RPC errors expose readable fields and redact credential-shaped details`() {
        val body = """{"result":{"ok":false,"error":{"code":"timeout","message":"Request timed out","requestId":"engine-17","details":{"api_key":"top-secret","cookie":"sid=private"},"reason":"provider timeout"}}}"""
        val error = EngineApi.parse(RawResponse(200, body, "rpc-local")) as RpcResult.Err

        assertEquals("timeout", error.code)
        assertEquals("Request timed out", error.message)
        assertEquals("engine-17", error.requestId)
        assertTrue(error.retryable)
        assertTrue(error.details.contains("provider timeout"))
        assertTrue(error.details.contains("[redacted]"))
        assertFalse(error.details.contains("top-secret"))
        assertFalse(error.details.contains("sid=private"))
    }

    @Test
    fun `non JSON responses remain protocol errors with request id`() {
        val error = EngineApi.parse(RawResponse(200, "<html>bad</html>", "rpc-html")) as RpcResult.Err
        assertEquals("protocol", error.code)
        assertEquals("rpc-html", error.requestId)
        assertFalse(error.retryable)
    }

    @Test
    fun `diagnostic redaction removes authorization and cookie values`() {
        val safe = EngineApi.safeDiagnostic("Authorization: Bearer abc123\nCookie: sid=private\naccess_token=secret")
        assertFalse(safe.contains("abc123"))
        assertFalse(safe.contains("sid=private"))
        assertFalse(safe.contains("secret"))
    }

    @Test
    fun `diagnostic redaction handles JSON authorization fields`() {
        val safe = EngineApi.safeDiagnostic("""{"authorization":"Bearer json-secret","api_key":"key-secret"}""")
        assertFalse(safe.contains("json-secret"))
        assertFalse(safe.contains("key-secret"))
        assertTrue(safe.contains("[redacted]"))
    }
}
