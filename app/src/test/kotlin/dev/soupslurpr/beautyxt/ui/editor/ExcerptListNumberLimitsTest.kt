package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.markdown.MarkdownBlockKind
import dev.soupslurpr.beautyxt.markdown.MarkdownPreviewDocument
import dev.soupslurpr.beautyxt.markdown.MarkdownRenderBlock
import org.junit.Assert.assertThrows
import org.junit.Test

class ExcerptListNumberLimitsTest {
    private fun item(number: Long, first: Boolean, depth: Int = 1) = MarkdownRenderBlock(
        MarkdownBlockKind.ListItem, false, false, false, false, false, false,
        0, 0, depth, number, "item", "", emptyList()
    ).copy(isOrderedListItem = true, startsList = first)

    private fun document(vararg blocks: MarkdownRenderBlock) =
        MarkdownPreviewDocument(64, blocks.toList(), 0, false)

    @Test fun supportsEveryAdmittedStartingBoundary() {
        for (start in listOf(0L, 1L, 999_999_999L))
            requireSupportedExcerptListStarts(document(item(start, true)))
    }

    @Test fun derivedLaterNumbersMayCrossTheStartingMarkerBoundary() {
        requireSupportedExcerptListStarts(document(
            item(999_999_999, true), item(1_000_000_000, false)
        ))
    }

    @Test fun rejectsAClippedGroupWhoseFirstRetainedNumberHasTenDigits() {
        assertThrows(UnsupportedExcerptListStartException::class.java) {
            requireSupportedExcerptListStarts(document(item(1_000_000_000, true)))
        }
    }

    @Test fun checksNestedGroupStartsIndependently() {
        assertThrows(UnsupportedExcerptListStartException::class.java) {
            requireSupportedExcerptListStarts(document(item(1, true), item(1_000_000_000, true, 2)))
        }
    }
}
