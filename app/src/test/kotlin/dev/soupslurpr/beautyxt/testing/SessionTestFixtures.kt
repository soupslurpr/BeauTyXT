package dev.soupslurpr.beautyxt.testing

import dev.soupslurpr.beautyxt.document.DocumentLineEnding
import dev.soupslurpr.beautyxt.document.DocumentMetrics
import dev.soupslurpr.beautyxt.document.EditWindowLimits
import dev.soupslurpr.beautyxt.document.EditWindowSnapshot
import dev.soupslurpr.beautyxt.document.EditorDocument
import dev.soupslurpr.beautyxt.document.EditorDocumentSnapshot
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
import dev.soupslurpr.beautyxt.document.isScalarBoundary
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher

private const val INITIAL_TEST_REVISION = 0L
private const val TEST_OUTPUT_RAW_FILE_DESCRIPTOR = 0
private const val TEST_CANCELLATION_RAW_FILE_DESCRIPTOR = 1
private const val TEST_SNAPSHOT_TIMEOUT_MILLIS = 1L

/** Records one replacement applied to a test document. */
internal data class TestReplaceCall(
    val expectedRevision: Long,
    val range: Utf16Range,
    val replacement: String
)

/** Identifies one half-open candidate-start range inspected by a test find call. */
private data class TestFindBatchRange(val start: Int, val end: Int) {
    init {
        require(start >= 0) { "test find batch start must be nonnegative" }
        require(end >= start) { "test find batch end must not precede its start" }
    }
}

/** Records deterministic descriptor-owner cleanup in retained-session tests. */
internal class TestDestinationOwner : AutoCloseable {
    var closeCallCount = 0
        private set

    val isClosed: Boolean
        get() = closeCallCount > 0

    /** Records one explicit ownership release. */
    override fun close() {
        closeCallCount = Math.incrementExact(closeCallCount)
    }
}

/** Implements one deterministic immutable and independently closeable test snapshot. */
internal class TestEditorDocumentSnapshot(val revision: Long, val text: String) :
    EditorDocumentSnapshot {
    private val snapshotLock = Any()
    private val utf8Bytes = text.toByteArray(Charsets.UTF_8)
    private var closed = false

    var writeCallCount = 0
        private set

    var closeCallCount = 0
        private set

    val byteLength: Long
        get() = utf8Bytes.size.toLong()

    val isClosed: Boolean
        get() = synchronized(snapshotLock) { closed }

    /** Returns a defensive copy of this captured revision's serialized bytes. */
    fun copyUtf8Bytes(): ByteArray = utf8Bytes.copyOf()

    /** Consumes this snapshot through deterministic placeholder descriptors. */
    fun consume(): Long = writeSnapshot(
        outputRawFileDescriptor = TEST_OUTPUT_RAW_FILE_DESCRIPTOR,
        cancellationRawFileDescriptor = TEST_CANCELLATION_RAW_FILE_DESCRIPTOR,
        timeoutMillis = TEST_SNAPSHOT_TIMEOUT_MILLIS
    )

    /** Streams this revision once and consumes the snapshot capability. */
    override fun writeSnapshot(
        outputRawFileDescriptor: Int,
        cancellationRawFileDescriptor: Int,
        timeoutMillis: Long
    ): Long {
        require(outputRawFileDescriptor >= 0) { "output file descriptor must be nonnegative" }
        require(cancellationRawFileDescriptor >= 0) {
            "cancellation file descriptor must be nonnegative"
        }
        require(timeoutMillis > 0) { "timeout must be positive" }
        synchronized(snapshotLock) {
            check(!closed) { "snapshot is closed" }
            closed = true
            writeCallCount = Math.incrementExact(writeCallCount)
        }
        return byteLength
    }

    /** Closes this snapshot idempotently while recording every close request. */
    override fun close() {
        synchronized(snapshotLock) {
            closeCallCount = Math.incrementExact(closeCallCount)
            closed = true
        }
    }
}

/** Implements a deterministic in-memory document for retained-session tests. */
internal class TestEditorDocument(
    initialText: String,
    private val editWindowUtf16Units: Int? = null,
    private val replaceFailure: Exception? = null,
    private val transformMetrics: (DocumentMetrics) -> DocumentMetrics = { metrics -> metrics }
) : EditorDocument {
    init {
        require(editWindowUtf16Units == null || editWindowUtf16Units > 0) {
            "test edit-window length must be positive"
        }
    }

    private var revision = INITIAL_TEST_REVISION
    private var isClosed = false
    private val snapshots = mutableListOf<TestEditorDocumentSnapshot>()

    var text = initialText
        private set

    val viewportCalls = mutableListOf<ViewportCursor>()
    val findCalls = mutableListOf<FindRequest>()
    val editWindowCalls = mutableListOf<Utf16Range>()
    val replaceCalls = mutableListOf<TestReplaceCall>()
    val capturedSnapshots: List<TestEditorDocumentSnapshot>
        get() = snapshots
    var closeCallCount = 0
        private set

    /** Returns one complete line-aligned viewport for the current revision. */
    override fun viewport(cursor: ViewportCursor, limits: ViewportLimits): ViewportSnapshot {
        checkOpen()
        requireCurrentRevision(cursor.revision)
        viewportCalls += cursor
        val blocks = createRenderBlocks(text)
        check(blocks.size <= limits.maxBlocks) { "test viewport exceeds the block limit" }
        check(
            blocks.all { block ->
                block.text.length <= limits.maxBlockUtf16Units
            }
        ) {
            "test viewport exceeds the per-block utf-16 limit"
        }
        check(blocks.sumOf { block -> block.text.length } <= limits.maxTotalUtf16Units) {
            "test viewport exceeds the total utf-16 limit"
        }
        return ViewportSnapshot(
            metrics = transformedMetrics(),
            blocks = blocks,
            previous = null,
            next = null
        )
    }

    /** Rejects reverse paging because this fixture always returns the complete document. */
    override fun previousViewport(
        cursor: ViewportCursor,
        limits: ViewportLimits
    ): ViewportSnapshot = error("complete test viewport has no previous page")

    /** Searches one scalar-aligned bounded range with deterministic literal semantics. */
    override fun find(request: FindRequest): FindBatch {
        checkOpen()
        requireCurrentRevision(request.revision)
        check(request.candidateRange.end <= text.length.toLong()) {
            "test find candidate exceeds the document"
        }
        val candidateStart = request.candidateRange.start.toInt()
        val candidateEnd = request.candidateRange.end.toInt()
        check(text.isScalarBoundary(candidateStart)) {
            "test find candidate start divides a Unicode character"
        }
        check(text.isScalarBoundary(candidateEnd)) {
            "test find candidate end divides a Unicode character"
        }
        findCalls += request

        val batchRange = findBatchRange(request, candidateStart, candidateEnd)
        val matchStart = findMatchStart(request, batchRange)
        val metrics = transformedMetrics()
        if (matchStart != null) {
            val lineStart = text.lastIndexOf('\n', startIndex = matchStart - 1) + 1
            val logicalLine = text.substring(0, matchStart).count { character -> character == '\n' }
            return FindBatch(
                metrics = metrics,
                match =
                    FindMatch(
                        range =
                            Utf16Range(
                                start = matchStart.toLong(),
                                end = Math.addExact(matchStart, request.query.length).toLong()
                            ),
                        start =
                            ViewportCursor(
                                revision = request.revision,
                                line = logicalLine.toLong(),
                                utf16Offset = (matchStart - lineStart).toLong()
                            )
                    ),
                remainingCandidateRange = null
            )
        }

        val remainingCandidateRange =
            when (request.direction) {
                FindDirection.Forward ->
                    if (batchRange.end < candidateEnd) {
                        Utf16Range(
                            start = batchRange.end.toLong(),
                            end = candidateEnd.toLong()
                        )
                    } else {
                        null
                    }

                FindDirection.Backward ->
                    if (candidateStart < batchRange.start) {
                        Utf16Range(
                            start = candidateStart.toLong(),
                            end = batchRange.start.toLong()
                        )
                    } else {
                        null
                    }
            }
        return FindBatch(
            metrics = metrics,
            match = null,
            remainingCandidateRange = remainingCandidateRange
        )
    }

    /** Returns one exact logical-line start in global UTF-16 coordinates. */
    override fun lineStartUtf16(revision: Long, logicalLine: Long): Long {
        checkOpen()
        requireCurrentRevision(revision)
        require(logicalLine in 0 until transformedMetrics().lineCount) {
            "test logical line exceeds the document"
        }
        var currentLine = 0L
        var utf16Offset = 0
        while (currentLine < logicalLine) {
            utf16Offset = text.indexOf('\n', startIndex = utf16Offset) + 1
            check(utf16Offset > 0) { "test line metadata does not match its text" }
            currentLine = Math.incrementExact(currentLine)
        }
        return utf16Offset.toLong()
    }

    /** Returns one complete or deterministically centered bounded edit window. */
    override fun editWindow(
        revision: Long,
        selection: Utf16Range,
        limits: EditWindowLimits
    ): EditWindowSnapshot {
        checkOpen()
        requireCurrentRevision(revision)
        val baseWindowUtf16Units = editWindowUtf16Units ?: text.length
        val selectionUtf16Units = Math.toIntExact(selection.end - selection.start)
        val windowUtf16Units =
            if (selectionUtf16Units == 0) {
                baseWindowUtf16Units
            } else {
                minOf(
                    text.length,
                    limits.maxUtf16Units,
                    Math.addExact(baseWindowUtf16Units, selectionUtf16Units)
                )
            }
        check(windowUtf16Units <= limits.maxUtf16Units) {
            "test edit window exceeds its utf-16 limit"
        }
        check(selection.end <= text.length.toLong()) {
            "test selection exceeds the document"
        }
        editWindowCalls += selection
        val surroundingUtf16Units = windowUtf16Units - selectionUtf16Units
        val precedingUtf16Units = surroundingUtf16Units / 2
        val rangeEnd =
            Math.addExact(
                (selection.start.toInt() - precedingUtf16Units).coerceAtLeast(0),
                windowUtf16Units
            ).coerceAtMost(text.length)
        val rangeStart = (rangeEnd - windowUtf16Units).coerceAtLeast(0)
        check(selection.start >= rangeStart && selection.end <= rangeEnd) {
            "test edit window does not contain its selection"
        }
        val precedingTerminator = text.lastIndexOf('\n', startIndex = rangeStart - 1)
        val lineStart = precedingTerminator + 1
        val startLine = text.substring(0, rangeStart).count { character -> character == '\n' }
        return EditWindowSnapshot(
            metrics = transformedMetrics(),
            range = Utf16Range(start = rangeStart.toLong(), end = rangeEnd.toLong()),
            selection = selection,
            start =
                ViewportCursor(
                    revision = revision,
                    line = startLine.toLong(),
                    utf16Offset = (rangeStart - lineStart).toLong()
                ),
            text = text.substring(rangeStart, rangeEnd),
            hasPrevious = rangeStart > 0,
            hasNext = rangeEnd < text.length
        )
    }

    /** Applies one exact replacement and advances the test revision. */
    override fun replace(
        expectedRevision: Long,
        range: Utf16Range,
        replacement: String
    ): DocumentMetrics {
        checkOpen()
        requireCurrentRevision(expectedRevision)
        check(range.end <= text.length.toLong()) {
            "test replacement range exceeds the document"
        }
        replaceFailure?.let { failure -> throw failure }
        replaceCalls +=
            TestReplaceCall(
                expectedRevision = expectedRevision,
                range = range,
                replacement = replacement
            )
        text =
            text.replaceRange(
                startIndex = range.start.toInt(),
                endIndex = range.end.toInt(),
                replacement = replacement
            )
        revision = Math.incrementExact(revision)
        return transformedMetrics()
    }

    /** Captures one immutable revision independently from subsequent document edits. */
    override fun captureSnapshot(expectedRevision: Long): EditorDocumentSnapshot {
        checkOpen()
        requireCurrentRevision(expectedRevision)
        return TestEditorDocumentSnapshot(revision = revision, text = text).also {
            snapshots += it
        }
    }

    /** Advances the test revision without changing its visible text. */
    fun advanceExternally(newRevision: Long) {
        require(newRevision > revision) { "new revision must advance the test document" }
        revision = newRevision
    }

    /** Records every close request so ownership bugs remain observable. */
    override fun close() {
        closeCallCount += 1
        isClosed = true
    }

    /** Rejects document work after the first close request. */
    private fun checkOpen() {
        check(!isClosed) { "test document is closed" }
    }

    /** Rejects revision-bound work after a newer test revision exists. */
    private fun requireCurrentRevision(expectedRevision: Long) {
        if (expectedRevision != revision) {
            throw StaleDocumentRevisionException(
                expectedRevision = expectedRevision,
                actualRevision = revision
            )
        }
    }

    /** Returns current metrics after applying the configured test seam. */
    private fun transformedMetrics(): DocumentMetrics =
        transformMetrics(createMetrics(text = text, revision = revision))

    /** Returns the scalar-aligned candidate-start range inspected by one find call. */
    private fun findBatchRange(
        request: FindRequest,
        candidateStart: Int,
        candidateEnd: Int
    ): TestFindBatchRange {
        if (candidateStart == candidateEnd) {
            return TestFindBatchRange(start = candidateStart, end = candidateEnd)
        }
        return when (request.direction) {
            FindDirection.Forward -> {
                var batchEnd =
                    (candidateStart.toLong() + request.maxCandidateUtf16Units)
                        .coerceAtMost(candidateEnd.toLong())
                        .toInt()
                if (!text.isScalarBoundary(batchEnd)) {
                    batchEnd -= 1
                }
                TestFindBatchRange(start = candidateStart, end = batchEnd)
            }

            FindDirection.Backward -> {
                var batchStart =
                    (candidateEnd - request.maxCandidateUtf16Units).coerceAtLeast(candidateStart)
                if (!text.isScalarBoundary(batchStart)) {
                    batchStart += 1
                }
                TestFindBatchRange(start = batchStart, end = candidateEnd)
            }
        }
    }

    /** Returns the first directional literal match whose start belongs to one batch. */
    private fun findMatchStart(request: FindRequest, batchRange: TestFindBatchRange): Int? {
        if (batchRange.start == batchRange.end) {
            return null
        }
        return when (request.direction) {
            FindDirection.Forward ->
                text.indexOf(
                    string = request.query,
                    startIndex = batchRange.start,
                    ignoreCase = !request.matchCase
                )
                    .takeIf { start -> start >= 0 && start < batchRange.end }

            FindDirection.Backward ->
                text.lastIndexOf(
                    string = request.query,
                    startIndex = batchRange.end - 1,
                    ignoreCase = !request.matchCase
                )
                    .takeIf { start -> start >= batchRange.start }
        }
    }
}

/** Runs every dispatched continuation immediately on the calling thread. */
internal object ImmediateSessionTestDispatcher : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        block.run()
    }
}

/** Queues dispatched continuations until a test explicitly releases them. */
internal class QueuedSessionTestDispatcher : CoroutineDispatcher() {
    private val pendingContinuations = ArrayDeque<Runnable>()

    val pendingCount: Int
        get() = pendingContinuations.size

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        pendingContinuations.addLast(block)
    }

    /** Runs every queued continuation, including work queued during execution. */
    fun runAll() {
        while (runNext()) {
            // Continuations may enqueue further work on this dispatcher.
        }
    }

    /** Runs at most one continuation, allowing tests to stop at a suspension boundary. */
    fun runNext(): Boolean {
        val continuation = pendingContinuations.pollFirst() ?: return false
        continuation.run()
        return true
    }
}

/** Creates document metrics consistent with one normalized test string. */
private fun createMetrics(text: String, revision: Long): DocumentMetrics {
    val byteLength = text.toByteArray(Charsets.UTF_8).size.toLong()
    return DocumentMetrics(
        revision = revision,
        byteLength = byteLength,
        serializedByteLength = byteLength,
        characterLength = text.codePointCount(0, text.length).toLong(),
        utf16Length = text.length.toLong(),
        lineCount = text.count { character -> character == '\n' }.toLong() + 1L,
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

/** Creates complete line-aligned render blocks for one normalized string. */
private fun createRenderBlocks(text: String): List<RenderBlock> {
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
            return blocks
        }
        logicalLine += 1L
        lineStart = lineTerminator + 1
    }
}
