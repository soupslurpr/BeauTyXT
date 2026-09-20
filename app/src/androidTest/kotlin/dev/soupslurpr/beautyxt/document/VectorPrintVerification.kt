package dev.soupslurpr.beautyxt.document

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import dev.soupslurpr.beautyxt.printing.PdfTextPage
import dev.soupslurpr.beautyxt.printing.PdfVectorPage
import dev.soupslurpr.beautyxt.printing.PdfVisualResource
import dev.soupslurpr.beautyxt.printing.PrintMargins
import dev.soupslurpr.beautyxt.printing.StreamingPdfWriter
import dev.soupslurpr.beautyxt.printing.VectorPrintCanvas
import dev.soupslurpr.beautyxt.printing.defaultPrintSettings
import dev.soupslurpr.beautyxt.printing.printGlyphPath
import dev.soupslurpr.beautyxt.printing.printRasterLayout
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** Inspects real PDF vector appearances against Android, then exercises hard page budgets. */
internal fun verifyVectorPrint(context: Context) {
    val artifacts =
        File(checkNotNull(context.getExternalFilesDir(null)), "print-vector-verification")
    check(artifacts.mkdirs() || artifacts.isDirectory)
    val attributes = PrintAttributes.Builder()
        .setMediaSize(PrintAttributes.MediaSize("vector-test", "Vector test", 6_000, 8_500))
        .setResolution(PrintAttributes.Resolution("vector-test", "Vector test", 144, 144))
        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
        .build()
    val settings = defaultPrintSettings(false).copy(
        margins = PrintMargins(0, 0, 0, 0),
        showFileName = false,
        showPageNumbers = false
    )
    val layout = printRasterLayout(attributes, settings)
    val expected = Bitmap.createBitmap(
        layout.rasterWidthPixels,
        layout.rasterHeightPixels,
        Bitmap.Config.ARGB_8888
    )
    expected.eraseColor(Color.WHITE)
    val file = File(artifacts, "vector-comparison.pdf")
    file.outputStream().use { output ->
        val writer = StreamingPdfWriter(output)
        PdfVectorPage(monochrome = false).use { page ->
            val native = Canvas(expected)
            val vector = writer.canvas(page, layout)
            drawVectorFixture(native, expected.width)
            drawVectorFixture(vector, expected.width)
            check(page.resources.count { it is PdfVisualResource.Outline } > 80)
            check(
                page.resources.filterIsInstance<PdfVisualResource.Image>().all {
                    it.bitmap.width.toLong() * it.bitmap.height <
                        expected.width * expected.height / 100
                }
            ) { "vector PDF unexpectedly allocated a page-sized bitmap" }
            writer.writePage(page, layout, PdfTextPage())
            writer.finish()
        }
    }
    val actual = Bitmap.createBitmap(expected.width, expected.height, Bitmap.Config.ARGB_8888)
    actual.eraseColor(Color.WHITE)
    PdfRenderer(
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    ).use { renderer ->
        renderer.openPage(0).use {
            it.render(actual, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
    }
    val comparison = Bitmap.createBitmap(
        expected.width * 2,
        expected.height,
        Bitmap.Config.ARGB_8888
    )
    Canvas(comparison).apply {
        drawBitmap(expected, 0f, 0f, null)
        drawBitmap(actual, expected.width.toFloat(), 0f, null)
    }
    File(artifacts, "android-and-vector.webp").outputStream().use {
        check(comparison.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, it))
    }
    comparison.recycle()
    try {
        verifyVectorInk(expected, actual)
    } finally {
        expected.recycle()
        actual.recycle()
    }
    verifyVectorPageBudgets()
    verifyVectorPageStreaming(layout)
}

private fun drawVectorFixture(canvas: Canvas, width: Int) {
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = 32f
        color = Color.BLACK
    }
    canvas.drawText("Android text and streamed vector PDF", 24f, 46f, paint)
    canvas.drawText("عنوان عربي — filename 123", 24f, 96f, paint)
    val samples = listOf(
        "A café with e\u0301 and ffi — 123",
        "العربية — السلام عليكم",
        "שלום עולם — 123 English",
        "日本語 Ελληνικά 中文",
        "नमस्ते दुनिया",
        "👩‍💻 family 👨‍👩‍👧‍👦 🟧",
        "Bold and italic with العربية and 日本語",
        "Serif glyphs: café ffi العربية",
        "Wide glyphs: ABC fi e\u0301 שלום",
        "Underline, strike, color, and background"
    )
    for ((index, sample) in samples.withIndex()) {
        val text = SpannableString(sample)
        paint.typeface = if (index == 7) Typeface.SERIF else Typeface.DEFAULT
        paint.textScaleX = if (index == 8) 1.25f else 1f
        paint.textSkewX = if (index == 8) -0.2f else 0f
        if (index == 6) text.setSpan(StyleSpan(Typeface.BOLD_ITALIC), 0, text.length, 33)
        if (index == 9) {
            text.setSpan(UnderlineSpan(), 0, 9, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(StrikethroughSpan(), 11, 17, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(ForegroundColorSpan(Color.rgb(30, 85, 90)), 19, 24, 33)
            text.setSpan(BackgroundColorSpan(Color.argb(35, 25, 75, 85)), 30, text.length, 33)
        }
        val textLayout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width - 48)
            .setIncludePad(false).build()
        val saved = canvas.save()
        canvas.translate(24f, 130f + index * 88)
        textLayout.draw(canvas)
        canvas.restoreToCount(saved)
    }
    paint.apply {
        textScaleX = 1f
        textSkewX = 0f
        textAlign = Paint.Align.RIGHT
    }
    canvas.drawText("عربي — Page 12", width - 24f, 1_110f, paint)
}

/** Allows raster hinting differences, but not missing, displaced, or differently shaped text. */
private fun verifyVectorInk(expected: Bitmap, actual: Bitmap) {
    val expectedPixels = IntArray(expected.width * expected.height)
    val actualPixels = IntArray(expectedPixels.size)
    expected.getPixels(expectedPixels, 0, expected.width, 0, 0, expected.width, expected.height)
    actual.getPixels(actualPixels, 0, actual.width, 0, 0, actual.width, actual.height)
    fun isInk(color: Int) = maxOf(Color.red(color), Color.green(color), Color.blue(color)) < 180
    for ((from, to) in listOf(expectedPixels to actualPixels, actualPixels to expectedPixels)) {
        var ink = 0
        var unmatched = 0
        for (y in 2 until expected.height - 2) {
            for (x in 2 until expected.width - 2) {
                if (!isInk(from[y * expected.width + x])) continue
                ink++
                var matched = false
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        if (isInk(to[(y + dy) * expected.width + x + dx])) matched = true
                    }
                }
                if (!matched) unmatched++
            }
        }
        check(ink > 10_000 && unmatched * 100 < ink * 2) {
            "vector appearance differs from Android: $unmatched unmatched of $ink ink pixels"
        }
    }
}

private fun verifyVectorPageBudgets() {
    fun rejected(action: () -> Unit) {
        check(runCatching(action).exceptionOrNull() is IOException) {
            "print budget was not enforced"
        }
    }
    PdfVectorPage(false).use { page ->
        rejected { page.reserveBitmap(4_001, 1_000) }
        rejected { repeat(9) { page.command(" ".repeat(1_024 * 1_024)) } }
    }
    for (components in listOf(
        floatArrayOf(0f, 1f),
        floatArrayOf(9f),
        floatArrayOf(0f, Float.NaN, 2f)
    )) {
        check(runCatching { printGlyphPath(components) }.isFailure)
    }
    PdfVectorPage(false).use { page ->
        val canvas = VectorPrintCanvas(page, 300, 100) {}
        val paint = Paint().apply { textSize = 24f }
        canvas.clipRect(0, 0, 100, 100)
        canvas.drawText("SECRET", 150f, 50f, paint)
        check(page.resources.isEmpty()) { "off-page text left recoverable vector resources" }
    }
}

/** Produces hundreds of vector pages without a bitmap, whole-document buffer, or private file. */
private fun verifyVectorPageStreaming(layout: dev.soupslurpr.beautyxt.printing.PrintRasterLayout) {
    var bytes = 0L
    val destination = object : OutputStream() {
        override fun write(value: Int) {
            bytes++
        }
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            bytes += length
        }
    }
    val writer = StreamingPdfWriter(destination)
    val paint = Paint().apply { textSize = 30f }
    repeat(256) { index ->
        PdfVectorPage(false).use { page ->
            writer.canvas(page, layout).drawText("Page $index: vector text", 24f, 60f, paint)
            check(
                page.resources.isNotEmpty() &&
                    page.resources.all { it is PdfVisualResource.Outline }
            )
            writer.writePage(page, layout, PdfTextPage())
        }
    }
    writer.finish()
    check(writer.writtenPageCount == 256 && bytes > 100_000)
}
