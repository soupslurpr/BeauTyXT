package dev.soupslurpr.beautyxt.printing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownPrintPaginationTest {
    @Test
    fun keepsADefinitionLabelWithTheFirstContentAndFittingFollowingRow() {
        val group = markdownPrintDefinitionGroupHeight(12, 30, 100, 20)
        assertEquals(62, group)
        assertTrue(shouldAdvanceMarkdownPrintGroup(0, 100, 50, group))
        assertFalse(shouldAdvanceMarkdownPrintGroup(0, 100, 38, group))
    }

    @Test
    fun yieldsFollowingContentBeforeSeparatingTheDefinitionLabel() {
        val group = markdownPrintDefinitionGroupHeight(12, 30, 50, 20)
        assertEquals(42, group)
        assertTrue(shouldAdvanceMarkdownPrintGroup(0, 50, 20, group))
        assertFalse(shouldAdvanceMarkdownPrintGroup(0, 50, 5, group))
    }

    @Test
    fun oversizedDefinitionContentStillMakesProgress() {
        val group = markdownPrintDefinitionGroupHeight(12, 100, 100, 20)
        assertEquals(112, group)
        assertFalse(shouldAdvanceMarkdownPrintGroup(0, 100, 80, group))
        assertFalse(shouldAdvanceMarkdownPrintGroup(0, 100, 0, group))
    }

    @Test
    fun movesOnlyWhenTheGroupDoesNotFitTheRemainingSpace() {
        assertTrue(shouldAdvanceMarkdownPrintGroup(10, 110, 81, 30))
        assertFalse(shouldAdvanceMarkdownPrintGroup(10, 110, 80, 30))
        assertFalse(shouldAdvanceMarkdownPrintGroup(10, 110, 79, 30))
    }

    @Test
    fun includesSpacingOnTheCurrentPageButNotOnTheFreshPage() {
        assertTrue(shouldAdvanceMarkdownPrintGroup(10, 110, 80, 30, 5))
        assertFalse(shouldAdvanceMarkdownPrintGroup(10, 110, 75, 30, 5))
        assertTrue(shouldAdvanceMarkdownPrintGroup(10, 110, 20, 100, 5))
    }

    @Test
    fun neverAddsABlankPageForAnOversizedGroup() {
        assertFalse(shouldAdvanceMarkdownPrintGroup(10, 110, 80, 101))
        assertFalse(shouldAdvanceMarkdownPrintGroup(10, 110, 10, 101))
        assertFalse(shouldAdvanceMarkdownPrintGroup(10, 110, 10, 100, 5))
    }

    @Test
    fun advancesFromAFullPageWithoutIntegerOverflow() {
        assertTrue(shouldAdvanceMarkdownPrintGroup(0, Int.MAX_VALUE, 1, Int.MAX_VALUE, 1))
        assertTrue(shouldAdvanceMarkdownPrintGroup(10, 110, 110, 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAnInvalidPagePosition() {
        shouldAdvanceMarkdownPrintGroup(10, 110, 111, 1)
    }
}
