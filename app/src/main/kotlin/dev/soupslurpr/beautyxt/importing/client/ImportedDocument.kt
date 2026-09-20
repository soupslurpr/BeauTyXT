package dev.soupslurpr.beautyxt.importing.client

import dev.soupslurpr.beautyxt.document.EditorDocument
import dev.soupslurpr.beautyxt.document.RustDocument

/** Describes whether an imported provider source can receive autosaves. */
internal enum class ImportedSourceAccess {
    /** Indicates that a compatible autosave descriptor was available during import. */
    ReadWrite,

    /** Indicates that only reading was safely available during import. */
    ReadOnly
}

/** Owns one imported Rust document, its transient source, and session-only provider facts. */
internal class ImportedDocument private constructor(
    private val document: RustDocument,
    selectedSource: SelectedDocumentSource,
    /** Describes the provider access verified during import. */
    val sourceAccess: ImportedSourceAccess,
    /** Contains sanitized provider metadata only for this in-memory document lifetime. */
    val metadata: SelectedDocumentMetadata
) : EditorDocument by document {
    private val ownershipLock = Any()
    private var selectedSource: SelectedDocumentSource? = selectedSource
    private var closed = false

    /** Returns the sanitized provider title for the current session. */
    val displayName: String?
        get() = metadata.displayName

    /** Transfers the selected-source capability exactly once to the document session. */
    fun takeSelectedSource(): SelectedDocumentSource = synchronized(ownershipLock) {
        check(!closed) { "imported document is closed" }
        checkNotNull(selectedSource) { "selected document source was already transferred" }
            .also { selectedSource = null }
    }

    /** Closes the document and every selected-source capability still owned here. */
    override fun close() {
        val sourceToClose =
            synchronized(ownershipLock) {
                if (closed) {
                    return
                }
                closed = true
                selectedSource.also { selectedSource = null }
            }
        var failure: Throwable? = null
        try {
            document.close()
        } catch (closeFailure: Throwable) {
            failure = closeFailure
        }
        try {
            sourceToClose?.close()
        } catch (closeFailure: Throwable) {
            if (failure == null) {
                failure = closeFailure
            } else {
                failure.addSuppressed(closeFailure)
            }
        }
        failure?.let { throw it }
    }

    companion object {
        /** Takes ownership of one imported document and its selected source. */
        fun takeOwnership(
            document: RustDocument,
            selectedSource: SelectedDocumentSource,
            sourceAccess: ImportedSourceAccess,
            metadata: SelectedDocumentMetadata = SelectedDocumentMetadata()
        ): ImportedDocument {
            checkNotNull(selectedSource.sourceVersion) {
                "imported document source has no exact version"
            }
            return ImportedDocument(document, selectedSource, sourceAccess, metadata)
        }
    }
}
