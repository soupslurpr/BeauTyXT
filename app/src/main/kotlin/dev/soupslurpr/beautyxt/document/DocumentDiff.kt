package dev.soupslurpr.beautyxt.document

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal const val DIFF_LINE_ENDING_LF_BIT = 1 shl 0
internal const val DIFF_LINE_ENDING_CRLF_BIT = 1 shl 1
internal const val DIFF_LINE_ENDING_CR_BIT = 1 shl 2

private const val MAX_DECODED_DIFF_HUNKS = 512
private const val MAX_DECODED_DIFF_LINES = 512
private const val MAX_DECODED_DIFF_LINE_BYTES = 2048
private const val DIFF_PACKET_HEADER_BYTES = 72
private const val DIFF_HUNK_HEADER_BYTES = 20L
private const val DIFF_LINE_HEADER_BYTES = 5L

/** Classifies one line inside a conflict-comparison hunk. */
internal enum class DiffLineKind {
    Context,
    Removed,
    Added
}

/** Holds one bounded display line inside a conflict-comparison hunk. */
internal data class DiffLine(
    val kind: DiffLineKind,
    val text: String
)

/** Groups nearby changes with surrounding context and 1-based line origins. */
internal data class DiffHunk(
    val externalStartLine: Long,
    val localStartLine: Long,
    val lines: List<DiffLine>
)

/** Summarizes text and format differences between external and local revisions. */
internal data class DocumentDiff(
    val externalHasBom: Boolean,
    val localHasBom: Boolean,
    val externalLineEndings: Int,
    val localLineEndings: Int,
    val externalBytes: Long,
    val localBytes: Long,
    val externalLines: Long,
    val localLines: Long,
    val comparedLines: Long,
    val addedLines: Long,
    val removedLines: Long,
    val textIdentical: Boolean,
    val formatDiffers: Boolean,
    val isPartial: Boolean,
    val hunks: List<DiffHunk>
) {
    val hasFormatDifference: Boolean
        get() = formatDiffers || externalHasBom != localHasBom || externalLineEndings != localLineEndings
}

/** Decodes the native bounded line-diff packet emitted by Rust `editor-jni`. */
internal object DocumentDiffPacketDecoder {
    private val MAGIC = byteArrayOf('B'.code.toByte(), 'E'.code.toByte(), 'D'.code.toByte(), 'F'.code.toByte())

    fun decode(packet: ByteArray): DocumentDiff {
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.remaining() >= DIFF_PACKET_HEADER_BYTES) { "diff packet header is truncated" }
        val magic = ByteArray(4).also(buffer::get)
        require(magic.contentEquals(MAGIC)) { "unexpected diff packet magic" }
        val version = buffer.int
        require(version == 1) { "unsupported diff packet version: $version" }
        val flags = buffer.get().toInt() and 0xFF
        val externalLineEndings = buffer.get().toInt() and 0xFF
        val localLineEndings = buffer.get().toInt() and 0xFF
        buffer.get() // reserved
        val externalBytes = buffer.long
        val localBytes = buffer.long
        val externalLines = buffer.long
        val localLines = buffer.long
        val comparedLines = buffer.long
        val addedLines = buffer.long
        val removedLines = buffer.long
        val hunkCount = buffer.int
        require(
            hunkCount in 0..MAX_DECODED_DIFF_HUNKS &&
                hunkCount.toLong() * DIFF_HUNK_HEADER_BYTES <= buffer.remaining().toLong()
        ) {
            "diff hunk count exceeds protocol bounds or remaining bytes"
        }
        val hunks = ArrayList<DiffHunk>(hunkCount)
        var totalDecodedLines = 0
        repeat(hunkCount) {
            require(buffer.remaining() >= DIFF_HUNK_HEADER_BYTES) { "diff hunk header is truncated" }
            val extStart = buffer.long
            val locStart = buffer.long
            val lineCount = buffer.int
            require(
                lineCount in 0..(MAX_DECODED_DIFF_LINES - totalDecodedLines) &&
                    lineCount.toLong() * DIFF_LINE_HEADER_BYTES <= buffer.remaining().toLong()
            ) {
                "diff line count exceeds protocol bounds or remaining bytes"
            }
            totalDecodedLines += lineCount
            val lines = ArrayList<DiffLine>(lineCount)
            repeat(lineCount) {
                require(buffer.remaining() >= DIFF_LINE_HEADER_BYTES) { "diff line header is truncated" }
                val kind = when (buffer.get().toInt() and 0xFF) {
                    0 -> DiffLineKind.Context
                    1 -> DiffLineKind.Removed
                    2 -> DiffLineKind.Added
                    else -> throw IllegalArgumentException("unknown diff line kind")
                }
                val textLen = buffer.int
                require(
                    textLen in 0..MAX_DECODED_DIFF_LINE_BYTES &&
                        buffer.remaining() >= textLen
                ) {
                    "diff line text length is invalid or truncated"
                }
                val textBytes = ByteArray(textLen).also(buffer::get)
                lines.add(DiffLine(kind, textBytes.decodeToString()))
            }
            hunks.add(DiffHunk(extStart, locStart, lines))
        }
        require(!buffer.hasRemaining()) { "diff packet contains trailing bytes" }
        return DocumentDiff(
            externalHasBom = (flags and (1 shl 0)) != 0,
            localHasBom = (flags and (1 shl 1)) != 0,
            externalLineEndings = externalLineEndings,
            localLineEndings = localLineEndings,
            externalBytes = externalBytes,
            localBytes = localBytes,
            externalLines = externalLines,
            localLines = localLines,
            comparedLines = comparedLines,
            addedLines = addedLines,
            removedLines = removedLines,
            textIdentical = (flags and (1 shl 2)) != 0,
            formatDiffers = (flags and (1 shl 4)) != 0,
            isPartial = (flags and (1 shl 3)) != 0,
            hunks = hunks
        )
    }
}

private const val FALLBACK_MAX_UTF16_UNITS = 256 * 1024
private const val FALLBACK_MAX_LINES = 2048
private const val FALLBACK_MAX_OUTPUT_LINES = 512

/** Bounded Kotlin fallback used only for non-native JVM test snapshots. */
internal fun diffDocumentSnapshotsFallback(
    external: EditorDocumentSnapshot,
    local: EditorDocumentSnapshot
): DocumentDiff {
    val extMetrics = external.metrics()
    val locMetrics = local.metrics()
    val (extText, extComplete) = readSnapshotPrefix(external, extMetrics.revision)
    val (locText, locComplete) = readSnapshotPrefix(local, locMetrics.revision)
    var partial = !extComplete || !locComplete
    val extAll = if (extText.isEmpty()) emptyList() else extText.split('\n')
    val locAll = if (locText.isEmpty()) emptyList() else locText.split('\n')
    if (extAll.size > FALLBACK_MAX_LINES || locAll.size > FALLBACK_MAX_LINES) partial = true
    val extLines = extAll.take(FALLBACK_MAX_LINES)
    val locLines = locAll.take(FALLBACK_MAX_LINES)
    val comparedLines = maxOf(extLines.size, locLines.size).toLong()
    var prefix = 0
    val minLen = minOf(extLines.size, locLines.size)
    while (prefix < minLen && extLines[prefix] == locLines[prefix]) prefix++
    var suffix = 0
    while (
        suffix < minLen - prefix &&
        extLines[extLines.size - 1 - suffix] == locLines[locLines.size - 1 - suffix]
    ) suffix++
    val removedCount = (extLines.size - prefix - suffix).toLong()
    val addedCount = (locLines.size - prefix - suffix).toLong()
    val hunks = if (removedCount == 0L && addedCount == 0L) {
        emptyList()
    } else {
        val ctxStart = maxOf(0, prefix - 3)
        val lines = ArrayList<DiffLine>()
        val maxRemoves = if (addedCount > 0L) FALLBACK_MAX_OUTPUT_LINES / 2 else FALLBACK_MAX_OUTPUT_LINES
        var emittedRemoves = 0
        fun push(kind: DiffLineKind, text: String) {
            if (lines.size < FALLBACK_MAX_OUTPUT_LINES) lines.add(DiffLine(kind, text))
            else partial = true
        }
        for (i in ctxStart until prefix) push(DiffLineKind.Context, extLines[i])
        for (i in prefix until extLines.size - suffix) {
            if (emittedRemoves < maxRemoves) {
                emittedRemoves++
                push(DiffLineKind.Removed, extLines[i])
            } else {
                partial = true
            }
        }
        for (i in prefix until locLines.size - suffix) push(DiffLineKind.Added, locLines[i])
        val ctxEnd = minOf(extLines.size, extLines.size - suffix + 3)
        for (i in extLines.size - suffix until ctxEnd) push(DiffLineKind.Context, extLines[i])
        listOf(DiffHunk((ctxStart + 1).toLong(), (ctxStart + 1).toLong(), lines))
    }
    val extMask = metricsLineEndingMask(extMetrics)
    val locMask = metricsLineEndingMask(locMetrics)
    val textIdentical = !partial && addedCount == 0L && removedCount == 0L &&
        extMetrics.utf16Length == locMetrics.utf16Length
    val formatDiffers = extMetrics.hasUtf8Bom != locMetrics.hasUtf8Bom ||
        extMask != locMask ||
        (textIdentical && extMetrics.serializedByteLength != locMetrics.serializedByteLength)
    return DocumentDiff(
        externalHasBom = extMetrics.hasUtf8Bom,
        localHasBom = locMetrics.hasUtf8Bom,
        externalLineEndings = extMask,
        localLineEndings = locMask,
        externalBytes = extMetrics.serializedByteLength,
        localBytes = locMetrics.serializedByteLength,
        externalLines = extMetrics.lineCount,
        localLines = locMetrics.lineCount,
        comparedLines = comparedLines,
        addedLines = addedCount,
        removedLines = removedCount,
        textIdentical = textIdentical,
        formatDiffers = formatDiffers,
        isPartial = partial,
        hunks = hunks
    )
}

private fun metricsLineEndingMask(metrics: DocumentMetrics): Int =
    (if (metrics.hasLfLineEndings) DIFF_LINE_ENDING_LF_BIT else 0) or
        (if (metrics.hasCrlfLineEndings) DIFF_LINE_ENDING_CRLF_BIT else 0) or
        (if (metrics.hasCrLineEndings) DIFF_LINE_ENDING_CR_BIT else 0)

private fun readSnapshotPrefix(snapshot: EditorDocumentSnapshot, revision: Long): Pair<String, Boolean> {
    val builder = StringBuilder()
    var cursor: ViewportCursor? = ViewportCursor(revision, 0L, 0L)
    while (cursor != null && builder.length < FALLBACK_MAX_UTF16_UNITS) {
        val remaining = FALLBACK_MAX_UTF16_UNITS - builder.length
        val page = snapshot.viewport(
            cursor = cursor,
            limits = ViewportLimits(
                maxBlocks = 64,
                maxBlockUtf16Units = minOf(32 * 1024, remaining),
                maxTotalUtf16Units = remaining
            )
        )
        for (block in page.blocks) {
            builder.append(block.text)
            if (block.lineTerminatorUtf16Units > 0) builder.append('\n')
        }
        cursor = page.next
    }
    return builder.toString() to (cursor == null)
}
