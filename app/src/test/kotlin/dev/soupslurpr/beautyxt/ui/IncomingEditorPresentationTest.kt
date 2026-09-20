package dev.soupslurpr.beautyxt.ui

import dev.soupslurpr.beautyxt.document.DocumentFormat
import dev.soupslurpr.beautyxt.importing.client.SelectedDocumentMetadata
import dev.soupslurpr.beautyxt.sharing.IncomingSourcePurpose
import dev.soupslurpr.beautyxt.ui.editor.EditorPresentation
import org.junit.Assert.assertEquals
import org.junit.Test

/** Verifies entry presentation independently from provider permissions and text contents. */
class IncomingEditorPresentationTest {
    /** Uses metadata discovered during import, not the sender's earlier format hint. */
    @Test
    fun providerFilenameAndTypeDetermineTheOpeningPresentation() {
        val cases = listOf(
            Triple("notes.mdown", "text/plain", DocumentFormat.Markdown),
            Triple("notes.txt", "text/markdown", DocumentFormat.PlainText),
            Triple("notes", "text/markdown", DocumentFormat.Markdown),
            Triple("notes", "text/plain", DocumentFormat.PlainText)
        )
        for ((name, type, expectedFormat) in cases) {
            for (purpose in IncomingSourcePurpose.entries) {
                val conflictingHint = if (expectedFormat == DocumentFormat.Markdown) {
                    DocumentFormat.PlainText
                } else {
                    DocumentFormat.Markdown
                }
                val expected = if (
                    expectedFormat == DocumentFormat.Markdown && purpose != IncomingSourcePurpose.Edit
                ) {
                    EditorPresentation.MarkdownPreview
                } else {
                    EditorPresentation.Text
                }
                assertEquals(
                    expected,
                    incomingEditorPresentation(
                        format = conflictingHint,
                        purpose = purpose,
                        metadata = SelectedDocumentMetadata(displayName = name, mimeType = type)
                    )
                )
            }
        }
    }

    /** Selects preview for explicitly formatted shared Markdown, including received text. */
    @Test
    fun previewsSharedMarkdown() {
        assertEquals(
            EditorPresentation.MarkdownPreview,
            incomingEditorPresentation(DocumentFormat.Markdown)
        )
    }

    /** Selects preview for an external Markdown view request. */
    @Test
    fun previewsViewedMarkdown() {
        assertEquals(
            EditorPresentation.MarkdownPreview,
            incomingEditorPresentation(DocumentFormat.Markdown, IncomingSourcePurpose.View)
        )
    }

    /** Keeps explicit editing and Home's file picker in source mode. */
    @Test
    fun editsMarkdownWhenRequested() {
        assertEquals(
            EditorPresentation.Text,
            incomingEditorPresentation(DocumentFormat.Markdown, IncomingSourcePurpose.Edit)
        )
    }

    /** Keeps every plain-text purpose in source mode without inspecting its contents. */
    @Test
    fun keepsPlainTextUnchanged() {
        for (purpose in IncomingSourcePurpose.entries) {
            assertEquals(
                EditorPresentation.Text,
                incomingEditorPresentation(DocumentFormat.PlainText, purpose)
            )
        }
    }
}
