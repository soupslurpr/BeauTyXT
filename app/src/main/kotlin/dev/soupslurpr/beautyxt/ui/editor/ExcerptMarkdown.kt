package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.ui.text.TextRange
import dev.soupslurpr.beautyxt.markdown.*
import java.net.URI

internal const val MAX_EXCERPT_MARKDOWN_BYTES = 16 * 1024 * 1024

internal enum class ExcerptNoticeKind { AddedHeaders, AddedNote, RemovedLink, MissingNote }
internal data class ExcerptNotice(val kind: ExcerptNoticeKind, val detail: String)
internal data class FormattedExcerpt(
    val markdown: String,
    val document: MarkdownPreviewDocument,
    val notices: List<ExcerptNotice>
)

/** One semantic block, retaining its original identity after transport fragments are joined. */
private data class ExcerptBlock(val original: Int, val block: MarkdownRenderBlock, val selection: TextRange?)

/** Generates complete syntax from the selected semantic content, never from neighboring source. */
internal fun selectedMarkdown(document: MarkdownPreviewDocument, selection: DocumentSelection.Reading): FormattedExcerpt {
    val originalBlocks = excerptSemanticBlocks(document, selection)
    val partialMarkers = excerptPartialFootnoteMarkers(document, selection)
    val originals = partialMarkers?.let { excerptSemanticBlocks(it.first, it.second) } ?: originalBlocks
    val notices = linkedSetOf<ExcerptNotice>()
    val selected = ArrayList<ExcerptBlock>()
    var index = 0
    while (index < originals.size) {
        val first = originals[index]
        if (first.block.kind == MarkdownBlockKind.TableRow) {
            var end = index + 1
            while (end < originals.size && originals[end].block.kind == MarkdownBlockKind.TableRow &&
                !originals[end].block.startsTable) end++
            selected += excerptTable(originals.subList(index, end), notices)
            index = end
        } else {
            val range = first.selection?.takeUnless { it.collapsed }
            if (range != null) {
                selected += first.copy(block = cropExcerptBlock(first.block, range.min, range.max), selection = null)
            } else if (first.original in selection.start.block..selection.end.block) {
                val prefix = readingListPrefix(first.block)
                val from = if (first.original == selection.start.block) selection.start.offset.coerceIn(-prefix.length, 0) else -prefix.length
                val to = if (first.original == selection.end.block) selection.end.offset.coerceIn(-prefix.length, 0) else 0
                if (from < to) selected += first.copy(block = first.block.copy(
                    kind = MarkdownBlockKind.Paragraph, text = prefix.substring(from + prefix.length, to + prefix.length),
                    spans = emptyList(), listDepth = 0, listNumber = 0, startsList = false,
                    isOrderedListItem = false, continuesListItem = false), selection = null)
            }
            index++
        }
    }
    promoteExcerptLists(originals, selected)

    // Resolve original heading identities before headings acquire their shorter excerpt names.
    val originalHeadings = MarkdownHeadingIndex(originalBlocks.map {
        MarkdownPreviewHeadingItem(it.original, it.block.kind == MarkdownBlockKind.Heading, it.block.text)
    })
    val excerptAnchors = excerptHeadingAnchors(selected)
    val definitions = originalBlocks.filter { it.block.kind == MarkdownBlockKind.Footnote }.groupBy { it.block.metadata }
    val noteNumbers = linkedMapOf<String, String>()
    fun discover(block: MarkdownRenderBlock) {
        block.spans.filter { it.destinationKind == MarkdownInlineDestinationKind.FootnoteReference }.forEach { span ->
            val label = span.destination ?: return@forEach
            if (definitions.containsKey(label)) noteNumbers.getOrPut(label) { (noteNumbers.size + 1).toString() }
            else notices += ExcerptNotice(ExcerptNoticeKind.MissingNote, label)
        }
    }
    selected.forEach { discover(it.block) }
    var visitedNotes = 0
    while (visitedNotes < noteNumbers.size) {
        val label = noteNumbers.keys.elementAt(visitedNotes++)
        definitions.getValue(label).forEach { discover(it.block) }
        if (noteNumbers.size > 4096) throw SelectionLimitException()
    }
    val body = selected.filterNot { it.block.kind == MarkdownBlockKind.Footnote && it.block.metadata in noteNumbers }
    val notes = noteNumbers.keys.flatMap { label ->
        notices += ExcerptNotice(ExcerptNoticeKind.AddedNote, noteNumbers.getValue(label))
        definitions.getValue(label)
    }
    val outputBlocks = (body + notes).map { entry ->
        val block = entry.block
        block.copy(
            kind = if (block.kind == MarkdownBlockKind.Footnote && block.metadata !in noteNumbers)
                MarkdownBlockKind.Paragraph else block.kind,
            metadata = if (block.kind == MarkdownBlockKind.Footnote) noteNumbers[block.metadata] ?: "" else block.metadata,
            spans = block.spans.map { span ->
                when (span.destinationKind) {
                    MarkdownInlineDestinationKind.FootnoteReference -> {
                        val number = noteNumbers[span.destination]
                        if (number == null) span.copy(destination = null, destinationKind = null,
                            styles = span.styles and (MARKDOWN_SPAN_STYLE_FOOTNOTE_REFERENCE or MARKDOWN_SPAN_STYLE_SUPERSCRIPT).inv())
                        else span.copy(destination = number)
                    }
                    MarkdownInlineDestinationKind.Link -> {
                        val destination = span.destination.orEmpty()
                        val retained = if (destination.startsWith('#')) {
                            val fragment = runCatching { URI(destination).fragment }.getOrNull()
                            fragment?.let(originalHeadings::itemIndex)?.let(excerptAnchors::get)?.let { "#$it" }
                        } else safeExcerptLink(destination)
                        if (retained == null) notices += ExcerptNotice(ExcerptNoticeKind.RemovedLink, destination)
                        span.copy(destination = retained, destinationKind = retained?.let { MarkdownInlineDestinationKind.Link })
                    }
                    null -> span
                }
            }, sourceMaps = emptyList()
        )
    }
    if (notices.size > 4096) throw SelectionLimitException()
    val markdown = serializeExcerptBlocks(outputBlocks)
    return FormattedExcerpt(markdown, MarkdownPreviewDocument(markdown.toByteArray().size.toLong(),
        outputBlocks, outputBlocks.sumOf { it.spans.size }, outputBlocks.any { it.containsRawHtml },
        markdown.length.toLong(), 0), notices.toList())
}

/** A selected digit is superscript text; only a complete reference imports its note. */
private fun excerptPartialFootnoteMarkers(document: MarkdownPreviewDocument, selection: DocumentSelection.Reading):
    Pair<MarkdownPreviewDocument, DocumentSelection.Reading>? {
    val points = listOf(selection.anchor, selection.focus).filter { it.markerOffset > 0 }
    if (points.isEmpty()) return null
    val numbers = markdownFootnoteNumbers(document.blocks)
    val presentations = HashMap<Int, MarkdownInlinePresentation>()
    val blocks = document.blocks.mapIndexed { index, block ->
        val partial = block.spans.indices.filter { spanIndex -> points.any {
            it.block == index && it.offset == block.spans[spanIndex].start
        } }.toSet()
        if (partial.isEmpty()) block else {
            // Keep the corresponding span sequence so every following offset maps exactly.
            val presentation = checkNotNull(markdownFootnotePresentation(block.text, block.spans.mapIndexed { spanIndex, span ->
                if (spanIndex in partial) span else span.copy(destinationKind = null)
            }, numbers))
            presentations[index] = presentation
            block.copy(text = presentation.text, sourceMaps = emptyList(), spans = presentation.spans.mapIndexed { spanIndex, span ->
                if (spanIndex in partial) span.copy(destination = null, destinationKind = null,
                    styles = span.styles and MARKDOWN_SPAN_STYLE_FOOTNOTE_REFERENCE.inv())
                else block.spans[spanIndex].copy(start = span.start, end = span.end)
            })
        }
    }
    fun point(value: ReadingPoint): ReadingPoint {
        val presentation = presentations[value.block] ?: return value
        val original = document.blocks[value.block]
        return value.copy(offset = readingPresentationOffset(value, 0, original.text, original.spans, presentation), markerOffset = 0)
    }
    return document.copy(blocks = blocks) to selection.copy(anchor = point(selection.anchor), focus = point(selection.focus))
}

private fun safeExcerptLink(value: String): String? = runCatching {
    URI(value) // PDF and generated Markdown must agree on the complete destination.
    (markdownLinkAction(value) as? MarkdownLinkAction.External)?.destination
}.getOrNull()

private fun excerptHeadingAnchors(blocks: List<ExcerptBlock>): Map<Int, String> {
    val used = hashSetOf<String>()
    val result = HashMap<Int, String>()
    for (entry in blocks) {
        if (entry.block.kind != MarkdownBlockKind.Heading) continue
        val base = markdownHeadingAnchor(entry.block.text)
        if (base.isEmpty()) continue
        var anchor = base
        var suffix = 0
        while (!used.add(anchor)) anchor = "$base-${++suffix}"
        result[entry.original] = anchor
    }
    return result
}

/** Joins renderer chunks before clipping, so a split style, table cell, or fence stays intact. */
private fun excerptSemanticBlocks(document: MarkdownPreviewDocument, selection: DocumentSelection.Reading): List<ExcerptBlock> {
    val result = ArrayList<ExcerptBlock>()
    var index = 0
    while (index < document.blocks.size) {
        val start = index
        val first = document.blocks[index++]
        if (first.illustrationContinuation) continue
        val text = StringBuilder(first.text)
        val spans = ArrayList(first.spans)
        val alignments = ArrayList(first.tableAlignments)
        var selectedStart: Int? = null
        var selectedEnd: Int? = null
        fun include(blockIndex: Int, base: Int) {
            selection.rangeFor(blockIndex, document.blocks[blockIndex].text.length)?.let {
                if (selectedStart == null) selectedStart = base + it.min
                selectedEnd = base + it.max
            }
        }
        include(start, 0)
        while (index < document.blocks.size && document.blocks[index].continuesPrevious &&
            !document.blocks[index].illustrationContinuation) {
            val part = document.blocks[index]
            include(index, text.length)
            spans += part.spans.map { it.copy(start = it.start + text.length, end = it.end + text.length) }
            if (first.kind == MarkdownBlockKind.TableRow) {
                val skip = if (alignments.isEmpty()) 0 else 1
                alignments += part.tableAlignments.drop(skip)
            }
            text.append(part.text)
            index++
        }
        result += ExcerptBlock(start, first.copy(text = text.toString(), spans = mergeExcerptSpans(spans),
            continuesPrevious = false, tableAlignments = alignments, sourceMaps = emptyList()),
            selectedStart?.let { TextRange(it, checkNotNull(selectedEnd)) })
    }
    return result
}

private fun mergeExcerptSpans(spans: List<MarkdownInlineSpan>): List<MarkdownInlineSpan> {
    val result = ArrayList<MarkdownInlineSpan>()
    spans.forEach { span ->
        val last = result.lastOrNull()
        if (last != null && last.end == span.start && last.styles == span.styles &&
            last.destination == span.destination && last.destinationKind == span.destinationKind &&
            last.illustration == null && span.illustration == null &&
            span.styles and (MARKDOWN_SPAN_STYLE_MATH or MARKDOWN_SPAN_STYLE_FOOTNOTE_REFERENCE) == 0)
            result[result.lastIndex] = last.copy(end = span.end)
        else result += span
    }
    return result
}

private fun cropExcerptBlock(block: MarkdownRenderBlock, start: Int, end: Int): MarkdownRenderBlock = block.copy(
    text = block.text.substring(start, end), sourceMaps = emptyList(),
    spans = block.spans.mapNotNull { span ->
        val from = maxOf(start, span.start)
        val to = minOf(end, span.end)
        if (from >= to) null else span.copy(start = from - start, end = to - start,
            illustration = span.illustration.takeIf { from == span.start && to == span.end })
    })

/** Retains selected columns and their original headers, leaving unselected data cells empty. */
private fun excerptTable(rows: List<ExcerptBlock>, notices: MutableSet<ExcerptNotice>): List<ExcerptBlock> {
    fun cells(block: MarkdownRenderBlock): List<IntRange> {
        val result = ArrayList<IntRange>()
        var offset = 0
        block.text.split('\t').forEach { result += offset until offset + it.length; offset += it.length + 1 }
        return result
    }
    val selectedCells = rows.flatMapIndexed { rowIndex, row ->
        val selection = row.selection ?: return@flatMapIndexed emptyList()
        cells(row.block).mapIndexedNotNull { column, cell ->
            if (selection.min < cell.last + 1 && selection.max > cell.first) rowIndex to column else null
        }
    }
    if (selectedCells.isEmpty()) return emptyList()
    if (selectedCells.size == 1) {
        val row = rows[selectedCells.single().first]
        val range = checkNotNull(row.selection)
        return listOf(row.copy(block = cropExcerptBlock(row.block, range.min, range.max).copy(
            kind = MarkdownBlockKind.Paragraph, isTableHeader = false, startsTable = false, tableAlignments = emptyList()), selection = null))
    }
    val columns = selectedCells.map { it.second }.distinct().sorted()
    val header = rows.firstOrNull { it.block.isTableHeader }
    val selectedRows = selectedCells.map { it.first }.distinct().map(rows::get).filterNot { it.block.isTableHeader }
    val included = (listOfNotNull(header) + selectedRows)
    if (header != null && (header.selection?.min != 0 || header.selection.max != header.block.text.length))
        notices += ExcerptNotice(ExcerptNoticeKind.AddedHeaders, columns.joinToString(", ") { (it + 1).toString() })
    return included.mapIndexed { rowIndex, row ->
        val originalCells = cells(row.block)
        val text = StringBuilder()
        val spans = ArrayList<MarkdownInlineSpan>()
        columns.forEachIndexed { index, column ->
            if (index != 0) text.append('\t')
            val cell = originalCells.getOrNull(column) ?: return@forEachIndexed
            val range = if (row.block.isTableHeader) TextRange(cell.first, cell.last + 1) else row.selection?.let {
                val start = maxOf(cell.first, it.min)
                val end = minOf(cell.last + 1, it.max)
                if (end >= start) TextRange(start, end) else null
            } ?: return@forEachIndexed
            val fragment = cropExcerptBlock(row.block, range.min, range.max)
            spans += fragment.spans.map { it.copy(start = it.start + text.length, end = it.end + text.length) }
            text.append(fragment.text)
        }
        row.copy(block = row.block.copy(text = text.toString(), spans = spans, sourceMaps = emptyList(),
            startsTable = rowIndex == 0,
            tableAlignments = columns.map { row.block.tableAlignments.getOrElse(it) { MarkdownTableAlignment.None } }), selection = null)
    }
}

/** Promotes missing ancestors but preserves identities of neighboring original groups. */
private fun promoteExcerptLists(originals: List<ExcerptBlock>, selected: MutableList<ExcerptBlock>) {
    val groups = HashMap<Int, Int>()
    val stack = sortedMapOf<Int, Int>()
    val parents = HashMap<Int, Int?>()
    var previous: MarkdownRenderBlock? = null
    for (entry in originals) {
        val block = entry.block
        if (block.kind != MarkdownBlockKind.ListItem) { stack.clear(); previous = block; continue }
        stack.keys.filter { it > block.listDepth }.forEach(stack::remove)
        if (block.startsList || stack[block.listDepth] == null || previous?.kind != MarkdownBlockKind.ListItem) {
            stack[block.listDepth] = entry.original
            parents[entry.original] = stack[block.listDepth - 1]
        }
        groups[entry.original] = stack.getValue(block.listDepth)
        previous = block
    }
    val firstIncluded = selected.mapNotNull { entry -> groups[entry.original]?.let { it to entry.original } }
        .groupBy({ it.first }, { it.second }).mapValues { it.value.min() }
    val seen = hashSetOf<Int>()
    selected.indices.forEach { index ->
        val entry = selected[index]
        val group = groups[entry.original] ?: return@forEach
        var parent = parents[group]
        var depth = 1
        while (parent != null && (firstIncluded[parent] ?: Int.MAX_VALUE) < entry.original) { depth++; parent = parents[parent] }
        selected[index] = entry.copy(block = entry.block.copy(listDepth = depth, startsList = seen.add(group),
            continuesListItem = entry.block.continuesListItem && index > 0 && groups[selected[index - 1].original] == group))
    }
}

private fun serializeExcerptBlocks(blocks: List<MarkdownRenderBlock>): String {
    val output = ExcerptText(MAX_EXCERPT_MARKDOWN_BYTES)
    val delimiters = HashMap<Int, Boolean>()
    var previous: MarkdownRenderBlock? = null
    blocks.forEach { block ->
        val prior = previous
        if (prior != null) output.append(if (block.kind == MarkdownBlockKind.TableRow &&
            prior.kind == block.kind && !block.startsTable || block.kind == MarkdownBlockKind.ListItem &&
            prior.kind == block.kind && !block.startsList && !block.continuesListItem) "\n" else "\n\n")
        var rendered = when (block.kind) {
            MarkdownBlockKind.Code -> excerptFence(block.text, block.metadata.substringBefore('\n').replace("`", ""))
            MarkdownBlockKind.HtmlLiteral -> excerptFence(block.text, "html")
            MarkdownBlockKind.Rule -> "---"
            MarkdownBlockKind.Heading -> "#".repeat(block.headingLevel.coerceIn(1, 6)) + " " + excerptInline(block)
            MarkdownBlockKind.TableRow -> {
                var offset = 0
                val cells = block.text.split('\t').map { cell ->
                    excerptInline(cropExcerptBlock(block, offset, offset + cell.length), table = true).also { offset += cell.length + 1 }
                }
                val row = cells.joinToString(" | ", "| ", " |")
                if (!block.isTableHeader) row else row + "\n" + cells.indices.joinToString(" | ", "| ", " |") {
                    when (block.tableAlignments.getOrNull(it)) {
                        MarkdownTableAlignment.Left -> ":---"
                        MarkdownTableAlignment.Right -> "---:"
                        MarkdownTableAlignment.Center -> ":---:"
                        else -> "---"
                    }
                }
            }
            MarkdownBlockKind.ListItem -> {
                val depth = block.listDepth.coerceAtLeast(1)
                if (block.startsList) delimiters[depth] = !(delimiters[depth] ?: true)
                val alternate = delimiters[depth] ?: false
                val marker = if (block.isOrderedListItem) "${block.listNumber}${if (alternate) ")" else "."} " else if (alternate) "* " else "- "
                val task = when { block.isTaskChecked -> "[x] "; block.isTaskUnchecked -> "[ ] "; else -> "" }
                val content = if (task.isNotEmpty() && (block.text.startsWith(MARKDOWN_CHECKED_TASK_PREFIX) ||
                    block.text.startsWith(MARKDOWN_UNCHECKED_TASK_PREFIX))) cropExcerptBlock(block, 2, block.text.length) else block
                val indent = "    ".repeat(depth - 1)
                val text = excerptInline(content)
                if (block.continuesListItem) indent + "    " + text.replace("\n", "\n$indent    ")
                else indent + marker + task + text.replace("\n", "\n$indent${" ".repeat(marker.length)}")
            }
            MarkdownBlockKind.Footnote -> if (prior?.kind == block.kind && prior.metadata == block.metadata)
                "    " + excerptInline(block).replace("\n", "\n    ") else
                "[^${block.metadata}]: " + excerptInline(block).replace("\n", "\n    ")
            else -> excerptInline(block)
        }
        if (block.quoteKind != null && (prior?.quoteKind != block.quoteKind || block.startsQuoteAlert))
            rendered = "[!${block.quoteKind.name.uppercase()}]\n" + rendered
        if (block.quoteDepth > 0) {
            val prefix = "> ".repeat(block.quoteDepth)
            rendered = prefix + rendered.replace("\n", "\n$prefix")
        }
        output.append(rendered)
        previous = block
    }
    return output.toString()
}

/** Inline runs in the renderer carry cumulative style bits, so clipping can re-close each run. */
private fun excerptInline(block: MarkdownRenderBlock, table: Boolean = false): String {
    val result = StringBuilder()
    var cursor = 0
    block.spans.sortedBy { it.start }.forEach { span ->
        if (span.start < cursor) return@forEach
        result.append(excerptEscape(block.text.substring(cursor, span.start), table))
        val text = block.text.substring(span.start, span.end)
        var value = when {
            span.destinationKind == MarkdownInlineDestinationKind.FootnoteReference -> "[^${span.destination}]"
            span.styles and MARKDOWN_SPAN_STYLE_MATH != 0 -> if (span.styles and MARKDOWN_SPAN_STYLE_DISPLAY_MATH != 0)
                "\\[$text\\]" else "\\($text\\)"
            span.styles and MARKDOWN_SPAN_STYLE_CODE != 0 -> excerptInlineCode(text, table)
            else -> excerptEscape(text, table)
        }
        if (span.styles and (MARKDOWN_SPAN_STYLE_MATH or MARKDOWN_SPAN_STYLE_FOOTNOTE_REFERENCE) == 0) {
            // Explicit inline tags preserve styled whitespace and adjacent runs. Markdown
            // delimiters can change meaning when a clipped run touches another delimiter.
            if (span.styles and MARKDOWN_SPAN_STYLE_STRONG != 0) value = "<strong>$value</strong>"
            if (span.styles and MARKDOWN_SPAN_STYLE_EMPHASIS != 0) value = "<em>$value</em>"
            if (span.styles and MARKDOWN_SPAN_STYLE_STRIKETHROUGH != 0) value = "<del>$value</del>"
            if (span.styles and MARKDOWN_SPAN_STYLE_SUPERSCRIPT != 0) value = "<sup>$value</sup>"
            if (span.styles and MARKDOWN_SPAN_STYLE_SUBSCRIPT != 0) value = "<sub>$value</sub>"
        }
        if (span.destinationKind == MarkdownInlineDestinationKind.Link && span.destination != null) {
            val destination = span.destination.replace("\\", "%5C").replace("<", "%3C").replace(">", "%3E").replace(" ", "%20")
            value = "[$value](<$destination>)"
        }
        result.append(value)
        cursor = span.end
    }
    result.append(excerptEscape(block.text.substring(cursor), table))
    return result.toString()
}

private fun excerptInlineCode(text: String, table: Boolean): String {
    if ('\n' in text || '\r' in text) return "<code>" + htmlExcerptText(text) + "</code>"
    val longest = Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0
    val delimiter = "`".repeat(longest + 1)
    val value = if (table) text.replace("|", "\\|") else text
    val padding = if (text.startsWith('`') || text.endsWith('`') || (text.startsWith(' ') && text.endsWith(' ') && text.isNotBlank())) " " else ""
    return "$delimiter$padding$value$padding$delimiter"
}

private fun htmlExcerptText(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

internal fun excerptEscape(text: String, table: Boolean = false): String = buildString {
    text.forEach { character ->
        when {
            character == '\n' && table -> append("<br>")
            character == '&' -> append("&amp;")
            character in "\\`*_{}[]<>#|!~$^" || character == '.' || character == '+' || character == '-' -> { append('\\'); append(character) }
            else -> append(character)
        }
    }
}
