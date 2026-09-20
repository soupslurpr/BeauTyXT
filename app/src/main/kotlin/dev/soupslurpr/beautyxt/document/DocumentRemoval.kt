package dev.soupslurpr.beautyxt.document

/** Selects one provider-owned destructive operation for an open document. */
internal enum class DocumentRemovalAction {
    /** Moves the document into its provider's recoverable trash. */
    Trash,

    /** Permanently deletes the document through its provider. */
    Delete
}

/** Describes the independent removal operations advertised by a document provider. */
internal data class DocumentRemovalCapabilities(val canTrash: Boolean, val canDelete: Boolean) {
    /** Returns whether the provider advertises one exact removal operation. */
    fun supports(action: DocumentRemovalAction): Boolean = when (action) {
        DocumentRemovalAction.Trash -> canTrash
        DocumentRemovalAction.Delete -> canDelete
    }

    companion object {
        /** Represents a source without an advertised removal operation. */
        val None = DocumentRemovalCapabilities(canTrash = false, canDelete = false)
    }
}
