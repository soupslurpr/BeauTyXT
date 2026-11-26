package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.importing.client.SelectedDocumentSource
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Retains one selected source that cannot safely receive autosaves or source removal.
 *
 * Read-only imports intentionally do not implement [RemovableEditorDocumentSource] so external
 * views, shares, downgraded edits, and read-only picker results cannot invoke trash/delete through
 * an ambient tree write grant.
 */
internal class SelectedReadOnlyEditorDocumentSource private constructor(
    private val selectedSource: SelectedDocumentSource,
    private val lease: AutoCloseable?
) : EditorDocumentSource {
    private val closed = AtomicBoolean(false)

    /** Returns whether this source retains the exact encoded content URI. */
    override fun matchesSourceUri(encodedUri: String): Boolean =
        selectedSource.matchesUri(encodedUri)

    /** Returns the exact encoded source URI for a user-initiated temporary grant. */
    override fun encodedShareUri(): String = selectedSource.uri.toString()

    override val fileIdentityKey: String?
        get() = selectedSource.fileIdentityKey

    /** Forgets the selected source capability and releases any tree lease when the viewer closes. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var failure: Throwable? = null
        try {
            selectedSource.close()
        } catch (closeFailure: Throwable) {
            failure = closeFailure
        }
        try {
            lease?.close()
        } catch (closeFailure: Throwable) {
            if (failure == null) failure = closeFailure else failure.addSuppressed(closeFailure)
        }
        failure?.let { throw it }
    }

    companion object {
        /** Takes an imported source that already has an exact byte version. */
        fun takeImportedOwnership(
            selectedSource: SelectedDocumentSource,
            lease: AutoCloseable? = null
        ): SelectedReadOnlyEditorDocumentSource {
            requireNotNull(selectedSource.sourceVersion) {
                "imported document source requires an exact version"
            }
            return SelectedReadOnlyEditorDocumentSource(selectedSource, lease)
        }
    }
}
