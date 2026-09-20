package dev.soupslurpr.beautyxt.printing

import org.junit.Assert.assertEquals
import org.junit.Test

/** Verifies safe native-print PDF naming. */
class PrintDocumentNameTest {
    /** Replaces recognized text extensions without changing the Unicode stem. */
    @Test
    fun replacesTextExtension() {
        assertEquals(
            "Private notes 😀.pdf",
            suggestPrintDocumentName("Private notes 😀.md")
        )
        assertEquals("README.pdf", suggestPrintDocumentName("README.TXT"))
        assertEquals("Notes.pdf", suggestPrintDocumentName("Notes.MARKDOWN"))
    }

    /** Adds one PDF extension to a safe transient title. */
    @Test
    fun addsPdfExtension() {
        assertEquals("New document.pdf", suggestPrintDocumentName("New document"))
    }

    /** Avoids duplicating an existing PDF extension. */
    @Test
    fun preservesPdfExtension() {
        assertEquals("notes.pdf", suggestPrintDocumentName("notes.PDF"))
    }

    /** Falls back when a title is structurally unsafe or leaves no filename stem. */
    @Test
    fun fallsBackForUnsafeTitle() {
        assertEquals("BeauTyXT document.pdf", suggestPrintDocumentName("folder/name.md"))
        assertEquals("BeauTyXT document.pdf", suggestPrintDocumentName(".md"))
        assertEquals("BeauTyXT document.pdf", suggestPrintDocumentName(".markdown"))
    }
}
