package dev.soupslurpr.beautyxt.importing.client

import dev.soupslurpr.beautyxt.document.SourceVersion
import dev.soupslurpr.beautyxt.importing.ImportProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val TEST_BYTE_LIMIT = 1024L
private val TEST_SHA256 =
    ByteArray(ImportProtocol.RESULT_SHA_256_BYTE_COUNT) { index -> index.toByte() }

/** Verifies sanitized validation of isolated import terminal states. */
class DocumentImportResultTest {
    /** Accepts only canonical bounded success. */
    @Test
    fun acceptsCanonicalSuccess() {
        val callbackSha256 = TEST_SHA256.copyOf()
        val status =
            ImportTerminalStatus(
                state = ImportProtocol.STATE_COMPLETE,
                resultCode = ImportProtocol.RESULT_SUCCESS,
                inputBytes = 12,
                outputBytes = 12,
                sourceFlags = ImportProtocol.SOURCE_FLAG_CRLF,
                sourceSha256 = callbackSha256
            )
        callbackSha256.fill(0)

        assertNull(status.failureOrNull(TEST_BYTE_LIMIT, TEST_BYTE_LIMIT))
        assertEquals(
            SourceVersion.from(byteLength = 12L, sha256 = TEST_SHA256),
            status.sourceVersion()
        )
    }

    /** Maps content and size failures without exposing provider details. */
    @Test
    fun mapsKnownFailures() {
        assertEquals(
            DocumentImportFailure.INVALID_UTF8,
            failedStatus(ImportProtocol.RESULT_INVALID_UTF8).failureOrNull(
                TEST_BYTE_LIMIT,
                TEST_BYTE_LIMIT
            )
        )
        assertEquals(
            DocumentImportFailure.UNSUPPORTED_ENCODING,
            failedStatus(ImportProtocol.RESULT_UNSUPPORTED_BOM).failureOrNull(
                TEST_BYTE_LIMIT,
                TEST_BYTE_LIMIT
            )
        )
        assertEquals(
            DocumentImportFailure.TOO_LARGE,
            failedStatus(ImportProtocol.RESULT_INPUT_LIMIT).failureOrNull(
                TEST_BYTE_LIMIT,
                TEST_BYTE_LIMIT
            )
        )
    }

    /** Rejects conflicting lifecycle state, flags, and success counters. */
    @Test
    fun rejectsNoncanonicalResponses() {
        val wrongState =
            ImportTerminalStatus(
                state = ImportProtocol.STATE_FAILED,
                resultCode = ImportProtocol.RESULT_SUCCESS,
                inputBytes = 1,
                outputBytes = 1,
                sourceFlags = 0,
                sourceSha256 = TEST_SHA256
            )
        val unknownFlags =
            ImportTerminalStatus(
                state = ImportProtocol.STATE_COMPLETE,
                resultCode = ImportProtocol.RESULT_SUCCESS,
                inputBytes = 1,
                outputBytes = 1,
                sourceFlags = 1 shl 8,
                sourceSha256 = TEST_SHA256
            )
        val oversizedSuccess =
            ImportTerminalStatus(
                state = ImportProtocol.STATE_COMPLETE,
                resultCode = ImportProtocol.RESULT_SUCCESS,
                inputBytes = TEST_BYTE_LIMIT + 1,
                outputBytes = TEST_BYTE_LIMIT + 1,
                sourceFlags = 0,
                sourceSha256 = TEST_SHA256
            )
        val changedOutput =
            ImportTerminalStatus(
                state = ImportProtocol.STATE_COMPLETE,
                resultCode = ImportProtocol.RESULT_SUCCESS,
                inputBytes = 2,
                outputBytes = 1,
                sourceFlags = 0,
                sourceSha256 = TEST_SHA256
            )
        val failedWithStatistics =
            ImportTerminalStatus(
                state = ImportProtocol.STATE_FAILED,
                resultCode = ImportProtocol.RESULT_INVALID_UTF8,
                inputBytes = 1,
                outputBytes = 0,
                sourceFlags = 0,
                sourceSha256 = ByteArray(ImportProtocol.RESULT_SHA_256_BYTE_COUNT)
            )
        val successWithShortDigest =
            ImportTerminalStatus(
                state = ImportProtocol.STATE_COMPLETE,
                resultCode = ImportProtocol.RESULT_SUCCESS,
                inputBytes = 1,
                outputBytes = 1,
                sourceFlags = 0,
                sourceSha256 = ByteArray(ImportProtocol.RESULT_SHA_256_BYTE_COUNT - 1)
            )
        val failureWithDigest =
            ImportTerminalStatus(
                state = ImportProtocol.STATE_FAILED,
                resultCode = ImportProtocol.RESULT_INVALID_UTF8,
                inputBytes = 0,
                outputBytes = 0,
                sourceFlags = 0,
                sourceSha256 = TEST_SHA256
            )

        listOf(
            wrongState,
            unknownFlags,
            oversizedSuccess,
            changedOutput,
            failedWithStatistics,
            successWithShortDigest,
            failureWithDigest
        ).forEach { status ->
            assertEquals(
                DocumentImportFailure.INVALID_RESPONSE,
                status.failureOrNull(TEST_BYTE_LIMIT, TEST_BYTE_LIMIT)
            )
        }
    }

    /** Creates one canonical failed terminal status. */
    private fun failedStatus(resultCode: Int): ImportTerminalStatus = ImportTerminalStatus(
        state = ImportProtocol.STATE_FAILED,
        resultCode = resultCode,
        inputBytes = 0,
        outputBytes = 0,
        sourceFlags = 0,
        sourceSha256 = ByteArray(ImportProtocol.RESULT_SHA_256_BYTE_COUNT)
    )
}
