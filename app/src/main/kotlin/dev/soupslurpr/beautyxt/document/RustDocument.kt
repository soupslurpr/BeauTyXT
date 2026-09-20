package dev.soupslurpr.beautyxt.document

private const val CLOSED_DOCUMENT_HANDLE = 0L
private const val CLOSED_SNAPSHOT_HANDLE = 0L
private const val NATIVE_FIND_DIRECTION_FORWARD = 0
private const val NATIVE_FIND_DIRECTION_BACKWARD = 1
private const val SOURCE_SAVE_PACKAGE_METRIC_COUNT = 5
private const val SOURCE_SAVE_PACKAGE_BYTES_INDEX = 0
private const val SOURCE_SAVE_OUTPUT_BYTES_INDEX = 1
private const val SOURCE_SAVE_PAYLOAD_BYTES_INDEX = 2
private const val SOURCE_SAVE_BACKING_BYTES_INDEX = 3
private const val SOURCE_SAVE_RECORD_COUNT_INDEX = 4
private val NATIVE_STALE_REVISION_PATTERN =
    Regex(
        "^Rust error: stale (?:document|viewport) revision: " +
            "expected ([0-9]+), actual ([0-9]+)$"
    )

/** Identifies a revision-bound position within a logical line. */
internal data class ViewportCursor(val revision: Long, val line: Long, val utf16Offset: Long) {
    init {
        require(revision >= 0) { "revision must be nonnegative" }
        require(line >= 0) { "line must be nonnegative" }
        require(utf16Offset >= 0) { "utf-16 offset must be nonnegative" }
    }
}

/** Defines bounded work limits for one viewport request. */
internal data class ViewportLimits(
    val maxBlocks: Int,
    val maxBlockUtf16Units: Int,
    val maxTotalUtf16Units: Int
) {
    init {
        require(maxBlocks > 0) { "maximum blocks must be positive" }
        require(maxBlockUtf16Units > 0) {
            "maximum block utf-16 units must be positive"
        }
        require(maxTotalUtf16Units > 0) {
            "maximum total utf-16 units must be positive"
        }
    }
}

/** Defines the hard text limit for one editable window. */
internal data class EditWindowLimits(val maxUtf16Units: Int) {
    init {
        require(maxUtf16Units > 0) { "maximum utf-16 units must be positive" }
        require(maxUtf16Units <= MAX_EDIT_WINDOW_UTF16_UNITS) {
            "maximum utf-16 units exceed the native limit"
        }
    }
}

/** Identifies a half-open range in global UTF-16 code units. */
internal data class Utf16Range(val start: Long, val end: Long) {
    init {
        require(start >= 0) { "range start must be nonnegative" }
        require(end >= start) { "range end must not precede its start" }
    }
}

/** Owns one Rust document handle and serializes its lifecycle operations. */
internal class RustDocument private constructor(private var nativeHandle: Long) : EditorDocument {
    private val documentLock = Any()

    init {
        require(nativeHandle > CLOSED_DOCUMENT_HANDLE) {
            "native document handle must be positive"
        }
    }

    /** Returns one decoded viewport for a revision-bound cursor. */
    override fun viewport(cursor: ViewportCursor, limits: ViewportLimits): ViewportSnapshot =
        withOpenHandle { handle ->
            val packet =
                NativeDocument.viewport(
                    handle = handle,
                    revision = cursor.revision,
                    startLine = cursor.line,
                    startUtf16Offset = cursor.utf16Offset,
                    maxBlocks = limits.maxBlocks,
                    maxBlockUtf16Units = limits.maxBlockUtf16Units,
                    maxTotalUtf16Units = limits.maxTotalUtf16Units
                )
            ViewportPacketDecoder.decode(packet)
        }

    /** Returns one decoded viewport immediately preceding a revision-bound cursor. */
    override fun previousViewport(
        cursor: ViewportCursor,
        limits: ViewportLimits
    ): ViewportSnapshot = withOpenHandle { handle ->
        val packet =
            NativeDocument.previousViewport(
                handle = handle,
                revision = cursor.revision,
                endLine = cursor.line,
                endUtf16Offset = cursor.utf16Offset,
                maxBlocks = limits.maxBlocks,
                maxBlockUtf16Units = limits.maxBlockUtf16Units,
                maxTotalUtf16Units = limits.maxTotalUtf16Units
            )
        ViewportPacketDecoder.decode(packet)
    }

    /** Searches one bounded candidate range in an immutable document revision. */
    override fun find(request: FindRequest): FindBatch = withOpenHandle { handle ->
        val packet =
            NativeDocument.find(
                handle = handle,
                revision = request.revision,
                query = request.query,
                matchCase = request.matchCase,
                candidateStartUtf16 = request.candidateRange.start,
                candidateEndUtf16 = request.candidateRange.end,
                direction =
                    when (request.direction) {
                        FindDirection.Forward -> NATIVE_FIND_DIRECTION_FORWARD
                        FindDirection.Backward -> NATIVE_FIND_DIRECTION_BACKWARD
                    },
                maxCandidateUtf16Units = request.maxCandidateUtf16Units
            )
        FindPacketDecoder.decode(packet)
    }

    /** Returns one exact logical-line start in global UTF-16 coordinates. */
    override fun lineStartUtf16(revision: Long, logicalLine: Long): Long {
        require(revision >= 0L) { "revision must be nonnegative" }
        require(logicalLine >= 0L) { "logical line must be nonnegative" }
        return withOpenHandle { handle ->
            NativeDocument.lineStartUtf16(
                handle = handle,
                revision = revision,
                logicalLine = logicalLine
            )
        }
    }

    /** Returns one bounded editable window containing the requested selection. */
    override fun editWindow(
        revision: Long,
        selection: Utf16Range,
        limits: EditWindowLimits
    ): EditWindowSnapshot {
        require(revision >= 0) { "revision must be nonnegative" }
        return withOpenHandle { handle ->
            val packet =
                NativeDocument.editWindow(
                    handle = handle,
                    revision = revision,
                    selectionStartUtf16 = selection.start,
                    selectionEndUtf16 = selection.end,
                    maxUtf16Units = limits.maxUtf16Units
                )
            EditWindowPacketDecoder.decode(packet)
        }
    }

    /** Replaces an exact UTF-16 range and returns the resulting metrics. */
    override fun replace(
        expectedRevision: Long,
        range: Utf16Range,
        replacement: String
    ): DocumentMetrics {
        require(expectedRevision >= 0) { "expected revision must be nonnegative" }
        require(replacement.hasWellFormedUtf16()) {
            "replacement contains an unpaired surrogate"
        }
        require('\r' !in replacement) { "replacement contains a carriage return" }
        return withOpenHandle { handle ->
            val packet =
                NativeDocument.replace(
                    handle = handle,
                    expectedRevision = expectedRevision,
                    startUtf16 = range.start,
                    endUtf16 = range.end,
                    replacement = replacement
                )
            DocumentMetricsPacketDecoder.decode(packet)
        }
    }

    /** Captures one exact immutable revision for independent streaming. */
    override fun captureSnapshot(expectedRevision: Long): EditorDocumentSnapshot {
        require(expectedRevision >= 0) { "expected revision must be nonnegative" }
        return withOpenHandle { handle ->
            RustDocumentSnapshot(
                NativeDocument.captureSnapshot(
                    handle = handle,
                    expectedRevision = expectedRevision
                )
            )
        }
    }

    /** Closes the native document exactly once. */
    override fun close() {
        synchronized(documentLock) {
            val handle = nativeHandle
            if (handle == CLOSED_DOCUMENT_HANDLE) {
                return
            }
            NativeDocument.close(handle)
            nativeHandle = CLOSED_DOCUMENT_HANDLE
        }
    }

    /** Runs an operation while close cannot invalidate its native handle. */
    private inline fun <Result> withOpenHandle(action: (Long) -> Result): Result =
        synchronized(documentLock) {
            check(nativeHandle != CLOSED_DOCUMENT_HANDLE) { "document is closed" }
            try {
                action(nativeHandle)
            } catch (failure: RuntimeException) {
                throw translateNativeFailure(failure)
            }
        }

    companion object {
        /** Creates an empty Rust document. */
        fun createEmpty(): RustDocument = RustDocument(NativeDocument.createEmpty())

        /** Opens UTF-8 by retaining a native descriptor while source pieces remain. */
        fun openSource(rawFileDescriptor: Int, expectedBytes: Long): RustDocument {
            require(rawFileDescriptor >= 0) { "file descriptor must be nonnegative" }
            require(expectedBytes >= 0L) { "expected byte count must be nonnegative" }
            return RustDocument(
                NativeDocument.openSource(
                    rawFileDescriptor = rawFileDescriptor,
                    expectedBytes = expectedBytes
                )
            )
        }
    }
}

/** Owns one native immutable revision until it is streamed or closed. */
private class RustDocumentSnapshot(private var nativeHandle: Long) : EditorDocumentSnapshot {
    private val snapshotLock = Any()
    private var preparedPackageMetrics: SourceSavePackageMetrics? = null

    init {
        require(nativeHandle > CLOSED_SNAPSHOT_HANDLE) {
            "native snapshot handle must be positive"
        }
    }

    /** Returns one decoded viewport without consuming this immutable revision. */
    override fun viewport(cursor: ViewportCursor, limits: ViewportLimits): ViewportSnapshot =
        synchronized(snapshotLock) {
            check(nativeHandle != CLOSED_SNAPSHOT_HANDLE) { "snapshot is closed" }
            check(preparedPackageMetrics == null) {
                "prepared source-save snapshot cannot provide a viewport"
            }
            try {
                val packet =
                    NativeDocument.snapshotViewport(
                        snapshotHandle = nativeHandle,
                        revision = cursor.revision,
                        startLine = cursor.line,
                        startUtf16Offset = cursor.utf16Offset,
                        maxBlocks = limits.maxBlocks,
                        maxBlockUtf16Units = limits.maxBlockUtf16Units,
                        maxTotalUtf16Units = limits.maxTotalUtf16Units
                    )
                ViewportPacketDecoder.decode(packet)
            } catch (failure: RuntimeException) {
                throw translateNativeFailure(failure)
            }
        }

    /** Prepares this revision for compact piece-backed source saving. */
    override fun prepareSourceSavePackage(): SourceSavePackageMetrics = synchronized(snapshotLock) {
        check(nativeHandle != CLOSED_SNAPSHOT_HANDLE) { "snapshot is closed" }
        check(preparedPackageMetrics == null) {
            "source-save package is already prepared"
        }
        decodeSourceSavePackageMetrics(
            NativeDocument.prepareSourceSavePackage(nativeHandle)
        ).also { preparedPackageMetrics = it }
    }

    /** Writes one prepared package and returns an owned source-backing descriptor or -1. */
    override fun writeSourceSavePackage(
        packageRawFileDescriptor: Int,
        cancellationRawFileDescriptor: Int,
        timeoutMillis: Long
    ): Int {
        require(packageRawFileDescriptor >= 0) {
            "package file descriptor must be nonnegative"
        }
        require(cancellationRawFileDescriptor >= 0) {
            "cancellation file descriptor must be nonnegative"
        }
        require(timeoutMillis > 0) { "timeout must be positive" }
        val handle =
            synchronized(snapshotLock) {
                check(nativeHandle != CLOSED_SNAPSHOT_HANDLE) { "snapshot is closed" }
                checkNotNull(preparedPackageMetrics) {
                    "source-save package is not prepared"
                }
                nativeHandle.also { nativeHandle = CLOSED_SNAPSHOT_HANDLE }
            }
        val sourceBackingRawFileDescriptor =
            NativeDocument.writeSourceSavePackage(
                snapshotHandle = handle,
                packageRawFileDescriptor = packageRawFileDescriptor,
                cancellationRawFileDescriptor = cancellationRawFileDescriptor,
                timeoutMillis = timeoutMillis
            )
        check(sourceBackingRawFileDescriptor >= -1) {
            "source-save backing descriptor result is invalid"
        }
        return sourceBackingRawFileDescriptor
    }

    /** Streams this revision with its preserved source format and consumes its handle. */
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
        val handle =
            synchronized(snapshotLock) {
                check(nativeHandle != CLOSED_SNAPSHOT_HANDLE) { "snapshot is closed" }
                check(preparedPackageMetrics == null) {
                    "prepared source-save package cannot be streamed directly"
                }
                nativeHandle.also { nativeHandle = CLOSED_SNAPSHOT_HANDLE }
            }
        return NativeDocument.writeSnapshot(
            snapshotHandle = handle,
            outputRawFileDescriptor = outputRawFileDescriptor,
            cancellationRawFileDescriptor = cancellationRawFileDescriptor,
            timeoutMillis = timeoutMillis
        )
    }

    /** Closes one unused native snapshot exactly once. */
    override fun close() {
        synchronized(snapshotLock) {
            val handle = nativeHandle
            if (handle == CLOSED_SNAPSHOT_HANDLE) {
                return
            }
            NativeDocument.closeSnapshot(handle)
            nativeHandle = CLOSED_SNAPSHOT_HANDLE
        }
    }
}

/** Decodes one fixed native source-save package metrics packet. */
private fun decodeSourceSavePackageMetrics(packet: LongArray): SourceSavePackageMetrics {
    require(packet.size == SOURCE_SAVE_PACKAGE_METRIC_COUNT) {
        "source-save package metrics length is invalid"
    }
    return SourceSavePackageMetrics(
        packageByteLength = packet[SOURCE_SAVE_PACKAGE_BYTES_INDEX],
        outputByteLength = packet[SOURCE_SAVE_OUTPUT_BYTES_INDEX],
        payloadByteLength = packet[SOURCE_SAVE_PAYLOAD_BYTES_INDEX],
        sourceBackingByteLength = packet[SOURCE_SAVE_BACKING_BYTES_INDEX],
        recordCount = packet[SOURCE_SAVE_RECORD_COUNT_INDEX]
    )
}

/** Translates stable native revision failures without exposing string parsing to UI state. */
private fun translateNativeFailure(failure: RuntimeException): RuntimeException {
    val message = failure.message ?: return failure
    val match = NATIVE_STALE_REVISION_PATTERN.matchEntire(message) ?: return failure
    val expectedRevision = match.groupValues[1].toLongOrNull() ?: return failure
    val actualRevision = match.groupValues[2].toLongOrNull() ?: return failure
    return StaleDocumentRevisionException(
        expectedRevision = expectedRevision,
        actualRevision = actualRevision,
        cause = failure
    )
}
