package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.document.Utf16Range
import dev.soupslurpr.beautyxt.markdown.*
import dev.soupslurpr.beautyxt.testing.ImmediateSessionTestDispatcher
import dev.soupslurpr.beautyxt.testing.TestEditorDocument
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DocumentSelectionTest {
    private fun block(text: String, kind: MarkdownBlockKind = MarkdownBlockKind.Paragraph) = MarkdownRenderBlock(kind,
        false, false, false, false, false, false, 0, 0, 0, 0, text, "", emptyList(),
        source = MarkdownSourceRange(0, text.length.toLong()),
        sourceMaps = listOf(MarkdownSourceMap(0, text.length, MarkdownSourceRange(0, text.length.toLong()))))
    private fun model(vararg blocks: MarkdownRenderBlock) = MarkdownPreviewDocument(500, blocks.toList(), 0, false)

    @Test fun individualFootnoteDigitsCopySearchAndExportWithoutImportingTheUnselectedNote() {
        fun reference(text: String, start: Int, end: Int, label: String) = block(text).copy(spans = listOf(
            MarkdownInlineSpan(start, end, MARKDOWN_SPAN_STYLE_FOOTNOTE_REFERENCE, label, MarkdownInlineDestinationKind.FootnoteReference)))
        val earlier = (1..9).map { reference("note$it", 0, 5, "note$it") }
        val marker = reference("See tenth here", 4, 9, "tenth")
        val document = model(*(earlier + marker + block("Private note", MarkdownBlockKind.Footnote).copy(metadata = "tenth")).toTypedArray())
        val presentation = markdownFootnotePresentation(marker.text, marker.spans, markdownFootnoteNumbers(document.blocks))!!
        assertEquals("See 10 here", presentation.text)
        val middle = readingPresentationPoint(9, 0, 5, marker.text, marker.spans, presentation)
        assertEquals(ReadingPoint(9, 4, 1), middle)
        assertEquals(5, readingPresentationOffset(middle, 0, marker.text, marker.spans, presentation))
        assertEquals(middle, atomicReadingPoint(document, middle, false))
        assertEquals(ReadingPoint(9, 9), atomicReadingPoint(document, ReadingPoint(9, 4, 2), true))
        val first = DocumentSelection.Reading(0, ReadingPoint(9, 4), middle)
        val last = first.copy(anchor = middle, focus = ReadingPoint(9, 9))
        assertEquals("1", selectedReadingText(document, first, 20))
        assertEquals("0", selectedReadingText(document, last, 20))
        assertNull(first.exactSource(document))
        assertNull(last.exactSource(document))
        for ((selection, digit) in listOf(first to "1", last to "0")) {
            val excerpt = selectedMarkdown(document, selection)
            assertEquals("<sup>$digit</sup>", excerpt.markdown)
            assertTrue(excerpt.notices.isEmpty())
            assertEquals(digit, excerpt.document.blocks.single().text)
        }
        val whole = first.copy(focus = ReadingPoint(9, 9))
        assertEquals("[^1]\n\n[^1]: Private note", selectedMarkdown(document, whole).markdown)
        val unit = readingSearchUnits(document, false).units.first()
        val position = unit.text.indexOf("10").toLong()
        assertEquals(Utf16Range(position + 1, position + 2), readingUnitScope(unit, last))
        val hit = dev.soupslurpr.beautyxt.document.SearchHit(Utf16Range(position + 1, position + 2), "0", null, "", "")
        val result = readingSearchResult(document, unit, hit)
        assertNull(result.source)
        assertEquals(4, result.segments.single().blockStart)
        assertEquals(1, result.segments.single().markerStart)
        assertEquals(9, result.segments.single().blockEnd)
        val insertion = readingSearchResult(document, unit, hit.copy(range = Utf16Range(position + 1, position + 1), text = ""))
        assertNull(insertion.source)
        assertEquals(1, insertion.segments.single().markerEnd)
    }

    @Test fun displayedListMarkersAreSelectableButAnItemSubstringDoesNotAcquireOne() {
        val document = model(block("first", MarkdownBlockKind.ListItem).copy(isOrderedListItem = true, listNumber = 3, startsList = true),
            block("second", MarkdownBlockKind.ListItem).copy(isOrderedListItem = true, listNumber = 4),
            block("separate", MarkdownBlockKind.ListItem).copy(startsList = true))
        assertEquals("3. first\n4. second\n\n• separate", selectedReadingText(document,
            DocumentSelection.Reading(0, ReadingPoint(0, -3), ReadingPoint(2, 8)), 100))
        assertEquals("irs", selectedReadingText(document,
            DocumentSelection.Reading(0, ReadingPoint(0, 1), ReadingPoint(0, 4)), 100))
        assertEquals("3.", selectedReadingText(document,
            DocumentSelection.Reading(0, ReadingPoint(0, -3), ReadingPoint(0, -1)), 100))
    }

    @Test fun selectingOnlyTheParagraphBoundaryCopiesItsLogicalSeparator() {
        val document = model(block("first"), block("second"))
        assertEquals("\n\n", selectedReadingText(document,
            DocumentSelection.Reading(0, ReadingPoint(0, 5), ReadingPoint(1, 0)), 100))
    }

    @Test fun readingFindPreservesListGroupsParagraphsWithinItemsAndSplitTableCells() {
        val list = model(block("first", MarkdownBlockKind.ListItem).copy(startsList = true, listDepth = 1),
            block("continued", MarkdownBlockKind.ListItem).copy(continuesListItem = true, listDepth = 1),
            block("child", MarkdownBlockKind.ListItem).copy(startsList = true, listDepth = 2),
            block("second", MarkdownBlockKind.ListItem).copy(listDepth = 1),
            block("separate", MarkdownBlockKind.ListItem).copy(startsList = true, listDepth = 1))
        assertEquals(listOf("• first\n\ncontinued\n• child\n• second", "• separate"), readingSearchUnits(list, false).units.map { it.text })
        val table = model(block("A\tlong", MarkdownBlockKind.TableRow),
            block(" cell\tC", MarkdownBlockKind.TableRow).copy(continuesPrevious = true), block("next", MarkdownBlockKind.TableRow))
        assertEquals(listOf("A", "long cell", "C", "next"), readingSearchUnits(table, false).units.map { it.text })
    }

    @Test fun readingSelectionScopeBoundsConsumptionAndRetainsOriginalFlowContext() {
        val document = model(block("before aaa after"), block("second"))
        val unit = readingSearchUnits(document, false).units.single()
        val selection = DocumentSelection.Reading(0, ReadingPoint(0, 8), ReadingPoint(0, 10))
        assertEquals("before aaa after\n\nsecond", unit.text)
        assertEquals(Utf16Range(8, 10), readingUnitScope(unit, selection))
        val across = selection.copy(focus = ReadingPoint(1, 3))
        assertEquals(Utf16Range(8, 21), readingUnitScope(unit, across))
    }

    @Test fun readingSelectionCopiesAcrossChunksAndParagraphsWithoutFormattingSyntax() {
        val document = model(block("First"), block(" line").copy(continuesPrevious = true), block("Second"))
        val selected = DocumentSelection.Reading(0, ReadingPoint(0, 2), ReadingPoint(2, 3))
        assertEquals("rst line\n\nSec", selectedReadingText(document, selected, 100))
        assertEquals(selectedReadingText(document, selected, 100), selectedReadingText(document,
            selected.copy(anchor = selected.focus, focus = selected.anchor), 100))
    }

    @Test fun paragraphExtensionCrossesLongParagraphsAsWholeUnits() {
        val document = model(block("First"), block(" paragraph").copy(continuesPrevious = true),
            block("Second"), block(" paragraph").copy(continuesPrevious = true), block("Third"))
        val next = readingAdjacentParagraphBoundary(document.blocks, ReadingPoint(0, 2), true)
        assertEquals("rst paragraph\n\nSecond paragraph", selectedReadingText(document,
            DocumentSelection.Reading(0, ReadingPoint(0, 2), next), 100))
        assertEquals(next, readingAdjacentParagraphBoundary(document.blocks, ReadingPoint(1, 3), true))
        assertEquals(ReadingPoint(0, 0), readingAdjacentParagraphBoundary(document.blocks, ReadingPoint(3, 4), false))
        assertEquals(ReadingPoint(2, 0), readingAdjacentParagraphBoundary(document.blocks, ReadingPoint(4, 2), false))
        assertEquals(ReadingPoint(4, 5), readingAdjacentParagraphBoundary(document.blocks, ReadingPoint(4, 2), true))
    }

    @Test fun paragraphExtensionRetainsListMarkersAndSkipsHiddenIllustrationFragments() {
        val document = model(block("item", MarkdownBlockKind.ListItem).copy(isOrderedListItem = true, listNumber = 12),
            block(" tail", MarkdownBlockKind.ListItem).copy(continuesPrevious = true),
            block("second paragraph", MarkdownBlockKind.ListItem).copy(continuesListItem = true),
            block("whole diagram", MarkdownBlockKind.Code),
            block("hidden fragment", MarkdownBlockKind.Code).copy(continuesPrevious = true, illustrationContinuation = true),
            block("Last"))
        assertEquals(ReadingPoint(0, -4), readingAdjacentParagraphBoundary(document.blocks, ReadingPoint(2, 1), false))
        assertEquals(ReadingPoint(2, 16), readingAdjacentParagraphBoundary(document.blocks, ReadingPoint(1, 1), true))
        assertEquals(ReadingPoint(3, 13), readingAdjacentParagraphBoundary(document.blocks, ReadingPoint(2, 1), true))
        assertEquals(ReadingPoint(5, 4), readingAdjacentParagraphBoundary(document.blocks, ReadingPoint(3, 1), true))
    }

    @Test fun tablePlainTextNeverAddsUnselectedHeadersOrCells() {
        val document = model(block("Name\tCost", MarkdownBlockKind.TableRow),
            block("Apples\t2", MarkdownBlockKind.TableRow), block("Pears\t3", MarkdownBlockKind.TableRow))
        val selected = DocumentSelection.Reading(0, ReadingPoint(1, 2), ReadingPoint(2, 5))
        assertEquals("ples\t2\nPears", selectedReadingText(document, selected, 100))
    }

    @Test fun wholeFormulasUseOriginalSourceAndNoncollidingFences() {
        val formula = block("Math: \\alpha+\\beta.").copy(spans = listOf(MarkdownInlineSpan(6, 18, MARKDOWN_SPAN_STYLE_MATH, null)))
        val document = model(formula)
        val start = atomicReadingPoint(document, ReadingPoint(0, 9), false)
        val end = atomicReadingPoint(document, ReadingPoint(0, 10), true)
        assertEquals(ReadingPoint(0, 6), start)
        assertEquals(ReadingPoint(0, 18), end)
        assertEquals("```tex\n\\alpha+\\beta\n```", selectedReadingText(document, DocumentSelection.Reading(0, start, end), 100))
        assertEquals("````text\n```\n````", excerptFence("```", "text"))
    }

    @Test fun sizeLimitRefusesCompleteUnicodePayloadWithoutTruncation() {
        val document = model(block("😀é"))
        val selection = DocumentSelection.Reading(0, ReadingPoint(0, 0), ReadingPoint(0, 3))
        assertEquals("😀é", selectedReadingText(document, selection, 6))
        assertThrows(SelectionLimitException::class.java) { selectedReadingText(document, selection, 5) }
    }

    @Test fun nativeSourceSelectionSurvivesFindFocusAndUsesExactCharacters() = runBlocking {
        val source = "one **two**\nthree"
        val session = EditorSession("Test.md", EditorDocumentState(TestEditorDocument(source), ImmediateSessionTestDispatcher),
            operationDispatcher = ImmediateSessionTestDispatcher, findDelay = {}, editSynchronizationDelay = {})
        session.openInitialEditor()
        assertTrue(session.selectSource(4, 11))
        session.showFind()
        assertTrue(session.captureSelectionFindScope())
        assertEquals(Utf16Range(4, 11), session.capturedFindScope)
        assertEquals("**two**", session.selectedPlainText(100))
        session.closeFind()
        assertNotNull(session.documentSelection)
        session.close()
        assertNull(session.documentSelection)
    }

    @Test fun cuttingAndPastingGlobalSourceSelectionAreSingleUndoableActions() {
        val document = TestEditorDocument("first\nsecond\nthird")
        val session = EditorSession("Test.txt", EditorDocumentState(document, ImmediateSessionTestDispatcher),
            operationDispatcher = ImmediateSessionTestDispatcher, findDelay = {}, editSynchronizationDelay = {})
        session.openInitialEditor()
        assertTrue(session.selectSource(2, 15))
        var copied: String? = null
        assertTrue(session.replaceSelectedSource("") { copied = it })
        assertEquals("rst\nsecond\nth", copied)
        assertEquals("fiird", document.text)
        assertTrue(session.requestUndo())
        assertEquals("first\nsecond\nthird", document.text)
        assertTrue(session.selectSource(0, 5))
        assertTrue(session.replaceSelectedSource("new\r\nline"))
        assertEquals("new\nline\nsecond\nthird", document.text)
        assertTrue(session.requestUndo())
        assertEquals("first\nsecond\nthird", document.text)
        session.close()
    }

    @Test fun reloadInheritsInputsWithoutScopeSelectionOrHistory() {
        fun session() = EditorSession("Test.txt", EditorDocumentState(TestEditorDocument("cat cat"), ImmediateSessionTestDispatcher),
            operationDispatcher = ImmediateSessionTestDispatcher, findDelay = {}, editSynchronizationDelay = {}).also { it.openInitialEditor() }
        val original = session()
        val reloaded = session()
        original.selectSource(0, 3)
        original.showFind()
        original.captureSelectionFindScope()
        original.updateFindFieldValue(androidx.compose.ui.text.input.TextFieldValue("cat"))
        original.updateFindWholeWord(true)
        reloaded.inheritFindInputs(original)
        assertEquals("cat", reloaded.findFieldValue.text)
        assertTrue(reloaded.isFindWholeWord)
        assertNull(reloaded.capturedFindScope)
        assertNull(reloaded.documentSelection)
        assertFalse(reloaded.hasPreviousLocation)
        assertEquals(2, reloaded.findResults.size)
        original.close(); reloaded.close()
    }
}
