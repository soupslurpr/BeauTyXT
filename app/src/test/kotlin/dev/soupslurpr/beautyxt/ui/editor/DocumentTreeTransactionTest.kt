package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.document.*
import dev.soupslurpr.beautyxt.testing.TestEditorDocument
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DocumentTreeTransactionTest {
    @Test fun gatesFieldInputAcrossPreparationPublicationAndWindowLoading() = runBlocking {
        var onDispatch: () -> Unit = {}
        val worker = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                onDispatch()
                block.run()
            }
        }
        val document = TestEditorDocument("seed", editWindowUtf16Units = 4)
        val state = EditorDocumentState(document, worker)
        state.loadInitialViewport()
        state.activateDocumentAt(Utf16Range(4, 4))
        var generation = checkNotNull(state.activeEdit).generation
        var observations = 0
        onDispatch = {
            observations++
            assertTrue(state.isReplacingDocumentTree)
            assertFalse(state.canAcceptActiveDraftInput(generation))
        }
        val result = state.replaceDocumentContent(0, Utf16Range(4, 4), DocumentInsertion("x".repeat(200_000)), Utf16Range(4, 4))
            as DocumentReplacementResult.Applied
        assertTrue(observations >= 3)
        assertFalse(state.isReplacingDocumentTree)
        generation = checkNotNull(state.activeEdit).generation
        assertTrue(state.canAcceptActiveDraftInput(generation))
        state.restoreDocumentHistory(1, checkNotNull(result.delta), undo = true)
        assertEquals("seed", document.text)
        generation = checkNotNull(state.activeEdit).generation
        state.replaceDocumentBatch(2, listOf(DocumentPatch(Utf16Range(0, 1), "s", "S")), Utf16Range(1, 1))
        assertEquals("Seed", document.text)
        assertFalse(state.isReplacingDocumentTree)
        state.close()
    }

    @Test fun aClosedDocumentCannotBeRevivedByAnInFlightTreeCommit() = runBlocking {
        lateinit var state: EditorDocumentState
        val backing = TestEditorDocument("seed")
        val document = object : EditorDocument by backing {
            override fun replaceContent(revision: Long, range: Utf16Range, input: DocumentInsertion,
                checkCancelled: () -> Unit): DocumentMetrics {
                val result = backing.replaceContent(revision, range, input, checkCancelled)
                state.close()
                return result
            }
        }
        state = EditorDocumentState(document, dev.soupslurpr.beautyxt.testing.ImmediateSessionTestDispatcher)
        state.loadInitialViewport()
        state.activateDocumentAt(Utf16Range(4, 4))
        assertEquals(DocumentReplacementResult.Unavailable,
            state.replaceDocumentContent(0, Utf16Range(4, 4), DocumentInsertion("!"), Utf16Range(4, 4)))
        assertEquals(EditorDocumentStatus.Closed, state.status)
        assertNull(state.metrics)
        assertNull(state.activeEdit)
        assertFalse(state.isReplacingDocumentTree)
        assertEquals(1, backing.closeCallCount)
    }

    @Test fun closingDuringPreparationDiscardsTheInsertionBeforePublication() = runBlocking {
        var onDispatch: () -> Unit = {}
        val worker = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                onDispatch()
                block.run()
            }
        }
        val document = TestEditorDocument("seed")
        val state = EditorDocumentState(document, worker)
        state.loadInitialViewport()
        state.activateDocumentAt(Utf16Range(4, 4))
        onDispatch = { state.close() }
        assertEquals(DocumentReplacementResult.Unavailable,
            state.replaceDocumentContent(0, Utf16Range(4, 4), DocumentInsertion("x".repeat(200_000)), Utf16Range(4, 4)))
        assertEquals("seed", document.text)
        assertEquals(EditorDocumentStatus.Closed, state.status)
        assertFalse(state.isReplacingDocumentTree)
        assertEquals(1, document.closeCallCount)
    }
}
