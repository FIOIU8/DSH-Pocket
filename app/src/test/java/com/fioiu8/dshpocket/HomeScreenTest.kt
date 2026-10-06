package com.fioiu8.dshpocket

import com.fioiu8.dshpocket.ui.formatDuration
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeScreenTest {

    @Test
    fun formatDuration_handlesMinutesAndHours() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("1:05", formatDuration(65_000))
        assertEquals("1:01:05", formatDuration(3_665_000))
    }

    @Test
    fun formatDuration_clampsNegativeValues() {
        assertEquals("0:00", formatDuration(-1))
    }
}
