package dev.soupslurpr.beautyxt.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Verifies allocation-free UTF-16 validation and UTF-8 length accounting. */
class Utf16TextTest {
    /** Verifies every UTF-8 width and a valid surrogate pair. */
    @Test
    fun countsUtf8Bytes() {
        assertEquals(0L, "".utf8LengthOrNull())
        assertEquals(1L, "a".utf8LengthOrNull())
        assertEquals(2L, "é".utf8LengthOrNull())
        assertEquals(3L, "€".utf8LengthOrNull())
        assertEquals(4L, "😀".utf8LengthOrNull())
        assertEquals(10L, "aé€😀".utf8LengthOrNull())
    }

    /** Verifies malformed high and low surrogates have no UTF-8 length. */
    @Test
    fun rejectsUnpairedSurrogates() {
        assertNull("\ud800".utf8LengthOrNull())
        assertNull("\udc00".utf8LengthOrNull())
        assertNull("\ud800a".utf8LengthOrNull())
    }
}
