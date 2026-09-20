package dev.soupslurpr.beautyxt.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Verifies reader preconditions without consuming any packet content. */
class DocumentPacketReaderTest {
    /** Rejects negative lengths before narrowing or advancing the UTF-8 cursor. */
    @Test
    fun rejectsNegativeUtf8Lengths() {
        val reader = DocumentPacketReader("😀".encodeToByteArray(), ::IllegalArgumentException)
        for (length in longArrayOf(-1L, -(1L shl Int.SIZE_BITS), Long.MIN_VALUE)) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                reader.readUtf8(length, "text")
            }
            assertEquals("byte length must be nonnegative", error.message)
            assertEquals(4, reader.remainingBytes)
        }
        assertEquals("😀", reader.readUtf8(4, "text"))
        assertEquals(0, reader.remainingBytes)
    }

    /** Rejects negative reserved-region sizes instead of silently skipping them. */
    @Test
    fun rejectsNegativeReservedByteCounts() {
        val reader = DocumentPacketReader(byteArrayOf(0), ::IllegalArgumentException)
        val error = assertThrows(IllegalArgumentException::class.java) {
            reader.requireZeroBytes(-1, "reserved bytes")
        }
        assertEquals("byte count must be nonnegative", error.message)
        assertEquals(1, reader.remainingBytes)
        reader.requireZeroBytes(0, "reserved bytes")
        reader.requireZeroBytes(1, "reserved bytes")
        assertEquals(0, reader.remainingBytes)
    }
}
