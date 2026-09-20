/* Verifies revision-bound viewport retention across source layout changes. */
package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.document.EditWindowLimits
import dev.soupslurpr.beautyxt.document.Utf16Range
import dev.soupslurpr.beautyxt.testing.TestEditorDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

private const val ORIGINAL_WIDTH_PIXELS = 400
private const val REFLOWED_WIDTH_PIXELS = 800
private const val VIEWPORT_TEST_TEXT = "first\nsecond\nthird"

/** Verifies reflow preserves source positions without superseding explicit navigation. */
class ActiveEditDraftViewportTest {
    /** Restores a retained semantic anchor only when the measured width changes. */
    @Test
    fun restoresSourcePositionAfterWidthChange() {
        val draft = viewportTestDraft()
        val anchor = SemanticViewportAnchor(0, 6, -3)
        draft.restoreViewportAfterWidthChange(ORIGINAL_WIDTH_PIXELS)
        draft.retainViewportAnchor(anchor)

        draft.restoreViewportAfterWidthChange(ORIGINAL_WIDTH_PIXELS)
        assertNull(draft.scrollRestoration)
        draft.restoreViewportAfterWidthChange(REFLOWED_WIDTH_PIXELS)
        val restoration = requireNotNull(draft.scrollRestoration)
        assertEquals(anchor, restoration.anchor)

        draft.consumeScrollRestoration(restoration)
        draft.restoreViewportAfterWidthChange(REFLOWED_WIDTH_PIXELS)
        assertNull(draft.scrollRestoration)
    }

    /** Leaves an explicit incoming navigation target in control during reflow. */
    @Test
    fun preservesExplicitNavigationDuringWidthChange() {
        val navigation = EditWindowScrollRestoration(SemanticViewportAnchor(0, 13, 0))
        val draft = viewportTestDraft(navigation)
        draft.restoreViewportAfterWidthChange(ORIGINAL_WIDTH_PIXELS)
        draft.retainViewportAnchor(SemanticViewportAnchor(0, 6, -3))

        draft.restoreViewportAfterWidthChange(REFLOWED_WIDTH_PIXELS)

        assertSame(navigation, draft.scrollRestoration)
    }

    /** Avoids applying a committed source anchor to text that is still being edited. */
    @Test
    fun skipsStaleAnchorsWhileDraftIsDirty() {
        val draft = viewportTestDraft()
        draft.restoreViewportAfterWidthChange(ORIGINAL_WIDTH_PIXELS)
        draft.retainViewportAnchor(SemanticViewportAnchor(0, 6, -3))
        draft.textFieldState.edit { append("!") }

        draft.restoreViewportAfterWidthChange(REFLOWED_WIDTH_PIXELS)

        assertNull(draft.scrollRestoration)
    }

    /** Clears the old anchor when the same field advances to a committed revision. */
    @Test
    fun clearsAnchorsFromPreviousRevisions() {
        val draft = viewportTestDraft()
        draft.restoreViewportAfterWidthChange(ORIGINAL_WIDTH_PIXELS)
        draft.retainViewportAnchor(SemanticViewportAnchor(0, 6, -3))
        val snapshot = draft.edit.snapshot
        draft.reconcileCommittedEdit(
            draft.edit.copy(snapshot = snapshot.copy(metrics = snapshot.metrics.copy(revision = 1)))
        )

        draft.restoreViewportAfterWidthChange(REFLOWED_WIDTH_PIXELS)

        assertNull(draft.scrollRestoration)
    }
}

/** Creates a clean bounded draft with deterministic source metrics. */
private fun viewportTestDraft(restoration: EditWindowScrollRestoration? = null): ActiveEditDraft =
    TestEditorDocument(VIEWPORT_TEST_TEXT).use { document ->
        ActiveEditDraft(
            initialEdit =
                ActiveEditWindow(
                    generation = 1,
                    snapshot =
                        document.editWindow(
                            revision = 0,
                            selection = Utf16Range(0, 0),
                            limits = EditWindowLimits(VIEWPORT_TEST_TEXT.length)
                        )
                ),
            initialScrollRestoration = restoration
        )
    }
