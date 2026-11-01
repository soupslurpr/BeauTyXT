package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.ui.text.TextRange
import dev.soupslurpr.beautyxt.document.EditorDocument
import dev.soupslurpr.beautyxt.document.ViewportCursor
import dev.soupslurpr.beautyxt.testing.QueuedSessionTestDispatcher
import dev.soupslurpr.beautyxt.testing.TestEditorDocument
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises reads and input observations on both sides of native edit publication. */
class EditorStateRegressionTest {
    @Test
    fun overlappingCommitsRetainTheSelectionBeforeTheRemainingEdit() {
        for (selectionBeforeSecondEdit in listOf(TextRange(0), TextRange(1, 3), TextRange(3, 1))) {
            for (timing in EditTiming.entries) {
                val debounce = CompletableDeferred<Unit>()
                RegressionSession("abcdef", delayEdit = { debounce.await() }).use { fixture ->
                    val session = fixture.session
                    val draft = requireNotNull(session.activeDraft)
                    draft.textFieldState.edit { selection = TextRange(6) }
                    session.observeActiveEdit(draft, draft.captureFieldValue())
                    draft.textFieldState.edit { append("X"); selection = TextRange(7) }
                    session.observeActiveEdit(draft, draft.captureFieldValue())
                    fixture.operations.runAll()
                    if (timing != EditTiming.Grouped) {
                        debounce.complete(Unit)
                        fixture.operations.runAll()
                        assertEquals(EditorDocumentStatus.ApplyingEdit, session.state.status)
                        if (timing == EditTiming.Settled) fixture.drain()
                    }

                    assertTrue(session.canApplyEditorInput(draft))
                    draft.textFieldState.edit { selection = selectionBeforeSecondEdit }
                    session.observeActiveEdit(draft, draft.captureFieldValue())
                    draft.textFieldState.edit {
                        replace(selection.min, selection.max, "Y")
                        selection = TextRange(selectionBeforeSecondEdit.min + 1)
                    }
                    session.observeActiveEdit(draft, draft.captureFieldValue())
                    debounce.complete(Unit)
                    fixture.drain()
                    val editedText = fixture.document.text
                    assertEquals(if (timing == EditTiming.Grouped) 1 else 2,
                        fixture.document.replaceCalls.size)

                    assertTrue(session.requestUndo())
                    fixture.drain()
                    assertEquals(if (timing == EditTiming.Grouped) "abcdef" else "abcdefX",
                        fixture.document.text)
                    val expectedSelection = if (timing == EditTiming.Grouped) TextRange(6)
                        else TextRange(selectionBeforeSecondEdit.min, selectionBeforeSecondEdit.max)
                    assertEquals(expectedSelection, session.activeDraft?.textFieldState?.selection)
                    if (timing != EditTiming.Grouped) {
                        assertTrue(session.requestUndo())
                        fixture.drain()
                        assertEquals("abcdef", fixture.document.text)
                        assertEquals(TextRange(6), session.activeDraft?.textFieldState?.selection)
                        assertTrue(session.requestRedo())
                        fixture.drain()
                    }
                    assertTrue(session.requestRedo())
                    fixture.drain()
                    assertEquals(editedText, fixture.document.text)
                    assertEquals(TextRange(selectionBeforeSecondEdit.min + 1),
                        session.activeDraft?.textFieldState?.selection)
                }
            }
        }
    }

    private enum class EditTiming { Overlapping, Settled, Grouped }
}

private class RegressionSession(text: String, delayEdit: suspend () -> Unit = {},
    failPositionRead: Boolean = false) : AutoCloseable {
    val document = TestEditorDocument(text)
    val operations = QueuedSessionTestDispatcher()
    val worker = ReorderableEditorWorker()
    private val stateDocument = if (failPositionRead) object : EditorDocument by document {
        override fun positionAt(revision: Long, offset: Long): ViewportCursor =
            error("synthetic position read failure")
    } else document
    val session = EditorSession("regression.txt", EditorDocumentState(stateDocument, worker),
        operationDispatcher = operations, editSynchronizationDelay = delayEdit, findDelay = {})

    init {
        session.openInitialEditor()
        drain()
        assertNotNull(session.activeDraft)
    }

    fun drain() {
        repeat(200) {
            operations.runAll()
            if (worker.pendingCount == 0 && operations.pendingCount == 0) return
            worker.runOldest()
        }
        error("editor operations did not settle")
    }

    override fun close() {
        session.close()
        drain()
        assertEquals(1, document.closeCallCount)
    }
}

private class ReorderableEditorWorker : CoroutineDispatcher() {
    private val work = ArrayDeque<Runnable>()
    val pendingCount: Int get() = work.size
    override fun dispatch(context: CoroutineContext, block: Runnable) { work.addLast(block) }
    fun runOldest() { work.pollFirst()?.run() }
    fun runNewest() { checkNotNull(work.pollLast()).run() }
}
