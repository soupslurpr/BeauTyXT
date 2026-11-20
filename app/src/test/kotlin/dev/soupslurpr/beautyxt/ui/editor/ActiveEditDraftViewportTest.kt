/* Verifies revision-bound viewport retention across source layout changes. */
package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.foundation.MutatePriority
import dev.soupslurpr.beautyxt.document.EditWindowLimits
import dev.soupslurpr.beautyxt.document.Utf16Range
import dev.soupslurpr.beautyxt.testing.TestEditorDocument
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

private const val ORIGINAL_WIDTH_PIXELS = 400
private const val REFLOWED_WIDTH_PIXELS = 800
private const val VIEWPORT_TEST_TEXT = "first\nsecond\nthird"

/** Verifies reflow preserves source positions without superseding explicit navigation. */
class ActiveEditDraftViewportTest {
    @Test
    fun userDragConsumesPendingRestorationAndKeepsItsScrollPosition() = runBlocking {
        val request = EditWindowScrollRestoration(SemanticViewportAnchor(0, 6, 0))
        val draft = viewportTestDraft(request)
        // Keep the old scroll's lock while scrollTo starts, so the drag interrupts
        // an in-flight restoration before it can move the viewport.
        val release = CompletableDeferred<Unit>()
        val oldScroll = launch(start = CoroutineStart.UNDISPATCHED) {
            draft.scrollState.scroll {
                try { awaitCancellation() } finally { withContext(NonCancellable) { release.await() } }
            }
        }
        val restoration = launch(start = CoroutineStart.UNDISPATCHED) {
            draft.restoreScrollPosition(request, 100) { error("vertical drag should take priority") }
        }
        val drag = launch(start = CoroutineStart.UNDISPATCHED) {
            draft.scrollState.scroll(MutatePriority.UserInput) { scrollBy(40f) }
        }
        release.complete(Unit)
        joinAll(oldScroll, restoration, drag)
        assertFalse(restoration.isCancelled)
        assertNull(draft.scrollRestoration)
        assertEquals(40, draft.scrollState.value)
    }

    @Test
    fun cancellingTheLayoutDuringHorizontalRevealRetainsTheRequest() = runBlocking {
        val request = EditWindowScrollRestoration(SemanticViewportAnchor(0, 6, 0))
        val draft = viewportTestDraft(request)
        val layout = launch(start = CoroutineStart.UNDISPATCHED) {
            draft.restoreScrollPosition(request, 100) { awaitCancellation() }
        }
        assertEquals(100, draft.scrollState.value)
        layout.cancelAndJoin()
        assertSame(request, draft.scrollRestoration)
    }

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

    @Test
    fun restoresSourcePositionWhenTypographyChangesWithoutWidthChange() {
        val draft = viewportTestDraft()
        val original = TextAppearance()
        val changed = original.change(AppearanceOption.Size, 28f)
        draft.restoreViewportAfterWidthChange(ORIGINAL_WIDTH_PIXELS, original)
        val anchor = SemanticViewportAnchor(0, 6, -3)
        draft.retainViewportAnchor(anchor)
        draft.restoreViewportAfterWidthChange(ORIGINAL_WIDTH_PIXELS, changed)
        assertEquals(anchor, draft.scrollRestoration?.anchor)
        draft.consumeScrollRestoration(requireNotNull(draft.scrollRestoration))
        draft.restoreViewportAfterWidthChange(ORIGINAL_WIDTH_PIXELS, changed)
        assertNull(draft.scrollRestoration)
    }

    @Test
    fun consecutiveTypographyChangesRetainTheFirstAnchorAndReplaceOnlyThatRequest() {
        val draft = viewportTestDraft()
        val anchor = SemanticViewportAnchor(0, 6, -3)
        draft.restoreViewportAfterWidthChange(400, TextAppearance())
        draft.retainViewportAnchor(anchor)
        draft.restoreViewportAfterWidthChange(400, TextAppearance().change(AppearanceOption.Size, 24f))
        val first = requireNotNull(draft.scrollRestoration)
        draft.retainViewportAnchor(SemanticViewportAnchor(0, 0, 0))
        draft.restoreViewportAfterWidthChange(400, TextAppearance().change(AppearanceOption.Size, 28f))
        assertFalse(draft.consumeScrollRestoration(first))
        assertEquals(anchor, draft.scrollRestoration?.anchor)
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
