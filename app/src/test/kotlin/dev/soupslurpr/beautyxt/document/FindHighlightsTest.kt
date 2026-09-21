package dev.soupslurpr.beautyxt.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FindHighlightsTest {
    /** Verifies native coordinates stay global, including offsets beyond Android field limits. */
    @Test
    fun decodesBoundedCoverageAtLargeDocumentOffsets() {
        val start = Int.MAX_VALUE.toLong() + 100
        assertEquals(
            listOf(Utf16Range(start, start + 3), Utf16Range(start + 5, start + 9)),
            decodeFindHighlights(
                longArrayOf(start, start + 3, start + 5, start + 9),
                Utf16Range(start, start + 10)
            )
        )
    }

    /** Verifies malformed coverage cannot escape its window or multiply overlapping spans. */
    @Test
    fun rejectsMalformedNativeCoverage() {
        for (coordinates in listOf(
            longArrayOf(5),
            longArrayOf(4, 7),
            longArrayOf(5, 11),
            longArrayOf(6, 6),
            longArrayOf(8, 7),
            longArrayOf(5, 8, 7, 9),
            longArrayOf(5, 7, 7, 9),
            longArrayOf(8, 9, 5, 6)
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                decodeFindHighlights(coordinates, Utf16Range(5, 10))
            }
        }
    }
}
