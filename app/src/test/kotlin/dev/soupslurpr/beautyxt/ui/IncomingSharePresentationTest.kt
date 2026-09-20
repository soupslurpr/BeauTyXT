/* Verifies the bounded incoming-content review presentation. */
package dev.soupslurpr.beautyxt.ui

import dev.soupslurpr.beautyxt.R
import org.junit.Assert.assertEquals
import org.junit.Test

/** Verifies shared-content counts match document Unicode-scalar metrics. */
class IncomingSharePresentationTest {
    /** Verifies empty, singular and plural counts without counting surrogate halves. */
    @Test
    fun countsUnicodeScalars() {
        assertEquals(
            UiText.Quantity(R.plurals.incoming_unicode_characters, 0, listOf(0)),
            incomingShareCharacterCount("")
        )
        assertEquals(
            UiText.Quantity(R.plurals.incoming_unicode_characters, 1, listOf(1)),
            incomingShareCharacterCount("a")
        )
        assertEquals(
            UiText.Quantity(R.plurals.incoming_unicode_characters, 1, listOf(1)),
            incomingShareCharacterCount("😀")
        )
        assertEquals(
            UiText.Quantity(R.plurals.incoming_unicode_characters, 2, listOf(2)),
            incomingShareCharacterCount("a😀")
        )
        assertEquals(
            UiText.Quantity(R.plurals.incoming_unicode_characters, 2, listOf(2)),
            incomingShareCharacterCount("e\u0301")
        )
    }
}
