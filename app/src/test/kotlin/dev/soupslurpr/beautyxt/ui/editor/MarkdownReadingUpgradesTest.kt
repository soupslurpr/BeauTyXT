package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.markdown.MarkdownBlockKind
import dev.soupslurpr.beautyxt.markdown.MarkdownRenderBlock
import dev.soupslurpr.beautyxt.markdown.MarkdownSourceRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownReadingUpgradesTest {

    private fun block(
        kind: MarkdownBlockKind,
        text: String,
        metadata: String = "",
        headingLevel: Int = 0,
        startsFrontmatter: Boolean = false,
        detailsId: Int = 0,
        detailsParentId: Int = 0,
        detailsInitiallyOpen: Boolean = false,
        sourceStart: Long = 0L
    ): MarkdownRenderBlock = MarkdownRenderBlock(
        kind = kind,
        continuesPrevious = false,
        isOrderedListItem = false,
        isTaskChecked = false,
        isTaskUnchecked = false,
        isTableHeader = false,
        containsRawHtml = false,
        headingLevel = headingLevel,
        quoteDepth = 0,
        listDepth = 0,
        listNumber = 0L,
        text = text,
        metadata = metadata,
        spans = emptyList(),
        source = MarkdownSourceRange(sourceStart, sourceStart + text.length),
        startsFrontmatter = startsFrontmatter,
        detailsId = detailsId,
        detailsParentId = detailsParentId,
        detailsInitiallyOpen = detailsInitiallyOpen
    )

    @Test
    fun groupsFrontmatterAndFiltersNestedDetails() {
        val blocks = listOf(
            block(MarkdownBlockKind.Frontmatter, "Hello", metadata = "title", startsFrontmatter = true),
            block(MarkdownBlockKind.Frontmatter, "2026-11-24", metadata = "date"),
            block(MarkdownBlockKind.DetailsSummary, "Outer", detailsId = 1, detailsParentId = 0, detailsInitiallyOpen = false),
            block(MarkdownBlockKind.Paragraph, "Outer body", detailsId = 1, detailsParentId = 0),
            block(MarkdownBlockKind.DetailsSummary, "Inner", detailsId = 2, detailsParentId = 1, detailsInitiallyOpen = false),
            block(MarkdownBlockKind.Paragraph, "Inner body", detailsId = 2, detailsParentId = 1),
            block(MarkdownBlockKind.Paragraph, "After")
        )
        val layout = prepareMarkdownPreview(blocks)
        // Frontmatter grouped into 1 item; 5 remaining single-block items.
        assertEquals(6, layout.items.size)
        assertTrue(layout.items[0].isFrontmatter)
        assertEquals(2, layout.items[0].blocks.size)

        // Default: both collapsed -> frontmatter, Outer summary, After visible.
        val defaultVisible = visibleMarkdownPreviewItems(layout, emptyMap())
        assertEquals(3, defaultVisible.size)
        assertEquals("Outer", defaultVisible[1].blocks.single().text)
        assertEquals("After", defaultVisible[2].blocks.single().text)

        // Required keys to reveal Inner body (blockIndex 5) includes both Inner (2) and Outer (1).
        val keysToRevealInner = requiredExpandedDetailsKeysForBlock(layout, blocks, 5)
        assertEquals(2, keysToRevealInner.size)
        val overrides = keysToRevealInner.associateWith { true }
        val expandedVisible = visibleMarkdownPreviewItems(layout, overrides)
        assertEquals(6, expandedVisible.size)
    }

    @Test
    fun filterDocumentOutlinePreservesAncestorsAndMatchesCaseInsensitively() {
        val entries = listOf(
            DocumentOutlineEntry(0, 1, "Architecture", 0L, 0),
            DocumentOutlineEntry(1, 2, "Storage", 20L, 1),
            DocumentOutlineEntry(2, 3, "PieceTree", 40L, 2),
            DocumentOutlineEntry(3, 2, "Networking", 60L, 3),
            DocumentOutlineEntry(4, 1, "Testing", 80L, 4)
        )
        val filtered = filterDocumentOutline(entries, "piecetree")
        assertEquals(3, filtered.size)
        assertEquals("Architecture", filtered[0].entry.title)
        assertTrue(filtered[0].isContextOnly)
        assertEquals("Storage", filtered[1].entry.title)
        assertTrue(filtered[1].isContextOnly)
        assertEquals("PieceTree", filtered[2].entry.title)
        assertFalse(filtered[2].isContextOnly)
    }

    @Test
    fun appearanceNewDiscreteOptionsDoNotReflowWhenVisualOnly() {
        val base = TextAppearance()
        assertEquals(1f, base.value(AppearanceOption.LineNumbers, AppearanceGroup.Source))
        assertEquals(1f, base.value(AppearanceOption.SyntaxHighlighting, AppearanceGroup.Markdown))
        assertEquals(0f, base.value(AppearanceOption.KeepScreenOn, AppearanceGroup.Markdown))

        val toggled = base
            .change(AppearanceOption.SyntaxHighlighting, 0f)
            .change(AppearanceOption.KeepScreenOn, 1f)
        assertEquals(TextAppearance(), toggled.effective(AppearanceGroup.Markdown))
        assertEquals(TextAppearance(), toggled.effective(AppearanceGroup.Source))
    }

    @Test
    fun previewKeysOmitAuthoredMetadataAndKeepDetailsSummaryOutsideAlertGroups() {
        val secretMeta = "secret-fence-info-xyz"
        val blocks = listOf(
            block(MarkdownBlockKind.Code, "val x = 1", metadata = secretMeta),
            block(
                MarkdownBlockKind.DetailsSummary,
                "Alert details",
                detailsId = 1,
                detailsInitiallyOpen = true
            ).copy(quoteKind = dev.soupslurpr.beautyxt.markdown.MarkdownQuoteKind.Note, quoteDepth = 1),
            block(
                MarkdownBlockKind.Paragraph,
                "Alert details body",
                detailsId = 1
            ).copy(quoteKind = dev.soupslurpr.beautyxt.markdown.MarkdownQuoteKind.Note, quoteDepth = 1)
        )
        val layout = prepareMarkdownPreview(blocks)
        assertEquals(3, layout.items.size)
        assertFalse(layout.items[0].stableKey.contains(secretMeta))
        assertEquals(MarkdownBlockKind.DetailsSummary, layout.items[1].blocks.single().kind)
        assertEquals(MarkdownBlockKind.Paragraph, layout.items[2].blocks.single().kind)
    }
}
