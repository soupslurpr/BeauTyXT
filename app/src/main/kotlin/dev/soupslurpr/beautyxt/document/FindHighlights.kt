package dev.soupslurpr.beautyxt.document

internal const val MAX_FIND_HIGHLIGHT_UTF16_UNITS = 32 * 1024

/** Requests display-only match coverage in one bounded source range. */
internal data class FindHighlightRequest(
    val revision: Long,
    val query: String,
    val matchCase: Boolean,
    val range: Utf16Range
) {
    init {
        FindRequest(
            revision = revision,
            query = query,
            matchCase = matchCase,
            candidateRange = range,
            direction = FindDirection.Forward,
            maxCandidateUtf16Units = MAX_FIND_HIGHLIGHT_UTF16_UNITS
        )
        require(range.end - range.start <= MAX_FIND_HIGHLIGHT_UTF16_UNITS) {
            "highlight range exceeds its limit"
        }
    }
}

/** Validates sorted, merged native coverage before applying it to displayed text. */
internal fun decodeFindHighlights(
    coordinates: LongArray,
    range: Utf16Range
): List<Utf16Range> {
    require(range.end - range.start <= MAX_FIND_HIGHLIGHT_UTF16_UNITS) {
        "highlight range exceeds its limit"
    }
    require(coordinates.size % 2 == 0) { "highlight coordinates contain an incomplete pair" }
    require(coordinates.size.toLong() <= (range.end - range.start) * 2) {
        "highlight count exceeds its source range"
    }
    var previousEnd: Long? = null
    return List(coordinates.size / 2) { index ->
        val start = coordinates[index * 2]
        val end = coordinates[index * 2 + 1]
        require(start >= range.start && end <= range.end && start < end) {
            "highlight exceeds its source range or is empty"
        }
        require(previousEnd?.let { start > it } != false) {
            "highlights are not sorted and merged"
        }
        previousEnd = end
        Utf16Range(start, end)
    }
}
