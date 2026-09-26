package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.document.SearchHit
import dev.soupslurpr.beautyxt.document.Utf16Range
import dev.soupslurpr.beautyxt.illustration.IllustrationKind
import dev.soupslurpr.beautyxt.illustration.IllustrationResult
import dev.soupslurpr.beautyxt.illustration.IllustrationTextBox
import dev.soupslurpr.beautyxt.markdown.MARKDOWN_SPAN_STYLE_MATH
import dev.soupslurpr.beautyxt.markdown.MarkdownBlockKind
import dev.soupslurpr.beautyxt.markdown.MarkdownInlineDestinationKind
import dev.soupslurpr.beautyxt.markdown.MarkdownPreviewDocument
import dev.soupslurpr.beautyxt.markdown.MarkdownRenderBlock
import dev.soupslurpr.beautyxt.markdown.markdownFootnoteNumbers

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
    fun prose(index: Int, block: MarkdownRenderBlock, start: Int, end: Int) {
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
            var start = 0
            block.text.split('\t').forEachIndexed { cellIndex, cell ->
                if (cellIndex > 0) flush()
                prose(index, block, start, start + cell.length)
                start += cell.length + 1
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
    val first = exactReadingSourceOffset(block, start, false) ?: return null
    // At an invisible formatting delimiter, an insertion uses the following text's
    // source position. Nonempty selections use the inward affinity at each edge.
    val last = if (start == end) first else exactReadingSourceOffset(block, end, true) ?: return null
    return if (last >= first) Utf16Range(first, last) else null
}

internal fun readingSearchResult(document: MarkdownPreviewDocument, unit: ReadingSearchUnit, hit: SearchHit): DocumentSearchResult {
    var parts = unit.segments.mapNotNull { part ->
        val start = maxOf(part.start.toLong(), hit.range.start).toInt()
        val end = minOf(part.end.toLong(), hit.range.end).toInt()
        if (end < start || (end == start && hit.range.start != hit.range.end)) null
        else if (part.generatedMarker) part.copy(
            blockStart = if (start == part.end) part.blockEnd else part.blockStart,
            blockEnd = if (end == part.end) part.blockEnd else part.blockStart,
            markerStart = if (start == part.end) 0 else start - part.start,
            markerEnd = if (end == part.end) 0 else end - part.start,
            start = start, end = end,
            exact = (start == part.start || start == part.end) && (end == part.start || end == part.end))
        else if (part.end - part.start == part.blockEnd - part.blockStart) part.copy(
            blockStart = part.blockStart + start - part.start,
            blockEnd = part.blockStart + end - part.start, start = start, end = end)
        else part.copy(start = start, end = end, exact = start == part.start && end == part.end)
    }
    if (parts.isEmpty() && unit.segments.isNotEmpty()) {
        // A match consisting only of a generated paragraph/list separator still
        // belongs at that boundary. Never invent an exact source range for it.
        val before = unit.segments.lastOrNull { it.end <= hit.range.start }
        val part = before ?: unit.segments.first()
        val offset = if (before != null) part.blockEnd else part.blockStart
        parts = listOf(part.copy(blockStart = offset, blockEnd = offset,
            start = hit.range.start.toInt(), end = hit.range.end.toInt(), exact = false,
            generatedMarker = false, markerStart = 0, markerEnd = 0))
    }
    val first = parts.firstOrNull()
    val last = parts.lastOrNull()
    val sourceStart = first?.takeIf { it.exact }?.let { exactReadingSourceRange(document.blocks[it.block], it.blockStart, it.blockEnd)?.start }
    val sourceEnd = last?.takeIf { it.exact }?.let { exactReadingSourceRange(document.blocks[it.block], it.blockStart, it.blockEnd)?.end }
    val source = if (sourceStart != null && sourceEnd != null && sourceEnd >= sourceStart) Utf16Range(sourceStart, sourceEnd) else null
    val owner = unit.illustration?.let { exactReadingSourceRange(document.blocks[it.block], it.start, it.end) }
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
