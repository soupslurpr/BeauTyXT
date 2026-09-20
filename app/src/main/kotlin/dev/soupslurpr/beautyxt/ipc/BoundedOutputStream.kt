/* Bounds generated output before it reaches an owned memory file. */
package dev.soupslurpr.beautyxt.ipc

import java.io.IOException
import java.io.OutputStream
import java.util.Objects

/** Limits output without allocating or retaining a second payload buffer. */
internal class BoundedOutputStream(
    private val output: OutputStream,
    private val maximumBytes: Long
) : OutputStream() {
    var byteCount: Long = 0L
        private set

    init {
        require(maximumBytes >= 0L) { "output limit must be nonnegative" }
    }

    /** Writes one byte only when the remaining output budget permits it. */
    override fun write(value: Int) {
        requireCapacity(1)
        output.write(value)
        byteCount++
    }

    /** Writes one complete slice without partially exceeding the output limit. */
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        Objects.checkFromIndexSize(offset, length, bytes.size)
        requireCapacity(length)
        output.write(bytes, offset, length)
        byteCount += length
    }

    /** Flushes the owned output stream. */
    override fun flush() = output.flush()

    /** Closes the owned output stream. */
    override fun close() = output.close()

    /** Rejects an oversized write before passing bytes to the output. */
    private fun requireCapacity(length: Int) {
        if (length.toLong() > maximumBytes - byteCount) {
            throw IOException("generated output exceeds its byte limit")
        }
    }
}
