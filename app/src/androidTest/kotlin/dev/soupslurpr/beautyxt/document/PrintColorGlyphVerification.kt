package dev.soupslurpr.beautyxt.document

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import dev.soupslurpr.beautyxt.markdown.client.IsolatedMarkdownRenderer
import dev.soupslurpr.beautyxt.printing.defaultPrintSettings
import dev.soupslurpr.beautyxt.printing.printRasterLayout
import dev.soupslurpr.beautyxt.printing.renderDocumentTextPdf
import dev.soupslurpr.beautyxt.printing.renderMarkdownDocumentPdf
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking

private const val COLOR_GLYPH = "🟧"
private const val GLYPH_FONT_SIZE_POINTS = 48
private const val GLYPH_PAGE_SIZE_MILS = 3_000
private const val GLYPH_RASTER_DPI = 144
private const val NONWHITE_CHANNEL_LIMIT = 240
private val GlyphShadingRange = 100..230

/** Verifies native color-glyph appearance in both color and monochrome source/Markdown PDFs. */
internal fun verifyPrintColorGlyphs(context: Context) {
    val artifacts =
        File(checkNotNull(context.getExternalFilesDir(null)), "print-color-verification")
    check(artifacts.mkdirs() || artifacts.isDirectory)
    val attributes =
        PrintAttributes.Builder()
            .setMediaSize(
                PrintAttributes.MediaSize(
                    "glyph-test",
                    "Glyph test",
                    GLYPH_PAGE_SIZE_MILS,
                    GLYPH_PAGE_SIZE_MILS
                )
            )
            .setResolution(
                PrintAttributes.Resolution(
                    "glyph-test",
                    "Glyph test",
                    GLYPH_RASTER_DPI,
                    GLYPH_RASTER_DPI
                )
            )
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()
    RustDocument.createEmpty().use { document ->
        val metrics =
            document.replace(
                expectedRevision = 0,
                range = Utf16Range(0, 0),
                replacement = COLOR_GLYPH
            )
        document.captureSnapshot(metrics.revision).use { snapshot ->
            runBlocking {
                val preview =
                    IsolatedMarkdownRenderer(context).render(
                        snapshot = snapshot,
                        expectedBytes = metrics.serializedByteLength
                    )
                for (formattedMarkdown in listOf(false, true)) {
                    for (monochrome in listOf(false, true)) {
                        val settings =
                            defaultPrintSettings(formattedMarkdown).copy(
                                fontSizePoints = GLYPH_FONT_SIZE_POINTS,
                                showFileName = false,
                                showPageNumbers = false
                            )
                        val layout = printRasterLayout(
                            PrintAttributes.Builder()
                                .setMediaSize(checkNotNull(attributes.mediaSize))
                                .setResolution(checkNotNull(attributes.resolution))
                                .setMinMargins(checkNotNull(attributes.minMargins))
                                .setColorMode(
                                    if (monochrome) {
                                        PrintAttributes.COLOR_MODE_MONOCHROME
                                    } else {
                                        PrintAttributes.COLOR_MODE_COLOR
                                    }
                                ).build(),
                            settings
                        )
                        val output = ByteArrayOutputStream()
                        if (formattedMarkdown) {
                            renderMarkdownDocumentPdf(
                                output,
                                preview,
                                "Glyph test",
                                layout,
                                settings,
                                arrayOf(PageRange.ALL_PAGES),
                                resources = context.resources
                            )
                        } else {
                            document.captureSnapshot(metrics.revision).use { sourceSnapshot ->
                                renderDocumentTextPdf(
                                    output,
                                    sourceSnapshot,
                                    metrics,
                                    "Glyph test",
                                    layout,
                                    settings,
                                    arrayOf(PageRange.ALL_PAGES),
                                    resources = context.resources
                                )
                            }
                        }
                        val file = File(artifacts, "glyph-$formattedMarkdown-$monochrome.pdf")
                        file.writeBytes(output.toByteArray())
                        verifyGlyphShading(file, monochrome)
                    }
                }
            }
        }
    }
}

/** Checks actual PDF compositing, including the localized image's soft alpha mask. */
private fun verifyGlyphShading(pdf: File, monochrome: Boolean) {
    PdfRenderer(
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
    ).use { renderer ->
        renderer.openPage(0).use { page ->
            val bitmap = Bitmap.createBitmap(
                page.width * 2,
                page.height * 2,
                Bitmap.Config.ARGB_8888
            )
            try {
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                var nonwhitePixels = 0
                var shadedPixels = 0
                var colorfulPixels = 0
                val row = IntArray(bitmap.width)
                repeat(bitmap.height) { y ->
                    bitmap.getPixels(row, 0, row.size, 0, y, row.size, 1)
                    for (color in row) {
                        val red = Color.red(color)
                        val green = Color.green(color)
                        val blue = Color.blue(color)
                        val gray = (red * 77 + green * 150 + blue * 29) / 256
                        if (gray < NONWHITE_CHANNEL_LIMIT) nonwhitePixels++
                        if (gray in GlyphShadingRange) shadedPixels++
                        if (maxOf(red, green, blue) - minOf(red, green, blue) > 3) colorfulPixels++
                    }
                }
                check(nonwhitePixels > 100 && shadedPixels * 2 > nonwhitePixels) {
                    "printed glyph lost its shading: $shadedPixels of $nonwhitePixels pixels"
                }
                check(
                    if (monochrome) colorfulPixels == 0 else colorfulPixels * 2 > nonwhitePixels
                ) {
                    "print color mode was not respected: monochrome=$monochrome color=$colorfulPixels"
                }
            } finally {
                bitmap.recycle()
            }
        }
    }
}
