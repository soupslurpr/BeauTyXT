package dev.soupslurpr.beautyxt.document

/** One immutable input slice; normalization never creates another full-size string. */
internal class DocumentInsertion(
    private val text: String,
    val start: Int = 0,
    val end: Int = text.length,
    private val followingLineFeed: Boolean = false
) {
    init {
        require(start in 0..end && end <= text.length)
        require(text.isScalarBoundary(start) && text.isScalarBoundary(end))
    }

    val rawLength: Int get() = end - start

    /** Maps a raw input position through CRLF normalization without copying text. */
    fun normalizedOffset(offset: Int, checkCancelled: () -> Unit = {}): Long {
        require(offset in 0..rawLength)
        var units = 0L
        for (index in start until start + offset) {
            if ((index - start) % CHUNK_UNITS == 0) checkCancelled()
            if (!isDiscardedCarriageReturn(index)) units++
        }
        return units
    }

    /** Validates Unicode and emits at most 8 Ki UTF-16 units per native transfer. */
    fun forEachChunk(checkCancelled: () -> Unit = {}, consume: (String) -> Unit) {
        var index = start
        val chunk = StringBuilder(CHUNK_UNITS)
        while (index < end) {
            checkCancelled()
            chunk.setLength(0)
            while (index < end && chunk.length < CHUNK_UNITS - 1) {
                val char = text[index]
                when {
                    isDiscardedCarriageReturn(index) -> index++
                    char == '\r' -> { chunk.append('\n'); index++ }
                    char.isHighSurrogate() -> {
                        require(index + 1 < end && text[index + 1].isLowSurrogate()) { "insertion contains invalid Unicode" }
                        chunk.append(char).append(text[index + 1]); index += 2
                    }
                    else -> {
                        require(!char.isLowSurrogate()) { "insertion contains invalid Unicode" }
                        chunk.append(char); index++
                    }
                }
            }
            if (chunk.isNotEmpty()) consume(chunk.toString())
        }
    }

    private fun isDiscardedCarriageReturn(index: Int): Boolean = text[index] == '\r' &&
        (if (index + 1 < end) text[index + 1] == '\n' else followingLineFeed)

    private companion object { const val CHUNK_UNITS = 8 * 1024 }
}

/** Identifies a refusal before publication when the native Undo budget cannot fit. */
internal class DocumentHistoryLimitException(cause: Throwable? = null) : IllegalStateException(
    "edit exceeds native history memory limit", cause
)

internal data class DocumentHistoryState(val undo: Long, val redo: Long, val oldestUndo: Long,
    val retainedBytes: Long, val entries: Long)

/** Describes coordinate changes without retaining any document text. */
internal interface DocumentChange {
    val range: Utf16Range
    val insertedLength: Long
}

internal data class DocumentEditShape(override val range: Utf16Range,
    override val insertedLength: Long) : DocumentChange {
    init { require(insertedLength >= 0) }
}

internal fun inverseChanges(changes: List<DocumentChange>): List<DocumentEditShape> {
    var shift = 0L
    return changes.map { change ->
        val start = Math.addExact(change.range.start, shift)
        val removedLength = change.range.end - change.range.start
        shift = Math.addExact(shift, change.insertedLength - removedLength)
        DocumentEditShape(Utf16Range(start, Math.addExact(start, change.insertedLength)), removedLength)
    }
}
