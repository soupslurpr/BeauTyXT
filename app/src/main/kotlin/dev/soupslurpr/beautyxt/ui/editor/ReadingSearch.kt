package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.document.SearchCompletion
import dev.soupslurpr.beautyxt.document.SearchCursor
import dev.soupslurpr.beautyxt.document.SearchHit
import dev.soupslurpr.beautyxt.document.SearchPage
import dev.soupslurpr.beautyxt.document.Utf16Range
import dev.soupslurpr.beautyxt.document.isScalarBoundary
import dev.soupslurpr.beautyxt.illustration.IllustrationKind
import dev.soupslurpr.beautyxt.illustration.IllustrationResult
import dev.soupslurpr.beautyxt.illustration.IllustrationTextBox
import dev.soupslurpr.beautyxt.markdown.MARKDOWN_SPAN_STYLE_MATH
import dev.soupslurpr.beautyxt.markdown.MarkdownBlockKind
import dev.soupslurpr.beautyxt.markdown.MarkdownInlineDestinationKind
import dev.soupslurpr.beautyxt.markdown.MarkdownPreviewDocument
import dev.soupslurpr.beautyxt.markdown.MarkdownRenderBlock
import dev.soupslurpr.beautyxt.markdown.markdownFootnoteNumbers
import dev.soupslurpr.beautyxt.markdown.markdownTableCellRanges

internal enum class SearchRepresentation { Source, Reading, FormulaSource, DiagramSource, Formula, DiagramLabel }
internal data class ReadingSearchSegment(
    val block: Int, val blockStart: Int, val start: Int, val end: Int,
    val blockEnd: Int = blockStart + end - start, val exact: Boolean = true,
    val generatedMarker: Boolean = false, val markerStart: Int = 0, val markerEnd: Int = 0
)
internal data class IllustrationSearchTarget(
    val block: Int, val start: Int, val end: Int, val run: Int?,
    val boxes: List<IllustrationTextBox> = emptyList()
)
internal data class ReadingSearchUnit(
    val text: String,
    val segments: List<ReadingSearchSegment>,
    val representation: SearchRepresentation = SearchRepresentation.Reading,
    val illustration: IllustrationSearchTarget? = null
)
internal data class DocumentSearchResult(
    val hit: SearchHit,
    val source: Utf16Range?,
    val navigationOffset: Long,
    val representation: SearchRepresentation,
    val segments: List<ReadingSearchSegment> = emptyList(),
    val illustration: IllustrationSearchTarget? = null,
    val ownerSource: Utf16Range? = null,
    val alsoMatchesSource: Boolean = false
)
internal data class ReadingSearchUnits(val units: List<ReadingSearchUnit>, val hasCoverageGaps: Boolean,
    val coverageGaps: List<IllustrationSearchTarget> = emptyList())

/** Limits consumption inside a rendered selection while leaving the original flow as context. */
internal fun readingUnitScope(unit: ReadingSearchUnit, selection: DocumentSelection): Utf16Range? {
    if (selection is DocumentSelection.Label) return unit.illustration?.takeIf {
        it.block == selection.block && it.start == selection.spanStart && it.run == selection.run
    }?.let { Utf16Range(selection.range.min.toLong(), selection.range.max.toLong()) }
    if (selection !is DocumentSelection.Reading) return null
    unit.illustration?.let { illustration ->
        return if (selection.start <= ReadingPoint(illustration.block, illustration.start) &&
            selection.end >= ReadingPoint(illustration.block, illustration.end)) Utf16Range(0, unit.text.length.toLong()) else null
    }
    var from: Int? = null
    var to: Int? = null
    for (part in unit.segments) {
        val beginning = ReadingPoint(part.block, part.blockStart)
        val ending = ReadingPoint(part.block, part.blockEnd)
        if (selection.end < beginning || selection.start > ending) continue
        fun project(point: ReadingPoint, end: Boolean): Int? = when {
            point <= beginning -> part.start
            point >= ending -> part.end
            part.generatedMarker -> part.start + point.markerOffset
            part.blockEnd - part.blockStart == part.end - part.start -> part.start + point.offset - part.blockStart
            else -> if (end) part.start else part.end // an atomic generated marker cannot be partly consumed
        }
        val start = project(selection.start, false) ?: continue
        val end = project(selection.end, true) ?: continue
        if (start > end) continue
        from = minOf(from ?: start, start)
        to = maxOf(to ?: end, end)
    }
    return from?.let { Utf16Range(it.toLong(), checkNotNull(to).toLong()) }
}

/** Joins actual text flows; cells, formula runs, labels, and illustration source stay independent. */
internal fun readingSearchUnits(document: MarkdownPreviewDocument, includeSource: Boolean): ReadingSearchUnits {
    val units = ArrayList<ReadingSearchUnit>()
    val numbers = markdownFootnoteNumbers(document.blocks)
    var text = StringBuilder()
    var segments = ArrayList<ReadingSearchSegment>()
    var previous: MarkdownRenderBlock? = null
    var gaps = false
    val gapOwners = ArrayList<IllustrationSearchTarget>()
    fun flush() {
        if (segments.isNotEmpty()) units.add(ReadingSearchUnit(text.toString(), segments.toList()))
        text = StringBuilder()
        segments = ArrayList()
    }
    fun append(index: Int, start: Int, end: Int, value: String, generatedMarker: Boolean = false) {
        val offset = text.length
        text.append(value)
        segments.add(ReadingSearchSegment(index, start, offset, text.length, end, generatedMarker = generatedMarker))
    }
    fun proseRun(index: Int, block: MarkdownRenderBlock, start: Int, end: Int) {
        var cursor = start
        block.spans.filter { it.start >= start && it.end <= end &&
            it.destinationKind == MarkdownInlineDestinationKind.FootnoteReference && it.destination in numbers
        }.forEach { span ->
            if (cursor < span.start) append(index, cursor, span.start, block.text.substring(cursor, span.start))
            append(index, span.start, span.end, numbers.getValue(span.destination!!).toString(), generatedMarker = true)
            cursor = span.end
        }
        if (cursor < end || (start == end && segments.isEmpty())) append(index, cursor, end, block.text.substring(cursor, end))
    }
    fun prose(index: Int, block: MarkdownRenderBlock, start: Int, end: Int) {
        var cursor = start
        for (separator in block.tableCellSeparators) {
            if (separator < start) continue
            if (separator >= end) break
            if (cursor < separator) proseRun(index, block, cursor, separator)
            flush()
            cursor = separator + 1
        }
        if (cursor < end || start == end) proseRun(index, block, cursor, end)
    }
    fun beginFlow(index: Int, block: MarkdownRenderBlock) {
        val last = previous
        val sameQuote = last?.quoteDepth == block.quoteDepth && last.quoteKind == block.quoteKind && !block.startsQuoteAlert
        when {
            block.continuesPrevious && last?.kind == block.kind -> Unit
            sameQuote && block.kind in listOf(MarkdownBlockKind.Paragraph, MarkdownBlockKind.Heading) &&
                last.kind in listOf(MarkdownBlockKind.Paragraph, MarkdownBlockKind.Heading) -> text.append("\n\n")
            sameQuote && block.kind == MarkdownBlockKind.ListItem && last.kind == MarkdownBlockKind.ListItem &&
                (!block.startsList || block.listDepth > last.listDepth) -> text.append(readingBlockSeparator(last, block))
            else -> flush()
        }
        val prefix = readingListPrefix(block)
        if (prefix.isNotEmpty()) append(index, -prefix.length, 0, prefix)
    }
    document.blocks.forEachIndexed { index, block ->
        if (block.illustrationContinuation) return@forEachIndexed
        val fence = block.kind == MarkdownBlockKind.Code && block.metadata.trim().lowercase() in listOf("math", "mermaid")
        val illustrations = block.spans.filter { it.styles and MARKDOWN_SPAN_STYLE_MATH != 0 || it.illustration != null }
        if (fence || illustrations.isNotEmpty()) {
            beginFlow(index, block)
            val spans = if (illustrations.isEmpty()) listOf(dev.soupslurpr.beautyxt.markdown.MarkdownInlineSpan(
                0, block.text.length, MARKDOWN_SPAN_STYLE_MATH, null)) else illustrations
            var cursor = 0
            spans.sortedBy { it.start }.forEach { span ->
                if (span.start > cursor) prose(index, block, cursor, span.start)
                flush()
                val rendered = span.illustration as? IllustrationResult.Rendered
                val diagram = rendered?.kind == IllustrationKind.Diagram || (fence && block.metadata.trim().equals("mermaid", true))
                val owner = IllustrationSearchTarget(index, span.start, span.end, null)
                if (rendered == null || !rendered.drawing.isTextComplete) gapOwners += owner
                if (rendered != null) {
                    if (!rendered.drawing.isTextComplete) gaps = true
                    val literalSource = block.text.substring(span.start, span.end)
                    // A single run of bare TeX letters/digits has an identity mapping: no
                    // commands, grouping, scripts, whitespace, or alternate glyph syntax.
                    // Equality alone never establishes provenance for general TeX/Mermaid.
                    val exactLiteral = !diagram && rendered.drawing.isTextComplete &&
                        rendered.drawing.textRuns.size == 1 && literalSource.isNotEmpty() &&
                        literalSource.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } &&
                        rendered.drawing.textRuns.single().text == literalSource
                    rendered.drawing.textRuns.forEachIndexed { runIndex, run ->
                        units += ReadingSearchUnit(run.text, if (exactLiteral) listOf(
                            ReadingSearchSegment(index, span.start, 0, run.text.length)) else emptyList(),
                            if (diagram) SearchRepresentation.DiagramLabel else SearchRepresentation.Formula,
                            owner.copy(run = runIndex, boxes = run.boxes))
                    }
                } else gaps = true
                if (includeSource || rendered == null) units += ReadingSearchUnit(
                    block.text.substring(span.start, span.end),
                    listOf(ReadingSearchSegment(index, span.start, 0, span.end - span.start)),
                    if (diagram) SearchRepresentation.DiagramSource else SearchRepresentation.FormulaSource, owner)
                cursor = maxOf(cursor, span.end)
            }
            if (cursor < block.text.length) { prose(index, block, cursor, block.text.length); flush() }
            previous = null
        } else if (block.kind == MarkdownBlockKind.TableRow) {
            if (!block.continuesPrevious || previous?.kind != MarkdownBlockKind.TableRow) flush()
            block.markdownTableCellRanges().forEachIndexed { cellIndex, cell ->
                if (cellIndex > 0) flush()
                prose(index, block, cell.start, cell.end)
            }
            previous = block
        } else {
            beginFlow(index, block)
            prose(index, block, 0, block.text.length)
            previous = block
        }
    }
    flush()
    return ReadingSearchUnits(units, gaps, gapOwners)
}

/** Returns a visible result only when both records prove the same source occurrence. */
internal fun matchingVisibleIllustration(results: List<DocumentSearchResult>, source: DocumentSearchResult): Int {
    if (source.representation !in listOf(SearchRepresentation.FormulaSource, SearchRepresentation.DiagramSource) ||
        source.source == null) return -1
    return results.indexOfFirst { visible ->
        visible.representation in listOf(SearchRepresentation.Formula, SearchRepresentation.DiagramLabel) &&
            visible.source == source.source && visible.illustration?.block == source.illustration?.block &&
            visible.illustration?.start == source.illustration?.start && visible.illustration?.end == source.illustration?.end
    }
}

/** Exact endpoint mapping; decoded escapes and generated markers are never guessed. */
internal fun exactReadingSourceOffset(block: MarkdownRenderBlock, offset: Int, ending: Boolean): Long? {
    if (offset !in 0..block.text.length || !block.text.isScalarBoundary(offset)) return null
    val before = block.sourceMaps.lastOrNull { offset > it.renderedStart && offset <= it.renderedEnd }
    val after = block.sourceMaps.firstOrNull { offset >= it.renderedStart && offset < it.renderedEnd }
    val map = (if (ending) before ?: after else after ?: before) ?: return null
    return when {
        offset == map.renderedStart -> map.source.start
        offset == map.renderedEnd -> map.source.end
        (map.renderedEnd - map.renderedStart).toLong() == map.source.end - map.source.start -> map.source.start + offset - map.renderedStart
        else -> null
    }
}

internal fun exactReadingSourceRange(block: MarkdownRenderBlock, start: Int, end: Int): Utf16Range? {
    if (start < 0 || end < start || end > block.text.length) return null
    val first = exactReadingSourceOffset(block, start, false) ?: return null
    // At an invisible formatting delimiter, an insertion uses the following text's
    // source position. Nonempty selections use the inward affinity at each edge.
    val last = if (start == end) first else exactReadingSourceOffset(block, end, true) ?: return null
    if (last < first) return null
    var cursor = start
    var sourceCursor = first
    for (map in block.sourceMaps) {
        if (map.renderedEnd <= start || map.renderedStart >= end) continue
        val mappedStart = if (map.renderedStart < start) first else map.source.start
        if (map.renderedStart > cursor || mappedStart < sourceCursor) return null
        cursor = minOf(end, map.renderedEnd)
        sourceCursor = if (cursor == end) last else map.source.end
    }
    if (cursor != end) return null
    // Coarse substitutions can share provenance with visible text outside the
    // selection. Such endpoints do not establish a unique source passage.
    if (start < end && block.sourceMaps.any { map ->
        (map.renderedEnd <= start || map.renderedStart >= end) &&
            map.source.start < last && map.source.end > first
    }) return null
    return Utf16Range(first, last)
}

internal const val MAX_READING_SEARCH_CONTEXT_UTF16 = 8 * 1024 * 1024
internal const val MAX_READING_LITERAL_CANDIDATE_UTF16 = 256 * 1024
internal const val MAX_READING_LITERAL_MATCH_UTF16 = 8 * 1024
private const val READING_SNIPPET_CONTEXT_SCALARS = 40

/** Describes one scalar-aligned bounded text slice for literal reading searches. */
internal data class ReadingLiteralSearchWindow(
    val windowStart: Int,
    val windowEnd: Int,
    val candidateEnd: Int,
    val localScope: Utf16Range,
    val localCursor: SearchCursor
)

private fun snapScalarBoundaryBackward(text: String, offset: Int): Int {
    val clamped = offset.coerceIn(0, text.length)
    return if (clamped in 1 until text.length &&
        text[clamped - 1].isHighSurrogate() && text[clamped].isLowSurrogate()
    ) clamped - 1 else clamped
}

private fun snapScalarBoundaryForward(text: String, offset: Int): Int {
    val clamped = offset.coerceIn(0, text.length)
    return if (clamped in 1 until text.length &&
        text[clamped - 1].isHighSurrogate() && text[clamped].isLowSurrogate()
    ) clamped + 1 else clamped
}

private fun retreatScalars(text: String, offset: Int, count: Int): Int {
    var cursor = snapScalarBoundaryBackward(text, offset)
    var remaining = count
    while (cursor > 0 && remaining > 0) {
        cursor = Character.offsetByCodePoints(text, cursor, -1)
        remaining--
    }
    return cursor
}

private fun advanceScalars(text: String, offset: Int, count: Int): Int {
    var cursor = snapScalarBoundaryForward(text, offset)
    var remaining = count
    while (cursor < text.length && remaining > 0) {
        cursor = Character.offsetByCodePoints(text, cursor, 1)
        remaining--
    }
    return cursor
}

/**
 * Slices a bounded, scalar-aligned context window for literal reading search so small scopes
 * inside huge flows and large joined flows never exceed the native context budget unnecessarily.
 * Returns null only when required whole-word line context exceeds [MAX_READING_SEARCH_CONTEXT_UTF16].
 */
internal fun readingLiteralSearchWindow(
    text: String,
    scope: Utf16Range,
    cursor: SearchCursor,
    wholeWord: Boolean,
    maxCandidateUnits: Int = MAX_READING_LITERAL_CANDIDATE_UTF16,
    maxMatchUnits: Int = MAX_READING_LITERAL_MATCH_UTF16,
    maxContextUnits: Int = MAX_READING_SEARCH_CONTEXT_UTF16
): ReadingLiteralSearchWindow? {
    val scopeEnd = scope.end.toInt()
    val cursorStart = cursor.start.toInt()
    val candidateEnd = snapScalarBoundaryForward(
        text,
        minOf(scopeEnd.toLong(), cursorStart.toLong() + maxCandidateUnits).toInt()
    ).coerceAtMost(scopeEnd)
    val consumptionEnd = snapScalarBoundaryForward(
        text,
        minOf(scopeEnd.toLong(), candidateEnd.toLong() + maxMatchUnits).toInt()
    ).coerceAtMost(scopeEnd)
    val snippetStart = retreatScalars(text, cursorStart, READING_SNIPPET_CONTEXT_SCALARS)
    val snippetEnd = advanceScalars(text, consumptionEnd, READING_SNIPPET_CONTEXT_SCALARS)
    val (windowStart, windowEnd) = if (wholeWord) {
        val lineStart = if (cursorStart == 0) 0 else text.lastIndexOf('\n', cursorStart - 1) + 1
        val lineEnd = text.indexOf('\n', consumptionEnd).let { if (it < 0) text.length else it }
        minOf(snippetStart, lineStart) to maxOf(snippetEnd, lineEnd)
    } else {
        snippetStart to snippetEnd
    }
    if (windowEnd - windowStart > maxContextUnits) return null
    return ReadingLiteralSearchWindow(
        windowStart = windowStart,
        windowEnd = windowEnd,
        candidateEnd = candidateEnd,
        localScope = Utf16Range((cursorStart - windowStart).toLong(), (consumptionEnd - windowStart).toLong()),
        localCursor = SearchCursor((cursorStart - windowStart).toLong(), cursor.skipEmpty)
    )
}

/** Rebases one windowed literal search page back into the unit's full UTF-16 coordinates. */
internal fun rebaseReadingLiteralSearchPage(
    window: ReadingLiteralSearchWindow,
    scopeEnd: Long,
    page: SearchPage
): SearchPage {
    val delta = window.windowStart.toLong()
    val candidateEnd = window.candidateEnd.toLong()
    val rebasedHits = page.hits.mapNotNull { hit ->
        val start = hit.range.start + delta
        if (candidateEnd < scopeEnd && start >= candidateEnd) null
        else hit.copy(range = Utf16Range(start, hit.range.end + delta))
    }
    val rebasedNext = SearchCursor(page.next.start + delta, page.next.skipEmpty)
    return if (candidateEnd < scopeEnd && (page.completion == SearchCompletion.Complete || rebasedNext.start >= candidateEnd)) {
        SearchPage(
            revision = page.revision,
            hits = rebasedHits,
            completion = SearchCompletion.PageLimit,
            next = SearchCursor(candidateEnd, false)
        )
    } else {
        SearchPage(page.revision, rebasedHits, page.completion, rebasedNext)
    }
}

internal fun readingSearchResult(
    document: MarkdownPreviewDocument,
    unit: ReadingSearchUnit,
    hit: SearchHit,
    sourceIndex: ReadingSourceRangeIndex? = null
): DocumentSearchResult {
    val segments = unit.segments
    var parts = if (segments.isEmpty()) emptyList() else {
        var low = 0
        var high = segments.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (segments[middle].end.toLong() < hit.range.start) low = middle + 1 else high = middle
        }
        val matched = ArrayList<ReadingSearchSegment>()
        var index = low
        while (index < segments.size && segments[index].start.toLong() <= hit.range.end) {
            val part = segments[index]
            val start = maxOf(part.start.toLong(), hit.range.start).toInt()
            val end = minOf(part.end.toLong(), hit.range.end).toInt()
            if (end > start || (end == start && hit.range.start == hit.range.end)) {
                matched += when {
                    part.generatedMarker -> part.copy(
                        blockStart = if (start == part.end) part.blockEnd else part.blockStart,
                        blockEnd = if (end == part.end) part.blockEnd else part.blockStart,
                        markerStart = if (start == part.end) 0 else start - part.start,
                        markerEnd = if (end == part.end) 0 else end - part.start,
                        start = start, end = end,
                        exact = (start == part.start || start == part.end) && (end == part.start || end == part.end)
                    )
                    part.end - part.start == part.blockEnd - part.blockStart -> part.copy(
                        blockStart = part.blockStart + start - part.start,
                        blockEnd = part.blockStart + end - part.start, start = start, end = end
                    )
                    else -> part.copy(start = start, end = end, exact = start == part.start && end == part.end)
                }
            }
            index++
        }
        matched
    }
    if (parts.isEmpty() && segments.isNotEmpty()) {
        // A match consisting only of a generated paragraph/list separator still
        // belongs at that boundary. Never invent an exact source range for it.
        var low = 0
        var high = segments.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (segments[middle].end.toLong() <= hit.range.start) low = middle + 1 else high = middle
        }
        val before = if (low > 0) segments[low - 1] else null
        val part = before ?: segments.first()
        val offset = if (before != null) part.blockEnd else part.blockStart
        parts = listOf(part.copy(blockStart = offset, blockEnd = offset,
            start = hit.range.start.toInt(), end = hit.range.end.toInt(), exact = false,
            generatedMarker = false, markerStart = 0, markerEnd = 0))
    }
    val first = parts.firstOrNull()
    val last = parts.lastOrNull()
    val source = if (parts.all { it.exact } && first != null && last != null) {
        DocumentSelection.Reading(0, ReadingPoint(first.block, first.blockStart),
            ReadingPoint(last.block, last.blockEnd)).exactSource(document, sourceIndex)
    } else null
    val owner = unit.illustration?.let { target ->
        DocumentSelection.Reading(0, ReadingPoint(target.block, target.start),
            ReadingPoint(target.block, target.end)).exactSource(document, sourceIndex)
    }
    val illustration = unit.illustration?.let { target ->
        target.copy(boxes = target.boxes.filter { box ->
            if (hit.range.start == hit.range.end) hit.range.start in box.start.toLong()..box.end.toLong()
            else box.start < hit.range.end && box.end > hit.range.start
        })
    }
    val navigation = first?.let { part ->
        val block = document.blocks[part.block]
        exactReadingSourceRange(block, part.blockStart, part.blockStart)?.start ?: block.source.start
    }
    return DocumentSearchResult(hit, source, source?.start ?: owner?.start ?: navigation ?: 0,
        unit.representation, parts, illustration, owner)
}
