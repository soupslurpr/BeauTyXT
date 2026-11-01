package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.markdown.MarkdownBlockKind
import dev.soupslurpr.beautyxt.markdown.MarkdownPreviewDocument

internal class UnsupportedExcerptListStartException : IllegalArgumentException("Unsupported Markdown list start")

/** CommonMark derives later numbers, but a new group's marker accepts at most nine digits. */
internal fun requireSupportedExcerptListStarts(document: MarkdownPreviewDocument) {
    if (document.blocks.any { block ->
        block.kind == MarkdownBlockKind.ListItem && block.isOrderedListItem &&
            block.startsList && block.listNumber > 999_999_999L
    }) throw UnsupportedExcerptListStartException()
}
