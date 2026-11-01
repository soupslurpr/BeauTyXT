/* Checks the small retained zero-width heading-destination lead against actual PDFs. */
package dev.soupslurpr.beautyxt.document

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import dev.soupslurpr.beautyxt.markdown.MarkdownBlockKind
import dev.soupslurpr.beautyxt.markdown.markdownFootnoteLabel
import dev.soupslurpr.beautyxt.markdown.client.IsolatedMarkdownRenderer
import dev.soupslurpr.beautyxt.printing.defaultPrintSettings
import dev.soupslurpr.beautyxt.printing.printRasterLayout
import dev.soupslurpr.beautyxt.printing.renderMarkdownDocumentPdf
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.runBlocking

private data class PrintDestinationFixture(
    val name: String,
    val source: String,
    val expectedContent: List<String>,
    val definitionKind: MarkdownBlockKind? = null,
    val expectedLinks: Int = 1
)

/** Checks visible definition numbers, rule/table appearance, and independently named destinations. */
internal fun verifyPrintHeadingDestinations(context: Context) = runBlocking {
    val directory = File(checkNotNull(context.getExternalFilesDir(null)), "print-text-verification")
    check(directory.mkdirs() || directory.isDirectory)
    val settings = defaultPrintSettings(true).copy(showFileName = false, showPageNumbers = false)
    val attributes = PrintAttributes.Builder()
        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
        .setResolution(PrintAttributes.Resolution("headings", "Headings", 144, 144))
        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
        .build()
    val cases = listOf(
        PrintDestinationFixture("ordinary", "# Title\n\n[Go to title](#title)\n", listOf("Title")),
        PrintDestinationFixture("plain-rule", "Reference\n\n---\n", listOf("Reference"),
            expectedLinks = 0),
        PrintDestinationFixture("note-prose", "Reference[^n]\n\n[^n]: Note content\n",
            listOf("Note content"), MarkdownBlockKind.Footnote),
        PrintDestinationFixture("note-list", "Reference[^n]\n\n[^n]: - Note content\n",
            listOf("Note content"), MarkdownBlockKind.ListItem),
        PrintDestinationFixture("note-code",
            "Reference[^n]\n\n[^n]:\n\n    ```kotlin\n    Note content\n    ```\n",
            listOf("Note content"), MarkdownBlockKind.Code),
        PrintDestinationFixture("note-heading",
            "Reference[^n] and [heading](#note-content)\n\n[^n]: # Note content\n",
            listOf("Note content"), MarkdownBlockKind.Heading, expectedLinks = 2),
        PrintDestinationFixture("note-rule", "Reference[^n]\n\n[^n]:\n\n    ---\n",
            listOf("Reference"), MarkdownBlockKind.Rule),
        PrintDestinationFixture("note-table",
            "Reference[^n]\n\n[^n]:\n\n    | HeadLeft | HeadRight |\n    | --- | --- |\n    | CellLeft | CellRight |\n",
            listOf("HeadLeft", "HeadRight", "CellLeft", "CellRight"), MarkdownBlockKind.TableRow),
        PrintDestinationFixture("note-table-empty-first",
            "Reference[^n]\n\n[^n]:\n\n    | | HeadRight |\n    | --- | --- |\n    | | CellRight |\n",
            listOf("HeadRight", "CellRight"), MarkdownBlockKind.TableRow),
        PrintDestinationFixture("note-table-empty",
            "Reference[^n]\n\n[^n]:\n\n    | | |\n    | --- | --- |\n    | | |\n",
            listOf("Reference"), MarkdownBlockKind.TableRow),
        PrintDestinationFixture("zero-width", "# \u200bTitle\n\n[Go to title](#title)\n", listOf("Title"))
    )
    var ordinaryRuleWidth: Int? = null
    for (fixture in cases) {
        val name = fixture.name
        val model = RustDocument.createEmpty().use { document ->
            val metrics = document.replace(0, Utf16Range(0, 0), fixture.source)
            document.captureSnapshot(metrics.revision).use { snapshot ->
                IsolatedMarkdownRenderer(context).render(snapshot, metrics.serializedByteLength)
            }
        }
        fixture.definitionKind?.let { kind ->
            check(model.blocks.any { it.kind == kind && it.markdownFootnoteLabel() == "n" }) {
                "$name fixture did not retain its actual nontext definition kind"
            }
        }
        val file = File(directory, "heading-$name.pdf")
        file.outputStream().use { output ->
            renderMarkdownDocumentPdf(output, model, "Heading destinations",
                printRasterLayout(attributes, settings), settings, arrayOf(PageRange.ALL_PAGES),
                context.resources)
        }
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
            PdfRenderer(input).use { renderer ->
                check(renderer.pageCount == 1)
                renderer.openPage(0).use { page ->
                    val text = page.textContents.joinToString("\n") { it.text }
                    fixture.expectedContent.forEach { expected ->
                        check(expected in text) { "$name PDF lost content: $expected" }
                    }
                    if (fixture.definitionKind != null) {
                        check("1." in text.filterNot { it.isWhitespace() }) {
                            "$name PDF omitted its visible definition number"
                        }
                    }
                    check(page.gotoLinks.size == fixture.expectedLinks) {
                        "$name PDF lost its internal destination"
                    }
                    check(page.gotoLinks.all {
                        it.destination.pageNumber == 0 && it.destination.xCoordinate.isFinite() &&
                            it.destination.yCoordinate.isFinite()
                    }) { "$name PDF has an invalid internal destination" }
                    if (fixture.expectedLinks == 2) {
                        check(page.gotoLinks.map {
                            it.destination.xCoordinate to it.destination.yCoordinate
                        }.distinct().size == 2) { "Heading and note destinations were merged" }
                    }
                    if (name == "plain-rule") ordinaryRuleWidth = printedRuleWidth(page)
                    if (name == "note-rule") {
                        check(abs(printedRuleWidth(page) - checkNotNull(ordinaryRuleWidth)) <= 2) {
                            "Numbered definition changed the rule's visible span"
                        }
                    }
                }
            }
        }
    }
}

/** Compares the long divider's appearance independently from its selectable label text. */
private fun printedRuleWidth(page: PdfRenderer.Page): Int {
    val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
    try {
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
        val row = IntArray(bitmap.width)
        var longest = 0
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            var run = 0
            for (pixel in row) {
                if (minOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) < 245) {
                    run++
                    longest = maxOf(longest, run)
                } else run = 0
            }
        }
        check(longest > bitmap.width * 3 / 4) { "PDF lost its horizontal rule" }
        return longest
    } finally {
        bitmap.recycle()
    }
}
