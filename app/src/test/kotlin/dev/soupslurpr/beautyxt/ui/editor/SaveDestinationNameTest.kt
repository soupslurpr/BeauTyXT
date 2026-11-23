package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.document.DocumentFormat
import dev.soupslurpr.beautyxt.testing.ImmediateSessionTestDispatcher
import dev.soupslurpr.beautyxt.testing.TestEditorDocument
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

private const val MAXIMUM_MARKDOWN_STEM_LENGTH = 252
private val FIXED_INSTANT: Instant = Instant.parse("2026-11-23T15:07:03Z")
private val FIXED_ZONE: ZoneId = ZoneId.of("UTC")
private val FIXED_CLOCK: Clock = Clock.fixed(FIXED_INSTANT, FIXED_ZONE)
private const val EXPECTED_TIMESTAMP_TXT = "2026-11-23 15.07.03.txt"
private const val EXPECTED_TIMESTAMP_MD = "2026-11-23 15.07.03.md"

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

    /** Falls back to timestamped names for generic titles and unrecognized filename extensions. */
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
        val plainFallback = formatTimestampedDocumentName(
            DocumentFormat.PlainText,
            FIXED_INSTANT,
            FIXED_ZONE
        )
        val markdownFallback = formatTimestampedDocumentName(
            DocumentFormat.Markdown,
            FIXED_INSTANT,
            FIXED_ZONE
        )
        assertEquals(EXPECTED_TIMESTAMP_TXT, plainFallback)
        assertEquals(EXPECTED_TIMESTAMP_MD, markdownFallback)

        genericOrUnknownTitles.forEach { title ->
            assertEquals(
                EXPECTED_TIMESTAMP_TXT,
                suggestSaveDestinationName(title, DocumentFormat.PlainText, plainFallback)
            )
            assertEquals(
                EXPECTED_TIMESTAMP_MD,
                suggestSaveDestinationName(title, DocumentFormat.Markdown, markdownFallback)
            )
        }
    }

    /** Formats ASCII digits regardless of the active device locale. */
    @Test
    fun timestampUsesAsciiDigitsAcrossLocales() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals(
                EXPECTED_TIMESTAMP_TXT,
                formatTimestampedDocumentName(DocumentFormat.PlainText, FIXED_INSTANT, FIXED_ZONE)
            )
            assertEquals(
                EXPECTED_TIMESTAMP_MD,
                formatTimestampedDocumentName(DocumentFormat.Markdown, FIXED_INSTANT, FIXED_ZONE)
            )
        } finally {
            Locale.setDefault(previous)
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
        val markdownFallback = formatTimestampedDocumentName(
            DocumentFormat.Markdown,
            FIXED_INSTANT,
            FIXED_ZONE
        )

        unsafeTitles.forEach { title ->
            assertEquals(
                EXPECTED_TIMESTAMP_MD,
                suggestSaveDestinationName(title, DocumentFormat.Markdown, markdownFallback)
            )
        }
        assertEquals(
            DocumentFormat.Markdown.fallbackSuggestedName,
            suggestSaveDestinationName("New document", DocumentFormat.Markdown, "bad/name.md")
        )
    }

    /** Falls back when changing extensions would exceed the filename bound. */
    @Test
    fun boundsChangedExtension() {
        val maximumMarkdownName = "a".repeat(MAXIMUM_MARKDOWN_STEM_LENGTH) + ".md"
        val plainFallback = formatTimestampedDocumentName(
            DocumentFormat.PlainText,
            FIXED_INSTANT,
            FIXED_ZONE
        )

        assertEquals(
            maximumMarkdownName,
            suggestSaveDestinationName(maximumMarkdownName, DocumentFormat.Markdown)
        )
        assertEquals(
            EXPECTED_TIMESTAMP_TXT,
            suggestSaveDestinationName(maximumMarkdownName, DocumentFormat.PlainText, plainFallback)
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

    /** Suggests a timestamped filename for unnamed documents using the session clock. */
    @Test
    fun freezesTimestampedSuggestionForNewDocument() {
        val session = createSession(title = "New document")
        session.openInitialEditor()
        session.showSaveFormatSelection()

        session.selectSaveFormat(DocumentFormat.Markdown)

        val readyRequest =
            (session.saveStatus as SaveStatus.DestinationReady).request
        assertEquals(EXPECTED_TIMESTAMP_MD, readyRequest.suggestedName)
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
        operationDispatcher = ImmediateSessionTestDispatcher,
        clock = FIXED_CLOCK
    )
}
