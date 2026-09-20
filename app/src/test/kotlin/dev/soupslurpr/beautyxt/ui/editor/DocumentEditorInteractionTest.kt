package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.text.TextRange
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.document.FindMatch
import dev.soupslurpr.beautyxt.document.Utf16Range
import dev.soupslurpr.beautyxt.document.ViewportCursor
import dev.soupslurpr.beautyxt.ui.UiText
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies deterministic interaction decisions shared by editor entry points. */
class DocumentEditorInteractionTest {
    /** Verifies bounded editor scrolling prefetches only an available nearby edge. */
    @Test
    fun selectsAutomaticEditWindowPrefetchDirection() {
        assertEquals(
            AutomaticEditWindowDirection.Earlier,
            automaticEditWindowDirection(
                scrollValuePixels = 100,
                maxScrollPixels = 1_000,
                viewportHeightPixels = 200,
                hasPrevious = true,
                hasNext = true
            )
        )
        assertEquals(
            AutomaticEditWindowDirection.Later,
            automaticEditWindowDirection(
                scrollValuePixels = 850,
                maxScrollPixels = 1_000,
                viewportHeightPixels = 200,
                hasPrevious = true,
                hasNext = true
            )
        )
        assertNull(
            automaticEditWindowDirection(
                scrollValuePixels = 500,
                maxScrollPixels = 1_000,
                viewportHeightPixels = 200,
                hasPrevious = true,
                hasNext = true
            )
        )
        assertNull(
            automaticEditWindowDirection(
                scrollValuePixels = 850,
                maxScrollPixels = 1_000,
                viewportHeightPixels = 200,
                hasPrevious = true,
                hasNext = false
            )
        )
    }

    /** Verifies an automatic anchor correction does not cancel an active fling mutator. */
    @Test
    fun restoresAutomaticEditWindowScrollWithoutCancellingMotion() = runBlocking {
        val scrollState = ScrollState(initial = 0)
        val retainedScroll =
            launch(start = CoroutineStart.UNDISPATCHED) {
                scrollState.scroll {
                    awaitCancellation()
                }
            }

        restoreEditWindowScroll(
            scrollState = scrollState,
            targetScrollPixels = 240,
            preserveScrollMomentum = true
        )

        assertEquals(240, scrollState.value)
        assertTrue(retainedScroll.isActive)
        retainedScroll.cancelAndJoin()
    }

    /** Verifies explicit anchor restoration may replace an existing scroll mutator. */
    @Test
    fun restoresExplicitEditWindowScrollWithARegularMutation() = runBlocking {
        val scrollState = ScrollState(initial = 0)
        val replacedScroll =
            launch(start = CoroutineStart.UNDISPATCHED) {
                scrollState.scroll {
                    awaitCancellation()
                }
            }

        restoreEditWindowScroll(
            scrollState = scrollState,
            targetScrollPixels = 160,
            preserveScrollMomentum = false
        )

        assertEquals(160, scrollState.value)
        assertFalse(replacedScroll.isActive)
    }

    /** Verifies invalid negative restoration targets fail before changing scroll state. */
    @Test
    fun rejectsNegativeEditWindowScrollTargets() {
        for (preserveScrollMomentum in listOf(true, false)) {
            val scrollState = ScrollState(initial = 40)
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    restoreEditWindowScroll(scrollState, -1, preserveScrollMomentum)
                }
            }
            assertEquals(40, scrollState.value)
        }
    }

    /** Verifies retained input sessions keep the field enabled during window loading. */
    @Test
    fun retainsFieldInteractionDuringInPlaceWindowLoading() {
        assertFalse(
            isActiveEditFieldEnabled(
                status = EditorDocumentStatus.LoadingInitial,
                preserveInputSession = true
            )
        )
        assertFalse(
            isActiveEditFieldEnabled(
                status = EditorDocumentStatus.LoadingEditWindow,
                preserveInputSession = false
            )
        )
        assertTrue(
            isActiveEditFieldEnabled(
                status = EditorDocumentStatus.LoadingEditWindow,
                preserveInputSession = true
            )
        )
        assertTrue(
            isActiveEditFieldEnabled(
                status = EditorDocumentStatus.Ready,
                preserveInputSession = false
            )
        )
    }

    /** Verifies retained IME contracts do not permit input to race a window handoff. */
    @Test
    fun retainsEditableImeContractWhileBlockingInput() {
        assertTrue(
            isActiveEditFieldReadOnly(
                canAcceptInput = false,
                preserveInputSession = false
            )
        )
        assertFalse(
            isActiveEditFieldReadOnly(
                canAcceptInput = false,
                preserveInputSession = true
            )
        )
        assertFalse(
            isActiveEditFieldReadOnly(
                canAcceptInput = true,
                preserveInputSession = false
            )
        )
        assertFalse(
            canApplyActiveEditFieldInput(
                isClosePending = true,
                canAcceptEditorInput = true,
                hasPendingEditWindowAction = false,
                documentCanAcceptInput = true
            )
        )
        assertFalse(
            canApplyActiveEditFieldInput(
                isClosePending = false,
                canAcceptEditorInput = false,
                hasPendingEditWindowAction = false,
                documentCanAcceptInput = true
            )
        )
        assertFalse(
            canApplyActiveEditFieldInput(
                isClosePending = false,
                canAcceptEditorInput = true,
                hasPendingEditWindowAction = true,
                documentCanAcceptInput = true
            )
        )
        assertFalse(
            canApplyActiveEditFieldInput(
                isClosePending = false,
                canAcceptEditorInput = true,
                hasPendingEditWindowAction = false,
                documentCanAcceptInput = false
            )
        )
        assertTrue(
            canApplyActiveEditFieldInput(
                isClosePending = false,
                canAcceptEditorInput = true,
                hasPendingEditWindowAction = false,
                documentCanAcceptInput = true
            )
        )
    }

    /** Verifies focus restoration never re-requests focus from a retained field. */
    @Test
    fun requestsEditorFocusOnlyAfterActualFocusLoss() {
        assertFalse(
            shouldRequestActiveEditFieldFocus(
                canAcceptInput = true,
                shouldRestoreEditorFocus = true,
                isEditorFocused = true
            )
        )
        assertTrue(
            shouldRequestActiveEditFieldFocus(
                canAcceptInput = true,
                shouldRestoreEditorFocus = true,
                isEditorFocused = false
            )
        )
        assertFalse(
            shouldRequestActiveEditFieldFocus(
                canAcceptInput = false,
                shouldRestoreEditorFocus = true,
                isEditorFocused = false
            )
        )
        assertFalse(
            shouldRequestActiveEditFieldFocus(
                canAcceptInput = true,
                shouldRestoreEditorFocus = false,
                isEditorFocused = false
            )
        )
    }

    /** Verifies later-page prefetching stops after new blocks extend the cache. */
    @Test
    fun prefetchesLaterBlocksWithoutChainingAfterAppend() {
        assertFalse(
            shouldPrefetchNextViewport(
                lastVisibleBlockIndex = 23,
                blockCount = 32,
                prefetchBlockCount = 8
            )
        )
        assertTrue(
            shouldPrefetchNextViewport(
                lastVisibleBlockIndex = 24,
                blockCount = 32,
                prefetchBlockCount = 8
            )
        )
        assertFalse(
            shouldPrefetchNextViewport(
                lastVisibleBlockIndex = 24,
                blockCount = 48,
                prefetchBlockCount = 8
            )
        )
    }

    /** Verifies cache eviction moves a retained block away from the later edge. */
    @Test
    fun stopsLaterPrefetchAfterCacheEviction() {
        assertTrue(
            shouldPrefetchNextViewport(
                lastVisibleBlockIndex = 120,
                blockCount = 128,
                prefetchBlockCount = 8
            )
        )
        assertFalse(
            shouldPrefetchNextViewport(
                lastVisibleBlockIndex = 104,
                blockCount = 128,
                prefetchBlockCount = 8
            )
        )
    }

    /** Verifies earlier-page prefetching stops after prepended blocks add a buffer. */
    @Test
    fun prefetchesEarlierBlocksWithoutChainingAfterPrepend() {
        assertTrue(
            shouldPrefetchPreviousViewport(
                firstVisibleBlockIndex = 7,
                blockCount = 32,
                prefetchBlockCount = 8
            )
        )
        assertFalse(
            shouldPrefetchPreviousViewport(
                firstVisibleBlockIndex = 23,
                blockCount = 48,
                prefetchBlockCount = 8
            )
        )
    }

    /** Verifies missing and invalid prefetch coordinates are handled explicitly. */
    @Test
    fun validatesViewportPrefetchCoordinates() {
        assertFalse(shouldPrefetchPreviousViewport(null, blockCount = 0))
        assertFalse(shouldPrefetchNextViewport(null, blockCount = 0))
        assertThrows(IllegalArgumentException::class.java) {
            shouldPrefetchPreviousViewport(firstVisibleBlockIndex = -1, blockCount = 8)
        }
        assertThrows(IllegalArgumentException::class.java) {
            shouldPrefetchNextViewport(lastVisibleBlockIndex = 8, blockCount = 8)
        }
        assertThrows(IllegalArgumentException::class.java) {
            shouldPrefetchNextViewport(
                lastVisibleBlockIndex = null,
                blockCount = 8,
                prefetchBlockCount = 0
            )
        }
    }

    /** Verifies loading rows cannot retain identity across cursor changes. */
    @Test
    fun keysViewportLoadingRowsByCursor() {
        assertEquals("viewport-loading:4:16:64", viewportLoadingItemKey("4:16:64"))
        assertFalse(viewportLoadingItemKey("4:16:64") == viewportLoadingItemKey("4:32:128"))
        assertThrows(IllegalArgumentException::class.java) {
            viewportLoadingItemKey(" ")
        }
    }

    /** Verifies transfer actions state exact capacity before the user taps them. */
    @Test
    fun describesTransferCapacityBeforeAction() {
        assertEquals(
            UiText.Resource(R.string.transfer_checking),
            transferCapacityDescription(
                DocumentTransferCapacity(textBytes = null, maxTextBytes = 1_536L)
            )
        )
        assertEquals(
            UiText.Resource(R.string.transfer_fits, listOf("768", "768")),
            transferCapacityDescription(
                DocumentTransferCapacity(textBytes = 768L, maxTextBytes = 768L)
            )
        )
        assertEquals(
            UiText.Resource(R.string.transfer_too_large, listOf("769", "768")),
            transferCapacityDescription(
                DocumentTransferCapacity(textBytes = 769L, maxTextBytes = 768L)
            )
        )
        assertEquals(
            UiText.Resource(R.string.transfer_too_large, listOf("765,915", "1,536")),
            transferCapacityDescription(
                DocumentTransferCapacity(textBytes = 765_915L, maxTextBytes = 1_536L)
            )
        )
        assertThrows(IllegalArgumentException::class.java) {
            formatTransferByteCount(-1L)
        }
    }

    /** Verifies global Find matches become exact block-local highlight ranges. */
    @Test
    fun resolvesFindHighlightsWithinRenderBlocks() {
        assertEquals(
            TextRange(start = 0, end = 3),
            findBlockHighlightRange(
                matchRange = Utf16Range(start = 7L, end = 13L),
                blockUtf16Start = 10L,
                blockUtf16End = 20L
            )
        )
        assertEquals(
            TextRange(start = 7, end = 10),
            findBlockHighlightRange(
                matchRange = Utf16Range(start = 17L, end = 24L),
                blockUtf16Start = 10L,
                blockUtf16End = 20L
            )
        )
        assertNull(
            findBlockHighlightRange(
                matchRange = Utf16Range(start = 20L, end = 24L),
                blockUtf16Start = 10L,
                blockUtf16End = 20L
            )
        )
        assertNull(
            findBlockHighlightRange(
                matchRange = null,
                blockUtf16Start = 10L,
                blockUtf16End = 20L
            )
        )
    }

    /** Verifies impossible render block coordinates fail before highlighting. */
    @Test
    fun rejectsInvalidFindHighlightBlocks() {
        assertThrows(IllegalArgumentException::class.java) {
            findBlockHighlightRange(
                matchRange = Utf16Range(start = 0L, end = 1L),
                blockUtf16Start = -1L,
                blockUtf16End = 1L
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            findBlockHighlightRange(
                matchRange = Utf16Range(start = 0L, end = 1L),
                blockUtf16Start = 2L,
                blockUtf16End = 1L
            )
        }
    }

    /** Verifies Find status describes exact matches and circular boundaries. */
    @Test
    fun describesRetainedFindStatus() {
        val match =
            FindMatch(
                range = Utf16Range(start = 12L, end = 16L),
                start = ViewportCursor(revision = 3L, line = 4L, utf16Offset = 12L)
            )

        assertEquals(
            UiText.Resource(R.string.find_ignores_case),
            findStatusMessage(FindStatus.Idle)
        )
        assertEquals(
            UiText.Resource(R.string.find_case_enabled),
            findStatusMessage(FindStatus.Idle, matchCase = true)
        )
        assertEquals(
            UiText.Resource(R.string.find_searching),
            findStatusMessage(FindStatus.Searching)
        )
        assertEquals(
            UiText.Resource(R.string.find_no_matches),
            findStatusMessage(FindStatus.NoMatches)
        )
        assertEquals(
            UiText.Resource(R.string.find_match_line, listOf(5L)),
            findStatusMessage(FindStatus.Match(match = match, wrappedAt = null))
        )
        assertEquals(
            UiText.Resource(R.string.find_wrapped_beginning, listOf(5L)),
            findStatusMessage(FindStatus.Match(match = match, wrappedAt = FindWrap.Beginning))
        )
        assertEquals(
            UiText.Resource(R.string.find_wrapped_end, listOf(5L)),
            findStatusMessage(FindStatus.Match(match = match, wrappedAt = FindWrap.End))
        )
        assertEquals(
            UiText.Resource(R.string.operation_find_interrupted),
            findStatusMessage(
                FindStatus.Failed(UiText.Resource(R.string.operation_find_interrupted))
            )
        )
    }

    /** Verifies immutable blocks expose explicit line and continuation identities. */
    @Test
    fun describesLogicalLinesWithoutVisualMarkers() {
        assertEquals(
            UiText.Resource(R.string.source_line, listOf(1L)),
            lineNumberDescription(logicalLine = 0L, isContinuation = false)
        )
        assertEquals(
            UiText.Resource(R.string.source_line_continuation, listOf(268435457L)),
            lineNumberDescription(logicalLine = 268435456L, isContinuation = true)
        )
    }

    /** Verifies invalid logical lines cannot enter viewer semantics. */
    @Test
    fun rejectsInvalidLogicalLineDescriptions() {
        assertThrows(IllegalArgumentException::class.java) {
            lineNumberDescription(logicalLine = -1L, isContinuation = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            lineNumberDescription(logicalLine = Long.MAX_VALUE, isContinuation = true)
        }
    }

    /** Verifies visible document ranges ignore auxiliary lazy items and continuations. */
    @Test
    fun resolvesVisibleLogicalLinesFromDocumentItems() {
        val logicalLineByItemKey =
            mapOf(
                "line-5-start" to 4L,
                "line-5-continuation" to 4L,
                "line-8" to 7L
            )

        val visibleRange =
            visibleLogicalLineRange(
                visibleItems =
                    listOf(
                        "earlier-loading",
                        "line-5-start",
                        "line-5-continuation",
                        "line-8",
                        "pagination"
                    ),
                logicalLineForItem = logicalLineByItemKey::get
            )

        assertEquals(
            VisibleLogicalLineRange(firstLogicalLine = 4L, lastLogicalLine = 7L),
            visibleRange
        )
        assertNull(
            visibleLogicalLineRange(
                visibleItems = listOf("pagination", "viewport-failure"),
                logicalLineForItem = logicalLineByItemKey::get
            )
        )
    }

    /** Verifies visible ranges fail before publishing invalid document order. */
    @Test
    fun rejectsInvalidVisibleLogicalLineRanges() {
        assertThrows(IllegalArgumentException::class.java) {
            VisibleLogicalLineRange(firstLogicalLine = 2L, lastLogicalLine = 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            visibleLogicalLineRange(
                visibleItems = listOf(2L, 1L),
                logicalLineForItem = { logicalLine -> logicalLine }
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            visibleLogicalLineRange(
                visibleItems = listOf(-1L),
                logicalLineForItem = { logicalLine -> logicalLine }
            )
        }
    }

    /** Verifies only lazy items with actual viewport pixels contribute line numbers. */
    @Test
    fun resolvesStrictLazyItemViewportIntersections() {
        val viewportStartOffset = 0
        val viewportEndOffset = 100

        assertFalse(
            lazyItemIntersectsViewport(
                itemOffset = -20,
                itemSize = 20,
                viewportStartOffset = viewportStartOffset,
                viewportEndOffset = viewportEndOffset
            )
        )
        assertTrue(
            lazyItemIntersectsViewport(
                itemOffset = -19,
                itemSize = 20,
                viewportStartOffset = viewportStartOffset,
                viewportEndOffset = viewportEndOffset
            )
        )
        assertTrue(
            lazyItemIntersectsViewport(
                itemOffset = 99,
                itemSize = 20,
                viewportStartOffset = viewportStartOffset,
                viewportEndOffset = viewportEndOffset
            )
        )
        assertFalse(
            lazyItemIntersectsViewport(
                itemOffset = viewportEndOffset,
                itemSize = 20,
                viewportStartOffset = viewportStartOffset,
                viewportEndOffset = viewportEndOffset
            )
        )
        assertFalse(
            lazyItemIntersectsViewport(
                itemOffset = 50,
                itemSize = 0,
                viewportStartOffset = viewportStartOffset,
                viewportEndOffset = viewportEndOffset
            )
        )
    }

    /** Verifies invalid lazy item geometry fails before range publication. */
    @Test
    fun rejectsInvalidLazyItemViewportGeometry() {
        assertThrows(IllegalArgumentException::class.java) {
            lazyItemIntersectsViewport(
                itemOffset = 0,
                itemSize = -1,
                viewportStartOffset = 0,
                viewportEndOffset = 100
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            lazyItemIntersectsViewport(
                itemOffset = 0,
                itemSize = 1,
                viewportStartOffset = 100,
                viewportEndOffset = 0
            )
        }
        assertThrows(ArithmeticException::class.java) {
            lazyItemIntersectsViewport(
                itemOffset = Int.MAX_VALUE,
                itemSize = 1,
                viewportStartOffset = 0,
                viewportEndOffset = Int.MAX_VALUE
            )
        }
    }

    /** Verifies one-based range descriptions remain exact at Long boundaries. */
    @Test
    fun describesVisibleLogicalLineRanges() {
        assertEquals(
            UiText.Resource(R.string.source_line_of_total, listOf(1L, 8L)),
            visibleLogicalLineRangeDescription(
                visibleRange =
                    VisibleLogicalLineRange(
                        firstLogicalLine = 0L,
                        lastLogicalLine = 0L
                    ),
                totalLineCount = 8L
            )
        )
        assertEquals(
            UiText.Resource(R.string.source_lines_of_total, listOf(5L, 8L, 8L)),
            visibleLogicalLineRangeDescription(
                visibleRange =
                    VisibleLogicalLineRange(
                        firstLogicalLine = 4L,
                        lastLogicalLine = 7L
                    ),
                totalLineCount = 8L
            )
        )
        assertEquals(
            UiText.Resource(
                R.string.source_lines_of_total,
                listOf(Long.MAX_VALUE - 1L, Long.MAX_VALUE, Long.MAX_VALUE)
            ),
            visibleLogicalLineRangeDescription(
                visibleRange =
                    VisibleLogicalLineRange(
                        firstLogicalLine = Long.MAX_VALUE - 2L,
                        lastLogicalLine = Long.MAX_VALUE - 1L
                    ),
                totalLineCount = Long.MAX_VALUE
            )
        )
    }

    /** Verifies range descriptions reject impossible document metrics. */
    @Test
    fun rejectsInvalidVisibleLogicalLineDescriptions() {
        val visibleRange =
            VisibleLogicalLineRange(firstLogicalLine = 0L, lastLogicalLine = 0L)
        assertThrows(IllegalArgumentException::class.java) {
            visibleLogicalLineRangeDescription(
                visibleRange = visibleRange,
                totalLineCount = 0L
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            visibleLogicalLineRangeDescription(
                visibleRange =
                    VisibleLogicalLineRange(
                        firstLogicalLine = 1L,
                        lastLogicalLine = 1L
                    ),
                totalLineCount = 1L
            )
        }
    }

    /** Verifies section-opening progress remains anchored over cached blocks. */
    @Test
    fun prioritizesOpeningSectionViewportFeedback() {
        assertSame(
            EditorViewportOverlay.OpeningSection,
            editorViewportOverlay(
                status = EditorDocumentStatus.LoadingEditWindow,
                editorMessage = UiText.Literal("stale message")
            )
        )
    }

    /** Verifies edit-window failures remain visible without moving the viewport. */
    @Test
    fun exposesEditWindowFailureOverTheViewport() {
        assertEquals(
            EditorViewportOverlay.Failure(UiText.Resource(R.string.operation_edit_window_failed)),
            editorViewportOverlay(
                status = EditorDocumentStatus.Ready,
                editorMessage = UiText.Resource(R.string.operation_edit_window_failed)
            )
        )
        assertNull(
            editorViewportOverlay(
                status = EditorDocumentStatus.Ready,
                editorMessage = null
            )
        )
    }

    /** Verifies target-aware line progress takes priority over stale editor messages. */
    @Test
    fun prioritizesLineNavigationViewportFeedback() {
        assertEquals(
            EditorViewportOverlay.OpeningLine(targetLogicalLine = 41L),
            editorViewportOverlay(
                status = EditorDocumentStatus.LoadingMore,
                editorMessage = UiText.Literal("stale message"),
                lineViewportStatus =
                    LineViewportStatus.Loading(targetLogicalLine = 41L)
            )
        )
        assertEquals(
            EditorViewportOverlay.LineFailure(targetLogicalLine = 41L),
            editorViewportOverlay(
                status = EditorDocumentStatus.Ready,
                editorMessage = UiText.Literal("stale message"),
                lineViewportStatus =
                    LineViewportStatus.Failed(
                        targetLogicalLine = 41L,
                        message = UiText.Resource(R.string.operation_open_line_failed)
                    )
            )
        )
    }
}
