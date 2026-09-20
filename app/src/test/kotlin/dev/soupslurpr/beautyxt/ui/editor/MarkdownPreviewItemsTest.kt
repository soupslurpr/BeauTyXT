package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.markdown.MarkdownBlockKind
import dev.soupslurpr.beautyxt.markdown.MarkdownInlineDestinationKind
import dev.soupslurpr.beautyxt.markdown.MarkdownInlineSpan
import dev.soupslurpr.beautyxt.markdown.MarkdownQuoteKind
import dev.soupslurpr.beautyxt.markdown.MarkdownRenderBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies bounded lazy grouping and continuation spacing for Markdown preview. */
class MarkdownPreviewItemsTest {
    @Test
    fun preparesGroupedHeadingTargetsWithoutCopyingText() {
        val blocks =
            listOf(
                block(MarkdownBlockKind.Code),
                block(MarkdownBlockKind.Code, continuesPrevious = true),
                block(MarkdownBlockKind.Heading, text = "Notes"),
                block(MarkdownBlockKind.Heading, text = "Notes"),
                block(MarkdownBlockKind.Paragraph, text = "Not a heading")
            )

        val layout = prepareMarkdownPreview(blocks)

        assertEquals(listOf(2, 1, 1, 1), layout.items.map { item -> item.blocks.size })
        assertEquals(1, layout.headings.itemIndex("notes"))
        assertEquals(2, layout.headings.itemIndex("notes-1"))
        assertNull(layout.headings.itemIndex("not-a-heading"))
        assertSame(blocks[2], layout.items[1].blocks.single())
    }

    @Test
    fun indexesWholeHeadingsAcrossPresentationFragments() {
        val layout = prepareMarkdownPreview(
            listOf(
                block(MarkdownBlockKind.Heading, text = "Long "),
                block(MarkdownBlockKind.Heading, continuesPrevious = true, text = "heading"),
                block(MarkdownBlockKind.Heading, text = "Long heading"),
                block(MarkdownBlockKind.Heading, text = "Cafe"),
                block(MarkdownBlockKind.Heading, continuesPrevious = true, text = "\u0301")
            )
        )
        assertEquals(0, layout.headings.itemIndex("long-heading"))
        assertEquals(2, layout.headings.itemIndex("long-heading-1"))
        assertEquals(3, layout.headings.itemIndex("café"))
        assertNull(layout.headings.itemIndex("long"))
        assertNull(layout.headings.itemIndex("heading"))
    }

    @Test
    fun keepsParagraphSpacingWithinOneListItem() {
        val items = markdownPreviewItems(
            listOf(
                block(MarkdownBlockKind.ListItem),
                block(MarkdownBlockKind.ListItem).copy(continuesListItem = true),
                block(MarkdownBlockKind.ListItem, continuesPrevious = true)
                    .copy(continuesListItem = true)
            )
        )
        assertTrue(items[1].hasSpacingBefore)
        assertFalse(items[2].hasSpacingBefore)
    }

    @Test
    fun preparesEmptyAndHeadingFreePreviews() {
        val empty = prepareMarkdownPreview(emptyList())
        val body = prepareMarkdownPreview(listOf(block(MarkdownBlockKind.Paragraph)))

        assertTrue(empty.items.isEmpty())
        assertNull(empty.headings.itemIndex(""))
        assertTrue(empty.footnotes.numbers.isEmpty())
        assertEquals(0, body.headings.itemIndex(""))
        assertNull(body.headings.itemIndex("text"))
    }

    @Test
    fun boundsUnquotedTableRuns() {
        val blocks =
            buildList {
                add(block(MarkdownBlockKind.Paragraph))
                repeat(40) {
                    add(block(MarkdownBlockKind.TableRow))
                }
                add(block(MarkdownBlockKind.Paragraph))
            }

        val items = markdownPreviewItems(blocks)

        assertEquals(listOf(1, 16, 16, 8, 1), items.map { item -> item.blocks.size })
        assertTrue(items[1].hasSpacingBefore)
        assertFalse(items[2].hasSpacingBefore)
        assertFalse(items[3].hasSpacingBefore)
        assertTrue(items[4].hasSpacingBefore)
        assertEquals(0, markdownPreviewItemIndexForBlock(items, 0))
        assertEquals(1, markdownPreviewItemIndexForBlock(items, 1))
        assertEquals(2, markdownPreviewItemIndexForBlock(items, 17))
        assertEquals(4, markdownPreviewItemIndexForBlock(items, 41))
        assertEquals(null, markdownPreviewItemIndexForBlock(items, 42))
    }

    /** Keeps consecutive independent tables in separate visual groups. */
    @Test
    fun separatesAdjacentTables() {
        val blocks = listOf(
            block(MarkdownBlockKind.TableRow).copy(isTableHeader = true, startsTable = true),
            block(MarkdownBlockKind.TableRow),
            block(MarkdownBlockKind.TableRow).copy(isTableHeader = true, startsTable = true),
            block(MarkdownBlockKind.TableRow)
        )

        val items = markdownPreviewItems(blocks)

        assertEquals(listOf(2, 2), items.map { item -> item.blocks.size })
        assertTrue(items[1].hasSpacingBefore)
    }

    /** Uses semantic starts rather than treating every HTML header row as a new table. */
    @Test
    fun preservesMultipleHeadersAndHeaderlessTables() {
        val blocks = listOf(
            block(MarkdownBlockKind.TableRow).copy(isTableHeader = true, startsTable = true),
            block(MarkdownBlockKind.TableRow).copy(isTableHeader = true),
            block(MarkdownBlockKind.TableRow),
            block(MarkdownBlockKind.TableRow).copy(startsTable = true),
            block(MarkdownBlockKind.TableRow),
            block(MarkdownBlockKind.TableRow).copy(startsTable = true)
        )

        val items = markdownPreviewItems(blocks)

        assertEquals(listOf(3, 2, 1), items.map { item -> item.blocks.size })
        assertTrue(items[1].hasSpacingBefore)
        assertTrue(items[2].hasSpacingBefore)
    }

    /** Preserves spacing when a separate table begins at a lazy chunk boundary. */
    @Test
    fun separatesTablesAtChunkBoundaries() {
        val blocks = List(16) { block(MarkdownBlockKind.TableRow) } +
            block(MarkdownBlockKind.TableRow).copy(startsTable = true)

        val items = markdownPreviewItems(blocks)

        assertEquals(listOf(16, 1), items.map { item -> item.blocks.size })
        assertTrue(items[1].hasSpacingBefore)
    }

    @Test
    fun omitsSectionSpacingBeforeContinuationBlocks() {
        val items =
            markdownPreviewItems(
                listOf(
                    block(MarkdownBlockKind.Paragraph),
                    block(MarkdownBlockKind.Paragraph, continuesPrevious = true),
                    block(MarkdownBlockKind.Heading)
                )
            )

        assertFalse(items[0].hasSpacingBefore)
        assertFalse(items[1].hasSpacingBefore)
        assertTrue(items[2].hasSpacingBefore)
    }

    @Test
    fun groupsBoundedCodeContinuationRuns() {
        val blocks =
            buildList {
                add(block(MarkdownBlockKind.Paragraph))
                add(block(MarkdownBlockKind.Code, text = "first\n"))
                repeat(10) {
                    add(
                        block(
                            MarkdownBlockKind.Code,
                            continuesPrevious = true,
                            text = "continuation\n"
                        )
                    )
                }
                add(block(MarkdownBlockKind.Code, text = "separate\n"))
                add(block(MarkdownBlockKind.Paragraph))
            }

        val items = markdownPreviewItems(blocks)

        assertEquals(listOf(1, 8, 3, 1, 1), items.map { item -> item.blocks.size })
        assertTrue(items[1].isCode)
        assertTrue(items[2].isCode)
        assertFalse(items[2].hasSpacingBefore)
        assertTrue(items[3].hasSpacingBefore)
        assertFalse(items[1].showsWrappedPreview)
        assertFalse(items[2].showsWrappedPreview)
    }

    @Test
    fun detectsOnlySemanticLinesAndRowsSplitAcrossBlocks() {
        val continuedCode =
            block(
                MarkdownBlockKind.Code,
                continuesPrevious = true,
                text = "rest"
            )

        assertTrue(
            markdownHasWrappedPreview(
                blocks = listOf(continuedCode),
                precedingBlock = block(MarkdownBlockKind.Code, text = "partial")
            )
        )
        assertFalse(
            markdownHasWrappedPreview(
                blocks = listOf(continuedCode),
                precedingBlock = block(MarkdownBlockKind.Code, text = "complete\n")
            )
        )
        assertTrue(
            markdownHasWrappedPreview(
                blocks =
                    listOf(
                        block(
                            MarkdownBlockKind.TableRow,
                            continuesPrevious = true,
                            text = "next cell"
                        )
                    ),
                precedingBlock = block(MarkdownBlockKind.TableRow, text = "first cell")
            )
        )
        assertFalse(
            markdownHasWrappedPreview(
                blocks = listOf(continuedCode),
                precedingBlock = block(MarkdownBlockKind.Paragraph, text = "partial")
            )
        )
    }

    @Test
    fun detectsWrappedCodeAcrossLazyItemBoundary() {
        val blocks =
            buildList {
                repeat(8) { blockIndex ->
                    add(
                        block(
                            MarkdownBlockKind.Code,
                            continuesPrevious = blockIndex != 0,
                            text = if (blockIndex == 7) "partial" else "line\n"
                        )
                    )
                }
                add(
                    block(
                        MarkdownBlockKind.Code,
                        continuesPrevious = true,
                        text = "remainder"
                    )
                )
            }

        val items = markdownPreviewItems(blocks)

        assertEquals(listOf(8, 1), items.map { item -> item.blocks.size })
        assertFalse(items[0].showsWrappedPreview)
        assertTrue(items[1].showsWrappedPreview)
    }

    @Test
    fun groupsEachContiguousGfmAlertIntoOneItem() {
        val items =
            markdownPreviewItems(
                listOf(
                    block(MarkdownBlockKind.Paragraph).copy(
                        quoteDepth = 1,
                        quoteKind = MarkdownQuoteKind.Note,
                        startsQuoteAlert = true
                    ),
                    block(MarkdownBlockKind.Paragraph).copy(
                        quoteDepth = 1,
                        quoteKind = MarkdownQuoteKind.Note
                    ),
                    block(MarkdownBlockKind.Paragraph),
                    block(MarkdownBlockKind.Paragraph).copy(
                        quoteDepth = 1,
                        quoteKind = MarkdownQuoteKind.Note,
                        startsQuoteAlert = true
                    )
                )
            )

        assertEquals(listOf(2, 1, 1), items.map { item -> item.blocks.size })
        assertTrue(items[0].isQuoteAlert)
        assertFalse(items[1].isQuoteAlert)
        assertTrue(items[2].isQuoteAlert)
        assertTrue(items[2].hasSpacingBefore)
    }

    @Test
    fun indexesFootnoteDefinitionsAndFirstReferences() {
        val items =
            markdownPreviewItems(
                listOf(
                    block(MarkdownBlockKind.Paragraph).copy(
                        spans =
                            listOf(
                                MarkdownInlineSpan(
                                    start = 0,
                                    end = 4,
                                    styles = 0,
                                    destination = "note",
                                    destinationKind =
                                        MarkdownInlineDestinationKind.FootnoteReference
                                )
                            )
                    ),
                    block(MarkdownBlockKind.Paragraph),
                    block(MarkdownBlockKind.Footnote).copy(metadata = "note")
                )
            )

        val targets = markdownFootnoteItemTargets(items)

        assertEquals(0, targets.references["note"])
        assertEquals(2, targets.definitions["note"])
        assertEquals(1, targets.numbers["note"])
    }

    @Test
    fun numbersFootnotesByFirstReferenceBeforeUnreferencedDefinitions() {
        fun reference(label: String): MarkdownRenderBlock = block(MarkdownBlockKind.Paragraph).copy(
            spans =
                listOf(
                    MarkdownInlineSpan(
                        start = 0,
                        end = 4,
                        styles = 0,
                        destination = label,
                        destinationKind =
                            MarkdownInlineDestinationKind.FootnoteReference
                    )
                )
        )
        val items =
            markdownPreviewItems(
                listOf(
                    reference("second"),
                    reference("first"),
                    block(MarkdownBlockKind.Footnote).copy(metadata = "first"),
                    block(MarkdownBlockKind.Footnote).copy(metadata = "orphan")
                )
            )

        val numbers = markdownFootnoteItemTargets(items).numbers

        assertEquals(mapOf("second" to 1, "first" to 2, "orphan" to 3), numbers)
    }

    /** Creates one otherwise empty presentation block. */
    private fun block(
        kind: MarkdownBlockKind,
        continuesPrevious: Boolean = false,
        text: String = "text"
    ): MarkdownRenderBlock = MarkdownRenderBlock(
        kind = kind,
        continuesPrevious = continuesPrevious,
        isOrderedListItem = false,
        isTaskChecked = false,
        isTaskUnchecked = false,
        isTableHeader = false,
        containsRawHtml = false,
        headingLevel = if (kind == MarkdownBlockKind.Heading) 1 else 0,
        quoteDepth = 0,
        listDepth = 0,
        listNumber = 0,
        text = text,
        metadata = "",
        spans = emptyList()
    )
}
