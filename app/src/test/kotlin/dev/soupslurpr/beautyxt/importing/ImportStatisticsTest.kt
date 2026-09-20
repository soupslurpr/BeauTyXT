package dev.soupslurpr.beautyxt.importing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TEST_INPUT_BYTES = 12L
private const val TEST_PARTIAL_OUTPUT_BYTES = 10L
private val TEST_SHA256 = ByteArray(ImportProtocol.RESULT_SHA_256_BYTE_COUNT) { index ->
    index.toByte()
}

/** Verifies native import statistics before the service publishes them. */
class ImportStatisticsTest {
    /** Accepts byte-identical successful output. */
    @Test
    fun acceptsByteIdenticalSuccess() {
        assertTrue(
            areValidImportStatistics(
                resultCode = ImportProtocol.RESULT_SUCCESS,
                inputBytes = TEST_INPUT_BYTES,
                outputBytes = TEST_INPUT_BYTES,
                sourceFlagsValue = ImportProtocol.SOURCE_FLAG_CRLF.toLong(),
                sourceSha256 = TEST_SHA256
            )
        )
    }

    /** Rejects a successful result that changed the source byte count. */
    @Test
    fun rejectsChangedSuccessfulOutput() {
        assertFalse(
            areValidImportStatistics(
                resultCode = ImportProtocol.RESULT_SUCCESS,
                inputBytes = TEST_INPUT_BYTES,
                outputBytes = TEST_PARTIAL_OUTPUT_BYTES,
                sourceFlagsValue = 0L,
                sourceSha256 = TEST_SHA256
            )
        )
    }

    /** Rejects partial counters for a failed bounded copy. */
    @Test
    fun rejectsPartialFailedOutput() {
        assertFalse(
            areValidImportStatistics(
                resultCode = ImportProtocol.RESULT_INVALID_UTF8,
                inputBytes = TEST_INPUT_BYTES,
                outputBytes = TEST_PARTIAL_OUTPUT_BYTES,
                sourceFlagsValue = 0L,
                sourceSha256 = ByteArray(ImportProtocol.RESULT_SHA_256_BYTE_COUNT)
            )
        )
    }

    /** Rejects flags outside the stable import protocol. */
    @Test
    fun rejectsUnknownSourceFlags() {
        assertFalse(
            areValidImportStatistics(
                resultCode = ImportProtocol.RESULT_SUCCESS,
                inputBytes = TEST_INPUT_BYTES,
                outputBytes = TEST_INPUT_BYTES,
                sourceFlagsValue = 1L shl 32,
                sourceSha256 = TEST_SHA256
            )
        )
    }

    /** Rejects malformed digest widths and digest bytes on failures. */
    @Test
    fun rejectsNoncanonicalSha256() {
        assertFalse(
            areValidImportStatistics(
                resultCode = ImportProtocol.RESULT_SUCCESS,
                inputBytes = TEST_INPUT_BYTES,
                outputBytes = TEST_INPUT_BYTES,
                sourceFlagsValue = 0L,
                sourceSha256 = ByteArray(ImportProtocol.RESULT_SHA_256_BYTE_COUNT - 1)
            )
        )
        assertFalse(
            areValidImportStatistics(
                resultCode = ImportProtocol.RESULT_INVALID_UTF8,
                inputBytes = 0L,
                outputBytes = 0L,
                sourceFlagsValue = 0L,
                sourceSha256 = TEST_SHA256
            )
        )
    }
}
