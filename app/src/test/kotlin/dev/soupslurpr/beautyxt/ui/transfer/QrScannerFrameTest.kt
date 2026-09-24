package dev.soupslurpr.beautyxt.ui.transfer

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies bounded CameraX luminance copying independently from camera hardware. */
class QrScannerFrameTest {
    /** Accepts the larger scan frame and bounded fallbacks in either orientation. */
    @Test
    fun acceptsBoundedAnalysisResolutions() {
        assertTrue(isSupportedQrAnalysisSize(1280, 960))
        assertTrue(isSupportedQrAnalysisSize(960, 1280))
        assertTrue(isSupportedQrAnalysisSize(1280, 720))
        assertTrue(isSupportedQrAnalysisSize(640, 480))
    }

    /** Filters unsupported camera sizes before they can starve the analyzer. */
    @Test
    fun rejectsAnalysisResolutionsOutsideDecoderBounds() {
        assertFalse(isSupportedQrAnalysisSize(1920, 1080))
        assertFalse(isSupportedQrAnalysisSize(1280, 961))
        assertFalse(isSupportedQrAnalysisSize(47, 640))
        assertFalse(isSupportedQrAnalysisSize(640, 0))
        assertFalse(isSupportedQrAnalysisSize(Int.MAX_VALUE, Int.MAX_VALUE))
    }

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
