package dev.soupslurpr.beautyxt.ui.transfer

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies bounded CameraX luminance copying independently from camera hardware. */
class QrScannerFrameTest {
    /** Verifies row padding is omitted without moving the provider buffer position. */
    @Test
    fun copiesPaddedLuminanceRows() {
        val source =
            ByteBuffer.wrap(
                byteArrayOf(
                    99,
                    1,
                    2,
                    3,
                    88,
                    88,
                    4,
                    5,
                    6
                )
            )
        source.position(1)
        val destination = ByteArray(6)

        assertTrue(
            copyLuminancePlane(
                source = source,
                width = 3,
                height = 2,
                rowStride = 5,
                pixelStride = 1,
                destination = destination
            )
        )

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), destination)
        assertEquals(1, source.position())
    }

    /** Verifies interleaved luminance samples are copied into a dense frame. */
    @Test
    fun copiesInterleavedLuminanceSamples() {
        val source = ByteBuffer.wrap(byteArrayOf(1, 99, 2, 99, 3, 88, 4, 99, 5, 99, 6))
        val destination = ByteArray(6)

        assertTrue(
            copyLuminancePlane(
                source = source,
                width = 3,
                height = 2,
                rowStride = 6,
                pixelStride = 2,
                destination = destination
            )
        )

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), destination)
    }

    /** Verifies truncated provider storage is rejected before any out-of-bounds read. */
    @Test
    fun rejectsTruncatedLuminanceStorage() {
        val source = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5))

        assertFalse(
            copyLuminancePlane(
                source = source,
                width = 3,
                height = 2,
                rowStride = 3,
                pixelStride = 1,
                destination = ByteArray(6)
            )
        )
    }
}
