package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.ui.text.input.TextFieldValue
import dev.soupslurpr.beautyxt.testing.ImmediateSessionTestDispatcher
import dev.soupslurpr.beautyxt.testing.TestEditorDocument
import org.junit.Assert.*
import org.junit.Test

class FindReplacementUndoTest {
    @Test fun reviewingWithoutApplyingCannotUndoTheInitialTyping() {
        Fixture().use { fixture ->
            val session = fixture.session
            session.updateFindResultsExpanded(true, reviewReplacements = true)
            assertTrue(session.canUndo)
            assertFalse(session.canUndoFindReplacement)
            assertFalse(session.requestUndoFindReplacement())
            assertEquals(ORIGINAL, fixture.document.text)
        }
    }

    @Test fun unchangedReplacementCannotUndoTheInitialTyping() {
        Fixture().use { fixture ->
            val session = fixture.session
            session.updateReplacementFieldValue(TextFieldValue("cat"))
            assertTrue(session.selectFindResult(0))
            assertTrue(session.applyFindReplacements(currentOnly = true))
            assertTrue(session.canUndo)
            assertFalse(session.canUndoFindReplacement)
            assertFalse(session.requestUndoFindReplacement())
            assertEquals(ORIGINAL, fixture.document.text)
        }
    }

    @Test fun contextualUndoReversesOnlyTheReplacementEvenWhenInvokedTwice() {
        for (currentOnly in listOf(false, true)) Fixture().use { fixture ->
            val session = fixture.session
            if (currentOnly) assertTrue(session.selectFindResult(0))
            assertTrue(session.applyFindReplacements(currentOnly))
            val changed = if (currentOnly) ORIGINAL.replaceFirst("cat", "dog") else ORIGINAL.replace("cat", "dog")
            assertEquals(changed, fixture.document.text)
            assertTrue(session.canUndoFindReplacement)
            assertTrue(session.requestUndoFindReplacement())
            assertEquals(ORIGINAL, fixture.document.text)
            assertEquals(session.state.metrics!!.revision, session.replacementUndoNoticeRevision)
            assertFalse(session.canUndoFindReplacement)
            assertFalse(session.requestUndoFindReplacement())
            assertEquals(ORIGINAL, fixture.document.text)
            assertTrue(session.canUndo) // Typing remains in the general document history.
            assertTrue(session.requestRedo())
            assertEquals(changed, fixture.document.text)
            assertNull(session.replacementUndoNoticeRevision)
            assertFalse(session.canUndoFindReplacement)
            assertTrue(session.requestUndo())
            assertEquals(ORIGINAL, fixture.document.text)
        }
    }

    @Test fun anOlderNoticeTimeoutCannotDismissANewerUndoConfirmation() {
        Fixture().use { fixture ->
            val session = fixture.session
            assertTrue(session.applyFindReplacements())
            assertTrue(session.requestUndoFindReplacement())
            val older = checkNotNull(session.replacementUndoNoticeRevision)
            assertTrue(session.applyFindReplacements())
            assertNull(session.replacementUndoNoticeRevision)
            assertTrue(session.requestUndoFindReplacement())
            val newer = checkNotNull(session.replacementUndoNoticeRevision)
            session.retireReplacementUndoNotice(older)
            assertEquals(newer, session.replacementUndoNoticeRevision)
            session.retireReplacementUndoNotice(newer)
            assertNull(session.replacementUndoNoticeRevision)
            assertEquals(ORIGINAL, fixture.document.text)
        }
    }

    @Test fun newerTypingInvalidatesContextualUndoBeforeItsFieldObservation() {
        for (observe in listOf(false, true)) Fixture().use { fixture ->
            val session = fixture.session
            assertTrue(session.applyFindReplacements())
            assertTrue(session.canUndoFindReplacement)
            val draft = checkNotNull(session.activeDraft)
            draft.textFieldState.edit { replace(length, length, " More text.") }
            if (observe) session.observeActiveEdit(draft, draft.captureFieldValue())
            assertFalse(session.canUndoFindReplacement)
            assertFalse(session.requestUndoFindReplacement())
            assertEquals(ORIGINAL.replace("cat", "dog") + " More text.", draft.textFieldState.text.toString())
        }
    }

    @Test fun ordinaryUndoAndClosingFindRetireTheReplacementAction() {
        for (closeFind in listOf(false, true)) Fixture().use { fixture ->
            val session = fixture.session
            assertTrue(session.applyFindReplacements())
            if (closeFind) session.closeFind() else assertTrue(session.requestUndo())
            assertFalse(session.canUndoFindReplacement)
            assertFalse(session.requestUndoFindReplacement())
            assertEquals(if (closeFind) ORIGINAL.replace("cat", "dog") else ORIGINAL, fixture.document.text)
        }
    }

    private class Fixture : AutoCloseable {
        val document = TestEditorDocument("")
        val session = EditorSession("New document", EditorDocumentState(document, ImmediateSessionTestDispatcher),
            operationDispatcher = ImmediateSessionTestDispatcher, findDelay = {}, editSynchronizationDelay = {})

        init {
            session.openInitialEditor()
            val draft = checkNotNull(session.activeDraft)
            draft.textFieldState.edit { replace(0, length, ORIGINAL) }
            session.observeActiveEdit(draft, draft.captureFieldValue())
            assertTrue(session.showFind(false))
            session.showReplace()
            session.updateFindFieldValue(TextFieldValue("cat"))
            session.updateReplacementFieldValue(TextFieldValue("dog"))
        }

        override fun close() = session.close()
    }

    private companion object { const val ORIGINAL = "The small cat sleeps. Another cat stays." }
}
