package dev.soupslurpr.beautyxt.printing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Verifies that grayscale output retains luminance rather than only glyph opacity. */
class PrintGrayscaleTest {
    @Test
    fun preservesBlackWhiteAndEveryNeutralTone() {
        val colors = IntArray(256) { level -> 0xff000000.toInt() or (level * 0x010101) }
        val grayscale = ByteArray(colors.size)
        printGrayscaleRow(colors, grayscale, colors.size)
        assertArrayEquals(ByteArray(256) { level -> level.toByte() }, grayscale)
    }

    @Test
    fun preservesDifferentShadesInOpaqueColorGlyphs() {
        val colors = intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt())
        val grayscale = ByteArray(colors.size)
        printGrayscaleRow(colors, grayscale, colors.size)
        assertArrayEquals(byteArrayOf(77, 149.toByte(), 29), grayscale)
    }

    @Test
    fun convertsOnlyTheRequestedRowWidth() {
        val grayscale = byteArrayOf(42, 42, 42)
        printGrayscaleRow(intArrayOf(-1, -1, -1), grayscale, width = 2)
        assertArrayEquals(byteArrayOf(-1, -1, 42), grayscale)
    }

    @Test
    fun rejectsOutOfBoundsRows() {
        assertThrows(IllegalArgumentException::class.java) {
            printGrayscaleRow(IntArray(1), ByteArray(1), width = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            printGrayscaleRow(IntArray(1), ByteArray(2), width = 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            printGrayscaleRow(IntArray(2), ByteArray(1), width = 2)
        }
    }
}
