package com.fioiu8.dshpocket

import com.fioiu8.dshpocket.engine.TypedEngineApi
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TypedEngineApiTest {
    @Test
    fun `session list accepts bare array and object envelope`() {
        val bare = TypedEngineApi.parseProtocolInfo(JSONObject().put("dshVersion", "0.2.0").put("protocolVersion", 2).put("capabilities", JSONArray().put("session/follow")))
        assertTrue(bare is TypedEngineApi.Result.Ok)
        val info = (bare as TypedEngineApi.Result.Ok).value
        assertEquals("0.2.0", info.dshVersion)
        assertEquals(2, info.protocolVersion)
        assertTrue("session/follow" in info.capabilities)
    }

    @Test
    fun `protocol info tolerates missing optional fields`() {
        val result = TypedEngineApi.parseProtocolInfo(JSONObject())
        assertTrue(result is TypedEngineApi.Result.Ok)
        val info = (result as TypedEngineApi.Result.Ok).value
        assertEquals(null, info.dshVersion)
        assertEquals(null, info.protocolVersion)
        assertTrue(info.capabilities.isEmpty())
    }

    @Test
    fun `session create parser rejects missing id`() {
        val result = TypedEngineApi.parseSessionRef(JSONObject().put("cwd", "/tmp"))
        assertTrue(result is TypedEngineApi.Result.Err)
    }
}
