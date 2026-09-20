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

private const val MAX_MARKDOWN_ALERT_BLOCKS_PER_ITEM = 16
private const val MAX_MARKDOWN_CODE_BLOCKS_PER_ITEM = 8
private const val MAX_MARKDOWN_TABLE_ROWS_PER_ITEM = 16

/** Retains one rendered revision's lazy grouping and precomputed local navigation. */
internal data class MarkdownPreviewLayout(
    val items: List<MarkdownPreviewItem>,
    val headings: MarkdownHeadingIndex,
    val footnotes: MarkdownFootnoteItemTargets,
    val outline: List<DocumentOutlineEntry> = documentOutline(items),
    val codeCopies: Map<Int, MarkdownCodeCopy> = emptyMap(),
    val illustrations: MarkdownIllustrationPlan = MarkdownIllustrationPlan(emptyList()),
    // Revision-owned and memory-only. Rotation and an unchanged source/preview round trip should
    // not discard already rendered appearances or briefly replace them with raw source again.
    val illustrationCache: IllustrationCache = IllustrationCache()
)

/** Prepares a bounded preview, retaining existing text except for fragmented headings. */
internal fun prepareMarkdownPreview(blocks: List<MarkdownRenderBlock>): MarkdownPreviewLayout {
    val items = markdownPreviewItems(blocks)
    val headings = MarkdownHeadingIndex(markdownPreviewHeadings(items).asIterable())
    return MarkdownPreviewLayout(
        items = items,
        headings = headings,
        footnotes = markdownFootnoteItemTargets(items),
        codeCopies = markdownCodeCopies(blocks),
        illustrations = MarkdownIllustrationPlan(blocks)
    )
}

/** Joins only fragmented headings so anchors retain their complete Unicode semantics. */
private fun markdownPreviewHeadings(
    items: List<MarkdownPreviewItem>
): Sequence<MarkdownPreviewHeadingItem> = sequence {
    var heading: MarkdownPreviewHeadingItem? = null
    var joinedText: StringBuilder? = null
    items.forEachIndexed { itemIndex, item ->
        item.blocks.forEach { block ->
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
                val entry = MarkdownPreviewHeadingItem(itemIndex, isHeading, block.text)
                heading = entry.takeIf { isHeading }
                if (!isHeading) yield(entry)
            }
        }
    }
    heading?.let { first ->
        yield(joinedText?.let { first.copy(text = it.toString()) } ?: first)
    }
}

/** Groups bounded table, code, and GFM-alert runs into lazy preview items. */
internal fun markdownPreviewItems(blocks: List<MarkdownRenderBlock>): List<MarkdownPreviewItem> {
    val items = ArrayList<MarkdownPreviewItem>(blocks.size)
    var firstBlockIndex = 0
    while (firstBlockIndex < blocks.size) {
        val firstBlock = blocks[firstBlockIndex]
        var endBlockIndex = firstBlockIndex + 1
        val isTable = firstBlock.kind == MarkdownBlockKind.TableRow && firstBlock.quoteDepth == 0
        val isCode = firstBlock.kind == MarkdownBlockKind.Code && firstBlock.quoteDepth == 0
        val isQuoteAlert = firstBlock.quoteKind != null
        if (isTable) {
            while (
                endBlockIndex < blocks.size &&
                endBlockIndex - firstBlockIndex < MAX_MARKDOWN_TABLE_ROWS_PER_ITEM &&
                blocks[endBlockIndex].kind == MarkdownBlockKind.TableRow &&
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
                blocks[endBlockIndex].quoteDepth == 0 &&
                blocks[endBlockIndex].continuesPrevious
            ) {
                endBlockIndex += 1
            }
        } else if (isQuoteAlert) {
            while (
                endBlockIndex < blocks.size &&
                endBlockIndex - firstBlockIndex < MAX_MARKDOWN_ALERT_BLOCKS_PER_ITEM &&
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
                blocks[firstBlockIndex - 1].quoteDepth == 0
        val followsSameAlert =
            isQuoteAlert &&
                firstBlockIndex != 0 &&
                blocks[firstBlockIndex - 1].quoteKind == firstBlock.quoteKind &&
                !firstBlock.startsQuoteAlert
        val followsSameCode =
            isCode &&
                firstBlockIndex != 0 &&
                firstBlock.continuesPrevious &&
                blocks[firstBlockIndex - 1].kind == MarkdownBlockKind.Code &&
                blocks[firstBlockIndex - 1].quoteDepth == 0
        val itemBlocks = blocks.subList(firstBlockIndex, endBlockIndex)
        val precedingBlock = blocks.getOrNull(firstBlockIndex - 1)
        items +=
            MarkdownPreviewItem(
                firstBlockIndex = firstBlockIndex,
                blocks = itemBlocks,
                isTable = isTable,
                isCode = isCode,
                isQuoteAlert = isQuoteAlert,
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
                        !followsSameCode
            )
        firstBlockIndex = endBlockIndex
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
    val hasSpacingBefore: Boolean
)

/** Holds lazy-list targets for footnote definitions and their first references. */
internal data class MarkdownFootnoteItemTargets(
    val definitions: Map<String, Int>,
    val references: Map<String, Int>,
    val numbers: Map<String, Int>
)

/** Finds bounded local footnote targets within prepared lazy preview items. */
internal fun markdownFootnoteItemTargets(
    items: List<MarkdownPreviewItem>
): MarkdownFootnoteItemTargets {
    val definitions = LinkedHashMap<String, Int>()
    val references = LinkedHashMap<String, Int>()
    items.forEachIndexed { itemIndex, item ->
        item.blocks.forEach { block ->
            if (block.kind == MarkdownBlockKind.Footnote) {
                definitions.putIfAbsent(block.metadata, itemIndex)
            }
            block.spans.forEach { span ->
                if (span.destinationKind == MarkdownInlineDestinationKind.FootnoteReference) {
                    span.destination?.let { label -> references.putIfAbsent(label, itemIndex) }
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
        numbers = numbers
    )
}
