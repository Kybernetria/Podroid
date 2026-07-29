package com.excp.podroid.engine

import java.io.OutputStream

/** Per-VM-run disk sink that stops writing at a fixed cap without blocking live consumers. */
internal class BoundedRunLog(
    private val delegate: OutputStream,
    private val maxBytes: Long = MAX_CONSOLE_LOG_BYTES,
) : OutputStream() {
    private var writtenBytes = 0L

    init { require(maxBytes in 1..MAX_CONSOLE_LOG_BYTES) }

    override fun write(value: Int) {
        if (writtenBytes >= maxBytes) return
        delegate.write(value)
        writtenBytes++
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
        val accepted = minOf(length.toLong(), maxBytes - writtenBytes).toInt()
        if (accepted <= 0) return
        delegate.write(bytes, offset, accepted)
        writtenBytes += accepted
    }

    override fun flush() = delegate.flush()
    override fun close() = delegate.close()

    companion object {
        const val MAX_CONSOLE_LOG_BYTES = 1024L * 1024L
    }
}
