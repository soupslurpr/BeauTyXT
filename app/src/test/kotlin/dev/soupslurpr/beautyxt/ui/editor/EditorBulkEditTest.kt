package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import dev.soupslurpr.beautyxt.document.DocumentSizeLimitException
import dev.soupslurpr.beautyxt.document.DocumentHistoryLimitException
import dev.soupslurpr.beautyxt.document.DocumentInsertion
import dev.soupslurpr.beautyxt.document.DocumentMetrics
import dev.soupslurpr.beautyxt.document.EditorDocument
import dev.soupslurpr.beautyxt.document.Utf16Range
import dev.soupslurpr.beautyxt.ui.UiText
import dev.soupslurpr.beautyxt.testing.ImmediateSessionTestDispatcher
import dev.soupslurpr.beautyxt.testing.QueuedSessionTestDispatcher
import dev.soupslurpr.beautyxt.testing.TestEditorDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private const val BULK_TEST_TEXT_LENGTH = 50_000

class EditorBulkEditTest {
    @Test
    fun keepsTheComposeFieldBoundedBeforePublishingBulkInput() {
        val field = TextFieldState("seed", TextRange(4))
        var proposal: EditorBulkEdit? = null
        val transformation = editorInputTransformation(
            canAcceptInput = { true },
            onBulkEdit = {
                proposal = it
                true
            },
            onRejection = { error("unexpected rejection: $it") },
            clearRejection = {}
        )
        field.edit {
            append("x".repeat(BULK_TEST_TEXT_LENGTH))
            selection = TextRange(length)
            with(transformation) { transformInput() }
        }
        assertEquals("seed", field.text.toString())
        assertEquals(TextRange(4), field.selection)
        assertEquals("x".repeat(BULK_TEST_TEXT_LENGTH), buildString { requireNotNull(proposal).input.forEachChunk { append(it) } })
    }

    @Test
    fun normalizesPastedLineEndingsAndSelection() {
        val field = TextFieldState()
        val transformation = editorInputTransformation(
            canAcceptInput = { true },
            onBulkEdit = { error("small input should remain in its field") },
            onRejection = { error("unexpected rejection: $it") },
            clearRejection = {}
        )
        field.edit {
            append("a\r\nb\rc\n")
            selection = TextRange(length)
            with(transformation) { transformInput() }
        }
        assertEquals("a\nb\nc\n", field.text.toString())
        assertEquals(TextRange(6), field.selection)
    }

    @Test
    fun retainsARejectionReportedWhileDispatchingBulkInput() {
        val field = TextFieldState("seed", TextRange(4))
        var rejection: EditorInputRejection? = EditorInputRejection.BulkUnavailable
        val transformation = editorInputTransformation(
            canAcceptInput = { true },
            onBulkEdit = {
                assertNull(rejection)
                rejection = EditorInputRejection.DocumentSize
                true
            },
            onRejection = { rejection = it },
            clearRejection = { rejection = null }
        )
        field.edit {
            append("x".repeat(BULK_TEST_TEXT_LENGTH))
            with(transformation) { transformInput() }
        }
        assertEquals("seed", field.text.toString())
        assertEquals(EditorInputRejection.DocumentSize, rejection)
    }

    @Test
    fun admitsMultiMegabyteInputAndDefersLargeUnicodeValidationToTransfer() {
        val large = "😀x\r\n".repeat(400_000)
        val proposal = requireNotNull(EditorBulkEdit.create("", large, TextRange.Zero, TextRange(large.length)))
        var length = 0L
        proposal.input.forEachChunk { length += it.length }
        assertEquals(1_600_000L, length)
        assertEquals(dev.soupslurpr.beautyxt.document.Utf16Range(length, length), proposal.selectionAfter(length))
        val invalid = requireNotNull(EditorBulkEdit.create("", "\uD800", TextRange.Zero, TextRange.Zero))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { invalid.input.forEachChunk { } }
        assertNull(EditorBulkEdit.create("😀", "abc", TextRange(1), TextRange.Zero))
    }

    @Test
    fun insertsAndUndoesAsOneEditWithoutReplacingTheFocusedField() {
        val dispatcher = QueuedSessionTestDispatcher()
        val document = TestEditorDocument("seed", editWindowUtf16Units = 4)
        val session = createSession(document, dispatcher)
        session.openInitialEditor()
        dispatcher.runAll()
        val draft = requireNotNull(session.activeDraft)
        draft.updateEditorFocusIntent(isFocused = true, canClear = true)
        val field = draft.textFieldState
        val insertion = "x".repeat(BULK_TEST_TEXT_LENGTH)

        assertTrue(session.requestBulkEdit(draft, proposal("seed", "seed$insertion")))
        assertFalse(session.canApplyEditorInput(draft))
        assertFalse(session.canCloseSafely)
        assertFalse(session.canCancelPendingEditWindowAction)
        assertFalse(session.requestUndo())
        dispatcher.runAll()

        assertEquals("seed$insertion", document.text)
        assertEquals(1, document.replaceCalls.size)
        assertSame(draft, session.activeDraft)
        assertSame(field, requireNotNull(session.activeDraft).textFieldState)
        assertTrue(field.text.length <= EDIT_DRAFT_MAX_UTF16_UNITS)
        assertTrue(draft.shouldRestoreEditorFocus)
        assertTrue(session.canUndo)

        assertTrue(session.requestUndo())
        dispatcher.runAll()
        assertEquals("seed", document.text)
        assertTrue(session.canRedo)
        assertTrue(session.requestRedo())
        dispatcher.runAll()
        assertEquals("seed$insertion", document.text)
        assertSame(field, requireNotNull(session.activeDraft).textFieldState)
        session.close()
        dispatcher.runAll()
    }

    @Test
    fun settlesExistingTypingBeforeRecordingTheBulkUndoEntry() {
        val dispatcher = QueuedSessionTestDispatcher()
        val document = TestEditorDocument("seed", editWindowUtf16Units = 4)
        val session = createSession(document, dispatcher)
        session.openInitialEditor()
        dispatcher.runAll()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            append("!")
            selection = TextRange(length)
        }
        val insertion = "x".repeat(BULK_TEST_TEXT_LENGTH)
        assertTrue(session.requestBulkEdit(draft, proposal("seed!", "seed!$insertion")))
        dispatcher.runAll()
        assertEquals(2, document.replaceCalls.size)
        assertTrue(session.requestUndo())
        dispatcher.runAll()
        assertEquals("seed!", document.text)
        assertTrue(session.requestUndo())
        dispatcher.runAll()
        assertEquals("seed", document.text)
        session.close()
        dispatcher.runAll()
    }

    @Test
    fun backCannotDiscardAnAcceptedBulkInsertionBeforeItCommits() {
        val dispatcher = QueuedSessionTestDispatcher()
        val document = TestEditorDocument("seed", editWindowUtf16Units = 4)
        val session = createSession(document, dispatcher)
        session.openInitialEditor()
        dispatcher.runAll()
        val draft = requireNotNull(session.activeDraft)
        val insertion = "x".repeat(BULK_TEST_TEXT_LENGTH)
        assertTrue(session.requestBulkEdit(draft, proposal("seed", "seed$insertion")))
        assertEquals(CloseRequestResult.Queued, session.requestClose())
        assertEquals(CloseRequestResult.Queued, session.requestClose())
        dispatcher.runAll()
        assertEquals("seed$insertion", document.text)
        assertEquals(CloseRequestResult.ConfirmationShown, session.resolvePendingClose())
        assertTrue(session.isDiscardConfirmationVisible)
        session.close()
        dispatcher.runAll()
    }

    @Test
    fun rejectedBulkInsertionLeavesTheOriginalAndHistoryUntouched() {
        val dispatcher = QueuedSessionTestDispatcher()
        val document = TestEditorDocument(
            "seed",
            editWindowUtf16Units = 4,
            replaceFailure = DocumentSizeLimitException("test size rejection")
        )
        val session = createSession(document, dispatcher)
        session.openInitialEditor()
        dispatcher.runAll()
        val draft = requireNotNull(session.activeDraft)
        assertTrue(
            session.requestBulkEdit(
                draft,
                proposal(
                    "seed",
                    "seed" + "x".repeat(BULK_TEST_TEXT_LENGTH)
                )
            )
        )
        dispatcher.runAll()
        assertEquals("seed", document.text)
        assertEquals("seed", draft.textFieldState.text.toString())
        assertEquals(EditorInputRejection.DocumentSize, draft.inputRejection)
        assertFalse(session.canUndo)
        assertFalse(session.hasPendingEditWindowAction)
        session.close()
        dispatcher.runAll()
    }

    @Test
    fun wholeDocumentReplacementAndUndoDoNotNeedRemovedTextInPlatformHistory() {
        val dispatcher = QueuedSessionTestDispatcher()
        val original = "x".repeat(400_000)
        val document = TestEditorDocument("seed", editWindowUtf16Units = 4)
        val session = createSession(document, dispatcher)
        session.openInitialEditor()
        dispatcher.runAll()
        assertTrue(session.requestBulkEdit(requireNotNull(session.activeDraft), proposal("seed", original)))
        dispatcher.runAll()
        assertTrue(session.selectWholeDocument())
        assertTrue(session.replaceSelectedSource("replacement\r\n😀"))
        dispatcher.runAll()
        assertEquals("replacement\n😀", document.text)
        assertTrue(session.requestUndo())
        dispatcher.runAll()
        assertEquals(original, document.text)
        assertEquals(dev.soupslurpr.beautyxt.document.Utf16Range(0, original.length.toLong()),
            (session.documentSelection as DocumentSelection.Source).range)
        assertTrue(requireNotNull(session.activeDraft).textFieldState.text.length <= EDIT_DRAFT_MAX_UTF16_UNITS)
        assertTrue(session.requestRedo())
        dispatcher.runAll()
        assertEquals("replacement\n😀", document.text)
        session.close()
        dispatcher.runAll()
    }

    @Test
    fun identicalNormalizedBulkReplacementMovesCaretWithoutCreatingHistory() {
        val dispatcher = QueuedSessionTestDispatcher()
        val document = TestEditorDocument("a\nb", editWindowUtf16Units = 3)
        val session = createSession(document, dispatcher)
        session.openInitialEditor()
        dispatcher.runAll()
        val draft = requireNotNull(session.activeDraft)
        val proposal = requireNotNull(EditorBulkEdit.insertion("a\nb", TextRange(0, 3), "a\r\nb"))
        assertTrue(session.requestBulkEdit(draft, proposal))
        dispatcher.runAll()
        assertEquals("a\nb", document.text)
        assertFalse(session.canUndo)
        assertEquals(TextRange(3), requireNotNull(session.activeDraft).textFieldState.selection)
        session.close()
        dispatcher.runAll()
    }

    @Test
    fun nativeMemoryRejectionPreservesTheExistingRedoAndField() {
        val dispatcher = QueuedSessionTestDispatcher()
        val underlying = TestEditorDocument("seed", editWindowUtf16Units = 4)
        var reject = false
        val document = object : EditorDocument by underlying {
            override fun replaceContent(revision: Long, range: Utf16Range, input: DocumentInsertion,
                checkCancelled: () -> Unit): DocumentMetrics {
                if (reject) throw DocumentHistoryLimitException()
                return underlying.replaceContent(revision, range, input, checkCancelled)
            }
        }
        val session = createSession(document, dispatcher)
        session.openInitialEditor()
        dispatcher.runAll()
        val inserted = "seed" + "x".repeat(BULK_TEST_TEXT_LENGTH)
        assertTrue(session.requestBulkEdit(requireNotNull(session.activeDraft), proposal("seed", inserted)))
        dispatcher.runAll()
        assertTrue(session.requestUndo())
        dispatcher.runAll()
        reject = true
        val draft = requireNotNull(session.activeDraft)
        assertTrue(session.requestBulkEdit(draft, proposal("seed", "seed" + "y".repeat(BULK_TEST_TEXT_LENGTH))))
        dispatcher.runAll()
        assertEquals("seed", underlying.text)
        assertSame(draft, session.activeDraft)
        assertEquals(UiText.Resource(dev.soupslurpr.beautyxt.R.string.editor_input_history_size), session.state.editorMessage)
        assertTrue(session.canRedo)
        assertTrue(session.requestRedo())
        dispatcher.runAll()
        assertEquals(inserted, underlying.text)
        session.close()
        dispatcher.runAll()
    }

    private fun proposal(original: String, updated: String): EditorBulkEdit = requireNotNull(
        EditorBulkEdit.create(
            original,
            updated,
            TextRange(original.length),
            TextRange(updated.length)
        )
    )

    private fun createSession(
        document: EditorDocument,
        dispatcher: QueuedSessionTestDispatcher
    ): EditorSession = EditorSession(
        title = "Bulk input test.txt",
        state = EditorDocumentState(document, ImmediateSessionTestDispatcher),
        operationDispatcher = dispatcher,
        editSynchronizationDelay = {}
    )
}
