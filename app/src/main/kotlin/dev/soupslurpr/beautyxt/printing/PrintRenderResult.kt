package dev.soupslurpr.beautyxt.printing

import android.print.PageRange

/** Reports the complete logical page count and exact original pages written. */
internal data class PrintRenderResult(
    val totalPageCount: Int,
    val writtenPageRanges: List<PageRange>
) {
    init {
        require(totalPageCount > 0) { "printed document must contain a page" }
        require(writtenPageRanges.isNotEmpty()) { "printed page ranges must not be empty" }
    }
}
