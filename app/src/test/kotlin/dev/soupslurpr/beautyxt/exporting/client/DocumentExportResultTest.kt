package dev.soupslurpr.beautyxt.exporting.client

import dev.soupslurpr.beautyxt.document.SHA_256_BYTE_COUNT
import dev.soupslurpr.beautyxt.document.SourceVersion
import dev.soupslurpr.beautyxt.exporting.ExportProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TEST_ZERO_BYTES = 0L
private const val TEST_EXPECTED_BYTES = 12L
private const val TEST_UNKNOWN_RESULT = Int.MAX_VALUE
private val TEST_SOURCE_SHA256 =
    ByteArray(SHA_256_BYTE_COUNT) { byteIndex -> byteIndex.toByte() }

/** Defines one canonical terminal failure mapping. */
private data class FailureMapping(
    val resultCode: Int,
    val state: Int,
    val expectedFailure: DocumentExportFailure
)

/** Verifies sanitized validation of isolated export terminal states. */
class DocumentExportResultTest {
    /** Accepts only positive export job identifiers. */
    @Test
    fun validatesPositiveJobIdentifiers() {
        assertFalse(ExportProtocol.isValidJobId(Long.MIN_VALUE))
        assertFalse(ExportProtocol.isValidJobId(-1L))
        assertFalse(ExportProtocol.isValidJobId(0L))
        assertTrue(ExportProtocol.isValidJobId(ExportProtocol.MIN_JOB_ID))
        assertTrue(ExportProtocol.isValidJobId(Long.MAX_VALUE))
    }

    /** Accepts an exact canonical empty export. */
    @Test
    fun acceptsExactZeroLengthSuccess() {
        val status =
            successfulStatus(
                inputBytes = TEST_ZERO_BYTES,
                outputBytes = TEST_ZERO_BYTES
            )

        assertNull(status.failureOrNull(TEST_ZERO_BYTES))
    }

    /** Accepts an exact canonical nonempty export. */
    @Test
    fun acceptsExactNonzeroSuccess() {
        val status =
            successfulStatus(
                inputBytes = TEST_EXPECTED_BYTES,
                outputBytes = TEST_EXPECTED_BYTES
            )

        assertNull(status.failureOrNull(TEST_EXPECTED_BYTES))
    }

    /** Rejects each disagreement between success counts and the expected length. */
    @Test
    fun rejectsSuccessCountMismatches() {
        val mismatchedStatuses =
            listOf(
                successfulStatus(
                    inputBytes = TEST_EXPECTED_BYTES - 1L,
                    outputBytes = TEST_EXPECTED_BYTES
                ),
                successfulStatus(
                    inputBytes = TEST_EXPECTED_BYTES,
                    outputBytes = TEST_EXPECTED_BYTES - 1L
                ),
                successfulStatus(
                    inputBytes = TEST_EXPECTED_BYTES + 1L,
                    outputBytes = TEST_EXPECTED_BYTES
                ),
                successfulStatus(
                    inputBytes = TEST_EXPECTED_BYTES,
                    outputBytes = TEST_EXPECTED_BYTES + 1L
                )
            )

        mismatchedStatuses.forEach { status ->
            assertEquals(
                DocumentExportFailure.INVALID_RESPONSE,
                status.failureOrNull(TEST_EXPECTED_BYTES)
            )
        }
    }

    /** Maps every known failure result in its canonical terminal state. */
    @Test
    fun mapsEveryKnownFailureResult() {
        failureMappings().forEach { mapping ->
            val status =
                ExportTerminalStatus(
                    state = mapping.state,
                    resultCode = mapping.resultCode,
                    inputBytes = TEST_ZERO_BYTES,
                    outputBytes = TEST_ZERO_BYTES
                )

            assertEquals(
                mapping.expectedFailure,
                status.failureOrNull(TEST_EXPECTED_BYTES)
            )
        }
    }

    /** Rejects every known result paired with a conflicting lifecycle state. */
    @Test
    fun rejectsConflictingResultStates() {
        val wrongSuccessState =
            ExportTerminalStatus(
                state = ExportProtocol.STATE_FAILED,
                resultCode = ExportProtocol.RESULT_SUCCESS,
                inputBytes = TEST_EXPECTED_BYTES,
                outputBytes = TEST_EXPECTED_BYTES
            )
        assertEquals(
            DocumentExportFailure.INVALID_RESPONSE,
            wrongSuccessState.failureOrNull(TEST_EXPECTED_BYTES)
        )

        failureMappings().forEach { mapping ->
            val conflictingState =
                if (mapping.state == ExportProtocol.STATE_FAILED) {
                    ExportProtocol.STATE_CANCELLED
                } else {
                    ExportProtocol.STATE_FAILED
                }
            val status =
                ExportTerminalStatus(
                    state = conflictingState,
                    resultCode = mapping.resultCode,
                    inputBytes = TEST_ZERO_BYTES,
                    outputBytes = TEST_ZERO_BYTES
                )

            assertEquals(
                DocumentExportFailure.INVALID_RESPONSE,
                status.failureOrNull(TEST_EXPECTED_BYTES)
            )
        }
    }

    /** Rejects negative input and output byte counts before result mapping. */
    @Test
    fun rejectsNegativeCounts() {
        val invalidStatuses =
            listOf(
                successfulStatus(
                    inputBytes = -1L,
                    outputBytes = TEST_EXPECTED_BYTES
                ),
                successfulStatus(
                    inputBytes = TEST_EXPECTED_BYTES,
                    outputBytes = -1L
                )
            )

        invalidStatuses.forEach { status ->
            assertEquals(
                DocumentExportFailure.INVALID_RESPONSE,
                status.failureOrNull(TEST_EXPECTED_BYTES)
            )
        }
    }

    /** Rejects terminal statistics outside the service's bounded relationships. */
    @Test
    fun rejectsOutOfRangeTerminalCounts() {
        val invalidStatuses =
            listOf(
                ExportTerminalStatus(
                    state = ExportProtocol.STATE_FAILED,
                    resultCode = ExportProtocol.RESULT_INPUT_IO,
                    inputBytes = TEST_EXPECTED_BYTES + 2L,
                    outputBytes = TEST_ZERO_BYTES
                ),
                ExportTerminalStatus(
                    state = ExportProtocol.STATE_FAILED,
                    resultCode = ExportProtocol.RESULT_OUTPUT_IO,
                    inputBytes = TEST_EXPECTED_BYTES,
                    outputBytes = TEST_EXPECTED_BYTES + 1L
                ),
                ExportTerminalStatus(
                    state = ExportProtocol.STATE_FAILED,
                    resultCode = ExportProtocol.RESULT_OUTPUT_IO,
                    inputBytes = TEST_EXPECTED_BYTES - 1L,
                    outputBytes = TEST_EXPECTED_BYTES
                )
            )

        invalidStatuses.forEach { status ->
            assertEquals(
                DocumentExportFailure.INVALID_RESPONSE,
                status.failureOrNull(TEST_EXPECTED_BYTES)
            )
        }
    }

    /** Accepts exactly one canonical running callback before one terminal callback. */
    @Test
    fun acceptsCanonicalCallbackSequence() {
        val protocol = ExportCallbackProtocol()

        assertEquals(
            ExportCallbackDecision.RUNNING,
            protocol.accept(runningStatus())
        )
        assertEquals(
            ExportCallbackDecision.TERMINAL,
            protocol.accept(
                successfulStatus(
                    inputBytes = TEST_EXPECTED_BYTES,
                    outputBytes = TEST_EXPECTED_BYTES
                )
            )
        )
        assertTrue(protocol.hasCanonicalTerminal())
    }

    /** Rejects every malformed running callback tuple. */
    @Test
    fun rejectsMalformedRunningCallbacks() {
        val invalidStatuses =
            listOf(
                runningStatus(resultCode = ExportProtocol.RESULT_INTERNAL),
                runningStatus(inputBytes = 1L),
                runningStatus(outputBytes = 1L)
            )

        invalidStatuses.forEach { status ->
            val protocol = ExportCallbackProtocol()

            assertEquals(
                ExportCallbackDecision.INVALID,
                protocol.accept(status)
            )
            assertFalse(protocol.hasCanonicalTerminal())
        }
    }

    /** Rejects missing, repeated, and post-terminal callback transitions. */
    @Test
    fun rejectsInvalidCallbackOrdering() {
        val terminalBeforeRunning = ExportCallbackProtocol()
        assertEquals(
            ExportCallbackDecision.INVALID,
            terminalBeforeRunning.accept(successfulStatus(TEST_ZERO_BYTES, TEST_ZERO_BYTES))
        )

        val repeatedRunning = ExportCallbackProtocol()
        assertEquals(ExportCallbackDecision.RUNNING, repeatedRunning.accept(runningStatus()))
        assertEquals(ExportCallbackDecision.INVALID, repeatedRunning.accept(runningStatus()))

        val repeatedTerminal = ExportCallbackProtocol()
        assertEquals(ExportCallbackDecision.RUNNING, repeatedTerminal.accept(runningStatus()))
        val terminal = successfulStatus(TEST_ZERO_BYTES, TEST_ZERO_BYTES)
        assertEquals(ExportCallbackDecision.TERMINAL, repeatedTerminal.accept(terminal))
        assertEquals(ExportCallbackDecision.INVALID, repeatedTerminal.accept(terminal))
        assertFalse(repeatedTerminal.hasCanonicalTerminal())
    }

    /** Rejects an unknown native result code in any terminal state. */
    @Test
    fun rejectsUnknownResult() {
        val status =
            ExportTerminalStatus(
                state = ExportProtocol.STATE_FAILED,
                resultCode = TEST_UNKNOWN_RESULT,
                inputBytes = TEST_ZERO_BYTES,
                outputBytes = TEST_ZERO_BYTES
            )

        assertEquals(
            DocumentExportFailure.INVALID_RESPONSE,
            status.failureOrNull(TEST_EXPECTED_BYTES)
        )
    }

    /** Keeps source-only terminal results out of the ordinary copy protocol. */
    @Test
    fun rejectsSourceOnlyResultsForCopy() {
        listOf(
            ExportProtocol.RESULT_SOURCE_CONFLICT,
            ExportProtocol.RESULT_SOURCE_UNCERTAIN
        ).forEach { resultCode ->
            val status =
                ExportTerminalStatus(
                    state = ExportProtocol.STATE_FAILED,
                    resultCode = resultCode,
                    inputBytes = TEST_ZERO_BYTES,
                    outputBytes = TEST_ZERO_BYTES
                )

            assertEquals(
                DocumentExportFailure.INVALID_RESPONSE,
                status.failureOrNull(TEST_EXPECTED_BYTES)
            )
        }
    }

    /** Rejects a negative caller-supplied expected byte count. */
    @Test
    fun rejectsNegativeExpectedByteCount() {
        val status = successfulStatus(inputBytes = TEST_ZERO_BYTES, outputBytes = TEST_ZERO_BYTES)

        assertThrows(IllegalArgumentException::class.java) {
            status.failureOrNull(-1L)
        }
    }

    /** Rejects a caller-supplied expected byte count above the export limit. */
    @Test
    fun rejectsOversizedExpectedByteCount() {
        val status = successfulStatus(inputBytes = TEST_ZERO_BYTES, outputBytes = TEST_ZERO_BYTES)

        assertThrows(IllegalArgumentException::class.java) {
            status.failureOrNull(ExportProtocol.MAX_BYTE_LIMIT + 1L)
        }
    }

    /** Returns an exact new source version only after verified source-save success. */
    @Test
    fun createsVerifiedSourceSaveReceipt() {
        val status =
            sourceStatus(
                state = ExportProtocol.STATE_COMPLETE,
                resultCode = ExportProtocol.RESULT_SUCCESS,
                inputBytes = TEST_EXPECTED_BYTES,
                outputBytes = TEST_EXPECTED_BYTES,
                outputStarted = true,
                sourceSha256 = TEST_SOURCE_SHA256
            )

        val result = status.toTerminalResult(SourceOperation.SAVE, TEST_EXPECTED_BYTES)

        assertEquals(
            SourceTerminalResult.Saved(
                SourceSaveReceipt(
                    SourceVersion.from(
                        byteLength = TEST_EXPECTED_BYTES,
                        sha256 = TEST_SOURCE_SHA256
                    )
                )
            ),
            result
        )
    }

    /** Maps only a preflight source mismatch to a retry-safe conflict. */
    @Test
    fun mapsCanonicalPreflightConflict() {
        val status =
            sourceStatus(
                state = ExportProtocol.STATE_FAILED,
                resultCode = ExportProtocol.RESULT_SOURCE_CONFLICT
            )

        assertEquals(
            SourceTerminalResult.Failed(DocumentExportFailure.SOURCE_CONFLICT),
            status.toTerminalResult(SourceOperation.SAVE, TEST_EXPECTED_BYTES)
        )
    }

    /** Maps a canonical post-boundary failure to source uncertainty. */
    @Test
    fun mapsCanonicalPostBoundaryUncertainty() {
        val status =
            sourceStatus(
                state = ExportProtocol.STATE_FAILED,
                resultCode = ExportProtocol.RESULT_SOURCE_UNCERTAIN,
                outputStarted = true
            )

        assertEquals(
            SourceTerminalResult.Failed(DocumentExportFailure.SOURCE_UNCERTAIN),
            status.toTerminalResult(SourceOperation.SAVE, TEST_EXPECTED_BYTES)
        )
    }

    /** Rejects uncertainty with noncanonical partial byte statistics. */
    @Test
    fun rejectsUncertaintyWithPartialCounts() {
        val status =
            sourceStatus(
                state = ExportProtocol.STATE_FAILED,
                resultCode = ExportProtocol.RESULT_SOURCE_UNCERTAIN,
                inputBytes = 1L,
                outputBytes = 1L,
                outputStarted = true
            )

        assertEquals(
            SourceTerminalResult.Failed(DocumentExportFailure.INVALID_RESPONSE),
            status.toTerminalResult(SourceOperation.SAVE, TEST_EXPECTED_BYTES)
        )
    }

    /** Rejects conflict, cancellation, or generic failure after output started. */
    @Test
    fun rejectsNonUncertainResultsAfterSourceOutputStarts() {
        val resultCodes =
            listOf(
                ExportProtocol.RESULT_SOURCE_CONFLICT,
                ExportProtocol.RESULT_CANCELLED,
                ExportProtocol.RESULT_INTERNAL
            )

        resultCodes.forEach { resultCode ->
            val status =
                sourceStatus(
                    state = ExportProtocol.STATE_FAILED,
                    resultCode = resultCode,
                    outputStarted = true
                )

            assertEquals(
                SourceTerminalResult.Failed(DocumentExportFailure.INVALID_RESPONSE),
                status.toTerminalResult(SourceOperation.SAVE, TEST_EXPECTED_BYTES)
            )
        }
    }

    /** Accepts one exact read-only verification without write-boundary evidence. */
    @Test
    fun acceptsExactReadOnlySourceVerification() {
        val status =
            sourceStatus(
                state = ExportProtocol.STATE_COMPLETE,
                resultCode = ExportProtocol.RESULT_SUCCESS,
                inputBytes = TEST_EXPECTED_BYTES
            )

        assertEquals(
            SourceTerminalResult.Verified,
            status.toTerminalResult(SourceOperation.VERIFY, TEST_EXPECTED_BYTES)
        )
    }

    /** Returns an exact version from one bounded read-only inspection. */
    @Test
    fun acceptsBoundedReadOnlySourceInspection() {
        val status =
            sourceStatus(
                state = ExportProtocol.STATE_COMPLETE,
                resultCode = ExportProtocol.RESULT_SUCCESS,
                inputBytes = TEST_EXPECTED_BYTES - 1L,
                sourceSha256 = TEST_SOURCE_SHA256
            )

        assertEquals(
            SourceTerminalResult.Inspected(
                SourceVersion.from(
                    byteLength = TEST_EXPECTED_BYTES - 1L,
                    sha256 = TEST_SOURCE_SHA256
                )
            ),
            status.toTerminalResult(SourceOperation.INSPECT, TEST_EXPECTED_BYTES)
        )
    }

    /** Rejects malformed or over-limit source inspection success. */
    @Test
    fun rejectsMalformedSourceInspectionSuccess() {
        val statuses =
            listOf(
                sourceStatus(
                    state = ExportProtocol.STATE_COMPLETE,
                    resultCode = ExportProtocol.RESULT_SUCCESS,
                    inputBytes = TEST_EXPECTED_BYTES + 1L,
                    sourceSha256 = TEST_SOURCE_SHA256
                ),
                sourceStatus(
                    state = ExportProtocol.STATE_COMPLETE,
                    resultCode = ExportProtocol.RESULT_SUCCESS,
                    inputBytes = TEST_EXPECTED_BYTES,
                    sourceSha256 = ByteArray(0)
                ),
                sourceStatus(
                    state = ExportProtocol.STATE_COMPLETE,
                    resultCode = ExportProtocol.RESULT_SUCCESS,
                    inputBytes = TEST_EXPECTED_BYTES,
                    outputStarted = true,
                    sourceSha256 = TEST_SOURCE_SHA256
                )
            )

        statuses.forEach { status ->
            assertEquals(
                SourceTerminalResult.Failed(DocumentExportFailure.INVALID_RESPONSE),
                status.toTerminalResult(SourceOperation.INSPECT, TEST_EXPECTED_BYTES)
            )
        }
    }

    /** Rejects source success without the exact digest and boundary evidence. */
    @Test
    fun rejectsMalformedSourceSaveSuccess() {
        val statuses =
            listOf(
                sourceStatus(
                    state = ExportProtocol.STATE_COMPLETE,
                    resultCode = ExportProtocol.RESULT_SUCCESS,
                    inputBytes = TEST_EXPECTED_BYTES,
                    outputBytes = TEST_EXPECTED_BYTES,
                    outputStarted = false,
                    sourceSha256 = TEST_SOURCE_SHA256
                ),
                sourceStatus(
                    state = ExportProtocol.STATE_COMPLETE,
                    resultCode = ExportProtocol.RESULT_SUCCESS,
                    inputBytes = TEST_EXPECTED_BYTES,
                    outputBytes = TEST_EXPECTED_BYTES,
                    outputStarted = true,
                    sourceSha256 = ByteArray(SHA_256_BYTE_COUNT - 1)
                )
            )

        statuses.forEach { status ->
            assertEquals(
                SourceTerminalResult.Failed(DocumentExportFailure.INVALID_RESPONSE),
                status.toTerminalResult(SourceOperation.SAVE, TEST_EXPECTED_BYTES)
            )
        }
    }

    /** Conservatively distinguishes mutating and read-only indeterminate requests. */
    @Test
    fun classifiesIndeterminateSourceRequests() {
        assertEquals(
            DocumentExportFailure.SOURCE_UNCERTAIN,
            SourceOperation.SAVE.indeterminateFailure()
        )
        assertEquals(
            DocumentExportFailure.SERVICE_UNAVAILABLE,
            SourceOperation.VERIFY.indeterminateFailure()
        )
        assertEquals(
            DocumentExportFailure.SERVICE_UNAVAILABLE,
            SourceOperation.INSPECT.indeterminateFailure()
        )
    }

    /** Creates one canonical successful terminal status. */
    private fun successfulStatus(inputBytes: Long, outputBytes: Long): ExportTerminalStatus =
        ExportTerminalStatus(
            state = ExportProtocol.STATE_COMPLETE,
            resultCode = ExportProtocol.RESULT_SUCCESS,
            inputBytes = inputBytes,
            outputBytes = outputBytes
        )

    /** Creates one running status with caller-selected protocol values. */
    private fun runningStatus(
        resultCode: Int = ExportProtocol.RESULT_SUCCESS,
        inputBytes: Long = TEST_ZERO_BYTES,
        outputBytes: Long = TEST_ZERO_BYTES
    ): ExportTerminalStatus = ExportTerminalStatus(
        state = ExportProtocol.STATE_RUNNING,
        resultCode = resultCode,
        inputBytes = inputBytes,
        outputBytes = outputBytes
    )

    /** Creates one source status with caller-selected protocol evidence. */
    private fun sourceStatus(
        state: Int,
        resultCode: Int,
        inputBytes: Long = TEST_ZERO_BYTES,
        outputBytes: Long = TEST_ZERO_BYTES,
        outputStarted: Boolean = false,
        sourceSha256: ByteArray = ByteArray(0)
    ): SourceTerminalStatus = SourceTerminalStatus(
        state = state,
        resultCode = resultCode,
        inputBytes = inputBytes,
        outputBytes = outputBytes,
        outputStarted = outputStarted,
        sourceSha256 = sourceSha256
    )

    /** Returns every stable failure result and its required terminal state. */
    private fun failureMappings(): List<FailureMapping> = listOf(
        FailureMapping(
            resultCode = ExportProtocol.RESULT_CANCELLED,
            state = ExportProtocol.STATE_CANCELLED,
            expectedFailure = DocumentExportFailure.CANCELLED
        ),
        FailureMapping(
            resultCode = ExportProtocol.RESULT_INPUT_LIMIT,
            state = ExportProtocol.STATE_FAILED,
            expectedFailure = DocumentExportFailure.TOO_LARGE
        ),
        FailureMapping(
            resultCode = ExportProtocol.RESULT_INPUT_LENGTH_MISMATCH,
            state = ExportProtocol.STATE_FAILED,
            expectedFailure = DocumentExportFailure.WRITE_FAILED
        ),
        FailureMapping(
            resultCode = ExportProtocol.RESULT_TIMEOUT,
            state = ExportProtocol.STATE_FAILED,
            expectedFailure = DocumentExportFailure.TIMED_OUT
        ),
        FailureMapping(
            resultCode = ExportProtocol.RESULT_INVALID_DESCRIPTOR,
            state = ExportProtocol.STATE_FAILED,
            expectedFailure = DocumentExportFailure.WRITE_FAILED
        ),
        FailureMapping(
            resultCode = ExportProtocol.RESULT_INPUT_IO,
            state = ExportProtocol.STATE_FAILED,
            expectedFailure = DocumentExportFailure.WRITE_FAILED
        ),
        FailureMapping(
            resultCode = ExportProtocol.RESULT_OUTPUT_IO,
            state = ExportProtocol.STATE_FAILED,
            expectedFailure = DocumentExportFailure.WRITE_FAILED
        ),
        FailureMapping(
            resultCode = ExportProtocol.RESULT_INTERNAL,
            state = ExportProtocol.STATE_FAILED,
            expectedFailure = DocumentExportFailure.WRITE_FAILED
        )
    )
}
