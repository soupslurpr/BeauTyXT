package dev.soupslurpr.beautyxt.importing.client

import dev.soupslurpr.beautyxt.document.SourceVersion
import dev.soupslurpr.beautyxt.importing.ImportProtocol

/** Classifies import failures without retaining file or provider details. */
internal enum class DocumentImportFailure(val systemMessage: String) {
    SOURCE_UNAVAILABLE(
        systemMessage = "selected document descriptor unavailable"
    ),
    BUFFER_UNAVAILABLE(
        systemMessage = "anonymous import buffer unavailable"
    ),
    SERVICE_UNAVAILABLE(
        systemMessage = "isolated import service unavailable"
    ),
    SERVICE_BUSY(
        systemMessage = "isolated import service busy"
    ),
    INVALID_UTF8(
        systemMessage = "selected document is not valid utf-8"
    ),
    UNSUPPORTED_ENCODING(
        systemMessage = "selected document encoding unsupported"
    ),
    TOO_LARGE(
        systemMessage = "selected document exceeds import limits"
    ),
    TIMED_OUT(
        systemMessage = "isolated import exceeded its time limit"
    ),
    CANCELLED(
        systemMessage = "isolated import cancelled"
    ),
    OPEN_FAILED(
        systemMessage = "isolated import failed"
    ),
    INVALID_RESPONSE(
        systemMessage = "isolated import returned an invalid response"
    )
}

/** Reports one sanitized import failure to application code. */
internal class DocumentImportException(
    val failure: DocumentImportFailure,
    cause: Throwable? = null
) : Exception(failure.systemMessage, cause)

/** Stores one terminal callback without retaining Binder or content objects. */
internal class ImportTerminalStatus(
    val state: Int,
    val resultCode: Int,
    val inputBytes: Long,
    val outputBytes: Long,
    val sourceFlags: Int,
    sourceSha256: ByteArray
) {
    private val sourceSha256 = sourceSha256.copyOf()

    /** Returns whether the digest has the exact protocol width. */
    fun hasExactSourceSha256Length(): Boolean =
        sourceSha256.size == ImportProtocol.RESULT_SHA_256_BYTE_COUNT

    /** Returns whether every reported digest byte is zero. */
    fun hasZeroSourceSha256(): Boolean = sourceSha256.all { byte -> byte == 0.toByte() }

    /** Creates the exact imported source version from a canonical success. */
    fun sourceVersion(): SourceVersion {
        check(state == ImportProtocol.STATE_COMPLETE) { "import is not complete" }
        check(resultCode == ImportProtocol.RESULT_SUCCESS) { "import did not succeed" }
        return SourceVersion.from(byteLength = inputBytes, sha256 = sourceSha256)
    }
}

/** Returns a sanitized failure when a terminal status is not valid success. */
internal fun ImportTerminalStatus.failureOrNull(
    maxInputBytes: Long,
    maxOutputBytes: Long
): DocumentImportFailure? {
    require(maxInputBytes > 0L) { "maximum input bytes must be positive" }
    require(maxOutputBytes > 0L) { "maximum output bytes must be positive" }
    if (
        inputBytes < 0L ||
        outputBytes < 0L ||
        !hasExactSourceSha256Length() ||
        sourceFlags and ImportProtocol.SOURCE_FLAGS_MASK.inv() != 0 ||
        (
            resultCode != ImportProtocol.RESULT_SUCCESS &&
                (
                    inputBytes != 0L ||
                        outputBytes != 0L ||
                        sourceFlags != 0 ||
                        !hasZeroSourceSha256()
                    )
            )
    ) {
        return DocumentImportFailure.INVALID_RESPONSE
    }

    return when (resultCode) {
        ImportProtocol.RESULT_SUCCESS ->
            if (
                state == ImportProtocol.STATE_COMPLETE &&
                inputBytes == outputBytes &&
                inputBytes <= maxInputBytes &&
                outputBytes <= maxOutputBytes
            ) {
                null
            } else {
                DocumentImportFailure.INVALID_RESPONSE
            }

        ImportProtocol.RESULT_CANCELLED ->
            if (state == ImportProtocol.STATE_CANCELLED) {
                DocumentImportFailure.CANCELLED
            } else {
                DocumentImportFailure.INVALID_RESPONSE
            }

        ImportProtocol.RESULT_INVALID_UTF8 -> expectedFailure(
            expectedState = ImportProtocol.STATE_FAILED,
            failure = DocumentImportFailure.INVALID_UTF8
        )

        ImportProtocol.RESULT_UNSUPPORTED_BOM -> expectedFailure(
            expectedState = ImportProtocol.STATE_FAILED,
            failure = DocumentImportFailure.UNSUPPORTED_ENCODING
        )

        ImportProtocol.RESULT_INPUT_LIMIT,
        ImportProtocol.RESULT_OUTPUT_LIMIT -> expectedFailure(
            expectedState = ImportProtocol.STATE_FAILED,
            failure = DocumentImportFailure.TOO_LARGE
        )

        ImportProtocol.RESULT_TIMEOUT -> expectedFailure(
            expectedState = ImportProtocol.STATE_FAILED,
            failure = DocumentImportFailure.TIMED_OUT
        )

        ImportProtocol.RESULT_INVALID_DESCRIPTOR,
        ImportProtocol.RESULT_INPUT_IO,
        ImportProtocol.RESULT_OUTPUT_IO,
        ImportProtocol.RESULT_INTERNAL -> expectedFailure(
            expectedState = ImportProtocol.STATE_FAILED,
            failure = DocumentImportFailure.OPEN_FAILED
        )

        else -> DocumentImportFailure.INVALID_RESPONSE
    }
}

/** Preserves a known failure only when its lifecycle state is canonical. */
private fun ImportTerminalStatus.expectedFailure(
    expectedState: Int,
    failure: DocumentImportFailure
): DocumentImportFailure =
    if (state == expectedState) failure else DocumentImportFailure.INVALID_RESPONSE
