package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.markdown.*
import org.junit.Assert.*
import org.junit.Test

class ExcerptMarkdownTest {
    private fun block(text: String, kind: MarkdownBlockKind = MarkdownBlockKind.Paragraph,
        spans: List<MarkdownInlineSpan> = emptyList()) = MarkdownRenderBlock(kind,
        false, false, false, false, false, false, 0, 0, 0, 0, text, "", spans)
    private fun excerpt(blocks: List<MarkdownRenderBlock>, start: ReadingPoint = ReadingPoint(0, 0),
        end: ReadingPoint = ReadingPoint(blocks.lastIndex, blocks.last().text.length)) = selectedMarkdown(
        MarkdownPreviewDocument(1000, blocks, blocks.sumOf { it.spans.size }, false), DocumentSelection.Reading(0, start, end))

    @Test fun clippedStylesAndLinksKeepOnlySelectedWords() {
        val bold = block("red flowers", spans = listOf(MarkdownInlineSpan(0, 11, MARKDOWN_SPAN_STYLE_STRONG, null)))
        assertEquals("<strong>flowers</strong>", excerpt(listOf(bold), ReadingPoint(0, 4)).markdown)
        val link = block("project guide", spans = listOf(MarkdownInlineSpan(0, 13, 0, "https://example.com/guide")))
        val result = excerpt(listOf(link), ReadingPoint(0, 8))
        assertEquals("[guide](<https://example.com/guide>)", result.markdown)
        assertEquals("https://example.com/guide", result.document.blocks.single().spans.single().destination)
    }

    @Test fun joinsChunksBeforeClosingStylesOrCodeFences() {
        val blocks = listOf(block("First", spans = listOf(MarkdownInlineSpan(0, 5, MARKDOWN_SPAN_STYLE_STRONG, null))),
            block(" second", spans = listOf(MarkdownInlineSpan(0, 7, MARKDOWN_SPAN_STYLE_STRONG, null))).copy(continuesPrevious = true))
        assertEquals("<strong>st sec</strong>", excerpt(blocks, ReadingPoint(0, 3), ReadingPoint(1, 4)).markdown)
        val code = block("    return x", MarkdownBlockKind.Code).copy(metadata = "python")
        assertEquals("```python\n    return x\n```", excerpt(listOf(code)).markdown)
    }

    @Test fun partialTableRestoresOnlyNeededHeadersAndLeavesOtherCellsEmpty() {
        fun row(text: String) = block(text, MarkdownBlockKind.TableRow).copy(tableAlignments = listOf(
            MarkdownTableAlignment.None, MarkdownTableAlignment.Right, MarkdownTableAlignment.None))
        val rows = listOf(row("Name\tQty\tNote").copy(isTableHeader = true, startsTable = true),
            row("Ada\t2\tready"), row("Bo\t5\tlater"))
        val result = excerpt(rows, ReadingPoint(1, 1), ReadingPoint(2, 4))
        assertEquals("| Name | Qty | Note |\n| --- | ---: | --- |\n| da | 2 | ready |\n| Bo | 5 |  |", result.markdown)
        assertEquals(listOf(ExcerptNoticeKind.AddedHeaders), result.notices.map { it.kind })
        assertFalse(result.markdown.contains("later"))
        assertEquals("da", excerpt(rows, ReadingPoint(1, 1), ReadingPoint(1, 3)).markdown)
    }

    @Test fun promotesOrphanedChildrenWithoutMergingTheFollowingParentList() {
        fun item(text: String, depth: Int, number: Long, first: Boolean = false) = block(text, MarkdownBlockKind.ListItem)
            .copy(isOrderedListItem = true, listDepth = depth, listNumber = number, startsList = first)
        val blocks = listOf(item("Parent", 1, 1, true), item("Child A", 2, 1, true),
            item("Child B", 2, 2), item("Child C", 2, 3), item("Next parent", 1, 2))
        val result = excerpt(blocks, ReadingPoint(2, 0))
        assertEquals("2. Child B\n3. Child C\n\n2) Next parent", result.markdown)
        assertEquals(listOf(1, 1, 1), result.document.blocks.map { it.listDepth })
    }

    @Test fun referencedNotesAreIncludedOnceRenumberedAndCyclesDoNotExpandForever() {
        fun ref(text: String, label: String) = block(text, spans = listOf(MarkdownInlineSpan(text.indexOf('['), text.length,
            MARKDOWN_SPAN_STYLE_FOOTNOTE_REFERENCE, label, MarkdownInlineDestinationKind.FootnoteReference)))
        val blocks = listOf(ref("Claim[^old]", "old"),
            ref("Evidence[^other]", "other").copy(kind = MarkdownBlockKind.Footnote, metadata = "old"),
            ref("Again[^old]", "old").copy(kind = MarkdownBlockKind.Footnote, metadata = "other"))
        val result = excerpt(blocks, end = ReadingPoint(0, blocks.first().text.length))
        assertEquals("Claim[^1]\n\n[^1]: Evidence[^2]\n\n[^2]: Again[^1]", result.markdown)
        assertEquals(2, result.notices.count { it.kind == ExcerptNoticeKind.AddedNote })
    }

    @Test fun includedHeadingIdentityIsRemappedAfterDuplicateHeadingRemoval() {
        val heading = block("Results", MarkdownBlockKind.Heading).copy(headingLevel = 3)
        val link = block("see", spans = listOf(MarkdownInlineSpan(0, 3, 0, "#results-1")))
        val result = excerpt(listOf(heading, link, heading), ReadingPoint(1, 0))
        assertEquals("[see](<#results>)\n\n### Results", result.markdown)
        val removed = excerpt(listOf(heading, link, heading), ReadingPoint(1, 0), ReadingPoint(1, 3))
        assertEquals("see", removed.markdown)
        assertEquals(ExcerptNoticeKind.RemovedLink, removed.notices.single().kind)
    }

    @Test fun unsupportedLinksAndLiteralHtmlNeverBecomeActiveOutput() {
        val block = block("script", spans = listOf(MarkdownInlineSpan(0, 6, 0, "javascript:bad")))
        assertEquals("script", excerpt(listOf(block)).markdown)
        assertEquals(1, excerpt(listOf(block)).notices.size)
        assertEquals("```html\n<iframe>\n```", excerpt(listOf(block("<iframe>", MarkdownBlockKind.HtmlLiteral))).markdown)
    }

    @Test fun taskFormattingDoesNotEatTheStartOfAPartialItem() {
        val item = block("☑ Finish this", MarkdownBlockKind.ListItem).copy(isTaskChecked = true, listDepth = 1, startsList = true)
        assertEquals("- [x] Finish this", excerpt(listOf(item), ReadingPoint(0, 2)).markdown)
    }

    @Test fun markerOnlySelectionRemainsVisibleTextWithoutImportingTheItemBody() {
        val item = block("Private words", MarkdownBlockKind.ListItem).copy(isOrderedListItem = true,
            listNumber = 4, listDepth = 1, startsList = true)
        val result = excerpt(listOf(item), ReadingPoint(0, -3), ReadingPoint(0, -1))
        assertEquals("4\\.", result.markdown)
        assertEquals("4.", result.document.blocks.single().text)
        assertEquals(MarkdownBlockKind.Paragraph, result.document.blocks.single().kind)
    }
}
