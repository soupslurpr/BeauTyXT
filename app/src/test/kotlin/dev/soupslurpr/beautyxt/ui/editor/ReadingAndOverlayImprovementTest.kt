package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.soupslurpr.beautyxt.document.SearchCompletion
import dev.soupslurpr.beautyxt.document.SearchCursor
import dev.soupslurpr.beautyxt.document.SearchHit
import dev.soupslurpr.beautyxt.document.SearchPage
import dev.soupslurpr.beautyxt.document.Utf16Range
import dev.soupslurpr.beautyxt.markdown.MarkdownBlockKind
import dev.soupslurpr.beautyxt.markdown.MarkdownInlineDestinationKind
import dev.soupslurpr.beautyxt.markdown.MarkdownInlineSpan
import dev.soupslurpr.beautyxt.markdown.MarkdownPreviewDocument
import dev.soupslurpr.beautyxt.markdown.MarkdownQuoteKind
import dev.soupslurpr.beautyxt.markdown.MarkdownRenderBlock
import dev.soupslurpr.beautyxt.markdown.MarkdownSourceMap
import dev.soupslurpr.beautyxt.markdown.MarkdownSourceRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies Reading Find indexing/windowing, reversible selection transfer, anchors, tables, and handles. */
class ReadingAndOverlayImprovementTest {
    private fun block(
        kind: MarkdownBlockKind,
        text: String,
        sourceStart: Long = 0L,
        sourceEnd: Long = sourceStart + text.length,
        continuesPrevious: Boolean = false,
        isTableHeader: Boolean = false,
        startsTable: Boolean = false,
        headingLevel: Int = if (kind == MarkdownBlockKind.Heading) 1 else 0,
        quoteDepth: Int = 0,
        quoteKind: MarkdownQuoteKind? = null,
        startsQuoteAlert: Boolean = false,
        metadata: String = "",
        footnoteLabel: String? = null,
        spans: List<MarkdownInlineSpan> = emptyList(),
        tableCellSeparators: List<Int> = emptyList(),
        sourceMaps: List<MarkdownSourceMap> = emptyList()
    ): MarkdownRenderBlock = MarkdownRenderBlock(
        kind = kind,
        continuesPrevious = continuesPrevious,
        isOrderedListItem = false,
        isTaskChecked = false,
        isTaskUnchecked = false,
        isTableHeader = isTableHeader,
        containsRawHtml = false,
        headingLevel = headingLevel,
        quoteDepth = quoteDepth,
        listDepth = 0,
        listNumber = 0,
        text = text,
        metadata = metadata,
        spans = spans,
        tableCellSeparators = tableCellSeparators,
        source = MarkdownSourceRange(sourceStart, sourceEnd),
        quoteKind = quoteKind,
        startsQuoteAlert = startsQuoteAlert,
        startsTable = startsTable,
        footnoteLabel = footnoteLabel,
        sourceMaps = sourceMaps
    )

    private fun paragraphBlock(text: String, sourceStart: Long): MarkdownRenderBlock = block(
        kind = MarkdownBlockKind.Paragraph,
        text = text,
        sourceStart = sourceStart,
        sourceMaps = listOf(
            MarkdownSourceMap(0, text.length, MarkdownSourceRange(sourceStart, sourceStart + text.length))
        )
    )

    @Test
    fun readingSourceRangeIndexMatchesBruteForceAndMapsMultiParagraphHits() {
        val blocks = buildList {
            var offset = 0L
            repeat(200) { index ->
                val text = "Paragraph $index contains needle word."
                add(paragraphBlock(text, offset))
                offset += text.length + 2
            }
        }
        val document = MarkdownPreviewDocument(10_000L, blocks, 0, false)
        val units = readingSearchUnits(document, includeSource = false)
        val index = ReadingSourceRangeIndex(document)
        val unit = units.units.single()
        var searchFrom = 0
        var count = 0
        while (true) {
            val found = unit.text.indexOf("needle", searchFrom)
            if (found < 0) break
            val hit = SearchHit(
                range = Utf16Range(found.toLong(), (found + 6).toLong()),
                text = "needle",
                replacement = null,
                before = "",
                after = ""
            )
            val indexed = readingSearchResult(document, unit, hit, index)
            val unindexed = readingSearchResult(document, unit, hit, null)
            assertEquals(unindexed.source, indexed.source)
            assertEquals(unindexed.segments, indexed.segments)
            assertNotNull(indexed.source)
            searchFrom = found + 6
            count++
        }
        assertEquals(200, count)

        // Separator-only match between paragraphs yields boundary without inventing an exact source range.
        val separatorStart = blocks[0].text.length
        val separatorHit = SearchHit(
            range = Utf16Range(separatorStart.toLong(), (separatorStart + 2).toLong()),
            text = "\n\n",
            replacement = null,
            before = "",
            after = ""
        )
        val sepResult = readingSearchResult(document, unit, separatorHit, index)
        assertNull(sepResult.source)
        assertEquals(1, sepResult.segments.size)
        assertEquals(0, sepResult.segments.single().block)
    }

    @Test
    fun readingSourceRangeIndexDetectsOutsideBlockOverlap() {
        val block0 = block(
            kind = MarkdownBlockKind.Paragraph,
            text = "alpha",
            sourceStart = 0L,
            sourceEnd = 10L,
            sourceMaps = listOf(MarkdownSourceMap(0, 5, MarkdownSourceRange(0L, 10L)))
        )
        val block1 = block(
            kind = MarkdownBlockKind.Paragraph,
            text = "beta",
            sourceStart = 4L,
            sourceEnd = 8L,
            sourceMaps = listOf(MarkdownSourceMap(0, 4, MarkdownSourceRange(4L, 8L)))
        )
        val document = MarkdownPreviewDocument(10L, listOf(block0, block1), 0, false)
        val index = ReadingSourceRangeIndex(document)
        val selection = DocumentSelection.Reading(0L, ReadingPoint(0, 0), ReadingPoint(0, 5))
        assertNull(selection.exactSource(document, null))
        assertNull(selection.exactSource(document, index))
    }

    @Test
    fun readingLiteralSearchWindowPreservesTinyScopeSurrogatesAndWholeWordLines() {
        val prefix = "x😀".repeat(60)
        val target = "cat"
        val suffix = "y猫".repeat(60)
        val text = "$prefix\nfoo $target bar\n$suffix"
        val targetStart = text.indexOf(target).toLong()
        val scope = Utf16Range(targetStart, targetStart + target.length)

        val window = readingLiteralSearchWindow(
            text = text,
            scope = scope,
            cursor = SearchCursor(scope.start),
            wholeWord = true,
            maxCandidateUnits = 64,
            maxMatchUnits = 16,
            maxContextUnits = 2048
        )
        assertNotNull(window)
        window!!
        val slice = text.substring(window.windowStart, window.windowEnd)
        assertTrue(slice.contains("foo cat bar"))
        assertEquals(
            "cat",
            slice.substring(window.localScope.start.toInt(), window.localScope.end.toInt())
        )

        // Chunk rebasing keeps hits before candidateEnd and advances cursor to candidateEnd.
        val multiChunk = "a".repeat(200)
        val chunkWindow = readingLiteralSearchWindow(
            text = multiChunk,
            scope = Utf16Range(0L, 200L),
            cursor = SearchCursor(0L),
            wholeWord = false,
            maxCandidateUnits = 50,
            maxMatchUnits = 10,
            maxContextUnits = 500
        )!!
        val rawPage = SearchPage(
            revision = 0L,
            hits = listOf(
                SearchHit(Utf16Range(10L, 12L), "aa", null, "", ""),
                SearchHit(Utf16Range(50L, 52L), "aa", null, "", "")
            ),
            completion = SearchCompletion.Complete,
            next = SearchCursor(60L, true)
        )
        val rebased = rebaseReadingLiteralSearchPage(chunkWindow, 200L, rawPage)
        assertEquals(SearchCompletion.PageLimit, rebased.completion)
        assertEquals(1, rebased.hits.size)
        assertEquals(Utf16Range(10L, 12L), rebased.hits.single().range)
        assertEquals(SearchCursor(50L, false), rebased.next)
    }

    @Test
    fun readingSelectionForSourceRequiresReversibleExactRoundTripAndPreservesDirection() {
        // Two table cells "A" and "B" separated by a generated separator without source map continuity.
        val tableRow = block(
            kind = MarkdownBlockKind.TableRow,
            text = "A\u001FB",
            sourceStart = 0L,
            sourceEnd = 5L,
            tableCellSeparators = listOf(1),
            sourceMaps = listOf(
                MarkdownSourceMap(0, 1, MarkdownSourceRange(0L, 1L)),
                MarkdownSourceMap(2, 3, MarkdownSourceRange(4L, 5L))
            )
        )
        val document = MarkdownPreviewDocument(5L, listOf(tableRow), 0, false)
        // Cross-cell source selection 0..5 cannot round-trip cleanly -> rejected.
        assertNull(
            readingSelectionForSource(document, DocumentSelection.Source(0L, 0L, 5L))
        )
        // Single-cell selection round-trips and preserves reversed direction.
        val reversed = readingSelectionForSource(document, DocumentSelection.Source(0L, 1L, 0L))
        assertNotNull(reversed)
        assertEquals(ReadingPoint(0, 1), reversed!!.anchor)
        assertEquals(ReadingPoint(0, 0), reversed.focus)
        assertEquals(Utf16Range(0L, 1L), reversed.exactSource(document))
    }

    @Test
    fun headingTargetsInsideMultiBlockAlertAndFootnoteTargetsRecordExactBlocks() {
        val alertIntro = block(
            kind = MarkdownBlockKind.Paragraph,
            text = "Intro inside note",
            quoteDepth = 1,
            quoteKind = MarkdownQuoteKind.Note,
            startsQuoteAlert = true,
            sourceStart = 0L
        )
        val alertHeading = block(
            kind = MarkdownBlockKind.Heading,
            headingLevel = 2,
            text = "Alert Heading",
            quoteDepth = 1,
            quoteKind = MarkdownQuoteKind.Note,
            sourceStart = 20L
        )
        val refPara = block(
            kind = MarkdownBlockKind.Paragraph,
            text = "First[^a] and second[^a]",
            spans = listOf(
                MarkdownInlineSpan(5, 9, 0, "a", MarkdownInlineDestinationKind.FootnoteReference),
                MarkdownInlineSpan(20, 24, 0, "a", MarkdownInlineDestinationKind.FootnoteReference)
            ),
            sourceStart = 40L
        )
        val defPara = block(
            kind = MarkdownBlockKind.Footnote,
            text = "Definition text",
            metadata = "a",
            sourceStart = 70L
        )
        val layout = prepareMarkdownPreview(listOf(alertIntro, alertHeading, refPara, defPara))
        val target = layout.headings.target("alert-heading")
        assertNotNull(target)
        assertEquals(0, target!!.itemIndex)
        assertEquals(1, target.blockIndex)
        assertEquals(1, layout.outline.single().blockIndex)

        val defTarget = layout.footnotes.definitionTargets["a"]
        val refTarget = layout.footnotes.referenceTargets["a"]
        assertEquals(MarkdownReadingTarget(2, 3, 0), defTarget)
        assertEquals(MarkdownReadingTarget(1, 2, 5), refTarget)
    }

    @Test
    fun longTableSharesTableIdColumnsAndLogicalRowsAcrossChunksWhileAdjacentTableIsIndependent() {
        val table1Blocks = (0 until 20).map { row ->
            val cols = if (row == 18) "a\u001Fb\u001Fc\u001Fd" else "a\u001Fb"
            val seps = if (row == 18) listOf(1, 3, 5) else listOf(1)
            block(
                kind = MarkdownBlockKind.TableRow,
                text = cols,
                startsTable = row == 0,
                isTableHeader = row == 0,
                tableCellSeparators = seps,
                sourceStart = row * 10L
            )
        }
        val table2Block = block(
            kind = MarkdownBlockKind.TableRow,
            text = "x\u001Fy",
            startsTable = true,
            isTableHeader = true,
            tableCellSeparators = listOf(1),
            sourceStart = 500L
        )
        val items = markdownPreviewItems(table1Blocks + table2Block)
        assertEquals(3, items.size)
        // First two chunks belong to table 1: shared tableId, max column count 4, total rows 20.
        assertEquals(items[0].tableId, items[1].tableId)
        assertEquals(4, items[0].tableColumnCount)
        assertEquals(4, items[1].tableColumnCount)
        assertEquals(20, items[0].tableRowCount)
        assertEquals(20, items[1].tableRowCount)
        assertEquals(0, items[0].tableFirstLogicalRowIndex)
        assertEquals(16, items[1].tableFirstLogicalRowIndex)
        assertFalse(items[0].tableUsesWideLayout)
        // Third item is an independent second table.
        assertEquals(20, items[2].tableId)
        assertEquals(2, items[2].tableColumnCount)
        assertEquals(1, items[2].tableRowCount)
    }

    @Test
    fun selectionHandleAvoidsFloatingCapsuleWhilePreservingSideGutters() {
        val root = Rect(0f, 0f, 400f, 800f)
        val capsule = Rect(100f, 720f, 300f, 784f)

        // Caret directly behind the capsule is hidden.
        assertNull(selectionHandle(Offset(200f, 740f), root, root, 48f, listOf(capsule)))

        // Caret in the side gutter beside the capsule stays visible.
        val sideHandle = selectionHandle(Offset(40f, 740f), root, root, 48f, listOf(capsule))
        assertNotNull(sideHandle)
        assertFalse(capsule.overlaps(sideHandle!!.bounds))

        // Caret just above the capsule clamps above capsule.top instead of overlapping buttons.
        val aboveHandle = selectionHandle(Offset(200f, 705f), root, root, 48f, listOf(capsule))
        assertNotNull(aboveHandle)
        assertTrue(aboveHandle!!.bounds.bottom <= capsule.top)
        assertFalse(capsule.overlaps(aboveHandle.bounds))
    }
}
