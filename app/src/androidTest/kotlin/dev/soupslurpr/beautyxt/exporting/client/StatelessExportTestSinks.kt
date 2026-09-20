package dev.soupslurpr.beautyxt.exporting.client

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle

private const val AUTHORITY_SUFFIX = ".stateless-export"
private const val SHA256_HEX_CHARS = 64

/** Builds deterministic sink URIs and controls their bounded observations. */
internal object StatelessExportTestSinks {
    /** Returns the exact pipe capacity of the blocked sink. */
    val blockedPipeBytes: Int
        get() = BLOCKED_PIPE_BYTES

    /** Returns the exact payload size drained by the terminal-failure fixture. */
    val drainedFailureBytes: Long
        get() = DRAINED_FAILURE_BYTES

    /** Returns one reliable sink that consumes output to clean EOF. */
    fun normal(authorityPackage: String, operationTokenHex: String): Uri = sinkUri(
        authorityPackage = authorityPackage,
        path = PATH_SINK,
        operationTokenHex = operationTokenHex
    )

    /** Returns one reliable sink that pauses after a deterministic prefix. */
    fun paused(authorityPackage: String, operationTokenHex: String): Uri = sinkUri(
        authorityPackage = authorityPackage,
        path = PATH_PAUSED_SINK,
        operationTokenHex = operationTokenHex
    )

    /** Returns a small reliable pipe that pauses after reading its first byte. */
    fun blocked(authorityPackage: String, operationTokenHex: String): Uri = sinkUri(
        authorityPackage = authorityPackage,
        path = PATH_BLOCKED_SINK,
        operationTokenHex = operationTokenHex
    )

    /** Returns one reliable sink that fails after a deterministic prefix. */
    fun failedAfterPrefix(authorityPackage: String, operationTokenHex: String): Uri = sinkUri(
        authorityPackage = authorityPackage,
        path = PATH_FAILED_SINK,
        operationTokenHex = operationTokenHex
    )

    /** Returns one sink that drains a complete fixture while reporting failure. */
    fun failedAfterCompleteDrain(authorityPackage: String, operationTokenHex: String): Uri =
        sinkUri(
            authorityPackage = authorityPackage,
            path = PATH_DRAINED_FAILED_SINK,
            operationTokenHex = operationTokenHex
        )

    /** Replaces any prior sink with one fresh operation-scoped session. */
    fun reset(resolver: ContentResolver, authorityPackage: String, operationTokenHex: String) {
        checkNotNull(
            resolver.call(
                controlUri(authorityPackage, operationTokenHex),
                METHOD_RESET_SINK,
                null,
                controlExtras(operationTokenHex)
            )
        ) {
            "test provider returned no sink reset result"
        }
    }

    /** Returns one validated bounded snapshot of the active sink. */
    fun status(
        resolver: ContentResolver,
        authorityPackage: String,
        operationTokenHex: String
    ): ExportSinkStatus {
        val result =
            checkNotNull(
                resolver.call(
                    controlUri(authorityPackage, operationTokenHex),
                    METHOD_SINK_STATUS,
                    null,
                    controlExtras(operationTokenHex)
                )
            ) {
                "test provider returned no sink status result"
            }
        val byteCount = result.getLong(STATUS_BYTE_COUNT_KEY)
        val prefix =
            checkNotNull(result.getByteArray(STATUS_PREFIX_KEY)) {
                "test provider returned no bounded prefix"
            }
        val suffix =
            checkNotNull(result.getByteArray(STATUS_SUFFIX_KEY)) {
                "test provider returned no bounded suffix"
            }
        val terminal = result.getBoolean(STATUS_TERMINAL_KEY)
        val sha256Hex = result.getString(STATUS_SHA256_KEY)
        require(byteCount in 0L..MAX_SINK_BYTES) {
            "test provider returned an invalid sink byte count"
        }
        require(prefix.size <= MAX_STATUS_SAMPLE_BYTES) {
            "test provider returned an oversized prefix"
        }
        require(suffix.size <= MAX_STATUS_SAMPLE_BYTES) {
            "test provider returned an oversized suffix"
        }
        if (terminal) {
            require(isSha256Hex(sha256Hex)) {
                "terminal test provider status returned an invalid digest"
            }
        } else {
            require(sha256Hex == null) {
                "nonterminal test provider status returned a digest"
            }
        }
        return ExportSinkStatus(
            opened = result.getBoolean(STATUS_OPENED_KEY),
            terminal = terminal,
            hasError = result.getBoolean(STATUS_ERROR_KEY),
            paused = result.getBoolean(STATUS_PAUSED_KEY),
            byteCount = byteCount,
            sha256Hex = sha256Hex,
            prefix = prefix,
            suffix = suffix
        )
    }

    /** Releases a paused sink so it can resume consuming output. */
    fun releasePaused(
        resolver: ContentResolver,
        authorityPackage: String,
        operationTokenHex: String
    ) {
        checkNotNull(
            resolver.call(
                controlUri(authorityPackage, operationTokenHex),
                METHOD_RELEASE_PAUSED_SINK,
                null,
                controlExtras(operationTokenHex)
            )
        ) {
            "test provider returned no paused-sink release result"
        }
    }

    /** Builds one operation-scoped content sink URI. */
    private fun sinkUri(authorityPackage: String, path: String, operationTokenHex: String): Uri =
        Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(authority(authorityPackage))
            .appendPath(path)
            .appendOperationToken(operationTokenHex)
            .build()

    /** Builds the provider control URI. */
    private fun controlUri(authorityPackage: String, operationTokenHex: String): Uri = Uri.Builder()
        .scheme(ContentResolver.SCHEME_CONTENT)
        .authority(authority(authorityPackage))
        .appendOperationToken(operationTokenHex)
        .build()

    /** Builds control extras containing one validated operation token. */
    private fun controlExtras(operationTokenHex: String): Bundle {
        validateOperationToken(operationTokenHex)
        return Bundle().apply {
            putString(OPERATION_TOKEN_KEY, operationTokenHex)
        }
    }

    /** Appends one validated operation token to a provider URI. */
    private fun Uri.Builder.appendOperationToken(operationTokenHex: String): Uri.Builder {
        validateOperationToken(operationTokenHex)
        return appendQueryParameter(
            OPERATION_TOKEN_QUERY_PARAMETER,
            operationTokenHex
        )
    }

    /** Rejects malformed operation identities before crossing the provider boundary. */
    private fun validateOperationToken(operationTokenHex: String) {
        require(
            operationTokenHex.length == OPERATION_TOKEN_HEX_CHARS &&
                operationTokenHex.all { character ->
                    character in '0'..'9' || character in 'a'..'f'
                }
        ) {
            "operation token must be lowercase hexadecimal"
        }
    }

    /** Returns whether one terminal digest is canonical lowercase SHA-256. */
    private fun isSha256Hex(value: String?): Boolean = value != null &&
        value.length == SHA256_HEX_CHARS &&
        value.all { character ->
            character in '0'..'9' || character in 'a'..'f'
        }

    /** Returns the authority for the separately installed provider APK. */
    private fun authority(authorityPackage: String): String {
        require(authorityPackage.isNotBlank()) { "test package must not be blank" }
        return authorityPackage + ".providers" + AUTHORITY_SUFFIX
    }
}

/** Captures bounded externally observable state for one export sink. */
internal data class ExportSinkStatus(
    val opened: Boolean,
    val terminal: Boolean,
    val hasError: Boolean,
    val paused: Boolean,
    val byteCount: Long,
    val sha256Hex: String?,
    val prefix: ByteArray,
    val suffix: ByteArray
)
