package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.document.DocumentFormat
import dev.soupslurpr.beautyxt.document.DocumentRemovalAction
import dev.soupslurpr.beautyxt.document.DocumentRemovalCapabilities
import dev.soupslurpr.beautyxt.document.DocumentSizeLimitException
import dev.soupslurpr.beautyxt.document.EditWindowLimits
import dev.soupslurpr.beautyxt.document.EditorDocument
import dev.soupslurpr.beautyxt.document.EditorDocumentSnapshot
import dev.soupslurpr.beautyxt.document.FindDirection
import dev.soupslurpr.beautyxt.document.MAX_FIND_QUERY_UTF16_UNITS
import dev.soupslurpr.beautyxt.document.Utf16Range
import dev.soupslurpr.beautyxt.document.ViewportCursor
import dev.soupslurpr.beautyxt.document.ViewportLimits
import dev.soupslurpr.beautyxt.document.ViewportSnapshot
import dev.soupslurpr.beautyxt.exporting.ExportProtocol
import dev.soupslurpr.beautyxt.exporting.client.DocumentExportException
import dev.soupslurpr.beautyxt.exporting.client.DocumentExportFailure
import dev.soupslurpr.beautyxt.importing.client.SelectedDocumentMetadata
import dev.soupslurpr.beautyxt.markdown.MarkdownPreviewDocument
import dev.soupslurpr.beautyxt.markdown.client.MarkdownRenderer
import dev.soupslurpr.beautyxt.printing.PrintContentMode
import dev.soupslurpr.beautyxt.printing.defaultPrintSettings
import dev.soupslurpr.beautyxt.sharing.MAX_SHARED_TEXT_UTF8_BYTES
import dev.soupslurpr.beautyxt.testing.ImmediateSessionTestDispatcher
import dev.soupslurpr.beautyxt.testing.QueuedSessionTestDispatcher
import dev.soupslurpr.beautyxt.testing.TestDestinationOwner
import dev.soupslurpr.beautyxt.testing.TestEditorDocument
import dev.soupslurpr.beautyxt.testing.TestEditorDocumentSnapshot
import dev.soupslurpr.beautyxt.transfer.TransferProtocol
import dev.soupslurpr.beautyxt.transfer.client.NfcTransferProcessor
import dev.soupslurpr.beautyxt.transfer.client.QrTransferProcessor
import dev.soupslurpr.beautyxt.ui.UiText
import dev.soupslurpr.beautyxt.ui.userMessage
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val FIRST_EDIT_GENERATION = 1L
private const val RETAINED_DRAFT_TEXT = "alpha 😀\nbeta"
private const val RETAINED_SELECTION_START = 6
private const val RETAINED_SELECTION_END = 8
private const val EXTERNAL_EDIT_REVISION = 7L
private const val RETAINED_SOURCE_URI = "content://test.documents/source.txt"
private const val OTHER_SOURCE_URI = "content://test.documents/other.txt"
private const val BOUNDED_EDIT_WINDOW_UTF16_UNITS = 4
private const val BOUNDED_DOCUMENT_TEXT = "abcdefghij"
private const val FIRST_BOUNDED_EDIT_TEXT = "abcd"
private const val REPLACEMENT_BOUNDED_EDIT_TEXT = "ABCD"
private const val EXPANDED_BOUNDED_EDIT_TEXT = "abcdef"
private const val INITIAL_VIEWPORT_ITEM_INDEX = 5
private const val NON_ASCII_LINE_INPUT = "٢"
private const val OVERFLOWING_LINE_INPUT = "9223372036854775808"
private const val OVERLONG_LINE_INPUT = "12345678901234567890"
private const val FIND_DOCUMENT_TEXT = "banana"
private const val OVERLAPPING_FIND_QUERY = "ana"
private const val FIRST_OVERLAPPING_MATCH_START = 1L
private const val SECOND_OVERLAPPING_MATCH_START = 3L
private const val FOCUS_SELECTION_TEXT = "0123456789abcdefghijklmnopqrstuvwxyz"
private const val FOCUS_SELECTION_START = 9
private const val FOCUS_SELECTION_END = 23
private const val DIFFERENT_FOCUS_CARET = 5
private const val DIFFERENT_SELECTION_START = 3
private const val DIFFERENT_SELECTION_END = 7
private const val SESSION_HISTORY_ENTRY_LIMIT = 128
private const val SESSION_HISTORY_BOUNDARY_EDIT_COUNT = SESSION_HISTORY_ENTRY_LIMIT + 1

/** Installs one retained IME composition through Compose's internal user-edit path. */
private fun TextFieldState.retainCompositionForTest(composition: TextRange) {
    require(!composition.collapsed) { "test composition must not be collapsed" }
    require(composition.min >= 0 && composition.max <= text.length) {
        "test composition exceeds the text field"
    }
    val mainBuffer =
        javaClass
            .getMethod("getMainBuffer\$foundation")
            .invoke(this)
    val setComposition =
        mainBuffer.javaClass.methods.single { method ->
            method.name == "setComposition\$foundation"
        }
    setComposition.invoke(
        mainBuffer,
        composition.start,
        composition.end,
        emptyList<Any>()
    )
    val publishUserEdit =
        javaClass.methods.single { method ->
            method.name == "editWithNoSideEffects\$foundation"
        }
    publishUserEdit.invoke(this, { _: TextFieldBuffer -> Unit })
    check(this.composition == composition) { "test composition was not retained" }
}


/** Records separate conditional and confirmed-overwrite saves for conflict tests. */
private class TestConflictRecoverableDocumentSource(
    private val encodedUri: String = RETAINED_SOURCE_URI,
    private val save: suspend (TestEditorDocumentSnapshot, Int) -> Unit = { _, _ -> },
    private val overwrite: suspend (TestEditorDocumentSnapshot, Int) -> Unit = { _, _ -> }
) : ConflictRecoverableEditorDocumentSource {
    val savedTexts = mutableListOf<String>()
    val overwrittenTexts = mutableListOf<String>()
    var closeCallCount = 0
        private set

    /** Returns whether this source has the configured exact test URI. */
    override fun matchesSourceUri(encodedUri: String): Boolean = this.encodedUri == encodedUri

    /** Returns the configured URI for deterministic conflict recovery. */
    override fun encodedShareUri(): String = encodedUri

    /** Records one ordinary conditional save attempt. */
    override suspend fun saveRevision(snapshot: EditorDocumentSnapshot, expectedBytes: Long) {
        val testSnapshot = validateSnapshot(snapshot, expectedBytes)
        savedTexts += testSnapshot.text
        save(testSnapshot, savedTexts.size)
        if (!testSnapshot.isClosed) {
            testSnapshot.consume()
        }
    }

    /** Records one explicitly confirmed overwrite attempt. */
    override suspend fun overwriteRevision(snapshot: EditorDocumentSnapshot, expectedBytes: Long) {
        val testSnapshot = validateSnapshot(snapshot, expectedBytes)
        overwrittenTexts += testSnapshot.text
        overwrite(testSnapshot, overwrittenTexts.size)
        if (!testSnapshot.isClosed) {
            testSnapshot.consume()
        }
    }

    /** Records one retained-source ownership release. */
    override fun close() {
        closeCallCount = Math.incrementExact(closeCallCount)
    }

    /** Returns one exact test snapshot after validating its declared length. */
    private fun validateSnapshot(
        snapshot: EditorDocumentSnapshot,
        expectedBytes: Long
    ): TestEditorDocumentSnapshot {
        val testSnapshot = snapshot as TestEditorDocumentSnapshot
        require(testSnapshot.byteLength == expectedBytes) {
            "test source received an unexpected byte count"
        }
        return testSnapshot
    }
}

/** Records deterministic ownership for a selected non-writable source. */
private class TestReadOnlyEditorDocumentSource(
    private val encodedUri: String = RETAINED_SOURCE_URI
) : EditorDocumentSource {
    var closeCallCount = 0
        private set

    /** Returns whether this source has the configured exact test URI. */
    override fun matchesSourceUri(encodedUri: String): Boolean = this.encodedUri == encodedUri

    /** Returns the configured URI for deterministic share tests. */
    override fun encodedShareUri(): String = encodedUri

    /** Records one retained-source ownership release. */
    override fun close() {
        closeCallCount = Math.incrementExact(closeCallCount)
    }
}

/** Fails a configured number of random-line requests before returning a valid page. */
private class RetriableLineEditorDocument(
    private val failuresBeforeSuccess: Int = 1,
    private val delegate: TestEditorDocument = TestEditorDocument("alpha\nbeta")
) : EditorDocument by delegate {
    var lineRequestCount = 0
        private set

    init {
        require(failuresBeforeSuccess >= 0) {
            "line failure count must be nonnegative"
        }
    }

    /** Returns the complete document or one retriable page beginning at line two. */
    override fun viewport(cursor: ViewportCursor, limits: ViewportLimits): ViewportSnapshot {
        if (cursor.line == 0L) {
            return delegate.viewport(cursor = cursor, limits = limits)
        }
        require(cursor.line == 1L) { "test line request must target line two" }
        lineRequestCount = Math.incrementExact(lineRequestCount)
        if (lineRequestCount <= failuresBeforeSuccess) {
            throw IllegalStateException("synthetic line viewport failure")
        }
        val completeViewport =
            delegate.viewport(
                cursor = ViewportCursor(revision = cursor.revision, line = 0L, utf16Offset = 0L),
                limits = limits
            )
        return completeViewport.copy(
            blocks = completeViewport.blocks.drop(1),
            previous = cursor,
            next = null
        )
    }
}

/** Fails a configured number of exact line-start lookups for active-editor retry tests. */
private class RetriableActiveLineEditorDocument(
    private val failuresBeforeSuccess: Int = 1,
    val delegate: TestEditorDocument = TestEditorDocument("alpha\nbeta")
) : EditorDocument by delegate {
    var lineStartCallCount = 0
        private set

    init {
        require(failuresBeforeSuccess >= 0) {
            "line-start failure count must be nonnegative"
        }
    }

    /** Returns one exact line start after the configured transient failures. */
    override fun lineStartUtf16(revision: Long, logicalLine: Long): Long {
        lineStartCallCount = Math.incrementExact(lineStartCallCount)
        if (lineStartCallCount <= failuresBeforeSuccess) {
            throw IllegalStateException("synthetic active line failure")
        }
        return delegate.lineStartUtf16(revision = revision, logicalLine = logicalLine)
    }
}

/** Adds exact-cursor viewport behavior to the complete in-memory document fixture. */
private class ExactFindViewportEditorDocument(val delegate: TestEditorDocument) :
    EditorDocument by delegate {
    /** Returns the complete document or a suffix beginning at the exact requested cursor. */
    override fun viewport(cursor: ViewportCursor, limits: ViewportLimits): ViewportSnapshot {
        if (cursor.line == 0L && cursor.utf16Offset == 0L) {
            return delegate.viewport(cursor = cursor, limits = limits)
        }
        val completeViewport =
            delegate.viewport(
                cursor = ViewportCursor(revision = cursor.revision, line = 0L, utf16Offset = 0L),
                limits = limits
            )
        val firstBlockIndex =
            completeViewport.blocks.indexOfFirst { block -> block.logicalLine == cursor.line }
        check(firstBlockIndex >= 0) { "test find cursor line is outside the document" }
        val completeFirstBlock = completeViewport.blocks[firstBlockIndex]
        val localUtf16Offset = Math.toIntExact(cursor.utf16Offset)
        check(localUtf16Offset in 0..completeFirstBlock.text.length) {
            "test find cursor offset is outside its line"
        }
        val firstBlock =
            completeFirstBlock.copy(
                globalUtf16Start =
                    Math.addExact(
                        completeFirstBlock.globalUtf16Start,
                        cursor.utf16Offset
                    ),
                text = completeFirstBlock.text.substring(localUtf16Offset),
                continuesAtStart = localUtf16Offset > 0
            )
        return completeViewport.copy(
            blocks = listOf(firstBlock) + completeViewport.blocks.drop(firstBlockIndex + 1),
            previous = cursor,
            next = null
        )
    }
}

/** Returns one logical line per page while exposing deterministic request counts. */
private class PagedEditorDocument(val delegate: TestEditorDocument) : EditorDocument by delegate {
    var viewportCallCount = 0
        private set

    var previousViewportCallCount = 0
        private set

    var failNextViewport = false

    /** Returns one forward page beginning at the requested logical line. */
    override fun viewport(cursor: ViewportCursor, limits: ViewportLimits): ViewportSnapshot {
        viewportCallCount = Math.incrementExact(viewportCallCount)
        if (failNextViewport) {
            failNextViewport = false
            error("synthetic viewport failure")
        }
        return pageAt(cursor = cursor, limits = limits, next = null)
    }

    /** Returns the single page immediately preceding the supplied cursor. */
    override fun previousViewport(
        cursor: ViewportCursor,
        limits: ViewportLimits
    ): ViewportSnapshot {
        previousViewportCallCount = Math.incrementExact(previousViewportCallCount)
        check(cursor.line > 0L && cursor.utf16Offset == 0L) {
            "test previous cursor must begin a noninitial line"
        }
        return pageAt(
            cursor =
                ViewportCursor(
                    revision = cursor.revision,
                    line = Math.decrementExact(cursor.line),
                    utf16Offset = 0L
                ),
            limits = limits,
            next = cursor
        )
    }

    /** Creates one protocol-valid single-line page from the complete test viewport. */
    private fun pageAt(
        cursor: ViewportCursor,
        limits: ViewportLimits,
        next: ViewportCursor?
    ): ViewportSnapshot {
        check(cursor.utf16Offset == 0L) { "test page cursor must begin its logical line" }
        val completeViewport =
            delegate.viewport(
                cursor = ViewportCursor(revision = cursor.revision, line = 0L, utf16Offset = 0L),
                limits = limits
            )
        val blockIndex =
            completeViewport.blocks.indexOfFirst { block -> block.logicalLine == cursor.line }
        check(blockIndex >= 0) { "test page cursor line is outside the document" }
        val followingCursor =
            next ?: completeViewport.blocks.getOrNull(blockIndex + 1)?.let { followingBlock ->
                ViewportCursor(
                    revision = cursor.revision,
                    line = followingBlock.logicalLine,
                    utf16Offset = 0L
                )
            }
        return completeViewport.copy(
            blocks = listOf(completeViewport.blocks[blockIndex]),
            previous = if (blockIndex == 0) null else cursor,
            next = followingCursor
        )
    }
}

/** Verifies retained editor ownership independently from the Compose lifecycle. */
class EditorSessionTest {
    /** Verifies semantic viewport anchors reject invalid revision and source coordinates. */
    @Test
    fun validatesSemanticViewportAnchorCoordinates() {
        assertThrows(IllegalArgumentException::class.java) {
            SemanticViewportAnchor(
                revision = -1L,
                sourceUtf16Offset = 0L,
                viewportTopOffsetPixels = 0
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SemanticViewportAnchor(
                revision = 0L,
                sourceUtf16Offset = -1L,
                viewportTopOffsetPixels = 0
            )
        }

        assertEquals(
            -24,
            SemanticViewportAnchor(
                revision = 3L,
                sourceUtf16Offset = 7L,
                viewportTopOffsetPixels = -24
            ).viewportTopOffsetPixels
        )
    }

    /** Verifies retained line input remains bounded and validates without overflow. */
    @Test
    fun retainsAndValidatesGoToLineInput() {
        val session = createSession(document = TestEditorDocument("alpha\nbeta\ngamma"))
        session.openInitialEditor()

        assertTrue(session.canNavigateToLine)
        assertTrue(session.showGoToLineDialog(currentVisibleLogicalLine = 1L))
        assertTrue(session.isGoToLineDialogVisible)
        assertEquals("2", session.goToLineInput)
        assertNull(session.goToLineErrorMessage)

        session.updateGoToLineInput("2x")
        assertEquals("2", session.goToLineInput)
        session.updateGoToLineInput(NON_ASCII_LINE_INPUT)
        assertEquals("2", session.goToLineInput)
        session.updateGoToLineInput(OVERLONG_LINE_INPUT)
        assertEquals("2", session.goToLineInput)

        session.updateGoToLineInput(OVERFLOWING_LINE_INPUT)
        assertEquals(OVERFLOWING_LINE_INPUT, session.goToLineInput)
        assertFalse(session.confirmGoToLine())
        assertTrue(session.isGoToLineDialogVisible)
        assertEquals(
            UiText.Resource(R.string.operation_line_range, listOf(1L, 3L)),
            session.goToLineErrorMessage
        )

        session.updateGoToLineInput("")
        assertNull(session.goToLineErrorMessage)
        assertFalse(session.confirmGoToLine())
        assertEquals(
            UiText.Resource(R.string.operation_line_range, listOf(1L, 3L)),
            session.goToLineErrorMessage
        )

        session.dismissGoToLineDialog()
        assertFalse(session.isGoToLineDialogVisible)
        assertNull(session.goToLineErrorMessage)

        assertTrue(session.showGoToLineDialog(currentVisibleLogicalLine = 0L))
        assertEquals("1", session.goToLineInput)
        session.close()

        assertFalse(session.isGoToLineDialogVisible)
        assertEquals("", session.goToLineInput)
        assertNull(session.goToLineErrorMessage)
        assertFalse(session.canNavigateToLine)
    }

    /** Verifies a successful line jump immediately resets the bounded list position. */
    @Test
    fun resetsTheViewportPositionAfterSuccessfulLineNavigation() {
        val document = TestEditorDocument("alpha\nbeta")
        val viewportListState =
            LazyListState(firstVisibleItemIndex = INITIAL_VIEWPORT_ITEM_INDEX)
        val session =
            EditorSession(
                title = "Test document",
                state = EditorDocumentState(document, ImmediateSessionTestDispatcher),
                documentSource = TestReadOnlyEditorDocumentSource(),
                viewportListState = viewportListState,
                operationDispatcher = ImmediateSessionTestDispatcher
            )
        session.openInitialEditor()
        assertTrue(session.showGoToLineDialog(currentVisibleLogicalLine = 0L))

        assertTrue(session.confirmGoToLine())

        assertFalse(session.isGoToLineDialogVisible)
        assertEquals(0, viewportListState.firstVisibleItemIndex)
        assertEquals(0L, document.viewportCalls.last().line)
        session.close()
    }

    /** Verifies a rejected target leaves the current bounded list position untouched. */
    @Test
    fun preservesTheViewportPositionAfterFailedLineNavigation() {
        val document = TestEditorDocument("alpha\nbeta")
        val viewportListState =
            LazyListState(firstVisibleItemIndex = INITIAL_VIEWPORT_ITEM_INDEX)
        val session =
            EditorSession(
                title = "Test document",
                state = EditorDocumentState(document, ImmediateSessionTestDispatcher),
                documentSource = TestReadOnlyEditorDocumentSource(),
                viewportListState = viewportListState,
                operationDispatcher = ImmediateSessionTestDispatcher
            )
        session.openInitialEditor()
        assertTrue(session.showGoToLineDialog(currentVisibleLogicalLine = 0L))
        session.updateGoToLineInput("2")

        assertTrue(session.confirmGoToLine())

        assertFalse(session.isGoToLineDialogVisible)
        assertEquals(INITIAL_VIEWPORT_ITEM_INDEX, viewportListState.firstVisibleItemIndex)
        assertEquals(1L, document.viewportCalls.last().line)
        assertTrue(session.state.lineViewportStatus is LineViewportStatus.Failed)
        assertFalse(session.canNavigateToLine)
        assertTrue(session.dismissLineNavigationFailure())
        assertEquals(LineViewportStatus.Idle, session.state.lineViewportStatus)
        assertTrue(session.canNavigateToLine)
        assertEquals(INITIAL_VIEWPORT_ITEM_INDEX, viewportListState.firstVisibleItemIndex)
        session.close()
    }

    /** Verifies a successful line retry resets the bounded list position exactly once. */
    @Test
    fun resetsTheViewportPositionAfterSuccessfulLineRetry() {
        val document = RetriableLineEditorDocument()
        val viewportListState =
            LazyListState(firstVisibleItemIndex = INITIAL_VIEWPORT_ITEM_INDEX)
        val state = EditorDocumentState(document, ImmediateSessionTestDispatcher)
        val session =
            EditorSession(
                title = "Test document",
                state = state,
                documentSource = TestReadOnlyEditorDocumentSource(),
                viewportListState = viewportListState,
                operationDispatcher = ImmediateSessionTestDispatcher
            )
        session.openInitialEditor()
        assertTrue(session.showGoToLineDialog(currentVisibleLogicalLine = 0L))
        session.updateGoToLineInput("2")
        assertTrue(session.confirmGoToLine())
        assertTrue(state.lineViewportStatus is LineViewportStatus.Failed)
        assertEquals(INITIAL_VIEWPORT_ITEM_INDEX, viewportListState.firstVisibleItemIndex)

        session.retryViewport()

        assertEquals(LineViewportStatus.Idle, state.lineViewportStatus)
        assertEquals(listOf("beta"), state.blocks.map { block -> block.block.text })
        assertEquals(0, viewportListState.firstVisibleItemIndex)
        session.close()
    }

    /** Verifies a queued duplicate retry cannot consume a later line failure. */
    @Test
    fun rejectsDuplicateLineRetryAcrossFailureGenerations() {
        val document = RetriableLineEditorDocument(failuresBeforeSuccess = 2)
        val workerDispatcher = QueuedSessionTestDispatcher()
        val viewportListState =
            LazyListState(firstVisibleItemIndex = INITIAL_VIEWPORT_ITEM_INDEX)
        val state = EditorDocumentState(document, workerDispatcher)
        val session =
            EditorSession(
                title = "Test document",
                state = state,
                documentSource = TestReadOnlyEditorDocumentSource(),
                viewportListState = viewportListState,
                operationDispatcher = ImmediateSessionTestDispatcher
            )
        session.openInitialEditor()
        workerDispatcher.runAll()
        assertTrue(session.showGoToLineDialog(currentVisibleLogicalLine = 0L))
        session.updateGoToLineInput("2")
        assertTrue(session.confirmGoToLine())
        workerDispatcher.runAll()
        assertEquals(1, document.lineRequestCount)
        assertTrue(state.lineViewportStatus is LineViewportStatus.Failed)

        session.retryViewport()
        assertTrue(state.lineViewportStatus is LineViewportStatus.Loading)
        session.retryViewport()
        workerDispatcher.runAll()

        assertEquals(2, document.lineRequestCount)
        assertTrue(state.lineViewportStatus is LineViewportStatus.Failed)
        assertEquals(INITIAL_VIEWPORT_ITEM_INDEX, viewportListState.firstVisibleItemIndex)

        session.retryViewport()
        workerDispatcher.runAll()

        assertEquals(3, document.lineRequestCount)
        assertEquals(LineViewportStatus.Idle, state.lineViewportStatus)
        assertEquals(0, viewportListState.firstVisibleItemIndex)
        session.close()
    }

    /** Verifies a dirty bounded editor synchronizes and opens an exact line in place. */
    @Test
    fun navigatesTheActiveEditorDirectlyToAnExactLine() {
        val document =
            TestEditorDocument(
                initialText = "alpha\nbeta\ngamma",
                editWindowUtf16Units = 6
            )
        val session = createSession(document)
        session.openInitialEditor()
        val firstDraft = requireNotNull(session.activeDraft)
        firstDraft.textFieldState.edit {
            replace(start = 0, end = 5, text = "ALPHA")
            selection = TextRange(2)
        }

        assertTrue(session.showGoToLineDialog())
        assertEquals("1", session.goToLineInput)
        session.updateGoToLineInput("3")
        assertTrue(session.confirmGoToLine())

        assertEquals("ALPHA\nbeta\ngamma", document.text)
        val movedDraft = requireNotNull(session.activeDraft)
        assertTrue(movedDraft !== firstDraft)
        assertEquals(
            Utf16Range(start = 11L, end = 11L),
            movedDraft.edit.snapshot.selection
        )
        assertTrue(movedDraft.shouldRestoreEditorFocus)
        assertEquals(LineViewportStatus.Idle, session.state.lineViewportStatus)
        session.close()
    }

    /** Verifies failed active line navigation retains its field and retries in place. */
    @Test
    fun retriesFailedActiveLineNavigationWithoutDroppingTheField() {
        val document = RetriableActiveLineEditorDocument()
        val session = createSession(document)
        session.openInitialEditor()
        val retainedDraft = requireNotNull(session.activeDraft)
        assertTrue(session.showGoToLineDialog())
        session.updateGoToLineInput("2")

        assertTrue(session.confirmGoToLine())

        assertSame(retainedDraft, session.activeDraft)
        assertEquals(1, document.lineStartCallCount)
        assertTrue(session.state.lineViewportStatus is LineViewportStatus.Failed)

        session.retryViewport()

        val movedDraft = requireNotNull(session.activeDraft)
        assertTrue(movedDraft !== retainedDraft)
        assertEquals(2, document.lineStartCallCount)
        assertEquals(Utf16Range(start = 6L, end = 6L), movedDraft.edit.snapshot.selection)
        assertTrue(movedDraft.shouldRestoreEditorFocus)
        assertEquals(LineViewportStatus.Idle, session.state.lineViewportStatus)
        session.close()
    }

    /** Verifies viewing sessions navigate only while document workflows are idle. */
    @Test
    fun gatesGoToLineAroundActiveDocumentWorkflows() {
        val document = TestEditorDocument("alpha\nbeta")
        val source = TestReadOnlyEditorDocumentSource()
        val session =
            EditorSession(
                title = "Read-only document",
                state = EditorDocumentState(document, ImmediateSessionTestDispatcher),
                documentSource = source,
                operationDispatcher = ImmediateSessionTestDispatcher
            )

        assertFalse(session.canNavigateToLine)
        assertFalse(session.showGoToLineDialog(currentVisibleLogicalLine = 0L))
        session.openInitialEditor()
        assertTrue(session.isViewOnly)
        assertTrue(session.canNavigateToLine)

        assertTrue(session.showSaveFormatSelection())
        assertFalse(session.canNavigateToLine)
        assertFalse(session.showGoToLineDialog(currentVisibleLogicalLine = 0L))
        session.dismissSaveFormatSelection()
        assertTrue(session.canNavigateToLine)

        session.showDiscardConfirmation()
        assertFalse(session.canNavigateToLine)
        session.dismissDiscardConfirmation()
        assertTrue(session.canNavigateToLine)
        assertFalse(session.showGoToLineDialog(currentVisibleLogicalLine = 2L))
        session.close()
    }

    /** Verifies a source-owned viewing session can run exact retained Find. */
    @Test
    fun findsExactTextInAViewOnlyDocument() {
        val query = OVERLAPPING_FIND_QUERY
        val document = ExactFindViewportEditorDocument(TestEditorDocument(FIND_DOCUMENT_TEXT))
        val session =
            createSession(
                document = document,
                documentSource = TestReadOnlyEditorDocumentSource(),
                findDelay = {}
            )
        session.openInitialEditor()

        assertTrue(session.isViewOnly)
        assertTrue(session.canShowFind)
        assertTrue(session.showFind())
        assertTrue(
            session.updateFindFieldValue(
                TextFieldValue(
                    text = query,
                    selection = TextRange(start = 0, end = query.length)
                )
            )
        )

        val status = session.findStatus as FindStatus.Match
        assertEquals(
            Utf16Range(
                start = FIRST_OVERLAPPING_MATCH_START,
                end = FIRST_OVERLAPPING_MATCH_START + query.length
            ),
            status.match.range
        )
        assertNull(status.wrappedAt)
        assertSame(status.match, session.findMatch)
        assertEquals(query, document.delegate.findCalls.single().query)
        session.close()
    }

    /** Verifies Find ignores case by default and reruns when Match case is enabled. */
    @Test
    fun togglesFindCaseSensitivityWithoutChangingTheQuery() {
        val documentText = "Before NEEDLE and needle"
        val query = "needle"
        val firstMatchStart = documentText.indexOf("NEEDLE")
        val exactMatchStart = documentText.indexOf(query)
        val document = ExactFindViewportEditorDocument(TestEditorDocument(documentText))
        val session = createSession(document = document, findDelay = {})
        session.openInitialEditor()
        assertTrue(session.showFind())

        assertTrue(session.updateFindFieldValue(TextFieldValue(query)))

        var status = session.findStatus as FindStatus.Match
        assertEquals(firstMatchStart.toLong(), status.match.range.start)
        assertFalse(session.isFindCaseSensitive)
        assertFalse(document.delegate.findCalls.single().matchCase)

        assertTrue(session.updateFindCaseSensitivity(matchCase = true))

        status = session.findStatus as FindStatus.Match
        assertEquals(exactMatchStart.toLong(), status.match.range.start)
        assertTrue(session.isFindCaseSensitive)
        assertEquals(query, session.findFieldValue.text)
        assertTrue(document.delegate.findCalls.last().matchCase)
        session.close()
    }

    /** Verifies opening Find commits one dirty active edit without closing it. */
    @Test
    fun flushesAnActiveEditBeforeOpeningFind() {
        val replacementText = "after target"
        val query = "target"
        val queryStart = replacementText.indexOf(query)
        val document = ExactFindViewportEditorDocument(TestEditorDocument("before target"))
        val session = createSession(document = document, findDelay = {})
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        draft.updateEditorFocusIntent(isFocused = true, canClear = true)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = replacementText)
            selection = TextRange(queryStart)
        }

        assertTrue(session.showFind())

        assertFalse(session.hasPendingEditWindowAction)
        requireNotNull(session.activeDraft)
        requireNotNull(session.state.activeEdit)
        assertEquals(replacementText, document.delegate.text)
        assertTrue(session.isFindVisible)
        assertFalse(requireNotNull(session.activeDraft).shouldRestoreEditorFocus)
        assertTrue(session.updateFindFieldValue(TextFieldValue(query)))
        val status = session.findStatus as FindStatus.Match
        assertEquals(queryStart.toLong(), status.match.range.start)
        assertNull(status.wrappedAt)
        val movedDraft = requireNotNull(session.activeDraft)
        assertEquals(status.match.range, movedDraft.edit.snapshot.selection)
        assertFalse(movedDraft.hasChanges)
        assertFalse(movedDraft.shouldRestoreEditorFocus)

        session.closeFind()

        assertFalse(session.isFindVisible)
        assertTrue(movedDraft.shouldRestoreEditorFocus)
        session.close()
    }

    /** Verifies File info synchronizes live facts without closing or moving the editor. */
    @Test
    fun opensFileInfoWithoutClosingTheActiveEdit() {
        val replacementText = "after details"
        val expectedSelection = TextRange(5)
        val document = TestEditorDocument("before")
        val session = createSession(document)
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = replacementText)
            selection = expectedSelection
        }

        assertTrue(session.showFileInfo())

        assertTrue(session.isFileInfoVisible)
        assertFalse(session.hasPendingEditWindowAction)
        assertSame(draft, session.activeDraft)
        assertSame(draft.edit, requireNotNull(session.state.activeEdit))
        assertEquals(replacementText, document.text)
        assertEquals(expectedSelection, draft.textFieldState.selection)
        assertEquals(2L, requireNotNull(session.state.metrics).wordCount)

        session.dismissFileInfo()

        assertFalse(session.isFileInfoVisible)
        assertSame(draft, session.activeDraft)
        assertEquals(expectedSelection, draft.textFieldState.selection)
        session.close()
    }

    /** Verifies File info exposes trash and deletion as independent live capabilities. */
    @Test
    fun loadsIndependentDocumentRemovalCapabilities() {
        val capabilities =
            DocumentRemovalCapabilities(canTrash = true, canDelete = false)
        val source = TestEditorDocumentSource(removalCapabilities = capabilities)
        val session =
            createSession(
                document = TestEditorDocument("source text"),
                documentSource = source
            )
        session.openInitialEditor()

        assertTrue(session.showFileInfo())
        assertEquals(DocumentRemovalStatus.Idle, session.documentRemovalStatus)
        assertTrue(session.refreshDocumentRemovalCapabilities())

        val ready = session.documentRemovalStatus as DocumentRemovalStatus.Ready
        assertEquals(capabilities, ready.capabilities)
        assertEquals(1, source.removalCapabilityQueryCount)
        assertNull(session.documentRemovalUnavailableReason)
        session.close()
    }

    /** Verifies provider-query failures remain retryable without exposing provider details. */
    @Test
    fun reportsDocumentRemovalCapabilityFailure() {
        val source =
            TestEditorDocumentSource(
                querySourceRemovalCapabilities = {
                    throw IllegalStateException("synthetic capability failure")
                }
            )
        val session =
            createSession(
                document = TestEditorDocument("source text"),
                documentSource = source
            )
        session.openInitialEditor()
        assertTrue(session.showFileInfo())

        assertTrue(session.refreshDocumentRemovalCapabilities())

        val failed = session.documentRemovalStatus as DocumentRemovalStatus.Failed
        assertEquals(
            UiText.Resource(R.string.operation_document_removal_capability_failure),
            failed.message
        )
        assertEquals(1, source.removalCapabilityQueryCount)
        assertTrue(source.removalActions.isEmpty())
        session.close()
    }

    /** Verifies permanent deletion requires confirmation and locks File info on success. */
    @Test
    fun confirmsPermanentDocumentDeletion() {
        val capabilities =
            DocumentRemovalCapabilities(canTrash = true, canDelete = true)
        val source = TestEditorDocumentSource(removalCapabilities = capabilities)
        val session =
            createSession(
                document = TestEditorDocument("source text"),
                documentSource = source
            )
        session.openInitialEditor()
        assertTrue(session.showFileInfo())
        assertTrue(session.refreshDocumentRemovalCapabilities())

        assertTrue(
            session.requestDocumentRemovalConfirmation(DocumentRemovalAction.Trash)
        )
        session.dismissDocumentRemovalConfirmation()
        assertEquals(
            DocumentRemovalStatus.Ready(capabilities),
            session.documentRemovalStatus
        )
        assertTrue(
            session.requestDocumentRemovalConfirmation(DocumentRemovalAction.Delete)
        )
        assertTrue(session.confirmDocumentRemoval())

        assertEquals(
            DocumentRemovalStatus.Succeeded(DocumentRemovalAction.Delete),
            session.documentRemovalStatus
        )
        assertEquals(listOf(DocumentRemovalAction.Delete), source.removalActions)
        session.dismissFileInfo()
        assertTrue(session.isFileInfoVisible)
        session.close()
        assertEquals(1, source.closeCallCount)
    }

    /** Verifies cleanup retains the source until a started destructive call returns. */
    @Test
    fun retainsSourceOwnershipDuringDocumentRemoval() = runBlocking {
        val removalStarted = CompletableDeferred<Unit>()
        val removalRelease = CompletableDeferred<Unit>()
        val source =
            TestEditorDocumentSource(
                removalCapabilities =
                    DocumentRemovalCapabilities(canTrash = true, canDelete = false),
                removeSource = {
                    withContext(NonCancellable) {
                        removalStarted.complete(Unit)
                        removalRelease.await()
                    }
                }
            )
        val session =
            createSession(
                document = TestEditorDocument("source text"),
                documentSource = source
            )
        session.openInitialEditor()
        assertTrue(session.showFileInfo())
        assertTrue(session.refreshDocumentRemovalCapabilities())
        assertTrue(
            session.requestDocumentRemovalConfirmation(DocumentRemovalAction.Trash)
        )
        assertTrue(session.confirmDocumentRemoval())
        removalStarted.await()

        session.close()

        assertEquals(0, source.closeCallCount)
        removalRelease.complete(Unit)
        assertEquals(1, source.closeCallCount)
    }

    /** Verifies source conflicts block removal instead of discarding local edits. */
    @Test
    fun blocksDocumentRemovalAfterASourceConflict() {
        val source =
            TestEditorDocumentSource(
                removalCapabilities =
                    DocumentRemovalCapabilities(canTrash = true, canDelete = true)
            ) { _, _ ->
                throw DocumentExportException(DocumentExportFailure.SOURCE_CONFLICT)
            }
        val session =
            createSession(
                document = TestEditorDocument("before"),
                documentSource = source
            )
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "after")
        }

        assertTrue(session.showFileInfo())
        assertTrue(session.sourceSaveStatus is SourceSaveStatus.Conflict)
        assertTrue(session.refreshDocumentRemovalCapabilities())

        assertEquals(
            UiText.Resource(R.string.operation_remove_resolve_save),
            session.documentRemovalUnavailableReason
        )
        assertFalse(
            session.requestDocumentRemovalConfirmation(DocumentRemovalAction.Trash)
        )
        assertTrue(source.removalActions.isEmpty())
        session.close()
    }

    /** Verifies an uncertain provider result is never retried automatically. */
    @Test
    fun requiresAFreshCheckAfterDocumentRemovalFailure() {
        val source =
            TestEditorDocumentSource(
                removalCapabilities =
                    DocumentRemovalCapabilities(canTrash = true, canDelete = false),
                removeSource = { throw IllegalStateException("synthetic trash failure") }
            )
        val session =
            createSession(
                document = TestEditorDocument("source text"),
                documentSource = source
            )
        session.openInitialEditor()
        assertTrue(session.showFileInfo())
        assertTrue(session.refreshDocumentRemovalCapabilities())
        assertTrue(
            session.requestDocumentRemovalConfirmation(DocumentRemovalAction.Trash)
        )

        assertTrue(session.confirmDocumentRemoval())

        assertTrue(session.documentRemovalStatus is DocumentRemovalStatus.Failed)
        assertEquals(listOf(DocumentRemovalAction.Trash), source.removalActions)
        assertFalse(
            session.requestDocumentRemovalConfirmation(DocumentRemovalAction.Trash)
        )
        assertTrue(session.refreshDocumentRemovalCapabilities())
        assertEquals(2, source.removalCapabilityQueryCount)
        assertTrue(session.documentRemovalStatus is DocumentRemovalStatus.Ready)
        session.close()
    }

    /** Verifies opening Find uses the latest caret even when text is already clean. */
    @Test
    fun opensFindAtTheLatestCleanCaret() {
        val document = ExactFindViewportEditorDocument(TestEditorDocument(FIND_DOCUMENT_TEXT))
        val session = createSession(document = document, findDelay = {})
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            selection = TextRange(SECOND_OVERLAPPING_MATCH_START.toInt())
        }

        assertTrue(session.showFind())
        assertTrue(session.updateFindFieldValue(TextFieldValue(OVERLAPPING_FIND_QUERY)))

        val status = session.findStatus as FindStatus.Match
        assertEquals(SECOND_OVERLAPPING_MATCH_START, status.match.range.start)
        assertNull(status.wrappedAt)
        assertTrue(document.delegate.replaceCalls.isEmpty())
        session.close()
    }

    /** Verifies Find searches composing text and rejects unsafe query values. */
    @Test
    fun searchesAndValidatesComposingFindFieldValues() {
        val document = ExactFindViewportEditorDocument(TestEditorDocument(FIND_DOCUMENT_TEXT))
        val session = createSession(document = document, findDelay = {})
        session.openInitialEditor()
        assertTrue(session.showFind())
        val composingValue =
            TextFieldValue(
                text = OVERLAPPING_FIND_QUERY,
                selection = TextRange(start = 1, end = 2),
                composition = TextRange(start = 0, end = OVERLAPPING_FIND_QUERY.length)
            )

        assertTrue(session.updateFindFieldValue(composingValue))
        assertEquals(composingValue, session.findFieldValue)
        assertTrue(session.findStatus is FindStatus.Match)
        assertTrue(session.canNavigateFind)
        assertEquals(
            listOf(OVERLAPPING_FIND_QUERY),
            document.delegate.findCalls.map { request -> request.query }
        )

        val committedValue =
            composingValue.copy(
                selection = TextRange(OVERLAPPING_FIND_QUERY.length),
                composition = null
            )
        assertTrue(session.updateFindFieldValue(committedValue))
        assertEquals(committedValue, session.findFieldValue)
        assertTrue(session.findStatus is FindStatus.Match)
        assertEquals(1, document.delegate.findCalls.size)

        val rejectedValues =
            listOf(
                TextFieldValue("line\nbreak"),
                TextFieldValue("line\rbreak"),
                TextFieldValue("\uD800"),
                TextFieldValue("a".repeat(MAX_FIND_QUERY_UTF16_UNITS + 1))
            )
        rejectedValues.forEach { rejectedValue ->
            assertFalse(session.updateFindFieldValue(rejectedValue))
            assertEquals(committedValue, session.findFieldValue)
        }
        session.close()
    }

    /** Verifies a newer debounced query cancels the older generation. */
    @Test
    fun searchesOnlyTheNewestDebouncedFindQuery() {
        val documentText = "alpha beta"
        val expectedQuery = "beta"
        val findDelay = CompletableDeferred<Unit>()
        val document = ExactFindViewportEditorDocument(TestEditorDocument(documentText))
        val session =
            createSession(
                document = document,
                findDelay = { findDelay.await() }
            )
        session.openInitialEditor()
        assertTrue(session.showFind())

        assertTrue(session.updateFindFieldValue(TextFieldValue("alpha")))
        assertTrue(session.updateFindFieldValue(TextFieldValue(expectedQuery)))
        assertEquals(FindStatus.Searching, session.findStatus)
        assertTrue(document.delegate.findCalls.isEmpty())

        findDelay.complete(Unit)

        val status = session.findStatus as FindStatus.Match
        assertEquals(documentText.indexOf(expectedQuery).toLong(), status.match.range.start)
        assertEquals(listOf(expectedQuery), document.delegate.findCalls.map { call -> call.query })
        session.close()
    }

    /** Verifies next and previous traverse overlapping matches with one circular wrap. */
    @Test
    fun navigatesOverlappingFindMatchesWithCircularWrap() {
        val document =
            ExactFindViewportEditorDocument(
                TestEditorDocument(
                    initialText = FIND_DOCUMENT_TEXT,
                    editWindowUtf16Units = 2
                )
            )
        val session = createSession(document = document, findDelay = {})
        session.openInitialEditor()
        assertTrue(session.showFind())
        assertTrue(session.updateFindFieldValue(TextFieldValue(OVERLAPPING_FIND_QUERY)))

        var status = session.findStatus as FindStatus.Match
        assertEquals(FIRST_OVERLAPPING_MATCH_START, status.match.range.start)
        assertNull(status.wrappedAt)
        assertEquals(
            status.match.range,
            requireNotNull(session.state.activeEdit).snapshot.selection
        )

        assertTrue(session.findNext())
        status = session.findStatus as FindStatus.Match
        assertEquals(SECOND_OVERLAPPING_MATCH_START, status.match.range.start)
        assertNull(status.wrappedAt)
        assertEquals(
            status.match.range,
            requireNotNull(session.state.activeEdit).snapshot.selection
        )

        val callsBeforeForwardWrap = document.delegate.findCalls.size
        assertTrue(session.findNext())
        status = session.findStatus as FindStatus.Match
        assertEquals(FIRST_OVERLAPPING_MATCH_START, status.match.range.start)
        assertEquals(FindWrap.Beginning, status.wrappedAt)
        assertEquals(callsBeforeForwardWrap + 2, document.delegate.findCalls.size)
        assertEquals(
            status.match.range,
            requireNotNull(session.state.activeEdit).snapshot.selection
        )

        val callsBeforeBackwardWrap = document.delegate.findCalls.size
        assertTrue(session.findPrevious())
        status = session.findStatus as FindStatus.Match
        assertEquals(SECOND_OVERLAPPING_MATCH_START, status.match.range.start)
        assertEquals(FindWrap.End, status.wrappedAt)
        assertEquals(callsBeforeBackwardWrap + 2, document.delegate.findCalls.size)
        assertEquals(
            status.match.range,
            requireNotNull(session.state.activeEdit).snapshot.selection
        )

        assertTrue(session.findPrevious())
        status = session.findStatus as FindStatus.Match
        assertEquals(FIRST_OVERLAPPING_MATCH_START, status.match.range.start)
        assertNull(status.wrappedAt)
        assertEquals(FindDirection.Backward, document.delegate.findCalls.last().direction)
        session.close()
    }

    /** Verifies a miss exhausts only the two nonempty circular candidate phases. */
    @Test
    fun stopsFindAfterOneCompleteCircularMiss() {
        val document = ExactFindViewportEditorDocument(TestEditorDocument(FIND_DOCUMENT_TEXT))
        val session = createSession(
            document = document,
            documentSource = TestReadOnlyEditorDocumentSource(),
            findDelay = {}
        )
        session.openInitialEditor()
        val origin = FIND_DOCUMENT_TEXT.length / 2L
        session.observeVisibleViewportAnchor(revision = 0L, utf16Offset = origin)
        assertTrue(session.showFind())

        assertTrue(session.updateFindFieldValue(TextFieldValue("missing")))

        assertEquals(FindStatus.NoMatches, session.findStatus)
        assertEquals(
            listOf(
                Utf16Range(start = origin, end = FIND_DOCUMENT_TEXT.length.toLong()),
                Utf16Range(start = 0L, end = origin)
            ),
            document.delegate.findCalls.map { call -> call.candidateRange }
        )
        session.close()
    }

    /** Verifies Find and line navigation exclude each other and closing clears Find. */
    @Test
    fun keepsFindAndGoToLineMutuallyExclusive() {
        val document = ExactFindViewportEditorDocument(TestEditorDocument("alpha\nbeta"))
        val session = createSession(document = document, findDelay = {})
        session.openInitialEditor()

        assertTrue(session.showGoToLineDialog(currentVisibleLogicalLine = 0L))
        assertFalse(session.canShowFind)
        assertFalse(session.showFind())
        session.dismissGoToLineDialog()

        assertTrue(session.showFind())
        assertFalse(session.canNavigateToLine)
        assertFalse(session.showGoToLineDialog(currentVisibleLogicalLine = 0L))
        assertTrue(session.updateFindFieldValue(TextFieldValue("beta")))
        assertTrue(session.findStatus is FindStatus.Match)

        session.closeFind()

        assertFalse(session.isFindVisible)
        assertEquals(TextFieldValue(), session.findFieldValue)
        assertEquals(FindStatus.Idle, session.findStatus)
        assertNull(session.findMatch)
        val resumedDraft = requireNotNull(session.activeDraft)
        assertEquals(Utf16Range(start = 6L, end = 10L), resumedDraft.edit.snapshot.selection)
        assertTrue(resumedDraft.shouldRestoreEditorFocus)
        session.close()
    }

    /** Verifies public paging and retry entry points cannot replace a Find viewport. */
    @Test
    fun blocksPaginationAndViewportRetryWhileFindIsVisible() {
        val document = PagedEditorDocument(TestEditorDocument("alpha\nbeta\ngamma"))
        val state = EditorDocumentState(document, ImmediateSessionTestDispatcher)
        val session =
            EditorSession(
                title = "Test document",
                state = state,
                documentSource = TestReadOnlyEditorDocumentSource(),
                operationDispatcher = ImmediateSessionTestDispatcher,
                findDelay = {}
            )
        session.openInitialEditor()
        assertTrue(session.showGoToLineDialog(currentVisibleLogicalLine = 0L))
        session.updateGoToLineInput("2")
        assertTrue(session.confirmGoToLine())
        assertTrue(state.canLoadPrevious)
        assertTrue(state.canLoadMore)
        assertTrue(session.showFind())
        val viewportCallsBeforePaging = document.viewportCallCount
        val previousCallsBeforePaging = document.previousViewportCallCount

        session.loadNextViewport()
        session.loadPreviousViewport()

        assertEquals(viewportCallsBeforePaging, document.viewportCallCount)
        assertEquals(previousCallsBeforePaging, document.previousViewportCallCount)

        document.failNextViewport = true
        runBlocking { state.loadNextViewport() }
        val failedViewportToken = requireNotNull(state.viewportFailureToken)
        val viewportCallsBeforeRetry = document.viewportCallCount

        session.retryViewport()

        assertEquals(viewportCallsBeforeRetry, document.viewportCallCount)
        assertSame(failedViewportToken, state.viewportFailureToken)
        session.close()
    }

    /** Verifies a stale Find failure cannot retain a match from the older revision. */
    @Test
    fun clearsTheOldFindMatchAfterAStaleRevisionFailure() {
        val document = ExactFindViewportEditorDocument(TestEditorDocument(FIND_DOCUMENT_TEXT))
        val session = createSession(
            document = document,
            documentSource = TestReadOnlyEditorDocumentSource(),
            findDelay = {}
        )
        session.openInitialEditor()
        session.observeVisibleViewportAnchor(
            revision = 0L,
            utf16Offset = SECOND_OVERLAPPING_MATCH_START
        )
        assertTrue(session.showFind())
        assertTrue(session.updateFindFieldValue(TextFieldValue(OVERLAPPING_FIND_QUERY)))
        assertEquals(
            SECOND_OVERLAPPING_MATCH_START,
            requireNotNull(session.findMatch).range.start
        )

        document.delegate.advanceExternally(newRevision = 1L)
        assertTrue(session.findNext())

        assertEquals(EditorDocumentStatus.Stale, session.state.status)
        assertTrue(session.findStatus is FindStatus.Failed)
        assertNull(session.findMatch)
        assertEquals(OVERLAPPING_FIND_QUERY, session.findFieldValue.text)
        session.close()
    }

    /** Verifies a reloaded visible block rebases and reruns the retained Find query. */
    @Test
    fun rerunsRetainedFindFromTheReloadedRevisionVisibleBlock() {
        val newRevision = 1L
        val document = ExactFindViewportEditorDocument(TestEditorDocument(FIND_DOCUMENT_TEXT))
        val session = createSession(
            document = document,
            documentSource = TestReadOnlyEditorDocumentSource(),
            findDelay = {}
        )
        session.openInitialEditor()
        session.observeVisibleViewportAnchor(
            revision = 0L,
            utf16Offset = SECOND_OVERLAPPING_MATCH_START
        )
        assertTrue(session.showFind())
        assertTrue(session.updateFindFieldValue(TextFieldValue(OVERLAPPING_FIND_QUERY)))
        assertEquals(
            SECOND_OVERLAPPING_MATCH_START,
            requireNotNull(session.findMatch).range.start
        )
        document.delegate.advanceExternally(newRevision)
        assertTrue(session.findNext())
        assertEquals(EditorDocumentStatus.Stale, session.state.status)

        session.reloadStaleViewport()

        assertEquals(EditorDocumentStatus.Ready, session.state.status)
        assertEquals(newRevision, requireNotNull(session.state.metrics).revision)
        assertTrue(session.findStatus is FindStatus.Failed)
        assertNull(session.findMatch)
        val firstVisibleBlock = session.state.blocks.first().block
        val findCallsBeforeObservation = document.delegate.findCalls.size

        session.observeVisibleViewportAnchor(
            revision = newRevision,
            utf16Offset = firstVisibleBlock.globalUtf16Start
        )

        val status = session.findStatus as FindStatus.Match
        assertEquals(newRevision, status.match.start.revision)
        assertEquals(FIRST_OVERLAPPING_MATCH_START, status.match.range.start)
        assertNull(status.wrappedAt)
        assertEquals(findCallsBeforeObservation + 1, document.delegate.findCalls.size)
        val rerunRequest = document.delegate.findCalls.last()
        assertEquals(newRevision, rerunRequest.revision)
        assertEquals(firstVisibleBlock.globalUtf16Start, rerunRequest.candidateRange.start)
        assertEquals(OVERLAPPING_FIND_QUERY, rerunRequest.query)
        session.close()
    }

    /** Verifies recreation cannot reopen editing behind one retained Find workflow. */
    @Test
    fun retainsFindAcrossRepeatedInitialEditorRequests() {
        val document = ExactFindViewportEditorDocument(TestEditorDocument(FIND_DOCUMENT_TEXT))
        val session = createSession(document = document, findDelay = {})
        session.openInitialEditor()
        assertTrue(session.showFind())
        assertTrue(session.updateFindFieldValue(TextFieldValue(OVERLAPPING_FIND_QUERY)))
        val retainedFieldValue = session.findFieldValue
        val retainedStatus = session.findStatus as FindStatus.Match
        val retainedMatch = requireNotNull(session.findMatch)
        val viewportCallsBeforeRecreation = document.delegate.viewportCalls.size
        val editWindowCallsBeforeRecreation = document.delegate.editWindowCalls.size

        session.openInitialEditor()
        session.openInitialEditor()

        assertTrue(session.isFindVisible)
        assertEquals(EditorDocumentStatus.Ready, session.state.status)
        requireNotNull(session.activeDraft)
        requireNotNull(session.state.activeEdit)
        assertEquals(viewportCallsBeforeRecreation, document.delegate.viewportCalls.size)
        assertEquals(editWindowCallsBeforeRecreation, document.delegate.editWindowCalls.size)
        assertEquals(retainedFieldValue, session.findFieldValue)
        assertSame(retainedStatus, session.findStatus)
        assertSame(retainedMatch, session.findMatch)
        session.close()
    }

    /** Verifies the initial ordinary-document flow creates one complete draft. */
    @Test
    fun opensAnOrdinaryDocumentDirectlyForEditing() {
        val originalText = "alpha\nbeta"
        val session = createSession(document = TestEditorDocument(originalText))

        session.openInitialEditor()

        val draft = requireNotNull(session.activeDraft)
        assertEquals(originalText, draft.textFieldState.text.toString())
        assertFalse(draft.edit.snapshot.hasPrevious)
        assertFalse(draft.edit.snapshot.hasNext)
        assertFalse(draft.shouldRestoreEditorFocus)
        assertTrue(session.canNavigateToLine)
        session.close()
    }

    /** Verifies a read-only source opens without exposing an editable draft. */
    @Test
    fun opensAReadOnlySourceInViewOnlyMode() {
        val document = TestEditorDocument("read only")
        val source = TestReadOnlyEditorDocumentSource()
        val session =
            EditorSession(
                title = "Read-only document",
                state = EditorDocumentState(document, ImmediateSessionTestDispatcher),
                documentSource = source,
                operationDispatcher = ImmediateSessionTestDispatcher,
                editSynchronizationDelay = {}
            )

        session.openInitialEditor()

        assertTrue(session.isViewOnly)
        assertTrue(session.hasDocumentSource)
        assertTrue(session.matchesDocumentSourceUri(RETAINED_SOURCE_URI))
        assertNull(session.activeDraft)
        assertEquals(listOf("read only"), session.state.blocks.map { block -> block.block.text })
        assertTrue(session.canStartSaveAs)


        assertNull(session.activeDraft)
        assertTrue(document.editWindowCalls.isEmpty())
        session.close()
        assertEquals(1, source.closeCallCount)
    }

    /** Verifies saving a view-only document creates its editable autosave source. */
    @Test
    fun savesAViewOnlyDocumentAsAnEditableSource() {
        val document = TestEditorDocument("read only")
        val readOnlySource = TestReadOnlyEditorDocumentSource()
        val source = TestEditorDocumentSource()
        val session =
            EditorSession(
                title = "Read-only document",
                state = EditorDocumentState(document, ImmediateSessionTestDispatcher),
                documentSource = readOnlySource,
                operationDispatcher = ImmediateSessionTestDispatcher,
                editSynchronizationDelay = {}
            )
        session.openInitialEditor()
        assertTrue(session.showSaveFormatSelection())
        session.selectSaveFormat(DocumentFormat.PlainText)
        val request = session.claimPreparedSaveDestination()

        assertTrue(
            session.saveSelectedSource(
                request = request,
                documentSource = source,
                sourceDisplayName = "editable.txt"
            )
        )

        assertFalse(session.isViewOnly)
        assertTrue(session.hasDocumentSource)
        assertEquals("editable.txt", session.title)
        assertEquals(SourceSaveStatus.Saved, session.sourceSaveStatus)
        assertEquals(listOf(0L), source.savedRevisions)
        assertEquals(1, readOnlySource.closeCallCount)
        assertTrue(requireNotNull(session.activeDraft).shouldRestoreEditorFocus)
        session.close()
        assertEquals(1, source.closeCallCount)
    }

    /** Verifies a replacement remains view only until its initial save completes. */
    @Test
    fun unlocksAViewOnlyDocumentOnlyAfterItsInitialSave() {
        val saveGate = CompletableDeferred<Unit>()
        val document = TestEditorDocument("read only")
        val readOnlySource = TestReadOnlyEditorDocumentSource()
        val writableSource =
            TestEditorDocumentSource(encodedUri = OTHER_SOURCE_URI) { _, _ ->
                saveGate.await()
            }
        val session =
            EditorSession(
                title = "Read-only document",
                state = EditorDocumentState(document, ImmediateSessionTestDispatcher),
                documentSource = readOnlySource,
                operationDispatcher = ImmediateSessionTestDispatcher,
                editSynchronizationDelay = {}
            )
        session.openInitialEditor()
        assertTrue(session.showSaveFormatSelection())
        session.selectSaveFormat(DocumentFormat.PlainText)
        val request = session.claimPreparedSaveDestination()

        assertTrue(session.saveSelectedSource(request, writableSource, "editable.txt"))

        assertTrue(session.isViewOnly)
        assertEquals(SourceSaveStatus.Saving, session.sourceSaveStatus)
        assertFalse(session.canStartSaveAs)
        assertTrue(session.matchesDocumentSourceUri(OTHER_SOURCE_URI))
        assertFalse(session.matchesDocumentSourceUri(RETAINED_SOURCE_URI))
        assertEquals(1, readOnlySource.closeCallCount)


        assertNull(session.activeDraft)
        assertTrue(document.editWindowCalls.isEmpty())
        saveGate.complete(Unit)

        assertFalse(session.isViewOnly)
        assertEquals(SourceSaveStatus.Saved, session.sourceSaveStatus)
        assertTrue(requireNotNull(session.activeDraft).shouldRestoreEditorFocus)
        session.close()
        assertEquals(1, writableSource.closeCallCount)
    }

    /** Verifies a failed initial replacement stays view only until retry succeeds. */
    @Test
    fun keepsAViewOnlyDocumentLockedAfterInitialSaveFailure() {
        val document = TestEditorDocument("read only")
        val readOnlySource = TestReadOnlyEditorDocumentSource()
        val writableSource =
            TestEditorDocumentSource { _, attempt ->
                if (attempt == 1) {
                    throw IllegalStateException("synthetic initial save failure")
                }
            }
        val session =
            EditorSession(
                title = "Read-only document",
                state = EditorDocumentState(document, ImmediateSessionTestDispatcher),
                documentSource = readOnlySource,
                operationDispatcher = ImmediateSessionTestDispatcher,
                editSynchronizationDelay = {}
            )
        session.openInitialEditor()
        assertTrue(session.showSaveFormatSelection())
        session.selectSaveFormat(DocumentFormat.Markdown)
        val request = session.claimPreparedSaveDestination()

        assertTrue(session.saveSelectedSource(request, writableSource))

        assertTrue(session.isViewOnly)
        assertTrue(session.sourceSaveStatus is SourceSaveStatus.Failed)
        assertTrue(session.shouldSelectNewDocumentSource)
        assertTrue(session.canStartSaveAs)
        assertFalse(session.hasUnsavedChanges)
        assertEquals(1, readOnlySource.closeCallCount)

        session.retrySourceSave()

        assertFalse(session.isViewOnly)
        assertEquals(SourceSaveStatus.Saved, session.sourceSaveStatus)
        assertEquals(listOf(0L, 0L), writableSource.savedRevisions)
        assertTrue(requireNotNull(session.activeDraft).shouldRestoreEditorFocus)
        session.close()
        assertEquals(1, writableSource.closeCallCount)
    }

    /** Verifies closing keeps an active editable-file source until its save settles. */
    @Test
    fun closesAViewOnlyReplacementSourceOnlyAfterItsInitialSaveSettles() {
        val saveGate = CompletableDeferred<Unit>()
        val document = TestEditorDocument("read only")
        val readOnlySource = TestReadOnlyEditorDocumentSource()
        val writableSource =
            TestEditorDocumentSource { _, _ ->
                withContext(NonCancellable) {
                    saveGate.await()
                }
            }
        val session =
            EditorSession(
                title = "Read-only document",
                state = EditorDocumentState(document, ImmediateSessionTestDispatcher),
                documentSource = readOnlySource,
                operationDispatcher = ImmediateSessionTestDispatcher,
                editSynchronizationDelay = {}
            )
        session.openInitialEditor()
        assertTrue(session.showSaveFormatSelection())
        session.selectSaveFormat(DocumentFormat.PlainText)
        val request = session.claimPreparedSaveDestination()
        assertTrue(session.saveSelectedSource(request, writableSource))
        assertEquals(SourceSaveStatus.Saving, session.sourceSaveStatus)

        session.close()

        assertTrue(session.isViewOnly)
        assertEquals(SourceSaveStatus.NoSource, session.sourceSaveStatus)
        assertEquals(1, readOnlySource.closeCallCount)
        assertEquals(0, writableSource.closeCallCount)
        assertEquals(1, document.closeCallCount)

        saveGate.complete(Unit)

        assertTrue(session.isViewOnly)
        assertEquals(SourceSaveStatus.NoSource, session.sourceSaveStatus)
        assertEquals(1, writableSource.closeCallCount)
    }

    /** Verifies an initial editable-file conflict keeps the document view only. */
    @Test
    fun keepsAViewOnlyDocumentLockedAfterInitialSourceConflict() {
        verifyViewOnlyInitialTerminalSave(DocumentExportFailure.SOURCE_CONFLICT)
    }

    /** Verifies an uncertain initial editable-file write keeps the document view only. */
    @Test
    fun keepsAViewOnlyDocumentLockedAfterUncertainInitialSave() {
        verifyViewOnlyInitialTerminalSave(DocumentExportFailure.SOURCE_UNCERTAIN)
    }

    /** Verifies focus intent survives window loss but follows deliberate focus changes. */
    @Test
    fun retainsOnlyIntentionalEditorFocus() {
        val session = createSession(document = TestEditorDocument("alpha"))
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)

        draft.updateEditorFocusIntent(isFocused = true, canClear = true)
        assertTrue(draft.shouldRestoreEditorFocus)

        draft.updateEditorFocusIntent(isFocused = false, canClear = false)
        assertTrue(draft.shouldRestoreEditorFocus)

        draft.updateEditorFocusIntent(isFocused = false, canClear = true)
        assertFalse(draft.shouldRestoreEditorFocus)
        session.close()
    }

    /** Verifies focus restoration repairs the exact observed active-end collapse. */
    @Test
    fun restoresANoncollapsedSelectionCollapsedByFocus() {
        withActiveDraft { draft ->
            val retainedSelection =
                TextRange(start = FOCUS_SELECTION_START, end = FOCUS_SELECTION_END)
            draft.textFieldState.edit {
                selection = TextRange(retainedSelection.end)
            }

            draft.restoreSelectionCollapsedByFocus(retainedSelection)

            assertEquals(retainedSelection, draft.textFieldState.selection)
        }
    }

    /** Verifies focus restoration never overwrites another caret or selection. */
    @Test
    fun preservesSelectionsThatDoNotMatchTheFocusCollapse() {
        withActiveDraft { draft ->
            val retainedSelection =
                TextRange(start = FOCUS_SELECTION_START, end = FOCUS_SELECTION_END)
            val differentCaret = TextRange(DIFFERENT_FOCUS_CARET)
            draft.textFieldState.edit {
                selection = differentCaret
            }

            draft.restoreSelectionCollapsedByFocus(retainedSelection)

            assertEquals(differentCaret, draft.textFieldState.selection)

            val differentSelection =
                TextRange(start = DIFFERENT_SELECTION_START, end = DIFFERENT_SELECTION_END)
            draft.textFieldState.edit {
                selection = differentSelection
            }

            draft.restoreSelectionCollapsedByFocus(retainedSelection)

            assertEquals(differentSelection, draft.textFieldState.selection)
        }
    }

    /** Verifies an originally collapsed caret does not trigger focus restoration. */
    @Test
    fun preservesACollapsedRetainedFocusSelection() {
        withActiveDraft { draft ->
            val retainedCaret = TextRange(FOCUS_SELECTION_END)
            draft.textFieldState.edit {
                selection = retainedCaret
            }

            draft.restoreSelectionCollapsedByFocus(retainedCaret)

            assertEquals(retainedCaret, draft.textFieldState.selection)
        }
    }

    /** Verifies active IME composition suppresses focus-selection restoration. */
    @Test
    fun refusesFocusSelectionRestorationDuringComposition() {
        val retainedSelection =
            TextRange(start = FOCUS_SELECTION_START, end = FOCUS_SELECTION_END)
        val collapsedSelection = TextRange(retainedSelection.end)

        assertFalse(
            shouldRestoreSelectionCollapsedByFocus(
                retainedSelection = retainedSelection,
                currentSelection = collapsedSelection,
                hasComposition = true
            )
        )
        assertTrue(
            shouldRestoreSelectionCollapsedByFocus(
                retainedSelection = retainedSelection,
                currentSelection = collapsedSelection,
                hasComposition = false
            )
        )
    }

    /** Verifies focus restoration uses the active end of a reversed selection. */
    @Test
    fun restoresAReversedSelectionFromItsActiveEnd() {
        withActiveDraft { draft ->
            val retainedSelection =
                TextRange(start = FOCUS_SELECTION_END, end = FOCUS_SELECTION_START)
            draft.textFieldState.edit {
                selection = TextRange(retainedSelection.end)
            }

            draft.restoreSelectionCollapsedByFocus(retainedSelection)

            assertEquals(retainedSelection, draft.textFieldState.selection)
        }
    }

    /** Verifies a new-document policy seeds one retained initial focus request. */
    @Test
    fun requestsInitialFocusForANewDocument() {
        val document = TestEditorDocument("")
        val session =
            EditorSession(
                title = "New document",
                state = EditorDocumentState(document, ImmediateSessionTestDispatcher),
                shouldFocusInitialEditor = true,
                operationDispatcher = ImmediateSessionTestDispatcher,
                editSynchronizationDelay = {}
            )

        session.openInitialEditor()

        assertTrue(requireNotNull(session.activeDraft).shouldRestoreEditorFocus)
        session.close()
    }

    /** Verifies received text stays editable without requesting the keyboard initially. */
    @Test
    fun opensUnfocusedTextWithoutAReadOnlyMode() {
        val document = TestEditorDocument("received text")
        val session = createSession(document)

        session.openInitialEditor()

        val draft = requireNotNull(session.activeDraft)
        assertFalse(draft.shouldRestoreEditorFocus)
        assertFalse(session.isViewOnly)
        assertEquals("received text", draft.textFieldState.text.toString())
        draft.textFieldState.edit {
            append(" edited")
            selection = TextRange(length)
        }
        session.flushPendingEdit()
        assertEquals("received text edited", document.text)
        session.close()
    }

    /** Verifies a first Save As returns to the same editor selection. */
    @Test
    fun preservesEditingAcrossTheFirstSaveAs() {
        val document = TestEditorDocument("initial")
        val source = TestEditorDocumentSource()
        val session =
            EditorSession(
                title = "New document",
                state = EditorDocumentState(document, ImmediateSessionTestDispatcher),
                shouldFocusInitialEditor = true,
                operationDispatcher = ImmediateSessionTestDispatcher,
                editSynchronizationDelay = {}
            )
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        val expectedSelection = TextRange(3)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "edited text")
            selection = expectedSelection
        }
        draft.updateEditorFocusIntent(isFocused = true, canClear = true)

        assertTrue(session.showSaveFormatSelection())
        draft.updateEditorFocusIntent(isFocused = false, canClear = true)
        session.selectSaveFormat(DocumentFormat.PlainText)
        val request = session.claimPreparedSaveDestination()

        assertTrue(session.saveSelectedSource(request, source, "saved.txt"))

        val resumedDraft = requireNotNull(session.activeDraft)
        assertSame(draft, resumedDraft)
        assertEquals(expectedSelection, resumedDraft.textFieldState.selection)
        assertTrue(resumedDraft.shouldRestoreEditorFocus)
        assertEquals(listOf("edited text"), source.savedTexts)
        assertEquals(SourceSaveStatus.Saved, session.sourceSaveStatus)
        session.close()
    }

    /** Verifies a rejected input notice never changes the retained field value. */
    @Test
    fun reportsRejectedInputWithoutChangingTheDraft() {
        val originalText = "alpha"
        val session = createSession(document = TestEditorDocument(originalText))
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        val originalFieldValue = draft.captureFieldValue()

        draft.reportInputRejection(EditorInputRejection.BulkSize)

        assertEquals(EditorInputRejection.BulkSize, draft.inputRejection)
        assertEquals(originalFieldValue, draft.captureFieldValue())

        draft.clearInputRejection()

        assertNull(draft.inputRejection)
        session.close()
    }

    /** Verifies an exact native size rejection never enters autosave or history. */
    @Test
    fun rejectsAnUnsavableDraftWithoutChangingTheSource() {
        val document =
            TestEditorDocument(
                "seed",
                replaceFailure = DocumentSizeLimitException("test rejection")
            )
        val source = TestEditorDocumentSource()
        val session = createSession(document, documentSource = source)
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        val before = draft.captureFieldValue()
        draft.updateEditorFocusIntent(isFocused = true, canClear = true)
        draft.textFieldState.edit {
            append("!")
            selection = TextRange(length)
        }

        session.observeActiveEdit(draft, draft.captureFieldValue())

        assertEquals(before, draft.captureFieldValue())
        assertEquals("seed", document.text)
        assertEquals(EditorInputRejection.DocumentSize, draft.inputRejection)
        assertTrue(draft.shouldRestoreEditorFocus)
        assertFalse(session.hasUnsavedChanges)
        assertFalse(session.canUndo)
        assertTrue(source.savedTexts.isEmpty())
        assertTrue(session.canCloseSafely)
        session.close()
    }

    /** Verifies a delayed rejection cannot roll back a newer field value or composition. */
    @Test
    fun preservesNewerInputWhenARejectedSubmissionFinishes() {
        val session = createSession(TestEditorDocument("seed"))
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit { append("!") }
        val submitted = draft.captureFieldValue()
        draft.textFieldState.edit { append("new") }
        assertFalse(draft.rejectSubmittedChange(submitted))
        assertEquals("seed!new", draft.textFieldState.text.toString())
        val newer = draft.captureFieldValue()
        draft.textFieldState.retainCompositionForTest(TextRange(5, 8))
        assertFalse(draft.rejectSubmittedChange(newer))
        assertEquals("seed!new", draft.textFieldState.text.toString())
        session.close()
    }

    /** Verifies received content stays protected without falsely claiming the user edited it. */
    @Test
    fun distinguishesUntouchedReceivedContentFromEdits() {
        val document = TestEditorDocument("received text")
        val session = EditorSession(
            title = "Shared text.txt",
            state = EditorDocumentState(
                document,
                ImmediateSessionTestDispatcher,
                initialUnsavedContent = true
            ),
            operationDispatcher = ImmediateSessionTestDispatcher,
            editSynchronizationDelay = {}
        )
        session.openInitialEditor()
        assertTrue(session.isUneditedReceivedContent)
        assertTrue(session.hasUnsavedChanges)
        assertEquals(CloseRequestResult.ConfirmationShown, session.requestClose())
        session.dismissDiscardConfirmation()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            append("!")
            selection = TextRange(length)
        }
        assertFalse(session.isUneditedReceivedContent)
        session.observeActiveEdit(draft, draft.captureFieldValue())
        assertFalse(session.isUneditedReceivedContent)
        assertTrue(session.hasUnsavedChanges)
        assertEquals(CloseRequestResult.ConfirmationShown, session.requestClose())
        session.close()
    }

    /** Verifies exact source identity remains bounded by session ownership. */
    @Test
    fun matchesOnlyTheRetainedSourceUriWhileOpen() {
        val source = TestEditorDocumentSource(encodedUri = RETAINED_SOURCE_URI)
        val session =
            createSession(
                document = TestEditorDocument("identity"),
                documentSource = source
            )

        assertTrue(session.matchesDocumentSourceUri(RETAINED_SOURCE_URI))
        assertFalse(session.matchesDocumentSourceUri(OTHER_SOURCE_URI))

        session.close()

        assertFalse(session.matchesDocumentSourceUri(RETAINED_SOURCE_URI))
        assertEquals(1, source.closeCallCount)
    }

    /** Verifies a first destination becomes the source even before any edit. */
    @Test
    fun adoptsFirstDestinationAndSavesItsInitialRevision() {
        val document = TestEditorDocument("initial")
        val source = TestEditorDocumentSource()
        val sourceDisplayName = "Provider notes.txt"
        val session = createSession(document = document)
        session.openInitialEditor()
        session.showSaveFormatSelection()
        session.selectSaveFormat(DocumentFormat.PlainText)
        val request = session.claimPreparedSaveDestination()

        assertTrue(
            session.saveSelectedSource(
                request = request,
                documentSource = source,
                sourceDisplayName = sourceDisplayName
            )
        )

        assertTrue(session.hasDocumentSource)
        assertEquals(sourceDisplayName, session.title)
        assertFalse(session.shouldSelectNewDocumentSource)
        assertEquals(listOf(0L), source.savedRevisions)
        assertEquals(SourceSaveStatus.Saved, session.sourceSaveStatus)
        assertEquals(SaveStatus.Idle, session.saveStatus)
        assertFalse(session.hasUnsavedChanges)

        session.showSaveCopyFormatSelection()
        session.selectSaveFormat(DocumentFormat.Markdown)

        val copyRequest =
            (session.saveStatus as SaveStatus.DestinationReady).request
        assertEquals("Provider notes.md", copyRequest.suggestedName)
        session.close()
        assertEquals(1, source.closeCallCount)
    }

    /** Verifies a failed first source write remains retryable for a clean revision. */
    @Test
    fun retriesFailedInitialSourceSaveWithoutAnEdit() {
        val document = TestEditorDocument("initial")
        val source =
            TestEditorDocumentSource { _, attempt ->
                if (attempt == 1) {
                    throw IllegalStateException("synthetic source failure")
                }
            }
        val session = createSession(document = document)
        session.openInitialEditor()
        session.showSaveFormatSelection()
        session.selectSaveFormat(DocumentFormat.Markdown)
        val request = session.claimPreparedSaveDestination()

        assertTrue(session.saveSelectedSource(request = request, documentSource = source))

        assertTrue(session.sourceSaveStatus is SourceSaveStatus.Failed)
        assertTrue(session.shouldSelectNewDocumentSource)
        assertTrue(session.hasUnsavedChanges)
        assertEquals(listOf(0L), source.savedRevisions)

        session.retrySourceSave()

        assertEquals(listOf(0L, 0L), source.savedRevisions)
        assertEquals(SourceSaveStatus.Saved, session.sourceSaveStatus)
        assertFalse(session.hasUnsavedChanges)
        session.close()
    }

    /** Verifies a valid selection replaces a failed retained source. */
    @Test
    fun replacesFailedSourceWithNextSelectedDestination() {
        val document = TestEditorDocument("initial")
        val failedSource =
            TestEditorDocumentSource { _, _ ->
                throw IllegalStateException("synthetic source failure")
            }
        val replacementSource = TestEditorDocumentSource()
        val session = createSession(document = document)
        session.openInitialEditor()
        session.showSaveFormatSelection()
        session.selectSaveFormat(DocumentFormat.PlainText)
        val failedRequest = session.claimPreparedSaveDestination()
        assertTrue(
            session.saveSelectedSource(
                request = failedRequest,
                documentSource = failedSource
            )
        )
        assertTrue(session.shouldSelectNewDocumentSource)
        assertEquals(0, failedSource.closeCallCount)

        session.showSaveFormatSelection()
        session.selectSaveFormat(DocumentFormat.Markdown)
        val replacementRequest = session.claimPreparedSaveDestination()

        assertTrue(
            session.saveSelectedSource(
                request = replacementRequest,
                documentSource = replacementSource
            )
        )

        assertEquals(1, failedSource.closeCallCount)
        assertEquals(listOf(0L), replacementSource.savedRevisions)
        assertEquals(SourceSaveStatus.Saved, session.sourceSaveStatus)
        assertFalse(session.shouldSelectNewDocumentSource)
        assertFalse(session.hasUnsavedChanges)
        session.close()
        assertEquals(1, failedSource.closeCallCount)
        assertEquals(1, replacementSource.closeCallCount)
    }

    /** Verifies invalid replacement state retains the failed source owner. */
    @Test
    fun retainsFailedSourceWhenReplacementValidationFails() {
        val document = TestEditorDocument("initial")
        val failedSource =
            TestEditorDocumentSource { _, _ ->
                throw IllegalStateException("synthetic source failure")
            }
        val rejectedSource = TestEditorDocumentSource()
        val session = createSession(document = document)
        session.openInitialEditor()
        session.showSaveFormatSelection()
        session.selectSaveFormat(DocumentFormat.PlainText)
        val failedRequest = session.claimPreparedSaveDestination()
        assertTrue(session.saveSelectedSource(failedRequest, failedSource))
        session.showSaveFormatSelection()
        session.selectSaveFormat(DocumentFormat.Markdown)
        val replacementRequest = session.claimPreparedSaveDestination()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "not ready")
        }
        session.updateActiveDraftStatus(draft)

        assertTrue(session.saveSelectedSource(replacementRequest, rejectedSource))

        assertEquals(0, failedSource.closeCallCount)
        assertEquals(1, rejectedSource.closeCallCount)
        assertTrue(session.shouldSelectNewDocumentSource)
        assertTrue(session.sourceSaveStatus is SourceSaveStatus.Failed)
        session.close()
        assertEquals(1, failedSource.closeCallCount)
        assertEquals(1, rejectedSource.closeCallCount)
    }

    /** Verifies one applied edit is saved automatically to its retained source. */
    @Test
    fun autosavesAnAppliedEditToItsRetainedSource() {
        val document = TestEditorDocument("before")
        val source = TestEditorDocumentSource()
        val sourceMetadata =
            SelectedDocumentMetadata(
                displayName = "before.txt",
                mimeType = "text/plain",
                lastModifiedEpochMillis = 1_700_000_000_000L
            )
        val session =
            createSession(
                document = document,
                documentSource = source,
                sourceMetadata = sourceMetadata
            )
        assertEquals(sourceMetadata, session.sourceMetadata)
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "after")
            selection = TextRange(length)
        }

        session.observeActiveEdit(draft, draft.captureFieldValue())

        assertEquals(listOf(1L), source.savedRevisions)
        assertEquals(SourceSaveStatus.Saved, session.sourceSaveStatus)
        assertFalse(session.hasUnsavedChanges)
        assertFalse(session.isSourceSaveBusy)
        assertEquals(
            sourceMetadata.copy(lastModifiedEpochMillis = null),
            session.sourceMetadata
        )
        session.close()
        assertEquals(1, source.closeCallCount)
    }

    /** Verifies a verified reload queues an otherwise unrequested dirty revision. */
    @Test
    fun autosavesAnAppliedRevisionAfterMetricsRecovery() {
        var corruptReplacementMetrics = false
        val document =
            TestEditorDocument("before") { metrics ->
                if (corruptReplacementMetrics) {
                    metrics.copy(byteLength = metrics.byteLength + 1L)
                } else {
                    metrics
                }
            }
        val source = TestEditorDocumentSource()
        val session = createSession(document = document, documentSource = source)
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "after")
            selection = TextRange(length)
        }
        corruptReplacementMetrics = true

        session.observeActiveEdit(draft, draft.captureFieldValue())

        assertEquals(EditorDocumentStatus.Stale, session.state.status)
        assertEquals(SourceSaveStatus.Saved, session.sourceSaveStatus)
        assertEquals(
            UiText.Resource(R.string.operation_reload_to_continue),
            session.saveUnavailableReason
        )
        assertTrue(source.savedRevisions.isEmpty())
        corruptReplacementMetrics = false

        session.reloadStaleActiveEdit(draft)

        assertEquals(EditorDocumentStatus.Ready, session.state.status)
        assertEquals(listOf(1L), source.savedRevisions)
        assertEquals(listOf("after"), source.savedTexts)
        assertNull(session.saveUnavailableReason)
        assertEquals(SourceSaveStatus.Saved, session.sourceSaveStatus)
        assertFalse(session.hasUnsavedChanges)
        session.close()
    }

    /** Verifies an oversized pending source revision becomes a closeable failure. */
    @Test
    fun failsOversizedPendingSourceRevision() {
        val document =
            TestEditorDocument("a") { metrics ->
                if (metrics.revision == 0L) {
                    metrics
                } else {
                    metrics.copy(serializedByteLength = ExportProtocol.MAX_BYTE_LIMIT + 1L)
                }
            }
        val source = TestEditorDocumentSource()
        val session = createSession(document = document, documentSource = source)
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "ab")
            selection = TextRange(length)
        }

        session.observeActiveEdit(draft, draft.captureFieldValue())

        assertEquals(
            SourceSaveStatus.Failed(
                UiText.Resource(R.string.operation_source_save_too_large)
            ),
            session.sourceSaveStatus
        )
        assertTrue(source.savedRevisions.isEmpty())
        assertFalse(session.isSourceSaveBusy)
        assertTrue(session.canCloseSafely)
        assertTrue(session.shouldSelectNewDocumentSource)
        assertTrue(session.hasUnsavedChanges)

        session.retrySourceSave()

        assertTrue(session.sourceSaveStatus is SourceSaveStatus.Failed)
        assertTrue(source.savedRevisions.isEmpty())
        session.close()
    }

    /** Verifies an explicit flush wakes only the pending debounce delay. */
    @Test
    fun flushesGatedDebounceIntoRustAndSourceAutosave() {
        val debounceGate = CompletableDeferred<Unit>()
        val document = TestEditorDocument("before")
        val source = TestEditorDocumentSource()
        val session =
            createSession(
                document = document,
                documentSource = source,
                editSynchronizationDelay = { debounceGate.await() }
            )
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "after")
            selection = TextRange(length)
        }
        session.observeActiveEdit(draft, draft.captureFieldValue())
        assertEquals("before", document.text)
        assertTrue(source.savedRevisions.isEmpty())

        session.flushPendingEdit()

        assertFalse(debounceGate.isCompleted)
        assertEquals("after", document.text)
        assertEquals(listOf(1L), source.savedRevisions)
        assertEquals(SourceSaveStatus.Saved, session.sourceSaveStatus)
        assertFalse(session.hasUnsavedChanges)
        session.close()
    }

    /** Verifies a lifecycle checkpoint saves text still marked as IME composition. */
    @Test
    fun checkpointsComposingTextIntoTheSource() {
        val document = TestEditorDocument("before")
        val source = TestEditorDocumentSource()
        val session = createSession(document = document, documentSource = source)
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "after")
            selection = TextRange(length)
        }
        val composition = TextRange(start = 0, end = 5)
        draft.textFieldState.retainCompositionForTest(composition)
        val composingValue = draft.captureFieldValue()

        session.observeActiveEdit(draft, composingValue)

        assertEquals("before", document.text)
        assertTrue(source.savedTexts.isEmpty())

        session.checkpointPendingEdit()

        assertEquals("after", document.text)
        assertEquals(composition, draft.textFieldState.composition)
        assertEquals(listOf("after"), source.savedTexts)
        assertEquals(SourceSaveStatus.Saved, session.sourceSaveStatus)
        assertFalse(session.hasUnsavedChanges)
        session.close()
    }

    /** Verifies leaving the editor checkpoints composing text before closing it. */
    @Test
    fun checkpointsComposingTextBeforeARequestedClose() {
        val document = TestEditorDocument("before")
        val source = TestEditorDocumentSource()
        val session = createSession(document = document, documentSource = source)
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "after")
            selection = TextRange(length)
        }
        session.observeActiveEdit(
            draft = draft,
            value =
                draft.captureFieldValue().copy(
                    composition = TextRange(start = 0, end = 5)
                )
        )

        assertEquals(CloseRequestResult.CloseNow, session.requestClose())
        assertEquals(listOf("after"), source.savedTexts)
        assertFalse(session.hasUnsavedChanges)
        session.close()
    }

    /** Verifies the first Save tap flushes a dirty draft without waiting for debounce. */
    @Test
    fun flushesLatestEditBeforeShowingSaveFormat() {
        val debounceGate = CompletableDeferred<Unit>()
        val document = TestEditorDocument("before")
        val session =
            createSession(
                document = document,
                editSynchronizationDelay = { debounceGate.await() }
            )
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "after")
            selection = TextRange(length)
        }
        session.observeActiveEdit(draft, draft.captureFieldValue())

        assertTrue(session.showSaveFormatSelection())

        assertFalse(debounceGate.isCompleted)
        assertEquals("after", document.text)
        assertEquals(
            SaveStatus.ChoosingFormat(SaveDestinationPurpose.SourceReplacement),
            session.saveStatus
        )
        session.close()
    }

    /** Verifies the first queued explicit save owns the draft synchronization. */
    @Test
    fun acceptsOnlyTheFirstQueuedExplicitSaveIntent() {
        val document = TestEditorDocument("before")
        val workerDispatcher = QueuedSessionTestDispatcher()
        val state = EditorDocumentState(document, workerDispatcher)
        val session =
            EditorSession(
                title = "Test document",
                state = state,
                operationDispatcher = ImmediateSessionTestDispatcher,
                editSynchronizationDelay = {}
            )
        session.openInitialEditor()
        workerDispatcher.runAll()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "after")
            selection = TextRange(length)
        }

        assertTrue(session.showSaveCopyFormatSelection())
        assertFalse(session.showSaveFormatSelection())
        assertEquals(
            SaveStatus.Queued(
                purpose = SaveDestinationPurpose.Copy,
                draftGeneration = draft.edit.generation
            ),
            session.saveStatus
        )

        workerDispatcher.runAll()

        assertEquals("after", document.text)
        assertEquals(
            SaveStatus.ChoosingFormat(SaveDestinationPurpose.Copy),
            session.saveStatus
        )
        session.close()
    }

    /** Verifies a queued copy waits for an already running source save. */
    @Test
    fun prioritizesExplicitSaveOverNewerSourceAutosave() {
        val firstSaveGate = CompletableDeferred<Unit>()
        val document = TestEditorDocument("a")
        val source =
            TestEditorDocumentSource { _, attempt ->
                if (attempt == 1) {
                    firstSaveGate.await()
                }
            }
        val session = createSession(document = document, documentSource = source)
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "ab")
            selection = TextRange(length)
        }
        session.observeActiveEdit(draft, draft.captureFieldValue())
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "abc")
            selection = TextRange(length)
        }
        session.observeActiveEdit(draft, draft.captureFieldValue())

        assertTrue(session.showSaveCopyFormatSelection())
        assertTrue(session.saveStatus is SaveStatus.Queued)
        firstSaveGate.complete(Unit)

        assertEquals(listOf(1L), source.savedRevisions)
        assertEquals(SourceSaveStatus.Pending, session.sourceSaveStatus)
        assertEquals(
            SaveStatus.ChoosingFormat(SaveDestinationPurpose.Copy),
            session.saveStatus
        )

        session.dismissSaveFormatSelection()

        assertEquals(listOf(1L, 2L), source.savedRevisions)
        assertEquals(listOf("ab", "abc"), source.savedTexts)
        assertEquals(SourceSaveStatus.Saved, session.sourceSaveStatus)
        session.close()
    }

    /** Verifies cancelling a queued save preserves its already requested edit. */
    @Test
    fun cancelsQueuedSaveWithoutCancellingItsEdit() {
        val document = TestEditorDocument("before")
        val workerDispatcher = QueuedSessionTestDispatcher()
        val state = EditorDocumentState(document, workerDispatcher)
        val session =
            EditorSession(
                title = "Test document",
                state = state,
                operationDispatcher = ImmediateSessionTestDispatcher,
                editSynchronizationDelay = {}
            )
        session.openInitialEditor()
        workerDispatcher.runAll()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "after")
            selection = TextRange(length)
        }
        assertTrue(session.showSaveFormatSelection())
        assertTrue(session.saveStatus is SaveStatus.Queued)

        session.cancelSave()
        workerDispatcher.runAll()

        assertEquals("after", document.text)
        assertEquals(SaveStatus.Idle, session.saveStatus)
        assertEquals(1, document.replaceCalls.size)
        session.close()
    }

    /** Verifies a synchronization failure retires its stale queued save. */
    @Test
    fun dropsQueuedSaveWhenEditSynchronizationFails() {
        val document =
            TestEditorDocument(
                initialText = "before",
                replaceFailure = IllegalStateException("synthetic replacement failure")
            )
        val workerDispatcher = QueuedSessionTestDispatcher()
        val state = EditorDocumentState(document, workerDispatcher)
        val session =
            EditorSession(
                title = "Test document",
                state = state,
                operationDispatcher = ImmediateSessionTestDispatcher,
                editSynchronizationDelay = {}
            )
        session.openInitialEditor()
        workerDispatcher.runAll()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "after")
            selection = TextRange(length)
        }
        assertTrue(session.showSaveFormatSelection())

        workerDispatcher.runAll()

        assertEquals(SaveStatus.Idle, session.saveStatus)
        assertEquals(UiText.Resource(R.string.operation_apply_edit_failed), state.editorMessage)
        assertTrue(draft.hasChanges)
        assertTrue(document.replaceCalls.isEmpty())
        session.close()
    }

    /** Verifies an active source write finishes before the newest revision starts. */
    @Test
    fun conflatesSourceSavesWithoutCancellingActiveOutput() {
        val firstSaveGate = CompletableDeferred<Unit>()
        val document = TestEditorDocument("a")
        val source =
            TestEditorDocumentSource { _, attempt ->
                if (attempt == 1) {
                    firstSaveGate.await()
                }
            }
        val session = createSession(document = document, documentSource = source)
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "ab")
            selection = TextRange(length)
        }
        session.observeActiveEdit(draft, draft.captureFieldValue())

        assertEquals(listOf(1L), source.savedRevisions)
        assertEquals(SourceSaveStatus.Saving, session.sourceSaveStatus)

        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "abc")
            selection = TextRange(length)
        }
        session.observeActiveEdit(draft, draft.captureFieldValue())
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "abcd")
            selection = TextRange(length)
        }
        session.observeActiveEdit(draft, draft.captureFieldValue())
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "abcde")
            selection = TextRange(length)
        }
        session.observeActiveEdit(draft, draft.captureFieldValue())

        assertEquals(listOf(1L), source.savedRevisions)
        assertEquals(SourceSaveStatus.Saving, session.sourceSaveStatus)
        firstSaveGate.complete(Unit)

        assertEquals(listOf(1L, 4L), source.savedRevisions)
        assertEquals(listOf("ab", "abcde"), source.savedTexts)
        assertEquals(SourceSaveStatus.Saved, session.sourceSaveStatus)
        assertFalse(session.hasUnsavedChanges)
        session.close()
    }

    /** Verifies queued closing saves the newest draft without requiring another attempt. */
    @Test
    fun finishesQueuedCloseAfterAnActiveSourceSave() {
        val firstSaveGate = CompletableDeferred<Unit>()
        val document = TestEditorDocument("a")
        val source =
            TestEditorDocumentSource { _, attempt ->
                if (attempt == 1) {
                    firstSaveGate.await()
                }
            }
        val session = createSession(document = document, documentSource = source)
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "ab")
            selection = TextRange(length)
        }
        session.observeActiveEdit(draft, draft.captureFieldValue())
        assertEquals(SourceSaveStatus.Saving, session.sourceSaveStatus)

        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "abc")
            selection = TextRange(length)
        }
        val initialResolutionVersion = session.closeResolutionVersion

        assertEquals(CloseRequestResult.Queued, session.requestClose())
        assertTrue(session.isClosePending)
        assertFalse(session.canCloseSafely)

        firstSaveGate.complete(Unit)

        assertEquals(listOf("ab", "abc"), source.savedTexts)
        assertEquals(SourceSaveStatus.Saved, session.sourceSaveStatus)
        assertFalse(session.hasUnsavedChanges)
        assertTrue(session.closeResolutionVersion > initialResolutionVersion)
        assertEquals(CloseRequestResult.CloseNow, session.resolvePendingClose())
        session.close()
    }

    /** Verifies failure latching discards queued intermediate source revisions. */
    @Test
    fun retriesOnlyNewestRevisionAfterActiveSourceSaveFails() {
        val firstSaveGate = CompletableDeferred<Unit>()
        val document = TestEditorDocument("a")
        val source =
            TestEditorDocumentSource { _, attempt ->
                if (attempt == 1) {
                    firstSaveGate.await()
                    throw IllegalStateException("synthetic source failure")
                }
            }
        val session = createSession(document = document, documentSource = source)
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "ab")
            selection = TextRange(length)
        }
        session.observeActiveEdit(draft, draft.captureFieldValue())
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "abc")
            selection = TextRange(length)
        }
        session.observeActiveEdit(draft, draft.captureFieldValue())
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "abcd")
            selection = TextRange(length)
        }
        session.observeActiveEdit(draft, draft.captureFieldValue())

        firstSaveGate.complete(Unit)

        assertTrue(session.sourceSaveStatus is SourceSaveStatus.Failed)
        assertEquals(listOf(1L), source.savedRevisions)
        assertTrue(session.hasUnsavedChanges)

        session.retrySourceSave()

        assertEquals(listOf(1L, 3L), source.savedRevisions)
        assertEquals(listOf("ab", "abcd"), source.savedTexts)
        assertEquals(SourceSaveStatus.Saved, session.sourceSaveStatus)
        session.close()
    }

    /** Verifies source ownership outlives cancellation of its active write. */
    @Test
    fun closesSourceOnlyAfterActiveWriteSettles() {
        val saveGate = CompletableDeferred<Unit>()
        val document = TestEditorDocument("a")
        val source =
            TestEditorDocumentSource { _, _ ->
                withContext(NonCancellable) {
                    saveGate.await()
                }
            }
        val session = createSession(document = document, documentSource = source)
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "ab")
            selection = TextRange(length)
        }
        session.observeActiveEdit(draft, draft.captureFieldValue())

        session.close()

        assertEquals(0, source.closeCallCount)
        assertEquals(1, document.closeCallCount)

        saveGate.complete(Unit)

        assertEquals(1, source.closeCallCount)
        assertEquals(SourceSaveStatus.NoSource, session.sourceSaveStatus)
    }

    /** Verifies format selection and source output never own the save slot together. */
    @Test
    fun defersSourceSaveUntilFormatSelectionEnds() {
        val document = TestEditorDocument("a")
        val source = TestEditorDocumentSource()
        val session = createSession(document = document, documentSource = source)
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        session.showSaveFormatSelection()
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "ab")
            selection = TextRange(length)
        }

        session.observeActiveEdit(draft, draft.captureFieldValue())

        assertEquals(
            SaveStatus.ChoosingFormat(SaveDestinationPurpose.Copy),
            session.saveStatus
        )
        assertEquals(SourceSaveStatus.Pending, session.sourceSaveStatus)
        assertTrue(session.canChooseSaveFormat)
        assertTrue(source.savedRevisions.isEmpty())

        session.dismissSaveFormatSelection()

        assertEquals(listOf(1L), source.savedRevisions)
        assertEquals(SourceSaveStatus.Saved, session.sourceSaveStatus)
        session.close()
    }

    /** Verifies a source failure stays latched until explicit retry. */
    @Test
    fun latchesSourceFailureUntilRetry() {
        val document = TestEditorDocument("a")
        val source =
            TestEditorDocumentSource { _, attempt ->
                if (attempt == 1) {
                    throw IllegalStateException("synthetic source failure")
                }
            }
        val session = createSession(document = document, documentSource = source)
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "ab")
            selection = TextRange(length)
        }
        session.observeActiveEdit(draft, draft.captureFieldValue())

        assertTrue(session.sourceSaveStatus is SourceSaveStatus.Failed)
        assertEquals(listOf(1L), source.savedRevisions)

        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "abc")
            selection = TextRange(length)
        }
        session.observeActiveEdit(draft, draft.captureFieldValue())

        assertEquals(listOf(1L), source.savedRevisions)
        assertTrue(session.sourceSaveStatus is SourceSaveStatus.Failed)
        assertTrue(session.hasUnsavedChanges)

        session.retrySourceSave()

        assertEquals(listOf(1L, 2L), source.savedRevisions)
        assertEquals(SourceSaveStatus.Saved, session.sourceSaveStatus)
        assertFalse(session.hasUnsavedChanges)
        session.close()
    }

    /** Verifies an external source conflict never exposes ordinary Retry. */
    @Test
    fun latchesSourceConflictUntilAnotherDestinationIsSelected() {
        val document = TestEditorDocument("a")
        val source =
            TestEditorDocumentSource { _, _ ->
                throw DocumentExportException(DocumentExportFailure.SOURCE_CONFLICT)
            }
        val session = createSession(document = document, documentSource = source)
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "changed")
            selection = TextRange(length)
        }

        session.observeActiveEdit(draft, draft.captureFieldValue())

        assertEquals(
            SourceSaveStatus.Conflict(DocumentExportFailure.SOURCE_CONFLICT.userMessage),
            session.sourceSaveStatus
        )
        assertTrue(session.shouldSelectNewDocumentSource)
        assertTrue(session.hasUnsavedChanges)
        assertTrue(session.canCloseSafely)
        assertEquals(listOf(1L), source.savedRevisions)

        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "newest")
            selection = TextRange(length)
        }
        session.observeActiveEdit(draft, draft.captureFieldValue())
        session.retrySourceSave()

        assertEquals(listOf(1L), source.savedRevisions)
        assertTrue(session.sourceSaveStatus is SourceSaveStatus.Conflict)
        session.close()
    }

    /** Verifies confirmed overwrite writes the newest settled local revision. */
    @Test
    fun overwritesAConflictedSourceOnlyAfterConfirmation() {
        val document = TestEditorDocument("a")
        val source =
            TestConflictRecoverableDocumentSource(
                save = { _, _ ->
                    throw DocumentExportException(DocumentExportFailure.SOURCE_CONFLICT)
                }
            )
        val session = createSession(document = document, documentSource = source)
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "first local edit")
            selection = TextRange(length)
        }
        session.observeActiveEdit(draft, draft.captureFieldValue())
        assertTrue(session.sourceSaveStatus is SourceSaveStatus.Conflict)

        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "newest local edit")
            selection = TextRange(length)
        }

        assertTrue(session.canRequestSourceOverwrite)
        assertTrue(session.requestSourceOverwriteConfirmation())
        assertEquals(SourceConflictResolution.Overwrite, session.sourceConflictResolution)
        assertTrue(session.confirmSourceOverwrite())

        assertEquals(listOf("first local edit"), source.savedTexts)
        assertEquals(listOf("newest local edit"), source.overwrittenTexts)
        assertEquals(SourceSaveStatus.Saved, session.sourceSaveStatus)
        assertNull(session.sourceConflictResolution)
        assertFalse(session.hasUnsavedChanges)
        session.close()
    }

    /** Verifies a second external change returns confirmed overwrite to conflict recovery. */
    @Test
    fun relatchesConflictWhenTheSourceChangesDuringOverwrite() {
        val source =
            TestConflictRecoverableDocumentSource(
                save = { _, _ ->
                    throw DocumentExportException(DocumentExportFailure.SOURCE_CONFLICT)
                },
                overwrite = { _, _ ->
                    throw DocumentExportException(DocumentExportFailure.SOURCE_CONFLICT)
                }
            )
        val session = createSession(document = TestEditorDocument("a"), documentSource = source)
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "local edit")
            selection = TextRange(length)
        }
        session.observeActiveEdit(draft, draft.captureFieldValue())

        assertTrue(session.requestSourceOverwriteConfirmation())
        assertTrue(session.confirmSourceOverwrite())

        assertEquals(listOf("local edit"), source.overwrittenTexts)
        assertTrue(session.sourceSaveStatus is SourceSaveStatus.Conflict)
        assertTrue(session.canRequestSourceOverwrite)
        assertTrue(session.hasUnsavedChanges)
        session.close()
    }

    /** Verifies Retry repeats safe overwrite preflight after a transient failure. */
    @Test
    fun retriesTheConfirmedOverwritePathAfterTransientFailure() {
        val source =
            TestConflictRecoverableDocumentSource(
                save = { _, _ ->
                    throw DocumentExportException(DocumentExportFailure.SOURCE_CONFLICT)
                },
                overwrite = { _, attempt ->
                    if (attempt == 1) {
                        throw IllegalStateException("synthetic overwrite failure")
                    }
                }
            )
        val session = createSession(document = TestEditorDocument("a"), documentSource = source)
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "local edit")
            selection = TextRange(length)
        }
        session.observeActiveEdit(draft, draft.captureFieldValue())
        assertTrue(session.requestSourceOverwriteConfirmation())

        assertTrue(session.confirmSourceOverwrite())

        assertTrue(session.sourceSaveStatus is SourceSaveStatus.Failed)
        assertEquals(listOf("local edit"), source.savedTexts)
        assertEquals(listOf("local edit"), source.overwrittenTexts)

        session.retrySourceSave()

        assertEquals(listOf("local edit"), source.savedTexts)
        assertEquals(listOf("local edit", "local edit"), source.overwrittenTexts)
        assertEquals(SourceSaveStatus.Saved, session.sourceSaveStatus)
        session.close()
    }

    /** Verifies reload claims the source once and restores recovery after failure. */
    @Test
    fun locksEditingDuringConfirmedSourceReload() {
        val source =
            TestConflictRecoverableDocumentSource(
                save = { _, _ ->
                    throw DocumentExportException(DocumentExportFailure.SOURCE_CONFLICT)
                }
            )
        val session = createSession(document = TestEditorDocument("a"), documentSource = source)
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "local edit")
            selection = TextRange(length)
        }
        session.observeActiveEdit(draft, draft.captureFieldValue())

        assertTrue(session.requestSourceReloadConfirmation())
        assertEquals(SourceConflictResolution.Reload, session.sourceConflictResolution)
        assertEquals(RETAINED_SOURCE_URI, session.beginSourceReload())
        assertEquals(SourceSaveStatus.Reloading, session.sourceSaveStatus)
        assertTrue(session.isSourceReloading)
        assertFalse(session.canAcceptEditorInput)
        assertNull(session.beginSourceReload())

        session.failSourceReload(UiText.Literal("The latest source could not be opened"))

        assertEquals(
            SourceSaveStatus.Conflict(UiText.Literal("The latest source could not be opened")),
            session.sourceSaveStatus
        )
        assertFalse(session.isSourceReloading)
        assertTrue(session.canAcceptEditorInput)
        assertTrue(session.canRequestSourceReload)
        session.close()
    }

    /** Verifies an uncertain source write never becomes an unconditional retry. */
    @Test
    fun latchesSourceUncertaintyUntilAnotherDestinationIsSelected() {
        val document = TestEditorDocument("a")
        val source =
            TestEditorDocumentSource { _, _ ->
                throw DocumentExportException(DocumentExportFailure.SOURCE_UNCERTAIN)
            }
        val session = createSession(document = document, documentSource = source)
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "changed")
            selection = TextRange(length)
        }

        session.observeActiveEdit(draft, draft.captureFieldValue())

        assertEquals(
            SourceSaveStatus.Uncertain(DocumentExportFailure.SOURCE_UNCERTAIN.userMessage),
            session.sourceSaveStatus
        )
        assertTrue(session.shouldSelectNewDocumentSource)
        assertTrue(session.hasUnsavedChanges)
        assertTrue(session.canCloseSafely)
        assertEquals(listOf(1L), source.savedRevisions)

        session.retrySourceSave()

        assertEquals(listOf(1L), source.savedRevisions)
        assertTrue(session.sourceSaveStatus is SourceSaveStatus.Uncertain)
        session.close()
    }

    /** Verifies conflict recovery replaces the source with the newest revision. */
    @Test
    fun replacesConflictedSourceWithSelectedDestination() {
        verifyTerminalSourceReplacement(DocumentExportFailure.SOURCE_CONFLICT)
    }

    /** Verifies uncertain-write recovery replaces the source with the newest revision. */
    @Test
    fun replacesUncertainSourceWithSelectedDestination() {
        verifyTerminalSourceReplacement(DocumentExportFailure.SOURCE_UNCERTAIN)
    }

    /** Verifies a rescue copy does not clear an external source conflict. */
    @Test
    fun savesCopyWithoutClearingSourceConflict() {
        verifyTerminalSourceCopy(DocumentExportFailure.SOURCE_CONFLICT)
    }

    /** Verifies a rescue copy does not clear source-save uncertainty. */
    @Test
    fun savesCopyWithoutClearingSourceUncertainty() {
        verifyTerminalSourceCopy(DocumentExportFailure.SOURCE_UNCERTAIN)
    }

    /** Verifies an in-flight picker retains its original source-replacement intent. */
    @Test
    fun retainsSourceReplacementIntentWhileRetryBecomesPending() {
        val document = TestEditorDocument("a")
        val failedSource =
            TestEditorDocumentSource { _, _ ->
                throw IllegalStateException("synthetic source failure")
            }
        val replacementSource = TestEditorDocumentSource()
        val session = createSession(document = document, documentSource = failedSource)
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "changed")
            selection = TextRange(length)
        }
        session.observeActiveEdit(draft, draft.captureFieldValue())
        session.showSaveFormatSelection()
        session.selectSaveFormat(DocumentFormat.Markdown)
        val request = session.claimPreparedSaveDestination()
        assertEquals(SaveDestinationPurpose.SourceReplacement, request.purpose)

        session.retrySourceSave()

        assertEquals(SourceSaveStatus.Pending, session.sourceSaveStatus)
        assertFalse(session.shouldSelectNewDocumentSource)
        assertTrue(session.saveSelectedSource(request, replacementSource))
        assertEquals(1, failedSource.closeCallCount)
        assertEquals(listOf("changed"), replacementSource.savedTexts)
        assertEquals(SourceSaveStatus.Saved, session.sourceSaveStatus)
        session.close()
        assertEquals(1, failedSource.closeCallCount)
        assertEquals(1, replacementSource.closeCallCount)
    }

    /** Verifies a complete copy is not mislabeled when the source remains dirty. */
    @Test
    fun reportsCurrentCopyCompleteAfterSourceFailure() {
        val document = TestEditorDocument("a")
        val source =
            TestEditorDocumentSource { _, _ ->
                throw IllegalStateException("synthetic source failure")
            }
        val session = createSession(document = document, documentSource = source)
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "current")
            selection = TextRange(length)
        }
        session.observeActiveEdit(draft, draft.captureFieldValue())
        assertTrue(session.sourceSaveStatus is SourceSaveStatus.Failed)
        session.showSaveCopyFormatSelection()
        session.selectSaveFormat(DocumentFormat.PlainText)
        val request = session.claimPreparedSaveDestination()
        val destinationOwner = TestDestinationOwner()
        var copiedText: String? = null

        assertTrue(
            session.saveSelectedDestination(
                request = request,
                destinationOwner = destinationOwner
            ) { snapshot, _ ->
                val testSnapshot = snapshot as TestEditorDocumentSnapshot
                copiedText = testSnapshot.text
                testSnapshot.consume()
            }
        )

        assertEquals("current", copiedText)
        assertEquals(
            SaveStatus.Succeeded(request = request, hasNewerChanges = false),
            session.saveStatus
        )
        assertTrue(session.sourceSaveStatus is SourceSaveStatus.Failed)
        assertTrue(session.hasUnsavedChanges)
        assertEquals(1, destinationOwner.closeCallCount)
        session.close()
    }

    /** Verifies one terminal source state is recoverable only through replacement. */
    private fun verifyTerminalSourceReplacement(failure: DocumentExportFailure) {
        require(
            failure == DocumentExportFailure.SOURCE_CONFLICT ||
                failure == DocumentExportFailure.SOURCE_UNCERTAIN
        ) {
            "terminal source replacement requires conflict or uncertainty"
        }
        val document = TestEditorDocument("a")
        val failedSource =
            TestEditorDocumentSource { _, _ ->
                throw DocumentExportException(failure)
            }
        val replacementSource = TestEditorDocumentSource()
        val session = createSession(document = document, documentSource = failedSource)
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "changed")
            selection = TextRange(length)
        }
        session.observeActiveEdit(draft, draft.captureFieldValue())
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "newest")
            selection = TextRange(length)
        }
        session.observeActiveEdit(draft, draft.captureFieldValue())
        assertEquals(listOf(1L), failedSource.savedRevisions)

        session.showSaveFormatSelection()
        assertEquals(
            SaveStatus.ChoosingFormat(SaveDestinationPurpose.SourceReplacement),
            session.saveStatus
        )
        session.selectSaveFormat(DocumentFormat.PlainText)
        val request = session.claimPreparedSaveDestination()
        assertTrue(session.saveSelectedSource(request, replacementSource))

        assertEquals(1, failedSource.closeCallCount)
        assertEquals(listOf(2L), replacementSource.savedRevisions)
        assertEquals(listOf("newest"), replacementSource.savedTexts)
        assertEquals(SourceSaveStatus.Saved, session.sourceSaveStatus)
        assertFalse(session.hasUnsavedChanges)
        session.close()
        assertEquals(1, failedSource.closeCallCount)
        assertEquals(1, replacementSource.closeCallCount)
    }

    /** Verifies one terminal initial save never unlocks a view-only document. */
    private fun verifyViewOnlyInitialTerminalSave(failure: DocumentExportFailure) {
        require(
            failure == DocumentExportFailure.SOURCE_CONFLICT ||
                failure == DocumentExportFailure.SOURCE_UNCERTAIN
        ) {
            "view-only initial save requires conflict or uncertainty"
        }
        val readOnlySource = TestReadOnlyEditorDocumentSource()
        val writableSource =
            TestEditorDocumentSource { _, _ ->
                throw DocumentExportException(failure)
            }
        val session =
            EditorSession(
                title = "Read-only document",
                state =
                    EditorDocumentState(
                        TestEditorDocument("read only"),
                        ImmediateSessionTestDispatcher
                    ),
                documentSource = readOnlySource,
                operationDispatcher = ImmediateSessionTestDispatcher,
                editSynchronizationDelay = {}
            )
        session.openInitialEditor()
        assertTrue(session.showSaveFormatSelection())
        session.selectSaveFormat(DocumentFormat.PlainText)
        val request = session.claimPreparedSaveDestination()

        assertTrue(session.saveSelectedSource(request, writableSource))

        assertTrue(session.isViewOnly)
        assertTrue(session.shouldSelectNewDocumentSource)
        assertTrue(session.canStartSaveAs)
        assertFalse(session.hasUnsavedChanges)
        assertEquals(1, readOnlySource.closeCallCount)
        assertEquals(1, writableSource.savedRevisions.size)
        when (failure) {
            DocumentExportFailure.SOURCE_CONFLICT ->
                assertTrue(session.sourceSaveStatus is SourceSaveStatus.Conflict)

            DocumentExportFailure.SOURCE_UNCERTAIN ->
                assertTrue(session.sourceSaveStatus is SourceSaveStatus.Uncertain)
        }

        session.retrySourceSave()

        assertEquals(1, writableSource.savedRevisions.size)
        assertTrue(session.isViewOnly)
        session.close()
        assertEquals(1, writableSource.closeCallCount)
    }

    /** Verifies one terminal source remains retained after a complete rescue copy. */
    private fun verifyTerminalSourceCopy(failure: DocumentExportFailure) {
        require(
            failure == DocumentExportFailure.SOURCE_CONFLICT ||
                failure == DocumentExportFailure.SOURCE_UNCERTAIN
        ) {
            "terminal source copy requires conflict or uncertainty"
        }
        val document = TestEditorDocument("a")
        val failedSource =
            TestEditorDocumentSource { _, _ ->
                throw DocumentExportException(failure)
            }
        val session = createSession(document = document, documentSource = failedSource)
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "rescue")
            selection = TextRange(length)
        }
        session.observeActiveEdit(draft, draft.captureFieldValue())
        val terminalStatus = session.sourceSaveStatus
        session.showSaveCopyFormatSelection()
        session.selectSaveFormat(DocumentFormat.PlainText)
        val request = session.claimPreparedSaveDestination()
        val destinationOwner = TestDestinationOwner()
        var copiedText: String? = null

        assertTrue(
            session.saveSelectedDestination(request, destinationOwner) { snapshot, _ ->
                val testSnapshot = snapshot as TestEditorDocumentSnapshot
                copiedText = testSnapshot.text
                testSnapshot.consume()
            }
        )

        assertEquals("rescue", copiedText)
        assertEquals(terminalStatus, session.sourceSaveStatus)
        assertTrue(session.shouldSelectNewDocumentSource)
        assertTrue(session.hasUnsavedChanges)
        assertEquals(0, failedSource.closeCallCount)
        assertEquals(1, destinationOwner.closeCallCount)
        session.close()
        assertEquals(1, failedSource.closeCallCount)
    }

    /** Verifies serialized output size controls Save As availability. */
    @Test
    fun rejectsSaveAsWhenSerializedOutputExceedsItsLimit() {
        val document =
            TestEditorDocument("small") { metrics ->
                metrics.copy(
                    serializedByteLength = ExportProtocol.MAX_BYTE_LIMIT + 1L
                )
            }
        val session = createSession(document)

        session.openInitialEditor()

        assertFalse(session.canStartSaveAs)
        assertEquals(
            UiText.Resource(R.string.operation_save_document_too_large),
            session.saveUnavailableReason
        )
        session.close()
    }

    /** Verifies one generation retains its draft object, field text, and selection. */
    @Test
    fun retainsDraftIdentityTextAndSelectionForOneGeneration() {
        val document = TestEditorDocument("first\nsecond")
        val session = createSession(document)
        session.openInitialEditor()
        val initialDraft = requireNotNull(session.activeDraft)
        val initialFieldState = initialDraft.textFieldState

        initialFieldState.edit {
            replace(start = 0, end = length, text = RETAINED_DRAFT_TEXT)
            selection =
                TextRange(
                    start = RETAINED_SELECTION_START,
                    end = RETAINED_SELECTION_END
                )
        }
        session.updateActiveDraftStatus(initialDraft)

        val retainedDraft = requireNotNull(session.activeDraft)
        assertSame(initialDraft, retainedDraft)
        assertSame(initialFieldState, retainedDraft.textFieldState)
        assertEquals(RETAINED_DRAFT_TEXT, retainedDraft.textFieldState.text.toString())
        assertEquals(
            TextRange(
                start = RETAINED_SELECTION_START,
                end = RETAINED_SELECTION_END
            ),
            retainedDraft.textFieldState.selection
        )
        assertEquals(FIRST_EDIT_GENERATION, retainedDraft.edit.generation)
        assertTrue(retainedDraft.hasChanges)
        assertTrue(session.state.hasActiveDraftChanges)
        assertTrue(session.hasUnsavedChanges)
        assertTrue(session.canStartSaveAs)
        assertNull(session.saveUnavailableReason)
        assertTrue(document.replaceCalls.isEmpty())

        session.close()
    }

    /** Verifies automatic synchronization retains the field and its generation. */
    @Test
    fun retainsDraftAfterAutomaticSynchronization() {
        val document = TestEditorDocument("before\nsecond")
        val session = createSession(document)
        session.openInitialEditor()
        val initialDraft = requireNotNull(session.activeDraft)

        initialDraft.textFieldState.edit {
            replace(start = 0, end = length, text = RETAINED_DRAFT_TEXT)
            placeCursorAfterCharAt(RETAINED_SELECTION_END - 1)
        }
        session.observeActiveEdit(initialDraft, initialDraft.captureFieldValue())

        val refreshedDraft = requireNotNull(session.activeDraft)
        assertSame(initialDraft, refreshedDraft)
        assertEquals(FIRST_EDIT_GENERATION, refreshedDraft.edit.generation)
        assertEquals(RETAINED_DRAFT_TEXT, refreshedDraft.textFieldState.text.toString())
        assertEquals(TextRange(RETAINED_SELECTION_END), refreshedDraft.textFieldState.selection)
        assertFalse(refreshedDraft.hasChanges)
        assertEquals(EditorDocumentStatus.Ready, session.state.status)
        assertTrue(session.state.hasDocumentChanges)
        assertFalse(session.state.hasActiveDraftChanges)
        assertTrue(session.hasUnsavedChanges)
        assertEquals(1, document.replaceCalls.size)

        session.close()
    }

    /** Verifies cursor movement cannot cancel a pending text synchronization. */
    @Test
    fun commitsLatestTextAfterCursorMovement() {
        val document = TestEditorDocument("before")
        val synchronizationDelay = CompletableDeferred<Unit>()
        val session =
            createSession(document) {
                synchronizationDelay.await()
            }
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        val textFieldState = draft.textFieldState

        textFieldState.edit {
            replace(start = 0, end = length, text = "after")
            selection = TextRange(length)
        }
        session.observeActiveEdit(draft, draft.captureFieldValue())
        textFieldState.edit {
            selection = TextRange(0)
        }
        session.observeActiveEdit(draft, draft.captureFieldValue())

        assertTrue(draft.hasChanges)
        assertTrue(document.replaceCalls.isEmpty())
        synchronizationDelay.complete(Unit)

        val synchronizedDraft = requireNotNull(session.activeDraft)
        assertSame(draft, synchronizedDraft)
        assertSame(textFieldState, synchronizedDraft.textFieldState)
        assertEquals("after", document.text)
        assertEquals("after", synchronizedDraft.textFieldState.text.toString())
        assertEquals(TextRange(0), synchronizedDraft.textFieldState.selection)
        assertFalse(synchronizedDraft.hasChanges)
        assertEquals(1, document.replaceCalls.size)

        textFieldState.edit {
            selection = TextRange(length)
        }
        session.observeActiveEdit(draft, draft.captureFieldValue())
        assertEquals(1, document.replaceCalls.size)

        session.close()
    }

    /** Verifies edits made during one Rust mutation are conflated to the latest text. */
    @Test
    fun coalescesRapidFieldChangesAroundAnInFlightCommit() {
        val document = TestEditorDocument("a")
        val workerDispatcher = QueuedSessionTestDispatcher()
        val state = EditorDocumentState(document, workerDispatcher)
        val session =
            EditorSession(
                title = "Test document",
                state = state,
                operationDispatcher = ImmediateSessionTestDispatcher,
                editSynchronizationDelay = {}
            )
        session.openInitialEditor()
        workerDispatcher.runAll()
        val draft = requireNotNull(session.activeDraft)
        val textFieldState = draft.textFieldState

        textFieldState.edit {
            replace(start = 0, end = length, text = "ab")
        }
        session.observeActiveEdit(draft, draft.captureFieldValue())
        assertEquals(EditorDocumentStatus.ApplyingEdit, state.status)
        assertTrue(state.canAcceptActiveDraftInput(draft.edit.generation))

        textFieldState.edit {
            replace(start = 0, end = length, text = "abc")
        }
        session.observeActiveEdit(draft, draft.captureFieldValue())
        textFieldState.edit {
            replace(start = 0, end = length, text = "abcd")
            selection = TextRange(length)
        }
        session.observeActiveEdit(draft, draft.captureFieldValue())

        workerDispatcher.runAll()

        assertSame(draft, session.activeDraft)
        assertSame(textFieldState, requireNotNull(session.activeDraft).textFieldState)
        assertEquals("abcd", document.text)
        assertEquals("abcd", draft.edit.snapshot.text)
        assertEquals(TextRange(4), textFieldState.selection)
        assertFalse(draft.hasChanges)
        assertEquals(2, document.replaceCalls.size)
        assertEquals(listOf(0L, 1L), document.replaceCalls.map { call -> call.expectedRevision })

        session.close()
    }

    /** Verifies a large writable document starts in one bounded editor window. */
    @Test
    fun opensLargeWritableDocumentInABoundedEditor() {
        val document =
            TestEditorDocument(
                initialText = BOUNDED_DOCUMENT_TEXT,
                editWindowUtf16Units = BOUNDED_EDIT_WINDOW_UTF16_UNITS
            )
        val session = createSession(document = document)

        session.openInitialEditor()

        val draft = requireNotNull(session.activeDraft)
        assertEquals(
            Utf16Range(start = 0L, end = BOUNDED_EDIT_WINDOW_UTF16_UNITS.toLong()),
            draft.edit.snapshot.range
        )
        assertTrue(draft.edit.snapshot.hasNext)
        assertFalse(draft.shouldRestoreEditorFocus)
        assertEquals(listOf(Utf16Range(start = 0L, end = 0L)), document.editWindowCalls)
        session.close()
    }

    /** Verifies the first queued section action owns one edit and one navigation. */
    @Test
    fun acceptsOnlyTheFirstQueuedEditWindowAction() {
        val document =
            TestEditorDocument(
                initialText = BOUNDED_DOCUMENT_TEXT,
                editWindowUtf16Units = BOUNDED_EDIT_WINDOW_UTF16_UNITS
            )
        val workerDispatcher = QueuedSessionTestDispatcher()
        val state = EditorDocumentState(document, workerDispatcher)
        val session =
            EditorSession(
                title = "Test document",
                state = state,
                operationDispatcher = ImmediateSessionTestDispatcher,
                editSynchronizationDelay = {}
            )
        session.openInitialEditor()
        workerDispatcher.runAll()
        val initialDraft = requireNotNull(session.activeDraft)
        initialDraft.textFieldState.edit {
            replace(start = 0, end = length, text = REPLACEMENT_BOUNDED_EDIT_TEXT)
            selection = TextRange(length)
        }

        assertTrue(
            session.requestEditWindowAction(
                draft = initialDraft,
                action = EditWindowAction.Later
            )
        )
        assertFalse(
            session.requestEditWindowAction(
                draft = initialDraft,
                action = EditWindowAction.OpenFind
            )
        )
        assertTrue(session.hasPendingEditWindowAction)
        assertTrue(session.canCancelPendingEditWindowAction)

        workerDispatcher.runAll()

        val movedDraft = requireNotNull(session.activeDraft)
        assertFalse(initialDraft === movedDraft)
        assertEquals(1, document.replaceCalls.size)
        assertEquals(2, document.editWindowCalls.size)
        assertEquals(
            Utf16Range(
                start = BOUNDED_EDIT_WINDOW_UTF16_UNITS.toLong(),
                end = BOUNDED_EDIT_WINDOW_UTF16_UNITS.toLong()
            ),
            document.editWindowCalls.last()
        )
        assertFalse(session.hasPendingEditWindowAction)
        session.close()
    }

    /** Verifies automatic handoff retains the global caret and visual scroll anchor. */
    @Test
    fun preservesCaretAndScrollAnchorAcrossAutomaticEditWindows() {
        val document =
            TestEditorDocument(
                initialText = BOUNDED_DOCUMENT_TEXT,
                editWindowUtf16Units = BOUNDED_EDIT_WINDOW_UTF16_UNITS
            )
        val session = createSession(document)
        session.openInitialEditor()
        val initialDraft = requireNotNull(session.activeDraft)
        initialDraft.textFieldState.edit {
            selection = TextRange(3)
        }
        initialDraft.updateEditorFocusIntent(isFocused = true, canClear = true)

        assertTrue(
            session.requestAutomaticEditWindowTransition(
                draft = initialDraft,
                towardNext = true,
                localAnchorUtf16Offset = 3,
                anchorViewportTopPixels = 120
            )
        )

        val movedDraft = requireNotNull(session.activeDraft)
        assertSame(initialDraft, movedDraft)
        assertSame(initialDraft.textFieldState, movedDraft.textFieldState)
        assertSame(initialDraft.scrollState, movedDraft.scrollState)
        assertEquals(Utf16Range(start = 1, end = 5), movedDraft.edit.snapshot.range)
        assertEquals(Utf16Range(start = 3, end = 3), movedDraft.edit.snapshot.selection)
        assertEquals(TextRange(2), movedDraft.textFieldState.selection)
        assertEquals(
            EditWindowScrollRestoration(
                anchor =
                    SemanticViewportAnchor(
                        revision = 0L,
                        sourceUtf16Offset = 3,
                        viewportTopOffsetPixels = 120
                    ),
                preserveScrollMomentum = true
            ),
            movedDraft.scrollRestoration
        )
        assertTrue(requireNotNull(movedDraft.scrollRestoration).preserveScrollMomentum)
        assertTrue(movedDraft.shouldRestoreEditorFocus)
        assertEquals(Utf16Range(start = 3, end = 3), document.editWindowCalls.last())
        assertFalse(session.hasPendingEditWindowAction)
        session.close()
    }

    /** Verifies repeated automatic handoffs retain one Compose interaction identity. */
    @Test
    fun retainsComposeStateAcrossRepeatedAutomaticEditWindows() {
        val document =
            TestEditorDocument(
                initialText = BOUNDED_DOCUMENT_TEXT,
                editWindowUtf16Units = BOUNDED_EDIT_WINDOW_UTF16_UNITS
            )
        val session = createSession(document)
        session.openInitialEditor()
        val retainedDraft = requireNotNull(session.activeDraft)
        val retainedTextFieldState = retainedDraft.textFieldState
        val retainedScrollState = retainedDraft.scrollState
        var previousGeneration = retainedDraft.edit.generation

        repeat(times = 2) {
            assertTrue(
                session.requestAutomaticEditWindowTransition(
                    draft = retainedDraft,
                    towardNext = true,
                    localAnchorUtf16Offset = retainedDraft.textFieldState.text.length - 1,
                    anchorViewportTopPixels = 48
                )
            )
            assertSame(retainedDraft, session.activeDraft)
            assertSame(retainedTextFieldState, retainedDraft.textFieldState)
            assertSame(retainedScrollState, retainedDraft.scrollState)
            assertTrue(retainedDraft.edit.generation > previousGeneration)
            previousGeneration = retainedDraft.edit.generation
            assertTrue(
                retainedDraft.consumeScrollRestoration(
                    requireNotNull(retainedDraft.scrollRestoration)
                )
            )
        }

        assertTrue(
            session.requestAutomaticEditWindowTransition(
                draft = retainedDraft,
                towardNext = false,
                localAnchorUtf16Offset = 1,
                anchorViewportTopPixels = -24
            )
        )
        assertSame(retainedDraft, session.activeDraft)
        assertSame(retainedTextFieldState, retainedDraft.textFieldState)
        assertSame(retainedScrollState, retainedDraft.scrollState)
        assertTrue(retainedDraft.edit.generation > previousGeneration)
        session.close()
    }

    /** Verifies edits synchronize against the generation retained after an automatic handoff. */
    @Test
    fun synchronizesEditsAfterAnAutomaticEditWindowHandoff() {
        val document =
            TestEditorDocument(
                initialText = BOUNDED_DOCUMENT_TEXT,
                editWindowUtf16Units = BOUNDED_EDIT_WINDOW_UTF16_UNITS
            )
        val session = createSession(document)
        session.openInitialEditor()
        val retainedDraft = requireNotNull(session.activeDraft)

        assertTrue(
            session.requestAutomaticEditWindowTransition(
                draft = retainedDraft,
                towardNext = true,
                localAnchorUtf16Offset = 3,
                anchorViewportTopPixels = 32
            )
        )
        assertSame(retainedDraft, session.activeDraft)
        assertEquals(Utf16Range(start = 1L, end = 5L), retainedDraft.edit.snapshot.range)

        retainedDraft.textFieldState.edit {
            replace(start = 1, end = 2, text = "C")
            selection = TextRange(2)
        }
        session.observeActiveEdit(retainedDraft, retainedDraft.captureFieldValue())

        assertEquals("abCdefghij", document.text)
        assertSame(retainedDraft, session.activeDraft)
        assertFalse(retainedDraft.hasChanges)
        session.close()
    }

    /** Verifies an active-end-first selection expands and keeps its direction across a handoff. */
    @Test
    fun preservesReversedSelectionAcrossAnExpandedEditWindow() {
        val documentText =
            List(size = 8) {
                "x".repeat(4_095)
            }.joinToString(separator = "\n")
        val document =
            TestEditorDocument(
                initialText = documentText,
                editWindowUtf16Units = EDIT_WINDOW_UTF16_UNITS
            )
        val session = createSession(document)
        session.openInitialEditor()
        val initialDraft = requireNotNull(session.activeDraft)
        assertEquals(EDIT_WINDOW_UTF16_UNITS, initialDraft.textFieldState.text.length)
        val reversedSelection = TextRange(start = EDIT_WINDOW_UTF16_UNITS, end = 0)
        initialDraft.textFieldState.edit {
            selection = reversedSelection
        }
        initialDraft.updateEditorFocusIntent(isFocused = true, canClear = true)

        assertTrue(
            session.requestAutomaticEditWindowTransition(
                draft = initialDraft,
                towardNext = true,
                localAnchorUtf16Offset = EDIT_WINDOW_UTF16_UNITS - 1,
                anchorViewportTopPixels = 24
            )
        )

        val movedDraft = requireNotNull(session.activeDraft)
        assertSame(initialDraft, movedDraft)
        assertEquals(
            Utf16Range(start = 0L, end = EDIT_WINDOW_UTF16_UNITS.toLong()),
            movedDraft.edit.snapshot.selection
        )
        assertEquals(reversedSelection, movedDraft.textFieldState.selection)
        assertTrue(movedDraft.edit.snapshot.range.end > EDIT_WINDOW_UTF16_UNITS)
        assertTrue(movedDraft.edit.snapshot.text.length <= EDIT_DRAFT_MAX_UTF16_UNITS)
        assertEquals(
            Utf16Range(start = 0L, end = EDIT_WINDOW_UTF16_UNITS.toLong()),
            document.editWindowCalls.last()
        )
        assertTrue(movedDraft.shouldRestoreEditorFocus)
        session.close()
    }

    /** Verifies the cross-window selection ceiling is direction independent. */
    @Test
    fun detectsTheBoundedSelectionLimitInBothDirections() {
        assertFalse(
            hasReachedEditSelectionLimit(
                TextRange(start = 0, end = EDIT_DRAFT_MAX_UTF16_UNITS - 1)
            )
        )
        assertTrue(
            hasReachedEditSelectionLimit(
                TextRange(start = 0, end = EDIT_DRAFT_MAX_UTF16_UNITS)
            )
        )
        assertTrue(
            hasReachedEditSelectionLimit(
                TextRange(start = EDIT_DRAFT_MAX_UTF16_UNITS, end = 0)
            )
        )
    }

    /** Verifies the editor reports and clears one reached selection boundary. */
    @Test
    fun reportsAndClearsTheBoundedSelectionLimit() {
        val documentText =
            buildString(capacity = EDIT_DRAFT_MAX_UTF16_UNITS) {
                repeat(times = 7) {
                    append("x".repeat(4_095))
                    append('\n')
                }
                append("x".repeat(4_096))
            }
        val document =
            TestEditorDocument(
                initialText = documentText,
                editWindowUtf16Units = EDIT_WINDOW_UTF16_UNITS
            )
        val session = createSession(document)
        session.openInitialEditor()
        var draft = requireNotNull(session.activeDraft)
        draft.updateEditorFocusIntent(isFocused = true, canClear = true)
        repeat(times = 2) {
            draft.textFieldState.edit {
                selection = TextRange(start = 0, end = length)
            }
            assertTrue(
                session.requestAutomaticEditWindowTransition(
                    draft = draft,
                    towardNext = true,
                    localAnchorUtf16Offset = draft.textFieldState.text.length,
                    anchorViewportTopPixels = 0
                )
            )
            draft = requireNotNull(session.activeDraft)
            draft.updateEditorFocusIntent(isFocused = true, canClear = true)
        }
        assertEquals(EDIT_DRAFT_MAX_UTF16_UNITS, draft.textFieldState.text.length)
        draft.textFieldState.edit {
            selection = TextRange(start = 0, end = length)
        }

        assertFalse(
            session.requestAutomaticEditWindowTransition(
                draft = draft,
                towardNext = true,
                localAnchorUtf16Offset = draft.textFieldState.text.length,
                anchorViewportTopPixels = 0
            )
        )
        assertEquals(
            UiText.Resource(R.string.source_selection_limit),
            draft.selectionBoundaryMessage
        )

        draft.textFieldState.edit {
            selection = TextRange(start = 1, end = EDIT_DRAFT_MAX_UTF16_UNITS)
        }
        draft.recordFieldObservation(draft.captureFieldValue())

        assertNull(draft.selectionBoundaryMessage)
        session.close()
    }

    /** Verifies an adjacent prefetch makes the automatic window swap allocation-free. */
    @Test
    fun consumesPrefetchedWindowDuringAutomaticHandoff() {
        val document =
            TestEditorDocument(
                initialText = BOUNDED_DOCUMENT_TEXT,
                editWindowUtf16Units = BOUNDED_EDIT_WINDOW_UTF16_UNITS
            )
        val session = createSession(document)
        session.openInitialEditor()
        val initialDraft = requireNotNull(session.activeDraft)

        session.prefetchAdjacentEditWindows(initialDraft)

        assertEquals(
            listOf(
                Utf16Range(start = 0L, end = 0L),
                Utf16Range(
                    start = BOUNDED_EDIT_WINDOW_UTF16_UNITS.toLong(),
                    end = BOUNDED_EDIT_WINDOW_UTF16_UNITS.toLong()
                )
            ),
            document.editWindowCalls
        )
        assertTrue(
            session.requestAutomaticEditWindowTransition(
                draft = initialDraft,
                towardNext = true,
                localAnchorUtf16Offset = 3,
                anchorViewportTopPixels = -12
            )
        )

        val movedDraft = requireNotNull(session.activeDraft)
        assertSame(initialDraft, movedDraft)
        assertEquals(Utf16Range(start = 2L, end = 6L), movedDraft.edit.snapshot.range)
        assertEquals(Utf16Range(start = 3L, end = 3L), movedDraft.edit.snapshot.selection)
        assertEquals(2, document.editWindowCalls.size)
        assertEquals(
            -12,
            requireNotNull(movedDraft.scrollRestoration).anchor.viewportTopOffsetPixels
        )
        session.close()
    }

    /** Verifies unfocused scrolling does not summon editing focus after handoff. */
    @Test
    fun keepsAutomaticBrowsingUnfocusedAcrossEditWindows() {
        val document =
            TestEditorDocument(
                initialText = BOUNDED_DOCUMENT_TEXT,
                editWindowUtf16Units = BOUNDED_EDIT_WINDOW_UTF16_UNITS
            )
        val session = createSession(document)
        session.openInitialEditor()
        val initialDraft = requireNotNull(session.activeDraft)

        assertTrue(
            session.requestAutomaticEditWindowTransition(
                draft = initialDraft,
                towardNext = true,
                localAnchorUtf16Offset = 3,
                anchorViewportTopPixels = 120
            )
        )

        val movedDraft = requireNotNull(session.activeDraft)
        assertSame(initialDraft, movedDraft)
        assertEquals(Utf16Range(start = 3, end = 3), movedDraft.edit.snapshot.selection)
        assertEquals(TextRange(2), movedDraft.textFieldState.selection)
        assertFalse(movedDraft.shouldRestoreEditorFocus)
        session.close()
    }

    /** Verifies Undo includes the visible word while the IME is still composing it. */
    @Test
    fun undoesComposingTextWithoutRequiringASpace() {
        val document = TestEditorDocument("before")
        val session = createSession(document)
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "after")
            selection = TextRange(length)
        }
        draft.textFieldState.retainCompositionForTest(TextRange(0, 5))
        session.observeActiveEdit(draft, draft.captureFieldValue())

        assertTrue(session.canUndo)
        assertTrue(session.requestUndo())
        assertSame(draft, session.activeDraft)
        assertEquals("before", document.text)
        assertEquals("before", session.activeDraft?.textFieldState?.text?.toString())
        assertNull(session.activeDraft?.textFieldState?.composition)
        assertTrue(session.canRedo)
        assertTrue(session.requestRedo())
        assertEquals("after", document.text)
        assertSame(draft, session.activeDraft)
        session.close()
    }

    /** Verifies both history directions retain field, scroll, and actual focus identities. */
    @Test
    fun retainsTheInputSessionAcrossHistoryRevisions() {
        createSession(TestEditorDocument("before")).use { session ->
            session.openInitialEditor()
            val draft = requireNotNull(session.activeDraft)
            val field = draft.textFieldState
            val scroll = draft.scrollState
            draft.updateEditorFocusIntent(isFocused = true, canClear = true)
            field.edit {
                replace(start = 0, end = length, text = "after")
                selection = TextRange(length)
            }

            repeat(3) {
                assertTrue(session.requestUndo())
                assertSame(draft, session.activeDraft)
                assertSame(field, session.activeDraft?.textFieldState)
                assertSame(scroll, session.activeDraft?.scrollState)
                assertEquals("before", field.text.toString())
                assertEquals(TextRange(0), field.selection)
                assertFalse(draft.hasChanges)
                assertTrue(draft.isEditorFocused)
                assertTrue(draft.shouldRestoreEditorFocus)
                assertFalse(session.preservesEditorInputSession)

                assertTrue(session.requestRedo())
                assertSame(draft, session.activeDraft)
                assertSame(field, session.activeDraft?.textFieldState)
                assertEquals("after", field.text.toString())
                assertEquals(TextRange(5), field.selection)
                assertFalse(draft.hasChanges)
                assertTrue(draft.isEditorFocused)
                assertFalse(session.preservesEditorInputSession)
            }
        }
    }

    /** Verifies history blocks racing input without changing the editable IME contract. */
    @Test
    fun gatesInputWhileHistoryKeepsItsInputSession() {
        val worker = QueuedSessionTestDispatcher()
        val document = TestEditorDocument("before")
        val state = EditorDocumentState(document, worker)
        EditorSession(
            title = "Test document",
            state = state,
            operationDispatcher = ImmediateSessionTestDispatcher,
            editSynchronizationDelay = {}
        ).use { session ->
            session.openInitialEditor()
            worker.runAll()
            val draft = requireNotNull(session.activeDraft)
            draft.textFieldState.edit {
                replace(start = 0, end = length, text = "after")
                selection = TextRange(length)
            }
            assertFalse(session.preservesEditorInputSession)
            assertTrue(session.canApplyEditorInput(draft))

            assertTrue(session.requestUndo())
            assertTrue(session.preservesEditorInputSession)
            assertSame(draft, session.activeDraft)
            assertFalse(session.requestRedo())
            assertFalse(session.requestUndo())
            assertFalse(session.canApplyEditorInput(draft))
            assertFalse(
                isActiveEditFieldReadOnly(
                    canAcceptInput = false,
                    preserveInputSession = session.preservesEditorInputSession
                )
            )
            worker.runAll()
            assertSame(draft, session.activeDraft)
            assertEquals("before", draft.textFieldState.text.toString())
            assertFalse(session.preservesEditorInputSession)
            assertTrue(session.canApplyEditorInput(draft))

            assertTrue(session.requestRedo())
            assertTrue(session.preservesEditorInputSession)
            assertFalse(session.canApplyEditorInput(draft))
            worker.runAll()
            assertSame(draft, session.activeDraft)
            assertEquals("after", draft.textFieldState.text.toString())
            assertFalse(session.preservesEditorInputSession)
            assertTrue(session.canApplyEditorInput(draft))
        }
    }

    /** Verifies a detached input connection cannot edit a newer document window. */
    @Test
    fun rejectsInputFromAReplacedDraft() {
        createSession(
            TestEditorDocument(BOUNDED_DOCUMENT_TEXT, BOUNDED_EDIT_WINDOW_UTF16_UNITS)
        ).use { session ->
            session.openInitialEditor()
            val initialDraft = requireNotNull(session.activeDraft)
            assertTrue(session.canApplyEditorInput(initialDraft))
            assertTrue(session.requestEditWindowAction(initialDraft, EditWindowAction.Later))

            val laterDraft = requireNotNull(session.activeDraft)
            assertFalse(session.canApplyEditorInput(initialDraft))
            assertTrue(session.canApplyEditorInput(laterDraft))
            session.close()
            assertFalse(session.canApplyEditorInput(laterDraft))
        }
    }

    /** Verifies a failed history window ends its handoff and preserves a retryable journal. */
    @Test
    fun recoversHistoryAfterItsTargetWindowFails() {
        val document = TestEditorDocument("before")
        var failWindow = false
        val failingDocument = object : EditorDocument by document {
            override fun editWindow(
                revision: Long,
                selection: Utf16Range,
                limits: EditWindowLimits
            ) = if (failWindow) {
                throw IllegalStateException("synthetic history-window failure")
            } else {
                document.editWindow(revision, selection, limits)
            }
        }
        createSession(failingDocument).use { session ->
            session.openInitialEditor()
            val draft = requireNotNull(session.activeDraft)
            draft.textFieldState.edit {
                replace(start = 0, end = length, text = "after")
                selection = TextRange(length)
            }
            session.flushPendingEdit()
            failWindow = true

            assertTrue(session.requestUndo())
            assertEquals("before", document.text)
            assertNull(session.activeDraft)
            assertFalse(session.preservesEditorInputSession)
            assertFalse(session.canApplyEditorInput(draft))
            assertTrue(session.canRetryEditWindow)

            failWindow = false
            session.retryEditWindow()
            val retried = requireNotNull(session.activeDraft)
            assertTrue(session.canApplyEditorInput(retried))
            assertTrue(session.requestRedo())
            assertSame(retried, session.activeDraft)
            assertEquals("after", retried.textFieldState.text.toString())
            assertFalse(session.preservesEditorInputSession)
        }
    }

    /** Verifies an unchanged IME composing region does not hide retained redo history. */
    @Test
    fun redoesWithAnUnchangedComposingRegion() {
        val document = TestEditorDocument("before")
        val session = createSession(document)
        session.openInitialEditor()
        val originalDraft = requireNotNull(session.activeDraft)
        originalDraft.textFieldState.edit {
            replace(start = 0, end = length, text = "after")
            selection = TextRange(length)
        }
        assertTrue(session.requestUndo())
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.retainCompositionForTest(TextRange(0, 6))
        session.observeActiveEdit(draft, draft.captureFieldValue())

        assertTrue(session.canRedo)
        assertTrue(session.requestRedo())
        assertEquals("after", document.text)
        assertNull(session.activeDraft?.textFieldState?.composition)
        session.close()
    }

    /** Verifies history composition finalization preserves reversed selection and focus. */
    @Test
    fun commitsComposingTextWithoutChangingTheField() {
        val session = createSession(TestEditorDocument("café 📝"))
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        val retainedSelection = TextRange(4, 1)
        draft.textFieldState.edit { selection = retainedSelection }
        draft.updateEditorFocusIntent(isFocused = true, canClear = true)
        draft.textFieldState.retainCompositionForTest(TextRange(0, 4))

        draft.commitComposingText()

        assertEquals("café 📝", draft.textFieldState.text.toString())
        assertEquals(retainedSelection, draft.textFieldState.selection)
        assertNull(draft.textFieldState.composition)
        assertTrue(draft.isEditorFocused)
        assertTrue(draft.shouldRestoreEditorFocus)
        assertFalse(draft.hasChanges)
        session.close()
    }

    /** Verifies composing history actions autosave exact content after a lifecycle checkpoint. */
    @Test
    fun autosavesHistoryAfterAComposingCheckpoint() {
        val document = TestEditorDocument("before")
        val source = TestEditorDocumentSource()
        val session = createSession(document = document, documentSource = source)
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "after")
            selection = TextRange(length)
        }
        draft.textFieldState.retainCompositionForTest(TextRange(0, 5))
        session.observeActiveEdit(draft, draft.captureFieldValue())
        session.checkpointPendingEdit()
        assertEquals(listOf("after"), source.savedTexts)

        assertTrue(session.requestUndo())
        val restoredDraft = requireNotNull(session.activeDraft)
        restoredDraft.textFieldState.retainCompositionForTest(TextRange(0, 6))
        session.observeActiveEdit(restoredDraft, restoredDraft.captureFieldValue())
        assertTrue(session.requestRedo())

        assertEquals(listOf("after", "before", "after"), source.savedTexts)
        assertEquals(SourceSaveStatus.Saved, session.sourceSaveStatus)
        session.close()
    }

    /** Verifies rejected Redo leaves a new composing edit untouched. */
    @Test
    fun rejectsRedoWithoutCommittingANewComposingEdit() {
        val session = createSession(TestEditorDocument("before"))
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "after")
            selection = TextRange(length)
        }
        assertTrue(session.requestUndo())
        val branchDraft = requireNotNull(session.activeDraft)
        branchDraft.textFieldState.edit {
            replace(start = 0, end = length, text = "branch")
            selection = TextRange(length)
        }
        val composition = TextRange(0, 6)
        branchDraft.textFieldState.retainCompositionForTest(composition)

        assertFalse(session.canRedo)
        assertFalse(session.requestRedo())
        assertEquals("branch", branchDraft.textFieldState.text.toString())
        assertEquals(composition, branchDraft.textFieldState.composition)
        session.close()
    }

    /** Verifies bounded undo and redo reopen the exact edit windows they cross. */
    @Test
    fun undoesAndRedoesAcrossBoundedEditWindows() {
        val document =
            TestEditorDocument(
                initialText = BOUNDED_DOCUMENT_TEXT,
                editWindowUtf16Units = BOUNDED_EDIT_WINDOW_UTF16_UNITS
            )
        val session = createSession(document)
        session.openInitialEditor()
        val firstDraft = requireNotNull(session.activeDraft)
        firstDraft.textFieldState.edit {
            replace(start = 0, end = 1, text = "A")
            selection = TextRange(1)
        }
        assertTrue(
            session.requestEditWindowAction(
                draft = firstDraft,
                action = EditWindowAction.Later
            )
        )
        val laterDraft = requireNotNull(session.activeDraft)
        val laterCharacter = laterDraft.edit.snapshot.text.indexOf('f')
        assertTrue(laterCharacter >= 0)
        laterDraft.textFieldState.edit {
            replace(start = laterCharacter, end = laterCharacter + 1, text = "F")
            selection = TextRange(laterCharacter + 1)
        }

        assertTrue(session.requestUndo())
        assertEquals("Abcdefghij", document.text)
        assertEquals(Utf16Range(start = 4, end = 4), session.activeDraft?.edit?.snapshot?.selection)
        assertSame(laterDraft, session.activeDraft)
        assertTrue(session.canUndo)
        assertTrue(session.canRedo)

        assertTrue(session.requestUndo())
        assertEquals(BOUNDED_DOCUMENT_TEXT, document.text)
        assertEquals(Utf16Range(start = 0, end = 0), session.activeDraft?.edit?.snapshot?.selection)
        assertSame(laterDraft, session.activeDraft)
        assertFalse(session.canUndo)
        assertTrue(session.canRedo)

        assertTrue(session.requestRedo())
        assertEquals("Abcdefghij", document.text)
        assertEquals(Utf16Range(start = 1, end = 1), session.activeDraft?.edit?.snapshot?.selection)
        assertSame(laterDraft, session.activeDraft)

        assertTrue(session.requestRedo())
        assertEquals("AbcdeFghij", document.text)
        assertEquals(Utf16Range(start = 6, end = 6), session.activeDraft?.edit?.snapshot?.selection)
        assertSame(laterDraft, session.activeDraft)
        assertTrue(session.canUndo)
        assertFalse(session.canRedo)
        session.close()
    }

    /** Verifies a new committed branch retires every redo entry. */
    @Test
    fun clearsRedoHistoryAfterANewCommittedEdit() {
        val document = TestEditorDocument("before")
        val session = createSession(document)
        session.openInitialEditor()
        val firstDraft = requireNotNull(session.activeDraft)
        firstDraft.textFieldState.edit {
            replace(start = 0, end = length, text = "after")
            selection = TextRange(length)
        }
        session.flushPendingEdit()
        assertEquals("after", document.text)
        assertTrue(session.requestUndo())
        assertEquals("before", document.text)
        assertTrue(session.canRedo)

        val branchDraft = requireNotNull(session.activeDraft)
        branchDraft.textFieldState.edit {
            replace(start = 0, end = length, text = "branch")
            selection = TextRange(length)
        }
        session.flushPendingEdit()

        assertEquals("branch", document.text)
        assertTrue(session.canUndo)
        assertFalse(session.canRedo)
        assertFalse(session.requestRedo())
        session.close()
    }

    /** Verifies undo restores the selection where a debounced edit actually began. */
    @Test
    fun restoresThePreEditSelectionAfterUndo() {
        val document = TestEditorDocument("abcdef")
        val session = createSession(document)
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            selection = TextRange(3)
        }
        session.observeActiveEdit(draft = draft, value = draft.captureFieldValue())
        draft.textFieldState.edit {
            replace(start = 3, end = 3, text = "X")
            selection = TextRange(4)
        }

        assertTrue(session.requestUndo())

        assertEquals("abcdef", document.text)
        assertEquals(Utf16Range(start = 3, end = 3), session.activeDraft?.edit?.snapshot?.selection)
        session.close()
    }

    /** Verifies an external revision invalidates history before it can replace text. */
    @Test
    fun clearsSessionHistoryAfterAStaleUndo() {
        val document = TestEditorDocument("before")
        val session = createSession(document)
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "after")
            selection = TextRange(length)
        }
        session.flushPendingEdit()
        document.advanceExternally(EXTERNAL_EDIT_REVISION)

        assertTrue(session.requestUndo())

        assertEquals("after", document.text)
        assertEquals(EditorDocumentStatus.Stale, session.state.status)
        assertFalse(session.canUndo)
        assertFalse(session.canRedo)
        assertFalse(session.requestUndo())
        session.close()
    }

    /** Verifies the session journal never retains more than its fixed entry bound. */
    @Test
    fun boundsTheSessionHistoryEntryCount() {
        val document = TestEditorDocument("0")
        val session = createSession(document)
        session.openInitialEditor()
        repeat(SESSION_HISTORY_BOUNDARY_EDIT_COUNT) { editIndex ->
            val replacement = if (editIndex % 2 == 0) "a" else "b"
            val draft = requireNotNull(session.activeDraft)
            draft.textFieldState.edit {
                replace(start = 0, end = length, text = replacement)
                selection = TextRange(length)
            }
            session.flushPendingEdit()
        }

        repeat(SESSION_HISTORY_ENTRY_LIMIT) {
            assertTrue(session.requestUndo())
        }
        assertEquals("a", document.text)
        assertFalse(session.canUndo)
        assertFalse(session.requestUndo())
        session.close()
    }

    /** Verifies cancelling section navigation preserves its already queued edit. */
    @Test
    fun cancelsNavigationWithoutCancellingItsEdit() {
        val document =
            TestEditorDocument(
                initialText = BOUNDED_DOCUMENT_TEXT,
                editWindowUtf16Units = BOUNDED_EDIT_WINDOW_UTF16_UNITS
            )
        val workerDispatcher = QueuedSessionTestDispatcher()
        val state = EditorDocumentState(document, workerDispatcher)
        val session =
            EditorSession(
                title = "Test document",
                state = state,
                operationDispatcher = ImmediateSessionTestDispatcher,
                editSynchronizationDelay = {}
            )
        session.openInitialEditor()
        workerDispatcher.runAll()
        val draft = requireNotNull(session.activeDraft)
        draft.updateEditorFocusIntent(isFocused = true, canClear = true)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = REPLACEMENT_BOUNDED_EDIT_TEXT)
            selection = TextRange(length)
        }
        assertTrue(
            session.requestEditWindowAction(
                draft = draft,
                action = EditWindowAction.Later
            )
        )
        draft.updateEditorFocusIntent(isFocused = false, canClear = true)

        session.cancelPendingEditWindowAction()
        workerDispatcher.runAll()

        assertSame(draft, session.activeDraft)
        assertEquals(
            REPLACEMENT_BOUNDED_EDIT_TEXT +
                BOUNDED_DOCUMENT_TEXT.drop(BOUNDED_EDIT_WINDOW_UTF16_UNITS),
            document.text
        )
        assertEquals(1, document.replaceCalls.size)
        assertEquals(1, document.editWindowCalls.size)
        assertTrue(draft.shouldRestoreEditorFocus)
        assertFalse(session.hasPendingEditWindowAction)
        session.close()
    }

    /** Verifies later navigation derives its caret from the committed replacement range. */
    @Test
    fun navigatesFromThePostCommitEditWindowEnd() {
        val document =
            TestEditorDocument(
                initialText = BOUNDED_DOCUMENT_TEXT,
                editWindowUtf16Units = BOUNDED_EDIT_WINDOW_UTF16_UNITS
            )
        val session = createSession(document)
        session.openInitialEditor()
        val initialDraft = requireNotNull(session.activeDraft)
        assertEquals(FIRST_BOUNDED_EDIT_TEXT, initialDraft.edit.snapshot.text)
        initialDraft.textFieldState.edit {
            replace(start = 0, end = length, text = EXPANDED_BOUNDED_EDIT_TEXT)
            selection = TextRange(length)
        }

        assertTrue(
            session.requestEditWindowAction(
                draft = initialDraft,
                action = EditWindowAction.Later
            )
        )

        val expectedPostCommitEnd = EXPANDED_BOUNDED_EDIT_TEXT.length.toLong()
        assertEquals(
            Utf16Range(start = expectedPostCommitEnd, end = expectedPostCommitEnd),
            document.editWindowCalls.last()
        )
        assertEquals(
            expectedPostCommitEnd,
            requireNotNull(session.activeDraft).edit.snapshot.selection.start
        )
        assertEquals(1, document.replaceCalls.size)
        assertEquals(2, document.editWindowCalls.size)
        assertFalse(session.hasPendingEditWindowAction)
        session.close()
    }

    /** Verifies later navigation starts before its committed revision finishes source saving. */
    @Test
    fun opensLaterTextBeforeWaitingForSourceAutosave() {
        val sourceSaveStarted = CompletableDeferred<Unit>()
        val finishSourceSave = CompletableDeferred<Unit>()
        val document =
            TestEditorDocument(
                initialText = BOUNDED_DOCUMENT_TEXT,
                editWindowUtf16Units = BOUNDED_EDIT_WINDOW_UTF16_UNITS
            )
        val source =
            TestEditorDocumentSource { _, _ ->
                sourceSaveStarted.complete(Unit)
                finishSourceSave.await()
            }
        val session = createSession(document = document, documentSource = source)
        session.openInitialEditor()
        val initialDraft = requireNotNull(session.activeDraft)
        initialDraft.textFieldState.edit {
            replace(start = 0, end = length, text = REPLACEMENT_BOUNDED_EDIT_TEXT)
            selection = TextRange(length)
        }

        try {
            assertTrue(
                session.requestEditWindowAction(
                    draft = initialDraft,
                    action = EditWindowAction.Later
                )
            )

            assertTrue(sourceSaveStarted.isCompleted)
            assertFalse(finishSourceSave.isCompleted)
            assertFalse(initialDraft === requireNotNull(session.activeDraft))
            assertEquals(2, document.editWindowCalls.size)
            assertFalse(session.hasPendingEditWindowAction)
        } finally {
            finishSourceSave.complete(Unit)
            session.close()
        }
    }

    /** Verifies reverting a failed edit permits moving to the neighboring window. */
    @Test
    fun movesAfterRevertingAFailedEdit() {
        val document =
            TestEditorDocument(
                initialText = BOUNDED_DOCUMENT_TEXT,
                editWindowUtf16Units = BOUNDED_EDIT_WINDOW_UTF16_UNITS
            )
        val session = createSession(document)
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "\uD800")
            selection = TextRange(length)
        }

        session.observeActiveEdit(draft, draft.captureFieldValue())

        assertEquals(
            UiText.Resource(R.string.operation_invalid_unicode),
            session.state.editorMessage
        )
        assertTrue(draft.hasChanges)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = FIRST_BOUNDED_EDIT_TEXT)
            selection = TextRange(length)
        }
        session.observeActiveEdit(draft, draft.captureFieldValue())
        assertFalse(draft.hasChanges)
        assertEquals(
            UiText.Resource(R.string.operation_invalid_unicode),
            session.state.editorMessage
        )

        assertTrue(
            session.requestEditWindowAction(
                draft = draft,
                action = EditWindowAction.Later
            )
        )

        assertFalse(draft === requireNotNull(session.activeDraft))
        assertNull(session.state.editorMessage)
        assertFalse(session.hasPendingEditWindowAction)
        assertTrue(document.replaceCalls.isEmpty())
        session.close()
    }

    /** Verifies failed queued navigation restores the retained field-focus intent. */
    @Test
    fun restoresEditorFocusAfterQueuedSynchronizationFails() {
        val document =
            TestEditorDocument(
                initialText = BOUNDED_DOCUMENT_TEXT,
                editWindowUtf16Units = BOUNDED_EDIT_WINDOW_UTF16_UNITS,
                replaceFailure = IllegalStateException("synthetic replacement failure")
            )
        val workerDispatcher = QueuedSessionTestDispatcher()
        val state = EditorDocumentState(document, workerDispatcher)
        val session =
            EditorSession(
                title = "Test document",
                state = state,
                operationDispatcher = ImmediateSessionTestDispatcher,
                editSynchronizationDelay = {}
            )
        session.openInitialEditor()
        workerDispatcher.runAll()
        val draft = requireNotNull(session.activeDraft)
        draft.updateEditorFocusIntent(isFocused = true, canClear = true)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = REPLACEMENT_BOUNDED_EDIT_TEXT)
            selection = TextRange(length)
        }

        assertTrue(
            session.requestEditWindowAction(
                draft = draft,
                action = EditWindowAction.Later
            )
        )
        draft.updateEditorFocusIntent(isFocused = false, canClear = true)
        workerDispatcher.runAll()

        assertSame(draft, session.activeDraft)
        assertTrue(draft.shouldRestoreEditorFocus)
        assertEquals(UiText.Resource(R.string.operation_apply_edit_failed), state.editorMessage)
        assertTrue(document.replaceCalls.isEmpty())
        assertEquals(1, document.editWindowCalls.size)
        assertFalse(session.hasPendingEditWindowAction)
        session.close()
    }

    /** Verifies composing text waits for confirmation without replacing the field. */
    @Test
    fun defersComposingTextUntilCompositionEnds() {
        val document = TestEditorDocument("before")
        val session = createSession(document)
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        val textFieldState = draft.textFieldState
        textFieldState.edit {
            replace(start = 0, end = length, text = "candidate")
            selection = TextRange(length)
        }
        val composingValue =
            draft.captureFieldValue().copy(composition = TextRange(start = 0, end = 9))

        session.observeActiveEdit(draft, composingValue)

        assertSame(draft, session.activeDraft)
        assertSame(textFieldState, requireNotNull(session.activeDraft).textFieldState)
        assertEquals("before", document.text)
        assertTrue(draft.hasChanges)
        assertTrue(document.replaceCalls.isEmpty())

        session.observeActiveEdit(draft, composingValue.copy(composition = null))

        assertEquals("candidate", document.text)
        assertEquals(TextRange(9), textFieldState.selection)
        assertFalse(draft.hasChanges)
        assertEquals(1, document.replaceCalls.size)

        session.close()
    }

    /** Verifies a stale automatic edit exposes an actionable discard and reload path. */
    @Test
    fun discardsAStaleFieldAndReloadsTheAuthoritativeRevision() {
        val document = TestEditorDocument("before")
        val session = createSession(document)
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "after")
        }
        document.advanceExternally(EXTERNAL_EDIT_REVISION)

        session.observeActiveEdit(draft, draft.captureFieldValue())

        assertEquals(EditorDocumentStatus.Stale, session.state.status)
        assertSame(draft, session.activeDraft)
        assertEquals(
            UiText.Resource(R.string.operation_reload_to_continue),
            session.saveUnavailableReason
        )
        assertTrue(draft.hasChanges)
        assertTrue(session.state.hasActiveDraftChanges)
        assertEquals(
            StaleEditRecovery.DiscardLocalChanges,
            session.state.staleEditRecovery
        )

        session.reloadStaleActiveEdit(draft)

        assertEquals(EditorDocumentStatus.Ready, session.state.status)
        assertNull(session.activeDraft)
        assertEquals(EXTERNAL_EDIT_REVISION, session.state.metrics?.revision)
        assertNull(session.state.staleEditRecovery)
        assertEquals("before", document.text)

        session.close()
    }

    /** Verifies destination selection and success remain generation-owned. */
    @Test
    fun completesOneRetainedSaveAsGeneration() {
        val document = TestEditorDocument("save me")
        val session = createSession(document)
        session.openInitialEditor()
        session.showSaveCopyFormatSelection()
        assertEquals(
            SaveStatus.ChoosingFormat(SaveDestinationPurpose.Copy),
            session.saveStatus
        )

        session.selectSaveFormat(DocumentFormat.Markdown)
        val request = session.claimPreparedSaveDestination()
        assertEquals(DocumentFormat.Markdown, request.format)
        assertEquals(SaveDestinationPurpose.Copy, request.purpose)
        assertEquals(SaveStatus.PreparingDestination(request), session.saveStatus)
        var exported = false
        lateinit var exportedSnapshot: TestEditorDocumentSnapshot
        val destinationOwner = TestDestinationOwner()

        assertTrue(
            session.saveSelectedDestination(request, destinationOwner) { snapshot, bytes ->
                exportedSnapshot = snapshot as TestEditorDocumentSnapshot
                assertEquals(0L, exportedSnapshot.revision)
                assertEquals("save me", exportedSnapshot.text)
                assertArrayEquals(
                    "save me".toByteArray(Charsets.UTF_8),
                    exportedSnapshot.copyUtf8Bytes()
                )
                assertEquals(7L, bytes)
                assertFalse(exportedSnapshot.isClosed)
                assertEquals(7L, exportedSnapshot.consume())
                assertEquals(1, exportedSnapshot.writeCallCount)
                exported = true
            }
        )

        assertTrue(exported)
        assertEquals(
            SaveStatus.Succeeded(request = request, hasNewerChanges = false),
            session.saveStatus
        )
        assertFalse(session.isSaveBusy)
        assertTrue(session.canStartSaveAs)
        assertFalse(session.hasUnsavedChanges)
        assertSame(exportedSnapshot, document.capturedSnapshots.single())
        assertTrue(exportedSnapshot.isClosed)
        assertEquals(1, exportedSnapshot.closeCallCount)
        assertEquals(1, destinationOwner.closeCallCount)
        session.retireSaveSuccess(Math.incrementExact(request.generation))
        assertEquals(
            SaveStatus.Succeeded(request = request, hasNewerChanges = false),
            session.saveStatus
        )
        session.retireSaveSuccess(request.generation)
        assertEquals(SaveStatus.Idle, session.saveStatus)
        session.close()
    }

    /** Verifies a failed first save retries with its exact source-replacement purpose. */
    @Test
    fun retriesAFailedSourceDestinationWithItsOriginalPurpose() {
        val session = createSession(TestEditorDocument("save me"))
        session.openInitialEditor()
        session.showSaveFormatSelection()
        session.selectSaveFormat(DocumentFormat.Markdown)
        val request = session.claimPreparedSaveDestination()
        assertEquals(SaveDestinationPurpose.SourceReplacement, request.purpose)

        assertTrue(
            session.failSaveDestination(request, UiText.Literal("Synthetic destination failure"))
        )
        assertEquals(
            SaveStatus.Failed(
                request = request,
                message = UiText.Literal("Synthetic destination failure")
            ),
            session.saveStatus
        )

        session.restartExplicitSave()

        assertEquals(
            SaveStatus.ChoosingFormat(SaveDestinationPurpose.SourceReplacement),
            session.saveStatus
        )
        session.close()
    }

    /** Verifies only the current explicit-save result can be dismissed. */
    @Test
    fun dismissesOnlyTheMatchingExplicitSaveResult() {
        val session = createSession(TestEditorDocument("save me"))
        session.openInitialEditor()
        session.showSaveCopyFormatSelection()
        session.selectSaveFormat(DocumentFormat.PlainText)
        val request = session.claimPreparedSaveDestination()
        val expectedStatus =
            SaveStatus.Failed(
                request = request,
                message = UiText.Literal("Synthetic destination failure")
            )
        assertTrue(session.failSaveDestination(request, expectedStatus.message))

        session.dismissExplicitSaveResult(Math.incrementExact(request.generation))

        assertEquals(expectedStatus, session.saveStatus)

        session.dismissExplicitSaveResult(request.generation)

        assertEquals(SaveStatus.Idle, session.saveStatus)
        session.close()
    }

    /** Verifies a failed copy pauses queued closing so its error remains visible. */
    @Test
    fun keepsTheEditorOpenAfterAQueuedCopyFails() {
        val document = TestEditorDocument("save me")
        val session = createSession(document)
        val finishExport = CompletableDeferred<Unit>()
        session.openInitialEditor()
        session.showSaveCopyFormatSelection()
        session.selectSaveFormat(DocumentFormat.PlainText)
        val request = session.claimPreparedSaveDestination()
        val destinationOwner = TestDestinationOwner()
        assertTrue(
            session.saveSelectedDestination(request, destinationOwner) { snapshot, _ ->
                (snapshot as TestEditorDocumentSnapshot).consume()
                finishExport.await()
                error("synthetic export failure")
            }
        )

        assertEquals(CloseRequestResult.Queued, session.requestClose())
        finishExport.complete(Unit)

        assertTrue(session.saveStatus is SaveStatus.Failed)
        assertNull(session.resolvePendingClose())
        assertFalse(session.isClosePending)
        assertEquals(1, destinationOwner.closeCallCount)
        session.restartExplicitSave()
        assertEquals(
            SaveStatus.ChoosingFormat(SaveDestinationPurpose.Copy),
            session.saveStatus
        )
        session.close()
    }

    /** Verifies saving an older snapshot reports newer unsaved edits accurately. */
    @Test
    fun reportsNewerChangesAfterOneSnapshotCommits() {
        val document = TestEditorDocument("original")
        val session = createSession(document)
        session.openInitialEditor()
        val editGeneration = requireNotNull(session.state.activeEdit).generation
        session.showSaveCopyFormatSelection()
        session.selectSaveFormat(DocumentFormat.PlainText)
        val request = session.claimPreparedSaveDestination()
        val destinationOwner = TestDestinationOwner()

        assertTrue(
            session.saveSelectedDestination(request, destinationOwner) { snapshot, _ ->
                (snapshot as TestEditorDocumentSnapshot).consume()
                session.state.commitActiveEdit(
                    generation = editGeneration,
                    text = "newer",
                    selection = Utf16Range(5L, 5L)
                )
            }
        )

        assertEquals(
            SaveStatus.Succeeded(request = request, hasNewerChanges = true),
            session.saveStatus
        )
        assertTrue(session.hasUnsavedChanges)
        assertEquals(1, destinationOwner.closeCallCount)
        session.close()
    }

    /** Verifies typing during export reports the newer draft as unsaved. */
    @Test
    fun reportsDraftChangesMadeWhileOneSnapshotExports() {
        val document = TestEditorDocument("original")
        val session = createSession(document)
        val allowCompletion = CompletableDeferred<Unit>()
        session.openInitialEditor()
        session.showSaveCopyFormatSelection()
        session.selectSaveFormat(DocumentFormat.PlainText)
        val request = session.claimPreparedSaveDestination()
        val destinationOwner = TestDestinationOwner()

        assertTrue(
            session.saveSelectedDestination(request, destinationOwner) { snapshot, _ ->
                (snapshot as TestEditorDocumentSnapshot).consume()
                allowCompletion.await()
            }
        )
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "newer draft")
        }
        session.updateActiveDraftStatus(draft)

        allowCompletion.complete(Unit)

        assertEquals(
            SaveStatus.Succeeded(request = request, hasNewerChanges = true),
            session.saveStatus
        )
        assertTrue(session.hasUnsavedChanges)
        assertEquals(1, destinationOwner.closeCallCount)
        session.close()
    }

    /** Verifies readiness loss closes an accepted descriptor without invoking export. */
    @Test
    fun closesDestinationWhenReadinessChangesAfterPickerLaunch() {
        val document = TestEditorDocument("draft")
        val session = createSession(document)
        session.openInitialEditor()
        session.showSaveCopyFormatSelection()
        session.selectSaveFormat(DocumentFormat.PlainText)
        val request = session.claimPreparedSaveDestination()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "changed")
        }
        session.updateActiveDraftStatus(draft)
        val destinationOwner = TestDestinationOwner()
        var exportCalled = false

        assertTrue(
            session.saveSelectedDestination(request, destinationOwner) { _, _ ->
                exportCalled = true
            }
        )

        assertFalse(exportCalled)
        assertEquals(1, destinationOwner.closeCallCount)
        assertEquals(
            SaveStatus.Failed(
                request = request,
                message = UiText.Resource(R.string.operation_not_ready_save)
            ),
            session.saveStatus
        )
        session.close()
    }

    /** Verifies cancellation owns the save slot until suspended cleanup finishes. */
    @Test
    fun cancelsOneRetainedSaveWithoutMarkingItSuccessful() {
        val document = TestEditorDocument("cancel me")
        val session = createSession(document)
        val neverComplete = CompletableDeferred<Unit>()
        session.openInitialEditor()
        session.showSaveCopyFormatSelection()
        session.selectSaveFormat(DocumentFormat.PlainText)
        val request = session.claimPreparedSaveDestination()
        val destinationOwner = TestDestinationOwner()

        assertTrue(
            session.saveSelectedDestination(request, destinationOwner) { _, _ ->
                neverComplete.await()
            }
        )
        assertTrue(session.saveStatus is SaveStatus.Exporting)
        assertTrue(session.isSaveBusy)

        session.cancelSave()

        assertEquals(
            SaveStatus.Cancelled(request),
            session.saveStatus
        )
        assertFalse(session.isSaveBusy)
        assertFalse(session.hasUnsavedChanges)
        assertEquals(EditorDocumentStatus.Ready, session.state.status)
        val cancelledSnapshot = document.capturedSnapshots.single()
        assertTrue(cancelledSnapshot.isClosed)
        assertEquals(1, cancelledSnapshot.closeCallCount)
        assertEquals(1, destinationOwner.closeCallCount)
        assertTrue(session.restartExplicitSave())
        assertEquals(
            SaveStatus.ChoosingFormat(SaveDestinationPurpose.Copy),
            session.saveStatus
        )
        session.close()
    }

    /** Verifies cancellation after destination commit still marks the revision saved. */
    @Test
    fun marksCommittedSaveSuccessfulWhenCancellationArrivesAfterCommit() {
        val document = TestEditorDocument("committed")
        val session = createSession(document)
        val awaitCancellation = CompletableDeferred<Unit>()
        session.openInitialEditor()
        session.showSaveCopyFormatSelection()
        session.selectSaveFormat(DocumentFormat.PlainText)
        val request = session.claimPreparedSaveDestination()
        val destinationOwner = TestDestinationOwner()

        assertTrue(
            session.saveSelectedDestination(request, destinationOwner) { snapshot, _ ->
                (snapshot as TestEditorDocumentSnapshot).consume()
                try {
                    awaitCancellation.await()
                } catch (_: CancellationException) {
                    // Simulate an exporter that observes a completed commit after cancellation.
                }
            }
        )

        session.cancelSave()

        assertEquals(
            SaveStatus.Succeeded(request = request, hasNewerChanges = false),
            session.saveStatus
        )
        assertFalse(session.hasUnsavedChanges)
        assertEquals(1, destinationOwner.closeCallCount)
        session.close()
    }

    /** Verifies retained operations finish without a composition-owned caller scope. */
    @Test
    fun completesWorkReleasedThroughItsOwnedOperationScope() {
        val document = TestEditorDocument("deferred")
        val workerDispatcher = QueuedSessionTestDispatcher()
        val state = EditorDocumentState(document, workerDispatcher)
        val session =
            EditorSession(
                title = "Test document",
                state = state,
                operationDispatcher = ImmediateSessionTestDispatcher
            )

        session.openInitialEditor()

        assertEquals(EditorDocumentStatus.LoadingInitial, state.status)
        assertEquals(1, workerDispatcher.pendingCount)
        assertTrue(document.viewportCalls.isEmpty())

        workerDispatcher.runAll()

        assertEquals(EditorDocumentStatus.Ready, state.status)
        assertEquals(1, document.viewportCalls.size)
        assertEquals(listOf("deferred"), state.blocks.map { block -> block.block.text })

        session.close()
    }

    /** Verifies one close request waits for retained work and is consumed exactly once. */
    @Test
    fun retainsCloseRequestUntilTheDocumentIsReady() {
        val document = TestEditorDocument("deferred")
        val workerDispatcher = QueuedSessionTestDispatcher()
        val state = EditorDocumentState(document, workerDispatcher)
        val session =
            EditorSession(
                title = "Test document",
                state = state,
                operationDispatcher = ImmediateSessionTestDispatcher
            )
        session.openInitialEditor()

        val closeResult = session.requestClose()

        assertEquals(CloseRequestResult.Queued, closeResult)
        assertTrue(session.isClosePending)
        assertFalse(session.canCloseSafely)
        assertFalse(session.canStartSaveAs)
        assertNull(session.resolvePendingClose())

        workerDispatcher.runAll()

        assertTrue(session.canCloseSafely)
        assertEquals(CloseRequestResult.CloseNow, session.resolvePendingClose())
        assertFalse(session.isClosePending)
        assertNull(session.resolvePendingClose())
        session.close()
    }

    /** Verifies cancelling a queued close leaves later completion in the editor. */
    @Test
    fun cancelsAQueuedCloseBeforeTheDocumentIsReady() {
        val document = TestEditorDocument("deferred")
        val workerDispatcher = QueuedSessionTestDispatcher()
        val state = EditorDocumentState(document, workerDispatcher)
        val session =
            EditorSession(
                title = "Test document",
                state = state,
                operationDispatcher = ImmediateSessionTestDispatcher
            )
        session.openInitialEditor()
        assertEquals(CloseRequestResult.Queued, session.requestClose())

        session.cancelPendingClose()
        workerDispatcher.runAll()

        assertFalse(session.isClosePending)
        assertNull(session.resolvePendingClose())
        assertEquals(EditorDocumentStatus.Ready, state.status)
        session.close()
    }

    /** Verifies a safe dirty close opens the retained discard confirmation immediately. */
    @Test
    fun confirmsBeforeClosingUnsavedChanges() {
        val document = TestEditorDocument("before")
        val session = createSession(document)
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "after")
        }
        session.updateActiveDraftStatus(draft)

        val result = session.requestClose()

        assertEquals(CloseRequestResult.ConfirmationShown, result)
        assertTrue(session.isDiscardConfirmationVisible)
        assertFalse(session.isClosePending)
        session.close()
    }

    /** Verifies repeated close requests cannot bypass one dirty-session guard. */
    @Test
    fun keepsRepeatedDirtyCloseRequestsBehindConfirmation() {
        val document = TestEditorDocument("before")
        val session = createSession(document)
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "after")
        }

        val firstResult = session.requestClose()
        val secondResult = session.requestClose()

        assertEquals(CloseRequestResult.ConfirmationShown, firstResult)
        assertEquals(CloseRequestResult.ConfirmationShown, secondResult)
        assertTrue(session.isDiscardConfirmationVisible)
        assertTrue(session.hasUnsavedChanges)
        assertEquals("after", draft.textFieldState.text.toString())
        session.close()
    }

    /** Verifies Save from close confirmation waits for the new source autosave. */
    @Test
    fun savesUnsourcedChangesBeforeClosing() {
        val document = TestEditorDocument("before")
        val source = TestEditorDocumentSource()
        val session = createSession(document)
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "after")
        }
        session.updateActiveDraftStatus(draft)
        assertEquals(CloseRequestResult.ConfirmationShown, session.requestClose())

        assertTrue(session.canSaveBeforeClose)
        assertTrue(session.saveBeforeClose())
        assertTrue(session.isSaveBeforeClosePending)
        session.selectSaveFormat(DocumentFormat.PlainText)
        assertTrue(session.isClosePending)
        val request = session.claimPreparedSaveDestination()

        assertTrue(session.saveSelectedSource(request, source, "saved.txt"))

        assertEquals(listOf("after"), source.savedTexts)
        assertEquals(SourceSaveStatus.Saved, session.sourceSaveStatus)
        assertFalse(session.hasUnsavedChanges)
        assertFalse(session.isSaveBeforeClosePending)
        assertEquals(CloseRequestResult.CloseNow, session.resolvePendingClose())
        session.close()
    }

    /** Verifies cancelling the Save picker cancels close without discarding text. */
    @Test
    fun keepsEditingWhenSaveBeforeCloseIsCancelled() {
        val document = TestEditorDocument("before")
        val session = createSession(document)
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "after")
        }
        session.updateActiveDraftStatus(draft)
        assertEquals(CloseRequestResult.ConfirmationShown, session.requestClose())
        assertTrue(session.saveBeforeClose())
        session.selectSaveFormat(DocumentFormat.PlainText)
        val request = requireNotNull(session.claimSaveDestination())

        assertTrue(session.cancelSaveDestination(request))

        assertFalse(session.isClosePending)
        assertFalse(session.isSaveBeforeClosePending)
        assertTrue(session.hasUnsavedChanges)
        assertNull(session.resolvePendingClose())
        session.close()
    }

    /** Verifies close cancels queued work and releases every retained owner once. */
    @Test
    fun closesExactlyOnceAndCancelsQueuedOperations() {
        val document = TestEditorDocument("temporary")
        val workerDispatcher = QueuedSessionTestDispatcher()
        val state = EditorDocumentState(document, workerDispatcher)
        val session =
            EditorSession(
                title = "Test document",
                state = state,
                operationDispatcher = ImmediateSessionTestDispatcher
            )
        session.openInitialEditor()
        session.showDiscardConfirmation()

        session.close()
        session.close()
        workerDispatcher.runAll()
        session.openInitialEditor()

        assertEquals(1, document.closeCallCount)
        assertTrue(document.viewportCalls.isEmpty())
        assertEquals(EditorDocumentStatus.Closed, state.status)
        assertNull(session.activeDraft)
        assertFalse(session.isDiscardConfirmationVisible)
        assertFalse(session.isClosePending)
    }

    /** Verifies a queued replacement cannot publish state after the session closes. */
    @Test
    fun keepsClosedStateAfterAQueuedReplacementResumes() {
        val document = TestEditorDocument("before")
        val workerDispatcher = QueuedSessionTestDispatcher()
        val state = EditorDocumentState(document, workerDispatcher)
        val session =
            EditorSession(
                title = "Test document",
                state = state,
                operationDispatcher = ImmediateSessionTestDispatcher,
                editSynchronizationDelay = {}
            )
        session.openInitialEditor()
        workerDispatcher.runAll()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "after")
        }

        session.observeActiveEdit(draft, draft.captureFieldValue())

        assertEquals(EditorDocumentStatus.ApplyingEdit, state.status)
        assertEquals(1, workerDispatcher.pendingCount)
        session.close()
        workerDispatcher.runAll()

        assertEquals(EditorDocumentStatus.Closed, state.status)
        assertNull(session.activeDraft)
        assertEquals(1, document.closeCallCount)
        assertTrue(document.replaceCalls.isEmpty())
    }

    /** Verifies a source-backed Markdown document shares its exact provider capability. */
    @Test
    fun sharesTheRetainedSourceWithoutCapturingText() {
        val document = TestEditorDocument("source text")
        val session =
            createSession(
                document = document,
                documentSource = TestEditorDocumentSource(RETAINED_SOURCE_URI),
                title = "notes.md"
            )
        session.openInitialEditor()

        assertTrue(session.requestShare())

        val request = (session.shareStatus as ShareStatus.Ready).request
        val payload = request.payload as DocumentSharePayload.Source
        assertEquals(RETAINED_SOURCE_URI, payload.encodedUri)
        assertEquals("notes.md", payload.title)
        assertEquals(DocumentFormat.Markdown, payload.format)
        assertTrue(document.capturedSnapshots.isEmpty())
        assertTrue(session.completeShareLaunch(request.generation))
        assertEquals(ShareStatus.Idle, session.shareStatus)
        session.close()
    }

    /** Verifies source sharing waits until the newest active draft is autosaved. */
    @Test
    fun autosavesTheLatestDraftBeforeSharingItsSource() {
        val document = TestEditorDocument("before")
        val source = TestEditorDocumentSource(RETAINED_SOURCE_URI)
        val session =
            createSession(
                document = document,
                documentSource = source,
                title = "notes.md"
            )
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "after")
        }

        assertTrue(session.requestShare())

        val payload =
            (session.shareStatus as ShareStatus.Ready).request.payload as
                DocumentSharePayload.Source
        assertEquals(RETAINED_SOURCE_URI, payload.encodedUri)
        assertEquals(listOf("after"), source.savedTexts)
        assertFalse(session.hasUnsavedChanges)
        session.close()
    }

    /** Verifies a transient document shares only one exact bounded text snapshot. */
    @Test
    fun preparesTransientTextForSharing() {
        val document = TestEditorDocument("private text")
        val session =
            createSession(
                document = document,
                sharedTextReader = { snapshot, expectedBytes ->
                    val testSnapshot = snapshot as TestEditorDocumentSnapshot
                    assertEquals(testSnapshot.byteLength, expectedBytes)
                    testSnapshot.copyUtf8Bytes().decodeToString()
                }
            )
        session.openInitialEditor()

        assertTrue(session.requestShare())

        val request = (session.shareStatus as ShareStatus.Ready).request
        val payload = request.payload as DocumentSharePayload.Text
        assertEquals("private text", payload.text)
        assertEquals(DocumentFormat.PlainText, payload.format)
        assertEquals(1, document.capturedSnapshots.size)
        assertTrue(document.capturedSnapshots.single().isClosed)
        session.close()
    }

    /** Verifies sharing synchronizes the latest active draft before snapshot capture. */
    @Test
    fun sharesTheLatestActiveDraft() {
        val document = TestEditorDocument("before")
        val session =
            createSession(
                document = document,
                sharedTextReader = { snapshot, _ ->
                    (snapshot as TestEditorDocumentSnapshot).text
                }
            )
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "after")
        }

        assertTrue(session.requestShare())

        val payload =
            (session.shareStatus as ShareStatus.Ready).request.payload as DocumentSharePayload.Text
        assertEquals("after", payload.text)
        assertEquals("after", document.text)
        session.close()
    }

    /** Verifies oversized transient text requires a user-selected file before sharing. */
    @Test
    fun rejectsOversizedTransientTextSharing() {
        val document =
            TestEditorDocument("small") { metrics ->
                metrics.copy(serializedByteLength = MAX_SHARED_TEXT_UTF8_BYTES + 1L)
            }
        val session = createSession(document)
        session.openInitialEditor()

        assertTrue(session.requestShare())

        val failure = session.shareStatus as ShareStatus.Failed
        assertEquals(
            UiText.Resource(R.string.operation_share_text_too_large),
            failure.message
        )
        session.dismissShareFailure(failure.generation)
        assertEquals(ShareStatus.Idle, session.shareStatus)
        session.close()
    }

    /** Verifies printing captures the newest draft without materializing its text in Kotlin. */
    @Test
    fun preparesTheLatestActiveDraftForPrinting() {
        val document = TestEditorDocument("before")
        val session = createSession(document)
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "after")
        }

        assertTrue(session.requestPrint())

        val ready = session.printStatus as PrintStatus.Ready
        val snapshot = document.capturedSnapshots.single()
        assertEquals("after", snapshot.text)
        assertFalse(snapshot.isClosed)
        assertEquals("Test document", ready.request.title)
        assertFalse(session.canStartShare)
        assertFalse(session.canStartSaveAs)

        session.cancelPrint()

        assertEquals(PrintStatus.Idle, session.printStatus)
        assertTrue(snapshot.isClosed)
        assertTrue(session.canStartPrint)
        session.close()
    }

    /** Verifies Markdown print setup remains transient until explicitly confirmed. */
    @Test
    fun retainsOnlyVisibleMarkdownPrintSetup() {
        val session =
            createSession(
                document = TestEditorDocument("# Heading"),
                title = "notes.md",
                markdownRenderer = MarkdownRenderer { _, expectedBytes ->
                    emptyMarkdownDocument(expectedBytes)
                }
            )
        session.openInitialEditor()

        assertTrue(session.showPrintSetup())
        val draft = requireNotNull(session.printSetupDraft)
        assertEquals(PrintContentMode.FormattedMarkdown, draft.contentMode)
        assertEquals(PrintStatus.Idle, session.printStatus)
        session.updatePrintSetup(draft.copy(topMargin = "invalid"))
        assertFalse(session.confirmPrintSetup())
        assertEquals("invalid", session.printSetupDraft?.topMargin)
        assertEquals(PrintStatus.Idle, session.printStatus)
        session.updatePrintSetup(requireNotNull(session.printSetupDraft).copy(topMargin = "0.75"))
        assertEquals("0.75", session.printSetupDraft?.topMargin)
        val validDraft = requireNotNull(session.printSetupDraft)
        session.updatePrintSetup(validDraft.copy(topMargin = "0".repeat(40)))
        assertSame(validDraft, session.printSetupDraft)
        session.updatePrintSetup(validDraft.copy(fontSize = "0".repeat(40)))
        assertSame(validDraft, session.printSetupDraft)

        session.dismissPrintSetup()

        assertNull(session.printSetupDraft)
        assertEquals(PrintStatus.Idle, session.printStatus)
        session.close()
    }

    /** Verifies formatted printing releases its exact snapshot after isolated rendering. */
    @Test
    fun preparesFormattedMarkdownWithoutRetainingItsSnapshot() {
        var renderedBytes: Long? = null
        val document = TestEditorDocument("# Heading")
        val session =
            createSession(
                document = document,
                title = "notes.md",
                markdownRenderer = MarkdownRenderer { snapshot, expectedBytes ->
                    assertFalse((snapshot as TestEditorDocumentSnapshot).isClosed)
                    renderedBytes = expectedBytes
                    emptyMarkdownDocument(expectedBytes)
                }
            )
        session.openInitialEditor()

        assertTrue(session.requestPrint(defaultPrintSettings(formattedMarkdown = true)))

        val ready = session.printStatus as PrintStatus.Ready
        assertEquals("# Heading".toByteArray().size.toLong(), renderedBytes)
        assertTrue(document.capturedSnapshots.single().isClosed)

        session.cancelPrint()

        assertEquals(PrintStatus.Idle, session.printStatus)
        ready.request.close()
        session.close()
    }

    @Test
    fun releasesFormattedPrintCaptureOnceWhenClosingItFails() {
        val delegate = TestEditorDocument("# Heading")
        val document = object : EditorDocument by delegate {
            override fun captureSnapshot(expectedRevision: Long): EditorDocumentSnapshot {
                val snapshot = delegate.captureSnapshot(expectedRevision)
                return object : EditorDocumentSnapshot by snapshot {
                    override fun close() {
                        snapshot.close()
                        error("synthetic print capture release failure")
                    }
                }
            }
        }
        val session = createSession(
            document = document,
            title = "notes.md",
            markdownRenderer = MarkdownRenderer { _, bytes -> emptyMarkdownDocument(bytes) }
        )
        session.openInitialEditor()

        assertTrue(session.requestPrint(defaultPrintSettings(formattedMarkdown = true)))

        assertTrue(session.printStatus is PrintStatus.Failed)
        assertEquals(1, delegate.capturedSnapshots.single().closeCallCount)
        session.close()
        assertEquals(1, delegate.capturedSnapshots.single().closeCallCount)
    }

    /** Verifies cancellation before snapshot capture leaves no retained capability. */
    @Test
    fun cancelsQueuedPrintPreparation() {
        val document = TestEditorDocument("private text")
        val operationDispatcher = QueuedSessionTestDispatcher()
        val session =
            EditorSession(
                title = "Test document",
                state = EditorDocumentState(document, ImmediateSessionTestDispatcher),
                operationDispatcher = operationDispatcher,
                editSynchronizationDelay = {}
            )
        session.openInitialEditor()
        operationDispatcher.runAll()

        assertTrue(session.requestPrint())
        assertTrue(session.printStatus is PrintStatus.Preparing)

        session.cancelPrint()
        operationDispatcher.runAll()

        assertEquals(PrintStatus.Idle, session.printStatus)
        assertTrue(document.capturedSnapshots.isEmpty())
        session.close()
    }

    /** Verifies closing a ready session releases its unlaunched print revision. */
    @Test
    fun closesAnUnlaunchedPrintRevision() {
        val document = TestEditorDocument("private text")
        val session = createSession(document)
        session.openInitialEditor()

        assertTrue(session.requestPrint())
        val snapshot = document.capturedSnapshots.single()
        assertFalse(snapshot.isClosed)

        session.close()

        assertTrue(snapshot.isClosed)
        assertEquals(PrintStatus.Idle, session.printStatus)
    }

    /** Verifies a failed print release cannot strand the document or source owner. */
    @Test
    fun closesTheSessionWhenItsPrintSnapshotReleaseFails() {
        val delegate = TestEditorDocument("private text")
        val failure = IllegalStateException("synthetic snapshot close failure")
        val document = object : EditorDocument by delegate {
            override fun captureSnapshot(expectedRevision: Long): EditorDocumentSnapshot {
                val snapshot = delegate.captureSnapshot(expectedRevision)
                return object : EditorDocumentSnapshot by snapshot {
                    override fun close() {
                        snapshot.close()
                        throw failure
                    }
                }
            }
        }
        val source = TestEditorDocumentSource()
        val session = createSession(document, documentSource = source)
        session.openInitialEditor()
        assertTrue(session.requestPrint())

        assertSame(failure, assertThrows(IllegalStateException::class.java, session::close))
        session.close()

        assertEquals(1, delegate.capturedSnapshots.single().closeCallCount)
        assertEquals(1, delegate.closeCallCount)
        assertEquals(1, source.closeCallCount)
        assertEquals(EditorDocumentStatus.Closed, session.state.status)
        assertNull(session.activeDraft)
        assertEquals(PrintStatus.Idle, session.printStatus)
    }

    /** Verifies a provider-owned view-only document remains printable. */
    @Test
    fun preparesAViewOnlyDocumentForPrinting() {
        val document = TestEditorDocument("read-only text")
        val session =
            createSession(
                document = document,
                documentSource = TestReadOnlyEditorDocumentSource()
            )
        session.openInitialEditor()

        assertTrue(session.isViewOnly)
        assertTrue(session.canStartPrint)
        assertTrue(session.requestPrint())
        assertTrue(session.printStatus is PrintStatus.Ready)
        assertEquals("read-only text", document.capturedSnapshots.single().text)

        session.cancelPrint()
        session.close()
    }

    /** Verifies a transient Markdown revision becomes one exact bounded QR grid. */
    @Test
    fun preparesTransientMarkdownForQrSharing() {
        val document = TestEditorDocument("# Private")
        val processor = TestQrTransferProcessor()
        val session =
            createSession(
                document = document,
                title = "notes.md",
                qrTransferProcessor = processor
            )
        session.openInitialEditor()

        assertTrue(session.requestQrShare())

        val ready = session.qrShareStatus as QrShareStatus.Ready
        assertSame(processor.grid, ready.grid)
        assertEquals("# Private".toByteArray().size.toLong(), ready.textBytes)
        assertEquals(DocumentFormat.Markdown, ready.format)
        assertEquals(listOf("# Private"), processor.encodedTexts)
        assertEquals(listOf(DocumentFormat.Markdown), processor.encodedFormats)
        assertTrue(document.capturedSnapshots.single().isClosed)
        session.dismissQrShare(ready.generation)
        assertEquals(QrShareStatus.Idle, session.qrShareStatus)
        session.close()
    }

    /** Verifies QR sharing autosaves and encodes the newest active source revision. */
    @Test
    fun autosavesTheLatestDraftBeforeQrSharing() {
        val document = TestEditorDocument("before")
        val source = TestEditorDocumentSource(RETAINED_SOURCE_URI)
        val processor = TestQrTransferProcessor()
        val session =
            createSession(
                document = document,
                documentSource = source,
                title = "notes.txt",
                qrTransferProcessor = processor
            )
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "after")
        }

        assertTrue(session.requestQrShare())

        assertTrue(session.qrShareStatus is QrShareStatus.Ready)
        assertEquals(listOf("after"), source.savedTexts)
        assertEquals(listOf("after"), processor.encodedTexts)
        assertEquals(listOf("after".toByteArray().size.toLong()), processor.encodedByteCounts)
        assertFalse(session.hasUnsavedChanges)
        session.close()
    }

    /** Verifies oversized documents disable QR creation before a request. */
    @Test
    fun disablesOversizedQrSharing() {
        val document =
            TestEditorDocument("small") { metrics ->
                metrics.copy(
                    serializedByteLength = TransferProtocol.MAX_QR_TEXT_BYTES + 1L
                )
            }
        val processor = TestQrTransferProcessor()
        val session = createSession(document = document, qrTransferProcessor = processor)
        session.openInitialEditor()

        assertEquals(
            TransferProtocol.MAX_QR_TEXT_BYTES + 1L,
            session.qrShareCapacity.textBytes
        )
        assertEquals(false, session.qrShareCapacity.fits)
        assertFalse(session.canStartQrShare)
        assertFalse(session.requestQrShare())
        assertEquals(QrShareStatus.Idle, session.qrShareStatus)
        assertTrue(processor.encodedTexts.isEmpty())
        assertTrue(document.capturedSnapshots.isEmpty())
        session.close()
    }

    /** Verifies flushing a draft publishes exact QR and NFC menu capacity. */
    @Test
    fun publishesTransferCapacityAfterDraftSynchronization() {
        val document = TestEditorDocument("small")
        val session =
            createSession(
                document = document,
                qrTransferProcessor = TestQrTransferProcessor(),
                nfcTransferProcessor = TestNfcTransferProcessor()
            )
        session.openInitialEditor()
        val oversizedText = "x".repeat(TransferProtocol.MAX_QR_TEXT_BYTES.toInt() + 1)
        requireNotNull(session.activeDraft).textFieldState.edit {
            replace(start = 0, end = length, text = oversizedText)
        }

        assertNull(session.qrShareCapacity.textBytes)
        assertNull(session.nfcWriteCapacity.textBytes)

        session.flushPendingEdit()

        assertEquals(oversizedText.length.toLong(), session.qrShareCapacity.textBytes)
        assertEquals(false, session.qrShareCapacity.fits)
        assertEquals(true, session.nfcWriteCapacity.fits)
        assertFalse(session.canStartQrShare)
        assertTrue(session.canStartNfcWrite)
        session.close()
    }

    /** Verifies a transient Markdown revision becomes one bounded NFC envelope. */
    @Test
    fun preparesTransientMarkdownForNfcWriting() {
        val document = TestEditorDocument("# Private")
        val processor = TestNfcTransferProcessor()
        val session =
            createSession(
                document = document,
                title = "notes.md",
                nfcTransferProcessor = processor
            )
        session.openInitialEditor()

        assertTrue(session.requestNfcWrite())
        val configuring = session.nfcWriteStatus as NfcWriteStatus.Configuring
        assertTrue(session.confirmNfcWriteConfiguration(configuring.generation, "A01"))

        val ready = session.nfcWriteStatus as NfcWriteStatus.Ready
        assertSame(processor.envelope, ready.envelope)
        assertEquals("# Private".toByteArray().size.toLong(), ready.textBytes)
        assertEquals(DocumentFormat.Markdown, ready.format)
        assertEquals("A01", ready.tagLabel)
        assertEquals(listOf("# Private"), processor.encodedTexts)
        assertEquals(listOf(DocumentFormat.Markdown), processor.encodedFormats)
        assertEquals(listOf("A01"), processor.encodedTagLabels)
        assertTrue(document.capturedSnapshots.single().isClosed)
        assertTrue(session.completeNfcWrite(ready.generation))
        assertThrows(IllegalStateException::class.java) {
            ready.envelope.copyBytes()
        }
        val succeeded = session.nfcWriteStatus as NfcWriteStatus.Succeeded
        session.dismissNfcWriteResult(succeeded.generation)
        assertEquals(NfcWriteStatus.Idle, session.nfcWriteStatus)
        session.close()
    }

    /** Verifies NFC writing autosaves and encodes the newest active source revision. */
    @Test
    fun autosavesTheLatestDraftBeforeNfcWriting() {
        val document = TestEditorDocument("before")
        val source = TestEditorDocumentSource(RETAINED_SOURCE_URI)
        val processor = TestNfcTransferProcessor()
        val session =
            createSession(
                document = document,
                documentSource = source,
                title = "notes.txt",
                nfcTransferProcessor = processor
            )
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "after")
        }

        assertTrue(session.requestNfcWrite())
        val configuring = session.nfcWriteStatus as NfcWriteStatus.Configuring
        assertTrue(session.confirmNfcWriteConfiguration(configuring.generation, null))

        assertTrue(session.nfcWriteStatus is NfcWriteStatus.Ready)
        assertEquals(listOf("after"), source.savedTexts)
        assertEquals(listOf("after"), processor.encodedTexts)
        assertEquals(listOf("after".toByteArray().size.toLong()), processor.encodedByteCounts)
        assertEquals(listOf(null), processor.encodedTagLabels)
        assertFalse(session.hasUnsavedChanges)
        session.close()
    }

    /** Verifies oversized documents disable NFC writing before a request. */
    @Test
    fun disablesOversizedNfcWriting() {
        val document =
            TestEditorDocument("small") { metrics ->
                metrics.copy(
                    serializedByteLength = TransferProtocol.MAX_NFC_TEXT_BYTES + 1L
                )
            }
        val processor = TestNfcTransferProcessor()
        val session = createSession(document = document, nfcTransferProcessor = processor)
        session.openInitialEditor()

        assertEquals(
            TransferProtocol.MAX_NFC_TEXT_BYTES + 1L,
            session.nfcWriteCapacity.textBytes
        )
        assertEquals(false, session.nfcWriteCapacity.fits)
        assertFalse(session.canStartNfcWrite)
        assertFalse(session.requestNfcWrite())
        assertEquals(NfcWriteStatus.Idle, session.nfcWriteStatus)
        assertTrue(processor.encodedTexts.isEmpty())
        assertTrue(document.capturedSnapshots.isEmpty())
        session.close()
    }

    /** Claims and begins one simulated picker result preparation. */
    private fun EditorSession.claimPreparedSaveDestination(): SaveDestinationRequest {
        val request = requireNotNull(claimSaveDestination())
        assertTrue(beginSaveDestinationPreparation(request))
        return request
    }

    /** Runs one assertion block against a complete active draft and closes its session. */
    private fun withActiveDraft(assertions: (ActiveEditDraft) -> Unit) {
        val session = createSession(TestEditorDocument(FOCUS_SELECTION_TEXT))
        session.openInitialEditor()
        try {
            assertions(requireNotNull(session.activeDraft))
        } finally {
            session.close()
        }
    }

    /** Creates one synchronously dispatched editor session for a test document. */
    private fun createSession(
        document: EditorDocument,
        documentSource: EditorDocumentSource? = null,
        sourceMetadata: SelectedDocumentMetadata? = null,
        findDelay: suspend () -> Unit = {},
        title: String = "Test document",
        sharedTextReader: suspend (EditorDocumentSnapshot, Long) -> String = { snapshot, _ ->
            (snapshot as TestEditorDocumentSnapshot).text
        },
        markdownRenderer: MarkdownRenderer? = null,
        qrTransferProcessor: QrTransferProcessor? = null,
        nfcTransferProcessor: NfcTransferProcessor? = null,
        viewportListState: LazyListState = LazyListState(),
        editSynchronizationDelay: suspend () -> Unit = {}
    ): EditorSession = EditorSession(
        title = title,
        state = EditorDocumentState(document, ImmediateSessionTestDispatcher),
        documentSource = documentSource,
        sourceMetadata = sourceMetadata,
        viewportListState = viewportListState,
        operationDispatcher = ImmediateSessionTestDispatcher,
        editSynchronizationDelay = editSynchronizationDelay,
        findDelay = findDelay,
        markdownRenderer = markdownRenderer,
        sharedTextReader = sharedTextReader,
        qrTransferProcessor = qrTransferProcessor,
        nfcTransferProcessor = nfcTransferProcessor
    )
}

/** Creates one empty bounded Markdown model for print-session tests. */
private fun emptyMarkdownDocument(inputBytes: Long): MarkdownPreviewDocument =
    MarkdownPreviewDocument(
        inputByteLength = inputBytes,
        blocks = emptyList(),
        spanCount = 0,
        containsRawHtml = false
    )
