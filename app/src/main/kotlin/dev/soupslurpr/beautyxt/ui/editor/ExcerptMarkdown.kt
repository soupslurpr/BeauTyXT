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
            val retainsEmptyStructure = when (first.block.kind) {
                MarkdownBlockKind.ListItem, MarkdownBlockKind.Rule,
                MarkdownBlockKind.Code, MarkdownBlockKind.Heading -> true
                else -> false
            }
            if (retainsEmptyStructure && first.block.text.isEmpty() && first.selection != null) {
                selected += first.copy(selection = null)
            } else if (range != null) {
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
    val listIndex = ExcerptListIndex(originals)

    // Resolve original heading identities before headings acquire their shorter excerpt names.
    val originalHeadings = MarkdownHeadingIndex(originalBlocks.map {
        MarkdownPreviewHeadingItem(it.original, it.block.kind == MarkdownBlockKind.Heading, it.block.text)
    })
    val definitions = originalBlocks.filter { it.block.markdownFootnoteLabel() != null }
        .groupBy { checkNotNull(it.block.markdownFootnoteLabel()) }
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
    val body = selected.filterNot { it.block.markdownFootnoteLabel() in noteNumbers }
    val notes = noteNumbers.keys.flatMap { label ->
        notices += ExcerptNotice(ExcerptNoticeKind.AddedNote, noteNumbers.getValue(label))
        definitions.getValue(label)
    }
    val excerptAnchors = excerptHeadingAnchors(body + notes)
    val entries = body + notes
    val listLayout = ExcerptListLayout(listIndex, entries)
    val transformedBlocks = entries.mapIndexed { outputIndex, entry ->
        val block = listLayout.promote(outputIndex, entry.block)
        val noteNumber = noteNumbers[block.markdownFootnoteLabel()]
        block.copy(
            kind = if (block.kind == MarkdownBlockKind.Footnote && noteNumber == null)
                MarkdownBlockKind.Paragraph else block.kind,
            metadata = if (block.kind == MarkdownBlockKind.Footnote) noteNumber ?: "" else block.metadata,
            footnoteLabel = noteNumber,
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
    val alertLayout = ExcerptAlertLayout(transformedBlocks, listLayout)
    val outputBlocks = transformedBlocks.mapIndexed { position, block ->
        block.copy(startsQuoteAlert = alertLayout.startsAlert(position))
    }
    if (notices.size > 4096) throw SelectionLimitException()
    val markdown = serializeExcerptBlocks(outputBlocks, listLayout, alertLayout)
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
            block.copy(text = presentation.text, sourceMaps = emptyList(), tableCellSeparators = presentation.tableCellSeparators(block),
                spans = presentation.spans.mapIndexed { spanIndex, span ->
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
        val separators = ArrayList(first.tableCellSeparators)
        var source = first.source
        var selectedStart: Int? = null
        var selectedEnd: Int? = null
        fun include(blockIndex: Int, base: Int) {
            val range = selection.rangeFor(blockIndex, document.blocks[blockIndex].text.length)
                ?: TextRange(0).takeIf { document.blocks[blockIndex].text.isEmpty() &&
                    selection.start < selection.end && ReadingPoint(blockIndex, 0) >= selection.start &&
                    ReadingPoint(blockIndex, 0) <= selection.end }
            range?.let {
                if (selectedStart == null) selectedStart = base + it.min
                selectedEnd = base + it.max
            }
        }
        include(start, 0)
        while (index < document.blocks.size && document.blocks[index].continuesPrevious &&
            !document.blocks[index].illustrationContinuation) {
            val part = document.blocks[index]
            source = MarkdownSourceRange(minOf(source.start, part.source.start), maxOf(source.end, part.source.end))
            include(index, text.length)
            spans += part.spans.map { it.copy(start = it.start + text.length, end = it.end + text.length) }
            if (first.kind == MarkdownBlockKind.TableRow) {
                separators += part.tableCellSeparators.map { it + text.length }
                val skip = if (alignments.isEmpty()) 0 else 1
                alignments += part.tableAlignments.drop(skip)
            }
            text.append(part.text)
            index++
        }
        result += ExcerptBlock(start, first.copy(text = text.toString(), spans = mergeExcerptSpans(spans),
            continuesPrevious = false, tableAlignments = alignments, tableCellSeparators = separators,
            source = source, sourceMaps = emptyList()),
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
    tableCellSeparators = block.tableCellSeparators.filter { it in start until end }.map { it - start },
    spans = block.spans.mapNotNull { span ->
        val from = maxOf(start, span.start)
        val to = minOf(end, span.end)
        if (from >= to) null else span.copy(start = from - start, end = to - start,
            illustration = span.illustration.takeIf { from == span.start && to == span.end })
    })

/** Retains selected columns and their original headers, leaving unselected data cells empty. */
private fun excerptTable(rows: List<ExcerptBlock>, notices: MutableSet<ExcerptNotice>): List<ExcerptBlock> {
    val selectedCells = rows.flatMapIndexed { rowIndex, row ->
        val selection = row.selection ?: return@flatMapIndexed emptyList()
        row.block.markdownTableCellRanges().mapIndexedNotNull { column, cell ->
            val intersects = if (cell.start == cell.end)
                selection.min <= cell.start && selection.max >= cell.end
            else selection.min < cell.end && selection.max > cell.start
            if (intersects) rowIndex to column else null
        }
    }
    if (selectedCells.isEmpty()) return emptyList()
    if (selectedCells.size == 1) {
        val row = rows[selectedCells.single().first]
        val range = checkNotNull(row.selection)
        return listOf(row.copy(block = cropExcerptBlock(row.block, range.min, range.max).copy(
            kind = MarkdownBlockKind.Paragraph, isTableHeader = false, startsTable = false,
            tableAlignments = emptyList(), tableCellSeparators = emptyList()), selection = null))
    }
    val columns = selectedCells.map { it.second }.distinct().sorted()
    val headerIndex = rows.indexOfFirst { it.block.isTableHeader }
    val selectedRows = selectedCells.map { it.first }.toSet()
    val included = rows.filterIndexed { index, _ -> index in selectedRows || index == headerIndex }
    val header = rows.getOrNull(headerIndex)
    if (header != null && (header.selection?.min != 0 || header.selection.max != header.block.text.length))
        notices += ExcerptNotice(ExcerptNoticeKind.AddedHeaders, columns.joinToString(", ") { (it + 1).toString() })
    return included.mapIndexed { rowIndex, row ->
        val originalCells = row.block.markdownTableCellRanges()
        val text = StringBuilder()
        val spans = ArrayList<MarkdownInlineSpan>()
        val separators = ArrayList<Int>()
        columns.forEachIndexed { index, column ->
            if (index != 0) { separators += text.length; text.append('\t') }
            val cell = originalCells.getOrNull(column) ?: return@forEachIndexed
            val range = if (row.block.isTableHeader) TextRange(cell.start, cell.end) else row.selection?.let {
                val start = maxOf(cell.start, it.min)
                val end = minOf(cell.end, it.max)
                if (end >= start) TextRange(start, end) else null
            } ?: return@forEachIndexed
            val fragment = cropExcerptBlock(row.block, range.min, range.max)
            spans += fragment.spans.map { it.copy(start = it.start + text.length, end = it.end + text.length) }
            text.append(fragment.text)
        }
        row.copy(block = row.block.copy(text = text.toString(), spans = spans, sourceMaps = emptyList(),
            startsTable = rowIndex == 0, tableCellSeparators = separators,
            tableAlignments = columns.map { row.block.tableAlignments.getOrElse(it) { MarkdownTableAlignment.None } }), selection = null)
    }
}

/** One authored item and group, independent of its paragraphs and child blocks. */
private data class ExcerptListItem(
    val id: Int,
    val group: Int,
    val parent: Int?,
    val block: MarkdownRenderBlock,
    val ambiguousSource: Boolean
)

private data class ExcerptListGroup(val id: Int, val parent: Int?, val ordered: Boolean, val note: String?)
private data class ExcerptItemScope(val depth: Int, val note: String?)

/** Indexes explicit list ancestry and uniquely owned nonlist children once per source model. */
private class ExcerptListIndex(originals: List<ExcerptBlock>) {
    val items = LinkedHashMap<Int, ExcerptListItem>()
    val itemForBlock = HashMap<Int, Int>()
    val ownerForBlock = HashMap<Int, Int>()

    init {
        val activeItems = sortedMapOf<Int, Int>()
        val activeGroups = sortedMapOf<Int, ExcerptListGroup>()
        val priorSourceEnds = HashMap<ExcerptItemScope, Long>()
        for (entry in originals) {
            val block = entry.block
            val note = block.markdownFootnoteLabel()
            if (block.kind != MarkdownBlockKind.ListItem) {
                val owner = activeItems.values.map(items::getValue).filter { item ->
                    item.block.markdownFootnoteLabel() == note && item.block.quoteDepth <= block.quoteDepth &&
                        item.block.source.start < item.block.source.end && block.source.start < block.source.end &&
                        item.block.source.start <= block.source.start && item.block.source.end >= block.source.end &&
                        (item.block.source.start < block.source.start || item.block.source.end > block.source.end)
                }.maxByOrNull { it.block.listDepth }?.takeUnless { it.ambiguousSource }
                if (owner == null) {
                    activeItems.clear()
                    activeGroups.clear()
                } else {
                    ownerForBlock[entry.original] = owner.id
                    activeItems.keys.filter { it > owner.block.listDepth }.forEach(activeItems::remove)
                    activeGroups.keys.filter { it > owner.block.listDepth }.forEach(activeGroups::remove)
                }
                continue
            }
            val depth = block.listDepth.coerceAtLeast(1)
            activeItems.keys.filter { it > depth }.forEach(activeItems::remove)
            activeGroups.keys.filter { it > depth }.forEach(activeGroups::remove)
            val current = activeItems[depth]?.let(items::getValue)
            if (block.continuesListItem && current != null && current.block.markdownFootnoteLabel() == note &&
                current.block.source == block.source && current.block.quoteDepth <= block.quoteDepth) {
                itemForBlock[entry.original] = current.id
                ownerForBlock[entry.original] = current.id
                continue
            }
            // List depth explicitly identifies its active parent. Coarse duplicate
            // HTML ranges do not invalidate that structure, but cannot prove that
            // an otherwise untyped code/table/rule block belongs to either item.
            val parent = activeItems[depth - 1]?.let(items::getValue)?.takeIf { item ->
                item.block.markdownFootnoteLabel() == note && item.block.quoteDepth <= block.quoteDepth &&
                    (item.block.source.start == item.block.source.end || block.source.start == block.source.end ||
                        item.block.source.start <= block.source.start && item.block.source.end >= block.source.end)
            }
            val previousGroup = activeGroups[depth]
            val group = if (block.startsList || previousGroup == null || previousGroup.parent != parent?.id ||
                previousGroup.note != note || previousGroup.ordered != block.isOrderedListItem)
                ExcerptListGroup(entry.original, parent?.id, block.isOrderedListItem, note) else previousGroup
            activeGroups[depth] = group
            val scope = ExcerptItemScope(depth, note)
            val end = priorSourceEnds[scope]
            val ambiguous = block.source.start < block.source.end && end != null && block.source.start < end
            if (block.source.start < block.source.end) priorSourceEnds[scope] = maxOf(end ?: 0, block.source.end)
            val item = ExcerptListItem(entry.original, group.id, parent?.id, block, ambiguous)
            items[item.id] = item
            activeItems[depth] = item.id
            itemForBlock[entry.original] = item.id
        }
    }
}

private sealed interface ExcerptContainer {
    data class Quote(val depth: Int) : ExcerptContainer
    data class Item(val id: Int, val markerWidth: Int) : ExcerptContainer
}

private data class ExcerptBlockListLayout(
    val item: Int?,
    val owner: Int?,
    val group: Int?,
    val depth: Int,
    val firstItemParagraph: Boolean,
    val startsGroup: Boolean,
    val marker: String,
    val path: List<ExcerptContainer>
)

/** Filters item ancestry to retained markers, then lays out each container path once. */
private class ExcerptListLayout(index: ExcerptListIndex, entries: List<ExcerptBlock>) {
    private val items = index.items
    private val blocks = ArrayList<ExcerptBlockListLayout>(entries.size)

    init {
        val firstParagraphs = HashMap<Int, Int>()
        entries.forEachIndexed { position, entry ->
            if (entry.block.kind == MarkdownBlockKind.ListItem && !entry.block.continuesListItem)
                index.itemForBlock[entry.original]?.let { firstParagraphs.putIfAbsent(it, position) }
        }
        val contentPaths = HashMap<Int, List<ExcerptContainer>>()
        val depths = HashMap<Int, Int>()
        val seenGroups = HashSet<Int>()
        val groupDelimiters = HashMap<Int, Boolean>()
        val delimitersAtDepth = HashMap<Int, Boolean>()
        entries.forEachIndexed { position, entry ->
            val block = entry.block
            val id = index.itemForBlock[entry.original].takeIf {
                block.kind == MarkdownBlockKind.ListItem && firstParagraphs.containsKey(it)
            }
            val item = id?.let(items::getValue)
            if (item != null && firstParagraphs[id] == position) {
                val parent = item.parent?.takeIf { (firstParagraphs[it] ?: Int.MAX_VALUE) < position }
                val depth = parent?.let { depths.getValue(it) + 1 } ?: 1
                depths[id] = depth
                val alternate = groupDelimiters.getOrPut(item.group) {
                    (!(delimitersAtDepth[depth] ?: true)).also { delimitersAtDepth[depth] = it }
                }
                val startsGroup = seenGroups.add(item.group)
                val number = block.listNumber
                // CommonMark derives subsequent numbers from the start. A later
                // marker stays syntactically valid even when its displayed value
                // has grown beyond the nine-digit authored-marker limit.
                val emittedNumber = if (!startsGroup && number > 999_999_999L) 1 else number
                val marker = if (block.isOrderedListItem) "$emittedNumber${if (alternate) ")" else "."} "
                    else if (alternate) "* " else "- "
                val path = withQuotes(parent?.let(contentPaths::getValue).orEmpty(), block.quoteDepth)
                contentPaths[id] = path + ExcerptContainer.Item(id, marker.length)
                blocks += ExcerptBlockListLayout(id, parent, item.group, depth, true,
                    startsGroup, marker, path)
            } else {
                val owner = (id ?: index.ownerForBlock[entry.original])?.takeIf {
                    (firstParagraphs[it] ?: Int.MAX_VALUE) < position &&
                        (id != null || !items.getValue(it).ambiguousSource)
                }
                val path = withQuotes(owner?.let(contentPaths::getValue).orEmpty(), block.quoteDepth)
                blocks += ExcerptBlockListLayout(id, owner, item?.group, id?.let { depths.getValue(it) } ?: 0,
                    false, false, "", path)
            }
        }
    }

    fun promote(position: Int, block: MarkdownRenderBlock): MarkdownRenderBlock {
        val layout = blocks[position]
        if (layout.item == null) return if (block.kind != MarkdownBlockKind.ListItem) block else block.copy(
            kind = MarkdownBlockKind.Paragraph, listDepth = 0, listNumber = 0, isOrderedListItem = false,
            isTaskChecked = false, isTaskUnchecked = false, startsList = false, continuesListItem = false)
        return block.copy(listDepth = layout.depth, startsList = layout.startsGroup,
            continuesListItem = !layout.firstItemParagraph)
    }

    fun marker(position: Int): String = blocks[position].marker
    fun prefix(position: Int): String = containerPrefix(blocks[position].path)
    fun quoteOwnerPath(position: Int, depth: Int): List<ExcerptContainer> {
        val path = blocks[position].path
        val quote = path.indexOfFirst { it is ExcerptContainer.Quote && it.depth == depth }
        check(quote >= 0) { "alert owner is outside its content quote" }
        return path.take(quote + 1)
    }
    fun quotePrefix(position: Int, depth: Int): String = containerPrefix(quoteOwnerPath(position, depth))

    fun separator(position: Int, prior: MarkdownRenderBlock, block: MarkdownRenderBlock, alertBoundary: Int): String {
        val previous = blocks[position - 1]
        val current = blocks[position]
        val emptyParent = prior.kind == MarkdownBlockKind.ListItem && prior.text.isEmpty() &&
            previous.item != null && current.owner == previous.item
        val adjacentRows = block.kind == MarkdownBlockKind.TableRow && prior.kind == block.kind && !block.startsTable
        val adjacentItems = block.kind == MarkdownBlockKind.ListItem && prior.kind == block.kind &&
            !block.startsList && !block.continuesListItem && current.group == previous.group
        if (emptyParent || adjacentRows || adjacentItems) return "\n"
        if (prior.markdownFootnoteLabel() != block.markdownFootnoteLabel()) return "\n\n"
        var count = previous.path.zip(current.path).takeWhile { (before, after) -> before == after }.size
        val alert = current.path.indexOfFirst { it is ExcerptContainer.Quote && it.depth >= alertBoundary }
        if (alert >= 0) count = minOf(count, alert)
        val common = current.path.take(count)
        val quote = common.indexOfLast { it is ExcerptContainer.Quote }
        if (quote < 0) return "\n\n"
        val note = if (block.markdownFootnoteLabel() != null) "    " else ""
        return "\n" + note + containerPrefix(common.take(quote + 1)).trimEnd() + "\n"
    }

    private fun withQuotes(path: List<ExcerptContainer>, quoteDepth: Int): List<ExcerptContainer> {
        val previousDepth = path.filterIsInstance<ExcerptContainer.Quote>().lastOrNull()?.depth ?: 0
        check(previousDepth <= quoteDepth) { "list ownership changed its enclosing quote depth" }
        return path + (previousDepth + 1..quoteDepth).map(ExcerptContainer::Quote)
    }

    private fun containerPrefix(path: List<ExcerptContainer>): String = buildString {
        path.forEach { container -> when (container) {
            is ExcerptContainer.Quote -> append("> ")
            is ExcerptContainer.Item -> append(" ".repeat(container.markerWidth))
        } }
    }
}

private data class ExcerptAlertOwner(val kind: MarkdownQuoteKind, val path: List<ExcerptContainer>)

/** Tracks visible enclosing alerts without confusing their depth with their first content. */
private class ExcerptAlertLayout(blocks: List<MarkdownRenderBlock>, listLayout: ExcerptListLayout) {
    private val starts = BooleanArray(blocks.size)
    private val boundaries = IntArray(blocks.size) { Int.MAX_VALUE }

    init {
        val active = sortedMapOf<Int, ExcerptAlertOwner>()
        var previousNote: String? = null
        blocks.forEachIndexed { position, block ->
            val note = block.markdownFootnoteLabel()
            if (previousNote != note) active.clear()
            previousNote = note
            fun discard(depths: List<Int>) {
                depths.forEach { depth ->
                    active.remove(depth)
                    boundaries[position] = minOf(boundaries[position], depth)
                }
            }
            val kind = block.quoteKind
            if (kind == null) {
                discard(active.keys.toList())
            } else {
                val depth = block.quoteAlertDepth
                check(depth in 1..block.quoteDepth) { "alert owner depth is invalid" }
                discard(active.filter { (ownerDepth, owner) -> ownerDepth > depth ||
                    listLayout.quoteOwnerPath(position, ownerDepth) != owner.path }.keys.toList())
                val path = listLayout.quoteOwnerPath(position, depth)
                if (block.startsQuoteAlert || active[depth] != ExcerptAlertOwner(kind, path)) {
                    discard(active.keys.filter { it >= depth })
                    boundaries[position] = minOf(boundaries[position], depth)
                    active[depth] = ExcerptAlertOwner(kind, path)
                    starts[position] = true
                }
            }
        }
    }

    fun startsAlert(position: Int): Boolean = starts[position]
    fun boundary(position: Int): Int = boundaries[position]
}

private fun serializeExcerptBlocks(blocks: List<MarkdownRenderBlock>, listLayout: ExcerptListLayout,
    alertLayout: ExcerptAlertLayout): String {
    val output = ExcerptText(MAX_EXCERPT_MARKDOWN_BYTES)
    val htmlTableRows = BooleanArray(blocks.size)
    var tableIndex = 0
    while (tableIndex < blocks.size) {
        if (blocks[tableIndex].kind != MarkdownBlockKind.TableRow) { tableIndex++; continue }
        var end = tableIndex + 1
        while (end < blocks.size && blocks[end].kind == MarkdownBlockKind.TableRow &&
            !blocks[end].startsTable && blocks[end].markdownFootnoteLabel() == blocks[tableIndex].markdownFootnoteLabel()) end++
        if (!blocks[tableIndex].isTableHeader || (tableIndex until end).count { blocks[it].isTableHeader } != 1)
            for (row in tableIndex until end) htmlTableRows[row] = true
        tableIndex = end
    }
    var previous: MarkdownRenderBlock? = null
    blocks.forEachIndexed { blockIndex, block ->
        val prior = previous
        if (prior != null) output.append(listLayout.separator(blockIndex, prior, block, alertLayout.boundary(blockIndex)))
        val rendered = when (block.kind) {
            MarkdownBlockKind.Code -> excerptFence(block.text, block.metadata.substringBefore('\n').replace("`", ""))
            MarkdownBlockKind.HtmlLiteral -> excerptFence(block.text, "html")
            MarkdownBlockKind.Rule -> "---"
            MarkdownBlockKind.Heading -> "#".repeat(block.headingLevel.coerceIn(1, 6)) + " " + excerptInline(block)
            MarkdownBlockKind.TableRow -> {
                val cells = block.markdownTableCellRanges().map { cell ->
                    cropExcerptBlock(block, cell.start, cell.end)
                }
                if (htmlTableRows[blockIndex]) {
                    val first = prior?.kind != MarkdownBlockKind.TableRow || block.startsTable ||
                        prior.markdownFootnoteLabel() != block.markdownFootnoteLabel()
                    val next = blocks.getOrNull(blockIndex + 1)
                    val last = next?.kind != MarkdownBlockKind.TableRow || next.startsTable ||
                        next.markdownFootnoteLabel() != block.markdownFootnoteLabel()
                    val tag = if (block.isTableHeader) "th" else "td"
                    (if (first) "<table>\n" else "") + "<tr>" + cells.joinToString("") {
                        "<$tag>${excerptHtmlInline(it)}</$tag>"
                    } + "</tr>" + if (last) "\n</table>" else ""
                } else {
                    val row = cells.joinToString(" | ", "| ", " |") { excerptInline(it, table = true) }
                    if (!block.isTableHeader) row else row + "\n" + cells.indices.joinToString(" | ", "| ", " |") {
                        when (block.tableAlignments.getOrNull(it)) {
                            MarkdownTableAlignment.Left -> ":---"
                            MarkdownTableAlignment.Right -> "---:"
                            MarkdownTableAlignment.Center -> ":---:"
                            else -> "---"
                        }
                    }
                }
            }
            MarkdownBlockKind.ListItem -> {
                val marker = listLayout.marker(blockIndex)
                val task = when { block.isTaskChecked -> "[x] "; block.isTaskUnchecked -> "[ ] "; else -> "" }
                val content = if (task.isNotEmpty() && (block.text.startsWith(MARKDOWN_CHECKED_TASK_PREFIX) ||
                    block.text.startsWith(MARKDOWN_UNCHECKED_TASK_PREFIX))) cropExcerptBlock(block, 2, block.text.length) else block
                val text = excerptInline(content)
                if (block.continuesListItem) text else marker + task + text
            }
            else -> excerptInline(block)
        }
        val alert = block.quoteKind?.takeIf { alertLayout.startsAlert(blockIndex) }
            ?.let { "[!${it.name.uppercase()}]" }
        val alertPrefix = if (alert != null) listLayout.quotePrefix(blockIndex, block.quoteAlertDepth) else ""
        val continuation = if (block.kind == MarkdownBlockKind.ListItem && !block.continuesListItem)
            " ".repeat(listLayout.marker(blockIndex).length) else ""
        appendExcerptBlock(output, rendered, listLayout.prefix(blockIndex), continuation, block, prior, alert, alertPrefix)
        previous = block
    }
    return output.toString()
}

/** Streams container prefixes through the byte cap before they can amplify multiline text. */
private fun appendExcerptBlock(output: ExcerptText, rendered: String, prefix: String, continuation: String,
    block: MarkdownRenderBlock, prior: MarkdownRenderBlock?, alert: String?, alertPrefix: String) {
    val note = block.markdownFootnoteLabel()
    val firstNote = note != null && prior?.markdownFootnoteLabel() != note
    val inlineNote = block.kind in listOf(MarkdownBlockKind.Footnote, MarkdownBlockKind.Paragraph)
    if (firstNote && !inlineNote) output.append("[^$note]:\n")
    var firstLine = true
    fun line(value: String, continuesContent: Boolean, linePrefix: String = prefix) {
        if (!firstLine) output.append("\n")
        if (note != null) output.append(if (firstLine && firstNote && inlineNote) "[^$note]: " else "    ")
        output.append(linePrefix)
        if (continuesContent) output.append(continuation)
        output.append(value)
        firstLine = false
    }
    if (alert != null) {
        line(alert, false, alertPrefix)
        // Empty items and non-1 ordered markers cannot interrupt a paragraph.
        if (block.kind == MarkdownBlockKind.ListItem && !block.continuesListItem) {
            line("", false, alertPrefix.trimEnd())
        }
    }
    var start = 0
    var continuesContent = false
    while (true) {
        val end = rendered.indexOf('\n', start)
        line(if (end < 0) rendered.substring(start) else rendered.substring(start, end), continuesContent)
        if (end < 0) break
        start = end + 1
        continuesContent = true
    }
}

/** Inline runs in the renderer carry cumulative style bits, so clipping can re-close each run. */
private fun excerptInline(block: MarkdownRenderBlock, table: Boolean = false): String {
    val result = StringBuilder()
    var cursor = 0
    block.spans.sortedBy { it.start }.forEach { span ->
        if (span.start < cursor) return@forEach
        result.append(excerptEscape(block.text.substring(cursor, span.start)))
        val text = block.text.substring(span.start, span.end)
        var value = when {
            span.destinationKind == MarkdownInlineDestinationKind.FootnoteReference -> "[^${span.destination}]"
            span.styles and MARKDOWN_SPAN_STYLE_MATH != 0 -> if (span.styles and MARKDOWN_SPAN_STYLE_DISPLAY_MATH != 0)
                "\\[$text\\]" else "\\($text\\)"
            span.styles and MARKDOWN_SPAN_STYLE_CODE != 0 -> excerptInlineCode(text, table)
            else -> excerptEscape(text)
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
                .replace("&", "&amp;")
            value = "[$value](<$destination>)"
        }
        result.append(value)
        cursor = span.end
    }
    result.append(excerptEscape(block.text.substring(cursor)))
    return result.toString()
}

/** Structural HTML retains headerless and multiple-header tables without synthetic cells. */
private fun excerptHtmlInline(block: MarkdownRenderBlock): String {
    val result = StringBuilder()
    var cursor = 0
    for (span in block.spans.sortedBy { it.start }) {
        if (span.start < cursor) continue
        result.append(htmlExcerptText(block.text.substring(cursor, span.start)).replace("\n", "<br>"))
        var value = htmlExcerptText(block.text.substring(span.start, span.end)).replace("\n", "<br>")
        if (span.styles and MARKDOWN_SPAN_STYLE_CODE != 0) value = "<code>$value</code>"
        if (span.styles and MARKDOWN_SPAN_STYLE_STRONG != 0) value = "<strong>$value</strong>"
        if (span.styles and MARKDOWN_SPAN_STYLE_EMPHASIS != 0) value = "<em>$value</em>"
        if (span.styles and MARKDOWN_SPAN_STYLE_STRIKETHROUGH != 0) value = "<del>$value</del>"
        if (span.styles and MARKDOWN_SPAN_STYLE_SUPERSCRIPT != 0) value = "<sup>$value</sup>"
        if (span.styles and MARKDOWN_SPAN_STYLE_SUBSCRIPT != 0) value = "<sub>$value</sub>"
        if (span.destinationKind == MarkdownInlineDestinationKind.Link && span.destination != null)
            value = "<a href=\"${htmlExcerptText(span.destination).replace("\"", "&quot;")}\">$value</a>"
        result.append(value)
        cursor = span.end
    }
    result.append(htmlExcerptText(block.text.substring(cursor)).replace("\n", "<br>"))
    return result.toString()
}

private fun excerptInlineCode(text: String, table: Boolean): String {
    if ('\n' in text || '\r' in text) return "<code>" + htmlExcerptText(text).replace("\n", "<br>") + "</code>"
    val longest = Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0
    val delimiter = "`".repeat(longest + 1)
    val value = if (table) text.replace("|", "\\|") else text
    val padding = if (text.startsWith('`') || text.endsWith('`') || (text.startsWith(' ') && text.endsWith(' ') && text.isNotBlank())) " " else ""
    return "$delimiter$padding$value$padding$delimiter"
}

private fun htmlExcerptText(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

internal fun excerptEscape(text: String): String = buildString {
    text.forEach { character ->
        when {
            character == '\n' -> append("<br>")
            character == '&' -> append("&amp;")
            character in "\\`*_{}[]<>#|!~$^()" || character == '.' || character == '+' || character == '-' -> { append('\\'); append(character) }
            else -> append(character)
        }
    }
}
