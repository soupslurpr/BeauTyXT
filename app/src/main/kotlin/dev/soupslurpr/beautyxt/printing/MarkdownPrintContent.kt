package dev.soupslurpr.beautyxt.printing

import dev.soupslurpr.beautyxt.markdown.MarkdownBlockKind
import dev.soupslurpr.beautyxt.markdown.MarkdownInlineSpan
import dev.soupslurpr.beautyxt.markdown.MarkdownRenderBlock
import dev.soupslurpr.beautyxt.markdown.MarkdownTableAlignment
import dev.soupslurpr.beautyxt.markdown.markdownFootnotePresentation

private const val MARKDOWN_TABLE_CELL_SEPARATOR = "  |  "
private const val MAXIMUM_SEMANTIC_MARKDOWN_PRINT_TABLE_COLUMNS = 16
private const val MINIMUM_MARKDOWN_PRINT_CELL_CONTENT_PIXELS = 1

/** Contains printable Markdown text and its presentation-relative spans. */
internal data class MarkdownPrintContent(val text: String, val spans: List<MarkdownInlineSpan>)

/** Contains one printable table cell and its presentation-relative spans. */
internal data class MarkdownPrintCell(
    val text: String,
    val spans: List<MarkdownInlineSpan>,
    val alignment: MarkdownTableAlignment
)

/** Builds printable block content with numbered footnotes and visible table separators. */
internal fun markdownPrintContent(
    block: MarkdownRenderBlock,
    footnoteNumbers: Map<String, Int> = emptyMap()
): MarkdownPrintContent {
    val footnotePresentation =
        markdownFootnotePresentation(block.text, block.spans, footnoteNumbers)
    val text = footnotePresentation?.text ?: block.text
    val spans = footnotePresentation?.spans ?: block.spans
    if (block.kind != MarkdownBlockKind.TableRow || '\t' !in text) {
        return MarkdownPrintContent(text = text, spans = spans)
    }
    val outputOffsets = IntArray(text.length + 1)
    val printableText =
        buildString(text.length) {
            text.forEachIndexed { sourceOffset, character ->
                outputOffsets[sourceOffset] = length
                if (character == '\t') {
                    append(MARKDOWN_TABLE_CELL_SEPARATOR)
                } else {
                    append(character)
                }
            }
            outputOffsets[text.length] = length
        }
    return MarkdownPrintContent(
        text = printableText,
        spans =
            spans.map { span ->
                span.copy(
                    start = outputOffsets[span.start],
                    end = outputOffsets[span.end]
                )
            }
    )
}

/** Returns one table row's exact number of semantic cells. */
internal fun markdownPrintTableColumnCount(block: MarkdownRenderBlock): Int {
    require(block.kind == MarkdownBlockKind.TableRow) { "Markdown block is not a table row" }
    return Math.addExact(block.text.count { character -> character == '\t' }, 1)
}

/** Returns whether a table can retain bounded equal-width semantic cells. */
internal fun supportsSemanticMarkdownPrintTable(
    columnCount: Int,
    availableWidthPixels: Int,
    horizontalPaddingPixels: Int
): Boolean {
    require(columnCount > 0) { "Markdown print table must contain a column" }
    require(availableWidthPixels > 0) { "Markdown print table width must be positive" }
    require(horizontalPaddingPixels >= 0) {
        "Markdown print table padding must be nonnegative"
    }
    return columnCount <= MAXIMUM_SEMANTIC_MARKDOWN_PRINT_TABLE_COLUMNS &&
        availableWidthPixels / columnCount - horizontalPaddingPixels * 2 >=
        MINIMUM_MARKDOWN_PRINT_CELL_CONTENT_PIXELS
}

/** Returns whether a table header should move to remain with its first row. */
internal fun shouldAdvanceMarkdownPrintTableHeader(
    bodyTopPixels: Int,
    bodyBottomPixels: Int,
    bodyY: Int,
    headerHeightPixels: Int,
    firstRowHeightPixels: Int
): Boolean {
    require(bodyTopPixels < bodyBottomPixels) { "Markdown print body must be nonempty" }
    require(bodyY in bodyTopPixels..bodyBottomPixels) {
        "Markdown print body position is outside the page"
    }
    require(headerHeightPixels > 0 && firstRowHeightPixels > 0) {
        "Markdown print table row heights must be positive"
    }
    val combinedHeight = Math.addExact(headerHeightPixels, firstRowHeightPixels)
    val bodyHeight = bodyBottomPixels - bodyTopPixels
    return combinedHeight <= bodyHeight &&
        combinedHeight > bodyBottomPixels - bodyY
}

/** Splits one table row into numbered, styled, and independently aligned print cells. */
internal fun markdownPrintCells(
    block: MarkdownRenderBlock,
    footnoteNumbers: Map<String, Int> = emptyMap()
): List<MarkdownPrintCell> {
    require(block.kind == MarkdownBlockKind.TableRow) { "Markdown block is not a table row" }
    val footnotePresentation =
        markdownFootnotePresentation(block.text, block.spans, footnoteNumbers)
    val text = footnotePresentation?.text ?: block.text
    val spans = footnotePresentation?.spans ?: block.spans
    val cells = ArrayList<MarkdownPrintCell>(markdownPrintTableColumnCount(block))
    var cellStart = 0
    while (cellStart <= text.length) {
        val separator = text.indexOf('\t', cellStart)
        val cellEnd = if (separator >= 0) separator else text.length
        val cellSpans =
            buildList {
                spans.forEach { span ->
                    val spanStart = maxOf(span.start, cellStart)
                    val spanEnd = minOf(span.end, cellEnd)
                    if (spanStart < spanEnd) {
                        add(
                            span.copy(
                                start = spanStart - cellStart,
                                end = spanEnd - cellStart
                            )
                        )
                    }
                }
            }
        cells +=
            MarkdownPrintCell(
                text = text.substring(cellStart, cellEnd),
                spans = cellSpans,
                alignment =
                    block.tableAlignments.getOrElse(cells.size) {
                        MarkdownTableAlignment.None
                    }
            )
        if (separator < 0) {
            break
        }
        cellStart = separator + 1
    }
    return cells
}
