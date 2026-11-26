/* Prepares bounded lazy items and local navigation before preview reaches Compose. */
package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.illustration.IllustrationCache
import dev.soupslurpr.beautyxt.illustration.MarkdownIllustrationPlan
import dev.soupslurpr.beautyxt.markdown.MarkdownBlockKind
import dev.soupslurpr.beautyxt.markdown.MarkdownHeadingIndex
import dev.soupslurpr.beautyxt.markdown.MarkdownInlineDestinationKind
import dev.soupslurpr.beautyxt.markdown.MarkdownPreviewHeadingItem
import dev.soupslurpr.beautyxt.markdown.MarkdownRenderBlock
import dev.soupslurpr.beautyxt.markdown.markdownFootnoteNumbers
import dev.soupslurpr.beautyxt.markdown.markdownFootnoteLabel

private const val MAX_MARKDOWN_ALERT_BLOCKS_PER_ITEM = 16
private const val MAX_MARKDOWN_CODE_BLOCKS_PER_ITEM = 8
private const val MAX_MARKDOWN_TABLE_ROWS_PER_ITEM = 16
private const val MAX_MARKDOWN_FRONTMATTER_BLOCKS_PER_ITEM = 64

/** Retains one rendered revision's lazy grouping and precomputed local navigation. */
internal data class MarkdownPreviewLayout(
    val items: List<MarkdownPreviewItem>,
    val headings: MarkdownHeadingIndex,
    val footnotes: MarkdownFootnoteItemTargets,
    val outline: List<DocumentOutlineEntry> = documentOutline(items),
    val listContinuationOwners: Map<Int, MarkdownRenderBlock> = emptyMap(),
    val codeCopies: Map<Int, MarkdownCodeCopy> = emptyMap(),
    val illustrations: MarkdownIllustrationPlan = MarkdownIllustrationPlan(emptyList()),
    val detailsParentById: Map<Int, Int> = emptyMap(),
    val detailsDefaultOpenById: Map<Int, Boolean> = emptyMap(),
    val detailsSummaryKeyById: Map<Int, String> = emptyMap(),
    // Revision-owned and memory-only. Rotation and an unchanged source/preview round trip should
    // not discard already rendered appearances or briefly replace them with raw source again.
    val illustrationCache: IllustrationCache = IllustrationCache()
)

/** Prepares a bounded preview, retaining existing text except for fragmented headings. */
internal fun prepareMarkdownPreview(blocks: List<MarkdownRenderBlock>): MarkdownPreviewLayout {
    val items = markdownPreviewItems(blocks)
    val headings = MarkdownHeadingIndex(markdownPreviewHeadings(items).asIterable())
    val detailsParentById = LinkedHashMap<Int, Int>()
    val detailsDefaultOpenById = LinkedHashMap<Int, Boolean>()
    val detailsSummaryKeyById = LinkedHashMap<Int, String>()
    val summaryOccurrences = HashMap<String, Int>()
    blocks.forEach { block ->
        if (block.kind == MarkdownBlockKind.DetailsSummary && block.detailsId > 0) {
            detailsParentById[block.detailsId] = block.detailsParentId
            detailsDefaultOpenById[block.detailsId] = block.detailsInitiallyOpen
            val signature = "details:${block.detailsParentId}:${block.text.take(64)}"
            val occurrence = summaryOccurrences.getOrDefault(signature, 0)
            summaryOccurrences[signature] = occurrence + 1
            detailsSummaryKeyById[block.detailsId] = "$signature#$occurrence"
        }
    }
    return MarkdownPreviewLayout(
        items = items,
        headings = headings,
        footnotes = markdownFootnoteItemTargets(items),
        listContinuationOwners = markdownListContinuationOwners(blocks),
        codeCopies = markdownCodeCopies(blocks),
        illustrations = MarkdownIllustrationPlan(blocks),
        detailsParentById = detailsParentById,
        detailsDefaultOpenById = detailsDefaultOpenById,
        detailsSummaryKeyById = detailsSummaryKeyById
    )
}

/** Continuation paragraphs carry their nesting depth but omit the original marker number. */
internal fun markdownListContinuationOwners(blocks: List<MarkdownRenderBlock>): Map<Int, MarkdownRenderBlock> = buildMap {
    val parents = mutableMapOf<Int, MarkdownRenderBlock>()
    blocks.forEachIndexed { index, block ->
        if (block.kind == MarkdownBlockKind.ListItem) {
            if (block.continuesListItem || block.continuesPrevious) {
                parents[block.listDepth]?.let { put(index, it) }
            } else {
                parents[block.listDepth] = block
            }
        }
    }
}

/** Joins only fragmented headings so anchors retain their complete Unicode semantics. */
private fun markdownPreviewHeadings(
    items: List<MarkdownPreviewItem>
): Sequence<MarkdownPreviewHeadingItem> = sequence {
    var heading: MarkdownPreviewHeadingItem? = null
    var joinedText: StringBuilder? = null
    items.forEachIndexed { itemIndex, item ->
        item.blocks.forEachIndexed { localIndex, block ->
            val blockIndex = item.firstBlockIndex + localIndex
            val isHeading = block.kind == MarkdownBlockKind.Heading
            if (isHeading && block.continuesPrevious && heading != null) {
                val text = joinedText ?: StringBuilder(checkNotNull(heading).text).also {
                    joinedText = it
                }
                text.append(block.text)
            } else {
                heading?.let { first ->
                    yield(joinedText?.let { first.copy(text = it.toString()) } ?: first)
                }
                joinedText = null
                val entry = MarkdownPreviewHeadingItem(itemIndex, isHeading, block.text, blockIndex)
                heading = entry.takeIf { isHeading }
                if (!isHeading) yield(entry)
            }
        }
    }
    heading?.let { first ->
        yield(joinedText?.let { first.copy(text = it.toString()) } ?: first)
    }
}

/** Groups bounded table, code, frontmatter, and GFM-alert runs into lazy preview items. */
internal fun markdownPreviewItems(blocks: List<MarkdownRenderBlock>): List<MarkdownPreviewItem> {
    val items = ArrayList<MarkdownPreviewItem>(blocks.size)
    val keyOccurrences = HashMap<String, Int>()
    var firstBlockIndex = 0
    while (firstBlockIndex < blocks.size) {
        val firstBlock = blocks[firstBlockIndex]
        var endBlockIndex = firstBlockIndex + 1
        val isFrontmatter = firstBlock.kind == MarkdownBlockKind.Frontmatter
        val isTable = firstBlock.kind == MarkdownBlockKind.TableRow && firstBlock.quoteDepth == 0
        val isCode = firstBlock.kind == MarkdownBlockKind.Code && firstBlock.quoteDepth == 0
        val isDetailsSummary = firstBlock.kind == MarkdownBlockKind.DetailsSummary
        val isQuoteAlert = firstBlock.quoteKind != null && !isDetailsSummary
        if (isFrontmatter) {
            while (
                endBlockIndex < blocks.size &&
                endBlockIndex - firstBlockIndex < MAX_MARKDOWN_FRONTMATTER_BLOCKS_PER_ITEM &&
                blocks[endBlockIndex].kind == MarkdownBlockKind.Frontmatter &&
                !blocks[endBlockIndex].startsFrontmatter
            ) {
                endBlockIndex += 1
            }
        } else if (isTable) {
            while (
                endBlockIndex < blocks.size &&
                endBlockIndex - firstBlockIndex < MAX_MARKDOWN_TABLE_ROWS_PER_ITEM &&
                blocks[endBlockIndex].kind == MarkdownBlockKind.TableRow &&
                blocks[endBlockIndex].detailsId == firstBlock.detailsId &&
                blocks[endBlockIndex].markdownFootnoteLabel() == firstBlock.markdownFootnoteLabel() &&
                !blocks[endBlockIndex].startsTable &&
                blocks[endBlockIndex].quoteDepth == 0
            ) {
                endBlockIndex += 1
            }
        } else if (isCode) {
            while (
                endBlockIndex < blocks.size &&
                endBlockIndex - firstBlockIndex < MAX_MARKDOWN_CODE_BLOCKS_PER_ITEM &&
                blocks[endBlockIndex].kind == MarkdownBlockKind.Code &&
                blocks[endBlockIndex].detailsId == firstBlock.detailsId &&
                blocks[endBlockIndex].markdownFootnoteLabel() == firstBlock.markdownFootnoteLabel() &&
                blocks[endBlockIndex].quoteDepth == 0 &&
                blocks[endBlockIndex].continuesPrevious
            ) {
                endBlockIndex += 1
            }
        } else if (isQuoteAlert) {
            while (
                endBlockIndex < blocks.size &&
                endBlockIndex - firstBlockIndex < MAX_MARKDOWN_ALERT_BLOCKS_PER_ITEM &&
                blocks[endBlockIndex].kind != MarkdownBlockKind.DetailsSummary &&
                blocks[endBlockIndex].detailsId == firstBlock.detailsId &&
                blocks[endBlockIndex].quoteKind == firstBlock.quoteKind &&
                !blocks[endBlockIndex].startsQuoteAlert
            ) {
                endBlockIndex += 1
            }
        }
        val followsSameTable =
            isTable &&
                !firstBlock.startsTable &&
                firstBlockIndex != 0 &&
                blocks[firstBlockIndex - 1].kind == MarkdownBlockKind.TableRow &&
                blocks[firstBlockIndex - 1].detailsId == firstBlock.detailsId &&
                blocks[firstBlockIndex - 1].markdownFootnoteLabel() == firstBlock.markdownFootnoteLabel() &&
                blocks[firstBlockIndex - 1].quoteDepth == 0
        val followsSameAlert =
            isQuoteAlert &&
                firstBlockIndex != 0 &&
                blocks[firstBlockIndex - 1].kind != MarkdownBlockKind.DetailsSummary &&
                blocks[firstBlockIndex - 1].detailsId == firstBlock.detailsId &&
                blocks[firstBlockIndex - 1].quoteKind == firstBlock.quoteKind &&
                !firstBlock.startsQuoteAlert
        val followsSameCode =
            isCode &&
                firstBlockIndex != 0 &&
                firstBlock.continuesPrevious &&
                blocks[firstBlockIndex - 1].kind == MarkdownBlockKind.Code &&
                blocks[firstBlockIndex - 1].detailsId == firstBlock.detailsId &&
                blocks[firstBlockIndex - 1].quoteDepth == 0
        val itemBlocks = blocks.subList(firstBlockIndex, endBlockIndex)
        val precedingBlock = blocks.getOrNull(firstBlockIndex - 1)
        val keyBase = if (isFrontmatter) {
            "frontmatter"
        } else {
            // Keep LazyColumn keys strictly structural and content-independent so zero authored
            // text, metadata, or content hashes ever enter SavedStateRegistry.
            "item:$firstBlockIndex:${firstBlock.kind.ordinal}:${firstBlock.detailsId}"
        }
        val keyOccurrence = keyOccurrences.getOrDefault(keyBase, 0)
        keyOccurrences[keyBase] = keyOccurrence + 1
        items +=
            MarkdownPreviewItem(
                firstBlockIndex = firstBlockIndex,
                blocks = itemBlocks,
                isTable = isTable,
                isCode = isCode,
                isQuoteAlert = isQuoteAlert,
                isFrontmatter = isFrontmatter,
                startsWrappedPreview =
                    markdownHasWrappedPreview(
                        blocks = listOf(firstBlock),
                        precedingBlock = precedingBlock
                    ),
                showsWrappedPreview =
                    markdownHasWrappedPreview(
                        blocks = itemBlocks,
                        precedingBlock = precedingBlock
                    ),
                hasSpacingBefore =
                    firstBlockIndex != 0 &&
                        !firstBlock.continuesPrevious &&
                        !followsSameTable &&
                        !followsSameAlert &&
                        !followsSameCode,
                stableKey = "$keyBase#$keyOccurrence"
            )
        firstBlockIndex = endBlockIndex
    }
    var itemCursor = 0
    while (itemCursor < items.size) {
        if (!items[itemCursor].isTable) {
            itemCursor += 1
            continue
        }
        val tableStartItem = itemCursor
        val tableId = items[tableStartItem].firstBlockIndex
        var tableEndItem = tableStartItem + 1
        while (
            tableEndItem < items.size &&
            items[tableEndItem].isTable &&
            !items[tableEndItem].blocks.first().startsTable &&
            items[tableEndItem].blocks.first().markdownFootnoteLabel() ==
                items[tableStartItem].blocks.first().markdownFootnoteLabel()
        ) {
            tableEndItem += 1
        }
        var maxColumns = 0
        var totalLogicalRows = 0
        val chunkFirstRow = IntArray(tableEndItem - tableStartItem)
        for (chunkOffset in 0 until (tableEndItem - tableStartItem)) {
            val chunk = items[tableStartItem + chunkOffset]
            chunkFirstRow[chunkOffset] = totalLogicalRows
            chunk.blocks.forEach { block ->
                val cols = markdownTableColumnCount(block)
                if (cols > maxColumns) maxColumns = cols
                if (!block.continuesPrevious || totalLogicalRows == 0) {
                    totalLogicalRows += 1
                }
            }
        }
        for (chunkOffset in 0 until (tableEndItem - tableStartItem)) {
            val idx = tableStartItem + chunkOffset
            val chunk = items[idx]
            val nextChunkContinuesRow = (idx + 1 < tableEndItem) &&
                items[idx + 1].blocks.first().continuesPrevious
            val hasFragmentedRow = chunk.showsWrappedPreview || nextChunkContinuesRow
            items[idx] = chunk.copy(
                tableId = tableId,
                tableColumnCount = maxColumns,
                tableUsesWideLayout = maxColumns > MAX_MARKDOWN_TABLE_COLUMNS || hasFragmentedRow,
                tableRowCount = totalLogicalRows,
                tableFirstLogicalRowIndex = chunkFirstRow[chunkOffset]
            )
        }
        itemCursor = tableEndItem
    }
    return items
}

/** Returns whether bounded renderer chunks wrap one semantic code line or table row. */
internal fun markdownHasWrappedPreview(
    blocks: List<MarkdownRenderBlock>,
    precedingBlock: MarkdownRenderBlock? = null
): Boolean {
    require(blocks.isNotEmpty()) { "Markdown preview item must contain at least one block" }
    var previous = precedingBlock
    blocks.forEach { block ->
        if (
            block.continuesPrevious &&
            previous?.kind == block.kind &&
            when (block.kind) {
                MarkdownBlockKind.Code -> !previous.text.endsWith('\n')
                MarkdownBlockKind.TableRow -> true
                else -> false
            }
        ) {
            return true
        }
        previous = block
    }
    return false
}

/** Returns the lazy preview item that owns one renderer block index. */
internal fun markdownPreviewItemIndexForBlock(
    items: List<MarkdownPreviewItem>,
    blockIndex: Int
): Int? {
    require(blockIndex >= 0) { "Markdown preview block index must be nonnegative" }
    return items.indexOfFirst { item ->
        blockIndex in item.firstBlockIndex until
            Math.addExact(item.firstBlockIndex, item.blocks.size)
    }.takeIf { itemIndex -> itemIndex >= 0 }
}

/** Holds one ordinary block or one bounded semantic run for lazy presentation. */
internal data class MarkdownPreviewItem(
    val firstBlockIndex: Int,
    val blocks: List<MarkdownRenderBlock>,
    val isTable: Boolean,
    val isCode: Boolean,
    val isQuoteAlert: Boolean,
    val startsWrappedPreview: Boolean,
    val showsWrappedPreview: Boolean,
    val hasSpacingBefore: Boolean,
    val isFrontmatter: Boolean = false,
    val stableKey: String = "item:$firstBlockIndex",
    val tableId: Int = firstBlockIndex,
    val tableColumnCount: Int = 0,
    val tableUsesWideLayout: Boolean = false,
    val tableRowCount: Int = 0,
    val tableFirstLogicalRowIndex: Int = 0
)

/** Returns whether one details section is expanded under user overrides and HTML default. */
internal fun isDetailsSectionExpanded(
    layout: MarkdownPreviewLayout,
    detailsId: Int,
    overrides: Map<String, Boolean>
): Boolean {
    val key = layout.detailsSummaryKeyById[detailsId] ?: return true
    return overrides[key] ?: layout.detailsDefaultOpenById[detailsId] ?: false
}

/** Returns the nesting depth of one block inside active details containers. */
internal fun markdownDetailsDepth(
    layout: MarkdownPreviewLayout,
    block: MarkdownRenderBlock
): Int {
    var currentId = if (block.kind == MarkdownBlockKind.DetailsSummary) {
        block.detailsParentId
    } else {
        block.detailsId
    }
    var depth = 0
    var steps = 0
    while (currentId > 0 && steps < 32) {
        depth += 1
        currentId = layout.detailsParentById[currentId] ?: 0
        steps += 1
    }
    return depth
}

/** Returns whether [block] is visible under the active `<details>` expansion state. */
internal fun isMarkdownBlockVisibleInDetails(
    layout: MarkdownPreviewLayout,
    block: MarkdownRenderBlock,
    overrides: Map<String, Boolean>
): Boolean {
    if (layout.detailsSummaryKeyById.isEmpty()) return true
    var ancestorId = if (block.kind == MarkdownBlockKind.DetailsSummary) {
        block.detailsParentId
    } else {
        block.detailsId
    }
    var steps = 0
    while (ancestorId > 0 && steps < 32) {
        if (!isDetailsSectionExpanded(layout, ancestorId, overrides)) {
            return false
        }
        ancestorId = layout.detailsParentById[ancestorId] ?: 0
        steps += 1
    }
    return true
}

/** Filters preview items to those not hidden inside any collapsed `<details>` container. */
internal fun visibleMarkdownPreviewItems(
    layout: MarkdownPreviewLayout,
    overrides: Map<String, Boolean>
): List<MarkdownPreviewItem> {
    if (layout.detailsSummaryKeyById.isEmpty()) return layout.items
    return layout.items.filter { item ->
        isMarkdownBlockVisibleInDetails(layout, item.blocks.first(), overrides)
    }
}

/** Returns every enclosing `<details>` key that must expand to reveal [blockIndex]. */
internal fun requiredExpandedDetailsKeysForBlock(
    layout: MarkdownPreviewLayout,
    blocks: List<MarkdownRenderBlock>,
    blockIndex: Int
): List<String> {
    val block = blocks.getOrNull(blockIndex) ?: return emptyList()
    var ancestorId = if (block.kind == MarkdownBlockKind.DetailsSummary) {
        block.detailsParentId
    } else {
        block.detailsId
    }
    val keys = ArrayList<String>()
    var steps = 0
    while (ancestorId > 0 && steps < 32) {
        layout.detailsSummaryKeyById[ancestorId]?.let(keys::add)
        ancestorId = layout.detailsParentById[ancestorId] ?: 0
        steps += 1
    }
    return keys
}

/** Identifies an exact reading destination within a lazy preview item. */
internal data class MarkdownReadingTarget(
    val itemIndex: Int,
    val blockIndex: Int,
    val renderedOffset: Int = 0
)

/** Holds lazy-list targets for footnote definitions and their first references. */
internal data class MarkdownFootnoteItemTargets(
    val definitions: Map<String, Int>,
    val references: Map<String, Int>,
    val numbers: Map<String, Int>,
    val definitionTargets: Map<String, MarkdownReadingTarget> = emptyMap(),
    val referenceTargets: Map<String, MarkdownReadingTarget> = emptyMap()
)

/** Finds bounded local footnote targets within prepared lazy preview items. */
internal fun markdownFootnoteItemTargets(
    items: List<MarkdownPreviewItem>
): MarkdownFootnoteItemTargets {
    val definitions = LinkedHashMap<String, Int>()
    val references = LinkedHashMap<String, Int>()
    val definitionTargets = LinkedHashMap<String, MarkdownReadingTarget>()
    val referenceTargets = LinkedHashMap<String, MarkdownReadingTarget>()
    items.forEachIndexed { itemIndex, item ->
        item.blocks.forEachIndexed { localIndex, block ->
            val blockIndex = item.firstBlockIndex + localIndex
            block.markdownFootnoteLabel()?.let { label ->
                definitions.putIfAbsent(label, itemIndex)
                definitionTargets.putIfAbsent(label, MarkdownReadingTarget(itemIndex, blockIndex))
            }
            block.spans.forEach { span ->
                if (span.destinationKind == MarkdownInlineDestinationKind.FootnoteReference) {
                    span.destination?.let { label ->
                        references.putIfAbsent(label, itemIndex)
                        referenceTargets.putIfAbsent(
                            label,
                            MarkdownReadingTarget(itemIndex, blockIndex, span.start)
                        )
                    }
                }
            }
        }
    }
    val numbers =
        markdownFootnoteNumbers(
            items.asSequence().flatMap { item -> item.blocks.asSequence() }.asIterable()
        )
    return MarkdownFootnoteItemTargets(
        definitions = definitions,
        references = references,
        numbers = numbers,
        definitionTargets = definitionTargets,
        referenceTargets = referenceTargets
    )
}
