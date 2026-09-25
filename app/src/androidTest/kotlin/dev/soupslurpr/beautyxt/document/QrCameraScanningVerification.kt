package dev.soupslurpr.beautyxt.document

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.SystemClock
import androidx.compose.ui.graphics.Color
import dev.soupslurpr.beautyxt.transfer.TransferProtocol
import dev.soupslurpr.beautyxt.transfer.client.IsolatedTransferProcessor
import dev.soupslurpr.beautyxt.transfer.client.QrLuminanceFrame
import dev.soupslurpr.beautyxt.ui.editor.QrCodeColors
import dev.soupslurpr.beautyxt.ui.editor.renderExpressiveQrCodeBitmap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.runBlocking

private const val CAMERA_WIDTH = 1280
private const val CAMERA_HEIGHT = 960

/** Supplies a repeatable production-image frame for scanner session checks. */
internal fun qrDecoderSessionFrame(qr: Bitmap): QrLuminanceFrame =
    cameraFrame(qr, 480f, CameraScene("session", background = 120))

/** Exercises actual Android QR rendering and isolated decoding under camera-like transforms. */
internal fun verifyQrCameraScanning(
    context: Context,
    report: (String) -> Unit
) = runBlocking {
    val processor = IsolatedTransferProcessor(context)
    val failures = mutableListOf<String>()
    val random = Random(812)
    val texts = listOf(
        "# Accessibility review\n\nA short note for Print and QR choices.\n",
        "A note with Unicode: café, 漢字, 😀.\n".repeat(14),
        buildString {
            repeat(TransferProtocol.MAX_QR_TEXT_BYTES.toInt()) {
                append(random.nextInt('!'.code, '~'.code + 1).toChar())
            }
        }
    )
    val colors = listOf(
        QrCodeColors(Color.Black, Color.White),
        QrCodeColors(Color(0xff17315e), Color(0xffafc6ff))
    )
    val scenes = listOf(
        CameraScene("small", background = 120),
        CameraScene("off-center", horizontalPosition = 0.05f, angle = 20f),
        CameraScene("rotated", angle = -35f, background = 24),
        CameraScene("perspective", angle = 15f, topInset = 0.12f),
        CameraScene("shaded", angle = 10f, shadow = true),
        CameraScene("inverted", angle = 25f, inverted = true)
    )
    texts.forEachIndexed { fixtureIndex, text ->
        RustDocument.createEmpty().use { document ->
            val metrics = document.replace(0L, Utf16Range(0L, 0L), text)
            document.captureSnapshot(metrics.revision).use { snapshot ->
                val grid = processor.encodeQr(snapshot, metrics.serializedByteLength, DocumentFormat.Markdown)

                colors.forEachIndexed { paletteIndex, palette ->
                    val qr = renderExpressiveQrCodeBitmap(grid, palette)
                    // Account for the export renderer's integer module size and outer padding.
                    val modulePixels = qr.width / (grid.dimension + 8)
                    val sides = listOf(
                        maxOf(260f, (grid.dimension + 8) * 4f),
                        maxOf(260f, qr.width * 4f / modulePixels)
                    ).distinct()
                    try {
                        for (side in sides) for (scene in scenes) {
                            val frame = cameraFrame(qr, side, scene)
                            val description = "fixture=$fixtureIndex dimension=${grid.dimension} " +
                                "palette=$paletteIndex side=$side scene=${scene.name}"
                            val start = SystemClock.elapsedRealtimeNanos()
                            val received = try {
                                processor.decodeQr(frame)
                            } catch (failure: Exception) {
                                failures += "$description: ${failure.message}"
                                report("QR camera $description FAILED ${failure.message}")
                                continue
                            } finally {
                                frame.bytes.fill(0)
                            }
                            val millis = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000
                            check(received.text == text && received.format == DocumentFormat.Markdown) {
                                "QR camera mismatch: fixture=$fixtureIndex palette=$paletteIndex scene=${scene.name}"
                            }
                            report("QR camera $description millis=$millis passed")
                        }
                    } finally {
                        qr.recycle()
                    }
                }
            }
        }
    }
    check(failures.isEmpty()) { "QR camera failures: ${failures.joinToString()}" }
}

private data class CameraScene(
    val name: String,
    val angle: Float = 0f,
    val horizontalPosition: Float = 0.5f,
    val topInset: Float = 0f,
    val background: Int = 245,
    val shadow: Boolean = false,
    val inverted: Boolean = false
)

/** Projects the production bitmap into a larger frame, preserving its complete quiet zone. */
private fun cameraFrame(qr: Bitmap, side: Float, scene: CameraScene): QrLuminanceFrame {
    val radians = Math.toRadians(scene.angle.toDouble())
    val cosine = cos(radians).toFloat()
    val sine = sin(radians).toFloat()
    val bounds = side * (abs(cosine) + abs(sine))
    val centerX = bounds / 2 + (CAMERA_WIDTH - bounds) * scene.horizontalPosition
    val centerY = CAMERA_HEIGHT / 2f
    val corners = floatArrayOf(
        -side / 2 + scene.topInset * side, -side / 2,
        side / 2 - scene.topInset * side, -side / 2,
        side / 2, side / 2,
        -side / 2, side / 2
    )
    for (index in corners.indices step 2) {
        val x = corners[index]
        val y = corners[index + 1]
        corners[index] = centerX + x * cosine - y * sine
        corners[index + 1] = centerY + x * sine + y * cosine
    }
    val matrix = Matrix()
    check(matrix.setPolyToPoly(
        floatArrayOf(0f, 0f, qr.width.toFloat(), 0f, qr.width.toFloat(), qr.height.toFloat(), 0f, qr.height.toFloat()),
        0, corners, 0, 4
    ))
    val bitmap = Bitmap.createBitmap(CAMERA_WIDTH, CAMERA_HEIGHT, Bitmap.Config.ARGB_8888)
    try {
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.rgb(scene.background, scene.background, scene.background))
        canvas.drawBitmap(qr, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        val pixels = IntArray(CAMERA_WIDTH * CAMERA_HEIGHT)
        bitmap.getPixels(pixels, 0, CAMERA_WIDTH, 0, 0, CAMERA_WIDTH, CAMERA_HEIGHT)
        val bytes = ByteArray(pixels.size) { index ->
            val color = pixels[index]
            var luminance = (
                android.graphics.Color.red(color) * 77 +
                    android.graphics.Color.green(color) * 150 +
                    android.graphics.Color.blue(color) * 29
                ) shr 8
            if (scene.shadow) {
                val brightness = 30 + 70 * (index % CAMERA_WIDTH) / (CAMERA_WIDTH - 1)
                luminance = luminance * brightness / 100
            }
            if (scene.inverted) luminance = 255 - luminance
            luminance.toByte()
        }
        return QrLuminanceFrame(CAMERA_WIDTH, CAMERA_HEIGHT, bytes)
    } finally {
        bitmap.recycle()
    }
}
