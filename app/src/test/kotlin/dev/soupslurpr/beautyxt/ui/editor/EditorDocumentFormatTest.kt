package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.document.DocumentFormat
import dev.soupslurpr.beautyxt.importing.client.SelectedDocumentMetadata
import dev.soupslurpr.beautyxt.testing.ImmediateSessionTestDispatcher
import dev.soupslurpr.beautyxt.testing.TestEditorDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorDocumentFormatTest {
    @Test
    fun fileInfoAndroidSharingQrAndNfcUseTheSameResolvedFormat() {
        val cases = listOf(
            Case("notes.mdown", "text/plain", DocumentFormat.Markdown),
            Case("notes.mkd", null, DocumentFormat.Markdown),
            Case("notes", "application/markdown", DocumentFormat.Markdown),
            Case("notes.txt", "text/markdown", DocumentFormat.PlainText),
            Case("notes", null, DocumentFormat.Markdown, hint = DocumentFormat.Markdown)
        )
        for (case in cases) {
            val qr = TestQrTransferProcessor()
            val nfc = TestNfcTransferProcessor()
            EditorSession(
                title = case.title,
                state = EditorDocumentState(
                    TestEditorDocument("# Note"), ImmediateSessionTestDispatcher
                ),
                documentSource = TestEditorDocumentSource("content://test.documents/note"),
                sourceMetadata = SelectedDocumentMetadata(
                    displayName = case.title, mimeType = case.mimeType
                ),
                sourceFormat = case.hint,
                operationDispatcher = ImmediateSessionTestDispatcher,
                qrTransferProcessor = qr,
                nfcTransferProcessor = nfc
            ).use { session ->
                session.openInitialEditor()
                assertEquals(case.expected, session.documentFormat)
                assertTrue(session.requestShare())
                val shared = (session.shareStatus as ShareStatus.Ready).request
                assertEquals(case.expected, shared.payload.format)
                assertTrue(session.completeShareLaunch(shared.generation))

                assertTrue(session.requestQrShare())
                val qrReady = session.qrShareStatus as QrShareStatus.Ready
                assertEquals(case.expected, qrReady.format)
                assertEquals(listOf(case.expected), qr.encodedFormats)
                session.dismissQrShare(qrReady.generation)

                assertTrue(session.requestNfcWrite())
                val configuring = session.nfcWriteStatus as NfcWriteStatus.Configuring
                assertTrue(session.confirmNfcWriteConfiguration(configuring.generation, null))
                assertEquals(case.expected, (session.nfcWriteStatus as NfcWriteStatus.Ready).format)
                assertEquals(listOf(case.expected), nfc.encodedFormats)
            }
        }
    }

    private data class Case(
        val title: String,
        val mimeType: String?,
        val expected: DocumentFormat,
        val hint: DocumentFormat = DocumentFormat.PlainText
    )
}
