/* Verifies print-only semantic block assembly and bounded failure behavior. */
package dev.soupslurpr.beautyxt.printing

import dev.soupslurpr.beautyxt.markdown.MARKDOWN_SPAN_STYLE_STRONG
import dev.soupslurpr.beautyxt.markdown.MarkdownBlockKind
import dev.soupslurpr.beautyxt.markdown.MarkdownInlineDestinationKind
import dev.soupslurpr.beautyxt.markdown.MarkdownInlineSpan
import dev.soupslurpr.beautyxt.markdown.MarkdownRenderBlock
import dev.soupslurpr.beautyxt.markdown.MarkdownSourceRange
import dev.soupslurpr.beautyxt.markdown.MarkdownTableAlignment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies bounded paragraph assembly independently from Android layout. */
class MarkdownPrintBlockTest {
    /** Preserves complete blocks without copying or absorbing their neighbors. */
    @Test
    fun reusesCompleteBlock() = runBlocking {
        val first = block("First")
        val result = readMarkdownPrintBlock(listOf(first, block("Second")), 0)
        assertSame(first, result.block)
        assertEquals(1, result.nextIndex)
    }

    /** Later list paragraphs retain their paragraph boundary instead of joining words. */
    @Test
    fun keepsListParagraphsSeparate() = runBlocking {
        val first = block("First paragraph.").copy(
            kind = MarkdownBlockKind.ListItem,
            listDepth = 1
        )
        val second = first.copy(text = "Second paragraph.", continuesListItem = true)
        val blocks = listOf(first, second)
        assertSame(first, readMarkdownPrintBlock(blocks, 0).block)
        assertEquals(1, readMarkdownPrintBlock(blocks, 0).nextIndex)
        assertSame(second, readMarkdownPrintBlock(blocks, 1).block)
        assertEquals(2, readMarkdownPrintBlock(blocks, 1).nextIndex)
    }

    /** Rejoins styles across transport boundaries while retaining the first block's semantics. */
    @Test
    fun joinsTextStylesAndSourceRange() = runBlocking {
        val first = block("Bold ").copy(
            kind = MarkdownBlockKind.ListItem,
            isOrderedListItem = true,
            listNumber = 42,
            metadata = "first metadata",
            spans = listOf(MarkdownInlineSpan(0, 5, MARKDOWN_SPAN_STYLE_STRONG, null)),
            source = MarkdownSourceRange(3, 8)
        )
        val second = first.copy(
            text = "text!",
            continuesPrevious = true,
            metadata = "",
            source = MarkdownSourceRange(8, 13)
        )
        val result = readMarkdownPrintBlock(listOf(first, second, block("Next")), 0)
        assertEquals(
            first.copy(
                text = "Bold text!",
                spans = listOf(MarkdownInlineSpan(0, 10, MARKDOWN_SPAN_STYLE_STRONG, null)),
                source = MarkdownSourceRange(3, 13)
            ),
            result.block
        )
        assertEquals(2, result.nextIndex)
    }

    /** Keeps neighboring footnote references separate even when they target the same note. */
    @Test
    fun retainsAdjacentFootnoteReferences() = runBlocking {
        val first = block("[^a]").copy(
            spans = listOf(
                MarkdownInlineSpan(0, 4, 0, "a", MarkdownInlineDestinationKind.FootnoteReference)
            )
        )
        val second = first.copy(continuesPrevious = true)
        val result = readMarkdownPrintBlock(listOf(first, second), 0)
        assertEquals(2, result.block.spans.size)
        assertEquals("11", markdownPrintContent(result.block, mapOf("a" to 1)).text)
    }

    /** Merges alignment metadata for cells split before or after a column separator. */
    @Test
    fun retainsFragmentedTableColumns() = runBlocking {
        val first = block("First\tSec").copy(
            kind = MarkdownBlockKind.TableRow,
            startsTable = true,
            tableAlignments = listOf(MarkdownTableAlignment.Left, MarkdownTableAlignment.Right)
        )
        val second = first.copy(
            text = "ond\t",
            continuesPrevious = true,
            startsTable = false,
            tableAlignments = listOf(MarkdownTableAlignment.Right, MarkdownTableAlignment.Center)
        )
        val third = second.copy(
            text = "Third",
            tableAlignments = listOf(MarkdownTableAlignment.Center)
        )
        val result = readMarkdownPrintBlock(listOf(first, second, third), 0)
        assertEquals("First\tSecond\tThird", result.block.text)
        assertEquals(
            listOf(
                MarkdownTableAlignment.Left,
                MarkdownTableAlignment.Right,
                MarkdownTableAlignment.Center
            ),
            result.block.tableAlignments
        )
        assertEquals(true, result.block.startsTable)
    }

    /** Supports default unaligned table models without manufacturing an extra cell. */
    @Test
    fun joinsTablesWithoutExplicitAlignments() = runBlocking {
        val first = block("a\tb").copy(kind = MarkdownBlockKind.TableRow)
        val second = first.copy(text = "c", continuesPrevious = true)
        val result = readMarkdownPrintBlock(listOf(first, second), 0)
        assertEquals(2, markdownPrintCells(result.block).size)
        assertEquals(
            listOf(MarkdownTableAlignment.None, MarkdownTableAlignment.None),
            result.block.tableAlignments
        )
    }

    /** Accepts the exact budget and rejects oversized input before constructing its joined text. */
    @Test
    fun boundsCompleteAndFragmentedBlocks(): Unit = runBlocking {
        val full = block("a".repeat(MAXIMUM_MARKDOWN_PRINT_BLOCK_UNITS))
        assertSame(full, readMarkdownPrintBlock(listOf(full), 0).block)
        assertThrows(MarkdownPrintBlockLimitException::class.java) {
            runBlocking {
                readMarkdownPrintBlock(listOf(full, block("b").copy(continuesPrevious = true)), 0)
            }
        }
        assertThrows(MarkdownPrintBlockLimitException::class.java) {
            runBlocking { readMarkdownPrintBlock(listOf(block(full.text + "b")), 0) }
        }
    }

    /** Streams code only at existing newlines, preserving continuation offsets and clipped styles. */
    @Test
    fun streamsCodeAtLogicalLineBoundaries() = runBlocking {
        val code = block("First\nSecond\nThird\n").copy(
            kind = MarkdownBlockKind.Code,
            metadata = "kotlin",
            spans = listOf(MarkdownInlineSpan(6, 18, MARKDOWN_SPAN_STYLE_STRONG, null))
        )
        val chunks =
            listOf(
                code.copy(
                    text = code.text.take(9),
                    spans = listOf(code.spans.single().copy(end = 9))
                ),
                code.copy(
                    text = code.text.drop(9),
                    continuesPrevious = true,
                    spans = listOf(code.spans.single().copy(start = 0, end = 9))
                )
            )
        val first = readMarkdownPrintBlock(chunks, 0, maxBlockUtf16Units = 10)
        assertEquals("First\n", first.block.text)
        assertEquals(true, first.continuesAtEnd)
        assertEquals(0, first.nextIndex)
        assertEquals(6, first.nextOffset)
        val second =
            readMarkdownPrintBlock(
                chunks,
                first.nextIndex,
                first.nextOffset,
                maxBlockUtf16Units = 10
            )
        assertEquals("Second\n", second.block.text)
        assertEquals(true, second.block.continuesPrevious)
        assertEquals(true, second.continuesAtEnd)
        assertEquals(
            listOf(MarkdownInlineSpan(0, 7, MARKDOWN_SPAN_STYLE_STRONG, null)),
            second.block.spans
        )
        val third =
            readMarkdownPrintBlock(
                chunks,
                second.nextIndex,
                second.nextOffset,
                maxBlockUtf16Units = 10
            )
        assertEquals("Third\n", third.block.text)
        assertEquals(false, third.continuesAtEnd)
        assertEquals(2, third.nextIndex)
    }

    /** Supports large multiline code while rejecting an individually oversized logical line. */
    @Test
    fun boundsCodeLinesInsteadOfWholeCodeBlocks(): Unit = runBlocking {
        val code = block(
            "line\n".repeat(MAXIMUM_MARKDOWN_PRINT_BLOCK_UNITS)
        ).copy(kind = MarkdownBlockKind.Code)
        var nextIndex = 0
        var nextOffset = 0
        val result = StringBuilder()
        while (nextIndex == 0) {
            val slice = readMarkdownPrintBlock(listOf(code), nextIndex, nextOffset)
            assertTrue(
                slice.block.text.length <= MAXIMUM_MARKDOWN_PRINT_BLOCK_UNITS
            )
            result.append(slice.block.text)
            nextIndex = slice.nextIndex
            nextOffset = slice.nextOffset
        }
        assertEquals(code.text, result.toString())
        assertThrows(MarkdownPrintBlockLimitException::class.java) {
            runBlocking {
                readMarkdownPrintBlock(
                    listOf(code.copy(text = "x".repeat(MAXIMUM_MARKDOWN_PRINT_BLOCK_UNITS + 1))),
                    0
                )
            }
        }
    }

    /** Checks cancellation while scanning and while assembling bounded fragments. */
    @Test
    fun observesCancellationInBothPasses() {
        val blocks = listOf(block("first"), block("second").copy(continuesPrevious = true))
        for (cancelAt in 1..4) {
            var checks = 0
            assertThrows(CancellationException::class.java) {
                runBlocking {
                    readMarkdownPrintBlock(blocks, 0) {
                        checks += 1
                        if (checks == cancelAt) throw CancellationException("cancelled fixture")
                    }
                }
            }
            assertEquals(cancelAt, checks)
        }
    }

    /** Rejects programmer errors instead of silently changing semantic block boundaries. */
    @Test
    fun rejectsInvalidBoundariesAndAlignment() {
        val first = block("First")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { readMarkdownPrintBlock(listOf(first), -1) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { readMarkdownPrintBlock(listOf(first.copy(continuesPrevious = true)), 0) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                readMarkdownPrintBlock(
                    listOf(first.copy(kind = MarkdownBlockKind.Code)),
                    0,
                    startOffset = 2
                )
            }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                readMarkdownPrintBlock(
                    listOf(
                        first,
                        first.copy(kind = MarkdownBlockKind.Code, continuesPrevious = true)
                    ),
                    0
                )
            }
        }
        val row = first.copy(
            kind = MarkdownBlockKind.TableRow,
            tableAlignments = listOf(MarkdownTableAlignment.Left)
        )
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                readMarkdownPrintBlock(
                    listOf(
                        row,
                        row.copy(
                            continuesPrevious = true,
                            tableAlignments = listOf(MarkdownTableAlignment.Right)
                        )
                    ),
                    0
                )
            }
        }
    }

    /** Creates one minimal semantic paragraph for print assembly fixtures. */
    private fun block(text: String) = MarkdownRenderBlock(
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
        listNumber = 0,
        text = text,
        metadata = "",
        spans = emptyList()
    )
}
