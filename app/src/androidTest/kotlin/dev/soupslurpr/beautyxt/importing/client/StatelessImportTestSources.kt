package dev.soupslurpr.beautyxt.importing.client

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle

private const val AUTHORITY_SUFFIX = ".stateless-import"

/** Builds deterministic provider URIs and controls paused streams. */
internal object StatelessImportTestSources {
    /** Returns one anonymous seekable source of the requested length. */
    fun seekable(authorityPackage: String, sourceBytes: Long, operationTokenHex: String): Uri =
        sourceUri(
            authorityPackage = authorityPackage,
            path = PATH_SEEKABLE,
            sourceBytes = sourceBytes,
            operationTokenHex = operationTokenHex
        )

    /** Returns one reliable nonseekable source of the requested length. */
    fun streaming(authorityPackage: String, sourceBytes: Long, operationTokenHex: String): Uri =
        sourceUri(
            authorityPackage = authorityPackage,
            path = PATH_PIPE,
            sourceBytes = sourceBytes,
            operationTokenHex = operationTokenHex
        )

    /** Returns the single controlled nonseekable source for the active session. */
    fun paused(authorityPackage: String, operationTokenHex: String): Uri = Uri.Builder()
        .scheme(ContentResolver.SCHEME_CONTENT)
        .authority(authority(authorityPackage))
        .appendPath(PATH_PAUSED_PIPE)
        .appendOperationToken(operationTokenHex)
        .build()

    /** Returns a nonseekable source that reports an error after valid data. */
    fun failed(authorityPackage: String, operationTokenHex: String): Uri = Uri.Builder()
        .scheme(ContentResolver.SCHEME_CONTENT)
        .authority(authority(authorityPackage))
        .appendPath(PATH_FAILED_PIPE)
        .appendOperationToken(operationTokenHex)
        .build()

    /** Offers one temporary read grant that the importer must never persist. */
    fun offerPersistableReadGrant(
        resolver: ContentResolver,
        authorityPackage: String,
        targetPackage: String,
        uri: Uri,
        operationTokenHex: String
    ) {
        callGrantControl(
            resolver = resolver,
            authorityPackage = authorityPackage,
            method = METHOD_OFFER_PERSISTABLE_READ_GRANT,
            targetPackage = targetPackage,
            uri = uri,
            operationTokenHex = operationTokenHex
        )
    }

    /** Revokes one temporary read grant offered by the test provider. */
    fun revokeReadGrant(
        resolver: ContentResolver,
        authorityPackage: String,
        targetPackage: String,
        uri: Uri,
        operationTokenHex: String
    ) {
        callGrantControl(
            resolver = resolver,
            authorityPackage = authorityPackage,
            method = METHOD_REVOKE_READ_GRANT,
            targetPackage = targetPackage,
            uri = uri,
            operationTokenHex = operationTokenHex
        )
    }

    /** Replaces any prior paused stream with a fresh controlled session. */
    fun resetPaused(
        resolver: ContentResolver,
        authorityPackage: String,
        operationTokenHex: String
    ) {
        checkNotNull(
            resolver.call(
                controlUri(authorityPackage, operationTokenHex),
                METHOD_RESET_PAUSED_PIPE,
                null,
                controlExtras(operationTokenHex)
            )
        ) {
            "test provider returned no reset result"
        }
    }

    /** Returns the current paused source state. */
    fun pausedStatus(
        resolver: ContentResolver,
        authorityPackage: String,
        operationTokenHex: String
    ): PausedPipeStatus {
        val result =
            checkNotNull(
                resolver.call(
                    controlUri(authorityPackage, operationTokenHex),
                    METHOD_PAUSED_PIPE_STATUS,
                    null,
                    controlExtras(operationTokenHex)
                )
            ) {
                "test provider returned no status result"
            }
        return PausedPipeStatus(
            opened = result.getBoolean(STATUS_OPENED_KEY),
            prefixDelivered = result.getBoolean(STATUS_PREFIX_DELIVERED_KEY),
            writeFailed = result.getBoolean(STATUS_WRITE_FAILED_KEY),
            terminated = result.getBoolean(STATUS_TERMINATED_KEY)
        )
    }

    /** Releases the paused source so its provider reports clean EOF. */
    fun releasePaused(
        resolver: ContentResolver,
        authorityPackage: String,
        operationTokenHex: String
    ) {
        checkNotNull(
            resolver.call(
                controlUri(authorityPackage, operationTokenHex),
                METHOD_RELEASE_PAUSED_PIPE,
                null,
                controlExtras(operationTokenHex)
            )
        ) {
            "test provider returned no release result"
        }
    }

    /** Builds one bounded source URI. */
    private fun sourceUri(
        authorityPackage: String,
        path: String,
        sourceBytes: Long,
        operationTokenHex: String
    ): Uri {
        val minimumSourceBytes = MIN_SOURCE_BYTES
        val maximumSourceBytes = MAX_SOURCE_BYTES
        require(sourceBytes in minimumSourceBytes..maximumSourceBytes) {
            "test source byte count is outside supported limits"
        }
        return Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(authority(authorityPackage))
            .appendPath(path)
            .appendQueryParameter(
                SIZE_QUERY_PARAMETER,
                sourceBytes.toString()
            )
            .appendOperationToken(operationTokenHex)
            .build()
    }

    /** Builds the provider control URI. */
    private fun controlUri(authorityPackage: String, operationTokenHex: String): Uri = Uri.Builder()
        .scheme(ContentResolver.SCHEME_CONTENT)
        .authority(authority(authorityPackage))
        .appendOperationToken(operationTokenHex)
        .build()

    /** Invokes one provider-side grant operation with validated identifiers. */
    private fun callGrantControl(
        resolver: ContentResolver,
        authorityPackage: String,
        method: String,
        targetPackage: String,
        uri: Uri,
        operationTokenHex: String
    ) {
        require(targetPackage.isNotBlank()) { "target package must not be blank" }
        val extras =
            controlExtras(operationTokenHex).apply {
                putString(GRANT_TARGET_PACKAGE_KEY, targetPackage)
                putString(GRANT_URI_KEY, uri.toString())
            }
        checkNotNull(
            resolver.call(
                controlUri(authorityPackage, operationTokenHex),
                method,
                null,
                extras
            )
        ) {
            "test provider returned no grant result"
        }
    }

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

    /** Returns the authority for the separately installed provider APK. */
    private fun authority(authorityPackage: String): String {
        require(authorityPackage.isNotBlank()) { "test package must not be blank" }
        return authorityPackage + ".providers" + AUTHORITY_SUFFIX
    }
}

/** Captures externally observable state for one paused provider stream. */
internal data class PausedPipeStatus(
    val opened: Boolean,
    val prefixDelivered: Boolean,
    val writeFailed: Boolean,
    val terminated: Boolean
)
