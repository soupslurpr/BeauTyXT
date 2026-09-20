package dev.soupslurpr.beautyxt.printing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownPrintPaginationTest {
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
