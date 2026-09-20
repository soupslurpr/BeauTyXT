package dev.soupslurpr.beautyxt.document

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.ui.graphics.Color
import dev.soupslurpr.beautyxt.transfer.TransferProtocol
import dev.soupslurpr.beautyxt.transfer.client.IsolatedTransferProcessor
import dev.soupslurpr.beautyxt.transfer.client.QrLuminanceFrame
import dev.soupslurpr.beautyxt.ui.editor.QrCodeColors
import dev.soupslurpr.beautyxt.ui.editor.renderExpressiveQrCodeBitmap
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlinx.coroutines.runBlocking

/** Measures Android encoders on actual QR exports and checks exact pixels and decoded content. */
internal fun verifyQrImageEncodings(
    context: Context,
    luminance: (Bitmap) -> QrLuminanceFrame,
    report: (String) -> Unit,
    benchmark: Boolean = false
) = runBlocking {
    val processor = IsolatedTransferProcessor(context)
    val random = Random(37)
    val fixtures = listOf(
        "Hello from BeauTyXT.",
        "# Notes\n\nA small document with **formatting** and Unicode: 漢字 😀.",
        "A medium document with Unicode: café, 漢字, 😀.\n".repeat(12),
        buildString {
            repeat(TransferProtocol.MAX_QR_TEXT_BYTES.toInt()) {
                append(random.nextInt('!'.code, '~'.code + 1).toChar())
            }
        }
    )
    val palettes = listOf(
        QrCodeColors(Color.Black, Color.White),
        QrCodeColors(Color(0xff17315e), Color(0xffafc6ff)),
        QrCodeColors(Color(0xff00392b), Color(0xff9df2cc))
    )
    val encoders = buildList {
        add(Triple("PNG", Bitmap.CompressFormat.PNG, 100))
        if (benchmark) add(Triple("WebP75", Bitmap.CompressFormat.WEBP_LOSSLESS, 75))
        add(Triple("WebP100", Bitmap.CompressFormat.WEBP_LOSSLESS, 100))
    }
    fixtures.forEachIndexed { fixtureIndex, text ->
        RustDocument.createEmpty().use { document ->
            val metrics = document.replace(0L, Utf16Range(0L, 0L), text)
            document.captureSnapshot(metrics.revision).use { snapshot ->
                report("QR encoding fixture=$fixtureIndex textBytes=${metrics.serializedByteLength}")
                val grid = processor.encodeQr(
                    snapshot, metrics.serializedByteLength, DocumentFormat.Markdown
                )
                report("QR encoding fixture=$fixtureIndex dimension=${grid.dimension}")
                val scale = 1024 / (grid.dimension + 8)
                val side = (grid.dimension + 8) * scale
                val pixels = IntArray(side * side) { offset ->
                    val row = offset / side / scale - 4
                    val column = offset % side / scale - 4
                    if (row in 0 until grid.dimension && column in 0 until grid.dimension &&
                        grid.isDark(row, column)
                    ) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                }
                val square = Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
                try {
                    check(processor.decodeQr(luminance(square)).text == text)
                } finally {
                    square.recycle()
                }
                report("QR encoding fixture=$fixtureIndex square reference decoded")
                palettes.forEachIndexed { paletteIndex, colors ->
                    val original = renderExpressiveQrCodeBitmap(grid, colors)
                    try {
                        for ((name, encoder, effort) in encoders) {
                            val elapsed = ArrayList<Long>()
                            var encoded = byteArrayOf()
                            repeat(if (benchmark) 6 else 1) { iteration ->
                                val output = ByteArrayOutputStream()
                                val start = SystemClock.elapsedRealtimeNanos()
                                check(original.compress(encoder, effort, output))
                                val duration = SystemClock.elapsedRealtimeNanos() - start
                                if (iteration > 0 || !benchmark) elapsed += duration
                                encoded = output.toByteArray()
                            }
                            val decoded = checkNotNull(
                                BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
                            )
                            try {
                                check(original.sameAs(decoded)) {
                                    "$name changed QR pixels for fixture $fixtureIndex"
                                }
                                val received = processor.decodeQr(luminance(decoded))
                                check(received.text == text) { "$name changed QR text" }
                                check(received.format == DocumentFormat.Markdown) {
                                    "$name changed the transferred document format"
                                }
                            } finally {
                                decoded.recycle()
                            }
                            report("QR encoding fixture=$fixtureIndex palette=$paletteIndex " +
                                "format=$name bytes=${encoded.size} " +
                                "medianNanos=${elapsed.sorted()[elapsed.size / 2]}")
                        }
                    } finally {
                        original.recycle()
                    }
                }
            }
        }
    }
}
