package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.ui.unit.dp
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
    fun rejectsInvalidLayoutInputs() {
        assertThrows(IllegalArgumentException::class.java) {
            usesCompactFindLayout((-1).dp, 1f)
        }
        for (fontScale in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                usesCompactFindLayout(360.dp, fontScale)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            usesEditorActionOverflow((-1).dp, offersSave = true)
        }
    }
}
