/* Verifies provider failure boundaries for generated QR images. */
package dev.soupslurpr.beautyxt.document

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import dev.soupslurpr.beautyxt.exporting.client.StatelessExportTestSinks
import dev.soupslurpr.beautyxt.transfer.TransferProtocol
import dev.soupslurpr.beautyxt.transfer.client.IsolatedTransferProcessor
import dev.soupslurpr.beautyxt.ui.editor.QrCodeColors
import dev.soupslurpr.beautyxt.ui.editor.QrImageFormat
import dev.soupslurpr.beautyxt.ui.editor.renderExpressiveQrCodeBitmap
import dev.soupslurpr.beautyxt.ui.editor.saveExpressiveQrCodeImage
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

private const val PROVIDER_WAIT_MILLIS = 10_000L
private const val CANCELLATION_WAIT_MILLIS = 5_000L
private const val STATUS_POLL_MILLIS = 25L
private const val BLOCKED_OPERATION_TOKEN = "00000000000000000000000000004000"

/** Cancels an image export without waiting for a stalled provider to resume. */
internal fun verifyQrImageExportCancellation(context: Context, testPackage: String) = runBlocking {
    QrImageFormat.entries.forEach { format ->
        verifyQrImageExportCancellation(context, testPackage, format)
    }
}

private suspend fun verifyQrImageExportCancellation(
    context: Context,
    testPackage: String,
    format: QrImageFormat
) {
    val resolver = context.contentResolver
    val random = Random(0)
    val text = buildString {
        repeat(TransferProtocol.MAX_QR_TEXT_BYTES.toInt()) {
            append(random.nextInt('!'.code, '~'.code + 1).toChar())
        }
    }
    val grid = RustDocument.createEmpty().use { document ->
        val metrics = document.replace(0L, Utf16Range(0L, 0L), text)
        document.captureSnapshot(metrics.revision).use { snapshot ->
            IsolatedTransferProcessor(context).encodeQr(
                snapshot,
                metrics.serializedByteLength,
                DocumentFormat.PlainText
            )
        }
    }
    val colors = QrCodeColors(Color.Black, Color.White)
    val bitmap = renderExpressiveQrCodeBitmap(grid, colors)
    try {
        val output = ByteArrayOutputStream()
        val encoder = when (format) {
            QrImageFormat.WebP -> Bitmap.CompressFormat.WEBP_LOSSLESS
            QrImageFormat.Png -> Bitmap.CompressFormat.PNG
        }
        check(bitmap.compress(encoder, 100, output))
        check(output.size() > StatelessExportTestSinks.blockedPipeBytes + 1) {
            "QR image fixture does not fill the blocked pipe"
        }
    } finally {
        bitmap.recycle()
    }
    StatelessExportTestSinks.reset(resolver, testPackage, BLOCKED_OPERATION_TOKEN)
    supervisorScope {
        val operation = async(Dispatchers.IO) {
            saveExpressiveQrCodeImage(
                context = context,
                destination = StatelessExportTestSinks.blocked(
                    testPackage,
                    BLOCKED_OPERATION_TOKEN
                ),
                grid = grid,
                colors = colors,
                format = format
            )
        }
        val cancelledBeforeRelease = try {
            withTimeout(PROVIDER_WAIT_MILLIS) {
                while (!StatelessExportTestSinks.status(
                        resolver,
                        testPackage,
                        BLOCKED_OPERATION_TOKEN
                    ).paused
                ) {
                    delay(STATUS_POLL_MILLIS)
                }
            }
            check(operation.isActive) { "QR image export did not reach its blocked write" }
            check(
                StatelessExportTestSinks.status(
                    resolver,
                    testPackage,
                    BLOCKED_OPERATION_TOKEN
                ).byteCount == 1L
            ) { "QR image provider did not observe the first byte" }
            operation.cancel()
            withTimeoutOrNull(CANCELLATION_WAIT_MILLIS) {
                operation.join()
                true
            } ?: false
        } finally {
            StatelessExportTestSinks.releasePaused(resolver, testPackage, BLOCKED_OPERATION_TOKEN)
            operation.cancel()
            operation.join()
        }
        check(cancelledBeforeRelease) { "QR image cancellation waited for the blocked provider" }
    }
}
