/* Verifies bounded outline construction and section lookup. */
package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.markdown.MarkdownBlockKind
import dev.soupslurpr.beautyxt.markdown.MarkdownRenderBlock
import dev.soupslurpr.beautyxt.markdown.MarkdownSourceRange
import dev.soupslurpr.beautyxt.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests the outline independently of Android layout and input state. */
class DocumentOutlineTest {
    @Test
    fun retainsTheHeadingSourcePositionForNavigation() {
        val heading = block("Inside a section").copy(source = MarkdownSourceRange(120, 140))
        val entry = prepareMarkdownPreview(listOf(heading)).outline.single()

        assertEquals(120L, entry.sourceOffset)
    }

    @Test
    fun keepsHierarchyAndRepeatedHeadingsInDocumentOrder() {
        val layout = prepareMarkdownPreview(
            listOf(
                block("Before", heading = 0),
                block("Overview", heading = 1),
                block("Detail", heading = 3),
                block("Body", heading = 0),
                block("Overview", heading = 2)
            )
        )

        assertEquals(
            listOf(
                DocumentOutlineEntry(1, 1, "Overview"),
                DocumentOutlineEntry(2, 3, "Detail"),
                DocumentOutlineEntry(4, 2, "Overview")
            ),
            layout.outline
        )
        assertNull(activeOutlineEntry(layout.outline, 0))
        assertEquals(0, activeOutlineEntry(layout.outline, 1))
        assertEquals(1, activeOutlineEntry(layout.outline, 3))
        assertEquals(2, activeOutlineEntry(layout.outline, 5))
    }

    @Test
    fun omitsSplitHeadingContinuations() {
        val entries = prepareMarkdownPreview(
            listOf(block("A long heading"), block("continues", continuation = true), block("Next"))
        ).outline

        assertEquals(listOf("A long heading", "Next"), entries.map { entry -> entry.title })
        assertEquals(listOf(0, 2), entries.map { entry -> entry.itemIndex })
    }

    @Test
    fun boundsTitlesWithoutSplittingSupplementaryCharacters() {
        val title = "😀".repeat(2_000)
        val entry = prepareMarkdownPreview(listOf(block(title))).outline.single()

        assertEquals("😀".repeat(160), entry.title)
        assertTrue(entry.title.last().isLowSurrogate())
    }

    @Test
    fun namesEmptyHeadingsAndIgnoresNonHeadings() {
        val entries = prepareMarkdownPreview(
            listOf(block("   "), block("Body", heading = 0))
        ).outline

        assertEquals("", entries.single().title)
        assertEquals(
            UiText.Resource(R.string.outline_untitled),
            outlineEntryTitle(entries.single())
        )
        assertTrue(prepareMarkdownPreview(listOf(block("Body", heading = 0))).outline.isEmpty())
        assertNull(activeOutlineEntry(emptyList(), 0))
    }

    @Test
    fun resolvesLargeOutlinesAtTheirBoundaries() {
        val entries = List(16_384) { index -> DocumentOutlineEntry(index * 2, 1, "Section") }

        assertEquals(0, activeOutlineEntry(entries, 0))
        assertEquals(8_192, activeOutlineEntry(entries, 16_385))
        assertEquals(entries.lastIndex, activeOutlineEntry(entries, Int.MAX_VALUE))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeVisiblePositions() {
        activeOutlineEntry(emptyList(), -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidHeadingLevels() {
        DocumentOutlineEntry(0, 7, "Invalid")
    }

    /** Creates one valid heading or paragraph fixture. */
    private fun block(
        text: String,
        heading: Int = 1,
        continuation: Boolean = false
    ): MarkdownRenderBlock = MarkdownRenderBlock(
        kind = if (heading == 0) MarkdownBlockKind.Paragraph else MarkdownBlockKind.Heading,
        continuesPrevious = continuation,
        isOrderedListItem = false,
        isTaskChecked = false,
        isTaskUnchecked = false,
        isTableHeader = false,
        containsRawHtml = false,
        headingLevel = heading,
        quoteDepth = 0,
        listDepth = 0,
        listNumber = 0,
        text = text,
        metadata = "",
        spans = emptyList()
    )
}
