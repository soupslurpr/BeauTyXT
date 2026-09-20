package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.markdown.client.MarkdownRenderer
import dev.soupslurpr.beautyxt.printing.MarkdownPrintDocumentContent
import dev.soupslurpr.beautyxt.printing.PrintContentMode
import dev.soupslurpr.beautyxt.printing.PrintDocumentContent
import dev.soupslurpr.beautyxt.printing.SourcePrintDocumentContent

/** Consumes one exact capture: source printing owns it; formatted printing releases it here. */
internal suspend fun prepareEditorPrintContent(
    captured: CapturedDocumentRevision,
    mode: PrintContentMode,
    markdownRenderer: MarkdownRenderer?
): PrintDocumentContent {
    if (mode == PrintContentMode.Source) {
        return try {
            SourcePrintDocumentContent(captured.metrics, captured.snapshot)
        } catch (failure: Throwable) {
            runCatching(captured::close).exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }
    return captured.use {
        val renderer = checkNotNull(markdownRenderer) { "Markdown renderer is unavailable" }
        MarkdownPrintDocumentContent(
            renderer.render(captured.snapshot, captured.metrics.serializedByteLength)
        )
    }
}
