package dev.soupslurpr.beautyxt.printing

import dev.soupslurpr.beautyxt.markdown.MARKDOWN_SPAN_STYLE_STRONG
import dev.soupslurpr.beautyxt.markdown.MarkdownBlockKind
import dev.soupslurpr.beautyxt.markdown.MarkdownInlineDestinationKind
import dev.soupslurpr.beautyxt.markdown.MarkdownInlineSpan
import dev.soupslurpr.beautyxt.markdown.MarkdownRenderBlock
import dev.soupslurpr.beautyxt.markdown.MarkdownTableAlignment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies semantic Markdown content preparation for native printing. */
class MarkdownPrintContentTest {
    /** Verifies ordinary block text and spans remain unchanged. */
    @Test
    fun preservesNonTableContent() {
        val span = MarkdownInlineSpan(0, 5, MARKDOWN_SPAN_STYLE_STRONG, null)

        val content = markdownPrintContent(markdownBlock("alpha", spans = listOf(span)))

        assertEquals("alpha", content.text)
        assertEquals(listOf(span), content.spans)
    }

    /** Verifies table tabs become visible separators without shifting cell spans. */
    @Test
    fun separatesTableCellsAndRemapsSpans() {
        val content =
            markdownPrintContent(
                markdownBlock(
                    text = "Name\tValue",
                    kind = MarkdownBlockKind.TableRow,
                    spans =
                        listOf(
                            MarkdownInlineSpan(
                                start = 5,
                                end = 10,
                                styles = MARKDOWN_SPAN_STYLE_STRONG,
                                destination = null
                            )
                        )
                )
            )

        assertEquals("Name  |  Value", content.text)
        assertEquals(9, content.spans.single().start)
        assertEquals(14, content.spans.single().end)
    }

    /** Verifies print content replaces source footnote labels with reading-order numbers. */
    @Test
    fun numbersFootnoteReferencesAndLaterSpans() {
        val content =
            markdownPrintContent(
                markdownBlock(
                    text = "See [^long] then link",
                    spans =
                        listOf(
                            MarkdownInlineSpan(
                                start = 4,
                                end = 11,
                                styles = 0,
                                destination = "long",
                                destinationKind =
                                    MarkdownInlineDestinationKind.FootnoteReference
                            ),
                            MarkdownInlineSpan(
                                start = 17,
                                end = 21,
                                styles = 0,
                                destination = "https://example.com"
                            )
                        )
                ),
                footnoteNumbers = mapOf("long" to 1)
            )

        assertEquals("See 1 then link", content.text)
        assertEquals(4, content.spans[0].start)
        assertEquals(5, content.spans[0].end)
        assertEquals(11, content.spans[1].start)
        assertEquals(15, content.spans[1].end)
    }

    /** Verifies semantic table cells retain independent alignment and rebased spans. */
    @Test
    fun splitsAlignedTableCells() {
        val cells =
            markdownPrintCells(
                markdownBlock(
                    text = "Name\tValue",
                    kind = MarkdownBlockKind.TableRow,
                    spans =
                        listOf(
                            MarkdownInlineSpan(
                                start = 5,
                                end = 10,
                                styles = MARKDOWN_SPAN_STYLE_STRONG,
                                destination = null
                            )
                        ),
                    tableAlignments =
                        listOf(MarkdownTableAlignment.Left, MarkdownTableAlignment.Right)
                )
            )

        assertEquals(listOf("Name", "Value"), cells.map(MarkdownPrintCell::text))
        assertEquals(
            listOf(MarkdownTableAlignment.Left, MarkdownTableAlignment.Right),
            cells.map(MarkdownPrintCell::alignment)
        )
        assertEquals(0, cells[1].spans.single().start)
        assertEquals(5, cells[1].spans.single().end)
    }

    /** Verifies ordinary tables retain cells while wide or cramped tables use text fallback. */
    @Test
    fun boundsSemanticTableLayout() {
        assertTrue(
            supportsSemanticMarkdownPrintTable(
                columnCount = 16,
                availableWidthPixels = 1_024,
                horizontalPaddingPixels = 8
            )
        )
        assertFalse(
            supportsSemanticMarkdownPrintTable(
                columnCount = 17,
                availableWidthPixels = 1_024,
                horizontalPaddingPixels = 8
            )
        )
        assertFalse(
            supportsSemanticMarkdownPrintTable(
                columnCount = 4,
                availableWidthPixels = 64,
                horizontalPaddingPixels = 8
            )
        )
    }

    /** Verifies a table header moves only when its first row can stay with it. */
    @Test
    fun keepsTableHeaderWithFirstRow() {
        assertTrue(
            shouldAdvanceMarkdownPrintTableHeader(
                bodyTopPixels = 10,
                bodyBottomPixels = 110,
                bodyY = 85,
                headerHeightPixels = 10,
                firstRowHeightPixels = 20
            )
        )
        assertFalse(
            shouldAdvanceMarkdownPrintTableHeader(
                bodyTopPixels = 10,
                bodyBottomPixels = 110,
                bodyY = 80,
                headerHeightPixels = 10,
                firstRowHeightPixels = 20
            )
        )
        assertFalse(
            shouldAdvanceMarkdownPrintTableHeader(
                bodyTopPixels = 10,
                bodyBottomPixels = 110,
                bodyY = 85,
                headerHeightPixels = 10,
                firstRowHeightPixels = 95
            )
        )
    }

    /** Creates one minimal semantic Markdown block for print-content tests. */
    private fun markdownBlock(
        text: String,
        kind: MarkdownBlockKind = MarkdownBlockKind.Paragraph,
        spans: List<MarkdownInlineSpan> = emptyList(),
        tableAlignments: List<MarkdownTableAlignment> = emptyList()
    ): MarkdownRenderBlock = MarkdownRenderBlock(
        kind = kind,
        continuesPrevious = false,
        isOrderedListItem = false,
        isTaskChecked = false,
        isTaskUnchecked = false,
        isTableHeader = false,
        containsRawHtml = false,
        headingLevel = 0,
        quoteDepth = 0,
        listDepth = 0,
        listNumber = 0L,
        text = text,
        metadata = "",
        spans = spans,
        tableAlignments = tableAlignments
    )
}
