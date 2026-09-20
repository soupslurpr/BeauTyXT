package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.ui.text.style.TextAlign
import dev.soupslurpr.beautyxt.markdown.MARKDOWN_SPAN_STYLE_STRONG
import dev.soupslurpr.beautyxt.markdown.MarkdownBlockKind
import dev.soupslurpr.beautyxt.markdown.MarkdownInlineSpan
import dev.soupslurpr.beautyxt.markdown.MarkdownRenderBlock
import dev.soupslurpr.beautyxt.markdown.MarkdownTableAlignment
import org.junit.Assert.assertEquals
import org.junit.Test

/** Verifies table cell splitting and UTF-16 span rebasing. */
class MarkdownTableLayoutTest {
    @Test
    fun splitsCellsAndRebasesInlineSpans() {
        val linkDestination = "https://example.com"
        val block =
            tableRow(
                text = "Strong\t😀 link",
                spans =
                    listOf(
                        MarkdownInlineSpan(
                            start = 0,
                            end = 6,
                            styles = MARKDOWN_SPAN_STYLE_STRONG,
                            destination = null
                        ),
                        MarkdownInlineSpan(
                            start = 10,
                            end = 14,
                            styles = 0,
                            destination = linkDestination
                        )
                    )
            )

        val cells = splitMarkdownTableRow(block)

        assertEquals(listOf("Strong", "😀 link"), cells.map(MarkdownTableCell::text))
        assertEquals(listOf(0, 7), cells.map(MarkdownTableCell::renderedStart))
        assertEquals(
            listOf(
                MarkdownInlineSpan(
                    start = 0,
                    end = 6,
                    styles = MARKDOWN_SPAN_STYLE_STRONG,
                    destination = null
                )
            ),
            cells[0].spans
        )
        assertEquals(
            listOf(
                MarkdownInlineSpan(
                    start = 3,
                    end = 7,
                    styles = 0,
                    destination = linkDestination
                )
            ),
            cells[1].spans
        )
    }

    @Test
    fun preservesATrailingEmptyCell() {
        val cells = splitMarkdownTableRow(tableRow(text = "value\t"))

        assertEquals(listOf("value", ""), cells.map(MarkdownTableCell::text))
        assertEquals(listOf(0, 6), cells.map(MarkdownTableCell::renderedStart))
    }

    @Test
    fun countsEveryFlattenedCell() {
        assertEquals(1, markdownTableColumnCount(tableRow(text = "value")))
        assertEquals(3, markdownTableColumnCount(tableRow(text = "one\ttwo\t")))
    }

    @Test
    fun preservesDirectionAwareColumnAlignment() {
        val cells =
            splitMarkdownTableRow(
                tableRow(text = "left\tcenter\tright").copy(
                    tableAlignments =
                        listOf(
                            MarkdownTableAlignment.Left,
                            MarkdownTableAlignment.Center,
                            MarkdownTableAlignment.Right
                        )
                )
            )

        assertEquals(
            listOf(
                MarkdownTableAlignment.Left,
                MarkdownTableAlignment.Center,
                MarkdownTableAlignment.Right
            ),
            cells.map(MarkdownTableCell::alignment)
        )
        assertEquals(TextAlign.Start, markdownTableTextAlign(cells[0].alignment))
        assertEquals(TextAlign.Center, markdownTableTextAlign(cells[1].alignment))
        assertEquals(TextAlign.End, markdownTableTextAlign(cells[2].alignment))
    }

    /** Creates one otherwise empty table-row model. */
    private fun tableRow(
        text: String,
        spans: List<MarkdownInlineSpan> = emptyList()
    ): MarkdownRenderBlock = MarkdownRenderBlock(
        kind = MarkdownBlockKind.TableRow,
        continuesPrevious = false,
        isOrderedListItem = false,
        isTaskChecked = false,
        isTaskUnchecked = false,
        isTableHeader = false,
        containsRawHtml = false,
        headingLevel = 0,
        quoteDepth = 0,
        listDepth = 0,
        listNumber = 0,
        text = text,
        metadata = "",
        spans = spans
    )
}
