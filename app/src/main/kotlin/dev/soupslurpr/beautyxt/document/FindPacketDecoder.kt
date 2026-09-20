package dev.soupslurpr.beautyxt.document

internal const val MAX_FIND_QUERY_UTF16_UNITS = 4 * 1024
internal const val MAX_FIND_QUERY_BYTES = 16 * 1024
private const val MAX_FIND_MATCH_UTF16_UNITS = MAX_FIND_QUERY_UTF16_UNITS * 2
internal const val MIN_FIND_CANDIDATE_UTF16_UNITS = 2
internal const val MAX_FIND_CANDIDATE_UTF16_UNITS = 256 * 1024

private const val FIND_PACKET_MAGIC = 0x4e46_4542L
private const val FIND_PACKET_VERSION = 2
private const val FIND_PACKET_BYTES = 120
private const val FIND_HAS_MATCH = 1
private const val FIND_HAS_REMAINING_CANDIDATE = 1 shl 1
private const val FIND_ALLOWED_FLAGS = FIND_HAS_MATCH or FIND_HAS_REMAINING_CANDIDATE

/** Selects the traversal direction for one bounded literal find request. */
internal enum class FindDirection {
    Forward,
    Backward
}

/** Requests one bounded literal search over an exact revision and candidate range. */
internal data class FindRequest(
    val revision: Long,
    val query: String,
    val matchCase: Boolean = false,
    val candidateRange: Utf16Range,
    val direction: FindDirection,
    val maxCandidateUtf16Units: Int
) {
    init {
        require(revision >= 0L) { "find revision must be nonnegative" }
        require(query.isNotEmpty()) { "find query must not be empty" }
        require(query.length <= MAX_FIND_QUERY_UTF16_UNITS) {
            "find query exceeds the utf-16 limit"
        }
        val queryUtf8Bytes = requireNotNull(query.utf8LengthOrNull()) {
            "find query contains an unpaired surrogate"
        }
        require('\r' !in query) { "find query contains a carriage return" }
        require(queryUtf8Bytes <= MAX_FIND_QUERY_BYTES.toLong()) {
            "find query exceeds the byte limit"
        }
        require(maxCandidateUtf16Units >= MIN_FIND_CANDIDATE_UTF16_UNITS) {
            "maximum candidate utf-16 units must be at least two"
        }
        require(maxCandidateUtf16Units <= MAX_FIND_CANDIDATE_UTF16_UNITS) {
            "maximum candidate utf-16 units exceed the native limit"
        }
    }
}

/** Identifies one literal match and its revision-bound line-relative start. */
internal data class FindMatch(val range: Utf16Range, val start: ViewportCursor) {
    init {
        require(range.start != range.end) { "find match must not be empty" }
    }
}

/** Contains one bounded find result or the exact candidate range still unsearched. */
internal data class FindBatch(
    val metrics: DocumentMetrics,
    val match: FindMatch?,
    val remainingCandidateRange: Utf16Range?
) {
    init {
        require(match == null || remainingCandidateRange == null) {
            "find match and remaining candidate are mutually exclusive"
        }
        match?.let { value ->
            require(value.start.revision == metrics.revision) {
                "find match revision differs from its metrics"
            }
            require(value.range.end <= metrics.utf16Length) {
                "find match exceeds the document"
            }
            require(value.start.line < metrics.lineCount) {
                "find match line exceeds the document"
            }
            require(value.start.utf16Offset <= value.range.start) {
                "find match line-relative offset exceeds its global start"
            }
        }
        remainingCandidateRange?.let { range ->
            require(range.start != range.end) { "remaining find candidate must not be empty" }
            require(range.end <= metrics.utf16Length) {
                "remaining find candidate exceeds the document"
            }
        }
    }
}

/** Reports malformed or unsupported data returned by the native find bridge. */
internal class FindProtocolException(message: String) : IllegalArgumentException(message)

/** Decodes and validates the fixed-size Rust find packet. */
internal object FindPacketDecoder {
    /** Decodes one complete version 2 little-endian find packet. */
    fun decode(packet: ByteArray): FindBatch {
        if (packet.size < FIND_PACKET_BYTES) {
            throw FindProtocolException("find packet is shorter than its fixed length")
        }

        val reader = DocumentPacketReader(packet, ::FindProtocolException)
        val magic = reader.readUnsignedInt("packet magic")
        if (magic != FIND_PACKET_MAGIC) {
            reader.reject("find packet has invalid magic")
        }
        val version = reader.readUnsignedShort("packet version")
        if (version != FIND_PACKET_VERSION) {
            reader.reject("unsupported find packet version $version")
        }
        val flags = reader.readUnsignedShort("find flags")
        reader.requireAllowedFlags(flags, FIND_ALLOWED_FLAGS, "find flags")
        if (flags and FIND_HAS_MATCH != 0 && flags and FIND_HAS_REMAINING_CANDIDATE != 0) {
            reader.reject("find packet contains both a match and a remaining candidate")
        }

        val metrics = decodeDocumentMetrics(reader)
        val matchRangeStart = reader.readSupportedUnsignedLong("match range start")
        val matchRangeEnd = reader.readSupportedUnsignedLong("match range end")
        val matchStartLine = reader.readSupportedUnsignedLong("match start line")
        val matchStartUtf16Offset =
            reader.readSupportedUnsignedLong("match start utf-16 offset")
        val remainingRangeStart = reader.readSupportedUnsignedLong("remaining range start")
        val remainingRangeEnd = reader.readSupportedUnsignedLong("remaining range end")

        if (reader.remainingBytes != 0) {
            reader.reject("find packet has trailing bytes")
        }

        val match =
            decodeMatch(
                flags = flags,
                metrics = metrics,
                rangeStart = matchRangeStart,
                rangeEnd = matchRangeEnd,
                startLine = matchStartLine,
                startUtf16Offset = matchStartUtf16Offset,
                reader = reader
            )
        val remainingCandidateRange =
            decodeRemainingCandidate(
                flags = flags,
                metrics = metrics,
                rangeStart = remainingRangeStart,
                rangeEnd = remainingRangeEnd,
                reader = reader
            )
        return FindBatch(
            metrics = metrics,
            match = match,
            remainingCandidateRange = remainingCandidateRange
        )
    }

    /** Decodes one present match or requires every absent field to be canonical zero. */
    private fun decodeMatch(
        flags: Int,
        metrics: DocumentMetrics,
        rangeStart: Long,
        rangeEnd: Long,
        startLine: Long,
        startUtf16Offset: Long,
        reader: DocumentPacketReader
    ): FindMatch? {
        if (flags and FIND_HAS_MATCH == 0) {
            if (rangeStart != 0L || rangeEnd != 0L || startLine != 0L || startUtf16Offset != 0L) {
                reader.reject("absent find match contains nonzero fields")
            }
            return null
        }
        if (rangeEnd < rangeStart) {
            reader.reject("find match end precedes its start")
        }
        if (rangeEnd == rangeStart) {
            reader.reject("find match must be nonempty")
        }
        if (rangeEnd > metrics.utf16Length) {
            reader.reject("find match exceeds the document")
        }
        if (rangeEnd - rangeStart > MAX_FIND_MATCH_UTF16_UNITS.toLong()) {
            reader.reject("find match exceeds the supported limit")
        }
        if (startLine >= metrics.lineCount) {
            reader.reject("find match line exceeds the document")
        }
        if (startUtf16Offset > rangeStart) {
            reader.reject("find match line-relative offset exceeds its global start")
        }
        if (startLine == 0L && startUtf16Offset != rangeStart) {
            reader.reject("first-line find match has an inconsistent start offset")
        }
        if (rangeStart == 0L && (startLine != 0L || startUtf16Offset != 0L)) {
            reader.reject("document-origin find match has an inconsistent start position")
        }
        return FindMatch(
            range = Utf16Range(start = rangeStart, end = rangeEnd),
            start =
                ViewportCursor(
                    revision = metrics.revision,
                    line = startLine,
                    utf16Offset = startUtf16Offset
                )
        )
    }

    /** Decodes one nonempty remaining candidate or requires canonical absent fields. */
    private fun decodeRemainingCandidate(
        flags: Int,
        metrics: DocumentMetrics,
        rangeStart: Long,
        rangeEnd: Long,
        reader: DocumentPacketReader
    ): Utf16Range? {
        if (flags and FIND_HAS_REMAINING_CANDIDATE == 0) {
            if (rangeStart != 0L || rangeEnd != 0L) {
                reader.reject("absent remaining find candidate contains nonzero fields")
            }
            return null
        }
        if (rangeEnd < rangeStart) {
            reader.reject("remaining find candidate end precedes its start")
        }
        if (rangeEnd == rangeStart) {
            reader.reject("remaining find candidate must be nonempty")
        }
        if (rangeEnd > metrics.utf16Length) {
            reader.reject("remaining find candidate exceeds the document")
        }
        return Utf16Range(start = rangeStart, end = rangeEnd)
    }
}
