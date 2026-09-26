package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.ui.text.input.TextFieldValue
import dev.soupslurpr.beautyxt.document.MAX_SEARCH_RESULTS
import dev.soupslurpr.beautyxt.markdown.*
import dev.soupslurpr.beautyxt.markdown.client.MarkdownRenderer
import dev.soupslurpr.beautyxt.testing.ImmediateSessionTestDispatcher
import dev.soupslurpr.beautyxt.testing.TestEditorDocument
import org.junit.Assert.*
import org.junit.Test

class FindContinuationTest {
    private fun denseText(count: Int) = buildString {
        repeat(count) { append('a'); append(if (it % 700 == 699) '\n' else ' ') }
    }

    private fun session(text: String, clock: () -> Long = { 0L }, reading: Boolean = false): EditorSession {
        val renderer = MarkdownRenderer { _, bytes ->
            MarkdownPreviewDocument(bytes, text.split('|').map { value ->
                MarkdownRenderBlock(MarkdownBlockKind.Code, false, false, false, false, false, false,
                    0, 0, 0, 0, value, "", emptyList())
            }, 0, false)
        }
        return EditorSession("Test.txt", EditorDocumentState(TestEditorDocument(text, editWindowUtf16Units = 1024), ImmediateSessionTestDispatcher),
            operationDispatcher = ImmediateSessionTestDispatcher, findDelay = {}, editSynchronizationDelay = {},
            findNanoTime = clock, markdownRenderer = renderer).also {
            it.openInitialEditor()
            check(it.state.metrics != null) { "Document failed to open: ${it.state.status}" }
            if (reading) it.showMarkdownPreview()
            it.showFind(false)
        }
    }

    @Test fun laterBatchesReachEverySourceMatchWithoutEnablingPartialReplaceAll() {
        session(denseText(4200)).use { session ->
            session.showReplace()
            session.updateReplacementFieldValue(TextFieldValue("dog"))
            session.updateFindFieldValue(TextFieldValue("a"))
            assertEquals(MAX_SEARCH_RESULTS, session.findResults.size)
            assertTrue(session.canContinueFind)
            assertFalse(session.canApplyFindReplacements)
            assertTrue(session.continueFind())
            assertEquals(104, session.findResults.size)
            assertEquals(4096L * 2, session.findResults.first().hit.range.start)
            assertFalse(session.canContinueFind)
            assertFalse(session.isFindComplete)
            assertFalse(session.canApplyFindReplacements)
            assertFalse(session.applyFindReplacements())
            assertTrue(session.hasEarlierFindResults)
            assertTrue(session.retryFind())
            assertEquals(0L, session.findResults.first().hit.range.start)
            assertFalse(session.hasEarlierFindResults)
            assertTrue(session.selectSource(0, 6))
            assertTrue(session.captureSelectionFindScope())
            assertEquals(3, session.findResults.size)
            assertTrue(session.isFindComplete)
            assertTrue(session.canApplyFindReplacements)
        }
    }

    @Test fun timePausesResumeWithoutDiscardingTheReviewOrItsExclusions() {
        var time = 0L
        session("cat ".repeat(700), clock = { time.also { time += 4_000_000_000L } }).use { session ->
            session.showReplace()
            session.updateReplacementFieldValue(TextFieldValue("dog"))
            session.updateFindFieldValue(TextFieldValue("cat"))
            assertEquals(256, session.findResults.size)
            session.toggleFindResultIncluded(0)
            assertTrue(session.continueFind())
            assertEquals(512, session.findResults.size)
            assertTrue(session.continueFind())
            assertEquals(700, session.findResults.size)
            assertEquals(700, session.findResults.map { it.hit.range }.distinct().size)
            assertTrue(session.isFindComplete)
            assertTrue(session.canApplyFindReplacements)
            assertEquals(699, session.includedReplacementCount)
            assertFalse(session.hasEarlierFindResults)
        }
    }

    @Test fun retainedReplacementTextAlsoAdvancesToTheFirstUnreviewedMatch() {
        session("cat ".repeat(1000)).use { session ->
            session.showReplace()
            session.updateReplacementFieldValue(TextFieldValue("x".repeat(500)))
            session.updateFindFieldValue(TextFieldValue("cat"))
            val first = session.findResults.size
            assertTrue(first in 1 until 1000)
            assertTrue(session.continueFind())
            assertEquals(first * 4L, session.findResults.first().hit.range.start)
            assertEquals(1000 - first, session.findResults.size)
            assertFalse(session.canApplyFindReplacements)
        }
    }

    @Test fun changingTheQueryDiscardsTheOldContinuation() {
        session(denseText(4200) + "dog").use { session ->
            session.updateFindFieldValue(TextFieldValue("a"))
            assertTrue(session.canContinueFind)
            session.updateFindFieldValue(TextFieldValue("dog"))
            assertTrue(session.isFindComplete)
            assertEquals(1, session.findResults.size)
            assertFalse(session.continueFind())
            assertFalse(session.hasEarlierFindResults)
        }
    }

    @Test fun readingContinuationRetainsItsUnitAndLocalOffset() {
        session(denseText(3000) + "|" + denseText(1200), reading = true).use { session ->
            session.updateFindFieldValue(TextFieldValue("a"))
            assertEquals(MAX_SEARCH_RESULTS, session.findResults.size)
            assertTrue(session.continueFind())
            assertEquals(104, session.findResults.size)
            assertEquals(1096L * 2, session.findResults.first().hit.range.start)
            assertEquals(1, session.findResults.first().segments.first().block)
            assertFalse(session.canContinueFind)
        }
    }
}
