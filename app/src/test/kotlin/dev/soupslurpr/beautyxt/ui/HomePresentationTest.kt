package dev.soupslurpr.beautyxt.ui

import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.unit.dp
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.document.DocumentFormat
import dev.soupslurpr.beautyxt.sharing.IncomingDocumentShare
import dev.soupslurpr.beautyxt.sharing.IncomingSourcePurpose
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies landing-screen labels and document discovery hints. */
class HomePresentationTest {
    /** Verifies the source action sends only the fixed project URL to Android. */
    @Test
    fun opensOnlyTheProjectSourceUrl() {
        var openedUri: String? = null
        val handler = object : UriHandler {
            override fun openUri(uri: String) {
                openedUri = uri
            }
        }
        assertTrue(tryOpenSourceCode(handler))
        assertEquals("https://github.com/soupslurpr/BeauTyXT", openedUri)
    }

    /** Verifies absent or restricted handlers produce recoverable source-link feedback. */
    @Test
    fun reportsSourceLinkDispatchFailures() {
        listOf(IllegalArgumentException("no handler"), SecurityException("restricted"))
            .forEach { failure ->
                val handler = object : UriHandler {
                    override fun openUri(uri: String): Unit = throw failure
                }
                assertFalse(tryOpenSourceCode(handler))
            }
    }

    /** Verifies the primary open action describes each retained phase. */
    @Test
    fun describesOpenActionPhases() {
        assertEquals(
            UiText.Resource(R.string.home_open_file),
            openDocumentActionLabel(OpenStatus.Idle)
        )
        assertEquals(
            UiText.Resource(R.string.home_open_file),
            openDocumentActionLabel(OpenStatus.Failed(UiText.Literal("Synthetic failure")))
        )
        assertEquals(
            UiText.Resource(R.string.home_selecting_file),
            openDocumentActionLabel(OpenStatus.Selecting)
        )
        assertEquals(
            UiText.Resource(R.string.home_cancel_open),
            openDocumentActionLabel(OpenStatus.Opening)
        )
        assertEquals(
            UiText.Resource(R.string.home_cancelling),
            openDocumentActionLabel(OpenStatus.Cancelling)
        )
    }

    /** Verifies horizontal rows yield before leading icons and labels become cramped. */
    @Test
    fun selectsHorizontalHomeActions() {
        assertTrue(usesHorizontalHomeActions(maxWidth = 440.dp, fontScale = 1f))
        assertFalse(usesHorizontalHomeActions(maxWidth = 439.dp, fontScale = 1f))
        assertTrue(usesHorizontalHomeActions(maxWidth = 572.dp, fontScale = 1.3f))
        assertFalse(usesHorizontalHomeActions(maxWidth = 571.dp, fontScale = 1.3f))
        assertFalse(usesHorizontalHomeActions(maxWidth = 400.dp, fontScale = 1.31f))
    }

    /** Verifies landscape uses both columns only when text has enough room. */
    @Test
    fun selectsWideHomeLayout() {
        assertTrue(usesWideHomeLayout(840.dp, 360.dp, 1f))
        assertFalse(usesWideHomeLayout(400.dp, 900.dp, 1f))
        assertFalse(usesWideHomeLayout(640.dp, 360.dp, 1f))
        assertFalse(usesWideHomeLayout(840.dp, 360.dp, 2f))
    }

    /** Verifies compatible text providers remain discoverable without a universal filter. */
    @Test
    fun discoversCommonTextAndMarkdownMimeTypes() {
        assertArrayEquals(
            arrayOf(
                "text/*",
                "application/markdown",
                "application/x-markdown",
                "application/octet-stream"
            ),
            openDocumentMimeTypes()
        )
    }

    /** Verifies only Android view and edit requests bypass the landing screen. */
    @Test
    fun identifiesDirectSourceRequests() {
        IncomingSourcePurpose.entries.forEach { purpose ->
            val share =
                IncomingDocumentShare.Source(
                    encodedUri = "content://test.documents/source.txt",
                    format = DocumentFormat.PlainText,
                    purpose = purpose
                )
            assertEquals(purpose != IncomingSourcePurpose.Share, isDirectSourceRequest(share))
        }
        assertFalse(isDirectSourceRequest(null))
    }
}
