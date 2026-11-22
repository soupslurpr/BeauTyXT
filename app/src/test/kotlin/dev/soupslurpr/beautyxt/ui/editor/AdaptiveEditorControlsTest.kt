package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the width and text-scale boundaries for compact editor controls. */
class AdaptiveEditorControlsTest {
    @Test
    fun reflowsFindBeforeCrowdingTheQuery() {
        assertTrue(usesCompactFindLayout(256.dp, 1f))
        assertTrue(usesCompactFindLayout(359.dp, 1f))
        assertFalse(usesCompactFindLayout(360.dp, 1f))
        assertFalse(usesCompactFindLayout(411.dp, 1f))
    }

    @Test
    fun accountsForLargeTextWithoutShrinkingTouchTargets() {
        assertTrue(usesCompactFindLayout(411.dp, 2f))
        assertTrue(usesCompactFindLayout(719.dp, 2f))
        assertFalse(usesCompactFindLayout(720.dp, 2f))
        assertTrue(usesCompactFindLayout(359.dp, 0.8f))
    }

    @Test
    fun preservesFullWidthToolbarActionsAtTheExactBoundary() {
        assertTrue(usesEditorActionOverflow(252.dp, offersSave = true))
        assertFalse(usesEditorActionOverflow(253.dp, offersSave = true))
        assertTrue(usesEditorActionOverflow(204.dp, offersSave = false))
        assertFalse(usesEditorActionOverflow(205.dp, offersSave = false))
    }

    @Test
    fun countsTheSaveActionOnlyWhenItIsOffered() {
        assertTrue(usesEditorActionOverflow(224.dp, offersSave = true))
        assertFalse(usesEditorActionOverflow(224.dp, offersSave = false))
    }

    @Test
    fun selectsToolbarHeightTiersAtExactBoundaries() {
        assertEquals(EditorToolbarHeightMode.Padded, editorToolbarHeightMode(208.dp))
        assertEquals(EditorToolbarHeightMode.Flush, editorToolbarHeightMode(207.dp))
        assertEquals(EditorToolbarHeightMode.Flush, editorToolbarHeightMode(192.dp))
        assertEquals(EditorToolbarHeightMode.Compact, editorToolbarHeightMode(191.dp))
    }

    @Test
    fun overflowsDocumentToolbarProgressivelyAcrossCapabilities() {
        val fullEditable = editorActionBarPlan(253.dp, showsHistory = true, offersSave = true)
        assertTrue(fullEditable.showUndo && fullEditable.showRedo && fullEditable.showMode && fullEditable.showSave && fullEditable.showShare)
        assertFalse(fullEditable.showOverflow)

        val compactEditable = editorActionBarPlan(224.dp, showsHistory = true, offersSave = true)
        assertTrue(compactEditable.showUndo && !compactEditable.showRedo && compactEditable.showMode && compactEditable.showSave && !compactEditable.showShare && compactEditable.showOverflow)

        val narrowEditable = editorActionBarPlan(148.dp, showsHistory = true, offersSave = true)
        assertFalse(narrowEditable.showUndo)
        assertTrue(narrowEditable.showMode && narrowEditable.showSave && narrowEditable.showOverflow)

        val ultraNarrowEditable = editorActionBarPlan(100.dp, showsHistory = true, offersSave = true)
        assertTrue(ultraNarrowEditable.showMode && !ultraNarrowEditable.showSave && ultraNarrowEditable.showOverflow)

        val menuOnly = editorActionBarPlan(99.dp, showsHistory = true, offersSave = true)
        assertFalse(menuOnly.showMode)
        assertTrue(menuOnly.showOverflow)

        val readOnlyFull = editorActionBarPlan(148.dp, showsHistory = false, offersSave = true)
        assertFalse(readOnlyFull.showUndo || readOnlyFull.showRedo || readOnlyFull.showOverflow)
        assertTrue(readOnlyFull.showMode && readOnlyFull.showSave && readOnlyFull.showShare)

        val readOnlyNarrow = editorActionBarPlan(147.dp, showsHistory = false, offersSave = true)
        assertTrue(readOnlyNarrow.showMode && !readOnlyNarrow.showSave && !readOnlyNarrow.showShare && readOnlyNarrow.showOverflow)
    }

    @Test
    fun overflowsSelectionToolbarAtExactBoundaries() {
        assertEquals(
            SelectionActionBarPlan(showCopy = true, showSend = true, showClear = true),
            selectionActionBarPlan(196.dp)
        )
        assertEquals(
            SelectionActionBarPlan(showCopy = true, showSend = false, showClear = true),
            selectionActionBarPlan(195.dp)
        )
        assertEquals(
            SelectionActionBarPlan(showCopy = true, showSend = false, showClear = true),
            selectionActionBarPlan(148.dp)
        )
        assertEquals(
            SelectionActionBarPlan(showCopy = true, showSend = false, showClear = false),
            selectionActionBarPlan(147.dp)
        )
        assertEquals(
            SelectionActionBarPlan(showCopy = true, showSend = false, showClear = false),
            selectionActionBarPlan(100.dp)
        )
        assertEquals(
            SelectionActionBarPlan(showCopy = false, showSend = false, showClear = false),
            selectionActionBarPlan(99.dp)
        )
    }

    @Test
    fun movesCompactMarkdownContentsIntoOverflowWhenTitleLacksRoom() {
        assertFalse(usesInlineEditorContentsAction(256.dp, fontScale = 1f, availableHeight = 600.dp))
        assertFalse(usesInlineEditorContentsAction(311.dp, fontScale = 1f, availableHeight = 600.dp))
        assertTrue(usesInlineEditorContentsAction(312.dp, fontScale = 1f, availableHeight = 600.dp))
        assertTrue(usesInlineEditorContentsAction(256.dp, fontScale = 2f, availableHeight = 480.dp))
        assertFalse(usesInlineEditorContentsAction(256.dp, fontScale = 2f, availableHeight = 479.dp))
    }

    @Test
    fun stacksPrintMarginFieldsForNarrowAndLargeTextLayouts() {
        assertTrue(usesStackedPrintMarginFields(299.dp, 1f))
        assertFalse(usesStackedPrintMarginFields(300.dp, 1f))
        assertTrue(usesStackedPrintMarginFields(599.dp, 2f))
        assertFalse(usesStackedPrintMarginFields(600.dp, 2f))
        assertTrue(usesStackedPrintMarginFields(299.dp, 0.85f))
    }

    @Test
    fun keepsInlineLandscapeFindFieldsAtMinimumWidthBeforeTrailingActions() {
        // 700 dp at 1x keeps Replace beside Query by dropping Options and Done into overflow.
        assertEquals(
            InlineFindLayoutPlan(
                showReplacementBesideQuery = true,
                showOptionsIcon = false,
                showStatusControls = true,
                showDoneButton = false
            ),
            inlineFindLayoutPlan(700.dp, fontScale = 1f, replaceVisible = true, hasFailure = false)
        )
        // 420 dp with Replace stacks Replace and overflows status so Query keeps >= 160 dp.
        assertEquals(
            InlineFindLayoutPlan(
                showReplacementBesideQuery = false,
                showOptionsIcon = false,
                showStatusControls = false,
                showDoneButton = false
            ),
            inlineFindLayoutPlan(420.dp, fontScale = 1f, replaceVisible = true, hasFailure = false)
        )
        // 650 dp without Replace keeps full controls inline.
        assertEquals(
            InlineFindLayoutPlan(
                showReplacementBesideQuery = false,
                showOptionsIcon = true,
                showStatusControls = true,
                showDoneButton = true
            ),
            inlineFindLayoutPlan(650.dp, fontScale = 1f, replaceVisible = false, hasFailure = false)
        )
        // 440 dp with a search failure moves status below the row rather than crowding Query.
        assertEquals(
            InlineFindLayoutPlan(
                showReplacementBesideQuery = false,
                showOptionsIcon = false,
                showStatusControls = false,
                showDoneButton = false
            ),
            inlineFindLayoutPlan(440.dp, fontScale = 1f, replaceVisible = false, hasFailure = true)
        )
    }

    @Test
    fun rejectsInvalidLayoutInputs() {
        assertThrows(IllegalArgumentException::class.java) {
            usesCompactFindLayout((-1).dp, 1f)
        }
        for (fontScale in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                usesCompactFindLayout(360.dp, fontScale)
            }
            assertThrows(IllegalArgumentException::class.java) {
                usesStackedPrintMarginFields(360.dp, fontScale)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            usesEditorActionOverflow((-1).dp, offersSave = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            editorActionBarPlan((-1).dp, showsHistory = true, offersSave = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            selectionActionBarPlan((-1).dp)
        }
        assertThrows(IllegalArgumentException::class.java) {
            editorToolbarHeightMode((-1).dp)
        }
        assertThrows(IllegalArgumentException::class.java) {
            usesInlineEditorContentsAction((-1).dp, fontScale = 1f, availableHeight = 480.dp)
        }
        assertThrows(IllegalArgumentException::class.java) {
            usesStackedPrintMarginFields((-1).dp, 1f)
        }
    }
}
