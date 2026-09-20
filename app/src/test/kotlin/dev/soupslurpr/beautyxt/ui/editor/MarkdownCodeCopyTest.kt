package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.markdown.MarkdownBlockKind
import dev.soupslurpr.beautyxt.markdown.MarkdownQuoteKind
import dev.soupslurpr.beautyxt.markdown.MarkdownRenderBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies exact, whole-block copying without weakening lazy rendering or memory bounds. */
class MarkdownCodeCopyTest {
    @Test
    fun retainsOriginalSingleBlockText() {
        val code = code("\tprint(\"café 😀 <script>\")  \n\n")
        val copy = markdownCodeCopies(listOf(code)).getValue(0)
        assertSame(code.text, copy.textOrNull())
        assertTrue(copy.canCopy)
    }

    @Test
    fun joinsFragmentsWithoutInventingSeparatorsOrTrimmingWhitespace() {
        val copy = markdownCodeCopies(
            listOf(code("  first"), code("\tsecond\n", true), code("\nlast  ", true))
        ).getValue(0)
        assertEquals("  first\tsecond\n\nlast  ", copy.textOrNull())
    }

    @Test
    fun includesOffscreenFragmentsBeyondOneLazyItem() {
        val blocks = List(12) { index -> code("line $index\n", index != 0) }
        val layout = prepareMarkdownPreview(blocks)
        assertEquals(listOf(8, 4), layout.items.map { it.blocks.size })
        assertEquals(setOf(0), layout.codeCopies.keys)
        assertEquals(
            blocks.joinToString("") {
                it.text
            },
            layout.codeCopies.getValue(0).textOrNull()
        )
    }

    @Test
    fun keepsAdjacentSemanticCodeBlocksSeparate() {
        val copies = markdownCodeCopies(listOf(code("one\n"), code("two\n")))
        assertEquals(setOf(0, 1), copies.keys)
        assertEquals("one\n", copies.getValue(0).textOrNull())
        assertEquals("two\n", copies.getValue(1).textOrNull())
    }

    @Test
    fun doesNotCopyProseOrOrphanedContinuations() {
        val copies = markdownCodeCopies(
            listOf(
                code("orphan", true),
                code("prose").copy(kind = MarkdownBlockKind.Paragraph),
                code("code"),
                code("not code", true).copy(kind = MarkdownBlockKind.Paragraph)
            )
        )
        assertEquals(setOf(2), copies.keys)
        assertEquals("code", copies.getValue(2).textOrNull())
    }

    @Test
    fun includesCodeInsideListsQuotesAndAlerts() {
        val first = code("quoted ").copy(quoteDepth = 1, quoteKind = MarkdownQuoteKind.Note)
        val second = first.copy(text = "code\n", continuesPrevious = true)
        val listed = code("nested\n").copy(listDepth = 2)
        val copies = prepareMarkdownPreview(listOf(first, second, listed)).codeCopies
        assertEquals("quoted code\n", copies.getValue(0).textOrNull())
        assertEquals("nested\n", copies.getValue(2).textOrNull())
    }

    @Test
    fun acceptsExactUnicodeLimitAcrossFragments() {
        val part = "😀".repeat(MAX_MARKDOWN_CODE_COPY_UTF16_UNITS / 4)
        val copy = markdownCodeCopies(listOf(code(part), code(part, true))).getValue(0)
        assertTrue(copy.canCopy)
        assertEquals(MAX_MARKDOWN_CODE_COPY_UTF16_UNITS.toLong(), copy.utf16Length)
        assertEquals(part + part, copy.textOrNull())
    }

    @Test
    fun rejectsOversizedCodeWithoutReturningAPrefix() {
        val part = "a".repeat(MAX_MARKDOWN_CODE_COPY_UTF16_UNITS)
        val copy = markdownCodeCopies(listOf(code(part), code("b", true))).getValue(0)
        assertFalse(copy.canCopy)
        assertFalse(copy.isEmpty)
        assertNull(copy.textOrNull())
    }

    @Test
    fun emptyCodeDoesNotClearTheClipboard() {
        val copy = markdownCodeCopies(listOf(code(""))).getValue(0)
        assertFalse(copy.canCopy)
        assertTrue(copy.isEmpty)
        assertNull(copy.textOrNull())
        assertTrue(markdownCodeCopies(emptyList()).isEmpty())
    }

    @Test
    fun reindexingANewRevisionDoesNotReuseOldCode() {
        val old = prepareMarkdownPreview(listOf(code("old")))
        val new = prepareMarkdownPreview(listOf(code("new")))
        assertEquals("old", old.codeCopies.getValue(0).textOrNull())
        assertEquals("new", new.codeCopies.getValue(0).textOrNull())
    }

    private fun code(text: String, continues: Boolean = false) = MarkdownRenderBlock(
        kind = MarkdownBlockKind.Code,
        continuesPrevious = continues,
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
        spans = emptyList()
    )
}
