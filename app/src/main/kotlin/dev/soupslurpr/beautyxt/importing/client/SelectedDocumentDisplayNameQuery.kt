package dev.soupslurpr.beautyxt.importing.client

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

private const val DISPLAY_NAME_QUERY_TIMEOUT_MILLIS = 2_000L
private const val MAX_CONCURRENT_DISPLAY_NAME_QUERIES = 1
private const val MAX_PROVIDER_MIME_TYPE_CHARACTERS = 255
private val ProviderMimeTypePattern =
    Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")
private val displayNameQueryRunner =
    BoundedProviderQueryRunner(
        timeoutMillis = DISPLAY_NAME_QUERY_TIMEOUT_MILLIS,
        dispatcher =
            Dispatchers.IO.limitedParallelism(MAX_CONCURRENT_DISPLAY_NAME_QUERIES)
    )

/** Contains sanitized provider facts retained only for one open document session. */
internal data class SelectedDocumentMetadata(
    val displayName: String? = null,
    val mimeType: String? = null,
    val lastModifiedEpochMillis: Long? = null
) {
    init {
        require(
            displayName == null || sanitizeSelectedDocumentDisplayName(displayName) == displayName
        ) {
            "provider display name is not sanitized"
        }
        require(mimeType == null || sanitizeProviderMimeType(mimeType) == mimeType) {
            "provider MIME type is not sanitized"
        }
        require(lastModifiedEpochMillis == null || lastModifiedEpochMillis > 0L) {
            "provider modification time must be positive"
        }
    }
}

/** Contains nullable facts returned by one provider cursor. */
private data class ProviderCursorMetadata(
    val displayName: String?,
    val lastModifiedEpochMillis: Long?
)

/** Returns one best-effort, sanitized display name from provider metadata. */
internal suspend fun querySelectedDocumentDisplayName(
    contentResolver: ContentResolver,
    uri: Uri
): String? = querySelectedDocumentMetadata(contentResolver, uri).displayName

/** Returns best-effort sanitized provider metadata without retaining source identity. */
internal suspend fun querySelectedDocumentMetadata(
    contentResolver: ContentResolver,
    uri: Uri
): SelectedDocumentMetadata {
    val cancellationSignal = CancellationSignal()
    return displayNameQueryRunner
        .query(
            cancellationAction = cancellationSignal::cancel
        ) {
            val cursorMetadata =
                queryProviderCursorMetadata(
                    contentResolver = contentResolver,
                    uri = uri,
                    cancellationSignal = cancellationSignal
                )
            val mimeType =
                try {
                    sanitizeProviderMimeType(contentResolver.getType(uri))
                } catch (_: Exception) {
                    null
                }
            SelectedDocumentMetadata(
                displayName = cursorMetadata?.displayName,
                mimeType = mimeType,
                lastModifiedEpochMillis = cursorMetadata?.lastModifiedEpochMillis
            )
        } ?: SelectedDocumentMetadata()
}

/** Queries display name and modification time with a display-name-only fallback. */
private fun queryProviderCursorMetadata(
    contentResolver: ContentResolver,
    uri: Uri,
    cancellationSignal: CancellationSignal
): ProviderCursorMetadata? {
    val completeProjection =
        arrayOf(
            OpenableColumns.DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
    return try {
        queryProviderCursorMetadata(
            contentResolver = contentResolver,
            uri = uri,
            cancellationSignal = cancellationSignal,
            projection = completeProjection
        )
    } catch (_: Exception) {
        if (cancellationSignal.isCanceled) {
            null
        } else {
            queryProviderCursorMetadata(
                contentResolver = contentResolver,
                uri = uri,
                cancellationSignal = cancellationSignal,
                projection = arrayOf(OpenableColumns.DISPLAY_NAME)
            )
        }
    }
}

/** Reads sanitized facts from one provider cursor projection. */
private fun queryProviderCursorMetadata(
    contentResolver: ContentResolver,
    uri: Uri,
    cancellationSignal: CancellationSignal,
    projection: Array<String>
): ProviderCursorMetadata? = contentResolver
    .query(uri, projection, Bundle.EMPTY, cancellationSignal)
    ?.use { cursor ->
        if (!cursor.moveToFirst()) {
            return@use null
        }
        val displayNameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val modifiedColumn =
            cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        ProviderCursorMetadata(
            displayName =
                displayNameColumn
                    .takeIf { column -> column >= 0 && !cursor.isNull(column) }
                    ?.let(cursor::getString)
                    ?.let(::sanitizeSelectedDocumentDisplayName),
            lastModifiedEpochMillis =
                modifiedColumn
                    .takeIf { column -> column >= 0 && !cursor.isNull(column) }
                    ?.let(cursor::getLong)
                    ?.takeIf { modified -> modified > 0L }
        )
    }

/** Returns one bounded canonical MIME type from provider metadata. */
internal fun sanitizeProviderMimeType(mimeType: String?): String? {
    val canonical = mimeType?.trim()?.lowercase(Locale.ROOT) ?: return null
    return canonical.takeIf { value ->
        value.length in 1..MAX_PROVIDER_MIME_TYPE_CHARACTERS &&
            ProviderMimeTypePattern.matches(value)
    }
}

/** Runs at most one detached display-name query with bounded waiting. */
internal class BoundedProviderQueryRunner(
    private val timeoutMillis: Long,
    private val dispatcher: CoroutineDispatcher
) {
    private val workerReserved = AtomicBoolean(false)

    init {
        require(timeoutMillis > 0L) { "timeout must be positive" }
    }

    /** Returns one best-effort result without retaining queued provider calls. */
    suspend fun <Result> query(
        cancellationAction: () -> Unit,
        queryAction: () -> Result?
    ): Result? = try {
        withTimeout(timeoutMillis) {
            currentCoroutineContext().ensureActive()
            if (!workerReserved.compareAndSet(false, true)) {
                currentCoroutineContext().ensureActive()
                return@withTimeout null
            }
            awaitDetachedQuery(
                cancellationAction = cancellationAction,
                queryAction = queryAction
            )
        }
    } catch (_: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        null
    } catch (cancellation: CancellationException) {
        throw cancellation
    }

    /** Dispatches one query whose blocked worker cannot retain its caller. */
    private suspend fun <Result> awaitDetachedQuery(
        cancellationAction: () -> Unit,
        queryAction: () -> Result?
    ): Result? = suspendCancellableCoroutine { continuation ->
        val pendingContinuation = AtomicReference(continuation)
        continuation.invokeOnCancellation {
            pendingContinuation.compareAndSet(continuation, null)
            try {
                cancellationAction()
            } catch (_: Exception) {
            }
        }
        try {
            dispatcher.dispatch(
                continuation.context,
                Runnable {
                    runQuery(
                        pendingContinuation = pendingContinuation,
                        queryAction = queryAction
                    )
                }
            )
        } catch (_: Exception) {
            workerReserved.set(false)
            pendingContinuation.getAndSet(null)?.resume(null) { _, _, _ -> }
        }
    }

    /** Completes or abandons one detached query and releases its only worker slot. */
    private fun <Result> runQuery(
        pendingContinuation: AtomicReference<CancellableContinuation<Result?>?>,
        queryAction: () -> Result?
    ) {
        if (pendingContinuation.get() == null) {
            workerReserved.set(false)
            return
        }
        var result: Result? = null
        try {
            result = queryAction()
        } catch (_: Exception) {
        } finally {
            workerReserved.set(false)
            pendingContinuation.getAndSet(null)?.resume(result) { _, _, _ -> }
        }
    }
}
