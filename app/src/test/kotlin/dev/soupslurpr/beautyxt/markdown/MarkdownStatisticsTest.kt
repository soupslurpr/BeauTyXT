package dev.soupslurpr.beautyxt.markdown

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TEST_INPUT_BYTES = 1_024L
private const val TEST_PACKET_BYTES = 2_048L

/** Verifies native Markdown statistics before isolated callback publication. */
class MarkdownStatisticsTest {
    /** Accepts one bounded successful render with canonical metrics. */
    @Test
    fun acceptsCanonicalSuccess() {
        assertTrue(
            canonicalStatistics(
                resultCode = MarkdownProtocol.RESULT_SUCCESS,
                inputBytes = TEST_INPUT_BYTES,
                packetBytes = TEST_PACKET_BYTES,
                blockCount = 4L,
                spanCount = 3L,
                documentFlags = MarkdownProtocol.DOCUMENT_FLAG_RAW_HTML.toLong()
            )
        )
    }

    /** Rejects successful byte counts outside the accepted request. */
    @Test
    fun rejectsInconsistentSuccess() {
        assertFalse(
            canonicalStatistics(
                resultCode = MarkdownProtocol.RESULT_SUCCESS,
                inputBytes = TEST_INPUT_BYTES - 1L,
                packetBytes = TEST_PACKET_BYTES,
                blockCount = 1L,
                spanCount = 0L,
                documentFlags = 0L
            )
        )
    }

    /** Rejects partial counters and unknown flags on terminal failures. */
    @Test
    fun rejectsNoncanonicalFailure() {
        assertFalse(
            canonicalStatistics(
                resultCode = MarkdownProtocol.RESULT_RENDER_LIMIT,
                inputBytes = TEST_INPUT_BYTES,
                packetBytes = 0L,
                blockCount = 0L,
                spanCount = 0L,
                documentFlags = 0L
            )
        )
        assertFalse(
            canonicalStatistics(
                resultCode = MarkdownProtocol.RESULT_SUCCESS,
                inputBytes = TEST_INPUT_BYTES,
                packetBytes = TEST_PACKET_BYTES,
                blockCount = 1L,
                spanCount = 0L,
                documentFlags = 1L shl 32
            )
        )
    }

    /** Calls validation with fixed request bounds. */
    private fun canonicalStatistics(
        resultCode: Int,
        inputBytes: Long,
        packetBytes: Long,
        blockCount: Long,
        spanCount: Long,
        documentFlags: Long
    ): Boolean = areValidMarkdownStatistics(
        resultCode = resultCode,
        expectedInputBytes = TEST_INPUT_BYTES,
        maxPacketBytes = MarkdownProtocol.MAX_PACKET_BYTES,
        inputBytes = inputBytes,
        packetBytes = packetBytes,
        blockCount = blockCount,
        spanCount = spanCount,
        documentFlags = documentFlags
    )
}
