package dev.soupslurpr.beautyxt.document

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import dev.soupslurpr.beautyxt.illustration.IllustrationKind
import dev.soupslurpr.beautyxt.illustration.IllustrationPath
import dev.soupslurpr.beautyxt.illustration.IllustrationResult
import dev.soupslurpr.beautyxt.illustration.NativeIllustration
import dev.soupslurpr.beautyxt.markdown.MarkdownBlockKind
import dev.soupslurpr.beautyxt.markdown.MarkdownInlineSpan
import dev.soupslurpr.beautyxt.markdown.MarkdownPreviewDocument
import dev.soupslurpr.beautyxt.markdown.MarkdownRenderBlock
import dev.soupslurpr.beautyxt.printing.PrintMargins
import dev.soupslurpr.beautyxt.printing.defaultPrintSettings
import dev.soupslurpr.beautyxt.printing.printRasterLayout
import dev.soupslurpr.beautyxt.printing.renderMarkdownDocumentPdf
import java.io.File
import kotlinx.coroutines.runBlocking

/** Checks actual PDF page membership, not just the arithmetic used to request a page break. */
internal fun verifyMarkdownPrintPagination(context: Context) = runBlocking {
    val artifacts =
        File(checkNotNull(context.getExternalFilesDir(null)), "markdown-pagination-verification")
    check(artifacts.mkdirs() || artifacts.isDirectory)
    var case = 0

    suspend fun render(
        blocks: List<MarkdownRenderBlock>,
        fontSize: Int = 12,
        paperHeightMils: Int = 5_000,
        selectedPage: Int? = null
    ): List<String> {
        val attributes = PrintAttributes.Builder()
            .setMediaSize(
                PrintAttributes.MediaSize("pagination", "Pagination", 4_000, paperHeightMils)
            )
            .setResolution(PrintAttributes.Resolution("pagination", "Pagination", 144, 144))
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()
        val settings = defaultPrintSettings(true).copy(
            margins = PrintMargins(0, 0, 0, 0),
            showFileName = false,
            showPageNumbers = false,
            fontSizePoints = fontSize
        )
        val file = File(artifacts, "pagination-${case++}.pdf")
        val result = file.outputStream().use { output ->
            renderMarkdownDocumentPdf(
                output,
                MarkdownPreviewDocument(
                    blocks.sumOf { it.text.toByteArray().size.toLong() },
                    blocks,
                    blocks.sumOf { it.spans.size },
                    false
                ),
                "Pagination.md",
                printRasterLayout(attributes, settings),
                settings,
                arrayOf(selectedPage?.let { PageRange(it, it) } ?: PageRange.ALL_PAGES),
                context.resources
            )
        }
        check(result.totalPageCount in 1..20) { "pagination did not make bounded progress" }
        return PdfRenderer(
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        ).use { pdf ->
            if (selectedPage == null) check(pdf.pageCount == result.totalPageCount)
            List(pdf.pageCount) { index ->
                pdf.openPage(index).use { page ->
                    page.textContents.joinToString("\n") { it.text }.also {
                        check(it.isNotBlank()) { "pagination inserted an empty page: $file/$index" }
                    }
                }
            }
        }
    }

    for (font in listOf(12, 20)) {
        for (intro in listOf(false, true)) {
            for (fillerLines in listOf(8, 14, 18)) {
                val blocks = buildList {
                    add(paginationBlock((1..fillerLines).joinToString("\n") { "Filler $it" }))
                    add(paginationBlock("Diagram heading", MarkdownBlockKind.Heading))
                    if (intro) add(paginationBlock("A short introduction."))
                    add(paginationDiagram())
                    add(paginationBlock("After the diagram."))
                }
                val pages = render(blocks, font)
                val diagramPage = uniquePaginationPage(pages, "diagramcontent")
                check(uniquePaginationPage(pages, "mermaid") == diagramPage) {
                    "language label separated from diagram: font=$font intro=$intro filler=$fillerLines"
                }
                check(uniquePaginationPage(pages, "Diagram heading") == diagramPage) {
                    "heading separated from its fitting illustration group: $font/$intro/$fillerLines"
                }
                if (intro) {
                    check(
                        uniquePaginationPage(pages, "A short introduction.") == diagramPage
                    )
                }
                check(uniquePaginationPage(pages, "After the diagram.") >= diagramPage)
                if (font == 12 && intro && fillerLines == 18) {
                    val selected = render(blocks, font, selectedPage = diagramPage)
                    check(selected.size == 1 && selected.single() == pages[diagramPage]) {
                        "selected-page rendering changed pagination or exposed another page"
                    }
                }
            }
        }
    }

    // A full-page illustration must still keep its own label, even when its section cannot fit.
    for (font in listOf(12, 36)) {
        val pages = render(
            listOf(
                paginationBlock("Filler\nFiller\nFiller"),
                paginationBlock("Oversized illustration", MarkdownBlockKind.Heading),
                paginationBlock("This diagram needs its own page."),
                paginationDiagram(
                    height = 400f,
                    width = 100f,
                    // A tall diagram has multiline source, not one word stretched over a page.
                    source = "diagramcontent\n" + (1..30).joinToString("\n") { "row$it" }
                ),
                paginationBlock("The end.")
            ),
            font,
            paperHeightMils = 3_000
        )
        check(
            uniquePaginationPage(pages, "mermaid") == uniquePaginationPage(pages, "diagramcontent")
        ) {
            "oversized drawing lost its label at font size $font"
        }
        uniquePaginationPage(pages, "The end.")
    }

    // An ordinary multiline fence is not made atomic; only its label and first line stay together.
    val source = (1..80).joinToString("\n") { "code${it.toString().padStart(3, '0')}" }
    val pages = render(
        listOf(
            paginationBlock((1..22).joinToString("\n") { "Filler $it" }),
            paginationBlock(source, MarkdownBlockKind.Code).copy(metadata = "kotlin")
        )
    )
    check(pages.size > 2)
    check(uniquePaginationPage(pages, "kotlin") == uniquePaginationPage(pages, "code001"))
    for (line in source.lines()) uniquePaginationPage(pages, line)

    // A small complete sample keeps its last line too, rather than orphaning a closing brace.
    val compact = render(
        listOf(
            paginationBlock((1..18).joinToString("\n") { "Filler $it" }),
            paginationBlock("Small example", MarkdownBlockKind.Heading),
            paginationBlock("A short program."),
            paginationBlock("firstcode\nsecondcode\nthirdcode\nlastcode", MarkdownBlockKind.Code)
                .copy(metadata = "kotlin")
        )
    )
    val firstCodePage = uniquePaginationPage(compact, "firstcode")
    for (term in listOf("Small example", "A short program.", "kotlin", "lastcode")) {
        check(uniquePaginationPage(compact, term) == firstCodePage) { "short code sample split" }
    }
}

private fun uniquePaginationPage(pages: List<String>, text: String): Int {
    // PDF readers may add whitespace between the source alternative's fitted glyph positions.
    val needle = text.filterNot(Char::isWhitespace)
    val matches = pages.indices.filter { needle in pages[it].filterNot(Char::isWhitespace) }
    check(matches.size == 1) { "expected one page containing '$text', found $matches: $pages" }
    return matches.single()
}

private fun paginationDiagram(
    height: Float = 4f,
    width: Float = 8f,
    source: String = "diagramcontent"
): MarkdownRenderBlock {
    val drawing = NativeIllustration(
        width,
        height,
        0f,
        listOf(
            IllustrationPath(
                2,
                false,
                floatArrayOf(0f, 0f, 0f, 1f, width, 0f, 1f, width, height, 1f, 0f, height, 4f)
            )
        ),
        116
    )
    val block = paginationBlock(source, MarkdownBlockKind.Code)
    return block.copy(
        metadata = "mermaid",
        spans = listOf(
            MarkdownInlineSpan(
                0,
                block.text.length,
                0,
                null,
                illustration = IllustrationResult.Rendered(drawing, IllustrationKind.Diagram)
            )
        )
    )
}

private fun paginationBlock(text: String, kind: MarkdownBlockKind = MarkdownBlockKind.Paragraph) =
    MarkdownRenderBlock(
        kind = kind,
        continuesPrevious = false,
        isOrderedListItem = false,
        isTaskChecked = false,
        isTaskUnchecked = false,
        isTableHeader = false,
        containsRawHtml = false,
        headingLevel = if (kind == MarkdownBlockKind.Heading) 2 else 0,
        quoteDepth = 0,
        listDepth = 0,
        listNumber = 0L,
        text = text,
        metadata = "",
        spans = emptyList()
    )
