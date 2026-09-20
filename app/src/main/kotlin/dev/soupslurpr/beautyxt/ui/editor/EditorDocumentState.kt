package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.document.DocumentMetrics
import dev.soupslurpr.beautyxt.document.DocumentSizeLimitException
import dev.soupslurpr.beautyxt.document.EditWindowLimits
import dev.soupslurpr.beautyxt.document.EditWindowSnapshot
import dev.soupslurpr.beautyxt.document.EditorDocument
import dev.soupslurpr.beautyxt.document.EditorDocumentSnapshot
import dev.soupslurpr.beautyxt.document.FindBatch
import dev.soupslurpr.beautyxt.document.FindDirection
import dev.soupslurpr.beautyxt.document.FindMatch
import dev.soupslurpr.beautyxt.document.FindRequest
import dev.soupslurpr.beautyxt.document.RenderBlock
import dev.soupslurpr.beautyxt.document.RustDocument
import dev.soupslurpr.beautyxt.document.StaleDocumentRevisionException
import dev.soupslurpr.beautyxt.document.Utf16Range
import dev.soupslurpr.beautyxt.document.ViewportCursor
import dev.soupslurpr.beautyxt.document.ViewportLimits
import dev.soupslurpr.beautyxt.document.ViewportSnapshot
import dev.soupslurpr.beautyxt.document.hasWellFormedUtf16
import dev.soupslurpr.beautyxt.document.isScalarBoundary
import dev.soupslurpr.beautyxt.markdown.MarkdownPreviewDocument
import dev.soupslurpr.beautyxt.markdown.client.MarkdownRenderer
import dev.soupslurpr.beautyxt.ui.UiText
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val INITIAL_DOCUMENT_REVISION = 0L
private const val INITIAL_EDIT_WINDOW_GENERATION = 0L
private const val VIEWPORT_MAX_BLOCKS = 16
private const val VIEWPORT_MAX_BLOCK_UTF16_UNITS = 4 * 1024
private const val VIEWPORT_MAX_TOTAL_UTF16_UNITS = 32 * 1024
private const val MATCH_PREFIX_CONTEXT_UTF16_UNITS = 64
private const val MAX_CACHED_PAGES = 8
private const val MAX_CACHED_BLOCKS = MAX_CACHED_PAGES * VIEWPORT_MAX_BLOCKS
private const val MAX_CACHED_UTF16_UNITS = 256 * 1024
private val FIND_FAILURE_MESSAGE =
    UiText.Resource(R.string.operation_find_failure)
private val MATCH_VIEWPORT_FAILURE_MESSAGE =
    UiText.Resource(R.string.operation_match_viewport_failure)
private const val CASE_INSENSITIVE_MATCH_UTF16_MULTIPLIER = 2L
internal const val EDIT_WINDOW_UTF16_UNITS = 16 * 1024
internal const val EDIT_DRAFT_MAX_UTF16_UNITS = 32 * 1024
private const val EDIT_SELECTION_CONTEXT_UTF16_UNITS = EDIT_WINDOW_UTF16_UNITS / 2
internal const val TRANSIENT_DOCUMENT_MAX_UTF16_UNITS = 256 * 1024

/** Describes the visible lifecycle state of one editor document. */
internal sealed interface EditorDocumentStatus {
    /** Indicates that the initial viewport has not been requested. */
    data object Idle : EditorDocumentStatus

    /** Indicates that the first bounded viewport is loading. */
    data object LoadingInitial : EditorDocumentStatus

    /** Indicates that bounded document content is ready for interaction. */
    data object Ready : EditorDocumentStatus

    /** Indicates that an adjacent bounded viewport is loading. */
    data object LoadingMore : EditorDocumentStatus

    /** Indicates that a bounded editable window is loading. */
    data object LoadingEditWindow : EditorDocumentStatus

    /** Indicates that one scalar-aligned edit is being applied. */
    data object ApplyingEdit : EditorDocumentStatus

    /** Indicates that a native revision superseded the cached view. */
    data object Stale : EditorDocumentStatus

    /** Indicates that a viewport request failed without changing the document. */
    data class Failed(val message: UiText) : EditorDocumentStatus

    /** Indicates that the owned native document has been closed. */
    data object Closed : EditorDocumentStatus
}

/** Describes one retained random-line viewport request. */
internal sealed interface LineViewportStatus {
    /** Indicates that no random-line viewport request is active. */
    data object Idle : LineViewportStatus

    /** Indicates that a bounded viewport starting at one logical line is loading. */
    data class Loading(val targetLogicalLine: Long) : LineViewportStatus {
        init {
            require(targetLogicalLine >= 0L) { "target logical line must be nonnegative" }
        }
    }

    /** Contains one failed logical-line target and sanitized retry message. */
    data class Failed(val targetLogicalLine: Long, val message: UiText) : LineViewportStatus {
        init {
            require(targetLogicalLine >= 0L) { "target logical line must be nonnegative" }
        }
    }
}

/** Contains one bounded render block with a stable revision-scoped identity. */
internal data class EditorRenderBlock(val revision: Long, val block: RenderBlock) {
    val key =
        "$revision:${block.logicalLine}:${block.globalUtf16Start}:" +
            "${block.globalUtf16End}"
}

/** Contains one immutable bounded edit window with a UI-owned generation. */
internal data class ActiveEditWindow(val generation: Long, val snapshot: EditWindowSnapshot) {
    val localSelection =
        Utf16Range(
            start = snapshot.selection.start - snapshot.range.start,
            end = snapshot.selection.end - snapshot.range.start
        )
}

/** Records one verified logical edit for bounded live-session history. */
internal data class CommittedEditDelta(
    val revisionBefore: Long,
    val revisionAfter: Long,
    val rangeStart: Long,
    val removedText: String,
    val insertedText: String,
    val selectionBefore: Utf16Range,
    val selectionAfter: Utf16Range
) {
    init {
        require(revisionBefore >= INITIAL_DOCUMENT_REVISION) {
            "edit-delta initial revision must be nonnegative"
        }
        require(revisionAfter == Math.incrementExact(revisionBefore)) {
            "edit-delta revision must advance exactly once"
        }
        require(rangeStart >= 0L) { "edit-delta range start must be nonnegative" }
        require(removedText.hasWellFormedUtf16()) {
            "edit-delta removed text contains invalid Unicode"
        }
        require(insertedText.hasWellFormedUtf16()) {
            "edit-delta inserted text contains invalid Unicode"
        }
    }

    /** Returns the retained UTF-16 memory cost of this history entry. */
    val retainedUtf16Units: Int
        get() = Math.addExact(removedText.length, insertedText.length)
}

/** Describes one revision-bound replacement for history or bulk input. */
internal data class DocumentReplacementRequest(
    val expectedRevision: Long,
    val range: Utf16Range,
    val expectedRemovedText: String,
    val replacement: String,
    val selectionAfter: Utf16Range
) {
    init {
        require(expectedRevision >= INITIAL_DOCUMENT_REVISION) {
            "replacement revision must be nonnegative"
        }
        require(range.end - range.start == expectedRemovedText.length.toLong()) {
            "replacement range conflicts with its expected text"
        }
        require(expectedRemovedText.hasWellFormedUtf16()) {
            "replacement expected text contains invalid Unicode"
        }
        require(replacement.hasWellFormedUtf16()) {
            "replacement text contains invalid Unicode"
        }
    }
}

/** Describes one attempt to apply a revision-bound document replacement. */
internal sealed interface DocumentReplacementResult {
    /** Reports the verified revision produced by the replacement. */
    data class Applied(val revision: Long) : DocumentReplacementResult

    /** Indicates that the active generation no longer accepts the request. */
    data object Unavailable : DocumentReplacementResult

    /** Indicates that the replacement failed or became unverifiable. */
    data object Failed : DocumentReplacementResult

    /** Indicates an atomic native save-size rejection, with no document mutation. */
    data object RejectedBySizeLimit : DocumentReplacementResult
}

/** Describes the result of synchronizing one bounded field value with Rust. */
internal sealed interface EditSynchronizationResult {
    /** Contains one verified edit suitable for the session-only history journal. */
    data class Applied(val delta: CommittedEditDelta) : EditSynchronizationResult

    /** Indicates that the field already matches its native window. */
    data object Unchanged : EditSynchronizationResult

    /** Indicates that the current state no longer accepts this generation. */
    data object Unavailable : EditSynchronizationResult

    /** Indicates that the edit failed while retaining recoverable field state. */
    data object Failed : EditSynchronizationResult

    /** Indicates an atomic native save-size rejection, with no document mutation. */
    data object RejectedBySizeLimit : EditSynchronizationResult
}

/** Describes one serialized bounded Find batch without retaining caller workflow state. */
internal sealed interface FindBatchResult {
    /** Indicates that the requested batch cannot run against the current editor state. */
    data object Unavailable : FindBatchResult

    /** Reports one sanitized native or protocol failure. */
    data class Failed(val message: UiText) : FindBatchResult

    /** Returns one validated bounded native batch. */
    data class Batch(val batch: FindBatch) : FindBatchResult
}

/** Describes one exact match-viewport publication attempt. */
internal sealed interface MatchViewportResult {
    /** Indicates that the match no longer belongs to an available document revision. */
    data object Unavailable : MatchViewportResult

    /** Reports one sanitized viewport or protocol failure. */
    data class Failed(val message: UiText) : MatchViewportResult

    /** Indicates that the bounded match viewport replaced the prior cache. */
    data object Published : MatchViewportResult
}

/** Describes one direct bounded-editor navigation attempt. */
internal sealed interface ActiveEditNavigationResult {
    /** Indicates that the requested destination no longer belongs to the active editor. */
    data object Unavailable : ActiveEditNavigationResult

    /** Reports one sanitized destination-loading failure. */
    data class Failed(val message: UiText) : ActiveEditNavigationResult

    /** Indicates that a replacement bounded field now contains the destination. */
    data object Published : ActiveEditNavigationResult
}

/** Identifies whether a successful write durably updates the selected source. */
internal enum class DocumentSavePurpose {
    /** Writes the selected source and advances its clean revision baseline. */
    Source,

    /** Writes a separate copy without changing the source revision baseline. */
    Copy
}

/** Describes why one retained field must cross a stale reload boundary. */
internal enum class StaleEditRecovery {
    DiscardLocalChanges,
    VerifyAppliedEdit
}

/** Stores a hard-bounded set of consecutive Rust viewport pages. */
private data class EditorViewportCache(
    val pages: List<CachedViewportPage> = emptyList(),
    val blocks: List<EditorRenderBlock> = emptyList()
)

/** Stores one request cursor and its bounded response blocks. */
private data class CachedViewportPage(
    val blocks: List<EditorRenderBlock>,
    val utf16Units: Int,
    val previous: ViewportCursor?,
    val next: ViewportCursor?
)

/** Selects how one viewport response changes the bounded page cache. */
private enum class ViewportLoadDirection {
    Replace,
    Next,
    Previous,
    Line,
    Target
}

/** Identifies one exact viewport failure without exposing its state generation. */
internal class ViewportFailureToken private constructor() {
    companion object {
        /** Creates a distinct token for one newly published viewport failure. */
        internal fun create(): ViewportFailureToken = ViewportFailureToken()
    }
}

/** Describes whether one token-bound viewport retry published content. */
internal enum class ViewportRetryResult {
    /** Indicates that the supplied failure no longer owns the retry slot. */
    Unavailable,

    /** Indicates that the matching retry completed without publishing content. */
    NotPublished,

    /** Indicates that an adjacent viewport retry published content. */
    ViewportPublished,

    /** Indicates that a random-line viewport retry published content. */
    LineViewportPublished
}

/** Stores a failed viewport request that is safe to retry. */
private data class FailedViewportRequest(
    val cursor: ViewportCursor,
    val direction: ViewportLoadDirection,
    val failureToken: ViewportFailureToken = ViewportFailureToken.create()
)

/** Caches one adjacent edit window that belongs to an exact active generation. */
private data class PrefetchedEditWindow(
    val sourceGeneration: Long,
    val towardNext: Boolean,
    val snapshot: EditWindowSnapshot
) {
    init {
        require(sourceGeneration > INITIAL_EDIT_WINDOW_GENERATION) {
            "prefetched edit-window generation must be positive"
        }
    }
}

/** Owns one immutable save purpose, revision, and exact encoded byte length. */
private class CapturedSave(
    val purpose: DocumentSavePurpose,
    val revision: Long,
    val serializedByteLength: Long,
    val snapshot: EditorDocumentSnapshot
)

/** Owns one exact immutable revision transferred to an independent operation. */
internal class CapturedDocumentRevision(
    val metrics: DocumentMetrics,
    val snapshot: EditorDocumentSnapshot
) : AutoCloseable {
    /** Closes the captured native revision exactly once. */
    override fun close() {
        snapshot.close()
    }
}

/** Contains one preview model tied to an exact native document revision. */
internal data class RenderedMarkdownRevision(
    val revision: Long,
    val document: MarkdownPreviewDocument,
    val layout: MarkdownPreviewLayout
)

/** Owns one immutable snapshot captured for Markdown rendering. */
private class CapturedMarkdown(
    val revision: Long,
    val serializedByteLength: Long,
    val snapshot: EditorDocumentSnapshot
)

/** Owns a document and exposes hard-bounded Compose editor state. */
@Stable
internal class EditorDocumentState
internal constructor(
    private val document: EditorDocument,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val initialRevision: Long = INITIAL_DOCUMENT_REVISION,
    private val initialUnsavedContent: Boolean = false
) : AutoCloseable {
    private val operations = Mutex()
    private val closeStarted = AtomicBoolean(false)
    private val viewportLimits =
        ViewportLimits(
            maxBlocks = VIEWPORT_MAX_BLOCKS,
            maxBlockUtf16Units = VIEWPORT_MAX_BLOCK_UTF16_UNITS,
            maxTotalUtf16Units = VIEWPORT_MAX_TOTAL_UTF16_UNITS
        )
    private val matchContextLimits =
        ViewportLimits(
            maxBlocks = 1,
            maxBlockUtf16Units = MATCH_PREFIX_CONTEXT_UTF16_UNITS,
            maxTotalUtf16Units = MATCH_PREFIX_CONTEXT_UTF16_UNITS
        )
    private val editWindowLimits = EditWindowLimits(maxUtf16Units = EDIT_WINDOW_UTF16_UNITS)

    private var cache by mutableStateOf(EditorViewportCache())
    private var currentRevision by mutableLongStateOf(initialRevision)
    private var savedRevision by mutableStateOf<Long?>(null)
    private var unsavedReceivedContent by mutableStateOf(initialUnsavedContent)
    private var editWindowGeneration = INITIAL_EDIT_WINDOW_GENERATION
    private var previousCursor by mutableStateOf<ViewportCursor?>(null)
    private var nextCursor by mutableStateOf<ViewportCursor?>(null)
    private var loadingViewportDirection by mutableStateOf<ViewportLoadDirection?>(null)
    private var failedViewportRequest: FailedViewportRequest? = null
    private var failedEditWindowSelection: Utf16Range? = null
    private var failedEditWindowLine: Long? = null
    private var prefetchedEarlierEditWindow: PrefetchedEditWindow? = null
    private var prefetchedLaterEditWindow: PrefetchedEditWindow? = null
    private var activeSaveRevision: Long? = null
    private var findOperationRunning by mutableStateOf(false)

    var status by mutableStateOf<EditorDocumentStatus>(EditorDocumentStatus.Idle)
        private set

    var lineViewportStatus by mutableStateOf<LineViewportStatus>(LineViewportStatus.Idle)
        private set

    var metrics by mutableStateOf<DocumentMetrics?>(null)
        private set

    var activeEdit by mutableStateOf<ActiveEditWindow?>(null)
        private set

    var editorMessage by mutableStateOf<UiText?>(null)
        private set

    var staleEditRecovery by mutableStateOf<StaleEditRecovery?>(null)
        private set

    var hasActiveDraftChanges by mutableStateOf(false)
        private set

    init {
        require(initialRevision >= INITIAL_DOCUMENT_REVISION) {
            "initial document revision must be nonnegative"
        }
    }

    val hasDocumentChanges: Boolean
        get() = unsavedReceivedContent ||
            (savedRevision?.let { revision -> revision != currentRevision } ?: false)

    val hasUnsavedChanges: Boolean
        get() = hasDocumentChanges || hasActiveDraftChanges

    /** Distinguishes untouched received text from a session that has accepted edits. */
    val isUneditedReceivedContent: Boolean
        get() = initialUnsavedContent &&
            currentRevision == initialRevision &&
            !hasActiveDraftChanges &&
            status != EditorDocumentStatus.Closed

    val canCloseSafely: Boolean
        get() =
            status != EditorDocumentStatus.LoadingInitial &&
                status != EditorDocumentStatus.LoadingMore &&
                status != EditorDocumentStatus.LoadingEditWindow &&
                status != EditorDocumentStatus.ApplyingEdit &&
                activeSaveRevision == null &&
                !findOperationRunning &&
                !closeStarted.get()

    /** Allows a newer Find query to wait for superseded match navigation to unwind. */
    val canQueueFind: Boolean
        get() = !closeStarted.get() &&
            !hasActiveDraftChanges &&
            lineViewportStatus == LineViewportStatus.Idle &&
            (status == EditorDocumentStatus.Ready ||
                (status == EditorDocumentStatus.LoadingEditWindow && findOperationRunning))

    val blocks: List<EditorRenderBlock>
        get() = cache.blocks

    val canLoadPrevious: Boolean
        get() =
            status == EditorDocumentStatus.Ready &&
                lineViewportStatus == LineViewportStatus.Idle &&
                !findOperationRunning &&
                activeEdit == null &&
                previousCursor != null &&
                !closeStarted.get()

    val canLoadMore: Boolean
        get() =
            status == EditorDocumentStatus.Ready &&
                lineViewportStatus == LineViewportStatus.Idle &&
                !findOperationRunning &&
                activeEdit == null &&
                nextCursor != null &&
                !closeStarted.get()

    val isLoadingPreviousViewport: Boolean
        get() =
            status == EditorDocumentStatus.LoadingMore &&
                loadingViewportDirection == ViewportLoadDirection.Previous

    val isLoadingNextViewport: Boolean
        get() =
            status == EditorDocumentStatus.LoadingMore &&
                loadingViewportDirection == ViewportLoadDirection.Next

    val hasPreviousViewportFailure: Boolean
        get() =
            status is EditorDocumentStatus.Failed &&
                failedViewportRequest?.direction == ViewportLoadDirection.Previous

    val hasNextViewportFailure: Boolean
        get() =
            status is EditorDocumentStatus.Failed &&
                failedViewportRequest?.direction == ViewportLoadDirection.Next

    val paginationKey: String?
        get() = nextCursor?.let { cursor ->
            "${cursor.revision}:${cursor.line}:${cursor.utf16Offset}"
        }

    val previousPaginationKey: String?
        get() = previousCursor?.let { cursor ->
            "${cursor.revision}:${cursor.line}:${cursor.utf16Offset}"
        }

    /** Returns the opaque identity of the currently retryable viewport failure. */
    val viewportFailureToken: ViewportFailureToken?
        get() = failedViewportRequest?.failureToken

    /** Returns whether one failed bounded editor window can be retried safely. */
    val canRetryEditWindow: Boolean
        get() =
            status == EditorDocumentStatus.Ready &&
                (
                    (activeEdit == null && failedEditWindowSelection != null) ||
                        (
                            activeEdit != null &&
                                !hasActiveDraftChanges &&
                                (
                                    failedEditWindowSelection != null ||
                                        failedEditWindowLine != null
                                    )
                            )
                    ) &&
                !closeStarted.get()

    /** Loads the first bounded viewport exactly once. */
    suspend fun loadInitialViewport() {
        operations.withLock {
            if (status != EditorDocumentStatus.Idle || closeStarted.get()) {
                return
            }
            requestViewportLocked(
                cursor = originCursor(currentRevision),
                direction = ViewportLoadDirection.Replace
            )
        }
    }

    /** Opens a bounded edit window while restoring one exact global selection. */
    suspend fun activateDocumentAt(selection: Utf16Range) {
        operations.withLock {
            val currentMetrics = metrics ?: return
            if (
                status != EditorDocumentStatus.Ready ||
                activeEdit != null ||
                currentMetrics.isEditable != true ||
                selection.end > currentMetrics.utf16Length ||
                closeStarted.get()
            ) {
                return
            }
            requestEditWindowLocked(selection)
        }
    }

    /** Loads the next bounded page when its revision-bound cursor is current. */
    suspend fun loadNextViewport() {
        operations.withLock {
            if (!canLoadMore) {
                return
            }
            val cursor = checkNotNull(nextCursor)
            requestViewportLocked(cursor = cursor, direction = ViewportLoadDirection.Next)
        }
    }

    /** Loads the immediately preceding bounded page when its anchor is current. */
    suspend fun loadPreviousViewport() {
        operations.withLock {
            if (!canLoadPrevious) {
                return
            }
            val cursor = checkNotNull(previousCursor)
            requestViewportLocked(cursor = cursor, direction = ViewportLoadDirection.Previous)
        }
    }

    /** Replaces the visible cache with one bounded page starting at a logical line. */
    suspend fun navigateToLine(logicalLine: Long): Boolean {
        require(logicalLine >= 0L) { "logical line must be nonnegative" }
        return operations.withLock {
            val currentMetrics = metrics ?: return@withLock false
            if (
                status != EditorDocumentStatus.Ready ||
                lineViewportStatus != LineViewportStatus.Idle ||
                activeEdit != null ||
                currentMetrics.revision != currentRevision ||
                logicalLine >= currentMetrics.lineCount ||
                closeStarted.get()
            ) {
                return@withLock false
            }
            requestViewportLocked(
                cursor =
                    ViewportCursor(
                        revision = currentRevision,
                        line = logicalLine,
                        utf16Offset = 0L
                    ),
                direction = ViewportLoadDirection.Line
            )
        }
    }

    /** Resolves an exact reading position without constructing an editable window. */
    suspend fun navigateToSourceOffset(revision: Long, utf16Offset: Long): Boolean =
        operations.withLock {
            val currentMetrics = metrics ?: return@withLock false
            if (
                status != EditorDocumentStatus.Ready ||
                lineViewportStatus != LineViewportStatus.Idle ||
                findOperationRunning || activeEdit != null || closeStarted.get() ||
                revision != currentRevision || revision != currentMetrics.revision ||
                utf16Offset !in 0L..currentMetrics.utf16Length
            ) {
                return@withLock false
            }
            status = EditorDocumentStatus.LoadingMore
            loadingViewportDirection = ViewportLoadDirection.Target
            editorMessage = null
            try {
                val (cursor, snapshot) = withContext(workerDispatcher) {
                    // Line starts are indexed natively. Binary search keeps this bounded even
                    // when the target is near the end of a document with millions of lines.
                    var line = 0L
                    var lineStart = 0L
                    var endLine = currentMetrics.lineCount
                    while (endLine - line > 1L) {
                        val middle = line + (endLine - line) / 2L
                        val start = document.lineStartUtf16(revision, middle)
                        check(start in lineStart..currentMetrics.utf16Length) {
                            "source line start exceeds the current document"
                        }
                        if (start <= utf16Offset) {
                            line = middle
                            lineStart = start
                        } else {
                            endLine = middle
                        }
                    }
                    val cursor = ViewportCursor(revision, line, utf16Offset - lineStart)
                    cursor to document.viewport(cursor, viewportLimits)
                }
                currentCoroutineContext().ensureActive()
                if (closeStarted.get()) return@withLock false
                validateViewport(cursor, ViewportLoadDirection.Target, snapshot)
                check(snapshot.blocks.firstOrNull()?.globalUtf16Start == utf16Offset) {
                    "source viewport does not begin at its requested position"
                }
                applyViewport(snapshot, ViewportLoadDirection.Target)
                true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                if (!closeStarted.get() && !handleStaleRevision(failure)) {
                    editorMessage = UiText.Resource(R.string.operation_source_position_failed)
                }
                false
            } finally {
                loadingViewportDirection = null
                if (!closeStarted.get() && status == EditorDocumentStatus.LoadingMore) {
                    status = EditorDocumentStatus.Ready
                }
            }
        }

    /** Runs and validates one bounded native Find batch against the current revision. */
    suspend fun findBatch(request: FindRequest): FindBatchResult = operations.withLock {
        val currentMetrics = metrics ?: return@withLock FindBatchResult.Unavailable
        if (
            status != EditorDocumentStatus.Ready ||
            lineViewportStatus != LineViewportStatus.Idle ||
            findOperationRunning ||
            hasActiveDraftChanges ||
            request.revision != currentRevision ||
            request.revision != currentMetrics.revision ||
            request.candidateRange.start == request.candidateRange.end ||
            request.candidateRange.end > currentMetrics.utf16Length ||
            closeStarted.get()
        ) {
            return@withLock FindBatchResult.Unavailable
        }

        findOperationRunning = true
        try {
            val batch = withContext(workerDispatcher) { document.find(request) }
            currentCoroutineContext().ensureActive()
            if (closeStarted.get()) {
                return@withLock FindBatchResult.Unavailable
            }
            validateFindBatch(
                request = request,
                expectedMetrics = currentMetrics,
                batch = batch
            )
            FindBatchResult.Batch(batch)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            if (closeStarted.get()) {
                FindBatchResult.Unavailable
            } else {
                handleStaleRevision(failure)
                FindBatchResult.Failed(FIND_FAILURE_MESSAGE)
            }
        } finally {
            findOperationRunning = false
        }
    }

    /** Publishes one bounded viewport retaining readable context before a Find match. */
    suspend fun navigateToMatch(match: FindMatch): MatchViewportResult = operations.withLock {
        val currentMetrics = metrics ?: return@withLock MatchViewportResult.Unavailable
        if (
            status != EditorDocumentStatus.Ready ||
            lineViewportStatus != LineViewportStatus.Idle ||
            findOperationRunning ||
            activeEdit != null ||
            !match.belongsTo(currentMetrics) ||
            closeStarted.get()
        ) {
            return@withLock MatchViewportResult.Unavailable
        }

        findOperationRunning = true
        loadingViewportDirection = ViewportLoadDirection.Target
        try {
            val contextSnapshot =
                if (match.start.utf16Offset > MATCH_PREFIX_CONTEXT_UTF16_UNITS) {
                    withContext(workerDispatcher) {
                        document.previousViewport(
                            cursor = match.start,
                            limits = matchContextLimits
                        )
                    }
                } else {
                    null
                }
            currentCoroutineContext().ensureActive()
            if (closeStarted.get()) {
                return@withLock MatchViewportResult.Unavailable
            }
            contextSnapshot?.let { snapshot ->
                validateViewport(
                    cursor = match.start,
                    direction = ViewportLoadDirection.Previous,
                    snapshot = snapshot,
                    limits = matchContextLimits,
                    validateCacheJoin = false
                )
            }
            val viewportCursor = matchViewportCursor(match, contextSnapshot)
            val snapshot =
                withContext(workerDispatcher) {
                    document.viewport(cursor = viewportCursor, limits = viewportLimits)
                }
            currentCoroutineContext().ensureActive()
            if (closeStarted.get()) {
                return@withLock MatchViewportResult.Unavailable
            }
            val validationCursor =
                matchViewportValidationCursor(
                    match = match,
                    requestedCursor = viewportCursor,
                    snapshot = snapshot
                )
            validateViewport(
                cursor = validationCursor,
                direction = ViewportLoadDirection.Target,
                snapshot = snapshot
            )
            validateMatchViewport(
                match = match,
                requestedCursor = viewportCursor,
                validationCursor = validationCursor,
                snapshot = snapshot
            )
            applyViewport(snapshot = snapshot, direction = ViewportLoadDirection.Target)
            MatchViewportResult.Published
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            if (closeStarted.get()) {
                MatchViewportResult.Unavailable
            } else {
                handleStaleRevision(failure)
                MatchViewportResult.Failed(MATCH_VIEWPORT_FAILURE_MESSAGE)
            }
        } finally {
            loadingViewportDirection = null
            findOperationRunning = false
        }
    }

    /** Replaces one clean bounded field with an exact Find match selection. */
    suspend fun navigateActiveEditToMatch(match: FindMatch): ActiveEditNavigationResult =
        operations.withLock {
            val currentMetrics = metrics
                ?: return@withLock ActiveEditNavigationResult.Unavailable
            if (
                status != EditorDocumentStatus.Ready ||
                lineViewportStatus != LineViewportStatus.Idle ||
                findOperationRunning ||
                activeEdit == null ||
                hasActiveDraftChanges ||
                !match.belongsTo(currentMetrics) ||
                closeStarted.get()
            ) {
                return@withLock ActiveEditNavigationResult.Unavailable
            }
            findOperationRunning = true
            try {
                val snapshot = loadEditWindowLocked(match.range)
                if (snapshot == null) {
                    return@withLock if (
                        status == EditorDocumentStatus.Ready &&
                        activeEdit != null &&
                        !closeStarted.get()
                    ) {
                        editorMessage = MATCH_VIEWPORT_FAILURE_MESSAGE
                        ActiveEditNavigationResult.Failed(MATCH_VIEWPORT_FAILURE_MESSAGE)
                    } else {
                        ActiveEditNavigationResult.Unavailable
                    }
                }
                publishEditWindow(snapshot)
                status = EditorDocumentStatus.Ready
                ActiveEditNavigationResult.Published
            } finally {
                findOperationRunning = false
            }
        }

    /** Replaces one clean bounded field with the start of an exact logical line. */
    suspend fun navigateActiveEditToLine(logicalLine: Long): Boolean {
        require(logicalLine >= 0L) { "logical line must be nonnegative" }
        return operations.withLock {
            requestActiveEditLineLocked(logicalLine)
        }
    }

    /** Returns the rendered source scalar width at one current Find match start. */
    fun findMatchStartScalarUtf16Units(match: FindMatch): Int? {
        val currentMetrics = metrics ?: return null
        if (!match.belongsTo(currentMetrics)) {
            return null
        }
        val editSnapshot = activeEdit?.snapshot
        if (
            editSnapshot != null &&
            match.range.start >= editSnapshot.range.start &&
            match.range.start < editSnapshot.range.end
        ) {
            val localUtf16Offset =
                Math.toIntExact(match.range.start - editSnapshot.range.start)
            if (editSnapshot.text.isScalarBoundary(localUtf16Offset)) {
                return Character.charCount(editSnapshot.text.codePointAt(localUtf16Offset))
            }
            return null
        }
        val block =
            cache.blocks.firstOrNull { editorBlock ->
                val renderBlock = editorBlock.block
                renderBlock.logicalLine == match.start.line &&
                    match.range.start >= renderBlock.globalUtf16Start &&
                    match.range.start < renderBlock.globalUtf16End
            }?.block ?: return null
        val localUtf16Offset =
            Math.toIntExact(Math.subtractExact(match.range.start, block.globalUtf16Start))
        if (
            localUtf16Offset >= block.text.length ||
            !block.text.isScalarBoundary(localUtf16Offset)
        ) {
            return null
        }
        return Character.charCount(block.text.codePointAt(localUtf16Offset))
    }

    /** Retries the exact viewport failure identified before this operation was queued. */
    suspend fun retryViewport(failureToken: ViewportFailureToken): ViewportRetryResult {
        return operations.withLock {
            if (closeStarted.get()) {
                return@withLock ViewportRetryResult.Unavailable
            }
            val failedRequest = failedViewportRequest
                ?: return@withLock ViewportRetryResult.Unavailable
            if (failedRequest.failureToken !== failureToken) {
                return@withLock ViewportRetryResult.Unavailable
            }
            if (failedRequest.direction == ViewportLoadDirection.Line) {
                val lineFailure = lineViewportStatus as? LineViewportStatus.Failed
                    ?: return@withLock ViewportRetryResult.Unavailable
                val currentMetrics = metrics
                    ?: return@withLock ViewportRetryResult.Unavailable
                if (
                    status != EditorDocumentStatus.Ready ||
                    activeEdit != null ||
                    currentMetrics.revision != currentRevision ||
                    failedRequest.cursor.revision != currentRevision ||
                    failedRequest.cursor.utf16Offset != 0L ||
                    lineFailure.targetLogicalLine != failedRequest.cursor.line ||
                    failedRequest.cursor.line >= currentMetrics.lineCount
                ) {
                    return@withLock ViewportRetryResult.Unavailable
                }
            } else if (status !is EditorDocumentStatus.Failed) {
                return@withLock ViewportRetryResult.Unavailable
            }
            val published =
                requestViewportLocked(
                    cursor = failedRequest.cursor,
                    direction = failedRequest.direction
                )
            when {
                !published -> ViewportRetryResult.NotPublished

                failedRequest.direction == ViewportLoadDirection.Line ->
                    ViewportRetryResult.LineViewportPublished

                else -> ViewportRetryResult.ViewportPublished
            }
        }
    }

    /** Dismisses one failed random-line request without changing visible content. */
    suspend fun dismissLineNavigationFailure(): Boolean = operations.withLock {
        val lineFailure = lineViewportStatus as? LineViewportStatus.Failed
            ?: return@withLock false
        if (failedEditWindowLine == lineFailure.targetLogicalLine) {
            if (
                status != EditorDocumentStatus.Ready ||
                activeEdit == null ||
                hasActiveDraftChanges ||
                closeStarted.get()
            ) {
                return@withLock false
            }
            failedEditWindowLine = null
            failedEditWindowSelection = null
            editorMessage = null
            lineViewportStatus = LineViewportStatus.Idle
            return@withLock true
        }
        val failedRequest = failedViewportRequest ?: return@withLock false
        if (status != EditorDocumentStatus.Ready || closeStarted.get()) {
            return@withLock false
        }
        check(
            failedRequest.direction == ViewportLoadDirection.Line &&
                failedRequest.cursor.line == lineFailure.targetLogicalLine
        ) {
            "line viewport failure does not match its retained request"
        }
        failedViewportRequest = null
        lineViewportStatus = LineViewportStatus.Idle
        true
    }

    /** Reloads the document start after a stale revision invalidates cached ranges. */
    suspend fun reloadStaleViewport() {
        operations.withLock {
            if (
                status != EditorDocumentStatus.Stale ||
                hasActiveDraftChanges ||
                closeStarted.get()
            ) {
                return
            }
            requestViewportLocked(
                cursor = originCursor(currentRevision),
                direction = ViewportLoadDirection.Replace
            )
        }
    }

    /** Moves a clean edit window toward earlier or later document content. */
    suspend fun moveEditWindow(generation: Long, towardNext: Boolean, hasDraftChanges: Boolean) {
        operations.withLock {
            val edit = activeEdit ?: return
            if (
                status != EditorDocumentStatus.Ready ||
                edit.generation != generation ||
                hasDraftChanges ||
                closeStarted.get()
            ) {
                return
            }
            val snapshot = edit.snapshot
            val target =
                if (towardNext) {
                    if (!snapshot.hasNext) {
                        return
                    }
                    snapshot.range.end
                } else {
                    if (!snapshot.hasPrevious) {
                        return
                    }
                    snapshot.range.start
                }
            val targetSelection = Utf16Range(start = target, end = target)
            val prefetched = consumePrefetchedEditWindow(edit, towardNext, targetSelection)
            if (prefetched == null) {
                requestEditWindowLocked(targetSelection)
            } else {
                publishEditWindow(prefetched)
                status = EditorDocumentStatus.Ready
            }
        }
    }

    /** Prefetches the clean active window's existing neighbors without changing visible state. */
    suspend fun prefetchAdjacentEditWindows(generation: Long) {
        operations.withLock {
            val edit = activeEdit ?: return
            if (
                status != EditorDocumentStatus.Ready ||
                edit.generation != generation ||
                hasActiveDraftChanges ||
                closeStarted.get()
            ) {
                return
            }
            if (edit.snapshot.hasPrevious) {
                prefetchEditWindow(edit = edit, towardNext = false)
            }
            if (edit.snapshot.hasNext) {
                prefetchEditWindow(edit = edit, towardNext = true)
            }
        }
    }

    /** Applies one verified atomic replacement and reopens its target selection. */
    suspend fun replaceDocumentRange(
        generation: Long,
        request: DocumentReplacementRequest
    ): DocumentReplacementResult = operations.withLock {
        val edit = activeEdit ?: return@withLock DocumentReplacementResult.Unavailable
        val previousMetrics = edit.snapshot.metrics
        if (
            status != EditorDocumentStatus.Ready ||
            edit.generation != generation ||
            hasActiveDraftChanges ||
            request.expectedRevision != currentRevision ||
            previousMetrics.revision != currentRevision ||
            request.range.end > previousMetrics.utf16Length ||
            closeStarted.get()
        ) {
            return@withLock DocumentReplacementResult.Unavailable
        }
        val resultingUtf16Length =
            Math.addExact(
                Math.subtractExact(
                    previousMetrics.utf16Length,
                    request.range.end - request.range.start
                ),
                request.replacement.length.toLong()
            )
        if (request.selectionAfter.end > resultingUtf16Length) {
            return@withLock DocumentReplacementResult.Unavailable
        }
        status = EditorDocumentStatus.ApplyingEdit
        editorMessage = null
        val resultingMetrics =
            try {
                withContext(workerDispatcher + NonCancellable) {
                    document.replace(
                        expectedRevision = request.expectedRevision,
                        range = request.range,
                        replacement = request.replacement
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                if (closeStarted.get()) {
                    return@withLock DocumentReplacementResult.Unavailable
                }
                if (failure is DocumentSizeLimitException) {
                    status = EditorDocumentStatus.Ready
                    return@withLock DocumentReplacementResult.RejectedBySizeLimit
                }
                if (!handleStaleRevision(failure)) {
                    status = EditorDocumentStatus.Ready
                    editorMessage = UiText.Resource(R.string.operation_apply_change_failed)
                }
                return@withLock DocumentReplacementResult.Failed
            }
        if (closeStarted.get()) {
            return@withLock DocumentReplacementResult.Unavailable
        }
        try {
            validateReplacementMetrics(
                previousMetrics = previousMetrics,
                resultingMetrics = resultingMetrics,
                removedText = request.expectedRemovedText,
                replacement = request.replacement
            )
        } catch (_: Exception) {
            recoverUnverifiedAppliedEdit(resultingMetrics.revision)
            return@withLock DocumentReplacementResult.Failed
        }
        currentRevision = resultingMetrics.revision
        clearRevisionBoundViewport()
        clearPrefetchedEditWindows()
        activeEdit = null
        hasActiveDraftChanges = false
        metrics = resultingMetrics
        failedEditWindowSelection = null
        failedEditWindowLine = null
        status = EditorDocumentStatus.Ready
        requestEditWindowLocked(request.selectionAfter)
        DocumentReplacementResult.Applied(resultingMetrics.revision)
    }

    /** Retries the exact bounded editor selection retained after a load failure. */
    suspend fun retryEditWindow() {
        operations.withLock {
            val failedLine = failedEditWindowLine
            if (failedLine != null) {
                if (
                    status == EditorDocumentStatus.Ready &&
                    activeEdit != null &&
                    !hasActiveDraftChanges &&
                    lineViewportStatus is LineViewportStatus.Failed &&
                    !closeStarted.get()
                ) {
                    requestActiveEditLineLocked(failedLine)
                }
                return
            }
            val selection = failedEditWindowSelection ?: return
            if (
                status != EditorDocumentStatus.Ready ||
                (activeEdit != null && hasActiveDraftChanges) ||
                closeStarted.get()
            ) {
                return
            }
            requestEditWindowLocked(selection)
        }
    }

    /** Resolves and opens one logical line while retaining the prior field on failure. */
    private suspend fun requestActiveEditLineLocked(logicalLine: Long): Boolean {
        val currentMetrics = metrics ?: return false
        if (
            status != EditorDocumentStatus.Ready ||
            activeEdit == null ||
            hasActiveDraftChanges ||
            findOperationRunning ||
            logicalLine >= currentMetrics.lineCount ||
            closeStarted.get()
        ) {
            return false
        }
        lineViewportStatus = LineViewportStatus.Loading(logicalLine)
        failedEditWindowLine = null
        failedEditWindowSelection = null
        editorMessage = null
        try {
            val utf16Offset =
                withContext(workerDispatcher) {
                    document.lineStartUtf16(
                        revision = currentRevision,
                        logicalLine = logicalLine
                    )
                }
            currentCoroutineContext().ensureActive()
            check(utf16Offset in 0L..currentMetrics.utf16Length) {
                "line start exceeds the current document"
            }
            val selection = Utf16Range(start = utf16Offset, end = utf16Offset)
            val snapshot = loadEditWindowLocked(selection)
            if (snapshot != null) {
                publishEditWindow(snapshot)
                status = EditorDocumentStatus.Ready
                lineViewportStatus = LineViewportStatus.Idle
                return true
            }
            if (
                status == EditorDocumentStatus.Ready &&
                activeEdit != null &&
                !closeStarted.get()
            ) {
                failedEditWindowLine = logicalLine
                failedEditWindowSelection = selection
                editorMessage = null
                lineViewportStatus =
                    LineViewportStatus.Failed(
                        targetLogicalLine = logicalLine,
                        message = UiText.Resource(R.string.operation_open_line_failed)
                    )
            } else {
                lineViewportStatus = LineViewportStatus.Idle
            }
            return false
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            if (!closeStarted.get() && !handleStaleRevision(failure)) {
                status = EditorDocumentStatus.Ready
                failedEditWindowLine = logicalLine
                editorMessage = null
                lineViewportStatus =
                    LineViewportStatus.Failed(
                        targetLogicalLine = logicalLine,
                        message = UiText.Resource(R.string.operation_open_line_failed)
                    )
            } else {
                lineViewportStatus = LineViewportStatus.Idle
            }
            return false
        }
    }

    /** Moves a clean edit window around one visible anchor while retaining its selection. */
    suspend fun moveEditWindowToAnchor(
        generation: Long,
        towardNext: Boolean,
        anchorUtf16Offset: Long,
        selection: Utf16Range,
        preserveSelection: Boolean,
        hasDraftChanges: Boolean
    ) {
        operations.withLock {
            val edit = activeEdit ?: return
            val currentSnapshot = edit.snapshot
            if (
                status != EditorDocumentStatus.Ready ||
                edit.generation != generation ||
                hasDraftChanges ||
                anchorUtf16Offset !in currentSnapshot.range.start..currentSnapshot.range.end ||
                selection.start < currentSnapshot.range.start ||
                selection.end > currentSnapshot.range.end ||
                closeStarted.get()
            ) {
                return
            }
            val localAnchor = Math.toIntExact(anchorUtf16Offset - currentSnapshot.range.start)
            if (!currentSnapshot.text.isScalarBoundary(localAnchor)) {
                return
            }
            val anchorSelection =
                Utf16Range(start = anchorUtf16Offset, end = anchorUtf16Offset)
            val publishedSelection =
                if (preserveSelection) {
                    selection
                } else {
                    anchorSelection
                }
            val prefetched =
                consumePrefetchedEditWindow(edit, towardNext, publishedSelection)
            val requestSelection =
                if (preserveSelection) {
                    Utf16Range(
                        start = minOf(selection.start, anchorUtf16Offset),
                        end = maxOf(selection.end, anchorUtf16Offset)
                    )
                } else {
                    anchorSelection
                }
            val requestLimits = editWindowLimitsFor(requestSelection)
            val snapshot =
                prefetched
                    ?: loadEditWindowLocked(requestSelection)
                    ?: return
            val revealsNeighbor =
                if (towardNext) {
                    snapshot.range.end > currentSnapshot.range.end
                } else {
                    snapshot.range.start < currentSnapshot.range.start
                }
            if (
                !revealsNeighbor ||
                publishedSelection.start < snapshot.range.start ||
                publishedSelection.end > snapshot.range.end
            ) {
                status = EditorDocumentStatus.Ready
                return
            }
            val publishedSnapshot =
                snapshot.copy(selection = publishedSelection)
            validateEditWindow(
                requestedRevision = currentRevision,
                requestedSelection = publishedSnapshot.selection,
                limits = requestLimits,
                snapshot = publishedSnapshot
            )
            publishEditWindow(publishedSnapshot)
            status = EditorDocumentStatus.Ready
        }
    }

    /** Returns whether the matching active generation may still accept input. */
    fun canAcceptActiveDraftInput(generation: Long): Boolean =
        (status == EditorDocumentStatus.Ready || status == EditorDocumentStatus.ApplyingEdit) &&
            activeEdit?.generation == generation &&
            activeEdit?.snapshot?.metrics?.revision == currentRevision &&
            !closeStarted.get()

    /** Records whether the matching field currently differs from its native window. */
    fun updateActiveDraftStatus(generation: Long, hasChanges: Boolean) {
        if (
            (
                status == EditorDocumentStatus.Ready ||
                    status == EditorDocumentStatus.ApplyingEdit
                ) &&
            activeEdit?.generation == generation
        ) {
            hasActiveDraftChanges = hasChanges
        }
    }

    /** Discards the active draft and reports whether it reloaded the document viewport. */
    suspend fun discardActiveEdit(generation: Long): Boolean = operations.withLock {
        val edit = activeEdit ?: return@withLock false
        val wasStale = status == EditorDocumentStatus.Stale
        if (
            (status != EditorDocumentStatus.Ready && !wasStale) ||
            edit.generation != generation ||
            closeStarted.get()
        ) {
            return@withLock false
        }
        if (
            cache.blocks.isNotEmpty() &&
            cache.blocks.all { block -> block.revision == currentRevision }
        ) {
            activeEdit = null
            hasActiveDraftChanges = false
            editorMessage = null
            status = EditorDocumentStatus.Ready
            false
        } else {
            hasActiveDraftChanges = false
            editorMessage = null
            val cursor =
                if (wasStale || edit.snapshot.metrics.revision != currentRevision) {
                    originCursor(currentRevision)
                } else {
                    editReturnCursor(edit.snapshot)
                }
            requestViewportLocked(cursor = cursor, direction = ViewportLoadDirection.Replace)
            if (closeStarted.get()) {
                return@withLock false
            }
            activeEdit = null
            hasActiveDraftChanges = false
            true
        }
    }

    /** Returns the full logical line containing an edit window's retained selection. */
    private fun editReturnCursor(snapshot: EditWindowSnapshot): ViewportCursor {
        val localSelectionStart =
            Math.toIntExact(snapshot.selection.start - snapshot.range.start)
        var logicalLine = snapshot.start.line
        for (textIndex in 0 until localSelectionStart) {
            if (snapshot.text[textIndex] == '\n') {
                logicalLine = Math.incrementExact(logicalLine)
            }
        }
        check(logicalLine < snapshot.metrics.lineCount) {
            "edit return line exceeds the document"
        }
        return ViewportCursor(
            revision = snapshot.metrics.revision,
            line = logicalLine,
            utf16Offset = 0L
        )
    }

    /** Applies one minimal field diff without replacing its bounded generation. */
    suspend fun commitActiveEdit(
        generation: Long,
        text: String,
        selection: Utf16Range,
        selectionBefore: Utf16Range? = null
    ): EditSynchronizationResult = operations.withLock {
        val edit = activeEdit ?: return@withLock EditSynchronizationResult.Unavailable
        if (
            status != EditorDocumentStatus.Ready ||
            edit.generation != generation ||
            closeStarted.get()
        ) {
            return@withLock EditSynchronizationResult.Unavailable
        }
        val validationMessage = validateDraft(text = text, selection = selection)
        if (validationMessage != null) {
            editorMessage = validationMessage
            return@withLock EditSynchronizationResult.Failed
        }
        val localSelectionBefore = selectionBefore ?: edit.localSelection
        require(localSelectionBefore.end <= edit.snapshot.text.length.toLong()) {
            "pre-edit selection exceeds the active edit window"
        }
        val minimalDiff =
            try {
                findUtf16MinimalDiff(edit.snapshot.text, text)
            } catch (_: IllegalArgumentException) {
                editorMessage = UiText.Resource(R.string.operation_invalid_unicode)
                return@withLock EditSynchronizationResult.Failed
            }
        if (minimalDiff == null) {
            publishCommittedEdit(
                edit = edit,
                metrics = edit.snapshot.metrics,
                text = text,
                selection = selection
            )
            editorMessage = null
            return@withLock EditSynchronizationResult.Unchanged
        }
        hasActiveDraftChanges = true

        val globalRange =
            Utf16Range(
                start = edit.snapshot.range.start + minimalDiff.oldRange.start,
                end = edit.snapshot.range.start + minimalDiff.oldRange.end
            )
        val removedText =
            edit.snapshot.text.substring(
                minimalDiff.oldRange.start.toInt(),
                minimalDiff.oldRange.end.toInt()
            )
        val globalSelectionAfter =
            Utf16Range(
                start = Math.addExact(edit.snapshot.range.start, selection.start),
                end = Math.addExact(edit.snapshot.range.start, selection.end)
            )
        val globalSelectionBefore =
            Utf16Range(
                start = Math.addExact(edit.snapshot.range.start, localSelectionBefore.start),
                end = Math.addExact(edit.snapshot.range.start, localSelectionBefore.end)
            )
        status = EditorDocumentStatus.ApplyingEdit
        editorMessage = null

        val resultingMetrics =
            try {
                withContext(workerDispatcher + NonCancellable) {
                    document.replace(
                        expectedRevision = edit.snapshot.metrics.revision,
                        range = globalRange,
                        replacement = minimalDiff.replacement
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                if (closeStarted.get()) {
                    return@withLock EditSynchronizationResult.Unavailable
                }
                if (failure is DocumentSizeLimitException) {
                    status = EditorDocumentStatus.Ready
                    return@withLock EditSynchronizationResult.RejectedBySizeLimit
                }
                if (!handleStaleRevision(failure)) {
                    status = EditorDocumentStatus.Ready
                    editorMessage = UiText.Resource(R.string.operation_apply_edit_failed)
                }
                return@withLock EditSynchronizationResult.Failed
            }
        if (closeStarted.get()) {
            return@withLock EditSynchronizationResult.Unavailable
        }

        try {
            validateReplacementMetrics(
                previousMetrics = edit.snapshot.metrics,
                resultingMetrics = resultingMetrics,
                removedText = removedText,
                replacement = minimalDiff.replacement
            )
            currentRevision = resultingMetrics.revision
            clearRevisionBoundViewport()
            publishCommittedEdit(
                edit = edit,
                metrics = resultingMetrics,
                text = text,
                selection = selection
            )
        } catch (_: Exception) {
            recoverUnverifiedAppliedEdit(resultingMetrics.revision)
            return@withLock EditSynchronizationResult.Failed
        }
        status = EditorDocumentStatus.Ready
        EditSynchronizationResult.Applied(
            CommittedEditDelta(
                revisionBefore = edit.snapshot.metrics.revision,
                revisionAfter = resultingMetrics.revision,
                rangeStart = globalRange.start,
                removedText = removedText,
                insertedText = minimalDiff.replacement,
                selectionBefore = globalSelectionBefore,
                selectionAfter = globalSelectionAfter
            )
        )
    }

    /**
     * Saves one captured revision without blocking newer document operations.
     *
     * A source save advances the clean baseline only after `saveRevision`
     * returns, so the callback must return only after verifying the write.
     * Returns the captured revision after success, or `null` when saving is
     * unavailable.
     */
    suspend fun saveDocument(
        purpose: DocumentSavePurpose,
        saveRevision: suspend (EditorDocumentSnapshot, Long) -> Unit
    ): Long? {
        val capturedSave =
            operations.withLock {
                if (
                    status != EditorDocumentStatus.Ready ||
                    hasActiveDraftChanges ||
                    activeSaveRevision != null ||
                    closeStarted.get()
                ) {
                    return@withLock null
                }
                val capturedMetrics = metrics ?: return@withLock null
                check(capturedMetrics.revision == currentRevision) {
                    "document metrics revision does not match the current revision"
                }
                val snapshot =
                    try {
                        document.captureSnapshot(currentRevision)
                    } catch (failure: Exception) {
                        if (handleStaleRevision(failure)) {
                            return@withLock null
                        }
                        throw failure
                    }
                CapturedSave(
                    purpose = purpose,
                    revision = currentRevision,
                    serializedByteLength = capturedMetrics.serializedByteLength,
                    snapshot = snapshot
                ).also { save -> activeSaveRevision = save.revision }
            } ?: return null

        var completedSuccessfully = false
        var savedResult: Long? = null
        try {
            saveRevision(
                capturedSave.snapshot,
                capturedSave.serializedByteLength
            )
            completedSuccessfully = true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } finally {
            try {
                capturedSave.snapshot.close()
            } finally {
                withContext(NonCancellable) {
                    operations.withLock {
                        activeSaveRevision = null
                        if (!closeStarted.get() && completedSuccessfully) {
                            savedResult = capturedSave.revision
                            when (capturedSave.purpose) {
                                DocumentSavePurpose.Source -> {
                                    savedRevision = capturedSave.revision
                                    unsavedReceivedContent = false
                                }

                                DocumentSavePurpose.Copy -> Unit
                            }
                        }
                    }
                }
            }
        }
        return savedResult
    }

    /** Captures one stable revision for an independently owned read operation. */
    suspend fun captureDocumentRevision(): CapturedDocumentRevision? = operations.withLock {
        if (
            status != EditorDocumentStatus.Ready ||
            hasActiveDraftChanges ||
            closeStarted.get()
        ) {
            return@withLock null
        }
        val capturedMetrics = metrics ?: return@withLock null
        check(capturedMetrics.revision == currentRevision) {
            "document metrics revision does not match the current revision"
        }
        val snapshot =
            try {
                document.captureSnapshot(currentRevision)
            } catch (failure: Exception) {
                if (handleStaleRevision(failure)) {
                    return@withLock null
                }
                throw failure
            }
        CapturedDocumentRevision(metrics = capturedMetrics, snapshot = snapshot)
    }

    /** Renders one immutable current revision without blocking document mutations. */
    suspend fun renderMarkdown(renderer: MarkdownRenderer): RenderedMarkdownRevision? {
        val captured =
            operations.withLock {
                if (
                    status != EditorDocumentStatus.Ready ||
                    hasActiveDraftChanges ||
                    activeEdit != null ||
                    closeStarted.get()
                ) {
                    return@withLock null
                }
                val capturedMetrics = metrics ?: return@withLock null
                check(capturedMetrics.revision == currentRevision) {
                    "document metrics revision does not match the current revision"
                }
                val snapshot =
                    try {
                        document.captureSnapshot(currentRevision)
                    } catch (failure: Exception) {
                        if (handleStaleRevision(failure)) {
                            return@withLock null
                        }
                        throw failure
                    }
                CapturedMarkdown(
                    revision = currentRevision,
                    serializedByteLength = capturedMetrics.serializedByteLength,
                    snapshot = snapshot
                )
            } ?: return null

        return try {
            val rendered =
                renderer.renderPreview(
                    snapshot = captured.snapshot,
                    expectedBytes = captured.serializedByteLength
                )
            val layout = withContext(workerDispatcher) { prepareMarkdownPreview(rendered.blocks) }
            currentCoroutineContext().ensureActive()
            operations.withLock {
                if (
                    closeStarted.get() ||
                    status != EditorDocumentStatus.Ready ||
                    activeEdit != null ||
                    currentRevision != captured.revision ||
                    metrics?.revision != captured.revision
                ) {
                    null
                } else {
                    RenderedMarkdownRevision(
                        revision = captured.revision,
                        document = rendered,
                        layout = layout
                    )
                }
            }
        } finally {
            captured.snapshot.close()
        }
    }

    /** Closes the owned native document and rejects subsequent work. */
    override fun close() {
        if (!closeStarted.compareAndSet(false, true)) {
            return
        }
        try {
            document.close()
        } finally {
            cache = EditorViewportCache()
            previousCursor = null
            nextCursor = null
            loadingViewportDirection = null
            lineViewportStatus = LineViewportStatus.Idle
            activeEdit = null
            hasActiveDraftChanges = false
            activeSaveRevision = null
            findOperationRunning = false
            failedViewportRequest = null
            failedEditWindowSelection = null
            failedEditWindowLine = null
            clearPrefetchedEditWindows()
            metrics = null
            editorMessage = null
            staleEditRecovery = null
            currentRevision = INITIAL_DOCUMENT_REVISION
            savedRevision = null
            unsavedReceivedContent = false
            status = EditorDocumentStatus.Closed
        }
    }

    /** Requests and validates one bounded page without exposing a full document string. */
    private suspend fun requestViewportLocked(
        cursor: ViewportCursor,
        direction: ViewportLoadDirection
    ): Boolean {
        check(direction != ViewportLoadDirection.Target) {
            "target viewport must use its dedicated transactional request"
        }
        if (direction == ViewportLoadDirection.Replace) {
            clearCachedView()
        }
        loadingViewportDirection = direction
        lineViewportStatus =
            if (direction == ViewportLoadDirection.Line) {
                LineViewportStatus.Loading(cursor.line)
            } else {
                LineViewportStatus.Idle
            }
        status =
            if (direction == ViewportLoadDirection.Replace) {
                EditorDocumentStatus.LoadingInitial
            } else {
                EditorDocumentStatus.LoadingMore
            }
        failedViewportRequest = null
        editorMessage = null

        try {
            val snapshot =
                withContext(workerDispatcher) {
                    when (direction) {
                        ViewportLoadDirection.Replace,
                        ViewportLoadDirection.Next,
                        ViewportLoadDirection.Line,
                        ViewportLoadDirection.Target ->
                            document.viewport(cursor = cursor, limits = viewportLimits)

                        ViewportLoadDirection.Previous ->
                            document.previousViewport(cursor = cursor, limits = viewportLimits)
                    }
                }
            if (closeStarted.get()) {
                return false
            }
            validateViewport(cursor = cursor, direction = direction, snapshot = snapshot)
            applyViewport(snapshot = snapshot, direction = direction)
            status = EditorDocumentStatus.Ready
            lineViewportStatus = LineViewportStatus.Idle
            return true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            if (closeStarted.get()) {
                return false
            }
            if (!handleStaleRevision(failure)) {
                val message = UiText.Resource(R.string.operation_load_section_failed)
                failedViewportRequest =
                    FailedViewportRequest(
                        cursor = cursor,
                        direction = direction
                    )
                status =
                    if (direction == ViewportLoadDirection.Line) {
                        EditorDocumentStatus.Ready
                    } else {
                        EditorDocumentStatus.Failed(message)
                    }
                lineViewportStatus =
                    if (direction == ViewportLoadDirection.Line) {
                        LineViewportStatus.Failed(
                            targetLogicalLine = cursor.line,
                            message = message
                        )
                    } else {
                        LineViewportStatus.Idle
                    }
            } else {
                lineViewportStatus = LineViewportStatus.Idle
            }
            return false
        } finally {
            loadingViewportDirection = null
        }
    }

    /** Requests a revision-bound bounded edit window around one global selection. */
    private suspend fun requestEditWindowLocked(selection: Utf16Range): Boolean {
        val snapshot = loadEditWindowLocked(selection) ?: return false
        publishEditWindow(snapshot)
        status = EditorDocumentStatus.Ready
        return true
    }

    /** Loads and validates one edit window without publishing its field generation. */
    private suspend fun loadEditWindowLocked(selection: Utf16Range): EditWindowSnapshot? {
        status = EditorDocumentStatus.LoadingEditWindow
        editorMessage = null
        val requestLimits = editWindowLimitsFor(selection)
        return try {
            val snapshot =
                withContext(workerDispatcher) {
                    document.editWindow(
                        revision = currentRevision,
                        selection = selection,
                        limits = requestLimits
                    )
                }
            if (closeStarted.get()) {
                return null
            }
            validateEditWindow(
                requestedRevision = currentRevision,
                requestedSelection = selection,
                limits = requestLimits,
                snapshot = snapshot
            )
            snapshot
        } catch (cancellation: CancellationException) {
            // Find can cancel navigation while retaining this document and draft.
            // No mutation has occurred, so release the loading state with the lock.
            if (!closeStarted.get()) {
                status = EditorDocumentStatus.Ready
            }
            throw cancellation
        } catch (failure: Exception) {
            if (closeStarted.get()) {
                return null
            }
            if (!handleStaleRevision(failure)) {
                status = EditorDocumentStatus.Ready
                editorMessage = UiText.Resource(R.string.operation_edit_window_failed)
                failedEditWindowSelection = selection
            }
            null
        }
    }

    /** Publishes one window under a fresh monotonically increasing generation. */
    private fun publishEditWindow(snapshot: EditWindowSnapshot) {
        clearPrefetchedEditWindows()
        failedEditWindowSelection = null
        failedEditWindowLine = null
        editWindowGeneration =
            Math.incrementExact(editWindowGeneration)
        activeEdit =
            ActiveEditWindow(
                generation = editWindowGeneration,
                snapshot = snapshot
            )
        hasActiveDraftChanges = false
        metrics = snapshot.metrics
        editorMessage = null
        staleEditRecovery = null
    }

    /** Publishes one committed baseline while retaining the active field generation. */
    private fun publishCommittedEdit(
        edit: ActiveEditWindow,
        metrics: DocumentMetrics,
        text: String,
        selection: Utf16Range
    ) {
        clearPrefetchedEditWindows()
        val globalSelection =
            Utf16Range(
                start = edit.snapshot.range.start + selection.start,
                end = edit.snapshot.range.start + selection.end
            )
        val committedSnapshot =
            edit.snapshot.copy(
                metrics = metrics,
                range =
                    Utf16Range(
                        start = edit.snapshot.range.start,
                        end = edit.snapshot.range.start + text.length
                    ),
                selection = globalSelection,
                start = edit.snapshot.start.copy(revision = metrics.revision),
                text = text,
                hasNext = edit.snapshot.range.start + text.length < metrics.utf16Length
            )
        validateEditWindow(
            requestedRevision = metrics.revision,
            requestedSelection = globalSelection,
            limits = EditWindowLimits(maxUtf16Units = maxOf(1, text.length)),
            snapshot = committedSnapshot
        )
        activeEdit = ActiveEditWindow(generation = edit.generation, snapshot = committedSnapshot)
        hasActiveDraftChanges = false
        this.metrics = metrics
        editorMessage = null
        staleEditRecovery = null
    }

    /** Retains the field while requiring a reload after an unverified mutation result. */
    private fun recoverUnverifiedAppliedEdit(resultingRevision: Long) {
        currentRevision = resultingRevision
        cache = EditorViewportCache()
        metrics = null
        previousCursor = null
        nextCursor = null
        failedViewportRequest = null
        failedEditWindowSelection = null
        failedEditWindowLine = null
        hasActiveDraftChanges = true
        editorMessage = UiText.Resource(R.string.operation_edit_unverified)
        staleEditRecovery = StaleEditRecovery.VerifyAppliedEdit
        status = EditorDocumentStatus.Stale
    }

    /** Validates authoritative metrics against one bounded replacement delta. */
    private fun validateReplacementMetrics(
        previousMetrics: DocumentMetrics,
        resultingMetrics: DocumentMetrics,
        removedText: String,
        replacement: String
    ) {
        check(resultingMetrics.revision == Math.incrementExact(previousMetrics.revision)) {
            "replacement revision did not advance exactly once"
        }
        check(
            resultingMetrics.byteLength ==
                previousMetrics.byteLength - normalizedUtf8ByteLength(removedText) +
                normalizedUtf8ByteLength(replacement)
        ) {
            "replacement byte length conflicts with its delta"
        }
        check(resultingMetrics.serializedByteLength >= resultingMetrics.byteLength) {
            "replacement serialized byte length is shorter than its logical length"
        }
        check(
            resultingMetrics.characterLength ==
                previousMetrics.characterLength - removedText.codePointCount(
                    0,
                    removedText.length
                ) +
                replacement.codePointCount(0, replacement.length)
        ) {
            "replacement character length conflicts with its delta"
        }
        check(
            resultingMetrics.utf16Length ==
                previousMetrics.utf16Length - removedText.length + replacement.length
        ) {
            "replacement utf-16 length conflicts with its delta"
        }
        check(
            resultingMetrics.lineCount ==
                previousMetrics.lineCount - removedText.count { character -> character == '\n' } +
                replacement.count { character -> character == '\n' }
        ) {
            "replacement line count conflicts with its delta"
        }
        check(resultingMetrics.isEditable == (resultingMetrics.utf16Length <= Int.MAX_VALUE)) {
            "replacement editable flag conflicts with its utf-16 length"
        }
    }

    /** Counts UTF-8 bytes without allocating an encoded copy of bounded text. */
    private fun normalizedUtf8ByteLength(text: String): Long {
        var byteLength = 0L
        var utf16Offset = 0
        while (utf16Offset < text.length) {
            val character = text[utf16Offset]
            val scalarByteLength =
                when {
                    character <= '\u007f' -> 1L

                    character <= '\u07ff' -> 2L

                    character.isHighSurrogate() -> {
                        check(
                            utf16Offset + 1 < text.length &&
                                text[utf16Offset + 1].isLowSurrogate()
                        ) {
                            "utf-8 length input contains an unpaired high surrogate"
                        }
                        utf16Offset += 1
                        4L
                    }

                    else -> {
                        check(!character.isLowSurrogate()) {
                            "utf-8 length input contains an unpaired low surrogate"
                        }
                        3L
                    }
                }
            byteLength = Math.addExact(byteLength, scalarByteLength)
            utf16Offset += 1
        }
        return byteLength
    }

    /** Applies a validated page while enforcing every cache bound. */
    private fun applyViewport(snapshot: ViewportSnapshot, direction: ViewportLoadDirection) {
        val pageBlocks =
            snapshot.blocks.map { block ->
                EditorRenderBlock(revision = snapshot.metrics.revision, block = block)
            }
        val page =
            CachedViewportPage(
                blocks = pageBlocks,
                utf16Units = snapshot.blocks.sumOf { block -> block.text.length },
                previous = snapshot.previous,
                next = snapshot.next
            )
        var pages =
            when (direction) {
                ViewportLoadDirection.Replace,
                ViewportLoadDirection.Line,
                ViewportLoadDirection.Target -> listOf(page)

                ViewportLoadDirection.Next -> cache.pages + page

                ViewportLoadDirection.Previous -> listOf(page) + cache.pages
            }
        while (exceedsCacheBounds(pages)) {
            check(pages.size > 1) { "one viewport page exceeds the editor cache bounds" }
            pages =
                if (direction == ViewportLoadDirection.Previous) {
                    pages.dropLast(1)
                } else {
                    pages.drop(1)
                }
        }
        val blocks = pages.flatMap { cachedPage -> cachedPage.blocks }
        currentRevision = snapshot.metrics.revision
        if (savedRevision == null) {
            savedRevision = currentRevision
        }
        metrics = snapshot.metrics
        staleEditRecovery = null
        previousCursor = pages.firstOrNull()?.previous
        nextCursor = pages.lastOrNull()?.next
        cache =
            EditorViewportCache(
                pages = pages,
                blocks = blocks
            )
    }

    /** Validates one native Find batch against its exact request and document metrics. */
    private fun validateFindBatch(
        request: FindRequest,
        expectedMetrics: DocumentMetrics,
        batch: FindBatch
    ) {
        check(batch.metrics == expectedMetrics) {
            "find batch metrics do not match the current document"
        }
        check(batch.match == null || batch.remainingCandidateRange == null) {
            "find batch contains both a match and remaining candidates"
        }

        val match = batch.match
        if (match != null) {
            validateFindMatch(
                request = request,
                metrics = batch.metrics,
                match = match
            )
            return
        }

        val remainingCandidateRange = batch.remainingCandidateRange
        if (remainingCandidateRange == null) {
            check(
                request.candidateRange.end - request.candidateRange.start <=
                    request.maxCandidateUtf16Units.toLong()
            ) {
                "find batch exhausted more candidates than its work limit"
            }
            return
        }

        check(remainingCandidateRange.start < remainingCandidateRange.end) {
            "find batch remaining candidate range is empty"
        }
        val progressedUtf16Units =
            when (request.direction) {
                FindDirection.Forward -> {
                    check(remainingCandidateRange.end == request.candidateRange.end) {
                        "forward find batch changed the candidate end"
                    }
                    check(remainingCandidateRange.start > request.candidateRange.start) {
                        "forward find batch did not advance"
                    }
                    remainingCandidateRange.start - request.candidateRange.start
                }

                FindDirection.Backward -> {
                    check(remainingCandidateRange.start == request.candidateRange.start) {
                        "backward find batch changed the candidate start"
                    }
                    check(remainingCandidateRange.end < request.candidateRange.end) {
                        "backward find batch did not advance"
                    }
                    request.candidateRange.end - remainingCandidateRange.end
                }
            }
        check(progressedUtf16Units > 0L) {
            "find batch did not make positive progress"
        }
        check(progressedUtf16Units <= request.maxCandidateUtf16Units.toLong()) {
            "find batch exceeded its candidate work limit"
        }
    }

    /** Validates one match and its directional batch position without reading document text. */
    private fun validateFindMatch(
        request: FindRequest,
        metrics: DocumentMetrics,
        match: FindMatch
    ) {
        check(match.belongsTo(metrics)) { "find match is outside the current document" }
        val matchUtf16Length = match.range.end - match.range.start
        val maxExpectedMatchUtf16Length =
            if (request.matchCase) {
                request.query.length.toLong()
            } else {
                Math.multiplyExact(
                    request.query.length.toLong(),
                    CASE_INSENSITIVE_MATCH_UTF16_MULTIPLIER
                )
            }
        check(
            matchUtf16Length > 0L &&
                matchUtf16Length <= maxExpectedMatchUtf16Length &&
                (!request.matchCase || matchUtf16Length == maxExpectedMatchUtf16Length)
        ) {
            "find match length does not match its literal query"
        }
        check(
            match.range.start >= request.candidateRange.start &&
                match.range.start < request.candidateRange.end
        ) {
            "find match start is outside the requested candidates"
        }
        when (request.direction) {
            FindDirection.Forward ->
                check(
                    match.range.start - request.candidateRange.start <
                        request.maxCandidateUtf16Units.toLong()
                ) {
                    "forward find match exceeds the candidate work limit"
                }

            FindDirection.Backward ->
                check(
                    request.candidateRange.end - match.range.start <=
                        request.maxCandidateUtf16Units.toLong()
                ) {
                    "backward find match exceeds the candidate work limit"
                }
        }
    }

    /** Returns whether one match has valid bounds for the supplied document metrics. */
    private fun FindMatch.belongsTo(metrics: DocumentMetrics): Boolean = range.start < range.end &&
        range.end <= metrics.utf16Length &&
        start.revision == metrics.revision &&
        start.line < metrics.lineCount &&
        start.utf16Offset <= range.start &&
        (start.line != 0L || start.utf16Offset == range.start) &&
        (range.start != 0L || (start.line == 0L && start.utf16Offset == 0L))

    /** Returns a scalar-aligned cursor retaining at most one short same-line prefix. */
    private fun matchViewportCursor(
        match: FindMatch,
        contextSnapshot: ViewportSnapshot?
    ): ViewportCursor {
        if (match.start.utf16Offset <= MATCH_PREFIX_CONTEXT_UTF16_UNITS) {
            return match.start.copy(utf16Offset = 0L)
        }
        val contextBlock = checkNotNull(contextSnapshot?.blocks?.lastOrNull()) {
            "match context viewport contains no blocks"
        }
        check(contextBlock.logicalLine == match.start.line) {
            "match context viewport ends on the wrong logical line"
        }
        check(contextBlock.globalUtf16End == match.range.start) {
            "match context viewport does not end at the match"
        }
        val lineGlobalStart = Math.subtractExact(match.range.start, match.start.utf16Offset)
        val contextUtf16Offset =
            Math.subtractExact(contextBlock.globalUtf16Start, lineGlobalStart)
        check(contextUtf16Offset in 0L until match.start.utf16Offset) {
            "match context cursor is outside its logical-line prefix"
        }
        return match.start.copy(utf16Offset = contextUtf16Offset)
    }

    /** Returns the requested or narrowly canonicalized cursor represented by a viewport. */
    private fun matchViewportValidationCursor(
        match: FindMatch,
        requestedCursor: ViewportCursor,
        snapshot: ViewportSnapshot
    ): ViewportCursor {
        if (requestedCursor != match.start) {
            return requestedCursor
        }
        val firstBlock = snapshot.blocks.firstOrNull() ?: return requestedCursor
        val metrics = snapshot.metrics
        if (match.start.line >= metrics.lineCount - 1L) {
            return requestedCursor
        }
        val canonicalCursor =
            ViewportCursor(
                revision = match.start.revision,
                line = Math.incrementExact(match.start.line),
                utf16Offset = 0L
            )
        val canonicalGlobalStart = Math.incrementExact(match.range.start)
        return if (
            snapshot.previous == canonicalCursor &&
            firstBlock.logicalLine == canonicalCursor.line &&
            firstBlock.globalUtf16Start == canonicalGlobalStart
        ) {
            canonicalCursor
        } else {
            requestedCursor
        }
    }

    /** Validates that one contextual viewport begins correctly and contains its match. */
    private fun validateMatchViewport(
        match: FindMatch,
        requestedCursor: ViewportCursor,
        validationCursor: ViewportCursor,
        snapshot: ViewportSnapshot
    ) {
        val firstBlock = checkNotNull(snapshot.blocks.firstOrNull()) {
            "match viewport contains no blocks"
        }
        val canonicalizedTerminator = validationCursor != requestedCursor
        val expectedGlobalStart =
            if (canonicalizedTerminator) {
                Math.incrementExact(match.range.start)
            } else {
                check(requestedCursor.line == match.start.line) {
                    "match viewport cursor is on the wrong logical line"
                }
                val lineGlobalStart =
                    Math.subtractExact(match.range.start, match.start.utf16Offset)
                Math.addExact(lineGlobalStart, requestedCursor.utf16Offset)
            }
        check(firstBlock.globalUtf16Start == expectedGlobalStart) {
            "match viewport does not begin at its contextual offset"
        }
        if (canonicalizedTerminator) {
            return
        }
        val lastBlock = snapshot.blocks.last()
        val visibleGlobalEnd =
            Math.addExact(
                lastBlock.globalUtf16End,
                lastBlock.lineTerminatorUtf16Units.toLong()
            )
        check(
            match.range.start >= firstBlock.globalUtf16Start &&
                match.range.end <= visibleGlobalEnd
        ) {
            "match viewport does not contain the complete match"
        }
    }

    /** Validates that Rust respected the request revision and work limits. */
    private fun validateViewport(
        cursor: ViewportCursor,
        direction: ViewportLoadDirection,
        snapshot: ViewportSnapshot,
        limits: ViewportLimits = viewportLimits,
        validateCacheJoin: Boolean = true
    ) {
        check(snapshot.metrics.revision == cursor.revision) {
            "viewport response revision does not match its request"
        }
        if (direction != ViewportLoadDirection.Replace) {
            check(snapshot.metrics == checkNotNull(metrics)) {
                "viewport metrics changed within one revision"
            }
        }
        check(
            snapshot.previous?.revision == null ||
                snapshot.previous.revision == snapshot.metrics.revision
        ) {
            "previous viewport cursor has an unexpected revision"
        }
        check(
            snapshot.next?.revision == null ||
                snapshot.next.revision == snapshot.metrics.revision
        ) {
            "next viewport cursor has an unexpected revision"
        }
        check(snapshot.blocks.size <= limits.maxBlocks) {
            "viewport response exceeds the block limit"
        }
        check(
            snapshot.blocks.all { block ->
                block.text.length <= limits.maxBlockUtf16Units
            }
        ) {
            "viewport response exceeds the per-block utf-16 limit"
        }
        val totalUtf16Units = snapshot.blocks.sumOf { block -> block.text.length }
        check(totalUtf16Units <= limits.maxTotalUtf16Units) {
            "viewport response exceeds the total utf-16 limit"
        }
        when (direction) {
            ViewportLoadDirection.Replace -> {
                check(snapshot.next == null || cursor.isBefore(snapshot.next)) {
                    "viewport response did not advance its continuation"
                }
            }

            ViewportLoadDirection.Line,
            ViewportLoadDirection.Target -> {
                val firstBlock = checkNotNull(snapshot.blocks.firstOrNull()) {
                    "line viewport contains no blocks"
                }
                check(firstBlock.logicalLine == cursor.line) {
                    "line viewport begins on the wrong logical line"
                }
                val expectedPrevious =
                    if (cursor == originCursor(cursor.revision)) {
                        null
                    } else {
                        cursor
                    }
                check(snapshot.previous == expectedPrevious) {
                    "line viewport does not begin at its request"
                }
                check(snapshot.next == null || cursor.isBefore(snapshot.next)) {
                    "line viewport did not advance its continuation"
                }
            }

            ViewportLoadDirection.Next -> {
                check(snapshot.previous == cursor) {
                    "next viewport response does not join its request"
                }
                check(snapshot.next == null || cursor.isBefore(snapshot.next)) {
                    "next viewport response did not advance"
                }
            }

            ViewportLoadDirection.Previous -> {
                check(snapshot.next == cursor) {
                    "previous viewport response does not join its request"
                }
                check(snapshot.previous == null || snapshot.previous.isBefore(cursor)) {
                    "previous viewport response did not advance"
                }
            }
        }
        if (snapshot.previous != null && snapshot.next != null) {
            check(snapshot.previous.isBefore(snapshot.next)) {
                "viewport cursors are not ordered"
            }
        }
        check(
            (snapshot.previous == null && snapshot.next == null) ||
                snapshot.blocks.isNotEmpty()
        ) {
            "viewport response has cursors without a page"
        }
        validateViewportEdges(snapshot)
        if (validateCacheJoin) {
            validateViewportJoin(direction = direction, snapshot = snapshot)
        }
    }

    /** Validates that page cursors identify the exact returned block edges. */
    private fun validateViewportEdges(snapshot: ViewportSnapshot) {
        val first = snapshot.blocks.firstOrNull()
        if (first == null) {
            check(snapshot.previous == null && snapshot.next == null) {
                "empty viewport contains page cursors"
            }
            return
        }

        val beginsAtOrigin =
            first.logicalLine == 0L &&
                first.globalUtf16Start == 0L &&
                !first.continuesAtStart
        check((snapshot.previous == null) == beginsAtOrigin) {
            "previous viewport cursor conflicts with the first block"
        }
        snapshot.previous?.let { previous ->
            check(previous.line == first.logicalLine) {
                "previous viewport cursor is on the wrong logical line"
            }
            check(first.continuesAtStart == (previous.utf16Offset > 0L)) {
                "previous viewport cursor conflicts with the first block offset"
            }
            check(previous.utf16Offset <= first.globalUtf16Start) {
                "previous viewport cursor exceeds the first block position"
            }
            if (first.logicalLine == 0L) {
                check(previous.utf16Offset == first.globalUtf16Start) {
                    "first-line viewport cursor does not match its global position"
                }
            }
        }

        val last = snapshot.blocks.last()
        val expectedNext =
            if (last.continuesAtEnd) {
                val firstLastLineBlockIndex =
                    snapshot.blocks.indexOfFirst { block ->
                        block.logicalLine == last.logicalLine
                    }
                check(firstLastLineBlockIndex >= 0) {
                    "viewport does not contain its last logical line"
                }
                val firstLastLineBlock = snapshot.blocks[firstLastLineBlockIndex]
                val startingUtf16Offset =
                    if (firstLastLineBlock.continuesAtStart) {
                        check(firstLastLineBlockIndex == 0) {
                            "viewport enters a logical line after returning earlier lines"
                        }
                        checkNotNull(snapshot.previous).utf16Offset
                    } else {
                        0L
                    }
                val returnedLastLineUtf16Units =
                    Math.subtractExact(
                        last.globalUtf16End,
                        firstLastLineBlock.globalUtf16Start
                    )
                ViewportCursor(
                    revision = snapshot.metrics.revision,
                    line = last.logicalLine,
                    utf16Offset =
                        Math.addExact(startingUtf16Offset, returnedLastLineUtf16Units)
                )
            } else if (last.logicalLine < snapshot.metrics.lineCount - 1L) {
                ViewportCursor(
                    revision = snapshot.metrics.revision,
                    line = Math.incrementExact(last.logicalLine),
                    utf16Offset = 0L
                )
            } else {
                check(last.globalUtf16End == snapshot.metrics.utf16Length) {
                    "final viewport block does not reach the document end"
                }
                null
            }
        check(snapshot.next == expectedNext) {
            "next viewport cursor does not match the last block"
        }
    }

    /** Validates one newly loaded page against the retained cache boundary. */
    private fun validateViewportJoin(direction: ViewportLoadDirection, snapshot: ViewportSnapshot) {
        when (direction) {
            ViewportLoadDirection.Replace,
            ViewportLoadDirection.Line,
            ViewportLoadDirection.Target -> return

            ViewportLoadDirection.Next ->
                validateBlockJoin(
                    earlier =
                        checkNotNull(cache.blocks.lastOrNull()) {
                            "next viewport has no retained predecessor"
                        }.block,
                    later =
                        checkNotNull(snapshot.blocks.firstOrNull()) {
                            "next viewport contains no blocks"
                        }
                )

            ViewportLoadDirection.Previous ->
                validateBlockJoin(
                    earlier =
                        checkNotNull(snapshot.blocks.lastOrNull()) {
                            "previous viewport contains no blocks"
                        },
                    later =
                        checkNotNull(cache.blocks.firstOrNull()) {
                            "previous viewport has no retained successor"
                        }.block
                )
        }
    }

    /** Validates that two render blocks meet at one exact document boundary. */
    private fun validateBlockJoin(earlier: RenderBlock, later: RenderBlock) {
        if (later.logicalLine == earlier.logicalLine) {
            check(earlier.continuesAtEnd && later.continuesAtStart) {
                "same-line viewport boundary has invalid continuation flags"
            }
            check(later.globalUtf16Start == earlier.globalUtf16End) {
                "same-line viewport boundary has a discontinuous utf-16 range"
            }
            return
        }

        check(later.logicalLine == Math.incrementExact(earlier.logicalLine)) {
            "viewport boundary logical lines are not contiguous"
        }
        check(!earlier.continuesAtEnd && !later.continuesAtStart) {
            "adjacent-line viewport boundary has invalid continuation flags"
        }
        val expectedGlobalStart =
            Math.addExact(
                earlier.globalUtf16End,
                earlier.lineTerminatorUtf16Units.toLong()
            )
        check(later.globalUtf16Start == expectedGlobalStart) {
            "adjacent-line viewport boundary has a discontinuous utf-16 range"
        }
    }

    /** Indicates whether one revision-bound cursor strictly precedes another. */
    private fun ViewportCursor.isBefore(other: ViewportCursor): Boolean {
        check(revision == other.revision) { "viewport cursor revisions differ" }
        return line < other.line || (line == other.line && utf16Offset < other.utf16Offset)
    }

    /** Validates one edit window before exposing it to Compose. */
    private fun validateEditWindow(
        requestedRevision: Long,
        requestedSelection: Utf16Range,
        limits: EditWindowLimits,
        snapshot: EditWindowSnapshot
    ) {
        check(snapshot.metrics.revision == requestedRevision) {
            "edit window revision does not match its request"
        }
        check(snapshot.selection == requestedSelection) {
            "edit window selection does not match its request"
        }
        check(snapshot.range.start <= snapshot.selection.start) {
            "edit window starts after its selection"
        }
        check(snapshot.selection.end <= snapshot.range.end) {
            "edit window ends before its selection"
        }
        check(snapshot.range.end <= snapshot.metrics.utf16Length) {
            "edit window exceeds the document"
        }
        check(snapshot.text.length.toLong() == snapshot.range.end - snapshot.range.start) {
            "edit window text conflicts with its range"
        }
        check(snapshot.text.length <= limits.maxUtf16Units) {
            "edit window exceeds its utf-16 limit"
        }
        check(
            snapshot.text.isScalarBoundary(
                Math.toIntExact(snapshot.selection.start - snapshot.range.start)
            )
        ) {
            "edit window selection start divides a Unicode character"
        }
        check(
            snapshot.text.isScalarBoundary(
                Math.toIntExact(snapshot.selection.end - snapshot.range.start)
            )
        ) {
            "edit window selection end divides a Unicode character"
        }
        check(snapshot.start.revision == requestedRevision) {
            "edit window start cursor has an unexpected revision"
        }
        check(snapshot.hasPrevious == (snapshot.range.start > 0L)) {
            "edit window previous flag conflicts with its range"
        }
        check(snapshot.hasNext == (snapshot.range.end < snapshot.metrics.utf16Length)) {
            "edit window next flag conflicts with its range"
        }
    }

    /** Returns a bounded request large enough to retain one local selection. */
    private fun editWindowLimitsFor(selection: Utf16Range): EditWindowLimits {
        val selectionUtf16Units = Math.toIntExact(selection.end - selection.start)
        require(selectionUtf16Units <= EDIT_DRAFT_MAX_UTF16_UNITS) {
            "edit selection exceeds the bounded editor limit"
        }
        val contextualUtf16Units =
            minOf(
                EDIT_DRAFT_MAX_UTF16_UNITS,
                Math.addExact(selectionUtf16Units, EDIT_SELECTION_CONTEXT_UTF16_UNITS)
            )
        return EditWindowLimits(
            maxUtf16Units =
                maxOf(editWindowLimits.maxUtf16Units, contextualUtf16Units)
        )
    }

    /** Returns a user-safe reason when one local draft cannot be applied. */
    private fun validateDraft(text: String, selection: Utf16Range): UiText? = when {
        text.length > EDIT_DRAFT_MAX_UTF16_UNITS -> UiText.Resource(
            R.string.operation_edit_too_large
        )

        '\r' in text -> UiText.Resource(R.string.operation_line_feeds_required)

        selection.end > text.length.toLong() -> UiText.Resource(
            R.string.operation_invalid_selection
        )

        !text.hasWellFormedUtf16() -> UiText.Resource(R.string.operation_invalid_unicode)

        !text.isScalarBoundary(selection.start.toInt()) ||
            !text.isScalarBoundary(selection.end.toInt()) ->
            UiText.Resource(R.string.operation_selection_divides_character)

        else -> null
    }

    /** Transitions to stale state and captures the current native revision. */
    private fun handleStaleRevision(failure: Exception): Boolean {
        val staleRevision = failure as? StaleDocumentRevisionException ?: return false
        val retainDirtyDraft = activeEdit != null && hasActiveDraftChanges
        currentRevision = staleRevision.actualRevision
        cache = EditorViewportCache()
        metrics = null
        previousCursor = null
        nextCursor = null
        clearPrefetchedEditWindows()
        if (!retainDirtyDraft) {
            activeEdit = null
            hasActiveDraftChanges = false
        }
        failedViewportRequest = null
        editorMessage =
            if (retainDirtyDraft) {
                UiText.Resource(R.string.operation_stale_draft)
            } else {
                null
            }
        staleEditRecovery =
            if (retainDirtyDraft) {
                StaleEditRecovery.DiscardLocalChanges
            } else {
                null
            }
        status = EditorDocumentStatus.Stale
        return true
    }

    /** Loads one adjacent edit window while leaving the active field untouched. */
    private suspend fun prefetchEditWindow(edit: ActiveEditWindow, towardNext: Boolean) {
        val retainedPrefetch =
            if (towardNext) {
                prefetchedLaterEditWindow
            } else {
                prefetchedEarlierEditWindow
            }
        if (
            retainedPrefetch?.sourceGeneration == edit.generation &&
            retainedPrefetch.snapshot.metrics.revision == currentRevision
        ) {
            return
        }
        val sourceSnapshot = edit.snapshot
        val target =
            if (towardNext) {
                sourceSnapshot.range.end
            } else {
                sourceSnapshot.range.start
            }
        val selection = Utf16Range(start = target, end = target)
        val limits = editWindowLimitsFor(selection)
        val snapshot =
            try {
                withContext(workerDispatcher) {
                    document.editWindow(
                        revision = currentRevision,
                        selection = selection,
                        limits = limits
                    )
                }.also { candidate ->
                    validateEditWindow(
                        requestedRevision = currentRevision,
                        requestedSelection = selection,
                        limits = limits,
                        snapshot = candidate
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                handleStaleRevision(failure)
                return
            }
        if (
            closeStarted.get() ||
            activeEdit?.generation != edit.generation ||
            hasActiveDraftChanges ||
            snapshot.metrics.revision != currentRevision
        ) {
            return
        }
        val revealsNeighbor =
            if (towardNext) {
                snapshot.range.end > sourceSnapshot.range.end
            } else {
                snapshot.range.start < sourceSnapshot.range.start
            }
        if (!revealsNeighbor) {
            return
        }
        val prefetch =
            PrefetchedEditWindow(
                sourceGeneration = edit.generation,
                towardNext = towardNext,
                snapshot = snapshot
            )
        if (towardNext) {
            prefetchedLaterEditWindow = prefetch
        } else {
            prefetchedEarlierEditWindow = prefetch
        }
    }

    /** Consumes a matching adjacent window and rebinds its retained selection. */
    private fun consumePrefetchedEditWindow(
        edit: ActiveEditWindow,
        towardNext: Boolean,
        selection: Utf16Range
    ): EditWindowSnapshot? {
        val prefetched =
            if (towardNext) {
                prefetchedLaterEditWindow
            } else {
                prefetchedEarlierEditWindow
            } ?: return null
        if (
            prefetched.sourceGeneration != edit.generation ||
            prefetched.towardNext != towardNext ||
            prefetched.snapshot.metrics.revision != currentRevision ||
            selection.start < prefetched.snapshot.range.start ||
            selection.end > prefetched.snapshot.range.end
        ) {
            return null
        }
        val localSelectionStart =
            Math.toIntExact(selection.start - prefetched.snapshot.range.start)
        val localSelectionEnd =
            Math.toIntExact(selection.end - prefetched.snapshot.range.start)
        if (
            !prefetched.snapshot.text.isScalarBoundary(localSelectionStart) ||
            !prefetched.snapshot.text.isScalarBoundary(localSelectionEnd)
        ) {
            return null
        }
        return prefetched.snapshot.copy(selection = selection)
    }

    /** Releases all adjacent windows whenever their active revision or generation changes. */
    private fun clearPrefetchedEditWindows() {
        prefetchedEarlierEditWindow = null
        prefetchedLaterEditWindow = null
    }

    /** Discards all revision-bound display data without closing the document. */
    private fun clearCachedView() {
        clearRevisionBoundViewport()
        metrics = null
    }

    /** Discards only viewport data while an edit window remains visible. */
    private fun clearRevisionBoundViewport() {
        cache = EditorViewportCache()
        previousCursor = null
        nextCursor = null
        failedViewportRequest = null
    }

    /** Returns whether an immutable page set exceeds any hard cache bound. */
    private fun exceedsCacheBounds(pages: List<CachedViewportPage>): Boolean {
        if (pages.size > MAX_CACHED_PAGES) {
            return true
        }
        var blockCount = 0
        var utf16Units = 0
        pages.forEach { page ->
            blockCount += page.blocks.size
            utf16Units += page.utf16Units
            if (blockCount > MAX_CACHED_BLOCKS || utf16Units > MAX_CACHED_UTF16_UNITS) {
                return true
            }
        }
        return false
    }

    companion object {
        /** Creates editor state that owns a new empty Rust document. */
        fun createEmpty(): EditorDocumentState = EditorDocumentState(RustDocument.createEmpty())

        /** Creates editor state that owns one bounded, unsaved transient text value. */
        fun createTransientText(text: String): EditorDocumentState {
            require(text.length <= TRANSIENT_DOCUMENT_MAX_UTF16_UNITS) {
                "transient text exceeds the editor limit"
            }
            require(text.hasWellFormedUtf16()) {
                "transient text contains an unpaired surrogate"
            }
            require('\r' !in text) { "transient text contains a carriage return" }
            val document = RustDocument.createEmpty()
            try {
                val metrics = appendTransientText(document = document, text = text)
                return EditorDocumentState(
                    document = document,
                    initialRevision = metrics.revision,
                    initialUnsavedContent = text.isNotEmpty()
                )
            } catch (failure: Throwable) {
                try {
                    document.close()
                } catch (closeFailure: Throwable) {
                    failure.addSuppressed(closeFailure)
                }
                throw failure
            }
        }

        /** Takes exclusive ownership of one already opened document. */
        fun takeOwnership(document: EditorDocument): EditorDocumentState =
            EditorDocumentState(document)
    }
}

/** Appends bounded scalar-safe chunks and returns the resulting transient document metrics. */
internal fun appendTransientText(document: EditorDocument, text: String): DocumentMetrics {
    if (text.isEmpty()) {
        return document.replace(
            expectedRevision = INITIAL_DOCUMENT_REVISION,
            range = Utf16Range(start = 0L, end = 0L),
            replacement = ""
        )
    }
    var offset = 0
    var revision = INITIAL_DOCUMENT_REVISION
    var documentEnd = 0L
    var metrics: DocumentMetrics? = null
    while (offset < text.length) {
        var end = minOf(Math.addExact(offset, EDIT_DRAFT_MAX_UTF16_UNITS), text.length)
        if (end < text.length && text[end - 1].isHighSurrogate()) {
            end -= 1
        }
        check(end > offset) { "transient text chunk must make progress" }
        metrics =
            document.replace(
                expectedRevision = revision,
                range = Utf16Range(start = documentEnd, end = documentEnd),
                replacement = text.substring(offset, end)
            )
        revision = metrics.revision
        documentEnd = metrics.utf16Length
        offset = end
    }
    return checkNotNull(metrics) { "non-empty transient text produced no document metrics" }
}

/** Creates a revision-bound cursor at the start of a document. */
private fun originCursor(revision: Long): ViewportCursor =
    ViewportCursor(revision = revision, line = 0, utf16Offset = 0)
