/* Admits bounded logical headings before building PDF destinations. */
package dev.soupslurpr.beautyxt.printing

import dev.soupslurpr.beautyxt.markdown.MarkdownBlockKind
import dev.soupslurpr.beautyxt.markdown.MarkdownHeadingIndex
import dev.soupslurpr.beautyxt.markdown.MarkdownPreviewHeadingItem
import dev.soupslurpr.beautyxt.markdown.MarkdownRenderBlock

/** Joins each admitted heading once, with cancellation during grouping and anchor indexing. */
internal suspend fun markdownPrintHeadingAnchors(
    blocks: List<MarkdownRenderBlock>,
    maxBlockUtf16Units: Int = MAXIMUM_MARKDOWN_PRINT_BLOCK_UNITS,
    checkActive: () -> Unit = {}
): Map<Int, String> {
    val headings = ArrayList<MarkdownPreviewHeadingItem>()
    var index = 0
    while (index < blocks.size) {
        checkActive()
        if (blocks[index].kind != MarkdownBlockKind.Heading) {
            index++
            continue
        }
        val heading = readMarkdownPrintBlock(
            blocks, index, maxBlockUtf16Units = maxBlockUtf16Units,
            ensureActive = { checkActive() }
        )
        headings += MarkdownPreviewHeadingItem(index, true, heading.block.text)
        index = heading.nextIndex
    }
    val items = headings.asSequence().onEach { checkActive() }.asIterable()
    return MarkdownHeadingIndex(items).destinations.entries.associate {
        checkActive()
        it.value to it.key
    }
}
