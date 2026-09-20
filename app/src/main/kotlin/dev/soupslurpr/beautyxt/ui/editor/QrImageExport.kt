package dev.soupslurpr.beautyxt.ui.editor

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import dev.soupslurpr.beautyxt.exporting.client.IsolatedDocumentExporter
import dev.soupslurpr.beautyxt.exporting.client.TransientDestinationSelection
import dev.soupslurpr.beautyxt.importing.client.recognizedDocumentFilenameStem
import dev.soupslurpr.beautyxt.importing.client.sanitizeSelectedDocumentDisplayName
import dev.soupslurpr.beautyxt.ipc.SealedInput
import dev.soupslurpr.beautyxt.transfer.client.QrCodeGrid
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal const val EXPORTED_QR_CODE_SIDE_PIXELS = 1_024

private const val MAX_QR_IMAGE_BYTES = 8L * 1_024L * 1_024L
private const val LOSSLESS_COMPRESSION_EFFORT = 100
private const val NO_QR_IMAGE_GENERATION = -1L

/** Both export choices preserve the rendered QR pixels exactly. */
internal enum class QrImageFormat(val mimeType: String, val extension: String) {
    WebP("image/webp", "webp"),
    Png("image/png", "png")
}

/** Identifies the current user-visible QR image export phase. */
internal enum class QrImageSaveStatus {
    Idle,
    ChoosingDestination,
    Saving,
    Succeeded,
    Failed;

    /** Returns whether the destination or output operation currently owns the flow. */
    fun isActive(): Boolean = this == ChoosingDestination || this == Saving
}

/** Retains the phase and generation of one QR image export across configuration changes. */
internal data class QrImageExportState(
    val status: QrImageSaveStatus = QrImageSaveStatus.Idle,
    val generation: Long = NO_QR_IMAGE_GENERATION,
    val format: QrImageFormat = QrImageFormat.WebP
)

/** Returns one safe QR image name derived from a recognized document filename. */
internal fun suggestQrImageDestinationName(title: String, format: QrImageFormat): String {
    require(title.isNotBlank()) { "editor title must not be blank" }
    val fallback = "BeauTyXT QR code.${format.extension}"
    val stem = recognizedDocumentFilenameStem(title) ?: return fallback
    val suggestion = "$stem QR.${format.extension}"
    return suggestion.takeIf { candidate ->
        sanitizeSelectedDocumentDisplayName(candidate) == candidate
    } ?: fallback
}

/** Encodes one memory-only QR image before exporting through the isolated writer. */
internal suspend fun saveExpressiveQrCodeImage(
    context: Context,
    destination: Uri,
    grid: QrCodeGrid,
    colors: QrCodeColors,
    format: QrImageFormat
) {
    if (destination.scheme != ContentResolver.SCHEME_CONTENT ||
        destination.authority.isNullOrBlank()
    ) {
        throw IOException("QR image destination must use an authoritative content URI")
    }
    withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        prepareQrImage(grid, colors, format).use { input ->
            currentCoroutineContext().ensureActive()
            val exporter = IsolatedDocumentExporter(context)
            TransientDestinationSelection.from(destination).use { selection ->
                exporter.openDestination(selection).use { output ->
                    exporter.save(output, input)
                }
            }
        }
    }
}

/** Renders and seals one lossless image without allocating a second image-sized array. */
private fun prepareQrImage(
    grid: QrCodeGrid,
    colors: QrCodeColors,
    format: QrImageFormat
): SealedInput {
    val bitmap = renderExpressiveQrCodeBitmap(grid = grid, colors = colors)
    return try {
        SealedInput.fromStream(MAX_QR_IMAGE_BYTES) { output ->
            val encoder = when (format) {
                QrImageFormat.WebP -> Bitmap.CompressFormat.WEBP_LOSSLESS
                QrImageFormat.Png -> Bitmap.CompressFormat.PNG
            }
            // WebP interprets this as effort, not image quality. PNG ignores it.
            if (!bitmap.compress(encoder, LOSSLESS_COMPRESSION_EFFORT, output)) {
                throw IOException("could not encode QR image")
            }
        }
    } finally {
        bitmap.recycle()
    }
}
