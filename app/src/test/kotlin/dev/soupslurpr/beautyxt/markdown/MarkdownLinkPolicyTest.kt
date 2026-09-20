package dev.soupslurpr.beautyxt.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies Markdown destinations are classified before Android receives them. */
class MarkdownLinkPolicyTest {
    @Test
    fun acceptsCuratedExternalSchemesWithoutConfirmation() {
        assertExternal("https://example.com", "https", MarkdownExternalAction.View)
        assertExternal("HTTP://example.com", "http", MarkdownExternalAction.View)
        assertExternal("mailto:reader@example.com", "mailto", MarkdownExternalAction.SendTo)
        assertExternal("tel:+15551234567", "tel", MarkdownExternalAction.Dial)
        assertExternal("sms:+15551234567", "sms", MarkdownExternalAction.SendTo)
        assertExternal("smsto:+15551234567", "smsto", MarkdownExternalAction.SendTo)
        assertExternal("geo:0,0?q=library", "geo", MarkdownExternalAction.View)
    }

    @Test
    fun confirmsUnfamiliarExternalSchemes() {
        val action = markdownLinkAction("matrix:roomid/example.org")

        val external = action as MarkdownLinkAction.External
        assertEquals("matrix", external.scheme)
        assertEquals(MarkdownExternalAction.View, external.action)
        assertTrue(external.requiresConfirmation)
    }

    @Test
    fun blocksLocalAndExecutableSchemesCaseInsensitively() {
        listOf("blob", "content", "data", "file", "filesystem", "intent", "javascript")
            .forEach { scheme ->
                val action = markdownLinkAction("${scheme.uppercase()}:payload")

                val unavailable = action as MarkdownLinkAction.Unavailable
                assertEquals(MarkdownLinkUnavailableReason.BlockedScheme, unavailable.reason)
                assertEquals(scheme, unavailable.scheme)
            }
    }

    @Test
    fun keepsRelativeDocumentsInsideTheCurrentGrantBoundary() {
        listOf("notes.md", "../notes.md", "/notes.md", "//example.com/notes.md", "?section=2")
            .forEach { destination ->
                val unavailable =
                    markdownLinkAction(destination) as MarkdownLinkAction.Unavailable

                assertEquals(
                    MarkdownLinkUnavailableReason.RelativeDocument,
                    unavailable.reason
                )
            }
    }

    @Test
    fun rejectsAmbiguousOrMalformedDestinations() {
        listOf("", " https://example.com", "https://example.com ", "https://exa mple.com")
            .forEach { destination ->
                val unavailable =
                    markdownLinkAction(destination) as MarkdownLinkAction.Unavailable

                assertEquals(MarkdownLinkUnavailableReason.Invalid, unavailable.reason)
            }
        val malformedFragment =
            markdownLinkAction("#broken%2") as MarkdownLinkAction.Unavailable
        assertEquals(MarkdownLinkUnavailableReason.Invalid, malformedFragment.reason)
    }

    @Test
    fun decodesInternalHeadingFragmentsWithoutTreatingPlusAsSpace() {
        assertEquals(
            MarkdownLinkAction.Heading("C++ notes"),
            markdownLinkAction("#C++%20notes")
        )
    }

    /** Verifies one curated external link classification. */
    private fun assertExternal(
        destination: String,
        scheme: String,
        expectedAction: MarkdownExternalAction
    ) {
        val external = markdownLinkAction(destination) as MarkdownLinkAction.External

        assertEquals(scheme, external.scheme)
        assertEquals(expectedAction, external.action)
        assertFalse(external.requiresConfirmation)
    }
}
