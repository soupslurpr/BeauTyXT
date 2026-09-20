package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.document.DocumentLineEnding
import dev.soupslurpr.beautyxt.document.DocumentMetrics
import dev.soupslurpr.beautyxt.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Test

/** Verifies stable, privacy-preserving File info presentation. */
class FileInfoPresentationTest {
    /** Distinguishes absent, single-style, and mixed current line endings. */
    @Test
    fun describesCurrentLineEndings() {
        assertEquals(UiText.Resource(R.string.file_info_none), fileInfoLineEndings(metrics()))
        assertEquals(
            UiText.Literal("CRLF"),
            fileInfoLineEndings(metrics(hasCrlfLineEndings = true))
        )
        assertEquals(
            UiText.Resource(R.string.file_info_mixed_line_endings, listOf("LF, CRLF, CR")),
            fileInfoLineEndings(
                metrics(
                    hasLfLineEndings = true,
                    hasCrlfLineEndings = true,
                    hasCrLineEndings = true
                )
            )
        )
    }

    /** Describes source capability and save state without source identity. */
    @Test
    fun describesSourceState() {
        assertEquals(
            UiText.Resource(R.string.file_info_transient),
            fileInfoSourceAccess(false, false)
        )
        assertEquals(
            UiText.Resource(R.string.file_info_view_only),
            fileInfoSourceAccess(true, true)
        )
        assertEquals(
            UiText.Resource(R.string.file_info_editable),
            fileInfoSourceAccess(true, false)
        )
        assertEquals(
            UiText.Resource(R.string.file_info_opened_version),
            fileInfoSaveState(SourceSaveStatus.Saved, true)
        )
        assertEquals(
            UiText.Resource(R.string.file_info_saved),
            fileInfoSaveState(SourceSaveStatus.Saved, false)
        )
        assertEquals(
            UiText.Resource(R.string.file_info_saving),
            fileInfoSaveState(SourceSaveStatus.Saving, false)
        )
        assertEquals(
            UiText.Resource(R.string.status_source_reloading),
            fileInfoSaveState(SourceSaveStatus.Reloading, false)
        )
        assertEquals(
            UiText.Resource(R.string.file_info_source_changed),
            fileInfoSaveState(SourceSaveStatus.Conflict(UiText.Literal("conflict")), false)
        )
    }

    /** Creates one internally consistent deterministic metrics value. */
    private fun metrics(
        hasLfLineEndings: Boolean = false,
        hasCrlfLineEndings: Boolean = false,
        hasCrLineEndings: Boolean = false
    ): DocumentMetrics {
        val hasLineEndings =
            hasLfLineEndings || hasCrlfLineEndings || hasCrLineEndings
        return DocumentMetrics(
            revision = 0,
            byteLength = if (hasLineEndings) 1 else 0,
            serializedByteLength = if (hasCrlfLineEndings) {
                2
            } else if (hasLineEndings) {
                1
            } else {
                0
            },
            characterLength = if (hasLineEndings) 1 else 0,
            utf16Length = if (hasLineEndings) 1 else 0,
            lineCount = if (hasLineEndings) 2 else 1,
            wordCount = 0,
            hasUtf8Bom = false,
            hasLfLineEndings = hasLfLineEndings,
            hasCrlfLineEndings = hasCrlfLineEndings,
            hasCrLineEndings = hasCrLineEndings,
            insertedLineEnding = DocumentLineEnding.Lf,
            isEditable = true
        )
    }
}
