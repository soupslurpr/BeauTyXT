package dev.soupslurpr.beautyxt.transfer.client

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rejects malformed worker grids before they reach Compose's QR geometry. */
class QrCodeGridTest {
    @Test
    fun acceptsEveryStandardDimension() {
        for (version in 1..40) {
            val dimension = 17 + 4 * version
            assertEquals(
                dimension,
                QrCodeGrid.decode(packet(dimension), dimension.toLong()).dimension
            )
        }
    }

    @Test
    fun rejectsDimensionsBetweenStandardVersions() {
        for (dimension in listOf(22, 23, 24, 176)) {
            val failure = assertThrows(TransferException::class.java) {
                QrCodeGrid.decode(packet(dimension), dimension.toLong())
            }
            assertEquals(TransferFailure.InvalidResponse, failure.failure)
        }
    }

    @Test
    fun rejectsExtremeDimensionBeforeArithmetic() {
        val failure = assertThrows(TransferException::class.java) {
            QrCodeGrid.decode(packet(65_535, packedBytes = 0), 65_535)
        }
        assertEquals(TransferFailure.InvalidResponse, failure.failure)
    }

    @Test
    fun ownsItsModulesAndRejectsNonzeroPadding() {
        val bytes = packet(21)
        bytes[11] = 0x80.toByte()
        val grid = QrCodeGrid.decode(bytes, 21)
        bytes.fill(0)
        assertTrue(grid.isDark(0, 0))
        val malformed = packet(21)
        malformed[malformed.lastIndex] = 1
        assertThrows(TransferException::class.java) {
            QrCodeGrid.decode(malformed, 21)
        }
    }

    private fun packet(
        dimension: Int,
        packedBytes: Int = (dimension * dimension + 7) / 8
    ): ByteArray = ByteBuffer.allocate(11 + packedBytes).order(ByteOrder.BIG_ENDIAN)
        .put(byteArrayOf(0x42, 0x58, 0x51, 0x52))
        .put(1.toByte())
        .putShort(dimension.toShort())
        .putInt(packedBytes)
        .array()
}
