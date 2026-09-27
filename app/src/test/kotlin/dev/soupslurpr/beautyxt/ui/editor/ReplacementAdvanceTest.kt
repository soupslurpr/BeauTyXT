package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.ui.text.input.TextFieldValue
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.document.DocumentSearch
import dev.soupslurpr.beautyxt.document.EditorDocument
import dev.soupslurpr.beautyxt.document.SearchCompletion
import dev.soupslurpr.beautyxt.document.SearchCursor
import dev.soupslurpr.beautyxt.document.SearchOptions
import dev.soupslurpr.beautyxt.document.Utf16Range
import dev.soupslurpr.beautyxt.testing.ImmediateSessionTestDispatcher
import dev.soupslurpr.beautyxt.testing.QueuedSessionTestDispatcher
import dev.soupslurpr.beautyxt.testing.TestEditorDocument
import dev.soupslurpr.beautyxt.ui.UiText
import org.junit.Assert.*
import org.junit.Test

class ReplacementAdvanceTest {
    @Test fun aCommittedBatchDoesNotDismissANewerResultsView() {
        Fixture().use { fixture ->
            val session = fixture.session
            session.updateFindResultsExpanded(true, reviewReplacements = true)
            assertTrue(session.applyFindReplacements())
            session.showFind(false)
            session.updateFindResultsExpanded(true, reviewReplacements = false)
            fixture.drain()
            assertEquals("dog dog other", fixture.document.text)
            assertEquals(FindResultsPage.Matches, session.findResultsPage)
            assertEquals(FindInputFocus.Results, session.findInputFocus)
        }
    }

    @Test fun aNewQueryDoesNotInheritReplacementNavigation() {
        for (committed in listOf(false, true)) {
            Fixture().use { fixture ->
                val session = fixture.session
                assertTrue(session.applyFindReplacements(currentOnly = true))
                if (committed) fixture.drainUntil {
                    session.state.metrics!!.revision == 1L && session.findStatus == FindStatus.Searching
                }
                session.updateFindFieldValue(TextFieldValue("other"))
                fixture.drain()
                assertEquals("dog cat other", fixture.document.text)
                assertEquals("other", session.findFieldValue.text)
                assertEquals(-1, session.findResultIndex)
                assertNull(session.findMatch)
            }
        }
    }

    @Test fun closingAndReopeningFindDoesNotRestorePendingReplacementNavigation() {
        Fixture().use { fixture ->
            val session = fixture.session
            assertTrue(session.applyFindReplacements(currentOnly = true))
            fixture.drainUntil { session.state.metrics!!.revision == 1L && session.findStatus == FindStatus.Searching }
            session.closeFind()
            session.showFind(false)
            fixture.drain()
            assertEquals("dog cat other", fixture.document.text)
            assertEquals(-1, session.findResultIndex)
            assertNull(session.findMatch)
        }
    }

    @Test fun newerInputFocusCancelsTheAutomaticJumpWithoutChangingTheQuery() {
        Fixture().use { fixture ->
            val session = fixture.session
            assertTrue(session.applyFindReplacements(currentOnly = true))
            fixture.drainUntil { session.state.metrics!!.revision == 1L && session.findStatus == FindStatus.Searching }
            session.showFind(false)
            fixture.drain()
            assertEquals("dog cat other", fixture.document.text)
            assertEquals(-1, session.findResultIndex)
            assertEquals(FindInputFocus.Query, session.findInputFocus)
        }
    }

    @Test fun anUninterruptedReplacementStillAdvancesPastItsInsertedText() {
        Fixture().use { fixture ->
            assertTrue(fixture.session.applyFindReplacements(currentOnly = true))
            fixture.drain()
            assertEquals("dog cat other", fixture.document.text)
            assertEquals(Utf16Range(4, 7), fixture.session.findMatch!!.range)
        }
    }

    @Test fun theLastVerifiedMatchDoesNotClaimTheEndOfAnIncompleteScope() {
        for (replacement in listOf("cat", "catx")) {
            val original = TestEditorDocument("cat cat cat")
            val document = object : EditorDocument by original {
                override fun compileSearch(query: String, options: SearchOptions): DocumentSearch {
                    val delegate = original.compileSearch(query, options)
                    return object : DocumentSearch by delegate {
                        override fun source(revision: Long, scope: Utf16Range, cursor: SearchCursor, replacement: String?) =
                            delegate.source(revision, scope, cursor, replacement).let {
                                it.copy(hits = it.hits.take(2), completion = SearchCompletion.ContextLimit)
                            }
                    }
                }
            }
            val session = EditorSession("test.txt", EditorDocumentState(document, ImmediateSessionTestDispatcher),
                operationDispatcher = ImmediateSessionTestDispatcher, findDelay = {}, editSynchronizationDelay = {})
            try {
                session.openInitialEditor()
                session.showFind(false)
                session.showReplace()
                session.updateFindFieldValue(TextFieldValue("cat"))
                session.updateReplacementFieldValue(TextFieldValue(replacement))
                assertEquals(2, session.findResults.size)
                assertFalse(session.isFindComplete)
                assertTrue(session.selectFindResult(1))
                assertTrue(session.applyFindReplacements(currentOnly = true))
                assertEquals("cat $replacement cat", original.text)
                assertEquals(UiText.Resource(R.string.replace_no_further_verified_match), session.findActionMessage)
                assertNotNull(session.findCoverageMessage)
            } finally { session.close() }
        }
    }

    private class Fixture : AutoCloseable {
        val document = TestEditorDocument("cat cat other")
        private val worker = QueuedSessionTestDispatcher()
        private val operations = QueuedSessionTestDispatcher()
        val session = EditorSession("test.txt", EditorDocumentState(document, worker),
            operationDispatcher = operations, findDelay = {}, editSynchronizationDelay = {})

        init {
            session.openInitialEditor()
            drain()
            session.showFind(false)
            session.showReplace()
            session.updateFindFieldValue(TextFieldValue("cat"))
            session.updateReplacementFieldValue(TextFieldValue("dog"))
            drain()
            assertTrue(session.selectFindResult(0))
            drain()
        }

        fun drain() = drainUntil { operations.pendingCount == 0 && worker.pendingCount == 0 }

        fun drainUntil(done: () -> Boolean) {
            repeat(200) {
                if (done()) return
                if (!operations.runNext()) worker.runNext()
            }
            check(done()) { "replacement workflow did not settle" }
        }

        override fun close() { session.close(); drain() }
    }
}
