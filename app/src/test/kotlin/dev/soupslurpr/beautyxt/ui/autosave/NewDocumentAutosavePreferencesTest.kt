package dev.soupslurpr.beautyxt.ui.autosave

import dev.soupslurpr.beautyxt.document.DocumentFormat
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies strict content-free parsing and canonical formatting of autosave markers. */
class NewDocumentAutosavePreferencesTest {
    @Test
    fun roundTripsCanonicalEnabledAndChangingMarkers() {
        val disabled = NewDocumentAutosaveMarker()
        assertTrue(disabled.isDefault)
        assertEquals("", formatNewDocumentAutosaveMarker(disabled))
        assertEquals(
            disabled,
            readNewDocumentAutosaveMarker(ByteArrayInputStream(ByteArray(0)))
        )

        val enabledTxt = NewDocumentAutosaveMarker(
            state = NewDocumentAutosaveMarkerState.Enabled,
            format = DocumentFormat.PlainText
        )
        assertFalse(enabledTxt.isDefault)
        assertEquals("state=enabled\nformat=txt\n", formatNewDocumentAutosaveMarker(enabledTxt))
        assertEquals(
            enabledTxt,
            readNewDocumentAutosaveMarker(
                ByteArrayInputStream(formatNewDocumentAutosaveMarker(enabledTxt).toByteArray())
            )
        )

        val enabledMd = NewDocumentAutosaveMarker(
            state = NewDocumentAutosaveMarkerState.Enabled,
            format = DocumentFormat.Markdown,
            grantTime = 1_795_450_000_000L
        )
        assertEquals(
            "state=enabled\nformat=md\ngrant_time=1795450000000\n",
            formatNewDocumentAutosaveMarker(enabledMd)
        )
        assertEquals(
            enabledMd,
            readNewDocumentAutosaveMarker(
                ByteArrayInputStream(formatNewDocumentAutosaveMarker(enabledMd).toByteArray())
            )
        )

        val changingMd = NewDocumentAutosaveMarker(
            state = NewDocumentAutosaveMarkerState.Changing,
            format = DocumentFormat.Markdown
        )
        assertEquals("state=changing\nformat=md\n", formatNewDocumentAutosaveMarker(changingMd))
        assertEquals(
            changingMd,
            readNewDocumentAutosaveMarker(
                ByteArrayInputStream(formatNewDocumentAutosaveMarker(changingMd).toByteArray())
            )
        )
    }

    @Test
    fun rejectsMalformedUnknownDuplicateOrOversizeMarkers() {
        val invalidPayloads = listOf(
            "format=md\n".toByteArray(),
            "state=disabled\n".toByteArray(),
            "state=enabled\nstate=enabled\n".toByteArray(),
            "state=enabled\nformat=md\nformat=txt\n".toByteArray(),
            "state=enabled\nfolder_uri=content://foo\n".toByteArray(),
            "state=enabled\nformat=pdf\n".toByteArray(),
            "state=enabled\ngrant_time=0\n".toByteArray(),
            "state=enabled\ngrant_time=abc\n".toByteArray(),
            "state=changing\ngrant_time=123\n".toByteArray(),
            "not-a-key-value\n".toByteArray(),
            "state=enabled\u0000\n".toByteArray(),
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()),
            ByteArray(MAX_NEW_DOCUMENT_AUTOSAVE_BYTES + 1) { 'a'.code.toByte() }
        )

        invalidPayloads.forEach { payload ->
            assertThrows(IllegalArgumentException::class.java) {
                readNewDocumentAutosaveMarker(ByteArrayInputStream(payload))
            }
        }
    }
}
