package dev.soupslurpr.beautyxt.sharing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectTextShareLimitsTest {
    @Test
    fun acceptsOutgoingTextAtEveryUtf8Width() {
        for (scalar in listOf("a", "β", "中", "😀")) {
            val width = scalar.toByteArray(Charsets.UTF_8).size
            val repetitions = MAX_SHARED_TEXT_UTF8_BYTES.toInt() / width
            val text =
                scalar.repeat(repetitions) + "a".repeat(MAX_SHARED_TEXT_UTF8_BYTES.toInt() % width)
            assertTrue(acceptsDirectSharedText(text))
            assertFalse(acceptsDirectSharedText(text + "a"))
        }
    }

    @Test
    fun acceptsThePreviouslyBrokenShareRoundTrip() {
        assertTrue(acceptsDirectSharedText("x".repeat(50_000)))
        assertTrue(acceptsDirectSharedText(""))
    }

    @Test
    fun rejectsInvalidUnicodeWithoutReplacementCharacters() {
        assertFalse(acceptsDirectSharedText("\uD800"))
        assertFalse(acceptsDirectSharedText("\uDC00"))
        assertFalse(acceptsDirectSharedText("a\uD800b"))
    }
}
