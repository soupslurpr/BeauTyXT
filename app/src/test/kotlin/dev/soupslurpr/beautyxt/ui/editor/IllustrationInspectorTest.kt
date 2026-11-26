package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.illustration.IllustrationKind
import dev.soupslurpr.beautyxt.illustration.IllustrationResult
import dev.soupslurpr.beautyxt.illustration.MarkdownIllustrationPlan
import dev.soupslurpr.beautyxt.illustration.NativeIllustration
import dev.soupslurpr.beautyxt.markdown.MARKDOWN_CHECKED_TASK_PREFIX
import dev.soupslurpr.beautyxt.markdown.MARKDOWN_UNCHECKED_TASK_PREFIX
import dev.soupslurpr.beautyxt.markdown.MarkdownBlockKind
import dev.soupslurpr.beautyxt.markdown.MarkdownRenderBlock
import dev.soupslurpr.beautyxt.markdown.MarkdownTableAlignment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IllustrationInspectorTest {

    private fun tableRow(
        text: String,
        isHeader: Boolean = false,
        continuesPrevious: Boolean = false,
        alignments: List<MarkdownTableAlignment> = emptyList()
    ): MarkdownRenderBlock = MarkdownRenderBlock(
        kind = MarkdownBlockKind.TableRow,
        continuesPrevious = continuesPrevious,
        isOrderedListItem = false,
        isTaskChecked = false,
        isTaskUnchecked = false,
        isTableHeader = isHeader,
        containsRawHtml = false,
        headingLevel = 0,
        quoteDepth = 0,
        listDepth = 0,
        listNumber = 0L,
        text = text,
        metadata = "",
        spans = emptyList(),
        tableAlignments = alignments,
        startsTable = isHeader,
        tableCellSeparators = text.indices.filter { text[it] == '\t' }
    )

    @Test
    fun formatsMarkdownTableAndRfc4180CsvCorrectly() {
        val blocks = listOf(
            tableRow(
                text = "Name\tQuote\tValue",
                isHeader = true,
                alignments = listOf(
                    MarkdownTableAlignment.Left,
                    MarkdownTableAlignment.Center,
                    MarkdownTableAlignment.Right
                )
            ),
            tableRow(text = "Alpha, Inc.\tHe said \"hi\"\t42"),
            tableRow(text = "Pipe|Cell\t*literal*\t99")
        )
        val md = formatTableAsMarkdown(blocks)
        assertEquals(
            "| Name | Quote | Value |\n" +
                "| :--- | :---: | ---: |\n" +
                "| Alpha, Inc\\. | He said \"hi\" | 42 |\n" +
                "| Pipe\\|Cell | \\*literal\\* | 99 |",
            md
        )

        val csv = formatTableAsCsv(blocks)
        assertEquals(
            "Name,Quote,Value\r\n" +
                "\"Alpha, Inc.\",\"He said \"\"hi\"\"\",42\r\n" +
                "Pipe|Cell,*literal*,99",
            csv
        )
    }

    @Test
    fun enforcesTableCopyLimit() {
        val hugeCell = "a".repeat(70_000)
        val blocks = listOf(tableRow(text = hugeCell, isHeader = true))
        assertNull(formatTableAsMarkdown(blocks))
        assertNull(formatTableAsCsv(blocks))
    }

    @Test
    fun joinsFragmentedTableRowContinuationsIntoTrailingColumn() {
        val part1 = "a".repeat(3_500)
        val part2 = "b".repeat(2_500)
        val blocks = listOf(
            tableRow(text = "Col1\tCol2", isHeader = true),
            tableRow(text = "first\t$part1", isHeader = false, continuesPrevious = false),
            tableRow(text = part2, isHeader = false, continuesPrevious = true)
        )
        val csv = formatTableAsCsv(blocks)
        assertEquals("Col1,Col2\r\nfirst,$part1$part2", csv)
    }

    @Test
    fun mergesHugeFragmentedCellLinearlyWithinDisplayCap() {
        val chunk = "x".repeat(4_096)
        val blocks = ArrayList<MarkdownRenderBlock>(2_049).apply {
            add(tableRow(text = "H1\tH2", isHeader = true))
            add(tableRow(text = "row\t$chunk", isHeader = false, continuesPrevious = false))
            repeat(2_047) {
                add(tableRow(text = chunk, isHeader = false, continuesPrevious = true))
            }
        }
        val merged = mergeLogicalTableRows(blocks, maxCellDisplayChars = 2_048)!!
        assertEquals(2, merged.size)
        assertEquals("row", merged[1].cells[0])
        assertEquals(2_049, merged[1].cells[1].length)
        assertTrue(merged[1].cells[1].endsWith("…"))

        // Lazy row indexing + visible-window extraction materializes only requested columns.
        val indexed = indexLogicalTableRows(blocks)
        assertEquals(2, indexed.size)
        assertEquals(2, indexed[1].columnCount)
        val onlySecondCol = extractVisibleTableRowCells(blocks, indexed[1], firstCol = 1, lastCol = 1)
        assertEquals(1, onlySecondCol.size)
        assertEquals(2_049, onlySecondCol[0].length)
        assertTrue(onlySecondCol[0].endsWith("…"))

        // High-cell-count table (300k+ empty cells) is rejected before materializing cell strings.
        val wideRowText = "\t".repeat(307) // 308 empty cells per row
        val manyCellBlocks = List(1_000) { idx ->
            tableRow(text = wideRowText, isHeader = idx == 0)
        }
        assertNull(formatTableAsCsv(manyCellBlocks))
        assertNull(formatTableAsMarkdown(manyCellBlocks))
    }

    @Test
    fun illustrationPlanWithUpdatedBlockPreservesToggledTaskState() {
        val task = MarkdownRenderBlock(
            kind = MarkdownBlockKind.ListItem,
            continuesPrevious = false,
            isOrderedListItem = false,
            isTaskChecked = false,
            isTaskUnchecked = true,
            isTableHeader = false,
            containsRawHtml = false,
            headingLevel = 0,
            quoteDepth = 0,
            listDepth = 1,
            listNumber = 0L,
            text = "${MARKDOWN_UNCHECKED_TASK_PREFIX}Ship it",
            metadata = "",
            spans = emptyList()
        )
        val diagram = MarkdownRenderBlock(
            kind = MarkdownBlockKind.Code,
            continuesPrevious = false,
            isOrderedListItem = false,
            isTaskChecked = false,
            isTaskUnchecked = false,
            isTableHeader = false,
            containsRawHtml = false,
            headingLevel = 0,
            quoteDepth = 0,
            listDepth = 0,
            listNumber = 0L,
            text = "flowchart LR\nA-->B",
            metadata = "mermaid",
            spans = emptyList()
        )
        val plan = MarkdownIllustrationPlan(listOf(task, diagram))
        val toggled = task.copy(
            text = "${MARKDOWN_CHECKED_TASK_PREFIX}Ship it",
            isTaskChecked = true,
            isTaskUnchecked = false
        )
        val updatedPlan = plan.withUpdatedBlock(0, toggled)
        val decorated = updatedPlan.decorate(0) { req -> IllustrationResult.Pending(req.kind) }
        assertTrue(decorated.isTaskChecked)
        assertEquals("${MARKDOWN_CHECKED_TASK_PREFIX}Ship it", decorated.text)
    }

    @Test
    fun boundsIllustrationRasterExportGeometryAndSuggestsSafeFilenames() {
        val wide = NativeIllustration(
            width = 200f,
            height = 10f,
            baseline = 0f,
            paths = emptyList(),
            packetBytes = 60
        )
        val geom = illustrationRasterGeometry(wide)
        assertTrue(geom.widthPx <= 2_048)
        assertTrue(geom.heightPx <= 2_048)
        assertTrue(geom.scale > 0f)

        // A valid large drawing (3,000 units wide) must downscale below 1f instead of cropping.
        val huge = NativeIllustration(
            width = 3_000f,
            height = 400f,
            baseline = 0f,
            paths = emptyList(),
            packetBytes = 60
        )
        val hugeGeom = illustrationRasterGeometry(huge)
        assertTrue(hugeGeom.widthPx <= 2_048)
        assertTrue(hugeGeom.scale < 1f)
        assertTrue(huge.width * hugeGeom.scale + hugeGeom.paddingPx * 2 <= hugeGeom.widthPx + 1f)

        // Inspector fit scale also downscales below 1f (including max 8,192-unit drawings in short
        // viewports) so large drawings fit cleanly at min zoom without cropping.
        val fitScale = computeIllustrationFitScale(
            viewportW = 1_000f,
            viewportH = 800f,
            paddingPx = 24f,
            drawingWidth = 3_000f,
            drawingHeight = 400f
        )
        assertTrue(fitScale < 1f)
        assertTrue(3_000f * fitScale <= 1_000f - 48f + 0.01f)

        val shortViewportScale = computeIllustrationFitScale(
            viewportW = 400f,
            viewportH = 60f,
            paddingPx = 24f,
            drawingWidth = 400f,
            drawingHeight = 8_192f
        )
        assertTrue(8_192f * shortViewportScale <= 60f - 48f + 0.01f)

        assertEquals(
            "notes diagram.webp",
            suggestIllustrationImageName("notes.md", IllustrationKind.Diagram, QrImageFormat.WebP)
        )
        assertEquals(
            "BeauTyXT formula.png",
            suggestIllustrationImageName("Untitled", IllustrationKind.Math, QrImageFormat.Png)
        )
    }
}
