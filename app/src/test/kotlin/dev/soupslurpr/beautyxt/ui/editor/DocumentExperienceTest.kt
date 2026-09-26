package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.soupslurpr.beautyxt.document.DocumentPatch
import dev.soupslurpr.beautyxt.document.Utf16Range
import dev.soupslurpr.beautyxt.document.inversePatches
import dev.soupslurpr.beautyxt.markdown.MarkdownBlockKind
import dev.soupslurpr.beautyxt.markdown.MarkdownPreviewDocument
import dev.soupslurpr.beautyxt.markdown.MarkdownRenderBlock
import dev.soupslurpr.beautyxt.markdown.MarkdownSourceMap
import dev.soupslurpr.beautyxt.markdown.MarkdownSourceRange
import dev.soupslurpr.beautyxt.testing.ImmediateSessionTestDispatcher
import dev.soupslurpr.beautyxt.testing.TestEditorDocument
import org.junit.Assert.*
import org.junit.Test

class DocumentExperienceTest {
    @Test fun laterFindInputFocusWinsOverAnEarlierPendingReveal() {
        val session = session(TestEditorDocument("cat cat"))
        session.showFind(false)
        session.updateFindFieldValue(TextFieldValue("cat"))
        session.findNext()
        session.showFind(false)
        assertFalse(session.consumeFindReveal())
        assertEquals(FindInputFocus.Query, session.findInputFocus)
        session.findNext()
        assertTrue(session.consumeFindReveal())
        assertEquals(FindInputFocus.Document, session.findInputFocus)
        session.showReplace()
        session.recordFindInputFocus(FindInputFocus.Replacement)
        session.hideReplace()
        assertEquals(FindInputFocus.Query, session.findInputFocus)
        assertFalse(session.findRequestsKeyboard)
        session.close()
    }

    @Test fun separatorOnlyReadingMatchesRevealTheirOwnBoundaryWithoutGuessingASourceRange() {
        val blocks = listOf(block("before").copy(source = MarkdownSourceRange(100, 106),
            sourceMaps = listOf(MarkdownSourceMap(0, 6, MarkdownSourceRange(100, 106)))), block("after"))
        val document = MarkdownPreviewDocument(120, blocks, 0, false)
        val unit = readingSearchUnits(document, false).units.single()
        for (range in listOf(Utf16Range(6, 8), Utf16Range(7, 7))) {
            val hit = dev.soupslurpr.beautyxt.document.SearchHit(range, unit.text.substring(range.start.toInt(), range.end.toInt()), null, "", "")
            val result = readingSearchResult(document, unit, hit)
            assertNull(result.source)
            assertEquals(106L, result.navigationOffset)
            assertEquals(6, result.segments.single().blockStart)
            assertEquals(6, result.segments.single().blockEnd)
        }
    }

    @Test fun aLaterToolChoiceSurvivesNavigationAndRecreationDoesNotRepeatItsEffects() {
        val session = session(TestEditorDocument("cat cat"))
        assertFalse(session.consumeFindReveal())
        session.showFind(false)
        session.updateFindFieldValue(TextFieldValue("cat"))
        session.updateFindResultsExpanded(true)
        session.findNext()
        assertTrue(session.consumeFindReveal())
        assertFalse(session.consumeFindReveal())
        session.collapseFindResultsForNavigation()
        assertFalse(session.isFindResultsExpanded)
        session.findNext()
        session.updateFindResultsExpanded(true)
        session.consumeFindReveal()
        session.collapseFindResultsForNavigation()
        assertTrue(session.isFindResultsExpanded)
        session.close()
    }

    @Test fun exactSelectionSurvivesSourceAndReadingModeChanges() {
        val document = TestEditorDocument("first\n\nsecond")
        val blocks = listOf(block("first"), block("second").copy(source = MarkdownSourceRange(7, 13),
            sourceMaps = listOf(MarkdownSourceMap(0, 6, MarkdownSourceRange(7, 13)))))
        val session = EditorSession("notes.md", EditorDocumentState(document, ImmediateSessionTestDispatcher),
            operationDispatcher = ImmediateSessionTestDispatcher, editSynchronizationDelay = {},
            markdownRenderer = dev.soupslurpr.beautyxt.markdown.client.MarkdownRenderer { _, bytes ->
                MarkdownPreviewDocument(bytes, blocks, 0, false)
            })
        session.openInitialEditor()
        assertTrue(session.selectSource(8, 11))
        session.showMarkdownPreview()
        val selection = DocumentSelection.Reading(0, ReadingPoint(1, 1), ReadingPoint(1, 4))
        assertEquals(selection, session.documentSelection)
        session.showTextEditor()
        assertEquals(DocumentSelection.Source(0, 8, 11), session.documentSelection)
        val draft = session.activeDraft!!
        assertEquals(Utf16Range(8, 11), Utf16Range(draft.edit.snapshot.range.start + draft.textFieldState.selection.min,
            draft.edit.snapshot.range.start + draft.textFieldState.selection.max))
        session.showMarkdownPreview()
        assertEquals(selection, session.documentSelection)
        assertTrue(session.selectReading(selection.focus, selection.anchor))
        session.showTextEditor()
        assertEquals(TextRange(11, 8), session.activeDraft!!.textFieldState.selection)
        session.showMarkdownPreview()
        assertEquals(selection.copy(anchor = selection.focus, focus = selection.anchor), session.documentSelection)
        session.close()
    }

    @Test fun exactSelectionEdgesUseTheSelectedSideOfAnInvisibleFormattingDelimiter() {
        val text = block("Read bold text").copy(source = MarkdownSourceRange(0, 18), sourceMaps = listOf(
            MarkdownSourceMap(0, 5, MarkdownSourceRange(0, 5)),
            MarkdownSourceMap(5, 14, MarkdownSourceRange(7, 16))))
        val model = MarkdownPreviewDocument(18, listOf(text), 0, false)
        assertEquals(Utf16Range(7, 11), DocumentSelection.Reading(0, ReadingPoint(0, 5), ReadingPoint(0, 9)).exactSource(model))
        assertEquals(Utf16Range(0, 5), DocumentSelection.Reading(0, ReadingPoint(0, 0), ReadingPoint(0, 5)).exactSource(model))
        assertEquals(Utf16Range(7, 7), exactReadingSourceRange(text, 5, 5))
    }

    @Test fun remountingUnchangedReadingContentDoesNotRestartFind() {
        val document = TestEditorDocument("cat cat")
        val session = EditorSession("notes.md", EditorDocumentState(document, ImmediateSessionTestDispatcher),
            operationDispatcher = ImmediateSessionTestDispatcher, findDelay = {}, editSynchronizationDelay = {},
            markdownRenderer = dev.soupslurpr.beautyxt.markdown.client.MarkdownRenderer { _, bytes ->
                MarkdownPreviewDocument(bytes, listOf(block("cat cat")), 0, false)
            })
        session.openInitialEditor()
        session.showMarkdownPreview()
        session.onReadingIllustrationsChanged(0)
        session.showFind(false)
        session.updateFindFieldValue(TextFieldValue("cat"))
        session.findNext()
        val results = session.findResults
        val reveal = session.findRevealRequest
        session.onReadingIllustrationsChanged(0)
        assertSame(results, session.findResults)
        assertEquals(0, session.findResultIndex)
        assertEquals(reveal, session.findRevealRequest)
        session.close()
    }

    @Test fun unavailableReadingReturnRestoresItsSourcePositionWithoutChangingFindInputs() {
        val document = TestEditorDocument("first\n\nsecond")
        val blocks = listOf(block("first"), block("second").copy(source = MarkdownSourceRange(7, 13),
            sourceMaps = listOf(MarkdownSourceMap(0, 6, MarkdownSourceRange(7, 13)))))
        var fail = false
        val session = EditorSession(title = "notes.md", state = EditorDocumentState(document, ImmediateSessionTestDispatcher),
            operationDispatcher = ImmediateSessionTestDispatcher, findDelay = {}, editSynchronizationDelay = {},
            markdownRenderer = dev.soupslurpr.beautyxt.markdown.client.MarkdownRenderer { _, bytes ->
                if (fail) error("renderer unavailable")
                MarkdownPreviewDocument(bytes, blocks, 0, false)
            })
        session.openInitialEditor()
        if (session.presentation == EditorPresentation.Text) session.showMarkdownPreview()
        assertTrue(session.markdownPreviewStatus is MarkdownPreviewStatus.Ready)
        assertTrue(session.navigateToReadingBlock(0, 1))
        session.showTextEditor()
        session.showFind(showKeyboard = false)
        session.updateFindFieldValue(TextFieldValue("first"))
        fail = true
        session.returnToDocumentLocation(false)
        assertEquals(EditorPresentation.Text, session.presentation)
        assertEquals(0, session.activeDraft!!.textFieldState.selection.start)
        assertEquals("first", session.findFieldValue.text)
        assertEquals(dev.soupslurpr.beautyxt.ui.UiText.Resource(dev.soupslurpr.beautyxt.R.string.location_source_fallback), session.locationMessage)
        assertEquals(0L, session.state.metrics!!.revision)
        session.close()
    }

    @Test fun bareFormulaConsolidatesOnlyItsProvenOriginalOccurrence() {
        val drawing = dev.soupslurpr.beautyxt.illustration.NativeIllustration(4f, 2f, 0f, emptyList(), 128,
            textRuns = listOf(dev.soupslurpr.beautyxt.illustration.IllustrationTextRun("abc", emptyList())), isTextComplete = true)
        val span = dev.soupslurpr.beautyxt.markdown.MarkdownInlineSpan(0, 3,
            dev.soupslurpr.beautyxt.markdown.MARKDOWN_SPAN_STYLE_MATH, null,
            illustration = dev.soupslurpr.beautyxt.illustration.IllustrationResult.Rendered(drawing,
                dev.soupslurpr.beautyxt.illustration.IllustrationKind.Math))
        val model = MarkdownPreviewDocument(3, listOf(block("abc").copy(spans = listOf(span))), 1, false)
        val units = readingSearchUnits(model, true).units
        val hit = dev.soupslurpr.beautyxt.document.SearchHit(Utf16Range(1, 2), "b", null, "a", "c")
        val visible = readingSearchResult(model, units[0], hit)
        val source = readingSearchResult(model, units[1], hit)
        assertEquals(0, matchingVisibleIllustration(listOf(visible), source))
        assertEquals(-1, matchingVisibleIllustration(listOf(visible), source.copy(source = Utf16Range(2, 3))))
        assertEquals(-1, matchingVisibleIllustration(listOf(visible.copy(source = null)), source))
    }

    @Test fun readingFindUsesVisibleIllustrationRunsAndKeepsUnprovenSourceOccurrencesSeparate() {
        val rect = dev.soupslurpr.beautyxt.illustration.IllustrationTextBox(0, 5, 0f, 0f, 2f, 1f)
        val drawing = dev.soupslurpr.beautyxt.illustration.NativeIllustration(4f, 2f, 0f, emptyList(), 128,
            textRuns = listOf(dev.soupslurpr.beautyxt.illustration.IllustrationTextRun("Start", listOf(rect))), isTextComplete = true)
        val block = block("A[Start] --> B[End]").copy(kind = MarkdownBlockKind.Code, metadata = "mermaid",
            spans = listOf(dev.soupslurpr.beautyxt.markdown.MarkdownInlineSpan(0, 19, 0, null,
                illustration = dev.soupslurpr.beautyxt.illustration.IllustrationResult.Rendered(drawing,
                    dev.soupslurpr.beautyxt.illustration.IllustrationKind.Diagram))))
        val document = MarkdownPreviewDocument(19, listOf(block), 1, false)
        val visible = readingSearchUnits(document, false)
        assertFalse(visible.hasCoverageGaps)
        assertEquals(listOf("Start"), visible.units.map { it.text })
        val both = readingSearchUnits(document, true)
        assertEquals(listOf(SearchRepresentation.DiagramLabel, SearchRepresentation.DiagramSource), both.units.map { it.representation })
        val hit = dev.soupslurpr.beautyxt.document.SearchHit(Utf16Range(0, 5), "Start", null, "", "")
        val result = readingSearchResult(document, visible.units.single(), hit)
        assertNull(result.source)
        assertEquals(Utf16Range(0, 19), result.ownerSource)
        assertEquals(listOf(rect), result.illustration!!.boxes)
    }

    @Test fun readingFindUsesFootnoteNumbersAndMapsTheirWholeDisplayedMarkerExactly() {
        val block = block("See long-name now").copy(spans = listOf(
            dev.soupslurpr.beautyxt.markdown.MarkdownInlineSpan(4, 13, 0, "long-name",
                dev.soupslurpr.beautyxt.markdown.MarkdownInlineDestinationKind.FootnoteReference)))
        val document = MarkdownPreviewDocument(17, listOf(block), 1, false)
        val unit = readingSearchUnits(document, false).units.single()
        assertEquals("See 1 now", unit.text)
        val hit = dev.soupslurpr.beautyxt.document.SearchHit(Utf16Range(4, 5), "1", null, "See ", " now")
        val result = readingSearchResult(document, unit, hit)
        assertEquals(Utf16Range(4, 13), result.source)
        assertEquals(4, result.segments.single().blockStart)
        assertEquals(13, result.segments.single().blockEnd)
    }

    private fun session(document: TestEditorDocument) = EditorSession(
        title = "Test.txt", state = EditorDocumentState(document, ImmediateSessionTestDispatcher),
        operationDispatcher = ImmediateSessionTestDispatcher, findDelay = {}, editSynchronizationDelay = {}
    ).also { it.openInitialEditor() }

    @Test fun typingDoesNotMoveAndReopeningActiveFindKeepsReview() {
        val document = TestEditorDocument("cat cat cat")
        val session = session(document)
        val draft = session.activeDraft!!
        session.showFind()
        session.updateFindFieldValue(TextFieldValue("cat"))
        assertEquals(3, session.findResults.size)
        assertNull(session.findMatch)
        assertSame(draft, session.activeDraft)
        session.showReplace()
        session.updateReplacementFieldValue(TextFieldValue("dog"))
        session.toggleFindResultIncluded(1)
        val calls = document.findCalls.size
        session.showFind()
        assertEquals(calls, document.findCalls.size)
        assertEquals(setOf(1), session.excludedFindResults)
        session.closeFind()
        session.showFind()
        assertEquals("cat", session.findFieldValue.text)
        assertEquals("dog", session.replacementFieldValue.text)
        assertTrue(session.excludedFindResults.isEmpty())
        session.close()
    }

    @Test fun exclusionsApplyTogetherAndUndoRedoAdvanceOnlyOnce() {
        val document = TestEditorDocument("cat cat cat")
        val session = session(document)
        session.showFind()
        session.updateFindFieldValue(TextFieldValue("cat"))
        session.showReplace()
        session.updateReplacementFieldValue(TextFieldValue("kitten"))
        session.toggleFindResultIncluded(1)
        assertTrue(session.applyFindReplacements())
        assertEquals("kitten cat kitten", document.text)
        assertEquals(1L, session.state.metrics!!.revision)
        assertTrue(session.requestUndo())
        assertEquals("cat cat cat", document.text)
        assertEquals(2L, session.state.metrics!!.revision)
        assertTrue(session.requestRedo())
        assertEquals("kitten cat kitten", document.text)
        assertEquals(3L, session.state.metrics!!.revision)
        session.close()
    }

    @Test fun replacementReviewCountsChangesRatherThanIdenticalMatches() {
        val document = TestEditorDocument("cat CAT cat")
        val session = session(document)
        session.showFind(false)
        session.updateFindFieldValue(TextFieldValue("cat"))
        session.showReplace()
        session.updateReplacementFieldValue(TextFieldValue("cat"))
        assertEquals(3, session.findResults.size)
        assertEquals(1, session.includedReplacementCount)
        assertEquals(2, session.unchangedReplacementCount)
        session.toggleFindResultIncluded(1)
        assertEquals(0, session.includedReplacementCount)
        assertFalse(session.canApplyFindReplacements)
        session.toggleFindResultIncluded(1)
        session.toggleFindResultIncluded(0)
        assertEquals(1, session.includedReplacementCount)
        assertEquals(1, session.unchangedReplacementCount)
        assertTrue(session.applyFindReplacements())
        assertEquals("cat cat cat", document.text)
        assertEquals(1L, session.state.metrics!!.revision)
        assertEquals(0, session.includedReplacementCount)
        assertEquals(3, session.unchangedReplacementCount)
        assertFalse(session.canApplyFindReplacements)
        assertTrue(session.requestUndo())
        assertEquals("cat CAT cat", document.text)
        session.close()
    }

    @Test fun leavingFocusedReplacementReviewRestoresTheQueryWithoutOpeningTheKeyboard() {
        val session = session(TestEditorDocument("cat"))
        session.showFind()
        session.showReplace()
        session.recordFindInputFocus(FindInputFocus.Results)
        session.updateFindResultsExpanded(false)
        assertEquals(FindInputFocus.Query, session.findInputFocus)
        assertFalse(session.findRequestsKeyboard)
        session.recordFindInputFocus(FindInputFocus.Results)
        assertEquals(FindInputFocus.Query, session.findInputFocus)
        session.close()
    }

    @Test fun replacementOnlyKeepsExclusionsButPatternChangesClearThem() {
        val session = session(TestEditorDocument("cat cat"))
        session.showFind()
        session.updateFindFieldValue(TextFieldValue("cat"))
        session.showReplace()
        session.toggleFindResultIncluded(0)
        session.updateReplacementFieldValue(TextFieldValue("dog"))
        assertEquals(setOf(0), session.excludedFindResults)
        session.updateFindWholeWord(true)
        assertTrue(session.excludedFindResults.isEmpty())
        session.close()
    }

    @Test fun selectionScopeSurvivesFindNavigationAndExplicitScopedReplacement() {
        val document = TestEditorDocument("cat cat")
        val session = session(document)
        session.activeDraft!!.textFieldState.edit { selection = TextRange(4, 7) }
        session.showFind()
        assertTrue(session.captureSelectionFindScope())
        session.updateFindFieldValue(TextFieldValue("cat"))
        assertTrue(session.findNext())
        assertEquals(Utf16Range(4, 7), session.capturedFindScope)
        session.showReplace()
        session.updateReplacementFieldValue(TextFieldValue("dog"))
        assertTrue(session.applyFindReplacements())
        assertEquals("cat dog", document.text)
        assertFalse(session.isFindScopePaused)
        assertEquals(Utf16Range(4, 7), session.capturedFindScope)
        assertFalse(session.canApplyFindReplacements)
        session.close()
    }

    @Test fun capturedScopeExcludesOrdinaryEdgeInsertions() {
        val range = Utf16Range(4, 8)
        val before = DocumentPatch(Utf16Range(4, 4), "", "abc")
        val after = DocumentPatch(Utf16Range(8, 8), "", "xyz")
        assertEquals(Utf16Range(7, 11), rebaseCapturedRange(range, listOf(before, after)))
        assertEquals(Utf16Range(4, 14), rebaseCapturedRange(range, listOf(before, after), scopedReplacement = true))
        assertNull(rebaseCapturedRange(range, listOf(DocumentPatch(Utf16Range(3, 6), "abc", "x"))))
        assertEquals(Utf16Range(4, 7), rebaseCapturedRange(range, listOf(DocumentPatch(range, "abcd", "xyz")), scopedReplacement = true))
        assertNull(rebaseCapturedRange(range, listOf(DocumentPatch(range, "abcd", "")), scopedReplacement = true))
    }

    @Test fun findExcursionsCoalesceAndReferenceJumpsRemainSeparate() {
        val locations = DocumentLocations()
        val origin = DocumentLocation(0, EditorPresentation.MarkdownPreview, 0)
        val first = origin.copy(offset = 20, caret = 20)
        val last = origin.copy(offset = 60, caret = 60)
        val reference = origin.copy(offset = 90, caret = 90)
        locations.record(origin, first, find = true)
        locations.record(first, last, find = true)
        locations.record(last, reference)
        assertEquals(last, locations.move(false).first)
        assertEquals(origin, locations.move(false).first)
        assertEquals(last, locations.move(true).first)
    }

    @Test fun returnHistorySkipsDeletedAnchorsAndRebasesSurvivors() {
        val locations = DocumentLocations()
        val origin = DocumentLocation(0, EditorPresentation.Text, 1)
        val removed = DocumentLocation(0, EditorPresentation.Text, 5)
        val end = DocumentLocation(0, EditorPresentation.Text, 10)
        locations.record(origin, removed)
        locations.record(removed, end)
        locations.rebase(0, 1, listOf(DocumentPatch(Utf16Range(4, 7), "abc", "")))
        assertEquals(origin.copy(revision = 1) to true, locations.move(false))
        assertEquals(end.copy(revision = 1, offset = 7, caret = 7) to true, locations.move(true))
    }

    @Test fun aDeletedForwardTailDoesNotRewindToAnEarlierVisitToTheSamePassage() {
        val locations = DocumentLocations()
        val first = DocumentLocation(0, EditorPresentation.Text, 0)
        val middle = DocumentLocation(0, EditorPresentation.Text, 4)
        val removed = DocumentLocation(0, EditorPresentation.Text, 8)
        locations.record(first, middle)
        locations.record(middle, first)
        locations.record(first, removed)
        assertEquals(first, locations.move(false).first)
        locations.rebase(0, 1, listOf(DocumentPatch(Utf16Range(8, 9), "x", "")))
        assertEquals(null to true, locations.move(true))
        assertFalse(locations.hasNext)
        assertTrue(locations.hasPrevious)
        assertEquals(middle.copy(revision = 1), locations.move(false).first)
    }

    @Test fun removingAForwardTailPreservesHistoryBeforeADeletedCurrentAnchor() {
        val locations = DocumentLocations()
        val points = (0L..6L step 2).map { DocumentLocation(0, EditorPresentation.Text, it) }
        points.zipWithNext().forEach { (from, to) -> locations.record(from, to) }
        assertEquals(points[2], locations.move(false).first)
        locations.rebase(0, 1, listOf(DocumentPatch(Utf16Range(4, 7), "xyz", "")))
        assertEquals(null to true, locations.move(true))
        assertFalse(locations.hasNext)
        assertEquals(points[1].copy(revision = 1), locations.move(false).first)
        assertEquals(points[0].copy(revision = 1), locations.move(false).first)
    }

    @Test fun adjacentDeletionUndoUsesOneUnambiguousInsertion() {
        val inverse = inversePatches(listOf(DocumentPatch(Utf16Range(0, 1), "a", ""),
            DocumentPatch(Utf16Range(1, 2), "b", "")))
        assertEquals(listOf(DocumentPatch(Utf16Range(0, 0), "", "ab")), inverse)
    }

    @Test fun readingJoinsParagraphsAndChunksButKeepsCellsSeparate() {
        val blocks = listOf(block("one"), block(" two").copy(continuesPrevious = true), block("three"),
            block("cell A\tcell B").copy(kind = MarkdownBlockKind.TableRow))
        val units = readingSearchUnits(MarkdownPreviewDocument(30, blocks, 0, false), false)
        assertEquals(listOf("one two\n\nthree", "cell A", "cell B"), units.units.map { it.text })
        assertFalse(units.hasCoverageGaps)
    }

    @Test fun exactMappingNeverInventsAPartialDecodedSourceRange() {
        val block = block("😀").copy(sourceMaps = listOf(MarkdownSourceMap(0, 2, MarkdownSourceRange(4, 13))))
        assertEquals(Utf16Range(4, 13), exactReadingSourceRange(block, 0, 2))
        assertNull(exactReadingSourceRange(block, 0, 1))
    }

    private fun block(text: String) = MarkdownRenderBlock(MarkdownBlockKind.Paragraph,
        false, false, false, false, false, false, 0, 0, 0, 0, text, "", emptyList(),
        source = MarkdownSourceRange(0, text.length.toLong()),
        sourceMaps = listOf(MarkdownSourceMap(0, text.length, MarkdownSourceRange(0, text.length.toLong()))))
}
