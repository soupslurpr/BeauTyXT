package dev.soupslurpr.beautyxt.ui

import dev.soupslurpr.beautyxt.document.DocumentFormat
import dev.soupslurpr.beautyxt.document.resolveDocumentFormat
import dev.soupslurpr.beautyxt.importing.client.SelectedDocumentMetadata
import dev.soupslurpr.beautyxt.sharing.IncomingSourcePurpose
import dev.soupslurpr.beautyxt.ui.editor.EditorPresentation

/** Selects reading for recognized incoming Markdown unless editing was requested. */
internal fun incomingEditorPresentation(
    format: DocumentFormat,
    purpose: IncomingSourcePurpose = IncomingSourcePurpose.Share,
    metadata: SelectedDocumentMetadata? = null
): EditorPresentation {
    val resolvedFormat = resolveDocumentFormat(metadata?.displayName, metadata?.mimeType) ?: format
    return if (resolvedFormat == DocumentFormat.Markdown && purpose != IncomingSourcePurpose.Edit) {
        EditorPresentation.MarkdownPreview
    } else {
        EditorPresentation.Text
    }
}
