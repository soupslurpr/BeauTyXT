package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.unit.dp
import dev.soupslurpr.beautyxt.markdown.MARKDOWN_SPAN_STYLE_CODE
import dev.soupslurpr.beautyxt.markdown.MarkdownBlockKind
import dev.soupslurpr.beautyxt.markdown.MarkdownInlineDestinationKind
import dev.soupslurpr.beautyxt.markdown.MarkdownInlineSpan
import dev.soupslurpr.beautyxt.markdown.MarkdownRenderBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/** Verifies bounded nesting and visible Markdown list markers. */
class MarkdownPreviewLayoutTest {
    @Test
    fun boundsNestedIndent() {
        assertEquals(0.dp, markdownNestedIndent(0))
        assertEquals(24.dp, markdownNestedIndent(2))
        assertEquals(60.dp, markdownNestedIndent(32))
        assertThrows(IllegalArgumentException::class.java) {
            markdownNestedIndent(-1)
        }
    }

    @Test
    fun preservesTaskState() {
        assertEquals(
            MarkdownTaskState.Checked,
            markdownTaskState(listItem(isTaskChecked = true))
        )
        assertEquals(
            MarkdownTaskState.Unchecked,
            markdownTaskState(listItem(isTaskUnchecked = true))
        )
    }

    @Test
    fun removesTaskMarkerAndShiftsSpans() {
        val task =
            listItem(isTaskChecked = true).copy(
                text = "☑ styled",
                spans =
                    listOf(
                        MarkdownInlineSpan(
                            start = 2,
                            end = 8,
                            styles = 1,
                            destination = null
                        )
                    )
            )

        val content = markdownTaskContent(task)

        assertEquals("styled", content.text)
        assertEquals(0, content.spans.single().start)
        assertEquals(6, content.spans.single().end)
    }

    @Test
    fun selectsNonTaskMarkers() {
        assertEquals("•", markdownListMarker(listItem()))
        assertEquals("7.", markdownListMarker(listItem(isOrdered = true, listNumber = 7L)))
        assertNull(markdownListMarker(listItem(isTaskUnchecked = true)))
        assertNull(markdownListMarker(listItem(continuesPrevious = true)))
        assertNull(markdownListMarker(listItem().copy(continuesListItem = true)))
        assertNull(
            markdownListMarker(listItem(isOrdered = true).copy(continuesListItem = true))
        )
    }

    @Test
    fun attachesDestinationsToNativeLinkAnnotations() {
        val destination = "https://example.com/private"
        var clicked: LinkAnnotation? = null
        val listener = LinkInteractionListener { annotation -> clicked = annotation }
        val annotated =
            markdownAnnotatedString(
                text = "Open link",
                spans =
                    listOf(
                        MarkdownInlineSpan(
                            start = 5,
                            end = 9,
                            styles = 0,
                            destination = destination
                        )
                    ),
                linkColor = Color.Blue,
                codeBackground = Color.LightGray,
                linkInteractionListener = listener
            )

        val range = annotated.getLinkAnnotations(0, annotated.length).single()
        val link = range.item as LinkAnnotation.Url
        assertEquals(5, range.start)
        assertEquals(9, range.end)
        assertEquals(destination, link.url)
        link.linkInteractionListener?.onClick(link)
        assertSame(link, clicked)
    }

    @Test
    fun attachesFootnotesToLocalClickableAnnotations() {
        val label = "local-note"
        var clicked: LinkAnnotation? = null
        val listener = LinkInteractionListener { annotation -> clicked = annotation }
        val annotated =
            markdownAnnotatedString(
                text = "[^local-note]",
                spans =
                    listOf(
                        MarkdownInlineSpan(
                            start = 0,
                            end = 13,
                            styles = MARKDOWN_SPAN_STYLE_CODE,
                            destination = label,
                            destinationKind =
                                MarkdownInlineDestinationKind.FootnoteReference
                        )
                    ),
                linkColor = Color.Blue,
                codeBackground = Color.LightGray,
                linkInteractionListener = listener,
                footnoteNumbers = mapOf(label to 1)
            )

        assertEquals("1", annotated.text)
        val range = annotated.getLinkAnnotations(0, annotated.length).single()
        val link = range.item as LinkAnnotation.Clickable
        assertEquals(0, range.start)
        assertEquals(1, range.end)
        assertEquals(BaselineShift.Superscript, annotated.spanStyles.single().item.baselineShift)
        assertEquals(label, link.tag)
        link.linkInteractionListener?.onClick(link)
        assertSame(link, clicked)
    }

    @Test
    fun preservesLaterLinkOffsetsAfterNumberingFootnote() {
        val listener = LinkInteractionListener {}
        val annotated =
            markdownAnnotatedString(
                text = "See [^long] then link",
                spans =
                    listOf(
                        MarkdownInlineSpan(
                            start = 4,
                            end = 11,
                            styles = MARKDOWN_SPAN_STYLE_CODE,
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
                    ),
                linkColor = Color.Blue,
                codeBackground = Color.LightGray,
                linkInteractionListener = listener,
                footnoteNumbers = mapOf("long" to 1)
            )

        assertEquals("See 1 then link", annotated.text)
        val linkRange = annotated.getLinkAnnotations(0, annotated.length)[1]
        assertEquals(11, linkRange.start)
        assertEquals(15, linkRange.end)
        assertEquals("https://example.com", (linkRange.item as LinkAnnotation.Url).url)
    }

    /** Creates one otherwise empty Markdown list item. */
    private fun listItem(
        continuesPrevious: Boolean = false,
        isOrdered: Boolean = false,
        isTaskChecked: Boolean = false,
        isTaskUnchecked: Boolean = false,
        listNumber: Long = 0L
    ): MarkdownRenderBlock = MarkdownRenderBlock(
        kind = MarkdownBlockKind.ListItem,
        continuesPrevious = continuesPrevious,
        isOrderedListItem = isOrdered,
        isTaskChecked = isTaskChecked,
        isTaskUnchecked = isTaskUnchecked,
        isTableHeader = false,
        containsRawHtml = false,
        headingLevel = 0,
        quoteDepth = 0,
        listDepth = 1,
        listNumber = listNumber,
        text = "item",
        metadata = "",
        spans = emptyList()
    )
}
