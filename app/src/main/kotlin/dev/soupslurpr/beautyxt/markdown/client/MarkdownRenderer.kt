package dev.soupslurpr.beautyxt.markdown.client

import dev.soupslurpr.beautyxt.document.EditorDocumentSnapshot
import dev.soupslurpr.beautyxt.markdown.MarkdownPreviewDocument

/** Renders one immutable document snapshot into a bounded Markdown model. */
internal fun interface MarkdownRenderer {
    /** Borrows one snapshot for the duration of rendering its serialized bytes. */
    suspend fun render(
        snapshot: EditorDocumentSnapshot,
        expectedBytes: Long
    ): MarkdownPreviewDocument

    /** Preview can publish semantic text before optional illustration appearances are ready. */
    suspend fun renderPreview(
        snapshot: EditorDocumentSnapshot,
        expectedBytes: Long
    ): MarkdownPreviewDocument = render(snapshot, expectedBytes)
}
