package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.markdown.*
import org.junit.Assert.*
import org.junit.Test

class ExcerptMarkdownTest {
    @Test fun preservesHardBreaksAndEscapesLiteralBlockStartsAndLinkEntities() {
        assertEquals("first<br>second", excerpt(listOf(block("first\nsecond"))).markdown)
        assertEquals("<strong>first<br>second</strong>", excerpt(listOf(block("first\nsecond",
            spans = listOf(MarkdownInlineSpan(0, 12, MARKDOWN_SPAN_STYLE_STRONG, null))))).markdown)
        assertEquals("1\\) Literal", excerpt(listOf(block("prefix 1) Literal")), ReadingPoint(0, 7)).markdown)
        val destination = "https://example.com/?q=&amp;"
        assertEquals("[label](<https://example.com/?q=&amp;amp;>)", excerpt(listOf(block("label",
            spans = listOf(MarkdownInlineSpan(0, 5, 0, destination))))).markdown)
    }

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
        fun row(text: String) = block(text, MarkdownBlockKind.TableRow).copy(tableCellSeparators = text.indices.filter { text[it] == '\t' }, tableAlignments = listOf(
            MarkdownTableAlignment.None, MarkdownTableAlignment.Right, MarkdownTableAlignment.None))
        val rows = listOf(row("Name\tQty\tNote").copy(isTableHeader = true, startsTable = true),
            row("Ada\t2\tready"), row("Bo\t5\tlater"))
        val result = excerpt(rows, ReadingPoint(1, 1), ReadingPoint(2, 4))
        assertEquals("| Name | Qty | Note |\n| --- | ---: | --- |\n| da | 2 | ready |\n| Bo | 5 |  |", result.markdown)
        assertEquals(listOf(ExcerptNoticeKind.AddedHeaders), result.notices.map { it.kind })
        assertFalse(result.markdown.contains("later"))
        assertEquals("da", excerpt(rows, ReadingPoint(1, 1), ReadingPoint(1, 3)).markdown)
    }

    @Test fun preservesAllSelectedHeadersBlankRowsAndHeaderlessTables() {
        fun row(text: String, header: Boolean = false, first: Boolean = false) = block(text, MarkdownBlockKind.TableRow)
            .copy(isTableHeader = header, startsTable = first, tableCellSeparators = listOf(text.indexOf('\t')),
                tableAlignments = List(2) { MarkdownTableAlignment.None })
        val headers = excerpt(listOf(row("A\tB", true, true), row("C\tD", true), row("E\tF")))
        assertEquals("<table>\n<tr><th>A</th><th>B</th></tr>\n<tr><th>C</th><th>D</th></tr>\n<tr><td>E</td><td>F</td></tr>\n</table>", headers.markdown)
        assertEquals(listOf("A\tB", "C\tD", "E\tF"), headers.document.blocks.map { it.text })
        val blank = excerpt(listOf(row("A\tB", true, true), row("\t"), row("C\tD")))
        assertEquals("| A | B |\n| --- | --- |\n|  |  |\n| C | D |", blank.markdown)
        val headerless = excerpt(listOf(row("A\tB", first = true), row("C\tD")))
        assertEquals("<table>\n<tr><td>A</td><td>B</td></tr>\n<tr><td>C</td><td>D</td></tr>\n</table>", headerless.markdown)
        assertTrue(headerless.document.blocks.none { it.isTableHeader })
    }

    @Test fun preservesLiteralDataTabsAndRebasesFragmentedCellBoundaries() {
        val first = block("A\tB", MarkdownBlockKind.TableRow).copy(isTableHeader = true, startsTable = true,
            tableCellSeparators = listOf(1), tableAlignments = List(2) { MarkdownTableAlignment.None })
        val data = first.copy(text = "a\tb", isTableHeader = false, startsTable = false, tableCellSeparators = emptyList(),
            tableAlignments = listOf(MarkdownTableAlignment.None))
        val continuation = data.copy(text = " tail\tc", continuesPrevious = true, tableCellSeparators = listOf(5),
            tableAlignments = List(2) { MarkdownTableAlignment.None })
        val result = excerpt(listOf(first, data, continuation))
        assertEquals("| A | B |\n| --- | --- |\n| a\tb tail | c |", result.markdown)
        assertEquals(listOf(8), result.document.blocks.last().tableCellSeparators)
    }

    @Test fun includesCompleteMixedAndNonparagraphFootnotesWithoutUnselectedSiblings() {
        val claim = block("Claim[^n]", spans = listOf(MarkdownInlineSpan(5, 9,
            MARKDOWN_SPAN_STYLE_FOOTNOTE_REFERENCE, "n", MarkdownInlineDestinationKind.FootnoteReference)))
        fun owned(text: String, kind: MarkdownBlockKind) = block(text, kind).copy(footnoteLabel = "n")
        val definition = listOf(owned("First", MarkdownBlockKind.Footnote).copy(metadata = "n"),
            owned("Detail", MarkdownBlockKind.ListItem).copy(listDepth = 1, startsList = true),
            owned("exact code\n", MarkdownBlockKind.Code).copy(metadata = "rust"),
            owned("Heading", MarkdownBlockKind.Heading).copy(headingLevel = 2),
            owned("Last", MarkdownBlockKind.Footnote).copy(metadata = "n"))
        val result = excerpt(listOf(claim) + definition + block("PRIVATE SIBLING"), end = ReadingPoint(0, 9))
        assertEquals("Claim[^1]\n\n[^1]: First\n\n    - Detail\n\n    ```rust\n    exact code\n    ```\n\n    ## Heading\n\n    Last", result.markdown)
        assertFalse(result.markdown.contains("PRIVATE"))
        assertEquals(1, result.notices.count { it.kind == ExcerptNoticeKind.AddedNote })
        val listOnly = excerpt(listOf(claim, definition[1]), end = ReadingPoint(0, 9))
        assertEquals("Claim[^1]\n\n[^1]:\n    - Detail", listOnly.markdown)
        assertTrue(listOnly.notices.none { it.kind == ExcerptNoticeKind.MissingNote })
    }

    @Test fun preservesCodeOnlyNestedItemsWithinFootnotesAndDoesNotImportUnselectedParents() {
        val claim = block("Claim[^n]", spans = listOf(MarkdownInlineSpan(5, 9,
            MARKDOWN_SPAN_STYLE_FOOTNOTE_REFERENCE, "n", MarkdownInlineDestinationKind.FootnoteReference)))
        val outer = block("", MarkdownBlockKind.ListItem).copy(listDepth = 1, startsList = true,
            source = MarkdownSourceRange(20, 80), footnoteLabel = "n")
        val nested = outer.copy(listDepth = 2, source = MarkdownSourceRange(30, 70))
        val code = block("exact\n", MarkdownBlockKind.Code).copy(metadata = "text", footnoteLabel = "n",
            source = MarkdownSourceRange(40, 60))
        val result = excerpt(listOf(claim, outer, nested, code), end = ReadingPoint(0, 9))
        assertEquals("Claim[^1]\n\n[^1]:\n    - \n      - \n        ```text\n        exact\n        ```", result.markdown)
        val detached = excerpt(listOf(outer.copy(footnoteLabel = null), code.copy(footnoteLabel = null)), ReadingPoint(1, 0))
        assertEquals("```text\nexact\n```", detached.markdown)
    }

    @Test fun keepsAnEmptySelectedMarkerAndItsCodeAsOneListItem() {
        val marker = block("", MarkdownBlockKind.ListItem).copy(listDepth = 1, startsList = true,
            source = MarkdownSourceRange(0, 30))
        assertEquals("- ", excerpt(listOf(marker), ReadingPoint(0, -2)).markdown)
        val code = block("exact\n", MarkdownBlockKind.Code).copy(metadata = "text", source = MarkdownSourceRange(2, 30))
        assertEquals("- \n  ```text\n  exact\n  ```", excerpt(listOf(marker, code), ReadingPoint(0, -2)).markdown)
        assertEquals("> - \n>   ```text\n>   exact\n>   ```",
            excerpt(listOf(marker.copy(quoteDepth = 1), code.copy(quoteDepth = 1)), ReadingPoint(0, -2)).markdown)
        val ambiguous = marker.copy(listDepth = 1, startsList = false)
        assertEquals("- \n- \n\n```text\nexact\n```",
            excerpt(listOf(marker, ambiguous, code), ReadingPoint(0, -2)).markdown)
    }

    @Test fun keepsBlankSeparatorsInsideANonemptyQuotedListParent() {
        val parent = block("outer", MarkdownBlockKind.ListItem).copy(listDepth = 1, startsList = true,
            quoteDepth = 1, source = MarkdownSourceRange(0, 100))
        val quote = parent.copy(text = "quote", startsList = false, continuesListItem = true,
            quoteDepth = 2, source = MarkdownSourceRange(0, 100))
        val code = block("exact\n", MarkdownBlockKind.Code).copy(metadata = "text", quoteDepth = 1,
            source = MarkdownSourceRange(40, 60))
        assertEquals("> - outer\n>\n>   > quote\n>\n>   ```text\n>   exact\n>   ```",
            excerpt(listOf(parent, quote, code)).markdown)
        val nestedAlert = quote.copy(quoteKind = MarkdownQuoteKind.Note, startsQuoteAlert = true, quoteAlertDepth = 2)
        assertEquals("> - outer\n>\n>   > [!NOTE]\n>   > quote", excerpt(listOf(parent, nestedAlert)).markdown)
        val firstAlert = block("first").copy(quoteDepth = 1, quoteKind = MarkdownQuoteKind.Note, startsQuoteAlert = true,
            quoteAlertDepth = 1)
        val secondAlert = firstAlert.copy(text = "second", quoteKind = MarkdownQuoteKind.Tip)
        assertEquals("> [!NOTE]\n> first\n\n> [!TIP]\n> second", excerpt(listOf(firstAlert, secondAlert)).markdown)
    }

    @Test fun emitsAnEnclosingAlertBeforeItsNestedFirstSelectedContent() {
        val first = block("PRIVATE first").copy(quoteDepth = 1, quoteKind = MarkdownQuoteKind.Note,
            startsQuoteAlert = true, quoteAlertDepth = 1)
        val nested = first.copy(text = "deeper", quoteDepth = 2, startsQuoteAlert = false)
        val after = first.copy(text = "after", startsQuoteAlert = false)
        val clipped = excerpt(listOf(first, nested, after), ReadingPoint(1, 0))
        assertEquals("> [!NOTE]\n> > deeper\n>\n> after", clipped.markdown)
        assertTrue(clipped.document.blocks.first().startsQuoteAlert)
        assertEquals(listOf(2, 1), clipped.document.blocks.map { it.quoteDepth })
        assertEquals(listOf(1, 1), clipped.document.blocks.map { it.quoteAlertDepth })
        val full = excerpt(listOf(nested.copy(startsQuoteAlert = true), after))
        assertEquals(clipped.markdown, full.markdown)
        assertFalse("PRIVATE" in clipped.markdown)
        assertEquals("> > [!NOTE]\n> > deeper", excerpt(listOf(nested.copy(
            startsQuoteAlert = true, quoteAlertDepth = 2))).markdown)
    }

    @Test fun restoresAnActiveOuterAlertAfterGenuineInnerAlerts() {
        val first = block("first").copy(quoteDepth = 1, quoteKind = MarkdownQuoteKind.Note,
            startsQuoteAlert = true, quoteAlertDepth = 1)
        val after = first.copy(text = "after", startsQuoteAlert = false)
        for (kind in listOf(MarkdownQuoteKind.Note, MarkdownQuoteKind.Tip)) {
            val inner = first.copy(text = "inner", quoteDepth = 2, quoteKind = kind, quoteAlertDepth = 2)
            val result = excerpt(listOf(first, inner, after))
            assertEquals("> [!NOTE]\n> first\n>\n> > [!${kind.name.uppercase()}]\n> > inner\n>\n> after", result.markdown)
            assertFalse(result.document.blocks.last().startsQuoteAlert)
        }
    }

    @Test fun separatesAlertParagraphsFromNewListItems() {
        val item = block("item", MarkdownBlockKind.ListItem).copy(listDepth = 1, startsList = true,
            isOrderedListItem = true, listNumber = 2, quoteDepth = 1, quoteKind = MarkdownQuoteKind.Note,
            quoteAlertDepth = 1, startsQuoteAlert = true)
        for (number in listOf(0L, 2L, 100L, 999999999L)) {
            val selected = excerpt(listOf(item.copy(listNumber = number)))
            assertEquals("> [!NOTE]\n>\n> $number. item", selected.markdown)
            assertEquals(number, selected.document.blocks.single().listNumber)
        }
        assertEquals("> [!NOTE]\n>\n> 1. item", excerpt(listOf(item.copy(listNumber = 1))).markdown)
        assertEquals("> [!NOTE]\n>\n> - item", excerpt(listOf(item.copy(isOrderedListItem = false, listNumber = 0))).markdown)
        val first = item.copy(text = "PRIVATE first", listNumber = 1)
        val second = item.copy(text = "second", startsList = false, startsQuoteAlert = false)
        assertEquals("> [!NOTE]\n>\n> 2. second", excerpt(listOf(first, second), ReadingPoint(1, 0)).markdown)
    }

    @Test fun preservesEmptyListMarkersAfterAnAlert() {
        val item = block("", MarkdownBlockKind.ListItem).copy(listDepth = 1, startsList = true,
            isOrderedListItem = true, listNumber = 1, quoteDepth = 1, quoteKind = MarkdownQuoteKind.Note,
            quoteAlertDepth = 1, startsQuoteAlert = true)
        assertEquals("> [!NOTE]\n>\n> 1. ", excerpt(listOf(item), ReadingPoint(0, -3)).markdown)
        assertEquals("> [!NOTE]\n>\n> - ", excerpt(listOf(item.copy(isOrderedListItem = false, listNumber = 0)),
            ReadingPoint(0, -2)).markdown)
    }

    @Test fun joinsCoarseFragmentExtentsBeforeFindingRetainedChildOwnership() {
        val first = block("a".repeat(4096), MarkdownBlockKind.ListItem).copy(listDepth = 1, startsList = true,
            source = MarkdownSourceRange(0, 4098))
        val last = first.copy(text = "a".repeat(904), continuesPrevious = true, startsList = false,
            source = MarkdownSourceRange(4098, 5032))
        val code = block("code child\n", MarkdownBlockKind.Code).copy(metadata = "text", source = MarkdownSourceRange(5005, 5031))
        val result = excerpt(listOf(first, last, code))
        assertEquals(MarkdownSourceRange(0, 5032), result.document.blocks.first().source)
        assertTrue(result.document.blocks.first().sourceMaps.isEmpty())
        assertTrue(result.markdown.endsWith("\n\n  ```text\n  code child\n  ```"))
        assertEquals("```text\ncode child\n```", excerpt(listOf(first, last, code), ReadingPoint(2, 0)).markdown)
    }

    @Test fun keepsNestedSiblingsInTheirGroupAcrossOwnedChildBlocks() {
        val outer = block("outer", MarkdownBlockKind.ListItem).copy(listDepth = 1, startsList = true,
            source = MarkdownSourceRange(0, 200))
        val parent = outer.copy(text = "parent", listDepth = 2, source = MarkdownSourceRange(10, 100))
        val next = parent.copy(text = "next", startsList = false, source = MarkdownSourceRange(110, 150))
        val sibling = outer.copy(text = "sibling", startsList = false, source = MarkdownSourceRange(210, 240))
        val children = listOf(
            block("code child\n", MarkdownBlockKind.Code).copy(metadata = "text"),
            block("heading child", MarkdownBlockKind.Heading).copy(headingLevel = 2),
            block("A\tB", MarkdownBlockKind.TableRow).copy(startsTable = true, tableCellSeparators = listOf(1),
                tableAlignments = List(2) { MarkdownTableAlignment.None })
        )
        for (child in children) {
            val blocks = listOf(outer, parent, child.copy(source = MarkdownSourceRange(40, 80)), next, sibling)
            val result = excerpt(blocks)
            val retained = result.document.blocks.single { it.text == "next" }
            assertEquals(2, retained.listDepth)
            assertFalse(retained.startsList)
            assertTrue(result.markdown.lineSequence().any { it == "  - next" })
            val childOnly = excerpt(blocks, ReadingPoint(2, 0), ReadingPoint(2, child.text.length))
            assertTrue(childOnly.document.blocks.none { it.kind == MarkdownBlockKind.ListItem })
            assertFalse("parent" in childOnly.markdown || "outer" in childOnly.markdown)
        }
    }

    @Test fun sumsEveryRetainedAncestorMarkerWidthAndPromotesMissingItems() {
        fun item(text: String, depth: Int, number: Long, start: Long, end: Long, first: Boolean) =
            block(text, MarkdownBlockKind.ListItem).copy(listDepth = depth, listNumber = number,
                isOrderedListItem = true, startsList = first, source = MarkdownSourceRange(start, end))
        val blocks = listOf(item("outer", 1, 123456789, 0, 500, true),
            item("middle", 2, 123456, 20, 400, true), item("parent", 3, 100, 40, 300, true),
            block("code child\n", MarkdownBlockKind.Code).copy(metadata = "text", source = MarkdownSourceRange(80, 200)),
            item("next", 3, 101, 310, 350, false), item("sibling", 1, 123456790, 510, 550, false))
        val full = excerpt(blocks)
        assertTrue(full.markdown.lineSequence().any { it == " ".repeat(11) + "123456. middle" })
        assertTrue(full.markdown.lineSequence().any { it == " ".repeat(19) + "100. parent" })
        assertTrue(full.markdown.lineSequence().any { it == " ".repeat(24) + "```text" })
        assertEquals(listOf(1, 2, 3, 0, 3, 1), full.document.blocks.map { it.listDepth })
        val missingOuter = excerpt(blocks, ReadingPoint(1, 0))
        assertTrue(missingOuter.markdown.startsWith("123456. middle"))
        assertTrue(missingOuter.markdown.lineSequence().any { it == " ".repeat(8) + "100. parent" })
        assertTrue(missingOuter.markdown.lineSequence().any { it == " ".repeat(13) + "```text" })
        assertEquals("100. parent", excerpt(blocks, ReadingPoint(2, 0), ReadingPoint(2, 6)).markdown)
    }

    @Test fun keepsContinuationProseInItsItemAfterCodeWithoutInventingAParent() {
        val parent = block("parent", MarkdownBlockKind.ListItem).copy(listDepth = 1, listNumber = 100,
            isOrderedListItem = true, startsList = true, source = MarkdownSourceRange(0, 100))
        val code = block("code child\n", MarkdownBlockKind.Code).copy(metadata = "text", source = MarkdownSourceRange(20, 50))
        val continuation = parent.copy(text = "back", listNumber = 0, startsList = false, continuesListItem = true)
        val result = excerpt(listOf(parent, code, continuation))
        assertTrue(result.document.blocks.last().continuesListItem)
        assertTrue(result.markdown.endsWith("\n\n     back"))
        assertFalse("0. back" in result.markdown)
        val ambiguous = parent.copy(text = "other", startsList = false)
        val detached = excerpt(listOf(parent, ambiguous, code))
        assertTrue(detached.markdown.endsWith("\n\n```text\ncode child\n```"))
        assertTrue(detached.document.blocks.all { it.sourceMaps.isEmpty() })
        val coarse = code.copy(source = parent.source)
        assertTrue(excerpt(listOf(parent, coarse)).markdown.endsWith("\n\n```text\ncode child\n```"))
        val crossing = ambiguous.copy(source = MarkdownSourceRange(10, 90))
        assertTrue(excerpt(listOf(parent, crossing, code)).markdown.endsWith("\n\n```text\ncode child\n```"))
    }

    @Test fun enforcesTheOutputByteCapWhileAddingWideAncestorPrefixes() {
        val blocks = (1..32).map { depth ->
            block("", MarkdownBlockKind.ListItem).copy(
                listDepth = depth, startsList = true, isOrderedListItem = true, listNumber = 123456789,
                source = MarkdownSourceRange(0, 100000))
        } + block("\n".repeat(60000), MarkdownBlockKind.Code).copy(source = MarkdownSourceRange(100, 99999))
        assertThrows(SelectionLimitException::class.java) { excerpt(blocks) }
    }

    @Test fun keepsMultilineInlineCodeBreaksAsCodeInTypedListItems() {
        val item = block("owned\n", MarkdownBlockKind.ListItem,
            listOf(MarkdownInlineSpan(0, 6, MARKDOWN_SPAN_STYLE_CODE, null))).copy(listDepth = 1, startsList = true)
        assertEquals("- <code>owned<br></code>", excerpt(listOf(item)).markdown)
    }

    @Test fun usesAnEmittedContinuationMarkerWidthAfterDerivedNumbersGrow() {
        fun item(text: String, number: Long, start: Long, end: Long) = block(text, MarkdownBlockKind.ListItem).copy(
            listDepth = 1, isOrderedListItem = true, listNumber = number, source = MarkdownSourceRange(start, end))
        val blocks = listOf(item("first", 999999999, 0, 17).copy(startsList = true),
            item("second", 1000000000, 17, 100),
            block("code child\n", MarkdownBlockKind.Code).copy(metadata = "text", source = MarkdownSourceRange(40, 50)),
            block("child", MarkdownBlockKind.ListItem).copy(listDepth = 2, startsList = true, source = MarkdownSourceRange(60, 80)),
            item("next", 1000000001, 110, 150))
        val result = excerpt(blocks)
        assertTrue(result.markdown.startsWith("999999999. first\n1. second"))
        assertTrue(result.markdown.lineSequence().any { it == "   ```text" })
        assertTrue(result.markdown.lineSequence().any { it == "   - child" })
        assertEquals(1000000000L, result.document.blocks[1].listNumber)
        assertEquals(1000000001L, result.document.blocks.last().listNumber)
    }

    @Test fun omitsAnUnselectedFirstMarkerWithoutInventingAQuotedParent() {
        val parent = block("parent", MarkdownBlockKind.ListItem).copy(listDepth = 1, listNumber = 100,
            isOrderedListItem = true, startsList = true, source = MarkdownSourceRange(0, 100))
        val quoted = parent.copy(text = "quote child", listNumber = 0, quoteDepth = 1, startsList = false, continuesListItem = true)
        val code = block("code child\n", MarkdownBlockKind.Code).copy(metadata = "text", source = MarkdownSourceRange(40, 60))
        val selected = excerpt(listOf(parent, quoted, code), ReadingPoint(1, 0))
        assertEquals("> quote child\n\n```text\ncode child\n```", selected.markdown)
        assertEquals(listOf(MarkdownBlockKind.Paragraph, MarkdownBlockKind.Code), selected.document.blocks.map { it.kind })
        assertTrue(selected.document.blocks.none { it.isOrderedListItem || it.listNumber != 0L })
        assertEquals("100. arent", excerpt(listOf(parent), ReadingPoint(0, 1)).markdown)
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
