package dev.soupslurpr.beautyxt.document

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class DocumentSearchPacketTest {
    private fun packet(start: Long = 4, text: String = "😀", flags: Int = 0, replacement: String = ""): ByteArray {
        val buffer = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x52534542).putShort(1).put(0).put(flags.toByte()).putLong(7).putLong(6).putInt(1)
        buffer.putLong(start).putLong(start + text.length)
        for (value in listOf(text, replacement, "before", "after")) {
            val bytes = value.toByteArray()
            buffer.putInt(bytes.size).put(bytes)
        }
        return buffer.array().copyOf(buffer.position())
    }

    @Test fun validatesUnicodeLengthsAndDistinguishesAnEmptyReplacementFromNoPlan() {
        val ordinary = decodeSearchPage(packet())
        assertEquals(Utf16Range(4, 6), ordinary.hits.single().range)
        assertNull(ordinary.hits.single().replacement)
        assertEquals("", decodeSearchPage(packet(flags = 2)).hits.single().replacement)
        assertEquals(Utf16Range(4, 4), decodeSearchPage(packet(text = "")).hits.single().range)
    }

    @Test fun rejectsMalformedOrAmbiguousNativeSearchPackets() {
        fun rejects(bytes: ByteArray) { assertThrows(IllegalArgumentException::class.java) { decodeSearchPage(bytes) } }
        val valid = packet()
        rejects(valid.copyOf(valid.size - 1))
        rejects(valid + byteArrayOf(0))
        rejects(packet(flags = 4))
        rejects(packet(replacement = "unexpected"))
        rejects(valid.copyOf().also { it[6] = 5 })
        rejects(valid.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putLong(36, 5) })
        rejects(valid.copyOf().also { it[48] = 0xff.toByte() })
        val duplicate = (valid + valid.copyOfRange(28, valid.size)).also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(24, 2)
        }
        rejects(duplicate)
        rejects(valid.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(24, 257) })
    }
}
