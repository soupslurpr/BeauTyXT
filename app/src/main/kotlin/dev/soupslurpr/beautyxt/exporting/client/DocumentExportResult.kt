package dev.soupslurpr.beautyxt.exporting.client

import dev.soupslurpr.beautyxt.document.SHA_256_BYTE_COUNT
import dev.soupslurpr.beautyxt.document.SourceVersion
import dev.soupslurpr.beautyxt.exporting.ExportProtocol
import java.util.concurrent.atomic.AtomicInteger

private const val CALLBACK_AWAITING_RUNNING = 0
private const val CALLBACK_RUNNING = 1
private const val CALLBACK_TERMINAL = 2
private const val CALLBACK_INVALID = 3

/** Classifies export failures without retaining destination details. */
internal enum class DocumentExportFailure(val systemMessage: String) {
    DESTINATION_UNAVAILABLE(
        systemMessage = "selected destination descriptor unavailable"
    ),
    PIPE_UNAVAILABLE(
        systemMessage = "reliable export pipe unavailable"
    ),
    SERVICE_UNAVAILABLE(
        systemMessage = "isolated export service unavailable"
    ),
    SERVICE_BUSY(
        systemMessage = "isolated export service busy"
    ),
    TOO_LARGE(
        systemMessage = "document exceeds export limits"
    ),
    TIMED_OUT(
        systemMessage = "isolated export exceeded its time limit"
    ),
    CANCELLED(
        systemMessage = "isolated export cancelled"
    ),
    SNAPSHOT_FAILED(
        systemMessage = "document snapshot producer failed"
    ),
    WRITE_FAILED(
        systemMessage = "isolated export failed"
    ),
    SOURCE_CONFLICT(
        systemMessage = "source version changed before save"
    ),
    SOURCE_UNCERTAIN(
        systemMessage = "source save completion is uncertain"
    ),
    INVALID_RESPONSE(
        systemMessage = "isolated export returned an invalid response"
    )
}

/** Reports one sanitized export failure to application code. */
internal class DocumentExportException(
    val failure: DocumentExportFailure,
    cause: Throwable? = null
) : Exception(failure.systemMessage, cause)

/** Stores one callback status without retaining Binder or content objects. */
internal data class ExportTerminalStatus(
    val state: Int,
    val resultCode: Int,
    val inputBytes: Long,
    val outputBytes: Long
)

/** Reports the newly verified source version after one conditional replacement. */
internal data class SourceSaveReceipt(val sourceVersion: SourceVersion)

/** Identifies which source protocol contract validates one terminal callback. */
internal enum class SourceOperation {
    SAVE,
    VERIFY,
    INSPECT
}

/** Classifies an accepted or reply-lost source operation with no trusted terminal result. */
internal fun SourceOperation.indeterminateFailure(): DocumentExportFailure = when (this) {
    SourceOperation.SAVE -> DocumentExportFailure.SOURCE_UNCERTAIN

    SourceOperation.VERIFY,
    SourceOperation.INSPECT -> DocumentExportFailure.SERVICE_UNAVAILABLE
}

/** Stores one source callback without retaining its mutable Binder byte array. */
internal class SourceTerminalStatus(
    val state: Int,
    val resultCode: Int,
    val inputBytes: Long,
    val outputBytes: Long,
    val outputStarted: Boolean,
    sourceSha256: ByteArray
) {
    private val sourceSha256 = sourceSha256.copyOf()

    /** Returns a defensive copy of the callback's source digest. */
    fun copySourceSha256(): ByteArray = sourceSha256.copyOf()
}

/** Represents one fully validated source terminal result. */
internal sealed interface SourceTerminalResult {
    /** Reports a verified conditional source replacement. */
    data class Saved(val receipt: SourceSaveReceipt) : SourceTerminalResult

    /** Reports one sanitized source operation failure. */
    data class Failed(val failure: DocumentExportFailure) : SourceTerminalResult

    /** Reports a verified read-only source match. */
    data object Verified : SourceTerminalResult

    /** Contains one exact version returned by a read-only source inspection. */
    data class Inspected(val sourceVersion: SourceVersion) : SourceTerminalResult
}

/** Classifies one callback against the required lifecycle ordering. */
internal enum class ExportCallbackDecision {
    RUNNING,
    TERMINAL,
    INVALID
}

/** Enforces one canonical running callback followed by one terminal callback. */
internal class ExportCallbackProtocol {
    private val state = AtomicInteger(CALLBACK_AWAITING_RUNNING)

    /** Accepts one status and returns its protocol role. */
    fun accept(status: ExportTerminalStatus): ExportCallbackDecision {
        if (status.state == ExportProtocol.STATE_RUNNING) {
            if (!status.isCanonicalRunning()) {
                return invalidate()
            }
            return if (
                state.compareAndSet(
                    CALLBACK_AWAITING_RUNNING,
                    CALLBACK_RUNNING
                )
            ) {
                ExportCallbackDecision.RUNNING
            } else {
                invalidate()
            }
        }

        return if (state.compareAndSet(CALLBACK_RUNNING, CALLBACK_TERMINAL)) {
            ExportCallbackDecision.TERMINAL
        } else {
            invalidate()
        }
    }

    /** Returns whether exactly one ordered terminal callback remains valid. */
    fun hasCanonicalTerminal(): Boolean = state.get() == CALLBACK_TERMINAL

    /** Makes every current and subsequent callback sequence invalid. */
    private fun invalidate(): ExportCallbackDecision {
        state.set(CALLBACK_INVALID)
        return ExportCallbackDecision.INVALID
    }
}

/** Returns whether this status is the only valid running notification. */
internal fun ExportTerminalStatus.isCanonicalRunning(): Boolean =
    state == ExportProtocol.STATE_RUNNING &&
        resultCode == ExportProtocol.RESULT_SUCCESS &&
        inputBytes == 0L &&
        outputBytes == 0L

/** Returns a sanitized failure when a terminal status is not exact success. */
internal fun ExportTerminalStatus.failureOrNull(expectedBytes: Long): DocumentExportFailure? {
    require(expectedBytes in ExportProtocol.MIN_BYTE_LIMIT..ExportProtocol.MAX_BYTE_LIMIT) {
        "expected byte count exceeds export limits"
    }
    if (
        inputBytes !in 0L..(expectedBytes + 1L) ||
        outputBytes !in 0L..expectedBytes ||
        outputBytes > inputBytes
    ) {
        return DocumentExportFailure.INVALID_RESPONSE
    }

    return when (resultCode) {
        ExportProtocol.RESULT_SUCCESS ->
            if (
                state == ExportProtocol.STATE_COMPLETE &&
                inputBytes == expectedBytes &&
                outputBytes == expectedBytes
            ) {
                null
            } else {
                DocumentExportFailure.INVALID_RESPONSE
            }

        ExportProtocol.RESULT_CANCELLED -> expectedFailure(
            expectedState = ExportProtocol.STATE_CANCELLED,
            failure = DocumentExportFailure.CANCELLED
        )

        ExportProtocol.RESULT_INPUT_LIMIT -> expectedFailure(
            expectedState = ExportProtocol.STATE_FAILED,
            failure = DocumentExportFailure.TOO_LARGE
        )

        ExportProtocol.RESULT_TIMEOUT -> expectedFailure(
            expectedState = ExportProtocol.STATE_FAILED,
            failure = DocumentExportFailure.TIMED_OUT
        )

        ExportProtocol.RESULT_INPUT_LENGTH_MISMATCH,
        ExportProtocol.RESULT_INVALID_DESCRIPTOR,
        ExportProtocol.RESULT_INPUT_IO,
        ExportProtocol.RESULT_OUTPUT_IO,
        ExportProtocol.RESULT_INTERNAL -> expectedFailure(
            expectedState = ExportProtocol.STATE_FAILED,
            failure = DocumentExportFailure.WRITE_FAILED
        )

        else -> DocumentExportFailure.INVALID_RESPONSE
    }
}

/** Validates one terminal source callback against its requested operation. */
internal fun SourceTerminalStatus.toTerminalResult(
    operation: SourceOperation,
    expectedBytes: Long
): SourceTerminalResult {
    require(expectedBytes in ExportProtocol.MIN_BYTE_LIMIT..ExportProtocol.MAX_BYTE_LIMIT) {
        "expected byte count exceeds export limits"
    }
    val sourceSha256 = copySourceSha256()
    val maximumOutputBytes = if (operation == SourceOperation.SAVE) expectedBytes else 0L
    if (
        inputBytes !in 0L..(expectedBytes + 1L) ||
        outputBytes !in 0L..maximumOutputBytes ||
        (operation == SourceOperation.SAVE && outputBytes > inputBytes)
    ) {
        return SourceTerminalResult.Failed(DocumentExportFailure.INVALID_RESPONSE)
    }

    if (resultCode == ExportProtocol.RESULT_SUCCESS) {
        return successfulSourceResult(
            operation = operation,
            expectedBytes = expectedBytes,
            sourceSha256 = sourceSha256
        )
    }
    if (sourceSha256.isNotEmpty()) {
        return SourceTerminalResult.Failed(DocumentExportFailure.INVALID_RESPONSE)
    }
    if (outputStarted) {
        if (operation != SourceOperation.SAVE) {
            return SourceTerminalResult.Failed(DocumentExportFailure.INVALID_RESPONSE)
        }
        return if (
            state == ExportProtocol.STATE_FAILED &&
            resultCode == ExportProtocol.RESULT_SOURCE_UNCERTAIN &&
            inputBytes == 0L &&
            outputBytes == 0L
        ) {
            SourceTerminalResult.Failed(DocumentExportFailure.SOURCE_UNCERTAIN)
        } else {
            SourceTerminalResult.Failed(DocumentExportFailure.INVALID_RESPONSE)
        }
    }

    val failure =
        when (resultCode) {
            ExportProtocol.RESULT_CANCELLED -> expectedSourceFailure(
                expectedState = ExportProtocol.STATE_CANCELLED,
                failure = DocumentExportFailure.CANCELLED
            )

            ExportProtocol.RESULT_INPUT_LIMIT -> expectedSourceFailure(
                expectedState = ExportProtocol.STATE_FAILED,
                failure = DocumentExportFailure.TOO_LARGE
            )

            ExportProtocol.RESULT_TIMEOUT -> expectedSourceFailure(
                expectedState = ExportProtocol.STATE_FAILED,
                failure = DocumentExportFailure.TIMED_OUT
            )

            ExportProtocol.RESULT_SOURCE_CONFLICT ->
                if (operation == SourceOperation.INSPECT) {
                    DocumentExportFailure.INVALID_RESPONSE
                } else {
                    expectedSourceFailure(
                        expectedState = ExportProtocol.STATE_FAILED,
                        failure = DocumentExportFailure.SOURCE_CONFLICT
                    )
                }

            ExportProtocol.RESULT_INPUT_LENGTH_MISMATCH,
            ExportProtocol.RESULT_INVALID_DESCRIPTOR,
            ExportProtocol.RESULT_INPUT_IO,
            ExportProtocol.RESULT_OUTPUT_IO,
            ExportProtocol.RESULT_INTERNAL -> expectedSourceFailure(
                expectedState = ExportProtocol.STATE_FAILED,
                failure = DocumentExportFailure.WRITE_FAILED
            )

            else -> DocumentExportFailure.INVALID_RESPONSE
        }
    return SourceTerminalResult.Failed(failure)
}

/** Validates one successful source operation and creates its exact result. */
private fun SourceTerminalStatus.successfulSourceResult(
    operation: SourceOperation,
    expectedBytes: Long,
    sourceSha256: ByteArray
): SourceTerminalResult {
    if (state != ExportProtocol.STATE_COMPLETE) {
        return SourceTerminalResult.Failed(DocumentExportFailure.INVALID_RESPONSE)
    }
    return when (operation) {
        SourceOperation.SAVE ->
            if (
                outputStarted &&
                inputBytes == expectedBytes &&
                outputBytes == expectedBytes &&
                sourceSha256.size == SHA_256_BYTE_COUNT
            ) {
                SourceTerminalResult.Saved(
                    SourceSaveReceipt(
                        SourceVersion.from(
                            byteLength = expectedBytes,
                            sha256 = sourceSha256
                        )
                    )
                )
            } else {
                SourceTerminalResult.Failed(DocumentExportFailure.INVALID_RESPONSE)
            }

        SourceOperation.VERIFY ->
            if (
                !outputStarted &&
                inputBytes == expectedBytes &&
                outputBytes == 0L &&
                sourceSha256.isEmpty()
            ) {
                SourceTerminalResult.Verified
            } else {
                SourceTerminalResult.Failed(DocumentExportFailure.INVALID_RESPONSE)
            }

        SourceOperation.INSPECT ->
            if (
                !outputStarted &&
                inputBytes <= expectedBytes &&
                outputBytes == 0L &&
                sourceSha256.size == SHA_256_BYTE_COUNT
            ) {
                SourceTerminalResult.Inspected(
                    SourceVersion.from(
                        byteLength = inputBytes,
                        sha256 = sourceSha256
                    )
                )
            } else {
                SourceTerminalResult.Failed(DocumentExportFailure.INVALID_RESPONSE)
            }
    }
}

/** Preserves a source failure only when its state and zero counts are canonical. */
private fun SourceTerminalStatus.expectedSourceFailure(
    expectedState: Int,
    failure: DocumentExportFailure
): DocumentExportFailure = if (state == expectedState && inputBytes == 0L && outputBytes == 0L) {
    failure
} else {
    DocumentExportFailure.INVALID_RESPONSE
}

/** Preserves a known failure only when its lifecycle state is canonical. */
private fun ExportTerminalStatus.expectedFailure(
    expectedState: Int,
    failure: DocumentExportFailure
): DocumentExportFailure =
    if (state == expectedState) failure else DocumentExportFailure.INVALID_RESPONSE
