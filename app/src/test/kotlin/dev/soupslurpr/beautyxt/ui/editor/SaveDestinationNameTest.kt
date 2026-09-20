package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.document.DocumentFormat
import dev.soupslurpr.beautyxt.testing.ImmediateSessionTestDispatcher
import dev.soupslurpr.beautyxt.testing.TestEditorDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

private const val MAXIMUM_MARKDOWN_STEM_LENGTH = 252

/** Verifies save picker suggestions remain safe and generation-owned. */
class SaveDestinationNameTest {
    /** Preserves an existing filename when its extension matches the selected format. */
    @Test
    fun preservesMatchingFilename() {
        assertEquals(
            "Meeting notes.txt",
            suggestSaveDestinationName(
                title = "Meeting notes.txt",
                format = DocumentFormat.PlainText
            )
        )
        assertEquals(
            "README.MD",
            suggestSaveDestinationName(
                title = "README.MD",
                format = DocumentFormat.Markdown
            )
        )
    }

    /** Replaces only the final known extension when the selected format changes. */
    @Test
    fun switchesKnownFilenameExtension() {
        assertEquals(
            "Meeting notes.md",
            suggestSaveDestinationName(
                title = "Meeting notes.TXT",
                format = DocumentFormat.Markdown
            )
        )
        assertEquals(
            "archive.notes.txt",
            suggestSaveDestinationName(
                title = "archive.notes.md",
                format = DocumentFormat.PlainText
            )
        )
    }

    /** Preserves the stem of the longer Markdown extension already recognized by the editor. */
    @Test
    fun recognizesLongMarkdownFilenames() {
        assertEquals(
            "Notes.md",
            suggestSaveDestinationName("Notes.MARKDOWN", DocumentFormat.Markdown)
        )
        assertEquals(
            "Notes.txt",
            suggestSaveDestinationName("Notes.markdown", DocumentFormat.PlainText)
        )
    }

    /** Falls back for generic titles and unrecognized filename extensions. */
    @Test
    fun fallsBackForGenericOrUnknownNames() {
        val genericOrUnknownTitles =
            listOf(
                "New document",
                "Opened document",
                "notes",
                ".txt",
                ".md",
                ".markdown"
            )

        genericOrUnknownTitles.forEach { title ->
            assertEquals(
                DocumentFormat.PlainText.fallbackSuggestedName,
                suggestSaveDestinationName(title, DocumentFormat.PlainText)
            )
            assertEquals(
                DocumentFormat.Markdown.fallbackSuggestedName,
                suggestSaveDestinationName(title, DocumentFormat.Markdown)
            )
        }
    }

    /** Falls back for structural, directional, and edge-spacing characters. */
    @Test
    fun fallsBackForUnsafeNames() {
        val unsafeTitles =
            listOf(
                "folder/notes.md",
                "folder\\notes.md",
                "line\nbreak.txt",
                "hidden\u0000suffix.txt",
                "safe\u202etxt.md",
                "safe\u2066txt.md",
                " leading.md",
                "trailing.md ",
                "broken\ud800name.txt"
            )

        unsafeTitles.forEach { title ->
            assertEquals(
                DocumentFormat.Markdown.fallbackSuggestedName,
                suggestSaveDestinationName(title, DocumentFormat.Markdown)
            )
        }
    }

    /** Falls back when changing extensions would exceed the filename bound. */
    @Test
    fun boundsChangedExtension() {
        val maximumMarkdownName = "a".repeat(MAXIMUM_MARKDOWN_STEM_LENGTH) + ".md"

        assertEquals(
            maximumMarkdownName,
            suggestSaveDestinationName(maximumMarkdownName, DocumentFormat.Markdown)
        )
        assertEquals(
            DocumentFormat.PlainText.fallbackSuggestedName,
            suggestSaveDestinationName(maximumMarkdownName, DocumentFormat.PlainText)
        )
    }

    /** Freezes the suggestion into the exact retained picker request. */
    @Test
    fun freezesSuggestionInDestinationRequest() {
        val session = createSession(title = "Field notes.md")
        session.openInitialEditor()
        session.showSaveCopyFormatSelection()

        session.selectSaveFormat(DocumentFormat.PlainText)

        val readyRequest =
            (session.saveStatus as SaveStatus.DestinationReady).request
        assertEquals("Field notes.txt", readyRequest.suggestedName)
        assertSame(readyRequest, session.claimSaveDestination())
        session.close()
    }

    /** Creates one immediately dispatched session with an explicit in-memory title. */
    private fun createSession(title: String): EditorSession = EditorSession(
        title = title,
        state =
            EditorDocumentState(
                document = TestEditorDocument("content"),
                workerDispatcher = ImmediateSessionTestDispatcher
            ),
        operationDispatcher = ImmediateSessionTestDispatcher
    )
}
