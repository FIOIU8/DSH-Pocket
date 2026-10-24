package com.fioiu8.dshpocket

import com.fioiu8.dshpocket.engine.CountingInputStream
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The runtime extraction reports progress as bytes-read / total-bytes; the counting
 * stream feeding that fraction must count every kind of read the zip loop makes and
 * never count end-of-stream as data.
 */
class CountingInputStreamTest {

    private val data = ByteArray(1000) { (it % 251).toByte() }

    @Test
    fun `counts single byte reads`() {
        val counter = CountingInputStream(ByteArrayInputStream(data))
        var reads = 0
        while (counter.read() != -1) reads++
        assertEquals(data.size.toLong(), counter.count)
        assertEquals(data.size, reads)
    }

    @Test
    fun `counts array reads`() {
        val counter = CountingInputStream(ByteArrayInputStream(data))
        val buffer = ByteArray(256)
        var total = 0
        while (true) {
            val n = counter.read(buffer, 0, buffer.size)
            if (n == -1) break
            total += n
        }
        assertEquals(data.size.toLong(), counter.count)
        assertEquals(data.size, total)
    }

    @Test
    fun `does not count end of stream`() {
        val counter = CountingInputStream(ByteArrayInputStream(data))
        counter.read(ByteArray(data.size * 2), 0, data.size * 2)
        assertEquals(-1, counter.read())
        assertEquals(-1, counter.read(ByteArray(16), 0, 16))
        assertEquals(data.size.toLong(), counter.count)
    }

    @Test
    fun `counts skipped bytes`() {
        val counter = CountingInputStream(ByteArrayInputStream(data))
        assertEquals(400L, counter.skip(400))
        counter.read()
        assertEquals(401L, counter.count)
    }
}
