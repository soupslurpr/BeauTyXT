package dev.soupslurpr.beautyxt.sharing

import android.content.Intent
import dev.soupslurpr.beautyxt.document.DocumentFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Verifies format selection and canonical text normalization for incoming shares. */
class IncomingDocumentShareTest {
    /** Verifies only send, view, and edit actions enter incoming content routing. */
    @Test
    fun recognizesSupportedIncomingActions() {
        assertEquals(
            IncomingSourcePurpose.Share,
            incomingSourcePurposeForAction(Intent.ACTION_SEND)
        )
        assertEquals(
            IncomingSourcePurpose.View,
            incomingSourcePurposeForAction(Intent.ACTION_VIEW)
        )
        assertEquals(
            IncomingSourcePurpose.Edit,
            incomingSourcePurposeForAction(Intent.ACTION_EDIT)
        )
        assertNull(incomingSourcePurposeForAction(Intent.ACTION_MAIN))
        assertNull(incomingSourcePurposeForAction(null))
    }

    @Test
    fun normalizesEveryAndroidLineEnding() {
        assertEquals(
            "one\ntwo\nthree\nfour",
            normalizeIncomingSharedText("one\r\ntwo\rthree\nfour")
        )
    }

    @Test
    fun selectsTransientTitlesThatPreserveFormat() {
        assertEquals(
            "Shared text.txt",
            incomingSharedTextTitle(DocumentFormat.PlainText)
        )
        assertEquals(
            "Shared document.md",
            incomingSharedTextTitle(DocumentFormat.Markdown)
        )
    }
}
