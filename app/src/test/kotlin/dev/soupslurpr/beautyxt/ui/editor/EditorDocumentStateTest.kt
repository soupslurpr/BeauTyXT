package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.document.DocumentLineEnding
import dev.soupslurpr.beautyxt.document.DocumentMetrics
import dev.soupslurpr.beautyxt.document.EditWindowLimits
import dev.soupslurpr.beautyxt.document.EditWindowSnapshot
import dev.soupslurpr.beautyxt.document.EditorDocument
import dev.soupslurpr.beautyxt.document.FindBatch
import dev.soupslurpr.beautyxt.document.FindDirection
import dev.soupslurpr.beautyxt.document.FindMatch
import dev.soupslurpr.beautyxt.document.FindRequest
import dev.soupslurpr.beautyxt.document.RenderBlock
import dev.soupslurpr.beautyxt.document.StaleDocumentRevisionException
import dev.soupslurpr.beautyxt.document.Utf16Range
import dev.soupslurpr.beautyxt.document.ViewportCursor
import dev.soupslurpr.beautyxt.document.ViewportLimits
import dev.soupslurpr.beautyxt.document.ViewportSnapshot
import dev.soupslurpr.beautyxt.document.hasWellFormedUtf16
import dev.soupslurpr.beautyxt.testing.TestEditorDocument
import dev.soupslurpr.beautyxt.testing.TestEditorDocumentSnapshot
import dev.soupslurpr.beautyxt.ui.UiText
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val INITIAL_REVISION = 0L
private const val FIRST_EDIT_REVISION = 1L
private const val SECOND_EDIT_REVISION = 2L
private const val EXTERNAL_REVISION = 7L
private const val TEST_VIEWPORT_PAGE_COUNT = 10
private const val TEST_BOUNDARY_SHIFT_UTF16_UNITS = 1L
private const val TEST_METRICS_BYTE_DELTA = 1L
private const val TEST_SAME_LINE_PAGE_UTF16_UNITS = 4
private const val TEST_FIND_BATCH_UTF16_UNITS = 8
private val TEST_VIEWPORT_FAILURE_MESSAGE = UiText.Resource(R.string.operation_load_section_failed)
private val TEST_FIND_FAILURE_MESSAGE = UiText.Resource(R.string.operation_find_failure)
private val TEST_MATCH_VIEWPORT_FAILURE_MESSAGE = UiText.Resource(
    R.string.operation_match_viewport_failure
)

/** Verifies deterministic state transitions around bounded editable windows. */
class EditorDocumentStateTest {
    /** Verifies a large transient transfer is appended in scalar-safe edit-sized chunks. */
    @Test
    fun appendsLargeTransientTextAcrossUnicodeBoundaries() {
        val originalText =
            "x".repeat(EDIT_DRAFT_MAX_UTF16_UNITS - 1) +
                "😀" +
                "y".repeat(EDIT_DRAFT_MAX_UTF16_UNITS)
        val document = TestEditorDocument("")

        val metrics = appendTransientText(document = document, text = originalText)

        assertEquals(originalText, document.text)
        assertEquals(originalText.length.toLong(), metrics.utf16Length)
        assertEquals(3, document.replaceCalls.size)
        assertTrue(
            document.replaceCalls.all { call ->
                call.replacement.length <= EDIT_DRAFT_MAX_UTF16_UNITS &&
                    call.replacement.hasWellFormedUtf16()
            }
        )
    }

    /** Verifies an ordinary document opens as one complete editable field. */
    @Test
    fun activatesACompleteOrdinaryDocument() = runBlocking {
        val originalText = "one\ntwo\nthree"
        val document = FakeEditorDocument(originalText)
        val state = EditorDocumentState(document, ImmediateTestDispatcher)

        state.loadInitialViewport()
        state.activateDocumentAt(Utf16Range(start = 0L, end = 0L))

        val activeEdit = requireNotNull(state.activeEdit)
        assertEquals(originalText, activeEdit.snapshot.text)
        assertFalse(activeEdit.snapshot.hasPrevious)
        assertFalse(activeEdit.snapshot.hasNext)
        assertEquals(Utf16Range(start = 0, end = 0), activeEdit.snapshot.selection)
        assertEquals(1, document.editWindowCalls.size)
    }

    /** Verifies an exact source caret reaches the bounded editor unchanged. */
    @Test
    fun activatesAWindowAtAnExactSourceCaret() = runBlocking {
        val originalText = "prefix\none 😀 tail\nsuffix"
        val document = FakeEditorDocument(originalText)
        val state = EditorDocumentState(document, ImmediateTestDispatcher)
        state.loadInitialViewport()
        val caret = "prefix\none 😀".length.toLong()
        val expectedSelection = Utf16Range(start = caret, end = caret)

        state.activateDocumentAt(expectedSelection)

        assertEquals(expectedSelection, document.editWindowCalls.single().selection)
        assertEquals(expectedSelection, requireNotNull(state.activeEdit).snapshot.selection)
    }

    /** Verifies random-line navigation replaces the cache with one exact bounded page. */
    @Test
    fun navigatesToAnExactLogicalLine() = runBlocking {
        val originalText = pagedDocumentText(TEST_VIEWPORT_PAGE_COUNT)
        val targetLogicalLine = (TEST_VIEWPORT_PAGE_COUNT - 3).toLong()
        val document = FakeEditorDocument(originalText)
        document.viewportOverride = { cursor, _ ->
            forwardPageSnapshot(
                text = originalText,
                revision = INITIAL_REVISION,
                pageIndex = cursor.line.toInt()
            )
        }
        val state = EditorDocumentState(document, ImmediateTestDispatcher)
        state.loadInitialViewport()
        val initialViewportLimits = document.viewportCalls.single().limits

        val published = state.navigateToLine(targetLogicalLine)

        assertTrue(published)
        assertEquals(EditorDocumentStatus.Ready, state.status)
        assertSame(LineViewportStatus.Idle, state.lineViewportStatus)
        assertEquals(
            listOf(targetLogicalLine),
            state.blocks.map { editorBlock -> editorBlock.block.logicalLine }
        )
        assertEquals(
            ViewportCursor(
                revision = INITIAL_REVISION,
                line = targetLogicalLine,
                utf16Offset = 0L
            ),
            document.viewportCalls.last().cursor
        )
        assertEquals(initialViewportLimits, document.viewportCalls.last().limits)
        assertEquals("0:$targetLogicalLine:0", state.previousPaginationKey)
        assertEquals("0:${targetLogicalLine + 1L}:0", state.paginationKey)
    }

    /** Verifies unavailable and invalid logical-line requests never reach the document. */
    @Test
    fun rejectsUnavailableOrInvalidLineNavigation() = runBlocking {
        val originalText = pagedDocumentText(TEST_VIEWPORT_PAGE_COUNT)
        val targetLogicalLine = 1L
        val document = FakeEditorDocument(originalText)
        document.viewportOverride = { cursor, _ ->
            forwardPageSnapshot(
                text = originalText,
                revision = INITIAL_REVISION,
                pageIndex = cursor.line.toInt()
            )
        }
        val state = EditorDocumentState(document, ImmediateTestDispatcher)

        assertFalse(state.navigateToLine(targetLogicalLine))
        assertTrue(document.viewportCalls.isEmpty())
        state.loadInitialViewport()
        val viewportCallCount = document.viewportCalls.size
        assertFalse(state.navigateToLine(TEST_VIEWPORT_PAGE_COUNT.toLong()))
        assertEquals(viewportCallCount, document.viewportCalls.size)
        state.activateDocumentAt(Utf16Range(start = 0L, end = 0L))
        assertFalse(state.navigateToLine(targetLogicalLine))
        assertEquals(viewportCallCount, document.viewportCalls.size)
        assertSame(LineViewportStatus.Idle, state.lineViewportStatus)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                state.navigateToLine(-1L)
            }
        }
        assertEquals(viewportCallCount, document.viewportCalls.size)
    }

    /** Verifies a failed line jump retains the visible cache throughout the request. */
    @Test
    fun retainsVisibleContentWhenLineNavigationFails() = runBlocking {
        val originalText = pagedDocumentText(TEST_VIEWPORT_PAGE_COUNT)
        val targetLogicalLine = (TEST_VIEWPORT_PAGE_COUNT - 2).toLong()
        val document = FakeEditorDocument(originalText)
        document.viewportOverride = { cursor, _ ->
            forwardPageSnapshot(
                text = originalText,
                revision = INITIAL_REVISION,
                pageIndex = cursor.line.toInt()
            )
        }
        val state = EditorDocumentState(document, ImmediateTestDispatcher)
        state.loadInitialViewport()
        val retainedBlocks = state.blocks
        val retainedMetrics = state.metrics
        val retainedPreviousPaginationKey = state.previousPaginationKey
        val retainedPaginationKey = state.paginationKey
        var observedLoadingState = false
        document.onViewport = {
            if (document.viewportCalls.last().cursor.line == targetLogicalLine) {
                observedLoadingState = true
                assertEquals(EditorDocumentStatus.LoadingMore, state.status)
                assertEquals(
                    LineViewportStatus.Loading(targetLogicalLine),
                    state.lineViewportStatus
                )
                assertSame(retainedBlocks, state.blocks)
                assertSame(retainedMetrics, state.metrics)
            }
        }
        document.viewportOverride = { cursor, limits ->
            if (cursor.line == targetLogicalLine) {
                error("test line viewport failure")
            }
            forwardPageSnapshot(
                text = originalText,
                revision = INITIAL_REVISION,
                pageIndex = cursor.line.toInt()
            ).also { snapshot ->
                assertEquals(document.viewportCalls.first().limits, limits)
                assertEquals(INITIAL_REVISION, snapshot.metrics.revision)
            }
        }

        val published = state.navigateToLine(targetLogicalLine)

        assertFalse(published)
        assertTrue(observedLoadingState)
        assertEquals(EditorDocumentStatus.Ready, state.status)
        assertEquals(
            LineViewportStatus.Failed(
                targetLogicalLine = targetLogicalLine,
                message = TEST_VIEWPORT_FAILURE_MESSAGE
            ),
            state.lineViewportStatus
        )
        assertFalse(state.hasPreviousViewportFailure)
        assertFalse(state.hasNextViewportFailure)
        assertFalse(state.canLoadPrevious)
        assertFalse(state.canLoadMore)
        assertTrue(state.canCloseSafely)
        assertSame(retainedBlocks, state.blocks)
        assertSame(retainedMetrics, state.metrics)
        assertEquals(retainedPreviousPaginationKey, state.previousPaginationKey)
        assertEquals(retainedPaginationKey, state.paginationKey)
        val viewportCallCount = document.viewportCalls.size
        assertFalse(state.navigateToLine(targetLogicalLine - 1L))
        assertEquals(viewportCallCount, document.viewportCalls.size)
    }

    /** Verifies dismissing a line failure leaves the retained viewport unchanged. */
    @Test
    fun dismissesALineNavigationFailureWithoutMovingTheViewport() = runBlocking {
        val originalText = pagedDocumentText(TEST_VIEWPORT_PAGE_COUNT)
        val targetLogicalLine = (TEST_VIEWPORT_PAGE_COUNT - 1).toLong()
        val document = FakeEditorDocument(originalText)
        document.viewportOverride = { cursor, _ ->
            if (cursor.line == targetLogicalLine) {
                error("test line viewport failure")
            }
            forwardPageSnapshot(
                text = originalText,
                revision = INITIAL_REVISION,
                pageIndex = cursor.line.toInt()
            )
        }
        val state = EditorDocumentState(document, ImmediateTestDispatcher)
        state.loadInitialViewport()
        assertFalse(state.navigateToLine(targetLogicalLine))
        val retainedBlocks = state.blocks
        val retainedMetrics = state.metrics
        val viewportCallCount = document.viewportCalls.size

        assertTrue(state.dismissLineNavigationFailure())

        assertEquals(EditorDocumentStatus.Ready, state.status)
        assertSame(LineViewportStatus.Idle, state.lineViewportStatus)
        assertSame(retainedBlocks, state.blocks)
        assertSame(retainedMetrics, state.metrics)
        assertEquals(viewportCallCount, document.viewportCalls.size)
        assertTrue(state.canLoadMore)
        assertFalse(state.dismissLineNavigationFailure())
    }

    /** Verifies retry reuses the exact failed random-line request. */
    @Test
    fun retriesTheExactFailedLineNavigationRequest() = runBlocking {
        val originalText = pagedDocumentText(TEST_VIEWPORT_PAGE_COUNT)
        val targetLogicalLine = (TEST_VIEWPORT_PAGE_COUNT / 2).toLong()
        var shouldFail = true
        val document = FakeEditorDocument(originalText)
        document.viewportOverride = { cursor, _ ->
            if (cursor.line == targetLogicalLine && shouldFail) {
                error("test line viewport failure")
            }
            forwardPageSnapshot(
                text = originalText,
                revision = INITIAL_REVISION,
                pageIndex = cursor.line.toInt()
            )
        }
        val state = EditorDocumentState(document, ImmediateTestDispatcher)
        state.loadInitialViewport()
        assertFalse(state.navigateToLine(targetLogicalLine))
        val failedCall = document.viewportCalls.last()
        val failureToken = checkNotNull(state.viewportFailureToken)
        shouldFail = false

        val retryResult = state.retryViewport(failureToken)

        assertEquals(ViewportRetryResult.LineViewportPublished, retryResult)
        assertEquals(failedCall, document.viewportCalls.last())
        assertEquals(EditorDocumentStatus.Ready, state.status)
        assertSame(LineViewportStatus.Idle, state.lineViewportStatus)
        assertEquals(
            listOf(targetLogicalLine),
            state.blocks.map { editorBlock -> editorBlock.block.logicalLine }
        )
    }

    /** Verifies a queued duplicate retry cannot consume a newer line failure. */
    @Test
    fun rejectsAnExpiredLineFailureTokenAfterRetryFailsAgain() = runBlocking {
        val originalText = pagedDocumentText(TEST_VIEWPORT_PAGE_COUNT)
        val targetLogicalLine = (TEST_VIEWPORT_PAGE_COUNT / 2).toLong()
        val retryStarted = CompletableDeferred<Unit>()
        val allowRetryFailure = CompletableDeferred<Unit>()
        var targetRequestCount = 0
        val document = FakeEditorDocument(originalText)
        document.viewportOverride = { cursor, _ ->
            if (cursor.line == targetLogicalLine) {
                targetRequestCount = Math.incrementExact(targetRequestCount)
                if (targetRequestCount == 2) {
                    retryStarted.complete(Unit)
                    runBlocking { allowRetryFailure.await() }
                }
                if (targetRequestCount <= 2) {
                    error("test line viewport failure")
                }
            }
            forwardPageSnapshot(
                text = originalText,
                revision = INITIAL_REVISION,
                pageIndex = cursor.line.toInt()
            )
        }
        val state = EditorDocumentState(document)
        state.loadInitialViewport()
        assertFalse(state.navigateToLine(targetLogicalLine))
        val expiredFailureToken = checkNotNull(state.viewportFailureToken)

        val firstRetry = async { state.retryViewport(expiredFailureToken) }
        retryStarted.await()
        val duplicateRetry = async { state.retryViewport(expiredFailureToken) }
        allowRetryFailure.complete(Unit)

        assertEquals(ViewportRetryResult.NotPublished, firstRetry.await())
        assertEquals(ViewportRetryResult.Unavailable, duplicateRetry.await())
        assertEquals(2, targetRequestCount)
        val currentFailureToken = checkNotNull(state.viewportFailureToken)
        assertFalse(currentFailureToken === expiredFailureToken)
        assertEquals(
            ViewportRetryResult.LineViewportPublished,
            state.retryViewport(currentFailureToken)
        )
        assertEquals(3, targetRequestCount)
    }

    /** Verifies a random-line response cannot publish a different logical line. */
    @Test
    fun rejectsALineNavigationResponseAtTheWrongPosition() = runBlocking {
        val originalText = pagedDocumentText(TEST_VIEWPORT_PAGE_COUNT)
        val targetLogicalLine = (TEST_VIEWPORT_PAGE_COUNT / 2).toLong()
        val wrongLogicalLine = targetLogicalLine + 1L
        val document = FakeEditorDocument(originalText)
        document.viewportOverride = { cursor, _ ->
            val responseLogicalLine =
                if (cursor.line == targetLogicalLine) {
                    wrongLogicalLine
                } else {
                    cursor.line
                }
            forwardPageSnapshot(
                text = originalText,
                revision = INITIAL_REVISION,
                pageIndex = responseLogicalLine.toInt()
            )
        }
        val state = EditorDocumentState(document, ImmediateTestDispatcher)
        state.loadInitialViewport()
        val retainedBlocks = state.blocks
        val retainedMetrics = state.metrics

        val published = state.navigateToLine(targetLogicalLine)

        assertFalse(published)
        assertEquals(
            LineViewportStatus.Failed(
                targetLogicalLine = targetLogicalLine,
                message = TEST_VIEWPORT_FAILURE_MESSAGE
            ),
            state.lineViewportStatus
        )
        assertSame(retainedBlocks, state.blocks)
        assertSame(retainedMetrics, state.metrics)
    }

    /** Verifies a queued line retry cannot replace the viewport under an active edit. */
    @Test
    fun rejectsAQueuedLineRetryAfterAnEditWindowOpens() = runBlocking {
        val originalText = pagedDocumentText(TEST_VIEWPORT_PAGE_COUNT)
        val targetLogicalLine = (TEST_VIEWPORT_PAGE_COUNT - 2).toLong()
        val editWindowRequestStarted = CompletableDeferred<Unit>()
        val allowEditWindowResponse = CompletableDeferred<Unit>()
        var shouldFailLineRequest = true
        val document = FakeEditorDocument(originalText)
        document.viewportOverride = { cursor, _ ->
            if (cursor.line == targetLogicalLine && shouldFailLineRequest) {
                shouldFailLineRequest = false
                error("test line viewport failure")
            }
            forwardPageSnapshot(
                text = originalText,
                revision = INITIAL_REVISION,
                pageIndex = cursor.line.toInt()
            )
        }
        document.editWindowOverride = { revision, selection, _ ->
            editWindowRequestStarted.complete(Unit)
            runBlocking { allowEditWindowResponse.await() }
            editWindowSnapshot(
                text = originalText,
                revision = revision,
                range = Utf16Range(start = 0L, end = originalText.length.toLong()),
                selection = selection
            )
        }
        val state = EditorDocumentState(document)
        state.loadInitialViewport()
        assertFalse(state.navigateToLine(targetLogicalLine))
        val retainedBlocks = state.blocks
        val retainedViewportCallCount = document.viewportCalls.size
        val failureToken = checkNotNull(state.viewportFailureToken)

        val activation = async { state.activateDocumentAt(Utf16Range(start = 0L, end = 0L)) }
        editWindowRequestStarted.await()
        val retry = async { state.retryViewport(failureToken) }
        allowEditWindowResponse.complete(Unit)

        activation.await()
        assertEquals(ViewportRetryResult.Unavailable, retry.await())
        assertTrue(state.activeEdit != null)
        assertEquals(retainedViewportCallCount, document.viewportCalls.size)
        assertSame(retainedBlocks, state.blocks)
        assertEquals(
            LineViewportStatus.Failed(
                targetLogicalLine = targetLogicalLine,
                message = TEST_VIEWPORT_FAILURE_MESSAGE
            ),
            state.lineViewportStatus
        )
    }

    /** Verifies one bounded Find batch returns only after exact defensive validation. */
    @Test
    fun returnsOneValidatedFindBatch() = runBlocking {
        val originalText = "zero needle tail"
        val query = "needle"
        val matchStart = originalText.indexOf(query).toLong()
        val request =
            FindRequest(
                revision = INITIAL_REVISION,
                query = query,
                candidateRange = Utf16Range(start = 0L, end = originalText.length.toLong()),
                direction = FindDirection.Forward,
                maxCandidateUtf16Units = TEST_FIND_BATCH_UTF16_UNITS
            )
        val expectedBatch =
            FindBatch(
                metrics = editableMetrics(originalText, INITIAL_REVISION),
                match =
                    FindMatch(
                        range =
                            Utf16Range(
                                start = matchStart,
                                end = matchStart + query.length
                            ),
                        start =
                            ViewportCursor(
                                revision = INITIAL_REVISION,
                                line = 0L,
                                utf16Offset = matchStart
                            )
                    ),
                remainingCandidateRange = null
            )
        val document = FakeEditorDocument(originalText)
        document.findOverride = { expectedBatch }
        val state = EditorDocumentState(document, ImmediateTestDispatcher)
        state.loadInitialViewport()

        val result = state.findBatch(request)

        assertEquals(FindBatchResult.Batch(expectedBatch), result)
        assertEquals(listOf(request), document.findCalls)
        assertEquals(EditorDocumentStatus.Ready, state.status)
        assertTrue(state.canCloseSafely)
    }

    /** Verifies scalar alignment may safely reduce one batch to a single unit of progress. */
    @Test
    fun acceptsSingleUnitScalarRoundedFindProgress() = runBlocking {
        val originalText = "a😀tail"
        val request =
            FindRequest(
                revision = INITIAL_REVISION,
                query = "missing",
                candidateRange = Utf16Range(start = 0L, end = originalText.length.toLong()),
                direction = FindDirection.Forward,
                maxCandidateUtf16Units = 2
            )
        val expectedBatch =
            FindBatch(
                metrics = editableMetrics(originalText, INITIAL_REVISION),
                match = null,
                remainingCandidateRange =
                    Utf16Range(start = 1L, end = originalText.length.toLong())
            )
        val document = FakeEditorDocument(originalText)
        document.findOverride = { expectedBatch }
        val state = EditorDocumentState(document, ImmediateTestDispatcher)
        state.loadInitialViewport()

        assertEquals(FindBatchResult.Batch(expectedBatch), state.findBatch(request))
    }

    /** Verifies Find batches reject unloaded, stale, out-of-range, and dirty requests. */
    @Test
    fun rejectsUnavailableFindBatchesBeforeNativeWork() = runBlocking {
        val originalText = "alpha beta"
        val request =
            FindRequest(
                revision = INITIAL_REVISION,
                query = "beta",
                candidateRange = Utf16Range(start = 0L, end = originalText.length.toLong()),
                direction = FindDirection.Forward,
                maxCandidateUtf16Units = originalText.length
            )
        val document = FakeEditorDocument(originalText)
        val state = EditorDocumentState(document, ImmediateTestDispatcher)

        assertSame(FindBatchResult.Unavailable, state.findBatch(request))
        state.loadInitialViewport()
        assertSame(
            FindBatchResult.Unavailable,
            state.findBatch(request.copy(revision = FIRST_EDIT_REVISION))
        )
        assertSame(
            FindBatchResult.Unavailable,
            state.findBatch(
                request.copy(
                    candidateRange =
                        Utf16Range(start = 0L, end = originalText.length.toLong() + 1L)
                )
            )
        )
        state.activateDocumentAt(Utf16Range(start = 0L, end = 0L))
        state.updateActiveDraftStatus(
            generation = requireNotNull(state.activeEdit).generation,
            hasChanges = true
        )
        assertSame(FindBatchResult.Unavailable, state.findBatch(request))

        assertTrue(document.findCalls.isEmpty())
    }

    /** Verifies one clean bounded editor can search without closing its field. */
    @Test
    fun searchesWhileKeepingACleanBoundedEditorActive() = runBlocking {
        val originalText = "alpha beta"
        val matchStart = originalText.indexOf("beta").toLong()
        val request =
            FindRequest(
                revision = INITIAL_REVISION,
                query = "beta",
                candidateRange = Utf16Range(start = 0L, end = originalText.length.toLong()),
                direction = FindDirection.Forward,
                maxCandidateUtf16Units = originalText.length
            )
        val expectedBatch =
            FindBatch(
                metrics = editableMetrics(originalText, INITIAL_REVISION),
                match =
                    FindMatch(
                        range = Utf16Range(start = matchStart, end = originalText.length.toLong()),
                        start =
                            ViewportCursor(
                                revision = INITIAL_REVISION,
                                line = 0L,
                                utf16Offset = matchStart
                            )
                    ),
                remainingCandidateRange = null
            )
        val document = FakeEditorDocument(originalText)
        document.findOverride = { expectedBatch }
        val state = EditorDocumentState(document, ImmediateTestDispatcher)
        state.loadInitialViewport()
        state.activateDocumentAt(Utf16Range(start = 0L, end = 0L))
        val retainedEdit = requireNotNull(state.activeEdit)

        assertEquals(FindBatchResult.Batch(expectedBatch), state.findBatch(request))

        assertSame(retainedEdit, state.activeEdit)
        assertEquals(listOf(request), document.findCalls)
    }

    /** Verifies a Find match replaces only the clean bounded field generation. */
    @Test
    fun opensAFindMatchDirectlyInTheBoundedEditor() = runBlocking {
        val originalText = "zero needle tail"
        val matchStart = originalText.indexOf("needle").toLong()
        val match =
            FindMatch(
                range = Utf16Range(start = matchStart, end = matchStart + "needle".length),
                start =
                    ViewportCursor(
                        revision = INITIAL_REVISION,
                        line = 0L,
                        utf16Offset = matchStart
                    )
            )
        val document = FakeEditorDocument(originalText)
        val state = EditorDocumentState(document, ImmediateTestDispatcher)
        state.loadInitialViewport()
        state.activateDocumentAt(Utf16Range(start = 0L, end = 0L))
        val retainedGeneration = requireNotNull(state.activeEdit).generation

        assertSame(ActiveEditNavigationResult.Published, state.navigateActiveEditToMatch(match))

        val movedEdit = requireNotNull(state.activeEdit)
        assertTrue(movedEdit.generation > retainedGeneration)
        assertEquals(match.range, movedEdit.snapshot.selection)
        assertEquals(match.range, document.editWindowCalls.last().selection)
        assertEquals(EditorDocumentStatus.Ready, state.status)
    }

    /** Verifies failed direct Find navigation retains and can retry the prior field. */
    @Test
    fun retainsTheBoundedEditorWhenDirectFindNavigationFails() = runBlocking {
        val originalText = "zero needle tail"
        val matchStart = originalText.indexOf("needle").toLong()
        val match =
            FindMatch(
                range = Utf16Range(start = matchStart, end = matchStart + "needle".length),
                start =
                    ViewportCursor(
                        revision = INITIAL_REVISION,
                        line = 0L,
                        utf16Offset = matchStart
                    )
            )
        var shouldFailMatch = true
        val document = FakeEditorDocument(originalText)
        document.editWindowOverride = { revision, selection, _ ->
            if (selection == match.range && shouldFailMatch) {
                shouldFailMatch = false
                throw IllegalStateException("synthetic direct Find failure")
            }
            editWindowSnapshot(
                text = originalText,
                revision = revision,
                range = Utf16Range(start = 0L, end = originalText.length.toLong()),
                selection = selection
            )
        }
        val state = EditorDocumentState(document, ImmediateTestDispatcher)
        state.loadInitialViewport()
        state.activateDocumentAt(Utf16Range(start = 0L, end = 0L))
        val retainedEdit = requireNotNull(state.activeEdit)

        assertEquals(
            ActiveEditNavigationResult.Failed(TEST_MATCH_VIEWPORT_FAILURE_MESSAGE),
            state.navigateActiveEditToMatch(match)
        )
        assertSame(retainedEdit, state.activeEdit)
        assertEquals(TEST_MATCH_VIEWPORT_FAILURE_MESSAGE, state.editorMessage)
        assertTrue(state.canRetryEditWindow)

        state.retryEditWindow()

        val movedEdit = requireNotNull(state.activeEdit)
        assertTrue(movedEdit !== retainedEdit)
        assertEquals(match.range, movedEdit.snapshot.selection)
        assertNull(state.editorMessage)
    }

    /** Verifies logical-line navigation retains, retries, and dismisses exact failures. */
    @Test
    fun retriesAndDismissesDirectLineNavigationWithoutDroppingTheEditor() = runBlocking {
        val originalText = "alpha\nbeta\ngamma"
        var failingSelection: Utf16Range? = Utf16Range(start = 11L, end = 11L)
        val document = FakeEditorDocument(originalText)
        document.editWindowOverride = { revision, selection, _ ->
            if (selection == failingSelection) {
                failingSelection = null
                throw IllegalStateException("synthetic direct line failure")
            }
            editWindowSnapshot(
                text = originalText,
                revision = revision,
                range = Utf16Range(start = 0L, end = originalText.length.toLong()),
                selection = selection
            )
        }
        val state = EditorDocumentState(document, ImmediateTestDispatcher)
        state.loadInitialViewport()
        state.activateDocumentAt(Utf16Range(start = 0L, end = 0L))
        val retainedEdit = requireNotNull(state.activeEdit)

        assertFalse(state.navigateActiveEditToLine(logicalLine = 2L))
        assertSame(retainedEdit, state.activeEdit)
        assertEquals(listOf(2L), document.lineStartCalls)
        assertTrue(state.lineViewportStatus is LineViewportStatus.Failed)
        assertTrue(state.canRetryEditWindow)

        state.retryEditWindow()

        val movedEdit = requireNotNull(state.activeEdit)
        assertTrue(movedEdit !== retainedEdit)
        assertEquals(Utf16Range(start = 11L, end = 11L), movedEdit.snapshot.selection)
        assertEquals(listOf(2L, 2L), document.lineStartCalls)
        assertEquals(LineViewportStatus.Idle, state.lineViewportStatus)

        val secondRetainedEdit = movedEdit
        failingSelection = Utf16Range(start = 6L, end = 6L)
        assertFalse(state.navigateActiveEditToLine(logicalLine = 1L))
        assertSame(secondRetainedEdit, state.activeEdit)
        assertTrue(state.dismissLineNavigationFailure())
        assertSame(secondRetainedEdit, state.activeEdit)
        assertEquals(LineViewportStatus.Idle, state.lineViewportStatus)
        assertFalse(state.canRetryEditWindow)
    }

    /** Verifies malformed Find progress and matches cannot escape as trusted results. */
    @Test
    fun rejectsMalformedFindBatchesWithoutChangingTheViewport() = runBlocking {
        val originalText = "0123456789abcdef"
        val request =
            FindRequest(
                revision = INITIAL_REVISION,
                query = "3",
                candidateRange = Utf16Range(start = 0L, end = originalText.length.toLong()),
                direction = FindDirection.Forward,
                maxCandidateUtf16Units = TEST_FIND_BATCH_UTF16_UNITS
            )
        val metrics = editableMetrics(originalText, INITIAL_REVISION)
        val malformedBatches =
            listOf(
                FindBatch(
                    metrics = metrics,
                    match = null,
                    remainingCandidateRange =
                        Utf16Range(start = 2L, end = originalText.length.toLong() - 1L)
                ),
                FindBatch(
                    metrics = metrics,
                    match = null,
                    remainingCandidateRange =
                        Utf16Range(start = 9L, end = originalText.length.toLong())
                ),
                FindBatch(metrics = metrics, match = null, remainingCandidateRange = null),
                FindBatch(
                    metrics = metrics,
                    match =
                        FindMatch(
                            range = Utf16Range(start = 8L, end = 9L),
                            start =
                                ViewportCursor(
                                    revision = INITIAL_REVISION,
                                    line = 0L,
                                    utf16Offset = 8L
                                )
                        ),
                    remainingCandidateRange = null
                ),
                FindBatch(
                    metrics = metrics.copy(byteLength = metrics.byteLength + 1L),
                    match = null,
                    remainingCandidateRange =
                        Utf16Range(start = 2L, end = originalText.length.toLong())
                )
            )
        var batchIndex = 0
        val document = FakeEditorDocument(originalText)
        document.findOverride = { malformedBatches[batchIndex++] }
        val state = EditorDocumentState(document, ImmediateTestDispatcher)
        state.loadInitialViewport()
        val retainedBlocks = state.blocks
        val retainedMetrics = state.metrics

        malformedBatches.forEach { _ ->
            assertEquals(
                FindBatchResult.Failed(TEST_FIND_FAILURE_MESSAGE),
                state.findBatch(request)
            )
            assertSame(retainedBlocks, state.blocks)
            assertSame(retainedMetrics, state.metrics)
            assertEquals(EditorDocumentStatus.Ready, state.status)
            assertTrue(state.canCloseSafely)
        }
        assertEquals(malformedBatches.size, document.findCalls.size)
    }

    /** Verifies stale native Find work invalidates the superseded revision safely. */
    @Test
    fun transitionsToStaleWhenAFindBatchUsesASupersededRevision() = runBlocking {
        val originalText = "alpha beta"
        val document = FakeEditorDocument(originalText)
        val state = EditorDocumentState(document, ImmediateTestDispatcher)
        state.loadInitialViewport()
        document.advanceExternally(EXTERNAL_REVISION)
        val request =
            FindRequest(
                revision = INITIAL_REVISION,
                query = "beta",
                candidateRange = Utf16Range(start = 0L, end = originalText.length.toLong()),
                direction = FindDirection.Forward,
                maxCandidateUtf16Units = originalText.length
            )

        val result = state.findBatch(request)

        assertEquals(FindBatchResult.Failed(TEST_FIND_FAILURE_MESSAGE), result)
        assertEquals(EditorDocumentStatus.Stale, state.status)
        assertNull(state.metrics)
        assertTrue(state.blocks.isEmpty())
        assertTrue(state.canCloseSafely)
    }

    /** Verifies cancelling one native batch restores every synchronous operation gate. */
    @Test
    fun restoresFindGatesWhenABatchIsCancelled() = runBlocking {
        val originalText = pagedDocumentText(TEST_VIEWPORT_PAGE_COUNT)
        val findStarted = CompletableDeferred<Unit>()
        val releaseFind = CompletableDeferred<Unit>()
        val document = FakeEditorDocument(originalText)
        document.viewportOverride = { cursor, _ ->
            forwardPageSnapshot(
                text = originalText,
                revision = INITIAL_REVISION,
                pageIndex = cursor.line.toInt()
            )
        }
        document.findOverride = { request ->
            findStarted.complete(Unit)
            runBlocking { releaseFind.await() }
            FindBatch(
                metrics = editableMetrics(originalText, request.revision),
                match = null,
                remainingCandidateRange = null
            )
        }
        val state = EditorDocumentState(document)
        state.loadInitialViewport()
        val retainedBlocks = state.blocks
        val request =
            FindRequest(
                revision = INITIAL_REVISION,
                query = "page",
                candidateRange = Utf16Range(start = 0L, end = originalText.length.toLong()),
                direction = FindDirection.Forward,
                maxCandidateUtf16Units = originalText.length
            )

        val pendingBatch = async { state.findBatch(request) }
        findStarted.await()
        assertFalse(state.canCloseSafely)
        assertFalse(state.canLoadMore)
        pendingBatch.cancel()
        releaseFind.complete(Unit)
        val cancellation = runCatching { pendingBatch.await() }.exceptionOrNull()

        assertTrue(cancellation is CancellationException)
        assertTrue(state.canCloseSafely)
        assertTrue(state.canLoadMore)
        assertSame(retainedBlocks, state.blocks)
        assertEquals(EditorDocumentStatus.Ready, state.status)
    }

    /** Verifies a huge-line match retains one bounded scalar-aligned prefix. */
    @Test
    fun navigatesToAMatchInsideAHugeLine() = runBlocking {
        val matchText = "find"
        val matchStart = 9_000L
        val originalText = "x".repeat(matchStart.toInt()) + matchText + "tail"
        val document = FakeEditorDocument(originalText)
        var contextStart = 0L
        document.viewportOverride = { cursor, _ ->
            assertEquals(0L, cursor.line)
            if (cursor.utf16Offset == 0L) {
                sameLinePageSnapshot(
                    text = originalText,
                    revision = INITIAL_REVISION,
                    pageIndex = 0
                )
            } else {
                assertEquals(contextStart, cursor.utf16Offset)
                ViewportSnapshot(
                    metrics = editableMetrics(originalText, INITIAL_REVISION),
                    blocks =
                        listOf(
                            RenderBlock(
                                logicalLine = 0L,
                                globalUtf16Start = contextStart,
                                globalUtf16End = originalText.length.toLong(),
                                lineTerminatorUtf16Units = 0,
                                text = originalText.substring(contextStart.toInt()),
                                continuesAtStart = true,
                                continuesAtEnd = false
                            )
                        ),
                    previous = cursor,
                    next = null
                )
            }
        }
        document.previousViewportOverride = { cursor, limits ->
            assertEquals(matchStart, cursor.utf16Offset)
            assertEquals(limits.maxBlockUtf16Units, limits.maxTotalUtf16Units)
            contextStart = matchStart - limits.maxTotalUtf16Units
            val contextCursor = cursor.copy(utf16Offset = contextStart)
            ViewportSnapshot(
                metrics = editableMetrics(originalText, INITIAL_REVISION),
                blocks =
                    listOf(
                        RenderBlock(
                            logicalLine = 0L,
                            globalUtf16Start = contextStart,
                            globalUtf16End = matchStart,
                            lineTerminatorUtf16Units = 0,
                            text = originalText.substring(contextStart.toInt(), matchStart.toInt()),
                            continuesAtStart = true,
                            continuesAtEnd = true
                        )
                    ),
                previous = contextCursor,
                next = cursor
            )
        }
        val state = EditorDocumentState(document, ImmediateTestDispatcher)
        state.loadInitialViewport()
        val initialLimits = document.viewportCalls.single().limits
        val match =
            FindMatch(
                range = Utf16Range(start = matchStart, end = matchStart + matchText.length),
                start =
                    ViewportCursor(
                        revision = INITIAL_REVISION,
                        line = 0L,
                        utf16Offset = matchStart
                    )
            )

        val result = state.navigateToMatch(match)

        assertSame(MatchViewportResult.Published, result)
        assertEquals(match.start, document.previousViewportCalls.single().cursor)
        assertEquals(contextStart, document.viewportCalls.last().cursor.utf16Offset)
        assertEquals(initialLimits, document.viewportCalls.last().limits)
        assertEquals(
            originalText.substring(contextStart.toInt()),
            state.blocks.single().block.text
        )
        assertEquals(contextStart, state.blocks.single().block.globalUtf16Start)
        assertEquals("0:0:$contextStart", state.previousPaginationKey)
        assertNull(state.paginationKey)
    }

    /** Verifies an ordinary match keeps the complete logical-line prefix. */
    @Test
    fun retainsWholeShortLineAroundAMatch() = runBlocking {
        val originalText = "first\n> A private, source-backed editor"
        val lineStart = originalText.indexOf('>')
        val matchStart = originalText.indexOf("private")
        val match =
            FindMatch(
                range = Utf16Range(matchStart.toLong(), (matchStart + "private".length).toLong()),
                start =
                    ViewportCursor(
                        revision = INITIAL_REVISION,
                        line = 1L,
                        utf16Offset = (matchStart - lineStart).toLong()
                    )
            )
        val expectedCursor = ViewportCursor(INITIAL_REVISION, 1L, 0L)
        val document = FakeEditorDocument(originalText)
        document.viewportOverride = { cursor, _ ->
            if (cursor == expectedCursor) {
                ViewportSnapshot(
                    metrics = editableMetrics(originalText, INITIAL_REVISION),
                    blocks =
                        listOf(
                            RenderBlock(
                                logicalLine = 1L,
                                globalUtf16Start = lineStart.toLong(),
                                globalUtf16End = originalText.length.toLong(),
                                lineTerminatorUtf16Units = 0,
                                text = originalText.substring(lineStart),
                                continuesAtStart = false,
                                continuesAtEnd = false
                            )
                        ),
                    previous = expectedCursor,
                    next = null
                )
            } else {
                assertEquals(ViewportCursor(INITIAL_REVISION, 0L, 0L), cursor)
                completeViewport(originalText, INITIAL_REVISION)
            }
        }
        val state = EditorDocumentState(document, ImmediateTestDispatcher)
        state.loadInitialViewport()

        val result = state.navigateToMatch(match)

        assertSame(MatchViewportResult.Published, result)
        assertEquals(expectedCursor, document.viewportCalls.last().cursor)
        assertEquals(
            listOf("> A private, source-backed editor"),
            state.blocks.map {
                it.block.text
            }
        )
        assertEquals(lineStart.toLong(), state.blocks.single().block.globalUtf16Start)
    }

    /** Verifies a terminator match retains its complete short-line context. */
    @Test
    fun navigatesFromATerminatorMatchToItsCanonicalNextLine() = runBlocking {
        val originalText = "first\nsecond"
        val matchStart = originalText.indexOf('\n').toLong()
        val match =
            FindMatch(
                range = Utf16Range(start = matchStart, end = matchStart + 1L),
                start =
                    ViewportCursor(
                        revision = INITIAL_REVISION,
                        line = 0L,
                        utf16Offset = matchStart
                    )
            )
        val document = FakeEditorDocument(originalText)
        val state = EditorDocumentState(document, ImmediateTestDispatcher)
        state.loadInitialViewport()

        val result = state.navigateToMatch(match)

        assertSame(MatchViewportResult.Published, result)
        assertEquals(ViewportCursor(INITIAL_REVISION, 0L, 0L), document.viewportCalls.last().cursor)
        assertEquals(listOf("first", "second"), state.blocks.map { block -> block.block.text })
        assertNull(state.previousPaginationKey)
        assertNull(state.paginationKey)
        assertSame(LineViewportStatus.Idle, state.lineViewportStatus)
        assertNull(state.viewportFailureToken)
    }

    /** Verifies a malformed match viewport fails without moving or arming line retry UI. */
    @Test
    fun retainsTheViewportWhenMatchNavigationFailsValidation() = runBlocking {
        val originalText = "prefix\nabcdefghijk"
        val matchStart = 10L
        val match =
            FindMatch(
                range = Utf16Range(start = matchStart, end = matchStart + 4L),
                start =
                    ViewportCursor(
                        revision = INITIAL_REVISION,
                        line = 1L,
                        utf16Offset = 3L
                    )
            )
        val document = FakeEditorDocument(originalText)
        document.viewportOverride = { cursor, _ ->
            if (cursor == match.start) {
                ViewportSnapshot(
                    metrics = editableMetrics(originalText, INITIAL_REVISION),
                    blocks =
                        listOf(
                            RenderBlock(
                                logicalLine = 1L,
                                globalUtf16Start = matchStart + 1L,
                                globalUtf16End = matchStart + 5L,
                                lineTerminatorUtf16Units = 0,
                                text = "efgh",
                                continuesAtStart = true,
                                continuesAtEnd = true
                            )
                        ),
                    previous = match.start,
                    next = match.start.copy(utf16Offset = 7L)
                )
            } else {
                completeViewport(text = originalText, revision = INITIAL_REVISION)
            }
        }
        val state = EditorDocumentState(document, ImmediateTestDispatcher)
        state.loadInitialViewport()
        val retainedBlocks = state.blocks
        val retainedMetrics = state.metrics
        val retainedPreviousPaginationKey = state.previousPaginationKey
        val retainedPaginationKey = state.paginationKey

        val result = state.navigateToMatch(match)

        assertEquals(MatchViewportResult.Failed(TEST_MATCH_VIEWPORT_FAILURE_MESSAGE), result)
        assertSame(retainedBlocks, state.blocks)
        assertSame(retainedMetrics, state.metrics)
        assertEquals(retainedPreviousPaginationKey, state.previousPaginationKey)
        assertEquals(retainedPaginationKey, state.paginationKey)
        assertSame(LineViewportStatus.Idle, state.lineViewportStatus)
        assertNull(state.viewportFailureToken)
        assertFalse(state.hasPreviousViewportFailure)
        assertFalse(state.hasNextViewportFailure)
        assertEquals(EditorDocumentStatus.Ready, state.status)
        assertTrue(state.canCloseSafely)
    }

    /** Verifies reverse paging prepends one page and evicts only the far suffix. */
    @Test
    fun prependsTheImmediatelyPreviousBoundedPage() = runBlocking {
        val originalText = pagedDocumentText(TEST_VIEWPORT_PAGE_COUNT)
        val document = FakeEditorDocument(originalText)
        document.viewportOverride = { cursor, _ ->
            forwardPageSnapshot(
                text = originalText,
                revision = INITIAL_REVISION,
                pageIndex = cursor.line.toInt()
            )
        }
        document.previousViewportOverride = { cursor, _ ->
            previousPageSnapshot(
                text = originalText,
                revision = INITIAL_REVISION,
                endPageIndex = cursor.line.toInt()
            )
        }
        val state = EditorDocumentState(document, ImmediateTestDispatcher)

        state.loadInitialViewport()
        repeat(TEST_VIEWPORT_PAGE_COUNT - 1) {
            assertTrue(state.canLoadMore)
            state.loadNextViewport()
        }

        assertEquals((2L..9L).toList(), state.blocks.map { block -> block.block.logicalLine })
        assertTrue(state.canLoadPrevious)
        assertEquals("0:2:0", state.previousPaginationKey)

        state.loadPreviousViewport()

        assertEquals((1L..8L).toList(), state.blocks.map { block -> block.block.logicalLine })
        assertEquals(
            ViewportCursor(revision = INITIAL_REVISION, line = 2, utf16Offset = 0),
            document.previousViewportCalls.single().cursor
        )
        assertTrue(state.canLoadPrevious)
        assertTrue(state.canLoadMore)
        assertEquals("0:1:0", state.previousPaginationKey)
        assertEquals("0:9:0", state.paginationKey)
    }

    /** Verifies retry preserves the failed reverse-page request direction. */
    @Test
    fun retriesAFailedPreviousViewportRequest() = runBlocking {
        val originalText = pagedDocumentText(TEST_VIEWPORT_PAGE_COUNT)
        val document = FakeEditorDocument(originalText)
        document.viewportOverride = { cursor, _ ->
            forwardPageSnapshot(
                text = originalText,
                revision = INITIAL_REVISION,
                pageIndex = cursor.line.toInt()
            )
        }
        document.previousViewportOverride = { _, _ ->
            error("test reverse viewport failure")
        }
        val state = EditorDocumentState(document, ImmediateTestDispatcher)
        state.loadInitialViewport()
        repeat(TEST_VIEWPORT_PAGE_COUNT - 1) {
            state.loadNextViewport()
        }
        val forwardCallCount = document.viewportCalls.size

        state.loadPreviousViewport()

        assertTrue(state.status is EditorDocumentStatus.Failed)
        assertTrue(state.hasPreviousViewportFailure)
        assertFalse(state.hasNextViewportFailure)
        assertEquals(1, document.previousViewportCalls.size)
        document.previousViewportOverride = { cursor, _ ->
            previousPageSnapshot(
                text = originalText,
                revision = INITIAL_REVISION,
                endPageIndex = cursor.line.toInt()
            )
        }

        val retryResult = state.retryViewport(checkNotNull(state.viewportFailureToken))

        assertEquals(ViewportRetryResult.ViewportPublished, retryResult)
        assertEquals(EditorDocumentStatus.Ready, state.status)
        assertFalse(state.hasPreviousViewportFailure)
        assertEquals(2, document.previousViewportCalls.size)
        assertEquals(forwardCallCount, document.viewportCalls.size)
        assertEquals((1L..8L).toList(), state.blocks.map { block -> block.block.logicalLine })
    }

    /** Verifies adjacent pages cannot change metrics while retaining a revision. */
    @Test
    fun rejectsChangedMetricsFromAnAdjacentViewport() = runBlocking {
        val originalText = pagedDocumentText(pageCount = 2)
        val document = FakeEditorDocument(originalText)
        document.viewportOverride = { cursor, _ ->
            val snapshot =
                forwardPageSnapshot(
                    text = originalText,
                    revision = INITIAL_REVISION,
                    pageIndex = cursor.line.toInt()
                )
            if (cursor.line == 0L) {
                snapshot
            } else {
                snapshot.copy(
                    metrics =
                        snapshot.metrics.copy(
                            serializedByteLength =
                                snapshot.metrics.serializedByteLength +
                                    TEST_METRICS_BYTE_DELTA
                        )
                )
            }
        }
        val state = EditorDocumentState(document, ImmediateTestDispatcher)
        state.loadInitialViewport()
        val retainedBlocks = state.blocks

        state.loadNextViewport()

        assertTrue(state.status is EditorDocumentStatus.Failed)
        assertTrue(state.hasNextViewportFailure)
        assertFalse(state.hasPreviousViewportFailure)
        assertEquals(retainedBlocks, state.blocks)
        assertEquals("0:1:0", state.paginationKey)
    }

    /** Verifies a forward page cannot introduce a gap at the cache boundary. */
    @Test
    fun rejectsAGappedForwardViewport() = runBlocking {
        val originalText = pagedDocumentText(pageCount = 2)
        val document = FakeEditorDocument(originalText)
        document.viewportOverride = { cursor, _ ->
            val snapshot =
                forwardPageSnapshot(
                    text = originalText,
                    revision = INITIAL_REVISION,
                    pageIndex = cursor.line.toInt()
                )
            if (cursor.line == 0L) {
                snapshot
            } else {
                snapshot.copy(
                    blocks =
                        snapshot.blocks.map { block ->
                            block.shiftedBy(TEST_BOUNDARY_SHIFT_UTF16_UNITS)
                        }
                )
            }
        }
        val state = EditorDocumentState(document, ImmediateTestDispatcher)
        state.loadInitialViewport()
        val retainedBlocks = state.blocks

        state.loadNextViewport()

        assertTrue(state.status is EditorDocumentStatus.Failed)
        assertEquals(retainedBlocks, state.blocks)
        assertEquals("0:1:0", state.paginationKey)
    }

    /** Verifies a reverse page cannot overlap the retained cache boundary. */
    @Test
    fun rejectsAnOverlappingPreviousViewport() = runBlocking {
        val originalText = pagedDocumentText(TEST_VIEWPORT_PAGE_COUNT)
        val document = FakeEditorDocument(originalText)
        document.viewportOverride = { cursor, _ ->
            forwardPageSnapshot(
                text = originalText,
                revision = INITIAL_REVISION,
                pageIndex = cursor.line.toInt()
            )
        }
        document.previousViewportOverride = { cursor, _ ->
            val snapshot =
                previousPageSnapshot(
                    text = originalText,
                    revision = INITIAL_REVISION,
                    endPageIndex = cursor.line.toInt()
                )
            snapshot.copy(
                blocks =
                    snapshot.blocks.map { block ->
                        block.shiftedBy(TEST_BOUNDARY_SHIFT_UTF16_UNITS)
                    }
            )
        }
        val state = EditorDocumentState(document, ImmediateTestDispatcher)
        state.loadInitialViewport()
        repeat(TEST_VIEWPORT_PAGE_COUNT - 1) {
            state.loadNextViewport()
        }
        val retainedBlocks = state.blocks

        state.loadPreviousViewport()

        assertTrue(state.status is EditorDocumentStatus.Failed)
        assertEquals(retainedBlocks, state.blocks)
        assertEquals("0:2:0", state.previousPaginationKey)
    }

    /** Verifies a forward cursor cannot skip content after its returned page. */
    @Test
    fun rejectsAForwardCursorBeyondThePageEdge() = runBlocking {
        val originalText = pagedDocumentText(pageCount = 4)
        val document = FakeEditorDocument(originalText)
        document.viewportOverride = { cursor, _ ->
            val snapshot =
                forwardPageSnapshot(
                    text = originalText,
                    revision = INITIAL_REVISION,
                    pageIndex = cursor.line.toInt()
                )
            if (cursor.line == 0L) {
                snapshot
            } else {
                snapshot.copy(
                    next =
                        ViewportCursor(
                            revision = INITIAL_REVISION,
                            line = 3L,
                            utf16Offset = 0L
                        )
                )
            }
        }
        val state = EditorDocumentState(document, ImmediateTestDispatcher)
        state.loadInitialViewport()
        val retainedBlocks = state.blocks

        state.loadNextViewport()

        assertTrue(state.status is EditorDocumentStatus.Failed)
        assertEquals(retainedBlocks, state.blocks)
        assertEquals("0:1:0", state.paginationKey)
    }

    /** Verifies a reverse cursor cannot skip content before its returned page. */
    @Test
    fun rejectsAReverseCursorBeyondThePageEdge() = runBlocking {
        val originalText = pagedDocumentText(TEST_VIEWPORT_PAGE_COUNT)
        val document = FakeEditorDocument(originalText)
        document.viewportOverride = { cursor, _ ->
            forwardPageSnapshot(
                text = originalText,
                revision = INITIAL_REVISION,
                pageIndex = cursor.line.toInt()
            )
        }
        document.previousViewportOverride = { cursor, _ ->
            previousPageSnapshot(
                text = originalText,
                revision = INITIAL_REVISION,
                endPageIndex = cursor.line.toInt()
            ).copy(
                previous =
                    ViewportCursor(
                        revision = INITIAL_REVISION,
                        line = 0L,
                        utf16Offset = 0L
                    )
            )
        }
        val state = EditorDocumentState(document, ImmediateTestDispatcher)
        state.loadInitialViewport()
        repeat(TEST_VIEWPORT_PAGE_COUNT - 1) {
            state.loadNextViewport()
        }
        val retainedBlocks = state.blocks

        state.loadPreviousViewport()

        assertTrue(state.status is EditorDocumentStatus.Failed)
        assertEquals(retainedBlocks, state.blocks)
        assertEquals("0:2:0", state.previousPaginationKey)
    }

    /** Verifies the document origin cannot carry a redundant previous cursor. */
    @Test
    fun rejectsAPreviousCursorAtTheDocumentOrigin() = runBlocking {
        val originalText = pagedDocumentText(pageCount = 2)
        val document = FakeEditorDocument(originalText)
        document.viewportOverride = { _, _ ->
            forwardPageSnapshot(
                text = originalText,
                revision = INITIAL_REVISION,
                pageIndex = 0
            ).copy(
                previous =
                    ViewportCursor(
                        revision = INITIAL_REVISION,
                        line = 0L,
                        utf16Offset = 0L
                    )
            )
        }
        val state = EditorDocumentState(document, ImmediateTestDispatcher)

        state.loadInitialViewport()

        assertTrue(state.status is EditorDocumentStatus.Failed)
        assertTrue(state.blocks.isEmpty())
    }

    /** Verifies same-line pages join exactly while paging in both directions. */
    @Test
    fun joinsSameLinePagesInBothDirections() = runBlocking {
        val originalText = "abcd".repeat(TEST_VIEWPORT_PAGE_COUNT)
        val document = FakeEditorDocument(originalText)
        document.viewportOverride = { cursor, _ ->
            sameLinePageSnapshot(
                text = originalText,
                revision = INITIAL_REVISION,
                pageIndex =
                    cursor.utf16Offset.toInt() / TEST_SAME_LINE_PAGE_UTF16_UNITS
            )
        }
        document.previousViewportOverride = { cursor, _ ->
            val endPageIndex =
                cursor.utf16Offset.toInt() / TEST_SAME_LINE_PAGE_UTF16_UNITS
            sameLinePageSnapshot(
                text = originalText,
                revision = INITIAL_REVISION,
                pageIndex = endPageIndex - 1
            )
        }
        val state = EditorDocumentState(document, ImmediateTestDispatcher)
        state.loadInitialViewport()
        repeat(TEST_VIEWPORT_PAGE_COUNT - 1) {
            state.loadNextViewport()
        }
        val retainedFirstBlock = state.blocks.first()

        state.loadPreviousViewport()

        assertEquals(EditorDocumentStatus.Ready, state.status)
        assertSame(retainedFirstBlock, state.blocks[1])
        assertEquals(
            (
                TEST_SAME_LINE_PAGE_UTF16_UNITS until
                    originalText.length - TEST_SAME_LINE_PAGE_UTF16_UNITS step
                    TEST_SAME_LINE_PAGE_UTF16_UNITS
                ).map(Int::toLong),
            state.blocks.map { block -> block.block.globalUtf16Start }
        )
    }

    /** Verifies a multiline diff retains its generation and field-sized baseline. */
    @Test
    fun appliesMinimalMultilineEditWithoutReplacingItsGeneration() = runBlocking {
        val originalDocument = "prefix\none 😀\ntwo\nsuffix"
        val originalWindow = "one 😀\ntwo"
        val updatedWindow = "one 😁\ntwo"
        val windowRange = Utf16Range(start = 7, end = 17)
        val expectedReplacementRange = Utf16Range(start = 11, end = 13)
        val expectedCaret = Utf16Range(start = 13, end = 13)
        val document = FakeEditorDocument(originalDocument, boundedWindowRange = windowRange)
        val state = EditorDocumentState(document, ImmediateTestDispatcher)

        state.loadInitialViewport()
        assertEquals(EditorDocumentStatus.Ready, state.status)
        assertEquals(4, state.blocks.size)
        state.activateDocumentAt(Utf16Range(start = windowRange.start, end = windowRange.start))
        val firstEdit = requireNotNull(state.activeEdit)
        assertEquals(1, firstEdit.generation)
        assertEquals(originalWindow, firstEdit.snapshot.text)
        assertEquals(Utf16Range(start = 0, end = 0), firstEdit.localSelection)

        state.updateActiveDraftStatus(firstEdit.generation, hasChanges = true)
        val result =
            state.commitActiveEdit(
                generation = firstEdit.generation,
                text = updatedWindow,
                selection = Utf16Range(start = 6, end = 6)
            )
        val delta = requireNotNull((result as? EditSynchronizationResult.Applied)?.delta)

        assertEquals(
            listOf(
                ReplaceCall(
                    expectedRevision = INITIAL_REVISION,
                    range = expectedReplacementRange,
                    replacement = "😁"
                )
            ),
            document.replaceCalls
        )
        assertEquals(updatedWindow, document.text.substring(7, 17))
        assertEquals(1, document.editWindowCalls.size)
        assertEquals(INITIAL_REVISION, delta.revisionBefore)
        assertEquals(FIRST_EDIT_REVISION, delta.revisionAfter)
        assertEquals(expectedReplacementRange.start, delta.rangeStart)
        assertEquals("😀", delta.removedText)
        assertEquals("😁", delta.insertedText)
        assertEquals(Utf16Range(start = 7, end = 7), delta.selectionBefore)
        assertEquals(expectedCaret, delta.selectionAfter)

        val refreshedEdit = requireNotNull(state.activeEdit)
        assertEquals(firstEdit.generation, refreshedEdit.generation)
        assertEquals(FIRST_EDIT_REVISION, refreshedEdit.snapshot.metrics.revision)
        assertEquals(updatedWindow, refreshedEdit.snapshot.text)
        assertEquals(expectedCaret, refreshedEdit.snapshot.selection)
        assertEquals(Utf16Range(start = 6, end = 6), refreshedEdit.localSelection)
        assertEquals(EditorDocumentStatus.Ready, state.status)
        assertTrue(state.hasDocumentChanges)
        assertFalse(state.hasActiveDraftChanges)
        assertTrue(state.hasUnsavedChanges)
        assertTrue(state.blocks.isEmpty())
    }

    /** Verifies length-changing bounded edits retain both neighboring regions. */
    @Test
    fun appliesConsecutiveLengthChangingEditsInsideOneStableWindow() = runBlocking {
        val originalDocument = "prefix\n012345-suffix"
        val windowRange = Utf16Range(start = 7, end = 13)
        val document = FakeEditorDocument(originalDocument, boundedWindowRange = windowRange)
        val state = EditorDocumentState(document, ImmediateTestDispatcher)
        state.loadInitialViewport()
        state.activateDocumentAt(Utf16Range(start = windowRange.start, end = windowRange.start))
        val initialEdit = requireNotNull(state.activeEdit)

        state.updateActiveDraftStatus(initialEdit.generation, hasChanges = true)
        state.commitActiveEdit(
            generation = initialEdit.generation,
            text = "0XX12345",
            selection = Utf16Range(start = 3, end = 3)
        )
        val expandedEdit = requireNotNull(state.activeEdit)

        assertEquals(initialEdit.generation, expandedEdit.generation)
        assertEquals(Utf16Range(start = 7, end = 15), expandedEdit.snapshot.range)
        assertTrue(expandedEdit.snapshot.hasPrevious)
        assertTrue(expandedEdit.snapshot.hasNext)
        assertEquals("prefix\n0XX12345-suffix", document.text)

        state.updateActiveDraftStatus(expandedEdit.generation, hasChanges = true)
        state.commitActiveEdit(
            generation = expandedEdit.generation,
            text = "0XX12Z45",
            selection = Utf16Range(start = 6, end = 6)
        )
        val secondEdit = requireNotNull(state.activeEdit)

        assertEquals(initialEdit.generation, secondEdit.generation)
        assertEquals(Utf16Range(start = 7, end = 15), secondEdit.snapshot.range)
        assertEquals("0XX12Z45", secondEdit.snapshot.text)
        assertEquals("prefix\n0XX12Z45-suffix", document.text)
        assertEquals(
            listOf(INITIAL_REVISION, FIRST_EDIT_REVISION),
            document.replaceCalls.map(ReplaceCall::expectedRevision)
        )
        assertEquals(SECOND_EDIT_REVISION, state.metrics?.revision)
        assertFalse(state.hasActiveDraftChanges)
    }

    /** Verifies a history edit remains applied while its failed target window is retryable. */
    @Test
    fun retriesTheTargetWindowAfterApplyingAHistoryEdit() = runBlocking {
        val document = FakeEditorDocument("abcdef")
        val state = EditorDocumentState(document, ImmediateTestDispatcher)
        state.loadInitialViewport()
        state.activateDocumentAt(Utf16Range(start = 0L, end = 0L))
        val edit = requireNotNull(state.activeEdit)
        document.editWindowOverride = { _, _, _ ->
            throw IllegalStateException("synthetic edit-window failure")
        }

        val result =
            state.replaceDocumentRange(
                generation = edit.generation,
                request =
                    DocumentReplacementRequest(
                        expectedRevision = INITIAL_REVISION,
                        range = Utf16Range(start = 1, end = 3),
                        expectedRemovedText = "bc",
                        replacement = "XY",
                        selectionAfter = Utf16Range(start = 3, end = 3)
                    )
            )

        assertEquals(DocumentReplacementResult.Applied(FIRST_EDIT_REVISION), result)
        assertEquals("aXYdef", document.text)
        assertNull(state.activeEdit)
        assertTrue(state.canRetryEditWindow)
        assertEquals(UiText.Resource(R.string.operation_edit_window_failed), state.editorMessage)

        document.editWindowOverride = null
        state.retryEditWindow()

        val retried = requireNotNull(state.activeEdit)
        assertEquals(Utf16Range(start = 3, end = 3), retried.snapshot.selection)
        assertEquals(FIRST_EDIT_REVISION, retried.snapshot.metrics.revision)
        assertFalse(state.canRetryEditWindow)
        assertNull(state.editorMessage)
    }

    /** Verifies malformed history requests fail before reaching the document seam. */
    @Test
    fun rejectsAHistoryRequestWhoseRangeConflictsWithItsExpectedText() {
        assertThrows(IllegalArgumentException::class.java) {
            DocumentReplacementRequest(
                expectedRevision = INITIAL_REVISION,
                range = Utf16Range(start = 1, end = 3),
                expectedRemovedText = "b",
                replacement = "XY",
                selectionAfter = Utf16Range(start = 3, end = 3)
            )
        }
    }

    /** Verifies an unchanged window never crosses the document seam. */
    @Test
    fun treatsAnUnchangedDraftAsANoOp() = runBlocking {
        val document = FakeEditorDocument("same\ntext")
        val state = EditorDocumentState(document, ImmediateTestDispatcher)
        state.loadInitialViewport()
        state.activateDocumentAt(Utf16Range(start = 0L, end = 0L))
        val edit = requireNotNull(state.activeEdit)

        state.updateActiveDraftStatus(edit.generation, hasChanges = true)
        assertTrue(state.hasActiveDraftChanges)
        assertTrue(state.hasUnsavedChanges)
        state.updateActiveDraftStatus(edit.generation, hasChanges = false)
        state.commitActiveEdit(
            generation = edit.generation,
            text = edit.snapshot.text,
            selection = edit.localSelection
        )

        assertTrue(document.replaceCalls.isEmpty())
        assertEquals(1, document.editWindowCalls.size)
        assertSame(edit, state.activeEdit)
        assertFalse(state.hasDocumentChanges)
        assertFalse(state.hasActiveDraftChanges)
        assertFalse(state.hasUnsavedChanges)
        assertNull(state.editorMessage)

        state.commitActiveEdit(
            generation = edit.generation + 1,
            text = "ignored",
            selection = Utf16Range(start = 0, end = 0)
        )
        assertTrue(document.replaceCalls.isEmpty())
    }

    /** Verifies dirty status follows restoration paths that bypass input transforms. */
    @Test
    fun tracksDraftRestorationToItsOriginalText() {
        val originalText = "one 😀"
        val edit =
            ActiveEditWindow(
                generation = 1,
                snapshot =
                    editWindowSnapshot(
                        text = originalText,
                        revision = INITIAL_REVISION,
                        range = Utf16Range(start = 0, end = originalText.length.toLong()),
                        selection = Utf16Range(start = 0, end = 0)
                    )
            )
        val draft = ActiveEditDraft(edit)
        assertFalse(draft.hasChanges)

        draft.textFieldState.edit {
            append("!")
        }
        assertTrue(draft.hasChanges)

        draft.textFieldState.edit {
            replace(start = 0, end = length, text = originalText)
        }
        assertFalse(draft.hasChanges)
    }

    /** Verifies selection-only changes never erase a pending text edit. */
    @Test
    fun retainsDirtyStatusAfterMovingTheSelection() {
        val originalText = "before"
        val edit =
            ActiveEditWindow(
                generation = 1,
                snapshot =
                    editWindowSnapshot(
                        text = originalText,
                        revision = INITIAL_REVISION,
                        range = Utf16Range(start = 0, end = originalText.length.toLong()),
                        selection = Utf16Range(start = 0, end = 0)
                    )
            )
        val draft = ActiveEditDraft(edit)

        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "after")
            selection = androidx.compose.ui.text.TextRange(length)
        }
        assertTrue(draft.hasChanges)

        draft.textFieldState.edit {
            selection = androidx.compose.ui.text.TextRange(0)
        }

        assertEquals("after", draft.textFieldState.text.toString())
        assertTrue(draft.hasChanges)
    }

    /** Verifies discard clears only the matching generation's transient draft. */
    @Test
    fun discardsOnlyTheCurrentDraftGeneration() = runBlocking {
        val document = FakeEditorDocument("first\nsecond")
        val state = EditorDocumentState(document, ImmediateTestDispatcher)
        state.loadInitialViewport()
        state.activateDocumentAt(Utf16Range(start = 6L, end = 6L))
        val edit = requireNotNull(state.activeEdit)
        val cachedBlocks = state.blocks

        state.updateActiveDraftStatus(edit.generation, hasChanges = true)
        state.updateActiveDraftStatus(edit.generation + 1, hasChanges = false)
        assertTrue(state.hasActiveDraftChanges)

        state.discardActiveEdit(edit.generation + 1)
        assertSame(edit, state.activeEdit)
        assertTrue(state.hasActiveDraftChanges)

        state.discardActiveEdit(edit.generation)
        assertNull(state.activeEdit)
        assertFalse(state.hasActiveDraftChanges)
        assertFalse(state.hasUnsavedChanges)
        assertEquals(cachedBlocks, state.blocks)
        assertEquals(EditorDocumentStatus.Ready, state.status)
        assertTrue(document.replaceCalls.isEmpty())
    }

    /** Verifies a stale replace retains a dirty draft until it is explicitly discarded. */
    @Test
    fun retainsDirtyDraftAfterAStaleRevision() = runBlocking {
        val document = FakeEditorDocument("before")
        val state = EditorDocumentState(document, ImmediateTestDispatcher)
        state.loadInitialViewport()
        state.activateDocumentAt(Utf16Range(start = 0L, end = 0L))
        val edit = requireNotNull(state.activeEdit)
        state.updateActiveDraftStatus(edit.generation, hasChanges = true)
        document.advanceExternally(EXTERNAL_REVISION)

        state.commitActiveEdit(
            generation = edit.generation,
            text = "after",
            selection = Utf16Range(start = 5, end = 5)
        )

        assertEquals(EditorDocumentStatus.Stale, state.status)
        assertNull(state.metrics)
        assertSame(edit, state.activeEdit)
        assertTrue(state.blocks.isEmpty())
        assertTrue(state.hasActiveDraftChanges)
        assertTrue(state.hasDocumentChanges)
        assertTrue(state.hasUnsavedChanges)
        assertFalse(state.canAcceptActiveDraftInput(edit.generation))
        assertEquals(UiText.Resource(R.string.operation_stale_draft), state.editorMessage)

        state.reloadStaleViewport()
        assertEquals(EditorDocumentStatus.Stale, state.status)
        assertSame(edit, state.activeEdit)
        assertEquals(1, document.viewportCalls.size)

        state.discardActiveEdit(edit.generation)
        assertEquals(EditorDocumentStatus.Ready, state.status)
        assertEquals(EXTERNAL_REVISION, state.metrics?.revision)
        assertEquals(EXTERNAL_REVISION, document.viewportCalls.last().cursor.revision)
        assertNull(state.activeEdit)
        assertFalse(state.hasActiveDraftChanges)
        assertTrue(state.hasDocumentChanges)
        assertTrue(state.hasUnsavedChanges)
    }

    /** Verifies a response beyond the UI window limit is never published. */
    @Test
    fun rejectsAnOversizedEditWindowResponse() = runBlocking {
        val document = FakeEditorDocument("visible")
        val oversizedText = "x".repeat(EDIT_WINDOW_UTF16_UNITS + 1)
        document.editWindowOverride = { revision, selection, _ ->
            EditWindowSnapshot(
                metrics = editableMetrics(oversizedText, revision),
                range = Utf16Range(start = 0, end = oversizedText.length.toLong()),
                selection = selection,
                start = ViewportCursor(revision = revision, line = 0, utf16Offset = 0),
                text = oversizedText,
                hasPrevious = false,
                hasNext = false
            )
        }
        val state = EditorDocumentState(document, ImmediateTestDispatcher)
        state.loadInitialViewport()

        state.activateDocumentAt(Utf16Range(start = 0L, end = 0L))

        assertEquals(EditorDocumentStatus.Ready, state.status)
        assertNull(state.activeEdit)
        assertEquals(UiText.Resource(R.string.operation_edit_window_failed), state.editorMessage)
        assertEquals(
            EDIT_WINDOW_UTF16_UNITS,
            document.editWindowCalls.single().limits.maxUtf16Units
        )
        assertFalse(state.hasUnsavedChanges)
    }

    /** Verifies an active generation remains composed until its viewport reload returns. */
    @Test
    fun retainsTheActiveGenerationDuringViewportReload() = runBlocking {
        val document = FakeEditorDocument("before")
        val state = EditorDocumentState(document, ImmediateTestDispatcher)
        state.loadInitialViewport()
        state.activateDocumentAt(Utf16Range(start = 0L, end = 0L))
        val initialEdit = requireNotNull(state.activeEdit)
        state.updateActiveDraftStatus(initialEdit.generation, hasChanges = true)
        state.commitActiveEdit(
            generation = initialEdit.generation,
            text = "after",
            selection = Utf16Range(start = 5, end = 5)
        )
        val refreshedEdit = requireNotNull(state.activeEdit)
        assertTrue(state.canAcceptActiveDraftInput(refreshedEdit.generation))
        var observedActiveReload = false
        document.onViewport = {
            observedActiveReload =
                state.activeEdit === refreshedEdit &&
                state.status == EditorDocumentStatus.LoadingInitial &&
                !state.canAcceptActiveDraftInput(refreshedEdit.generation)
        }

        state.discardActiveEdit(refreshedEdit.generation)

        assertTrue(observedActiveReload)
        assertNull(state.activeEdit)
        assertEquals(EditorDocumentStatus.Ready, state.status)
        assertEquals(listOf("after"), state.blocks.map { block -> block.block.text })
        assertTrue(state.hasDocumentChanges)
        assertTrue(state.hasUnsavedChanges)
    }

    /** Verifies returning after an edit reloads the full line containing the caret. */
    @Test
    fun returnsToTheEditedLogicalLineAfterViewportReload() = runBlocking {
        val originalText = "zero\none\ntwo\nthree\nfour"
        val boundedRange = Utf16Range(start = 1L, end = originalText.length.toLong())
        val document = FakeEditorDocument(originalText, boundedWindowRange = boundedRange)
        val state = EditorDocumentState(document, ImmediateTestDispatcher)
        state.loadInitialViewport()
        val caret = "zero\none\ntwo\nthr".length.toLong()
        state.activateDocumentAt(Utf16Range(start = caret, end = caret))
        val edit = requireNotNull(state.activeEdit)
        val localCaret = edit.snapshot.selection.start - boundedRange.start
        val editedWindow = edit.snapshot.text.replaceFirst("ero", "ERO")
        state.updateActiveDraftStatus(edit.generation, hasChanges = true)

        state.commitActiveEdit(
            generation = edit.generation,
            text = editedWindow,
            selection = Utf16Range(start = localCaret, end = localCaret)
        )
        val reloadedViewport = state.discardActiveEdit(edit.generation)

        assertTrue(reloadedViewport)
        assertEquals(
            ViewportCursor(
                revision = FIRST_EDIT_REVISION,
                line = 3L,
                utf16Offset = 0L
            ),
            document.viewportCalls.last().cursor
        )
        assertEquals(EditorDocumentStatus.Ready, state.status)
        assertNull(state.activeEdit)
    }

    /** Verifies synchronization never requests a replacement edit window. */
    @Test
    fun keepsTheActiveWindowWithoutRefetchingItAfterAnEdit() = runBlocking {
        val document = FakeEditorDocument("before")
        val state = EditorDocumentState(document, ImmediateTestDispatcher)
        state.loadInitialViewport()
        state.activateDocumentAt(Utf16Range(start = 0L, end = 0L))
        val edit = requireNotNull(state.activeEdit)
        document.editWindowOverride = { revision, selection, _ ->
            editWindowSnapshot(
                text = document.text,
                revision = revision,
                range = Utf16Range(start = 0, end = document.text.length.toLong()),
                selection = selection
            ).copy(selection = Utf16Range(start = 0, end = 0))
        }
        state.updateActiveDraftStatus(edit.generation, hasChanges = true)

        state.commitActiveEdit(
            generation = edit.generation,
            text = "after",
            selection = Utf16Range(start = 5, end = 5)
        )

        assertEquals("after", document.text)
        val committedEdit = requireNotNull(state.activeEdit)
        assertEquals(edit.generation, committedEdit.generation)
        assertEquals("after", committedEdit.snapshot.text)
        assertEquals(FIRST_EDIT_REVISION, committedEdit.snapshot.metrics.revision)
        assertEquals(Utf16Range(start = 5, end = 5), committedEdit.snapshot.selection)
        assertEquals(EditorDocumentStatus.Ready, state.status)
        assertTrue(state.blocks.isEmpty())
        assertEquals(1, document.editWindowCalls.size)
        assertNull(state.editorMessage)
        assertTrue(state.hasDocumentChanges)
        assertFalse(state.hasActiveDraftChanges)
        assertTrue(state.hasUnsavedChanges)
    }

    /** Verifies an unverified native result retains the field behind a reload boundary. */
    @Test
    fun requiresReloadAfterReplacementMetricsConflict() = runBlocking {
        val document = FakeEditorDocument("before")
        val state = EditorDocumentState(document, ImmediateTestDispatcher)
        state.loadInitialViewport()
        state.activateDocumentAt(Utf16Range(start = 0L, end = 0L))
        val edit = requireNotNull(state.activeEdit)
        document.replaceMetricsOverride = { metrics ->
            metrics.copy(byteLength = metrics.byteLength + 1L)
        }
        state.updateActiveDraftStatus(edit.generation, hasChanges = true)

        val result =
            state.commitActiveEdit(
                generation = edit.generation,
                text = "after",
                selection = Utf16Range(start = 5, end = 5)
            )

        assertEquals(EditSynchronizationResult.Failed, result)
        assertEquals("after", document.text)
        assertEquals(EditorDocumentStatus.Stale, state.status)
        assertNull(state.metrics)
        assertSame(edit, state.activeEdit)
        assertTrue(state.hasActiveDraftChanges)
        assertEquals(StaleEditRecovery.VerifyAppliedEdit, state.staleEditRecovery)
        assertEquals(
            UiText.Resource(R.string.operation_edit_unverified),
            state.editorMessage
        )

        document.replaceMetricsOverride = null
        state.discardActiveEdit(edit.generation)

        assertEquals(EditorDocumentStatus.Ready, state.status)
        assertNull(state.activeEdit)
        assertEquals(listOf("after"), state.blocks.map { block -> block.block.text })
        assertEquals(FIRST_EDIT_REVISION, state.metrics?.revision)
        assertNull(state.staleEditRecovery)
    }

    /** Verifies only a successful source write advances the clean revision baseline. */
    @Test
    fun marksOnlyASuccessfullyWrittenSourceRevisionAsSaved() = runBlocking {
        val document = FakeEditorDocument("before")
        val state = EditorDocumentState(document, ImmediateTestDispatcher)
        state.loadInitialViewport()
        state.activateDocumentAt(Utf16Range(start = 0L, end = 0L))
        val edit = requireNotNull(state.activeEdit)
        state.updateActiveDraftStatus(edit.generation, hasChanges = true)
        state.commitActiveEdit(
            generation = edit.generation,
            text = "after",
            selection = Utf16Range(start = 5, end = 5)
        )
        assertTrue(state.hasDocumentChanges)

        lateinit var sourceSnapshot: TestEditorDocumentSnapshot
        val savedRevision = state.saveDocument(DocumentSavePurpose.Source) { snapshot, bytes ->
            sourceSnapshot = snapshot as TestEditorDocumentSnapshot
            assertEquals(FIRST_EDIT_REVISION, sourceSnapshot.revision)
            assertEquals("after", sourceSnapshot.text)
            assertArrayEquals("after".toByteArray(Charsets.UTF_8), sourceSnapshot.copyUtf8Bytes())
            assertEquals(5L, bytes)
            assertFalse(sourceSnapshot.isClosed)
            assertEquals(EditorDocumentStatus.Ready, state.status)
            assertFalse(state.canCloseSafely)
            assertEquals(5L, sourceSnapshot.consume())
            assertTrue(sourceSnapshot.isClosed)
            assertEquals(1, sourceSnapshot.writeCallCount)
            val repeatedWrite = runCatching { sourceSnapshot.consume() }.exceptionOrNull()
            assertTrue(repeatedWrite is IllegalStateException)
        }

        assertEquals(FIRST_EDIT_REVISION, savedRevision)
        assertEquals(EditorDocumentStatus.Ready, state.status)
        assertTrue(state.canCloseSafely)
        assertFalse(state.hasDocumentChanges)
        assertFalse(state.hasUnsavedChanges)
        assertSame(sourceSnapshot, document.capturedSnapshots.single())
        assertTrue(sourceSnapshot.isClosed)
        assertEquals(1, sourceSnapshot.closeCallCount)
    }

    /** Verifies a successful copy never advances the source revision baseline. */
    @Test
    fun keepsSourceDirtyAfterSuccessfulCopy() = runBlocking {
        val document = FakeEditorDocument("before")
        val state = EditorDocumentState(document, ImmediateTestDispatcher)
        state.loadInitialViewport()
        state.activateDocumentAt(Utf16Range(start = 0L, end = 0L))
        val edit = requireNotNull(state.activeEdit)
        state.updateActiveDraftStatus(edit.generation, hasChanges = true)
        state.commitActiveEdit(
            generation = edit.generation,
            text = "after",
            selection = Utf16Range(start = 5, end = 5)
        )

        lateinit var copiedSnapshot: TestEditorDocumentSnapshot
        val copiedRevision =
            state.saveDocument(DocumentSavePurpose.Copy) { snapshot, bytes ->
                copiedSnapshot = snapshot as TestEditorDocumentSnapshot
                assertEquals(FIRST_EDIT_REVISION, copiedSnapshot.revision)
                assertEquals("after", copiedSnapshot.text)
                assertEquals(5L, bytes)
                assertFalse(copiedSnapshot.isClosed)
            }

        assertEquals(FIRST_EDIT_REVISION, copiedRevision)
        assertTrue(state.hasDocumentChanges)
        assertTrue(state.hasUnsavedChanges)
        assertTrue(state.canCloseSafely)
        assertSame(copiedSnapshot, document.capturedSnapshots.single())
        assertTrue(copiedSnapshot.isClosed)
        assertEquals(1, copiedSnapshot.closeCallCount)
    }

    /** Verifies independent reads retain one exact revision until their owner closes it. */
    @Test
    fun capturesAnIndependentlyOwnedDocumentRevision() = runBlocking {
        val document = FakeEditorDocument("before")
        val state = EditorDocumentState(document, ImmediateTestDispatcher)
        state.loadInitialViewport()

        val captured = requireNotNull(state.captureDocumentRevision())
        val snapshot = captured.snapshot as TestEditorDocumentSnapshot
        assertEquals(INITIAL_REVISION, captured.metrics.revision)
        assertEquals("before", snapshot.text)
        assertFalse(snapshot.isClosed)

        state.activateDocumentAt(Utf16Range(start = 0L, end = 0L))
        val edit = requireNotNull(state.activeEdit)
        state.updateActiveDraftStatus(edit.generation, hasChanges = true)
        state.commitActiveEdit(
            generation = edit.generation,
            text = "after",
            selection = Utf16Range(start = 5L, end = 5L)
        )

        assertEquals("before", snapshot.text)
        assertFalse(snapshot.isClosed)
        captured.close()
        assertTrue(snapshot.isClosed)
        assertEquals(1, snapshot.closeCallCount)
    }

    /** Verifies saving uses exact serialized bytes rather than logical editor bytes. */
    @Test
    fun passesSerializedByteLengthToSave() = runBlocking {
        val document = FakeEditorDocument("a")
        val state = EditorDocumentState(document, ImmediateTestDispatcher)
        state.loadInitialViewport()
        state.activateDocumentAt(Utf16Range(start = 0L, end = 0L))
        val edit = requireNotNull(state.activeEdit)
        document.replaceMetricsOverride = { metrics ->
            metrics.copy(serializedByteLength = metrics.byteLength + 3L)
        }
        state.updateActiveDraftStatus(edit.generation, hasChanges = true)
        state.commitActiveEdit(
            generation = edit.generation,
            text = "b",
            selection = Utf16Range(start = 1, end = 1)
        )

        var receivedByteLength: Long? = null
        val savedRevision = state.saveDocument(DocumentSavePurpose.Source) { _, bytes ->
            receivedByteLength = bytes
        }

        assertEquals(FIRST_EDIT_REVISION, savedRevision)
        assertEquals(4L, receivedByteLength)
        assertFalse(state.hasUnsavedChanges)
    }

    /** Verifies a successful older source write never marks a concurrent edit clean. */
    @Test
    fun keepsANewerConcurrentEditDirtyAfterSourceSaveCompletes() = runBlocking {
        val document = FakeEditorDocument("before")
        val state = EditorDocumentState(document, ImmediateTestDispatcher)
        state.loadInitialViewport()
        state.activateDocumentAt(Utf16Range(start = 0L, end = 0L))
        val firstEdit = requireNotNull(state.activeEdit)
        state.updateActiveDraftStatus(firstEdit.generation, hasChanges = true)
        state.commitActiveEdit(
            generation = firstEdit.generation,
            text = "after",
            selection = Utf16Range(start = 5, end = 5)
        )
        val concurrentEdit = requireNotNull(state.activeEdit)
        val sourceSaveStarted = CompletableDeferred<Unit>()
        val finishSourceSave = CompletableDeferred<Unit>()
        lateinit var sourceSnapshot: TestEditorDocumentSnapshot
        val sourceSave = async {
            state.saveDocument(DocumentSavePurpose.Source) { snapshot, bytes ->
                sourceSnapshot = snapshot as TestEditorDocumentSnapshot
                assertEquals(FIRST_EDIT_REVISION, sourceSnapshot.revision)
                assertEquals("after", sourceSnapshot.text)
                assertArrayEquals(
                    "after".toByteArray(Charsets.UTF_8),
                    sourceSnapshot.copyUtf8Bytes()
                )
                assertEquals(5L, bytes)
                sourceSaveStarted.complete(Unit)
                finishSourceSave.await()
                assertEquals("after", sourceSnapshot.text)
                assertArrayEquals(
                    "after".toByteArray(Charsets.UTF_8),
                    sourceSnapshot.copyUtf8Bytes()
                )
                assertEquals(5L, sourceSnapshot.consume())
                assertEquals(1, sourceSnapshot.writeCallCount)
            }
        }
        sourceSaveStarted.await()

        assertEquals(EditorDocumentStatus.Ready, state.status)
        assertFalse(state.canCloseSafely)
        assertTrue(state.canAcceptActiveDraftInput(concurrentEdit.generation))
        val competingSave =
            state.saveDocument(DocumentSavePurpose.Copy) { _, _ -> error("competing save started") }
        assertNull(competingSave)
        state.updateActiveDraftStatus(concurrentEdit.generation, hasChanges = true)
        state.commitActiveEdit(
            generation = concurrentEdit.generation,
            text = "newer",
            selection = Utf16Range(start = 5, end = 5)
        )
        assertEquals("newer", document.text)
        assertEquals(SECOND_EDIT_REVISION, state.metrics?.revision)
        assertEquals("after", sourceSnapshot.text)
        assertArrayEquals(
            "after".toByteArray(Charsets.UTF_8),
            sourceSnapshot.copyUtf8Bytes()
        )
        assertFalse(sourceSnapshot.isClosed)
        assertEquals(1, document.capturedSnapshots.size)

        finishSourceSave.complete(Unit)
        assertEquals(FIRST_EDIT_REVISION, sourceSave.await())
        assertEquals(EditorDocumentStatus.Ready, state.status)
        assertTrue(state.canCloseSafely)
        assertTrue(state.hasDocumentChanges)
        assertFalse(state.hasActiveDraftChanges)
        assertTrue(state.hasUnsavedChanges)
        assertTrue(sourceSnapshot.isClosed)
        assertEquals(1, sourceSnapshot.closeCallCount)
    }

    /** Verifies failed and cancelled writes never advance the source baseline. */
    @Test
    fun keepsDocumentDirtyAfterFailedOrCancelledWrite() = runBlocking {
        val document = FakeEditorDocument("before")
        val state = EditorDocumentState(document, ImmediateTestDispatcher)
        state.loadInitialViewport()
        state.activateDocumentAt(Utf16Range(start = 0L, end = 0L))
        val edit = requireNotNull(state.activeEdit)
        state.updateActiveDraftStatus(edit.generation, hasChanges = true)
        state.commitActiveEdit(
            generation = edit.generation,
            text = "after",
            selection = Utf16Range(start = 5, end = 5)
        )

        val failure = runCatching {
            state.saveDocument(DocumentSavePurpose.Source) { _, _ ->
                error("test write failure")
            }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(EditorDocumentStatus.Ready, state.status)
        assertTrue(state.hasDocumentChanges)
        assertTrue(document.capturedSnapshots.single().isClosed)
        assertEquals(1, document.capturedSnapshots.single().closeCallCount)

        val cancellation = runCatching {
            state.saveDocument(DocumentSavePurpose.Copy) { _, _ ->
                throw CancellationException("test cancellation")
            }
        }.exceptionOrNull()
        assertTrue(cancellation is CancellationException)
        assertEquals(EditorDocumentStatus.Ready, state.status)
        assertTrue(state.hasDocumentChanges)
        assertTrue(state.hasUnsavedChanges)
        assertEquals(2, document.capturedSnapshots.size)
        assertTrue(document.capturedSnapshots.all(TestEditorDocumentSnapshot::isClosed))
        assertTrue(document.capturedSnapshots.all { snapshot -> snapshot.closeCallCount == 1 })
    }

    /** Verifies a dirty bounded draft cannot be omitted from Save As output. */
    @Test
    fun refusesToSaveWhileTheActiveDraftIsDirty() = runBlocking {
        val document = FakeEditorDocument("draft")
        val state = EditorDocumentState(document, ImmediateTestDispatcher)
        state.loadInitialViewport()
        state.activateDocumentAt(Utf16Range(start = 0L, end = 0L))
        val edit = requireNotNull(state.activeEdit)
        state.updateActiveDraftStatus(edit.generation, hasChanges = true)
        var writeCalled = false

        val savedRevision =
            state.saveDocument(DocumentSavePurpose.Copy) { _, _ -> writeCalled = true }

        assertNull(savedRevision)
        assertFalse(writeCalled)
        assertEquals(EditorDocumentStatus.Ready, state.status)
        assertTrue(state.hasActiveDraftChanges)
        assertTrue(state.hasUnsavedChanges)
        assertTrue(document.capturedSnapshots.isEmpty())
    }

    /** Verifies confirmed transient content remains dirty until explicitly saved. */
    @Test
    fun retainsAnUnsavedInitialRevision() = runBlocking {
        val document = FakeEditorDocument("received")
        document.advanceExternally(FIRST_EDIT_REVISION)
        val state =
            EditorDocumentState(
                document = document,
                workerDispatcher = ImmediateTestDispatcher,
                initialRevision = FIRST_EDIT_REVISION,
                initialUnsavedContent = true
            )

        state.loadInitialViewport()

        assertEquals(FIRST_EDIT_REVISION, state.metrics?.revision)
        assertTrue(state.hasDocumentChanges)
        assertTrue(state.hasUnsavedChanges)
        state.close()
    }

    /** Verifies close releases the document once and rejects all later work. */
    @Test
    fun closesIdempotentlyAndClearsTransientState() = runBlocking {
        val document = FakeEditorDocument("temporary")
        val state = EditorDocumentState(document, ImmediateTestDispatcher)
        state.loadInitialViewport()
        state.activateDocumentAt(Utf16Range(start = 0L, end = 0L))
        val edit = requireNotNull(state.activeEdit)
        state.updateActiveDraftStatus(edit.generation, hasChanges = true)
        val viewportCallCount = document.viewportCalls.size
        val editWindowCallCount = document.editWindowCalls.size

        state.close()
        state.close()

        assertEquals(1, document.closeCallCount)
        assertEquals(EditorDocumentStatus.Closed, state.status)
        assertNull(state.activeEdit)
        assertTrue(state.blocks.isEmpty())
        assertFalse(state.hasActiveDraftChanges)
        assertFalse(state.hasUnsavedChanges)

        state.loadInitialViewport()
        state.activateDocumentAt(Utf16Range(start = 0L, end = 0L))
        assertEquals(viewportCallCount, document.viewportCalls.size)
        assertEquals(editWindowCallCount, document.editWindowCalls.size)
        assertEquals(EditorDocumentStatus.Closed, state.status)
    }
}

/** Runs every dispatched test continuation immediately on the calling thread. */
private object ImmediateTestDispatcher : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        block.run()
    }
}

/** Records one viewport request made through the editor seam. */
private data class ViewportCall(val cursor: ViewportCursor, val limits: ViewportLimits)

/** Records one reverse viewport request made through the editor seam. */
private data class PreviousViewportCall(val cursor: ViewportCursor, val limits: ViewportLimits)

/** Records one edit-window request made through the editor seam. */
private data class EditWindowCall(
    val revision: Long,
    val selection: Utf16Range,
    val limits: EditWindowLimits
)

/** Records one revision-bound replacement made through the editor seam. */
private data class ReplaceCall(
    val expectedRevision: Long,
    val range: Utf16Range,
    val replacement: String
)

/** Implements deterministic bounded document behavior for state tests. */
private class FakeEditorDocument(
    initialText: String,
    private val boundedWindowRange: Utf16Range? = null
) : EditorDocument {
    private var revision = INITIAL_REVISION
    private var isClosed = false
    private val snapshots = mutableListOf<TestEditorDocumentSnapshot>()

    var text = initialText
        private set

    var editWindowOverride:
        ((Long, Utf16Range, EditWindowLimits) -> EditWindowSnapshot)? = null
    var replaceMetricsOverride: ((DocumentMetrics) -> DocumentMetrics)? = null
    var onViewport: (() -> Unit)? = null
    var viewportOverride: ((ViewportCursor, ViewportLimits) -> ViewportSnapshot)? = null
    var previousViewportOverride:
        ((ViewportCursor, ViewportLimits) -> ViewportSnapshot)? = null
    var findOverride: ((FindRequest) -> FindBatch)? = null
    var lineStartOverride: ((Long, Long) -> Long)? = null

    val viewportCalls = mutableListOf<ViewportCall>()
    val previousViewportCalls = mutableListOf<PreviousViewportCall>()
    val findCalls = mutableListOf<FindRequest>()
    val lineStartCalls = mutableListOf<Long>()
    val editWindowCalls = mutableListOf<EditWindowCall>()
    val replaceCalls = mutableListOf<ReplaceCall>()
    val capturedSnapshots: List<TestEditorDocumentSnapshot>
        get() = snapshots
    var closeCallCount = 0
        private set

    /** Returns one complete revision-bound test viewport. */
    override fun viewport(cursor: ViewportCursor, limits: ViewportLimits): ViewportSnapshot {
        checkOpen()
        viewportCalls += ViewportCall(cursor = cursor, limits = limits)
        requireCurrentRevision(cursor.revision)
        onViewport?.invoke()
        val override = viewportOverride
        if (override != null) {
            return override(cursor, limits)
        }
        return completeViewport(text = text, revision = revision)
    }

    /** Returns one configured reverse viewport ending at the requested cursor. */
    override fun previousViewport(
        cursor: ViewportCursor,
        limits: ViewportLimits
    ): ViewportSnapshot {
        checkOpen()
        previousViewportCalls += PreviousViewportCall(cursor = cursor, limits = limits)
        requireCurrentRevision(cursor.revision)
        val override = checkNotNull(previousViewportOverride) {
            "fake document has no reverse viewport override"
        }
        return override(cursor, limits)
    }

    /** Returns one configured bounded Find batch for the current revision. */
    override fun find(request: FindRequest): FindBatch {
        checkOpen()
        findCalls += request
        requireCurrentRevision(request.revision)
        return checkNotNull(findOverride) { "fake document has no Find override" }(request)
    }

    /** Returns one configured or derived logical-line start. */
    override fun lineStartUtf16(revision: Long, logicalLine: Long): Long {
        checkOpen()
        requireCurrentRevision(revision)
        lineStartCalls += logicalLine
        lineStartOverride?.let { override -> return override(revision, logicalLine) }
        var currentLine = 0L
        var utf16Offset = 0
        while (currentLine < logicalLine) {
            utf16Offset = text.indexOf('\n', startIndex = utf16Offset) + 1
            check(utf16Offset > 0) { "logical line exceeds the fake document" }
            currentLine = Math.incrementExact(currentLine)
        }
        return utf16Offset.toLong()
    }

    /** Returns one configured or complete bounded test edit window. */
    override fun editWindow(
        revision: Long,
        selection: Utf16Range,
        limits: EditWindowLimits
    ): EditWindowSnapshot {
        checkOpen()
        editWindowCalls +=
            EditWindowCall(
                revision = revision,
                selection = selection,
                limits = limits
            )
        requireCurrentRevision(revision)
        val override = editWindowOverride
        if (override != null) {
            return override(revision, selection, limits)
        }
        val range =
            boundedWindowRange ?: Utf16Range(start = 0, end = text.length.toLong())
        check(selection.start >= range.start && selection.end <= range.end) {
            "selection is outside the fake edit window"
        }
        return editWindowSnapshot(
            text = text,
            revision = revision,
            range = range,
            selection = selection
        )
    }

    /** Applies one exact in-memory replacement or reports a stale revision. */
    override fun replace(
        expectedRevision: Long,
        range: Utf16Range,
        replacement: String
    ): DocumentMetrics {
        checkOpen()
        replaceCalls +=
            ReplaceCall(
                expectedRevision = expectedRevision,
                range = range,
                replacement = replacement
            )
        requireCurrentRevision(expectedRevision)
        check(range.end <= text.length.toLong()) { "replacement range exceeds the fake document" }
        text =
            buildString(text.length - (range.end - range.start).toInt() + replacement.length) {
                append(text, 0, range.start.toInt())
                append(replacement)
                append(text, range.end.toInt(), text.length)
            }
        revision = Math.incrementExact(revision)
        val metrics = editableMetrics(text = text, revision = revision)
        return replaceMetricsOverride?.invoke(metrics) ?: metrics
    }

    /** Captures one immutable revision independently from subsequent document edits. */
    override fun captureSnapshot(expectedRevision: Long): TestEditorDocumentSnapshot {
        checkOpen()
        requireCurrentRevision(expectedRevision)
        return TestEditorDocumentSnapshot(revision = revision, text = text).also {
            snapshots += it
        }
    }

    /** Advances the native revision without changing the visible test text. */
    fun advanceExternally(newRevision: Long) {
        require(newRevision > revision) { "new revision must advance the fake document" }
        revision = newRevision
    }

    /** Records a close and rejects future document operations. */
    override fun close() {
        closeCallCount += 1
        isClosed = true
    }

    /** Fails when an operation is attempted after close. */
    private fun checkOpen() {
        check(!isClosed) { "fake document is closed" }
    }

    /** Fails with the production stale-revision message contract. */
    private fun requireCurrentRevision(expectedRevision: Long) {
        if (expectedRevision != revision) {
            throw StaleDocumentRevisionException(
                expectedRevision = expectedRevision,
                actualRevision = revision
            )
        }
    }
}

/** Creates metrics consistent with one normalized test string. */
private fun editableMetrics(text: String, revision: Long): DocumentMetrics {
    val byteLength = text.toByteArray(Charsets.UTF_8).size.toLong()
    return DocumentMetrics(
        revision = revision,
        byteLength = byteLength,
        serializedByteLength = byteLength,
        characterLength = text.codePointCount(0, text.length).toLong(),
        utf16Length = text.length.toLong(),
        lineCount = text.count { character -> character == '\n' }.toLong() + 1,
        wordCount = countTestWords(text),
        hasUtf8Bom = false,
        hasLfLineEndings = '\n' in text,
        hasCrlfLineEndings = false,
        hasCrLineEndings = false,
        insertedLineEnding = DocumentLineEnding.Lf,
        isEditable = true
    )
}

/** Counts runs of non-whitespace characters in one test document. */
private fun countTestWords(text: String): Long {
    var words = 0L
    var previousIsWord = false
    text.forEach { character ->
        val isWord = !character.isWhitespace()
        if (isWord && !previousIsWord) {
            words += 1L
        }
        previousIsWord = isWord
    }
    return words
}

/** Creates deterministic equal-width lines for viewport paging tests. */
private fun pagedDocumentText(pageCount: Int): String {
    require(pageCount in 1..TEST_VIEWPORT_PAGE_COUNT) {
        "test page count is outside the supported range"
    }
    return List(pageCount) { pageIndex -> "page $pageIndex" }.joinToString(separator = "\n")
}

/** Creates one forward page and both anchors for a deterministic test document. */
private fun forwardPageSnapshot(text: String, revision: Long, pageIndex: Int): ViewportSnapshot {
    val complete = completeViewport(text = text, revision = revision)
    require(pageIndex in complete.blocks.indices) { "forward page index is outside the document" }
    val cursor =
        ViewportCursor(
            revision = revision,
            line = pageIndex.toLong(),
            utf16Offset = 0
        )
    return ViewportSnapshot(
        metrics = complete.metrics,
        blocks = listOf(complete.blocks[pageIndex]),
        previous = if (pageIndex == 0) null else cursor,
        next =
            if (pageIndex + 1 < complete.blocks.size) {
                cursor.copy(line = cursor.line + 1)
            } else {
                null
            }
    )
}

/** Creates one fixed-width block from a single-line test document. */
private fun sameLinePageSnapshot(text: String, revision: Long, pageIndex: Int): ViewportSnapshot {
    require(text.isNotEmpty()) { "same-line test document must not be empty" }
    require(text.length % TEST_SAME_LINE_PAGE_UTF16_UNITS == 0) {
        "same-line test document must contain complete pages"
    }
    val pageCount = text.length / TEST_SAME_LINE_PAGE_UTF16_UNITS
    require(pageIndex in 0 until pageCount) { "same-line page index is outside the document" }
    val globalStart = Math.multiplyExact(pageIndex, TEST_SAME_LINE_PAGE_UTF16_UNITS)
    val globalEnd = Math.addExact(globalStart, TEST_SAME_LINE_PAGE_UTF16_UNITS)
    return ViewportSnapshot(
        metrics = editableMetrics(text = text, revision = revision),
        blocks =
            listOf(
                RenderBlock(
                    logicalLine = 0L,
                    globalUtf16Start = globalStart.toLong(),
                    globalUtf16End = globalEnd.toLong(),
                    lineTerminatorUtf16Units = 0,
                    text = text.substring(globalStart, globalEnd),
                    continuesAtStart = globalStart > 0,
                    continuesAtEnd = globalEnd < text.length
                )
            ),
        previous =
            if (globalStart == 0) {
                null
            } else {
                ViewportCursor(
                    revision = revision,
                    line = 0L,
                    utf16Offset = globalStart.toLong()
                )
            },
        next =
            if (globalEnd == text.length) {
                null
            } else {
                ViewportCursor(
                    revision = revision,
                    line = 0L,
                    utf16Offset = globalEnd.toLong()
                )
            }
    )
}

/** Creates the one-page response immediately preceding a deterministic end anchor. */
private fun previousPageSnapshot(
    text: String,
    revision: Long,
    endPageIndex: Int
): ViewportSnapshot {
    require(endPageIndex > 0) { "reverse page end must follow the document origin" }
    val previousPage =
        forwardPageSnapshot(
            text = text,
            revision = revision,
            pageIndex = endPageIndex - 1
        )
    return previousPage.copy(
        next =
            ViewportCursor(
                revision = revision,
                line = endPageIndex.toLong(),
                utf16Offset = 0
            )
    )
}

/** Shifts one test block while preserving its encoded text length. */
private fun RenderBlock.shiftedBy(utf16Units: Long): RenderBlock {
    require(utf16Units != 0L) { "test block shift must be nonzero" }
    return copy(
        globalUtf16Start = Math.addExact(globalUtf16Start, utf16Units),
        globalUtf16End = Math.addExact(globalUtf16End, utf16Units)
    )
}

/** Creates a complete line-aligned viewport for one normalized test string. */
private fun completeViewport(text: String, revision: Long): ViewportSnapshot {
    val blocks = ArrayList<RenderBlock>()
    var logicalLine = 0L
    var lineStart = 0
    while (true) {
        val lineTerminator = text.indexOf('\n', startIndex = lineStart)
        val lineEnd =
            if (lineTerminator < 0) {
                text.length
            } else {
                lineTerminator
            }
        blocks +=
            RenderBlock(
                logicalLine = logicalLine,
                globalUtf16Start = lineStart.toLong(),
                globalUtf16End = lineEnd.toLong(),
                lineTerminatorUtf16Units = if (lineTerminator < 0) 0 else 1,
                text = text.substring(lineStart, lineEnd),
                continuesAtStart = false,
                continuesAtEnd = false
            )
        if (lineTerminator < 0) {
            break
        }
        logicalLine += 1
        lineStart = lineTerminator + 1
    }
    return ViewportSnapshot(
        metrics = editableMetrics(text = text, revision = revision),
        blocks = blocks,
        previous = null,
        next = null
    )
}

/** Creates one bounded edit snapshot with a scalar-aligned global range. */
private fun editWindowSnapshot(
    text: String,
    revision: Long,
    range: Utf16Range,
    selection: Utf16Range
): EditWindowSnapshot {
    val rangeStart = range.start.toInt()
    val precedingTerminator = text.lastIndexOf('\n', startIndex = rangeStart - 1)
    val lineStart = precedingTerminator + 1
    val startLine = text.substring(0, rangeStart).count { character -> character == '\n' }
    return EditWindowSnapshot(
        metrics = editableMetrics(text = text, revision = revision),
        range = range,
        selection = selection,
        start =
            ViewportCursor(
                revision = revision,
                line = startLine.toLong(),
                utf16Offset = (rangeStart - lineStart).toLong()
            ),
        text = text.substring(rangeStart, range.end.toInt()),
        hasPrevious = range.start > 0,
        hasNext = range.end < text.length
    )
}
