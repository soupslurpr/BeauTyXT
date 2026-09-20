package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.document.DocumentRemovalAction
import dev.soupslurpr.beautyxt.document.DocumentRemovalCapabilities
import dev.soupslurpr.beautyxt.document.EditorDocumentSnapshot
import dev.soupslurpr.beautyxt.document.SourceVersion
import dev.soupslurpr.beautyxt.exporting.ExportProtocol
import dev.soupslurpr.beautyxt.exporting.client.DocumentExportException
import dev.soupslurpr.beautyxt.exporting.client.DocumentExportFailure
import dev.soupslurpr.beautyxt.exporting.client.SourceDocumentExporter
import dev.soupslurpr.beautyxt.exporting.client.StagedSourceSavePackage
import dev.soupslurpr.beautyxt.exporting.client.TransientSourceDescriptor
import dev.soupslurpr.beautyxt.importing.client.SelectedDocumentSource
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Saves revisions to one retained source through anonymous isolated staging. */
internal class SelectedEditorDocumentSource private constructor(
    private val selectedSource: SelectedDocumentSource,
    private val exporter: SourceDocumentExporter
) : ConflictRecoverableEditorDocumentSource,
    RemovableEditorDocumentSource {
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

    /** Stages one immutable revision before opening its destructive destination. */
    override suspend fun saveRevision(snapshot: EditorDocumentSnapshot, expectedBytes: Long) {
        StagedSourceSavePackage.capture(
            snapshot = snapshot,
            expectedBytes = expectedBytes
        ).use { stagedPackage ->
            withContext(NonCancellable) {
                saveStagedPackage(
                    stagedPackage = stagedPackage,
                    expectedSourceVersion = selectedSource.sourceVersion
                )
            }
        }
    }

    /** Inspects the current source before conditionally applying an explicit overwrite. */
    override suspend fun overwriteRevision(snapshot: EditorDocumentSnapshot, expectedBytes: Long) {
        StagedSourceSavePackage.capture(
            snapshot = snapshot,
            expectedBytes = expectedBytes
        ).use { stagedPackage ->
            withContext(NonCancellable) {
                val previousSourceVersion = selectedSource.sourceVersion
                val observedSourceVersion =
                    TransientSourceDescriptor.from(
                        selectedSource.openReadDescriptor()
                    ).use { sourceDescriptor ->
                        exporter.inspectSource(
                            source = sourceDescriptor,
                            maximumBytes = ExportProtocol.MAX_BYTE_LIMIT
                        )
                    }
                selectedSource.advanceSourceVersion(
                    expectedVersion = previousSourceVersion,
                    newVersion = observedSourceVersion
                )
                saveStagedPackage(
                    stagedPackage = stagedPackage,
                    expectedSourceVersion = observedSourceVersion
                )
            }
        }
    }

    /** Saves and verifies one staged package against an exact source baseline. */
    private suspend fun saveStagedPackage(
        stagedPackage: StagedSourceSavePackage,
        expectedSourceVersion: SourceVersion?
    ) {
        val receipt =
            TransientSourceDescriptor.from(
                selectedSource.openReadWriteDescriptor()
            ).use { sourceDescriptor ->
                exporter.saveSource(
                    source = sourceDescriptor,
                    stagedPackage = stagedPackage,
                    expectedSourceVersion = expectedSourceVersion
                )
            }
        try {
            TransientSourceDescriptor.from(
                selectedSource.openReadDescriptor()
            ).use { sourceDescriptor ->
                exporter.verifySource(
                    source = sourceDescriptor,
                    expectedSourceVersion = receipt.sourceVersion
                )
            }
            selectedSource.advanceSourceVersion(
                expectedVersion = expectedSourceVersion,
                newVersion = receipt.sourceVersion
            )
        } catch (failure: Exception) {
            throw DocumentExportException(
                DocumentExportFailure.SOURCE_UNCERTAIN,
                failure
            )
        } catch (failure: LinkageError) {
            throw DocumentExportException(
                DocumentExportFailure.SOURCE_UNCERTAIN,
                failure
            )
        }
    }

    /** Forgets the selected source capability when its editor closes. */
    override fun close() {
        selectedSource.close()
    }

    companion object {
        /** Takes an imported source that already has an exact byte version. */
        fun takeImportedOwnership(
            selectedSource: SelectedDocumentSource,
            exporter: SourceDocumentExporter
        ): SelectedEditorDocumentSource {
            requireNotNull(selectedSource.sourceVersion) {
                "imported document source requires an exact version"
            }
            return SelectedEditorDocumentSource(
                selectedSource = selectedSource,
                exporter = exporter
            )
        }

        /** Takes a newly created source that has no prior save baseline. */
        fun takeCreatedOwnership(
            selectedSource: SelectedDocumentSource,
            exporter: SourceDocumentExporter
        ): SelectedEditorDocumentSource {
            require(selectedSource.sourceVersion == null) {
                "created document source already has an exact version"
            }
            return SelectedEditorDocumentSource(
                selectedSource = selectedSource,
                exporter = exporter
            )
        }
    }
}
