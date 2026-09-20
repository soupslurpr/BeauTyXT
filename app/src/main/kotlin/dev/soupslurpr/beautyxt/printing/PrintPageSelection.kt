package dev.soupslurpr.beautyxt.printing

import android.print.PageRange

private const val BOUNDARIES_PER_RANGE = 2
private const val INITIAL_BOUNDARY_CAPACITY = 8

/** Answers page-membership checks in logarithmic time for one validated Android request. */
internal class PrintPageSelection(pageRanges: Array<out PageRange>) {
    private val ranges = pageRanges.copyOf()

    init {
        require(ranges.isNotEmpty()) { "print page ranges must not be empty" }
        var previousEnd = -1
        ranges.forEach { range ->
            require(range.start >= 0 && range.end >= range.start) {
                "print page range is invalid"
            }
            require(range.start > previousEnd) {
                "print page ranges must be non-overlapping and ascending"
            }
            previousEnd = range.end
        }
    }

    /** Returns whether one zero-based logical page was requested. */
    fun contains(pageIndex: Int): Boolean {
        require(pageIndex >= 0) { "print page index must be nonnegative" }
        var start = 0
        var end = ranges.size
        while (start < end) {
            val middle = start + (end - start) / 2
            val range = ranges[middle]
            when {
                pageIndex < range.start -> end = middle
                pageIndex > range.end -> start = middle + 1
                else -> return true
            }
        }
        return false
    }

    /** Returns whether Android requested every available logical page. */
    val includesEveryPage: Boolean
        get() = ranges.size == 1 && ranges[0] == PageRange.ALL_PAGES
}

/** Compacts monotonically appended page indexes into Android page ranges. */
internal class WrittenPageRanges {
    private var boundaries = IntArray(INITIAL_BOUNDARY_CAPACITY)
    private var boundaryCount = 0

    /** Appends one strictly increasing written page index. */
    fun append(pageIndex: Int) {
        require(pageIndex >= 0) { "written page index must be nonnegative" }
        if (boundaryCount == 0) {
            appendBoundary(pageIndex)
            appendBoundary(pageIndex)
            return
        }
        val previousEndIndex = boundaryCount - 1
        val previousEnd = boundaries[previousEndIndex]
        require(pageIndex > previousEnd) { "written page indexes must be strictly increasing" }
        if (pageIndex == Math.incrementExact(previousEnd)) {
            boundaries[previousEndIndex] = pageIndex
        } else {
            appendBoundary(pageIndex)
            appendBoundary(pageIndex)
        }
    }

    /** Returns the compact nonempty Android ranges written so far. */
    fun toList(): List<PageRange> {
        require(boundaryCount > 0 && boundaryCount % BOUNDARIES_PER_RANGE == 0) {
            "no print pages were written"
        }
        return List(boundaryCount / BOUNDARIES_PER_RANGE) { rangeIndex ->
            val boundaryIndex = rangeIndex * BOUNDARIES_PER_RANGE
            PageRange(boundaries[boundaryIndex], boundaries[boundaryIndex + 1])
        }
    }

    /** Adds one boundary while retaining a primitive contiguous buffer. */
    private fun appendBoundary(value: Int) {
        if (boundaryCount == boundaries.size) {
            boundaries = boundaries.copyOf(Math.multiplyExact(boundaries.size, 2))
        }
        boundaries[boundaryCount] = value
        boundaryCount = Math.incrementExact(boundaryCount)
    }
}
