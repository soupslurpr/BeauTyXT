package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.ui.text.TextRange
import dev.soupslurpr.beautyxt.document.Utf16Range
import dev.soupslurpr.beautyxt.illustration.IllustrationKind
import dev.soupslurpr.beautyxt.illustration.IllustrationResult
import dev.soupslurpr.beautyxt.markdown.MARKDOWN_SPAN_STYLE_MATH
import dev.soupslurpr.beautyxt.markdown.MarkdownBlockKind
import dev.soupslurpr.beautyxt.markdown.MarkdownInlineDestinationKind
import dev.soupslurpr.beautyxt.markdown.MarkdownInlinePresentation
import dev.soupslurpr.beautyxt.markdown.MarkdownInlineSpan
import dev.soupslurpr.beautyxt.markdown.MarkdownPreviewDocument
import dev.soupslurpr.beautyxt.markdown.MarkdownRenderBlock
import dev.soupslurpr.beautyxt.markdown.markdownFootnoteNumbers

/** Positions are independent of composed items, layout width, and transient Android selection. */
internal data class ReadingPoint(val block: Int, val offset: Int, val markerOffset: Int = 0) : Comparable<ReadingPoint> {
    override fun compareTo(other: ReadingPoint) = compareValuesBy(this, other,
        ReadingPoint::block, ReadingPoint::offset, ReadingPoint::markerOffset)
}

/** Interior digits of a generated footnote number retain their own displayed positions. */
internal fun readingPresentationPoint(block: Int, base: Int, offset: Int, sourceText: String,
    sourceSpans: List<MarkdownInlineSpan>, presentation: MarkdownInlinePresentation?): ReadingPoint {
    val marker = presentation?.spans?.indexOfFirst {
        it.destinationKind == MarkdownInlineDestinationKind.FootnoteReference && offset > it.start && offset < it.end
    } ?: -1
    if (marker >= 0) return ReadingPoint(block, base + sourceSpans[marker].start,
        offset - presentation!!.spans[marker].start)
    return ReadingPoint(block, base + (presentation?.sourceUtf16OffsetForPresentation(offset, sourceText.length, sourceSpans) ?: offset))
}

internal fun readingPresentationOffset(point: ReadingPoint, base: Int, sourceText: String,
    sourceSpans: List<MarkdownInlineSpan>, presentation: MarkdownInlinePresentation?): Int =
    (presentation?.presentationUtf16OffsetForSource(point.offset - base, sourceText.length, sourceSpans)
        ?: (point.offset - base)) + point.markerOffset

/** Generated list markers precede body offset zero, so body/source positions remain stable. */
internal fun readingListPrefix(block: MarkdownRenderBlock): String = when {
    block.kind != MarkdownBlockKind.ListItem || block.continuesPrevious || block.continuesListItem ||
        block.isTaskChecked || block.isTaskUnchecked -> ""
    block.isOrderedListItem -> "${block.listNumber}. "
    else -> "• "
}

internal fun readingBlockSeparator(previous: MarkdownRenderBlock, block: MarkdownRenderBlock): String = when {
    block.continuesPrevious -> ""
    block.kind == MarkdownBlockKind.TableRow && previous.kind == block.kind && !block.startsTable -> "\n"
    block.kind == MarkdownBlockKind.ListItem && previous.kind == block.kind && !block.continuesListItem &&
        (!block.startsList || block.listDepth > previous.listDepth) &&
        block.quoteDepth == previous.quoteDepth && block.quoteKind == previous.quoteKind -> "\n"
    else -> "\n\n"
}

/** Transport fragments belong to one paragraph, regardless of their bounded rendering size. */
internal fun readingAdjacentParagraphBoundary(blocks: List<MarkdownRenderBlock>, focus: ReadingPoint,
    forward: Boolean): ReadingPoint {
    fun first(index: Int): Int {
        var start = index
        while (start > 0 && blocks[start].continuesPrevious) start--
        return start
    }
    fun last(index: Int): Int {
        var end = index
        while (end < blocks.lastIndex && blocks[end + 1].continuesPrevious) end++
        return end
    }
    val target = if (forward) {
        var end = last((last(focus.block) + 1).coerceAtMost(blocks.lastIndex))
        // A joined illustration is displayed entirely by its first fragment.
        while (end > 0 && blocks[end].illustrationContinuation) end--
        end
    } else first((first(focus.block) - 1).coerceAtLeast(0))
    return ReadingPoint(target, if (forward) blocks[target].text.length else -readingListPrefix(blocks[target]).length)
}

internal sealed interface DocumentSelection {
    val revision: Long
    data class Source(override val revision: Long, val anchor: Long, val focus: Long) : DocumentSelection {
        val range get() = Utf16Range(minOf(anchor, focus), maxOf(anchor, focus))
    }
    data class Reading(override val revision: Long, val anchor: ReadingPoint, val focus: ReadingPoint) : DocumentSelection {
        val start get() = minOf(anchor, focus)
        val end get() = maxOf(anchor, focus)
    }
    data class Label(override val revision: Long, val block: Int, val spanStart: Int, val run: Int,
        val text: String, val range: TextRange = TextRange(0, text.length)) : DocumentSelection
}

internal fun DocumentSelection.Reading.rangeFor(block: Int, length: Int): TextRange? {
    if (block !in start.block..end.block) return null
    val from = (if (block == start.block) start.offset else 0).coerceIn(0, length)
    val to = (if (block == end.block) end.offset + if (end.markerOffset > 0) 1 else 0 else length).coerceIn(0, length)
    return if (from < to) TextRange(from, to) else null
}

internal fun DocumentSelection.exactSource(document: MarkdownPreviewDocument?): Utf16Range? { return when (this) {
    is DocumentSelection.Source -> range
    is DocumentSelection.Label -> null
    is DocumentSelection.Reading -> document?.let { model ->
        if (start.markerOffset != 0 || end.markerOffset != 0) return null
        val first = model.blocks.getOrNull(start.block) ?: return null
        val last = model.blocks.getOrNull(end.block) ?: return null
        val beginning = exactReadingSourceOffset(first, start.offset, ending = false) ?: return null
        val ending = exactReadingSourceOffset(last, end.offset, ending = true) ?: return null
        if (beginning <= ending) Utf16Range(beginning, ending) else null
    }
} }

/** Whole illustrations are indivisible in passage selection; formulas never become TeX fragments. */
internal fun atomicReadingPoint(document: MarkdownPreviewDocument, point: ReadingPoint, ending: Boolean): ReadingPoint {
    val block = document.blocks[point.block]
    val offset = point.offset.coerceIn(-readingListPrefix(block).length, block.text.length)
    val marker = block.spans.firstOrNull { it.destinationKind == MarkdownInlineDestinationKind.FootnoteReference &&
        (it.start == offset && point.markerOffset > 0 || it.start < offset && it.end > offset) }
    if (marker != null) {
        if (offset > marker.start) return ReadingPoint(point.block, if (ending) marker.end else marker.start)
        val length = markdownFootnoteNumbers(document.blocks)[marker.destination]?.toString()?.length ?: 0
        return if (point.markerOffset >= length) ReadingPoint(point.block, marker.end)
        else point.copy(offset = marker.start, markerOffset = point.markerOffset.coerceAtLeast(0))
    }
    val span = block.spans.firstOrNull { it.start < offset && it.end > offset &&
        (it.styles and MARKDOWN_SPAN_STYLE_MATH != 0 || it.illustration != null) }
    return point.copy(offset = if (span == null) offset else if (ending) span.end else span.start, markerOffset = 0)
}

internal class SelectionLimitException : IllegalArgumentException("Selected output exceeds its destination limit")

/** A bounded builder checks the complete UTF-8 payload; it never publishes a truncated excerpt. */
internal class ExcerptText(private val maximumBytes: Int) {
    private val text = StringBuilder()
    private var bytes = 0
    fun append(value: String) {
        if (value.length > maximumBytes - bytes) throw SelectionLimitException()
        val count = value.toByteArray(Charsets.UTF_8).size
        if (count > maximumBytes - bytes) throw SelectionLimitException()
        bytes += count
        text.append(value)
    }
    override fun toString() = text.toString()
}

internal fun excerptFence(text: String, language: String): String {
    var longest = 0
    var current = 0
    text.forEach { if (it == '`') { current++; longest = maxOf(longest, current) } else current = 0 }
    val fence = "`".repeat(maxOf(3, longest + 1))
    return "$fence$language\n$text${if (text.endsWith('\n')) "" else "\n"}$fence"
}

/** Displayed text only: notes, table headers, and link destinations are never imported here. */
internal fun selectedReadingText(document: MarkdownPreviewDocument, selection: DocumentSelection.Reading, maximumBytes: Int): String {
    val output = ExcerptText(maximumBytes)
    val numbers = markdownFootnoteNumbers(document.blocks)
    var previous: MarkdownRenderBlock? = null
    for (index in selection.start.block..selection.end.block) {
        val block = document.blocks[index]
        if (block.illustrationContinuation) continue
        val selected = selection.rangeFor(index, block.text.length)
        val last = previous
        if (last != null) output.append(readingBlockSeparator(last, block))
        val prefix = readingListPrefix(block)
        val prefixStart = if (index == selection.start.block) selection.start.offset.coerceIn(-prefix.length, 0) else -prefix.length
        val prefixEnd = if (index == selection.end.block) selection.end.offset.coerceIn(-prefix.length, 0) else 0
        if (prefixStart < prefixEnd) output.append(prefix.substring(prefixStart + prefix.length, prefixEnd + prefix.length))
        previous = block
        if (selected == null) continue
        val atomic = block.spans.filter { it.illustration != null || it.styles and MARKDOWN_SPAN_STYLE_MATH != 0 ||
            it.destinationKind == MarkdownInlineDestinationKind.FootnoteReference }
        var cursor = selected.min
        atomic.sortedBy { it.start }.forEach { span ->
            if (span.start >= selected.max || span.end <= selected.min) return@forEach
            if (cursor < span.start) output.append(block.text.substring(cursor, span.start))
            if (span.destinationKind == MarkdownInlineDestinationKind.FootnoteReference) {
                val number = numbers[span.destination]?.toString()
                if (number == null) output.append(block.text.substring(maxOf(span.start, selected.min), minOf(span.end, selected.max)))
                else {
                    val from = if (selection.start.block == index && selection.start.offset == span.start) selection.start.markerOffset else 0
                    val to = if (selection.end.block == index && selection.end.offset == span.start) selection.end.markerOffset else number.length
                    output.append(number.substring(from.coerceIn(0, number.length), to.coerceIn(0, number.length)))
                }
            } else {
                if (span.start > selected.min) output.append("\n\n")
                output.append(excerptFence(block.text.substring(span.start, span.end),
                    if (span.illustration?.kind == IllustrationKind.Diagram) "mermaid" else "tex"))
                if (span.end < selected.max) output.append("\n\n")
            }
            cursor = minOf(span.end, selected.max)
        }
        if (cursor < selected.max) output.append(block.text.substring(cursor, selected.max))
    }
    return output.toString()
}
