/* Verifies printable Unicode, page confidentiality and bounded-fragment layout fidelity. */
package dev.soupslurpr.beautyxt.document

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import dev.soupslurpr.beautyxt.markdown.MARKDOWN_SPAN_STYLE_STRONG
import dev.soupslurpr.beautyxt.markdown.MarkdownBlockKind
import dev.soupslurpr.beautyxt.markdown.MarkdownInlineDestinationKind
import dev.soupslurpr.beautyxt.markdown.MarkdownInlineSpan
import dev.soupslurpr.beautyxt.markdown.MarkdownPreviewDocument
import dev.soupslurpr.beautyxt.markdown.MarkdownTableAlignment
import dev.soupslurpr.beautyxt.markdown.client.IsolatedMarkdownRenderer
import dev.soupslurpr.beautyxt.markdown.markdownFootnoteNumbers
import dev.soupslurpr.beautyxt.markdown.markdownFootnotePresentation
import dev.soupslurpr.beautyxt.printing.MAXIMUM_MARKDOWN_PRINT_BLOCK_UNITS
import dev.soupslurpr.beautyxt.printing.PrintSettings
import dev.soupslurpr.beautyxt.printing.defaultPrintSettings
import dev.soupslurpr.beautyxt.printing.pdfUnicodeHex
import dev.soupslurpr.beautyxt.printing.printRasterLayout
import dev.soupslurpr.beautyxt.printing.renderDocumentTextPdf
import dev.soupslurpr.beautyxt.printing.renderMarkdownDocumentPdf
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.coroutines.runBlocking

private const val TAG = "PrintTextLayerVerification"
private const val TEST_MARGIN_POINTS = 36
private const val TEST_BOUNDS_TOLERANCE_POINTS = 1
private const val TEST_PAGE_WIDTH_POINTS = 595
private const val TEST_PAGE_HEIGHT_POINTS = 842
private const val TEST_FONT_SIZE_POINTS = 12f
private const val TEST_RESOLUTION_DPI = 216
private const val FIRST_CJK_IDEOGRAPH = 0x4e00
private const val TEST_PRINT_FRAGMENT_UTF16_UNITS = 127
private const val TEST_LONG_PRINT_LINE_REPETITIONS = 2_000
private const val TEST_UNBREAKABLE_PRINT_CLUSTER_UNITS = 20_000
private const val TEST_LONG_SPACE_RUN_UNITS = 20_000
private const val TEST_MARKDOWN_LAYOUT_UNITS = 4 * 1_024
private const val PRINT_TEXT_ARTIFACTS = "print-text-verification"
private const val PRINT_FRAGMENT_ARTIFACTS = "print-fragment-verification"
private const val MARKDOWN_PRINT_FRAGMENT_ARTIFACTS = "markdown-print-fragment-verification"

/** Removes only named print-test artifacts without following symbolic links. */
internal fun clearPrintVerificationArtifacts(context: Context) {
    val root = checkNotNull(context.getExternalFilesDir(null)) {
        "print verification storage is unavailable"
    }
    for (name in listOf(
        PRINT_TEXT_ARTIFACTS,
        PRINT_FRAGMENT_ARTIFACTS,
        MARKDOWN_PRINT_FRAGMENT_ARTIFACTS,
        "markdown-pagination-verification",
        "print-outline-verification",
        "print-color-verification",
        "print-vector-verification",
        "native-math-verification"
    )) {
        val directory = File(root, name).toPath()
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
            }
        }
    }
}

private val UnicodeLines = listOf(
    "A café 😀 with e\u0301 and ffi",
    "العربية",
    "שלום עולם",
    "日本語 Ελληνικά",
    "नमस्ते दुनिया",
    "👩‍💻 family 👨‍👩‍👧‍👦",
    "Literal PDF syntax: ) /JavaScript ( /Launch <00FF>"
)

/** Verifies real exported Unicode, native search, clipping and selected-page confidentiality. */
internal fun verifyPrintTextLayer(context: Context) {
    val artifacts = File(checkNotNull(context.getExternalFilesDir(null)), PRINT_TEXT_ARTIFACTS)
    check(artifacts.mkdirs() || artifacts.isDirectory) {
        "could not create print verification output"
    }
    val settings = defaultPrintSettings(false).copy(showFileName = false, showPageNumbers = false)
    val unicodePdf = File(artifacts, "unicode.pdf")
    writeTextLayerFixture(context, unicodePdf, UnicodeLines.joinToString("\n"), settings)
    inspectTextPdf(unicodePdf) { renderer ->
        check(renderer.pageCount == 1) { "Unicode PDF unexpectedly spans pages" }
        renderer.openPage(0).use { page ->
            val extracted = page.textContents.joinToString("\n") { it.text }
            Log.i(TAG, "Unicode extraction: " + extracted.replace('\n', ' ').replace('\r', ' '))
            for (line in UnicodeLines) {
                check(extracted.contains(line)) {
                    "PDF lost Unicode text: $line"
                }
            }
            for (term in listOf("café", "العربية", "שלום", "日本語", "Ελληνικά", "ffi")) {
                val matches = page.searchText(term)
                check(matches.isNotEmpty()) { "PDF search failed for $term" }
                val minimumInset = TEST_MARGIN_POINTS - TEST_BOUNDS_TOLERANCE_POINTS
                for (match in matches) {
                    check(
                        match.bounds.all { bounds ->
                            bounds.left >= minimumInset && bounds.top >= minimumInset &&
                                bounds.right <= page.width - minimumInset &&
                                bounds.bottom <= page.height - minimumInset
                        }
                    ) { "PDF search bounds escaped the printed body: $term ${match.bounds}" }
                }
            }
        }
    }
    verifyMixedDirectionText(context, artifacts, settings)
    val clippedPdf = File(artifacts, "clipped.pdf")
    writeTextLayerFixture(
        context,
        clippedPdf,
        "Visible " + "x".repeat(2_000) + " HIDDEN_SUFFIX",
        settings.copy(wrapLongLines = false)
    )
    inspectTextPdf(clippedPdf) { renderer ->
        renderer.openPage(0).use { page ->
            val extracted = page.textContents.joinToString { it.text }
            check(extracted.contains("Visible")) { "clipped PDF lost visible text" }
            check(!extracted.contains("HIDDEN_SUFFIX") && extracted.length < 200) {
                "clipped PDF exposed text outside the printed page"
            }
        }
    }
    val clippedRtlPdf = File(artifacts, "clipped-rtl.pdf")
    writeTextLayerFixture(
        context,
        clippedRtlPdf,
        "العربية " + "مرحبا ".repeat(300) + "HIDDEN_SUFFIX",
        settings.copy(wrapLongLines = false)
    )
    inspectTextPdf(clippedRtlPdf) { renderer ->
        renderer.openPage(0).use { page ->
            val text = page.textContents.joinToString { it.text }
            check(text.contains("العربية") && !text.contains("HIDDEN_SUFFIX")) {
                "unwrapped RTL text did not retain its visible beginning"
            }
        }
    }
    val manyLines = (0 until 180).joinToString("\n") {
        "UniqueLine${it.toString().padStart(3, '0')}"
    }
    val allPdf = File(artifacts, "all-pages.pdf")
    val selectedPdf = File(artifacts, "selected-page.pdf")
    writeTextLayerFixture(context, allPdf, manyLines, settings)
    writeTextLayerFixture(context, selectedPdf, manyLines, settings, arrayOf(PageRange(1, 1)))
    inspectTextPdf(allPdf) { all ->
        check(all.pageCount >= 3) { "selection fixture does not span enough pages" }
        val expected = all.openPage(1).use {
            it.textContents.joinToString { content -> content.text }
        }
        inspectTextPdf(selectedPdf) { selected ->
            check(selected.pageCount == 1) { "selected PDF included unrequested pages" }
            val actual = selected.openPage(0).use {
                it.textContents.joinToString { content -> content.text }
            }
            check(actual == expected) { "selected PDF text differs from the matching full page" }
            check(!actual.contains("UniqueLine000") && !actual.contains("UniqueLine179")) {
                "selected PDF exposed unrequested text"
            }
        }
    }
    val cjkPdf = File(artifacts, "many-glyphs.pdf")
    val cjk = (0 until 300).joinToString("") { (FIRST_CJK_IDEOGRAPH + it).toChar().toString() }
    writeTextLayerFixture(context, cjkPdf, cjk, settings)
    inspectTextPdf(cjkPdf) { renderer ->
        val extracted = (0 until renderer.pageCount).joinToString("") { index ->
            renderer.openPage(index).use { page -> page.textContents.joinToString("") { it.text } }
        }.filterNot(Char::isWhitespace)
        check(extracted == cjk) { "PDF lost text across a font-code boundary" }
    }
    verifyMarkdownTextLayer(context, File(artifacts, "markdown.pdf"))
    val combiningPdf = File(artifacts, "long-cluster.pdf")
    val combiningText = "a" + "\u0301".repeat(300)
    writeTextLayerFixture(context, combiningPdf, combiningText, settings)
    inspectTextPdf(combiningPdf) { renderer ->
        renderer.openPage(0).use { page ->
            val actual = page.textContents.joinToString("") {
                it.text
            }.filterNot(Char::isWhitespace)
            check(actual == combiningText) { "PDF lost a long combining sequence" }
        }
    }
}

/** Checks a mixed-direction sentence against a native Android PDF control. */
private fun verifyMixedDirectionText(context: Context, artifacts: File, settings: PrintSettings) {
    val original = "English العربية 123 עברית end"
    val output = File(artifacts, "mixed-direction.pdf")
    writeTextLayerFixture(context, output, original, settings)
    val serialized = output.readBytes().toString(Charsets.ISO_8859_1)
    check(serialized.contains("/ActualText <FEFF${pdfUnicodeHex(original)}>")) {
        "mixed-direction PDF did not preserve its logical Unicode text"
    }
    inspectTextPdf(output) { renderer ->
        renderer.openPage(0).use { page ->
            for (word in listOf("English", "العربية", "123", "עברית", "end")) {
                check(page.searchText(word).isNotEmpty()) { "mixed-direction word cannot be found" }
            }
            Log.i(TAG, "mixed-direction extraction: " + page.textContents.joinToString { it.text })
        }
    }
    val control = File(artifacts, "platform-control.pdf")
    val document = PdfDocument()
    try {
        val page = document.startPage(
            PdfDocument.PageInfo.Builder(
                TEST_PAGE_WIDTH_POINTS,
                TEST_PAGE_HEIGHT_POINTS,
                1
            ).create()
        )
        val paint = TextPaint().apply { textSize = TEST_FONT_SIZE_POINTS }
        val bodyWidth = TEST_PAGE_WIDTH_POINTS - 2 * TEST_MARGIN_POINTS
        val layout = StaticLayout.Builder.obtain(
            original,
            0,
            original.length,
            paint,
            bodyWidth
        ).build()
        page.canvas.translate(TEST_MARGIN_POINTS.toFloat(), TEST_MARGIN_POINTS.toFloat())
        layout.draw(page.canvas)
        document.finishPage(page)
        control.outputStream().use(document::writeTo)
    } finally {
        document.close()
    }
    inspectTextPdf(control) { renderer ->
        renderer.openPage(0).use { page ->
            Log.i(
                TAG,
                "platform mixed-direction extraction: " + page.textContents.joinToString {
                    it.text
                }
            )
        }
    }
}

/** Exports a real immutable source revision without using an application document cache. */
private fun writeTextLayerFixture(
    context: Context,
    output: File,
    text: String,
    settings: PrintSettings,
    requestedPages: Array<PageRange> = arrayOf(PageRange.ALL_PAGES),
    maximumBlockUtf16Units: Int? = null
) {
    RustDocument.createEmpty().use { document ->
        val metrics = document.replace(0, Utf16Range(0, 0), text)
        document.captureSnapshot(metrics.revision).use { snapshot ->
            val viewportSnapshot = if (maximumBlockUtf16Units == null) {
                snapshot
            } else {
                object : EditorDocumentSnapshot by snapshot {
                    override fun viewport(
                        cursor: ViewportCursor,
                        limits: ViewportLimits
                    ): ViewportSnapshot = snapshot.viewport(
                        cursor,
                        limits.copy(
                            maxBlockUtf16Units = minOf(
                                limits.maxBlockUtf16Units,
                                maximumBlockUtf16Units
                            )
                        )
                    )
                }
            }
            output.outputStream().buffered().use { destination ->
                runBlocking {
                    renderDocumentTextPdf(
                        destination,
                        viewportSnapshot,
                        metrics,
                        "Not embedded as metadata",
                        printRasterLayout(textLayerAttributes(), settings),
                        settings,
                        requestedPages,
                        resources = context.resources
                    )
                }
            }
        }
    }
}

/** Compares rendered pages when the same logical lines arrive in different native fragments. */
internal fun verifyPrintFragmentContinuity(context: Context) {
    val artifacts = File(checkNotNull(context.getExternalFilesDir(null)), PRINT_FRAGMENT_ARTIFACTS)
    check(artifacts.mkdirs() || artifacts.isDirectory) {
        "could not create print fixture directory"
    }
    val settings = defaultPrintSettings(false).copy(showFileName = false, showPageNumbers = false)
    val fixtures = listOf(
        "word boundaries " + "a word of varying length ".repeat(80),
        "before " + "x".repeat(800) + " after\n\nAnother line",
        "Direction " + "مرحبا بالعالم ".repeat(60),
        "مرحبا " + "Latin text changes the first strong character ".repeat(40),
        "Printable ".repeat(TEST_LONG_PRINT_LINE_REPETITIONS),
        "123 ".repeat(100) + "العربية مرحبا ".repeat(60),
        "123 ".repeat(100) + "\nالعربية",
        "... ".repeat(100) + "English after neutral text ".repeat(20),
        "العربية " + "123 ".repeat(160) + "English",
        " ".repeat(TEST_LONG_SPACE_RUN_UNITS) + "After whitespace",
        "Before " + " ".repeat(TEST_LONG_SPACE_RUN_UNITS) + "After whitespace\nEnd",
        "Before " + " ".repeat(TEST_LONG_SPACE_RUN_UNITS) + "\nEnd",
        " ".repeat(TEST_LONG_SPACE_RUN_UNITS) + "\nEnd",
        "\u202E" + "Directional override ".repeat(80) + "\u202C",
        "\u2067" + "אבג 123 ".repeat(300) + "\u2069 ending",
        "\u2068" + "123 ".repeat(100) + "العربية\u2069 ending",
        "\u202E" + "Long override ".repeat(2_000) + "\u202C"
    )
    for ((index, text) in fixtures.withIndex()) {
        val whole = File(artifacts, "whole-$index.pdf")
        val fragmented = File(artifacts, "fragmented-$index.pdf")
        writeTextLayerFixture(context, whole, text, settings)
        writeTextLayerFixture(
            context,
            fragmented,
            text,
            settings,
            maximumBlockUtf16Units = TEST_PRINT_FRAGMENT_UTF16_UNITS
        )
        inspectTextPdf(whole) { expected ->
            inspectTextPdf(fragmented) { actual ->
                check(actual.pageCount == expected.pageCount) {
                    "native print fragments changed the page count for fixture $index"
                }
                for (pageIndex in 0 until expected.pageCount) {
                    expected.openPage(pageIndex).use { expectedPage ->
                        actual.openPage(pageIndex).use { actualPage ->
                            val expectedBitmap = rasterizePrintPage(expectedPage)
                            val actualBitmap = rasterizePrintPage(actualPage)
                            try {
                                check(actualBitmap.sameAs(expectedBitmap)) {
                                    "native print fragments changed fixture $index page $pageIndex"
                                }
                            } finally {
                                expectedBitmap.recycle()
                                actualBitmap.recycle()
                            }
                        }
                    }
                }
            }
        }
    }
    var rejected = false
    try {
        writeTextLayerFixture(
            context,
            File(artifacts, "oversized-cluster.pdf"),
            "a" + "\u0301".repeat(TEST_UNBREAKABLE_PRINT_CLUSTER_UNITS),
            settings,
            maximumBlockUtf16Units = TEST_PRINT_FRAGMENT_UTF16_UNITS
        )
    } catch (_: IOException) {
        rejected = true
    }
    check(rejected) { "print fragments retained an unbounded unbreakable line" }
}

/** Rasterizes one fixture page at a deterministic scale for exact visual comparison. */
private fun rasterizePrintPage(page: PdfRenderer.Page): Bitmap {
    val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
    try {
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    } catch (failure: Throwable) {
        bitmap.recycle()
        throw failure
    }
}

/** Compares native Markdown continuations against the same complete semantic block. */
internal fun verifyMarkdownPrintFragmentContinuity(context: Context) {
    val artifacts =
        File(checkNotNull(context.getExternalFilesDir(null)), MARKDOWN_PRINT_FRAGMENT_ARTIFACTS)
    check(artifacts.mkdirs() || artifacts.isDirectory)
    val text = "A paragraph with varying word lengths and comfortable spacing. ".repeat(
        100
    ).trimEnd()
    val codeLines = "val sample = \"Text with unicode αβγ, العربية and emoji 😀\"\n".repeat(300)
    val zeroWidthCell = "First" + "\u200b".repeat(TEST_MARKDOWN_LAYOUT_UNITS + 1)
    val fixtures = listOf(
        text to text,
        "**$text**" to text,
        "# $text" to text,
        "> $text" to text,
        "- $text" to text,
        "```text\n$text\n```" to "$text\n",
        "<table><tr><td>$text</td><td>Final cell</td></tr></table>" to "$text\tFinal cell",
        "```kotlin\n$codeLines```" to codeLines,
        "<table><tr><td>$zeroWidthCell</td><td>Last</td></tr></table>" to "$zeroWidthCell\tLast"
    )
    val settings = defaultPrintSettings(true).copy(showFileName = false, showPageNumbers = false)
    for ((index, fixture) in fixtures.withIndex()) {
        val (source, expectedText) = fixture
        val document = RustDocument.createEmpty().use { native ->
            val metrics = native.replace(0, Utf16Range(0, 0), source)
            native.captureSnapshot(metrics.revision).use { snapshot ->
                runBlocking {
                    IsolatedMarkdownRenderer(context).render(snapshot, metrics.serializedByteLength)
                }
            }
        }
        check(document.blocks.size > 1 && document.blocks.drop(1).all { it.continuesPrevious }) {
            "Markdown fixture $index does not contain one fragmented semantic block"
        }
        check(document.blocks.joinToString("") { it.text } == expectedText) {
            "Markdown fixture $index has unexpected rendered text"
        }
        val completeBlock = document.blocks.first().copy(
            text = expectedText,
            spans = if (source.startsWith("**")) {
                listOf(MarkdownInlineSpan(0, expectedText.length, MARKDOWN_SPAN_STYLE_STRONG, null))
            } else {
                emptyList()
            },
            sourceMaps = emptyList(),
            tableAlignments = if (source.startsWith("<table>")) {
                listOf(MarkdownTableAlignment.None, MarkdownTableAlignment.None)
            } else {
                emptyList()
            }
        )
        val whole = File(artifacts, "whole-$index.pdf")
        val fragmented = File(artifacts, "fragmented-$index.pdf")
        writeMarkdownPrintFixture(
            context,
            whole,
            document.copy(blocks = listOf(completeBlock)),
            settings
        )
        writeMarkdownPrintFixture(
            context,
            fragmented,
            document,
            settings,
            maxBlockUtf16Units = if (expectedText == codeLines) {
                TEST_MARKDOWN_LAYOUT_UNITS
            } else {
                MAXIMUM_MARKDOWN_PRINT_BLOCK_UNITS
            }
        )
        inspectTextPdf(whole) { expected ->
            inspectTextPdf(fragmented) { actual ->
                check(actual.pageCount == expected.pageCount) {
                    "Markdown fragments changed the page count for fixture $index"
                }
                for (pageIndex in 0 until expected.pageCount) {
                    expected.openPage(pageIndex).use { expectedPage ->
                        actual.openPage(pageIndex).use { actualPage ->
                            val expectedBitmap = rasterizePrintPage(expectedPage)
                            val actualBitmap = rasterizePrintPage(actualPage)
                            try {
                                check(actualBitmap.sameAs(expectedBitmap)) {
                                    "Markdown fragments changed fixture $index page $pageIndex"
                                }
                            } finally {
                                expectedBitmap.recycle()
                                actualBitmap.recycle()
                            }
                        }
                    }
                }
            }
        }
    }
    verifyMarkdownFootnoteIdentity(context, File(artifacts, "footnotes.pdf"))
}

/** Verifies adjacent and boundary-crossing native references in preview text and the PDF. */
private fun verifyMarkdownFootnoteIdentity(context: Context, output: File) {
    val prefix = "a".repeat(TEST_MARKDOWN_LAYOUT_UNITS - 1)
    val source = "$prefix[^note][^note][^other].\n\n" +
        "[^note]: First definition.\n\n[^other]: Second definition."
    val preview = RustDocument.createEmpty().use { document ->
        val metrics = document.replace(0, Utf16Range(0, 0), source)
        document.captureSnapshot(metrics.revision).use { snapshot ->
            runBlocking {
                IsolatedMarkdownRenderer(context).render(snapshot, metrics.serializedByteLength)
            }
        }
    }
    val numbers = markdownFootnoteNumbers(preview.blocks)
    check(numbers == mapOf("note" to 1, "other" to 2)) {
        "native footnotes changed reading-order identities"
    }
    val references = preview.blocks.flatMap { block ->
        block.spans.filter { it.destinationKind == MarkdownInlineDestinationKind.FootnoteReference }
            .map { it.destination }
    }
    check(references == listOf("note", "note", "other")) {
        "native footnotes merged adjacent references or divided one reference"
    }
    val paragraph = preview.blocks.filter { it.kind != MarkdownBlockKind.Footnote }
        .joinToString("") { block ->
            markdownFootnotePresentation(block.text, block.spans, numbers)?.text ?: block.text
        }
    check(paragraph == "${prefix}112.") { "preview lost a numbered footnote reference" }
    writeMarkdownPrintFixture(
        context,
        output,
        preview,
        defaultPrintSettings(true).copy(showFileName = false, showPageNumbers = false)
    )
    inspectTextPdf(output) { renderer ->
        val printed = (0 until renderer.pageCount).joinToString("") { pageIndex ->
            renderer.openPage(pageIndex).use { page ->
                page.textContents.joinToString("") { it.text }
            }
        }.filterNot(Char::isWhitespace)
        check(printed == "${prefix}112.1.Firstdefinition.2.Seconddefinition.") {
            "PDF changed a footnote reference or definition"
        }
    }
}

/** Writes one semantic test model using the production Android PDF layout. */
private fun writeMarkdownPrintFixture(
    context: Context,
    output: File,
    document: MarkdownPreviewDocument,
    settings: PrintSettings,
    maxBlockUtf16Units: Int = MAXIMUM_MARKDOWN_PRINT_BLOCK_UNITS
) {
    output.outputStream().buffered().use { destination ->
        runBlocking {
            renderMarkdownDocumentPdf(
                destination,
                document,
                "Markdown fragment test",
                printRasterLayout(textLayerAttributes(), settings),
                settings,
                arrayOf(PageRange.ALL_PAGES),
                resources = context.resources,
                maxBlockUtf16Units = maxBlockUtf16Units
            )
        }
    }
}

/** Verifies rendered Markdown text and safe HTML without embedding link actions or source markup. */
private fun verifyMarkdownTextLayer(context: Context, output: File) {
    val markdown = """
        # Reading, shared

        ## Unicode and structure

        A **bold phrase**, *emphasis*, and [a link](https://example.org/hidden-destination).

        <p>A safe <strong>HTML paragraph</strong>.</p>

        | First | Second |
        | --- | --- |
        | Red | Blue |

        ```rust
        let answer = 42;
        ```

        العربية
    """.trimIndent()
    val settings = defaultPrintSettings(true).copy(showFileName = false, showPageNumbers = false)
    RustDocument.createEmpty().use { document ->
        val metrics = document.replace(0, Utf16Range(0, 0), markdown)
        document.captureSnapshot(metrics.revision).use { snapshot ->
            runBlocking {
                val preview = IsolatedMarkdownRenderer(
                    context
                ).render(snapshot, metrics.serializedByteLength)
                output.outputStream().buffered().use { destination ->
                    renderMarkdownDocumentPdf(
                        destination,
                        preview,
                        "Markdown",
                        printRasterLayout(textLayerAttributes(), settings),
                        settings,
                        arrayOf(PageRange.ALL_PAGES),
                        resources = context.resources
                    )
                }
            }
        }
    }
    inspectTextPdf(output) { renderer ->
        val text = (0 until renderer.pageCount).joinToString("\n") { index ->
            renderer.openPage(index).use { page ->
                check(page.linkContents.isEmpty()) { "PDF introduced link actions" }
                page.textContents.joinToString("\n") { it.text }
            }
        }
        Log.i(TAG, "Markdown extraction: " + text.replace('\n', ' ').replace('\r', ' '))
        for (term in listOf(
            "Reading, shared",
            "bold phrase",
            "HTML paragraph",
            "First",
            "Blue",
            "answer = 42",
            "العربية"
        )) {
            check(text.contains(term)) { "Markdown PDF lost rendered text: $term" }
        }
        check(!text.contains("hidden-destination") && !text.contains("<strong>")) {
            "Markdown PDF exposed unprinted source content"
        }
    }
}

/** Creates a deterministic portrait print page at the production raster density. */
private fun textLayerAttributes(): PrintAttributes = PrintAttributes.Builder()
    .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
    .setResolution(
        PrintAttributes.Resolution(
            "text-test",
            "Text test",
            TEST_RESOLUTION_DPI,
            TEST_RESOLUTION_DPI
        )
    )
    .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
    .build()

/** Opens a test PDF through Android's production PDF renderer with deterministic ownership. */
private fun inspectTextPdf(file: File, inspect: (PdfRenderer) -> Unit) {
    PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use(inspect)
}
