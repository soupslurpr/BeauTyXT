package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.document.DocumentRemovalAction
import dev.soupslurpr.beautyxt.document.DocumentRemovalCapabilities
import dev.soupslurpr.beautyxt.importing.client.SelectedDocumentSource

/** Retains one selected source that cannot safely receive autosaves. */
internal class SelectedReadOnlyEditorDocumentSource private constructor(
    private val selectedSource: SelectedDocumentSource
) : RemovableEditorDocumentSource {
    /** Returns whether this source retains the exact encoded content URI. */
    override fun matchesSourceUri(encodedUri: String): Boolean =
        selectedSource.matchesUri(encodedUri)

    /** Returns the exact encoded source URI for a user-initiated temporary grant. */
    override fun encodedShareUri(): String = selectedSource.uri.toString()

    /** Returns the source provider's live trash and deletion capabilities. */
    override suspend fun queryRemovalCapabilities(): DocumentRemovalCapabilities =
        selectedSource.queryRemovalCapabilities()

    /** Completes one provider-authorized source removal operation. */
    override suspend fun remove(action: DocumentRemovalAction) {
        selectedSource.remove(action)
    }

    /** Forgets the selected source capability when its viewer closes. */
    override fun close() {
        selectedSource.close()
    }

    companion object {
        /** Takes an imported source that already has an exact byte version. */
        fun takeImportedOwnership(
            selectedSource: SelectedDocumentSource
        ): SelectedReadOnlyEditorDocumentSource {
            requireNotNull(selectedSource.sourceVersion) {
                "imported document source requires an exact version"
            }
            return SelectedReadOnlyEditorDocumentSource(selectedSource)
        }
    }
}
