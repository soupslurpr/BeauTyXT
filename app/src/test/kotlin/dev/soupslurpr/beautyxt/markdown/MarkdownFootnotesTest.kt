package dev.soupslurpr.beautyxt.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Verifies shared footnote numbering and presentation transformations. */
class MarkdownFootnotesTest {
    /** Verifies references receive precedence before unreferenced definitions. */
    @Test
    fun numbersByFirstReferenceThenDefinition() {
        val blocks =
            listOf(
                reference("second"),
                reference("first"),
                block(MarkdownBlockKind.Footnote, metadata = "first"),
                block(MarkdownBlockKind.Footnote, metadata = "orphan")
            )

        assertEquals(
            mapOf("second" to 1, "first" to 2, "orphan" to 3),
            markdownFootnoteNumbers(blocks)
        )
    }

    /** Verifies text without a mapped reference needs no presentation copy. */
    @Test
    fun preservesTextWithoutMappedReferences() {
        assertNull(
            markdownFootnotePresentation(
                text = "ordinary",
                spans = emptyList(),
                footnoteNumbers = mapOf("note" to 1)
            )
        )
    }

    /** Maps numbered reference substitutions without inventing label positions. */
    @Test
    fun mapsOffsetsThroughNumberedReferences() {
        val sourceText = "See [^long] now"
        val sourceSpan =
            MarkdownInlineSpan(
                start = 4,
                end = 11,
                styles = MARKDOWN_SPAN_STYLE_CODE,
                destination = "long",
                destinationKind = MarkdownInlineDestinationKind.FootnoteReference
            )
        val presentation =
            requireNotNull(
                markdownFootnotePresentation(
                    text = sourceText,
                    spans = listOf(sourceSpan),
                    footnoteNumbers = mapOf("long" to 1)
                )
            )

        assertEquals(4, presentation.presentationUtf16OffsetForSource(4, 15, listOf(sourceSpan)))
        assertEquals(4, presentation.presentationUtf16OffsetForSource(7, 15, listOf(sourceSpan)))
        assertEquals(5, presentation.presentationUtf16OffsetForSource(11, 15, listOf(sourceSpan)))
        assertEquals(6, presentation.presentationUtf16OffsetForSource(12, 15, listOf(sourceSpan)))
        assertEquals(4, presentation.sourceUtf16OffsetForPresentation(4, 15, listOf(sourceSpan)))
        assertEquals(11, presentation.sourceUtf16OffsetForPresentation(5, 15, listOf(sourceSpan)))
        assertEquals(12, presentation.sourceUtf16OffsetForPresentation(6, 15, listOf(sourceSpan)))
    }

    /** Creates one paragraph containing a complete source-style footnote reference. */
    private fun reference(label: String): MarkdownRenderBlock {
        val text = "[^$label]"
        return block(
            kind = MarkdownBlockKind.Paragraph,
            text = text,
            spans =
                listOf(
                    MarkdownInlineSpan(
                        start = 0,
                        end = text.length,
                        styles = MARKDOWN_SPAN_STYLE_CODE,
                        destination = label,
                        destinationKind = MarkdownInlineDestinationKind.FootnoteReference
                    )
                )
        )
    }

    /** Creates one otherwise empty semantic Markdown block. */
    private fun block(
        kind: MarkdownBlockKind,
        text: String = "text",
        metadata: String = "",
        spans: List<MarkdownInlineSpan> = emptyList()
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
        listNumber = 0,
        text = text,
        metadata = metadata,
        spans = spans
    )
}
