/* Memory-only lossless WebP and PNG export for rendered diagrams and formulas. */
package dev.soupslurpr.beautyxt.ui.editor

import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.core.graphics.createBitmap
import dev.soupslurpr.beautyxt.exporting.client.IsolatedDocumentExporter
import dev.soupslurpr.beautyxt.exporting.client.TransientDestinationSelection
import dev.soupslurpr.beautyxt.illustration.IllustrationColors
import dev.soupslurpr.beautyxt.illustration.IllustrationDrawing
import dev.soupslurpr.beautyxt.illustration.IllustrationKind
import dev.soupslurpr.beautyxt.illustration.NativeIllustration
import dev.soupslurpr.beautyxt.importing.client.recognizedDocumentFilenameStem
import dev.soupslurpr.beautyxt.importing.client.sanitizeSelectedDocumentDisplayName
import dev.soupslurpr.beautyxt.ipc.SealedInput
import dev.soupslurpr.beautyxt.sharing.ExcerptShareLease
import dev.soupslurpr.beautyxt.sharing.ExcerptShares
import java.io.IOException
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private const val MAX_ILLUSTRATION_IMAGE_EDGE_PX = 2_048
private const val ILLUSTRATION_EXPORT_TARGET_PX_PER_EM = 48f
private const val ILLUSTRATION_EXPORT_PADDING_PX = 32
private const val MAX_ILLUSTRATION_IMAGE_BYTES = 16L * 1_024L * 1_024L
private const val ILLUSTRATION_LOSSLESS_EFFORT = 100

/** Suggests a safe filename for saving or sharing a rendered diagram or formula image. */
internal fun suggestIllustrationImageName(
    documentTitle: String,
    kind: IllustrationKind,
    format: QrImageFormat
): String {
    val suffix = if (kind == IllustrationKind.Diagram) "diagram" else "formula"
    val fallback = "BeauTyXT $suffix.${format.extension}"
    val stem = recognizedDocumentFilenameStem(documentTitle) ?: return fallback
    val candidate = "$stem $suffix.${format.extension}"
    return candidate.takeIf { sanitizeSelectedDocumentDisplayName(it) == it } ?: fallback
}

/** Computes bounded bitmap pixel dimensions and vector scale for exporting one illustration. */
internal data class IllustrationRasterGeometry(
    val widthPx: Int,
    val heightPx: Int,
    val paddingPx: Int,
    val scale: Float
)

internal fun illustrationRasterGeometry(drawing: NativeIllustration): IllustrationRasterGeometry {
    require(drawing.width > 0f && drawing.height > 0f) { "illustration dimensions must be positive" }
    val usableEdge = (MAX_ILLUSTRATION_IMAGE_EDGE_PX - ILLUSTRATION_EXPORT_PADDING_PX * 2)
        .coerceAtLeast(64)
        .toFloat()
    val scale = minOf(
        ILLUSTRATION_EXPORT_TARGET_PX_PER_EM,
        usableEdge / drawing.width,
        usableEdge / drawing.height
    ).coerceAtLeast(0.01f)
    val contentW = ceil(drawing.width * scale).toInt().coerceAtLeast(1)
    val contentH = ceil(drawing.height * scale).toInt().coerceAtLeast(1)
    val widthPx = (contentW + ILLUSTRATION_EXPORT_PADDING_PX * 2)
        .coerceAtMost(MAX_ILLUSTRATION_IMAGE_EDGE_PX)
    val heightPx = (contentH + ILLUSTRATION_EXPORT_PADDING_PX * 2)
        .coerceAtMost(MAX_ILLUSTRATION_IMAGE_EDGE_PX)
    return IllustrationRasterGeometry(
        widthPx = widthPx,
        heightPx = heightPx,
        paddingPx = ILLUSTRATION_EXPORT_PADDING_PX,
        scale = scale
    )
}

/** Renders one vector illustration onto a bounded opaque bitmap and seals lossless bytes in RAM. */
internal fun sealIllustrationImage(
    drawing: NativeIllustration,
    colors: IllustrationColors,
    format: QrImageFormat
): SealedInput {
    val geom = illustrationRasterGeometry(drawing)
    val bitmap = createBitmap(geom.widthPx, geom.heightPx, Bitmap.Config.ARGB_8888)
    return try {
        val canvas = Canvas(bitmap)
        canvas.drawColor(colors.surface)
        IllustrationDrawing(drawing).draw(
            canvas = canvas,
            x = geom.paddingPx.toFloat(),
            y = geom.paddingPx.toFloat(),
            scale = geom.scale,
            colors = colors
        )
        SealedInput.fromStream(MAX_ILLUSTRATION_IMAGE_BYTES) { output ->
            val compressFormat = when (format) {
                QrImageFormat.WebP -> Bitmap.CompressFormat.WEBP_LOSSLESS
                QrImageFormat.Png -> Bitmap.CompressFormat.PNG
            }
            if (!bitmap.compress(compressFormat, ILLUSTRATION_LOSSLESS_EFFORT, output)) {
                throw IOException("could not encode illustration image")
            }
        }
    } finally {
        bitmap.recycle()
    }
}

/** Saves a rendered diagram or formula as lossless WebP or PNG via the isolated exporter. */
internal suspend fun saveIllustrationImage(
    context: Context,
    destination: Uri,
    drawing: NativeIllustration,
    colors: IllustrationColors,
    format: QrImageFormat,
    resolveForbiddenSource: suspend () -> SourceIdentitySnapshot = { SourceIdentitySnapshot() }
) {
    if (destination.scheme != ContentResolver.SCHEME_CONTENT || destination.authority.isNullOrBlank()) {
        throw IOException("illustration destination must use an authoritative content URI")
    }
    withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val exporter = IsolatedDocumentExporter(context.applicationContext)
        TransientDestinationSelection.from(destination).use { selection ->
            exporter.openDestination(selection).use { output ->
                val destinationKeys = writableDocumentKeysForDescriptor(
                    context = context.applicationContext,
                    uri = destination,
                    descriptorIdentityKey = output.fileIdentityKey
                )
                // Recheck live source identity (fail-closed) and refresh active leases AFTER open.
                val forbiddenSource = resolveForbiddenSource()
                val emptyBeforeAcquire = output.isCurrentlyEmptyRegularFile()
                if (
                    forbiddenSource.conflictsWith(
                        destinationKeys = destinationKeys,
                        destinationHasRegularInode = output.fileIdentityKey != null,
                        destinationIsEmpty = emptyBeforeAcquire
                    )
                ) {
                    throw IOException("illustration destination matches the current document source")
                }
                val destinationLease = WritableDocumentRegistry.shared.tryAcquireRefreshing(
                    documentKeys = destinationKeys,
                    destinationIsEmpty = emptyBeforeAcquire
                )
                    ?: throw IOException("illustration destination is already open for writing")
                val leaseRef = java.util.concurrent.atomic.AtomicReference<AutoCloseable?>(destinationLease)
                try {
                    sealIllustrationImage(drawing, colors, format).use { sealed ->
                        currentCoroutineContext().ensureActive()
                        if (emptyBeforeAcquire && !output.isCurrentlyEmptyRegularFile()) {
                            throw IOException("illustration destination changed before writing")
                        }
                        exporter.save(
                            destination = output,
                            input = sealed,
                            releaseLeaseOnUncertainExit = { leaseRef.getAndSet(null) }
                        )
                    }
                } finally {
                    leaseRef.getAndSet(null)?.close()
                }
            }
        }
    }
}

/** Prepared memory-only illustration share payload paired with its newly allocated lease. */
internal data class PreparedIllustrationShare(
    val intent: Intent,
    val lease: ExcerptShareLease
)

/** Prepares a memory-only ExcerptProvider lease and Android share Intent, closing on cancel. */
internal suspend fun prepareIllustrationImageShare(
    context: Context,
    documentTitle: String,
    kind: IllustrationKind,
    drawing: NativeIllustration,
    colors: IllustrationColors,
    format: QrImageFormat,
    onLeaseAllocated: (ExcerptShareLease) -> Unit
): PreparedIllustrationShare = withContext(Dispatchers.IO) {
    currentCoroutineContext().ensureActive()
    val name = suggestIllustrationImageName(documentTitle, kind, format)
    var unclaimedSealed: SealedInput? = sealIllustrationImage(drawing, colors, format)
    var unclaimedLease: ExcerptShareLease? = null
    try {
        currentCoroutineContext().ensureActive()
        val sealed = checkNotNull(unclaimedSealed)
        unclaimedLease = ExcerptShares.retainIllustration(context.applicationContext, name, format.mimeType, sealed)
        unclaimedSealed = null
        val lease = checkNotNull(unclaimedLease)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = format.mimeType
            putExtra(Intent.EXTRA_STREAM, lease.uri)
            clipData = ClipData.newRawUri(name, lease.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // Hand off ownership before clearing unclaimedLease so cancellation during withContext
        // resumption back to Main can never strand the retained SealedInput in ExcerptShares.
        onLeaseAllocated(lease)
        unclaimedLease = null
        PreparedIllustrationShare(intent = send, lease = lease)
    } finally {
        unclaimedLease?.close()
        unclaimedSealed?.close()
    }
}
