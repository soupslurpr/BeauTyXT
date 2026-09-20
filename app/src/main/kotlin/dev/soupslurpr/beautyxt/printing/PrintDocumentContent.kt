package dev.soupslurpr.beautyxt.printing

import dev.soupslurpr.beautyxt.document.DocumentMetrics
import dev.soupslurpr.beautyxt.document.EditorDocumentSnapshot
import dev.soupslurpr.beautyxt.markdown.MarkdownPreviewDocument
import java.util.concurrent.atomic.AtomicBoolean

/** Owns one exact source or semantic document representation for printing. */
internal sealed interface PrintDocumentContent : AutoCloseable

/** Owns one immutable source revision for bounded streaming during printing. */
internal class SourcePrintDocumentContent(
    val metrics: DocumentMetrics,
    val snapshot: EditorDocumentSnapshot
) : PrintDocumentContent {
    private val closed = AtomicBoolean(false)

    init {
        require(metrics.revision >= 0L) { "printed document revision must be nonnegative" }
    }

    /** Closes the exact native revision once its print lifecycle ends. */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            snapshot.close()
        }
    }
}

/** Owns one bounded semantic Markdown model without retaining its source snapshot. */
internal class MarkdownPrintDocumentContent(val document: MarkdownPreviewDocument) :
    PrintDocumentContent {
    /** Releases no external capability because the model is ordinary bounded memory. */
    override fun close() = Unit
}
