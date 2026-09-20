/* Checks generated-output limits without Android or provider dependencies. */
package dev.soupslurpr.beautyxt.ipc

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies exact output budgets, failure ordering, and stream ownership. */
class BoundedOutputStreamTest {
    @Test
    fun singleBytesAndSlicesShareOneExactBudget() {
        val destination = ByteArrayOutputStream()
        val output = BoundedOutputStream(destination, 3L)
        output.write(1)
        output.write(byteArrayOf(9, 2, 3, 9), 1, 2)
        output.write(byteArrayOf())
        assertEquals(3L, output.byteCount)
        assertArrayEquals(byteArrayOf(1, 2, 3), destination.toByteArray())
        assertThrows(IOException::class.java) { output.write(4) }
        assertEquals(3L, output.byteCount)
    }

    @Test
    fun rejectsWholeOversizedSliceBeforeWritingAnyPrefix() {
        val destination = ByteArrayOutputStream()
        val output = BoundedOutputStream(destination, 2L)
        output.write(1)
        assertThrows(IOException::class.java) { output.write(byteArrayOf(2, 3)) }
        assertArrayEquals(byteArrayOf(1), destination.toByteArray())
        assertEquals(1L, output.byteCount)
        output.write(2)
        assertEquals(2L, output.byteCount)
    }

    @Test
    fun zeroBudgetAcceptsOnlyEmptyWrites() {
        val output = BoundedOutputStream(ByteArrayOutputStream(), 0L)
        output.write(byteArrayOf())
        assertThrows(IOException::class.java) { output.write(0) }
        assertEquals(0L, output.byteCount)
    }

    @Test
    fun invalidSlicesAndLimitsFailWithoutWriting() {
        val destination = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) {
            BoundedOutputStream(destination, -1L)
        }
        val output = BoundedOutputStream(destination, Long.MAX_VALUE)
        assertThrows(IndexOutOfBoundsException::class.java) {
            output.write(byteArrayOf(1), Int.MAX_VALUE, 1)
        }
        assertThrows(IndexOutOfBoundsException::class.java) {
            output.write(byteArrayOf(1), 0, -1)
        }
        assertEquals(0L, output.byteCount)
        assertEquals(0, destination.size())
    }

    @Test
    fun failedWriteDoesNotReportUnwrittenBytes() {
        val destination = object : OutputStream() {
            override fun write(value: Int) = throw IOException("synthetic write failure")
        }
        val output = BoundedOutputStream(destination, 1L)
        assertThrows(IOException::class.java) { output.write(1) }
        assertEquals(0L, output.byteCount)
    }

    @Test
    fun flushAndCloseReachTheOwnedStream() {
        var flushed = false
        var closed = false
        val destination = object : ByteArrayOutputStream() {
            override fun flush() {
                flushed = true
            }

            override fun close() {
                closed = true
            }
        }
        BoundedOutputStream(destination, 0L).use { output -> output.flush() }
        assertTrue(flushed)
        assertTrue(closed)
    }
}
