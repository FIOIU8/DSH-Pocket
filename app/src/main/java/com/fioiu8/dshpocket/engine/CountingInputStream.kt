package com.fioiu8.dshpocket.engine

import java.io.InputStream

/** Counts bytes consumed by the extractor so progress can be reported as a fraction. */
internal class CountingInputStream(private val upstream: InputStream) : InputStream() {
    var count: Long = 0
        private set

    override fun read(): Int {
        val b = upstream.read()
        if (b >= 0) count++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = upstream.read(b, off, len)
        if (n > 0) count += n
        return n
    }

    override fun skip(n: Long): Long {
        val skipped = upstream.skip(n)
        count += skipped
        return skipped
    }

    override fun available(): Int = upstream.available()

    override fun close() = upstream.close()
}
