package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.document.DocumentRemovalAction
import dev.soupslurpr.beautyxt.document.DocumentRemovalCapabilities
import dev.soupslurpr.beautyxt.document.EditorDocumentSnapshot

/** Owns one retained user-selected document source. */
internal interface EditorDocumentSource : AutoCloseable {
    /** Returns whether this source retains the exact encoded content URI. */
    fun matchesSourceUri(encodedUri: String): Boolean

    /** Returns the exact encoded content URI that may receive a temporary read grant. */
    fun encodedShareUri(): String?
}

/** Saves revisions to one compatible retained document source. */
internal interface WritableEditorDocumentSource : EditorDocumentSource {
    /** Saves one immutable revision and returns only after exact completion. */
    suspend fun saveRevision(snapshot: EditorDocumentSnapshot, expectedBytes: Long)
}

/** Recovers explicit conflicts for one externally mutable writable source. */
internal interface ConflictRecoverableEditorDocumentSource : WritableEditorDocumentSource {
    /** Replaces the freshly inspected source with one immutable local revision. */
    suspend fun overwriteRevision(snapshot: EditorDocumentSnapshot, expectedBytes: Long)
}

/** Removes one retained source only through operations advertised by its provider. */
internal interface RemovableEditorDocumentSource : EditorDocumentSource {
    /** Returns the provider's live trash and permanent deletion capabilities. */
    suspend fun queryRemovalCapabilities(): DocumentRemovalCapabilities

    /** Completes one provider-authorized removal operation. */
    suspend fun remove(action: DocumentRemovalAction)
}
