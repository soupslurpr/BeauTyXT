package dev.soupslurpr.beautyxt.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocumentFormatTest {
    @Test
    fun recognizedNamesOverrideConflictingProviderTypes() {
        for (extension in listOf("md", "markdown", "mdown", "mkd", "MD")) {
            assertEquals(
                DocumentFormat.Markdown,
                resolveDocumentFormat("notes.$extension", "text/plain")
            )
        }
        for (extension in listOf("txt", "text", "log", "asc", "TXT")) {
            assertEquals(
                DocumentFormat.PlainText,
                resolveDocumentFormat("notes.$extension", "text/markdown")
            )
        }
    }

    @Test
    fun unknownNamesFallBackToSupportedMimeTypes() {
        val types = listOf(
            "text/markdown", "TEXT/X-MARKDOWN", "application/markdown", "application/x-markdown"
        )
        for (mimeType in types) {
            assertEquals(DocumentFormat.Markdown, resolveDocumentFormat("notes", mimeType))
        }
        assertEquals(DocumentFormat.PlainText, resolveDocumentFormat(null, "TEXT/CSV"))
        assertNull(resolveDocumentFormat("archive.zip", "application/zip"))
        assertNull(resolveDocumentFormat("notes", null))
    }

    @Test
    fun providerPathIsOnlyAnAlternateFilenameHint() {
        assertEquals(
            DocumentFormat.Markdown,
            resolveDocumentFormat(null, "application/octet-stream", "primary:Documents/README.MD")
        )
        assertEquals(
            DocumentFormat.PlainText,
            resolveDocumentFormat("notes.txt", "text/markdown", "primary:Documents/README.MD")
        )
        assertEquals(DocumentFormat.PlainText, documentFormatForSourceName("diceware-v6.txt.asc"))
        assertNull(documentFormatForSourceName("README.md/archive"))
    }
}
