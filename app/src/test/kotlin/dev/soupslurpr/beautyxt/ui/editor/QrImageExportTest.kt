package dev.soupslurpr.beautyxt.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MAXIMUM_MARKDOWN_STEM_LENGTH = 252

/** Verifies QR image naming and active-state boundaries without Android I/O. */
class QrImageExportTest {
    /** Derives a recognizable filename for each lossless format. */
    @Test
    fun derivesSafeImageNames() {
        QrImageFormat.entries.forEach { format ->
            assertEquals("Notes QR.${format.extension}", suggestQrImageDestinationName("Notes.md", format))
            assertEquals("Notes QR.${format.extension}", suggestQrImageDestinationName("Notes.MARKDOWN", format))
            assertEquals("archive.notes QR.${format.extension}", suggestQrImageDestinationName("archive.notes.TXT", format))
        }
        assertEquals("image/webp", QrImageFormat.WebP.mimeType)
        assertEquals("image/png", QrImageFormat.Png.mimeType)
    }

    /** Falls back for generic, unsafe, and overlong editor titles. */
    @Test
    fun fallsBackForUnsafeImageNames() {
        QrImageFormat.entries.forEach { format ->
            val fallback = "BeauTyXT QR code.${format.extension}"
            listOf("New document", ".markdown", "folder/Notes.md",
                "a".repeat(MAXIMUM_MARKDOWN_STEM_LENGTH) + ".md").forEach { title ->
                assertEquals(fallback, suggestQrImageDestinationName(title, format))
            }
        }
    }

    /** Treats only picker ownership and active writing as blocking phases. */
    @Test
    fun identifiesActiveImageSavePhases() {
        assertTrue(QrImageSaveStatus.ChoosingDestination.isActive())
        assertTrue(QrImageSaveStatus.Saving.isActive())
        assertFalse(QrImageSaveStatus.Idle.isActive())
        assertFalse(QrImageSaveStatus.Succeeded.isActive())
        assertFalse(QrImageSaveStatus.Failed.isActive())
    }
}
