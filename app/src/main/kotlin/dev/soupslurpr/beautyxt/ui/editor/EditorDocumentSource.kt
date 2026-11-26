package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.document.DocumentRemovalAction
import dev.soupslurpr.beautyxt.document.DocumentRemovalCapabilities
import dev.soupslurpr.beautyxt.document.EditorDocumentSnapshot
import dev.soupslurpr.beautyxt.document.SourceVersion
import dev.soupslurpr.beautyxt.exporting.client.DocumentExportException
import dev.soupslurpr.beautyxt.exporting.client.DocumentExportFailure

/** Holds one read-only external snapshot paired with the exact version observed during import. */
internal class ExternalSourceComparisonInput(
    val snapshot: EditorDocumentSnapshot,
    val sourceVersion: SourceVersion,
    private val owner: AutoCloseable? = null
) : AutoCloseable {
    override fun close() {
        var failure: Throwable? = null
        try {
            snapshot.close()
        } catch (closeFailure: Throwable) {
            failure = closeFailure
        }
        try {
            owner?.close()
        } catch (closeFailure: Throwable) {
            if (failure == null) failure = closeFailure else failure.addSuppressed(closeFailure)
        }
        failure?.let { throw it }
    }
}

/** Owns one retained user-selected document source. */
internal interface EditorDocumentSource : AutoCloseable {
    /** Returns whether this source retains the exact encoded content URI. */
    fun matchesSourceUri(encodedUri: String): Boolean

    /** Returns the exact encoded content URI that may receive a temporary read grant. */
    fun encodedShareUri(): String?

    /** Returns the kernel `(st_dev, st_ino)` identity key observed on this source, if any. */
    val fileIdentityKey: String?
        get() = null

    /** Returns the process-local write lease owner ID held by this source, if writable and leased. */
    val writableLeaseOwnerId: java.util.UUID?
        get() = null
}

/** Saves revisions to one compatible retained document source. */
internal interface WritableEditorDocumentSource : EditorDocumentSource {
    /** Saves one immutable revision and returns only after exact completion. */
    suspend fun saveRevision(snapshot: EditorDocumentSnapshot, expectedBytes: Long)
}

/** Recovers explicit conflicts for one externally mutable writable source. */
internal interface ConflictRecoverableEditorDocumentSource : WritableEditorDocumentSource {
    /** Reports whether this source can perform a fresh read-only import for conflict comparison. */
    val canCompareExternalSource: Boolean
        get() = false

    /** Opens a read-only snapshot and exact version of the external file without altering the baseline. */
    suspend fun openExternalForComparison(): ExternalSourceComparisonInput? = null

    /** Replaces the freshly inspected source with one immutable local revision. */
    suspend fun overwriteRevision(snapshot: EditorDocumentSnapshot, expectedBytes: Long)

    /** Replaces the source only if it still matches [reviewedExternalVersion], failing closed if unsupported. */
    suspend fun overwriteReviewedRevision(
        snapshot: EditorDocumentSnapshot,
        expectedBytes: Long,
        reviewedExternalVersion: SourceVersion
    ) {
        throw DocumentExportException(DocumentExportFailure.SOURCE_CONFLICT)
    }
}

/** Removes one retained source only through operations advertised by its provider. */
internal interface RemovableEditorDocumentSource : EditorDocumentSource {
    /** Returns the provider's live trash and permanent deletion capabilities. */
    suspend fun queryRemovalCapabilities(): DocumentRemovalCapabilities

    /** Completes one provider-authorized removal operation. */
    suspend fun remove(action: DocumentRemovalAction)
}
