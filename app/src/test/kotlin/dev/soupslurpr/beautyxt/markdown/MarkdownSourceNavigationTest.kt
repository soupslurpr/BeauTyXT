package dev.soupslurpr.beautyxt.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Verifies bidirectional logical-source navigation through Markdown presentation ranges. */
class MarkdownSourceNavigationTest {
    /** Maps invisible outer syntax and one exact equal-length inline run. */
    @Test
    fun mapsStyledTextWithoutTargetingInvisibleSyntax() {
        val block =
            block(
                text = "bold",
                source = MarkdownSourceRange(start = 0L, end = 8L),
                sourceMaps =
                    listOf(
                        MarkdownSourceMap(
                            renderedStart = 0,
                            renderedEnd = 4,
                            source = MarkdownSourceRange(start = 2L, end = 6L)
                        )
                    )
            )

        assertEquals(0, block.renderedUtf16OffsetForSource(0L))
        assertEquals(2, block.renderedUtf16OffsetForSource(4L))
        assertEquals(4, block.renderedUtf16OffsetForSource(7L))
        assertEquals(2L, block.sourceUtf16OffsetForRendered(0))
        assertEquals(4L, block.sourceUtf16OffsetForRendered(2))
        assertEquals(6L, block.sourceUtf16OffsetForRendered(4))
    }

    /** Uses a provenance boundary when rendered and source lengths differ. */
    @Test
    fun mapsSyntheticPresentationToStableSourceBoundaries() {
        val block =
            block(
                text = "☐ ",
                source = MarkdownSourceRange(start = 0L, end = 3L),
                sourceMaps =
                    listOf(
                        MarkdownSourceMap(
                            renderedStart = 0,
                            renderedEnd = 2,
                            source = MarkdownSourceRange(start = 0L, end = 3L)
                        )
                    )
            )

        assertEquals(0L, block.sourceUtf16OffsetForRendered(1))
        assertEquals(3L, block.sourceUtf16OffsetForRendered(2))
        assertEquals(0, block.renderedUtf16OffsetForSource(2L))
        assertEquals(2, block.renderedUtf16OffsetForSource(3L))
    }

    /** Preserves supplementary scalar offsets through an exact mapping run. */
    @Test
    fun mapsUnicodeUtf16OffsetsExactly() {
        val block =
            block(
                text = "a😀b",
                source = MarkdownSourceRange(start = 10L, end = 14L),
                sourceMaps =
                    listOf(
                        MarkdownSourceMap(
                            renderedStart = 0,
                            renderedEnd = 4,
                            source = MarkdownSourceRange(start = 10L, end = 14L)
                        )
                    )
            )

        assertEquals(13L, block.sourceUtf16OffsetForRendered(3))
        assertEquals(3, block.renderedUtf16OffsetForSource(13L))
    }

    /** Moves an imprecise UTF-16 caret to the scalar start with or without an explicit source map. */
    @Test
    fun avoidsMappingInsideSupplementaryCharacters() {
        val source = MarkdownSourceRange(start = 10L, end = 14L)
        for (block in listOf(block("a😀b", source), block("a😀b", source, emptyList()))) {
            assertEquals(11L, block.sourceUtf16OffsetForRendered(2))
            assertEquals(1, block.renderedUtf16OffsetForSource(12L))
            assertEquals(13L, block.sourceUtf16OffsetForRendered(3))
            assertEquals(3, block.renderedUtf16OffsetForSource(13L))
        }
    }

    /** Chooses containing, following, and final blocks across source gaps. */
    @Test
    fun selectsTheNearestRenderedBlockForOneSourceOffset() {
        val blocks =
            listOf(
                block("first", MarkdownSourceRange(start = 2L, end = 7L)),
                block("second", MarkdownSourceRange(start = 10L, end = 16L))
            )

        assertEquals(
            MarkdownRenderedSourcePosition(blockIndex = 0, renderedUtf16Offset = 0),
            markdownRenderedPositionForSourceOffset(blocks, sourceUtf16Offset = 0L)
        )
        assertEquals(
            MarkdownRenderedSourcePosition(blockIndex = 0, renderedUtf16Offset = 3),
            markdownRenderedPositionForSourceOffset(blocks, sourceUtf16Offset = 5L)
        )
        assertEquals(
            MarkdownRenderedSourcePosition(blockIndex = 1, renderedUtf16Offset = 0),
            markdownRenderedPositionForSourceOffset(blocks, sourceUtf16Offset = 8L)
        )
        assertEquals(
            MarkdownRenderedSourcePosition(blockIndex = 1, renderedUtf16Offset = 6),
            markdownRenderedPositionForSourceOffset(blocks, sourceUtf16Offset = 20L)
        )
        assertNull(markdownRenderedPositionForSourceOffset(emptyList(), 0L))
    }

    /** Creates one otherwise ordinary source-mapped paragraph block. */
    private fun block(
        text: String,
        source: MarkdownSourceRange,
        sourceMaps: List<MarkdownSourceMap> =
            listOf(
                MarkdownSourceMap(
                    renderedStart = 0,
                    renderedEnd = text.length,
                    source = source
                )
            )
    ): MarkdownRenderBlock = MarkdownRenderBlock(
        kind = MarkdownBlockKind.Paragraph,
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
        spans = emptyList(),
        source = source,
        sourceMaps = sourceMaps
    )
}
