/* Reassembles bounded semantic blocks without exposing transport fragments to print layout. */
package dev.soupslurpr.beautyxt.printing

import dev.soupslurpr.beautyxt.markdown.MARKDOWN_SPAN_STYLE_MATH
import dev.soupslurpr.beautyxt.markdown.MarkdownBlockKind
import dev.soupslurpr.beautyxt.markdown.MarkdownInlineDestinationKind
import dev.soupslurpr.beautyxt.markdown.MarkdownInlineSpan
import dev.soupslurpr.beautyxt.markdown.MarkdownRenderBlock
import dev.soupslurpr.beautyxt.markdown.MarkdownSourceRange
import dev.soupslurpr.beautyxt.markdown.MarkdownTableAlignment
import java.io.IOException

internal const val MAXIMUM_MARKDOWN_PRINT_BLOCK_UNITS = 64 * 1_024

/** Reports a semantic block that exceeds the bounded Android layout budget. */
internal class MarkdownPrintBlockLimitException :
    IOException("formatted print block exceeds the bounded layout limit")

/** Contains one semantic layout slice and its continuation within the transport model. */
internal data class MarkdownPrintBlock(
    val block: MarkdownRenderBlock,
    val nextIndex: Int,
    val nextOffset: Int = 0,
    val continuesAtEnd: Boolean = false
)

/** Joins one bounded block, rebasing styles and overlapping table-column metadata. */
internal suspend fun readMarkdownPrintBlock(
    blocks: List<MarkdownRenderBlock>,
    startIndex: Int,
    startOffset: Int = 0,
    maxBlockUtf16Units: Int = MAXIMUM_MARKDOWN_PRINT_BLOCK_UNITS,
    ensureActive: suspend () -> Unit = {}
): MarkdownPrintBlock {
    require(startIndex in blocks.indices) { "Markdown print block index is outside the document" }
    require(maxBlockUtf16Units in 1..MAXIMUM_MARKDOWN_PRINT_BLOCK_UNITS) {
        "Markdown print layout limit is outside its bounds"
    }
    val first = blocks[startIndex]
    val streamsLines =
        first.kind == MarkdownBlockKind.Code || first.kind == MarkdownBlockKind.HtmlLiteral
    require(startOffset in 0..first.text.length) { "Markdown print offset is outside the block" }
    require(streamsLines || (!first.continuesPrevious && startOffset == 0)) {
        "Markdown print block starts within a continuation"
    }
    if (first.continuesPrevious || startOffset > 0) {
        require(
            if (startOffset > 0) {
                first.text[startOffset - 1] == '\n'
            } else {
                startIndex > 0 && blocks[startIndex - 1].text.endsWith('\n')
            }
        ) { "Markdown print continuation starts within a logical line" }
    }
    var nextIndex = startIndex
    var nextOffset = startOffset
    var textLength = 0
    var lastBreakIndex = -1
    var lastBreakOffset = 0
    var lastBreakLength = 0
    var continuesAtEnd = false
    while (true) {
        ensureActive()
        val fragment = blocks[nextIndex]
        check(fragment.kind == first.kind) { "Markdown print continuation changed block kind" }
        val available = maxBlockUtf16Units - textLength
        val boundedEnd = minOf(fragment.text.length, nextOffset + available)
        if (streamsLines) {
            val lineBreak = fragment.text.lastIndexOf('\n', boundedEnd - 1)
            if (lineBreak >= nextOffset) {
                val afterBreak = lineBreak + 1
                lastBreakIndex =
                    if (afterBreak == fragment.text.length) nextIndex + 1 else nextIndex
                lastBreakOffset = if (afterBreak == fragment.text.length) 0 else afterBreak
                lastBreakLength = textLength + afterBreak - nextOffset
            }
        }
        if (boundedEnd < fragment.text.length) {
            if (lastBreakIndex < 0) throw MarkdownPrintBlockLimitException()
            nextIndex = lastBreakIndex
            nextOffset = lastBreakOffset
            textLength = lastBreakLength
            continuesAtEnd = true
            break
        }
        textLength += fragment.text.length - nextOffset
        nextIndex += 1
        nextOffset = 0
        if (nextIndex == blocks.size || !blocks[nextIndex].continuesPrevious) break
    }
    if (nextIndex == startIndex + 1 && nextOffset == 0 && startOffset == 0 && !continuesAtEnd) {
        return MarkdownPrintBlock(first, nextIndex)
    }

    val text = StringBuilder(textLength)
    val spans = ArrayList<MarkdownInlineSpan>()
    val alignments = ArrayList<MarkdownTableAlignment>()
    var columnOffset = 0
    val endExclusive = nextIndex + if (nextOffset > 0) 1 else 0
    for (fragmentIndex in startIndex until endExclusive) {
        ensureActive()
        val fragment = blocks[fragmentIndex]
        val start = if (fragmentIndex == startIndex) startOffset else 0
        val end = if (fragmentIndex == nextIndex) nextOffset else fragment.text.length
        val textOffset = text.length
        text.append(fragment.text, start, end)
        fragment.spans.forEach { span ->
            val clippedStart = maxOf(start, span.start)
            val clippedEnd = minOf(end, span.end)
            if (clippedStart >= clippedEnd) return@forEach
            val rebased = span.copy(
                start = textOffset + clippedStart - start,
                end = textOffset + clippedEnd - start,
                // An illustration describes its entire original span, never a clipped prefix.
                illustration = span.illustration.takeIf {
                    clippedStart == span.start && clippedEnd == span.end
                }
            )
            val previous = spans.lastOrNull()
            if (previous != null && previous.end == rebased.start &&
                previous.styles == rebased.styles && previous.destination == rebased.destination &&
                previous.destinationKind == rebased.destinationKind &&
                previous.illustration == null && rebased.illustration == null &&
                rebased.styles and MARKDOWN_SPAN_STYLE_MATH == 0 &&
                rebased.destinationKind != MarkdownInlineDestinationKind.FootnoteReference
            ) {
                spans[spans.lastIndex] = previous.copy(end = rebased.end)
            } else {
                spans += rebased
            }
        }
        if (first.kind == MarkdownBlockKind.TableRow) {
            val columnCount = markdownPrintTableColumnCount(fragment)
            for (localColumn in 0 until columnCount) {
                val alignment = fragment.tableAlignments.getOrElse(localColumn) {
                    MarkdownTableAlignment.None
                }
                val column = columnOffset + localColumn
                if (column < alignments.size) {
                    check(alignments[column] == alignment) {
                        "Markdown print continuation changed table alignment"
                    }
                } else {
                    check(column == alignments.size) { "Markdown print table skipped a column" }
                    alignments += alignment
                }
            }
            columnOffset += columnCount - 1
        }
    }
    return MarkdownPrintBlock(
        first.copy(
            text = text.toString(),
            continuesPrevious = first.continuesPrevious || startOffset > 0,
            spans = spans,
            source = MarkdownSourceRange(first.source.start, blocks[endExclusive - 1].source.end),
            sourceMaps = emptyList(),
            tableAlignments = alignments
        ),
        nextIndex,
        nextOffset,
        continuesAtEnd
    )
}
