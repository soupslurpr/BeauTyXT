package dev.soupslurpr.beautyxt.ui.editor

import android.content.Context
import android.net.Uri
import dev.soupslurpr.beautyxt.document.DocumentFormat
import dev.soupslurpr.beautyxt.exporting.client.IsolatedDocumentExporter
import dev.soupslurpr.beautyxt.importing.client.ImportedDocument
import dev.soupslurpr.beautyxt.importing.client.ImportedSourceAccess
import dev.soupslurpr.beautyxt.importing.client.SelectedDocumentSource
import dev.soupslurpr.beautyxt.markdown.client.MarkdownRenderer
import dev.soupslurpr.beautyxt.sharing.IncomingSourcePurpose
import dev.soupslurpr.beautyxt.transfer.client.NfcTransferProcessor
import dev.soupslurpr.beautyxt.transfer.client.QrTransferProcessor
import dev.soupslurpr.beautyxt.ui.autosave.LeasedEditorDocumentSource
import dev.soupslurpr.beautyxt.ui.incomingEditorPresentation
import java.util.UUID

/** Selects whether one imported provider capability may become an autosave source. */
internal enum class ImportedSourcePolicy {
    /** Uses a compatible provider write capability when one was verified. */
    PreferWritable,

    /** Keeps the imported source read-only regardless of provider capabilities. */
    ReadOnly
}

/** Returns whether one imported source may become a writable editor source. */
internal fun shouldUseWritableImportedSource(
    sourceAccess: ImportedSourceAccess,
    sourcePolicy: ImportedSourcePolicy
): Boolean = sourcePolicy == ImportedSourcePolicy.PreferWritable &&
    sourceAccess == ImportedSourceAccess.ReadWrite

/** Takes one imported document and source into a complete editor session. */
internal suspend fun createImportedEditorSession(
    importedDocument: ImportedDocument,
    exporter: IsolatedDocumentExporter,
    markdownRenderer: MarkdownRenderer,
    qrTransferProcessor: QrTransferProcessor,
    nfcTransferProcessor: NfcTransferProcessor?,
    sourcePolicy: ImportedSourcePolicy = ImportedSourcePolicy.PreferWritable,
    sourceFormat: DocumentFormat = DocumentFormat.PlainText,
    sourcePurpose: IncomingSourcePurpose = IncomingSourcePurpose.Edit,
    readOnlyImporter: (suspend (Uri) -> ImportedDocument)? = null,
    treeGrantLease: AutoCloseable? = null,
    context: Context? = null,
    writableRegistry: WritableDocumentRegistry = WritableDocumentRegistry.shared,
    replacesWritableOwnerId: UUID? = null
): EditorSession {
    var documentOwner: ImportedDocument? = importedDocument
    var selectedSource: SelectedDocumentSource? = null
    var editorSource: EditorDocumentSource? = null
    var unclaimedLease: AutoCloseable? = treeGrantLease
    var unclaimedWriteLease: WritableDocumentLease? = null
    try {
        selectedSource = importedDocument.takeSelectedSource()
        val observedIdentityKey = selectedSource.fileIdentityKey
        val wantsWritable =
            shouldUseWritableImportedSource(
                sourceAccess = importedDocument.sourceAccess,
                sourcePolicy = sourcePolicy
            ) && (context == null || !observedIdentityKey.isNullOrBlank())
        if (wantsWritable) {
            val keys = buildSet {
                add(writableDocumentKey(context, selectedSource.uri))
                observedIdentityKey?.let(::add)
            }
            val probeUri = selectedSource.uri
            val probeContext = context?.applicationContext
            unclaimedWriteLease = writableRegistry.tryAcquireRefreshing(
                documentKeys = keys,
                replacesOwnerId = replacesWritableOwnerId,
                liveProbe = probeContext?.let { app -> { probeWritableFileIdentity(app, probeUri) } }
            )
        }
        editorSource =
            if (wantsWritable && unclaimedWriteLease != null) {
                val ownedSource = SelectedEditorDocumentSource.takeImportedOwnership(
                    selectedSource = checkNotNull(selectedSource),
                    exporter = exporter,
                    readOnlyImporter = readOnlyImporter,
                    writeLease = unclaimedWriteLease
                )
                val combinedLease = combineCloseables(unclaimedLease, unclaimedWriteLease)
                unclaimedLease = null
                unclaimedWriteLease = null
                if (combinedLease != null) {
                    LeasedEditorDocumentSource(ownedSource, combinedLease)
                } else {
                    ownedSource
                }
            } else {
                val lease = unclaimedLease
                unclaimedLease = null
                SelectedReadOnlyEditorDocumentSource.takeImportedOwnership(
                    selectedSource = checkNotNull(selectedSource),
                    lease = lease
                )
            }
        selectedSource = null
        val editor =
            EditorSession.takeOwnership(
                document = checkNotNull(documentOwner),
                documentSource = editorSource,
                title = importedDocument.displayName,
                sourceMetadata = importedDocument.metadata,
                sourceFormat = sourceFormat,
                initialPresentation = incomingEditorPresentation(
                    format = sourceFormat,
                    purpose = sourcePurpose,
                    metadata = importedDocument.metadata
                ),
                markdownRenderer = markdownRenderer,
                qrTransferProcessor = qrTransferProcessor,
                nfcTransferProcessor = nfcTransferProcessor
            )
        documentOwner = null
        editorSource = null
        unclaimedLease?.close()
        unclaimedLease = null
        unclaimedWriteLease?.close()
        unclaimedWriteLease = null
        return editor
    } catch (failure: Throwable) {
        closeAfterFailedImportTransfer(unclaimedWriteLease, failure)
        closeAfterFailedImportTransfer(unclaimedLease, failure)
        closeAfterFailedImportTransfer(editorSource, failure)
        closeAfterFailedImportTransfer(selectedSource, failure)
        closeAfterFailedImportTransfer(documentOwner, failure)
        throw failure
    }
}

/** Closes one failed transfer owner while preserving its primary failure. */
private fun closeAfterFailedImportTransfer(resource: AutoCloseable?, primaryFailure: Throwable) {
    try {
        resource?.close()
    } catch (cleanupFailure: Throwable) {
        primaryFailure.addSuppressed(cleanupFailure)
    }
}
