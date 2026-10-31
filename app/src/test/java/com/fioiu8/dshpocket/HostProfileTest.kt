package com.fioiu8.dshpocket

import com.fioiu8.dshpocket.data.remote.HostAuthMethod
import com.fioiu8.dshpocket.data.remote.HostProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostProfileTest {
    @Test
    fun `host profile round trips without secret material`() {
        val profile = HostProfile("id", "Lab", "host.example", 2222, "dev", HostAuthMethod.PrivateKey, "key-alias", "SHA256:abc", 41000, 123L)
        val restored = HostProfile.fromJson(profile.toJson())
        assertEquals(profile, restored)
        assertTrue(profile.toJson().toString().contains("key-alias"))
        assertTrue(!profile.toJson().toString().contains("privateKey"))
    }

    @Test
    fun `host profile rejects invalid required fields`() {
        val json = JSONObject().put("id", "id").put("name", "host").put("address", "host").put("username", "u").put("authMethod", "Password")
        assertNull(HostProfile.fromJson(json))
    }
}
