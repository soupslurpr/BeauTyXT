@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.soupslurpr.beautyxt.document.MAX_FIND_QUERY_UTF16_UNITS
import dev.soupslurpr.beautyxt.document.MAX_REPLACEMENT_REVIEW_UNITS
import dev.soupslurpr.beautyxt.document.Utf16Range
import dev.soupslurpr.beautyxt.testing.ImmediateSessionTestDispatcher
import dev.soupslurpr.beautyxt.testing.QueuedSessionTestDispatcher
import dev.soupslurpr.beautyxt.testing.TestEditorDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FindInputStateTest {
    @Test
    fun rejectsUnsafeUserInputBeforeChangingTextSelectionOrHistory() {
        for (limit in listOf(MAX_FIND_QUERY_UTF16_UNITS, MAX_REPLACEMENT_REVIEW_UNITS)) {
            val field = TextFieldState("dog", TextRange(2, 1))
            val transformation = findInputTransformation(limit)
            for (input in listOf("line\rbreak", "\uD800", "\uDC00", "a".repeat(limit + 1))) {
                field.edit {
                    replace(0, length, input)
                    selection = TextRange(length)
                    with(transformation) { transformInput() }
                }
                assertEquals("dog", field.text.toString())
                assertEquals(TextRange(2, 1), field.selection)
                assertFalse(field.undoState.canUndo)
            }
            field.edit {
                replace(0, length, "line\n😀")
                with(transformation) { transformInput() }
            }
            assertEquals("line\n😀", field.text.toString())
            field.edit {
                replace(0, length, "a".repeat(limit))
                with(transformation) { transformInput() }
            }
            assertEquals(limit, field.text.length)
        }
    }

    @Test
    fun rejectsHiddenQueryUserInputAndUnsafeProgrammaticInputs() {
        val field = TextFieldState("cat", TextRange(3))
        val transformation = findInputTransformation(MAX_FIND_QUERY_UTF16_UNITS) { false }
        field.edit {
            append("x")
            with(transformation) { transformInput() }
        }
        assertEquals("cat", field.text.toString())
        assertFalse(field.undoState.canUndo)
        session(TestEditorDocument("cat")).use { session ->
            session.showReplace(false)
            session.updateFindFieldValue(TextFieldValue("cat", TextRange(2, 1)))
            session.updateReplacementFieldValue(TextFieldValue("dog", TextRange(2, 1)))
            val query = session.findFieldValue
            val replacement = session.replacementFieldValue
            for (input in listOf("line\rbreak", "\uD800", "a".repeat(MAX_FIND_QUERY_UTF16_UNITS + 1))) {
                assertFalse(session.updateFindFieldValue(TextFieldValue(input)))
                assertEquals(query, session.findFieldValue)
            }
            for (input in listOf("line\rbreak", "\uDC00", "a".repeat(MAX_REPLACEMENT_REVIEW_UNITS + 1))) {
                session.updateReplacementFieldValue(TextFieldValue(input))
                assertEquals(replacement, session.replacementFieldValue)
            }
            assertFalse(session.updateFindFieldValue(query.copy(composition = TextRange(0, 3))))
            session.updateReplacementFieldValue(replacement.copy(composition = TextRange(0, 3)))
            assertEquals(query, session.findFieldValue)
            assertEquals(replacement, session.replacementFieldValue)
        }
    }

    @Test
    fun refusesOldReplacementResultsBeforeEitherFieldObserverRuns() {
        for (changeQuery in listOf(false, true)) {
            val document = TestEditorDocument("cat dog cat")
            session(document).use { session ->
                session.showReplace(false)
                session.updateReplacementFieldValue(TextFieldValue("bird"))
                session.updateFindFieldValue(TextFieldValue("cat"))
                assertTrue(session.findNext())
                assertTrue(session.canReplaceCurrent)
                assertTrue(session.canApplyFindReplacements)
                val revision = session.state.metrics!!.revision
                val field = if (changeQuery) session.findFieldState else session.replacementFieldState
                field.edit { replace(0, length, if (changeQuery) "dog" else "fish") }

                assertFalse(session.canReplaceCurrent)
                assertFalse(session.canApplyFindReplacements)
                assertFalse(session.applyFindReplacements(currentOnly = true))
                assertFalse(session.applyFindReplacements())
                assertFalse(session.selectFindResult(0))
                session.toggleFindResultIncluded(0)
                assertTrue(session.excludedFindResults.isEmpty())
                assertEquals("cat dog cat", document.text)
                assertEquals(revision, session.state.metrics!!.revision)

                session.synchronizeFindInputs()
                assertTrue(session.canApplyFindReplacements)
                assertTrue(session.findResults.all {
                    it.hit.text == if (changeQuery) "dog" else "cat"
                })
                assertTrue(session.findResults.all {
                    it.hit.replacement == if (changeQuery) "bird" else "fish"
                })
            }
        }
    }

    @Test
    fun keyboardNavigationSynchronizesTheActualQueryBeforeUsingResults() {
        session(TestEditorDocument("alpha beta")).use { session ->
            session.updateFindFieldValue(TextFieldValue("alpha"))
            session.findFieldState.edit { replace(0, length, "beta") }
            assertTrue(session.findNext())
            assertEquals(Utf16Range(6, 10), session.findMatch!!.range)
            assertEquals("beta", session.findResults.single().hit.text)
        }
    }

    @Test
    fun refusesAnOldContinuationBeforeTheQueryObserverRuns() {
        val text = List(4) { "a ".repeat(1050) }.joinToString("\n") + "\ndog"
        session(TestEditorDocument(text, editWindowUtf16Units = 1024)).use { session ->
            session.updateFindFieldValue(TextFieldValue("a"))
            assertTrue(session.canContinueFind)
            session.findFieldState.edit { replace(0, length, "dog") }
            assertFalse(session.canContinueFind)
            assertFalse(session.continueFind())
            session.synchronizeFindInputs()
            assertEquals("dog", session.findResults.single().hit.text)
            assertFalse(session.hasEarlierFindResults)
        }
    }

    @Test
    fun anUnobservedFieldEditPreventsAnAlreadyComputedSearchPageFromPublishing() {
        val worker = QueuedSessionTestDispatcher()
        val operations = QueuedSessionTestDispatcher()
        val document = TestEditorDocument("alpha beta")
        val session = EditorSession("test.txt", EditorDocumentState(document, worker),
            operationDispatcher = operations, findDelay = {})
        session.use {
            session.openInitialEditor()
            drain(operations, worker)
            assertTrue(session.showFind(false))
            session.updateFindFieldValue(TextFieldValue("alpha"))
            assertTrue(operations.runNext())
            assertTrue(worker.runNext())
            assertTrue(operations.runNext())
            assertTrue(worker.runNext())
            assertEquals("alpha", document.findCalls.single().query)
            session.findFieldState.edit { replace(0, length, "beta") }
            drain(operations, worker)
            assertTrue(session.findResults.isEmpty())
            assertFalse(session.isFindComplete)
            assertNull(session.findMatch)

            session.synchronizeFindInputs()
            drain(operations, worker)
            assertEquals("beta", session.findResults.single().hit.text)
        }
        drain(operations, worker)
    }

    @Test
    fun unobservedInputsPreventPendingMatchNavigationFromMovingTheDocument() {
        for (windowAlreadyLoaded in listOf(false, true)) {
            val worker = QueuedSessionTestDispatcher()
            val operations = QueuedSessionTestDispatcher()
            val session = EditorSession("test.txt", EditorDocumentState(TestEditorDocument("alpha beta"), worker),
                operationDispatcher = operations, findDelay = {})
            session.use {
                session.openInitialEditor()
                drain(operations, worker)
                assertTrue(session.showFind(false))
                session.updateFindFieldValue(TextFieldValue("beta"))
                drain(operations, worker)
                val previous = session.state.activeEdit
                val reveal = session.findRevealRequest
                assertTrue(session.findNext())
                assertTrue(worker.runNext())
                if (windowAlreadyLoaded) {
                    assertTrue(operations.runNext())
                    assertEquals(EditorDocumentStatus.LoadingEditWindow, session.state.status)
                    assertTrue(worker.runNext())
                }
                session.replacementFieldState.edit { replace(0, length, "changed") }
                drain(operations, worker)
                assertSame(previous, session.state.activeEdit)
                assertNull(session.findMatch)
                assertEquals(reveal, session.findRevealRequest)
                assertEquals(EditorDocumentStatus.Ready, session.state.status)
            }
            drain(operations, worker)
        }
    }

    @Test
    fun newerUnobservedInputsKeepTheirViewWhenAnAcceptedReplacementCompletes() {
        for (currentOnly in listOf(true, false)) for (changeQuery in listOf(true, false)) {
            val worker = QueuedSessionTestDispatcher()
            val document = TestEditorDocument("cat dog cat")
            val session = EditorSession("test.txt", EditorDocumentState(document, worker),
                operationDispatcher = ImmediateSessionTestDispatcher, findDelay = {})
            session.use {
                session.openInitialEditor()
                worker.runAll()
                assertTrue(session.showFind(false))
                session.showReplace(false)
                session.updateFindFieldValue(TextFieldValue("cat"))
                session.updateReplacementFieldValue(TextFieldValue("bird"))
                worker.runAll()
                if (currentOnly) {
                    assertTrue(session.selectFindResult(0))
                    worker.runAll()
                    session.consumeFindReveal()
                    session.recordFindInputFocus(if (changeQuery) FindInputFocus.Query else FindInputFocus.Replacement)
                } else {
                    // Model a review whose inputs change before their observer can refresh it.
                    session.updateFindResultsExpanded(true, reviewReplacements = true)
                }
                val reveal = session.findRevealRequest
                val page = session.findResultsPage
                val focus = session.findInputFocus
                assertTrue(session.applyFindReplacements(currentOnly))
                assertEquals(EditorDocumentStatus.ApplyingEdit, session.state.status)
                val field = if (changeQuery) session.findFieldState else session.replacementFieldState
                val input = if (changeQuery) "dog" else "fish"
                field.edit { replace(0, length, input) }
                // Complete the accepted mutation before notifying the input observer.
                worker.runAll()
                assertEquals(if (currentOnly) "bird dog cat" else "bird dog bird", document.text)
                assertEquals(input, field.text.toString())
                assertNull(session.findMatch)
                assertEquals(reveal, session.findRevealRequest)
                assertEquals(page, session.findResultsPage)
                assertEquals(focus, session.findInputFocus)
            }
            worker.runAll()
        }
    }

    @Test
    fun inheritancePreservesDirectedInputsWithoutSharingStateOrLocalHistory() {
        val previous = session(TestEditorDocument("alpha"))
        session(TestEditorDocument("alpha")).use { next ->
            previous.updateFindFieldValue(TextFieldValue("alpha", TextRange(4, 1)))
            previous.updateReplacementFieldValue(TextFieldValue("dog", TextRange(2, 1)))
            assertTrue(previous.findFieldState.undoState.canUndo)
            assertTrue(previous.replacementFieldState.undoState.canUndo)
            previous.closeFind()
            val query = previous.findFieldValue
            val replacement = previous.replacementFieldValue
            val nextQuery = next.findFieldState
            val nextReplacement = next.replacementFieldState
            next.closeFind()
            next.inheritFindInputs(previous)
            next.synchronizeFindInputs()
            assertSame(nextQuery, next.findFieldState)
            assertSame(nextReplacement, next.replacementFieldState)
            assertEquals(query, next.findFieldValue)
            assertEquals(replacement, next.replacementFieldValue)
            assertFalse(next.findFieldState.undoState.canUndo)
            assertFalse(next.replacementFieldState.undoState.canUndo)
            assertFalse(next.isFindVisible)
            assertEquals(FindStatus.Idle, next.findStatus)
            assertTrue(next.findResults.isEmpty())
            previous.close()
            assertEquals(query, next.findFieldValue)
            assertEquals(replacement, next.replacementFieldValue)
        }
        previous.close()
    }

    @Test
    fun selectionUpdatesAndObservationsPreserveEachFieldsLocalHistory() {
        val unchanged = TextFieldState("dog", TextRange(3))
        unchanged.updateCommittedFindInput(TextFieldValue("dog", TextRange(2, 1)))
        assertEquals(TextRange(2, 1), unchanged.selection)
        assertFalse(unchanged.undoState.canUndo)
        for (query in listOf(false, true)) {
            session(TestEditorDocument("cat dog cat")).use { session ->
                session.showReplace(false)
                session.updateFindFieldValue(TextFieldValue("cat", TextRange(3)))
                session.updateReplacementFieldValue(TextFieldValue("dog", TextRange(3)))
                val field = if (query) session.findFieldState else session.replacementFieldState
                val original = field.text.toString()
                field.edit { append("x") }
                session.synchronizeFindInputs()
                field.undoState.undo()
                session.synchronizeFindInputs()
                assertEquals(original, field.text.toString())
                assertTrue(field.undoState.canRedo)
                field.undoState.redo()
                session.synchronizeFindInputs()
                assertEquals(original + "x", field.text.toString())
                assertSame(field, if (query) session.findFieldState else session.replacementFieldState)
            }
        }
    }

    @Test
    fun closingTheSessionReleasesItsInputObserverAndLocalHistory() {
        val operations = QueuedSessionTestDispatcher()
        val session = EditorSession("test.txt",
            EditorDocumentState(TestEditorDocument("cat"), ImmediateSessionTestDispatcher),
            operationDispatcher = operations, findDelay = {})
        session.openInitialEditor()
        operations.runAll()
        assertTrue(session.showFind(false))
        session.updateFindFieldValue(TextFieldValue("cat"))
        session.updateReplacementFieldValue(TextFieldValue("dog"))
        operations.runAll()
        session.close()
        operations.runAll()
        assertEquals("", session.findFieldState.text.toString())
        assertEquals("", session.replacementFieldState.text.toString())
        assertFalse(session.findFieldState.undoState.canUndo)
        assertFalse(session.replacementFieldState.undoState.canUndo)
        session.findFieldState.edit { append("after close") }
        Snapshot.sendApplyNotifications()
        assertEquals(0, operations.pendingCount)
    }

    private fun session(document: TestEditorDocument): EditorSession =
        EditorSession("test.txt", EditorDocumentState(document, ImmediateSessionTestDispatcher),
            operationDispatcher = ImmediateSessionTestDispatcher, findDelay = {}).also {
            it.openInitialEditor()
            check(it.showFind(false))
        }

    private fun drain(operations: QueuedSessionTestDispatcher, worker: QueuedSessionTestDispatcher) {
        repeat(100) {
            if (operations.pendingCount == 0 && worker.pendingCount == 0) return
            if (!operations.runNext()) worker.runNext()
        }
        error("Find work did not settle after 100 continuations")
    }
}
