package com.fioiu8.dshpocket

import com.fioiu8.dshpocket.ui.UiText
import com.fioiu8.dshpocket.ui.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiTextTest {
    @Test
    fun rawTextRemainsExternalDiagnostic() {
        val value = UiText.Raw("connection refused")
        assertEquals("connection refused", value.value)
        assertEquals("connection refused", value.resolve(nullContext()))
    }

    @Test
    fun resourceTextKeepsResourceIdAndArgumentsUnresolved() {
        val value = text(R.string.engine_status_failed, "diagnostic")
        assertTrue(value is UiText.Resource)
        value as UiText.Resource
        assertEquals(R.string.engine_status_failed, value.id)
        assertEquals(listOf("diagnostic"), value.args)
    }

    private fun nullContext(): android.content.Context = object : android.content.ContextWrapper(null) {}
}
