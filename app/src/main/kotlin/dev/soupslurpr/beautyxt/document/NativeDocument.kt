package dev.soupslurpr.beautyxt.document

private const val EDITOR_JNI_LIBRARY = "beautyxt_editor_jni"

/** Exposes the native document operations implemented by the Rust bridge. */
internal object NativeDocument {
    init {
        System.loadLibrary(EDITOR_JNI_LIBRARY)
    }

    /** Creates an empty document and returns its native handle. */
    @JvmStatic
    external fun createEmpty(): Long

    /** Opens format-preserving UTF-8 from a descriptor and returns its native handle. */
    @JvmStatic
    external fun openSource(rawFileDescriptor: Int, expectedBytes: Long): Long

    /** Returns one bounded viewport packet for the requested revision. */
    @JvmStatic
    external fun viewport(
        handle: Long,
        revision: Long,
        startLine: Long,
        startUtf16Offset: Long,
        maxBlocks: Int,
        maxBlockUtf16Units: Int,
        maxTotalUtf16Units: Int
    ): ByteArray

    /** Returns one bounded viewport from an immutable snapshot handle. */
    @JvmStatic
    external fun snapshotViewport(
        snapshotHandle: Long,
        revision: Long,
        startLine: Long,
        startUtf16Offset: Long,
        maxBlocks: Int,
        maxBlockUtf16Units: Int,
        maxTotalUtf16Units: Int
    ): ByteArray

    /** Returns one bounded viewport packet immediately preceding the requested position. */
    @JvmStatic
    external fun previousViewport(
        handle: Long,
        revision: Long,
        endLine: Long,
        endUtf16Offset: Long,
        maxBlocks: Int,
        maxBlockUtf16Units: Int,
        maxTotalUtf16Units: Int
    ): ByteArray

    /** Returns one fixed-size bounded literal find packet. */
    @JvmStatic
    external fun find(
        handle: Long,
        revision: Long,
        query: String,
        matchCase: Boolean,
        candidateStartUtf16: Long,
        candidateEndUtf16: Long,
        direction: Int,
        maxCandidateUtf16Units: Int
    ): ByteArray

    /** Returns merged global UTF-16 highlight pairs for one bounded displayed range. */
    @JvmStatic
    external fun findHighlights(
        handle: Long,
        revision: Long,
        query: String,
        matchCase: Boolean,
        rangeStartUtf16: Long,
        rangeEndUtf16: Long
    ): LongArray

    /** Returns one exact logical-line start in global UTF-16 coordinates. */
    @JvmStatic
    external fun lineStartUtf16(handle: Long, revision: Long, logicalLine: Long): Long

    /** Returns one bounded edit-window packet containing the selection. */
    @JvmStatic
    external fun editWindow(
        handle: Long,
        revision: Long,
        selectionStartUtf16: Long,
        selectionEndUtf16: Long,
        maxUtf16Units: Int
    ): ByteArray

    /** Replaces a UTF-16 range and returns the resulting metrics packet. */
    @JvmStatic
    external fun replace(
        handle: Long,
        expectedRevision: Long,
        startUtf16: Long,
        endUtf16: Long,
        replacement: String
    ): ByteArray

    /** Captures an exact immutable document revision and returns its native handle. */
    @JvmStatic
    external fun captureSnapshot(handle: Long, expectedRevision: Long): Long

    /** Prepares one snapshot package and returns its fixed metrics packet. */
    @JvmStatic
    external fun prepareSourceSavePackage(snapshotHandle: Long): LongArray

    /** Consumes one prepared package and returns an owned source descriptor or -1. */
    @JvmStatic
    external fun writeSourceSavePackage(
        snapshotHandle: Long,
        packageRawFileDescriptor: Int,
        cancellationRawFileDescriptor: Int,
        timeoutMillis: Long
    ): Int

    /** Consumes one snapshot into an interruptible descriptor and returns its byte count. */
    @JvmStatic
    external fun writeSnapshot(
        snapshotHandle: Long,
        outputRawFileDescriptor: Int,
        cancellationRawFileDescriptor: Int,
        timeoutMillis: Long
    ): Long

    /** Closes a native snapshot handle without streaming it. */
    @JvmStatic
    external fun closeSnapshot(snapshotHandle: Long)

    /** Closes a native document handle. */
    @JvmStatic
    external fun close(handle: Long)
}
