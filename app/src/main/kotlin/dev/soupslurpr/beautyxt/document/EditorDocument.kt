package dev.soupslurpr.beautyxt.document

internal const val MAX_EDIT_WINDOW_UTF16_UNITS = 32 * 1024
internal const val NO_SOURCE_SAVE_BACKING_BYTE_LENGTH = -1L

/** Describes one prepared immutable source-save package. */
internal data class SourceSavePackageMetrics(
    val packageByteLength: Long,
    val outputByteLength: Long,
    val payloadByteLength: Long,
    val sourceBackingByteLength: Long,
    val recordCount: Long
) {
    init {
        require(packageByteLength > 0L) { "package byte length must be positive" }
        require(outputByteLength >= 0L) { "output byte length must be nonnegative" }
        require(payloadByteLength >= 0L) { "payload byte length must be nonnegative" }
        require(
            sourceBackingByteLength == NO_SOURCE_SAVE_BACKING_BYTE_LENGTH ||
                sourceBackingByteLength >= 0L
        ) {
            "source backing byte length is invalid"
        }
        require(recordCount >= 0L) { "record count must be nonnegative" }
    }

    /** Returns whether this package references one immutable source backing. */
    val hasSourceBacking: Boolean
        get() = sourceBackingByteLength != NO_SOURCE_SAVE_BACKING_BYTE_LENGTH
}

/** Reports that a document operation used a superseded revision. */
internal class StaleDocumentRevisionException(
    val expectedRevision: Long,
    val actualRevision: Long,
    cause: Throwable? = null
) : IllegalStateException(
    "stale document revision: expected $expectedRevision, actual $actualRevision",
    cause
)

/** Defines one immutable document revision owned independently from its source. */
internal interface EditorDocumentSnapshot : AutoCloseable {
    /** Returns the exact metrics of this independently owned revision. */
    fun metrics(): DocumentMetrics = error("snapshot metrics are unsupported")

    /** Creates independent ownership for another consumer of this exact revision. */
    fun duplicate(): EditorDocumentSnapshot = error("snapshot duplication is unsupported")
    /** Returns one bounded viewport without consuming this immutable revision. */
    fun viewport(cursor: ViewportCursor, limits: ViewportLimits): ViewportSnapshot =
        error("snapshot viewport is unsupported")

    /** Prepares this revision for compact piece-backed source saving. */
    fun prepareSourceSavePackage(): SourceSavePackageMetrics =
        error("source-save package preparation is unsupported")

    /** Writes one prepared package and returns an owned source-backing descriptor or -1. */
    fun writeSourceSavePackage(
        packageRawFileDescriptor: Int,
        cancellationRawFileDescriptor: Int,
        timeoutMillis: Long
    ): Int = error("source-save package writing is unsupported")

    /** Streams this revision with its preserved source format and consumes the snapshot. */
    fun writeSnapshot(
        outputRawFileDescriptor: Int,
        cancellationRawFileDescriptor: Int,
        timeoutMillis: Long
    ): Long
}

/** Defines the bounded document operations required by the Compose editor. */
internal interface EditorDocument : AutoCloseable {
    /** Publishes normalized input from bounded chunks, with an atomic native Undo. */
    fun replaceContent(revision: Long, range: Utf16Range, input: DocumentInsertion,
        checkCancelled: () -> Unit = {}): DocumentMetrics = error("chunked insertion is unsupported")

    /** Restores the exact native history token as a new document revision. */
    fun restoreHistory(revision: Long, token: Long, undo: Boolean): DocumentMetrics =
        error("native history is unsupported")

    fun historyState(): DocumentHistoryState = DocumentHistoryState(0, 0, 0, 0, 0)
    fun clearHistory() = Unit

    /** Creates one bounded native matcher with independent cancellation. */
    fun compileSearch(query: String, options: SearchOptions): DocumentSearch =
        error("advanced search is unsupported")

    /** Reads an exact, bounded source range in one revision. */
    fun readRange(revision: Long, range: Utf16Range): String = error("source range reading is unsupported")

    /** Resolves a source offset without changing the editor selection. */
    fun positionAt(revision: Long, offset: Long): ViewportCursor = error("position resolution is unsupported")

    /** Applies sorted verified patches as one revision, with atomic native history. */
    fun replaceBatch(revision: Long, patches: List<DocumentPatch>): DocumentMetrics =
        error("atomic replacement is unsupported")

    /** Returns one decoded viewport for a revision-bound cursor. */
    fun viewport(cursor: ViewportCursor, limits: ViewportLimits): ViewportSnapshot

    /** Returns one decoded viewport immediately preceding a revision-bound cursor. */
    fun previousViewport(cursor: ViewportCursor, limits: ViewportLimits): ViewportSnapshot

    /** Searches one bounded candidate range in an immutable document revision. */
    fun find(request: FindRequest): FindBatch

    /** Returns merged match coverage clipped to one bounded displayed range. */
    fun findHighlights(request: FindHighlightRequest): List<Utf16Range>

    /** Returns one exact logical-line start in global UTF-16 coordinates. */
    fun lineStartUtf16(revision: Long, logicalLine: Long): Long

    /** Returns one bounded editable window containing the requested selection. */
    fun editWindow(
        revision: Long,
        selection: Utf16Range,
        limits: EditWindowLimits
    ): EditWindowSnapshot

    /** Replaces an exact UTF-16 range and returns the resulting metrics. */
    fun replace(expectedRevision: Long, range: Utf16Range, replacement: String): DocumentMetrics

    /** Captures one exact immutable revision for independent streaming. */
    fun captureSnapshot(expectedRevision: Long): EditorDocumentSnapshot

    /** Captures only a logical selection, without a BOM or source newline encoding. */
    fun captureRange(expectedRevision: Long, range: Utf16Range): EditorDocumentSnapshot =
        error("selection snapshots are unsupported")
}
