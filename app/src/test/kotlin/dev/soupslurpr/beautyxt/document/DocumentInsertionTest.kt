package dev.soupslurpr.beautyxt.document

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class DocumentInsertionTest {
    @Test fun normalizesAcrossChunkAndSliceBoundariesWithoutSplittingUnicode() {
        val raw = "x".repeat(8190) + "😀\r\n" + "é\r".repeat(12_000) + "\r"
        val input = DocumentInsertion("prefix${raw}suffix", 6, 6 + raw.length, followingLineFeed = true)
        val chunks = ArrayList<String>()
        input.forEachChunk { chunks += it }
        assertTrue(chunks.size > 2)
        assertTrue(chunks.all { it.length <= 8192 && it.hasWellFormedUtf16() })
        val expected = raw.dropLast(1).replace("\r\n", "\n").replace('\r', '\n')
        assertEquals(expected, chunks.joinToString(""))
        assertEquals(expected.length.toLong(), input.normalizedOffset(input.rawLength))
        assertEquals(8192L, input.normalizedOffset(8193)) // Between CR and LF.
        assertEquals(8193L, input.normalizedOffset(8194)) // After LF.
    }

    @Test fun rejectsMalformedUnicodeAndAllowsCancellationBeforeMoreChunks() {
        for (invalid in listOf("\uD800", "\uDC00", "a\uD800b", "x".repeat(8190) + "\uD800")) {
            assertThrows(IllegalArgumentException::class.java) { DocumentInsertion(invalid).forEachChunk { } }
        }
        var calls = 0
        var chunks = 0
        assertThrows(CancellationException::class.java) {
            DocumentInsertion("a".repeat(100_000)).forEachChunk(
                checkCancelled = { if (++calls == 3) throw CancellationException("cancelled") },
                consume = { chunks++ }
            )
        }
        assertEquals(2, chunks)
    }

    @Test fun invertsAdjacentEditsWithoutTextOrLosingCoordinates() {
        val changes = listOf(DocumentEditShape(Utf16Range(2, 5), 0),
            DocumentEditShape(Utf16Range(5, 9), 2), DocumentEditShape(Utf16Range(12, 12), 7))
        val inverse = inverseChanges(changes)
        assertEquals(listOf(DocumentEditShape(Utf16Range(2, 2), 3),
            DocumentEditShape(Utf16Range(2, 4), 4), DocumentEditShape(Utf16Range(7, 14), 0)), inverse)
        assertEquals(changes, inverseChanges(inverse))
    }
}
