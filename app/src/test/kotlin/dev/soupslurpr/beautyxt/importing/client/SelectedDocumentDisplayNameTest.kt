package dev.soupslurpr.beautyxt.importing.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val MAXIMUM_LENGTH_CHARACTER = 'a'

/** Verifies provider display names become bounded, unambiguous editor titles. */
class SelectedDocumentDisplayNameTest {
    /** Preserves a normal Unicode filename and its extension. */
    @Test
    fun preservesNormalUnicodeFilename() {
        val displayName = "Family 👨‍👩‍👧 notes.md"

        assertEquals(displayName, sanitizeSelectedDocumentDisplayName(displayName))
    }

    /** Removes harmless surrounding spacing from the displayed title. */
    @Test
    fun trimsSurroundingSpacing() {
        assertEquals(
            "notes.txt",
            sanitizeSelectedDocumentDisplayName("  notes.txt\u00a0")
        )
    }

    /** Rejects missing and whitespace-only metadata. */
    @Test
    fun rejectsMissingOrBlankMetadata() {
        assertNull(sanitizeSelectedDocumentDisplayName(null))
        assertNull(sanitizeSelectedDocumentDisplayName(" \u00a0 "))
    }

    /** Accepts the exact allocation bound and rejects a longer result. */
    @Test
    fun enforcesUtf16LengthBound() {
        val maximumLengthName =
            MAXIMUM_LENGTH_CHARACTER.toString()
                .repeat(MAX_SELECTED_DOCUMENT_DISPLAY_NAME_UTF16_UNITS)
        val excessiveLengthName = maximumLengthName + MAXIMUM_LENGTH_CHARACTER

        assertEquals(
            maximumLengthName,
            sanitizeSelectedDocumentDisplayName(maximumLengthName)
        )
        assertNull(sanitizeSelectedDocumentDisplayName(excessiveLengthName))
    }

    /** Rejects controls, structural separators, and directional overrides. */
    @Test
    fun rejectsUnsafeCharacters() {
        val unsafeNames =
            listOf(
                "line\nbreak.txt",
                "hidden\u0000suffix.txt",
                "folder/name.txt",
                "folder\\name.txt",
                "safe\u202etxt.md",
                "safe\u2066txt.md",
                "broken\ud800name.txt"
            )

        unsafeNames.forEach { unsafeName ->
            assertNull(sanitizeSelectedDocumentDisplayName(unsafeName))
        }
    }

    /** Canonicalizes well-formed provider MIME types for display. */
    @Test
    fun canonicalizesProviderMimeTypes() {
        assertEquals("text/markdown", sanitizeProviderMimeType(" Text/Markdown "))
        assertEquals(
            "application/x-notes+text",
            sanitizeProviderMimeType("application/x-notes+text")
        )
    }

    /** Rejects missing, parameterized, malformed, and oversized MIME metadata. */
    @Test
    fun rejectsUnsafeProviderMimeTypes() {
        assertNull(sanitizeProviderMimeType(null))
        assertNull(sanitizeProviderMimeType(" "))
        assertNull(sanitizeProviderMimeType("text/plain; charset=utf-8"))
        assertNull(sanitizeProviderMimeType("text"))
        assertNull(sanitizeProviderMimeType("text/plain\nimage/png"))
        assertNull(sanitizeProviderMimeType("a/" + "b".repeat(254)))
    }
}
