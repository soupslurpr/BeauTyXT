package dev.soupslurpr.beautyxt.importing.client

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.DocumentsContract
import dev.soupslurpr.beautyxt.document.DocumentRemovalAction
import dev.soupslurpr.beautyxt.document.DocumentRemovalCapabilities
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

private const val REMOVAL_CAPABILITY_QUERY_TIMEOUT_MILLIS = 2_000L
private const val MAX_CONCURRENT_REMOVAL_CAPABILITY_QUERIES = 1
private val removalCapabilityQueryRunner =
    BoundedProviderQueryRunner(
        timeoutMillis = REMOVAL_CAPABILITY_QUERY_TIMEOUT_MILLIS,
        dispatcher =
            Dispatchers.IO.limitedParallelism(
                MAX_CONCURRENT_REMOVAL_CAPABILITY_QUERIES
            )
    )

/** Reports that a selected document provider could not complete a removal request. */
internal class SelectedDocumentRemovalException(cause: Throwable? = null) :
    IOException("selected document removal unavailable", cause)

/** Maps provider document flags into independent trash and deletion capabilities. */
internal fun documentRemovalCapabilities(documentFlags: Int): DocumentRemovalCapabilities =
    DocumentRemovalCapabilities(
        canTrash =
            documentFlags and DocumentsContract.Document.FLAG_SUPPORTS_TRASH != 0,
        canDelete =
            documentFlags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0
    )

/** Queries live removal capabilities without retaining provider metadata. */
internal suspend fun querySelectedDocumentRemovalCapabilities(
    contentResolver: ContentResolver,
    uri: Uri,
    isDocumentUri: Boolean
): DocumentRemovalCapabilities {
    if (!isDocumentUri) {
        return DocumentRemovalCapabilities.None
    }
    val cancellationSignal = CancellationSignal()
    val documentFlags =
        removalCapabilityQueryRunner.query(
            cancellationAction = cancellationSignal::cancel
        ) {
            querySelectedDocumentFlags(
                contentResolver = contentResolver,
                uri = uri,
                cancellationSignal = cancellationSignal
            )
        } ?: throw SelectedDocumentRemovalException()
    return documentRemovalCapabilities(documentFlags)
}

/** Performs one freshly authorized provider removal operation to exact completion. */
internal suspend fun removeSelectedDocument(
    contentResolver: ContentResolver,
    uri: Uri,
    isDocumentUri: Boolean,
    action: DocumentRemovalAction
) {
    val capabilities =
        querySelectedDocumentRemovalCapabilities(
            contentResolver = contentResolver,
            uri = uri,
            isDocumentUri = isDocumentUri
        )
    if (!capabilities.supports(action)) {
        throw SelectedDocumentRemovalException()
    }
    try {
        withContext(Dispatchers.IO + NonCancellable) {
            val succeeded =
                when (action) {
                    DocumentRemovalAction.Trash ->
                        DocumentsContract.trashDocument(contentResolver, uri) != null

                    DocumentRemovalAction.Delete ->
                        DocumentsContract.deleteDocument(contentResolver, uri)
                }
            if (!succeeded) {
                throw SelectedDocumentRemovalException()
            }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: SelectedDocumentRemovalException) {
        throw failure
    } catch (failure: Exception) {
        throw SelectedDocumentRemovalException(failure)
    } catch (failure: LinkageError) {
        throw SelectedDocumentRemovalException(failure)
    }
}

/** Reads one exact provider flag word from a document query. */
private fun querySelectedDocumentFlags(
    contentResolver: ContentResolver,
    uri: Uri,
    cancellationSignal: CancellationSignal
): Int? = contentResolver
    .query(
        uri,
        arrayOf(DocumentsContract.Document.COLUMN_FLAGS),
        Bundle.EMPTY,
        cancellationSignal
    )
    ?.use { cursor ->
        if (!cursor.moveToFirst()) {
            return@use null
        }
        val flagsColumn =
            cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
        flagsColumn
            .takeIf { column -> column >= 0 && !cursor.isNull(column) }
            ?.let(cursor::getInt)
    }
