package dev.soupslurpr.beautyxt.printing

import android.graphics.Bitmap
import android.graphics.RectF
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

private const val MAXIMUM_PAGE_DRAW_BYTES = 8 * 1_024 * 1_024
private const val MAXIMUM_PAGE_OUTLINE_BYTES = 8 * 1_024 * 1_024
private const val MAXIMUM_PAGE_BITMAP_PIXELS = 4_000_000L
private const val MAXIMUM_PAGE_VISUAL_RESOURCES = 8_192

/** Page-local visual resources carry no source strings or executable document content. */
internal sealed interface PdfVisualResource {
    data class Outline(val commands: ByteArray, val bounds: RectF) : PdfVisualResource

    data class Image(val bitmap: Bitmap) : PdfVisualResource
}

/** Retains at most one bounded page's drawing program, outlines, and localized bitmap fallbacks. */
internal class PdfVectorPage(val monochrome: Boolean) : AutoCloseable {
    private val drawing = ByteArrayOutputStream()
    private val mutableResources = mutableListOf<PdfVisualResource>()
    private var outlineBytes = 0
    private var bitmapPixels = 0L
    private var closed = false

    val resources: List<PdfVisualResource> get() = mutableResources
    val alphaValues = linkedSetOf<Int>()

    fun command(value: String) {
        check(!closed)
        val bytes = value.toByteArray(Charsets.US_ASCII)
        if (bytes.size > MAXIMUM_PAGE_DRAW_BYTES - drawing.size()) {
            throw IOException("printed page exceeds its drawing limit")
        }
        drawing.write(bytes)
    }

    fun addOutline(components: FloatArray): Int {
        checkResourceCapacity()
        val path = printGlyphPath(components)
        val bounds = RectF()
        path.computeBounds(bounds, true)
        val commands = pdfOutlineCommands(components).toByteArray(Charsets.US_ASCII)
        if (commands.size > MAXIMUM_PAGE_OUTLINE_BYTES - outlineBytes) {
            throw IOException("printed page exceeds its outline limit")
        }
        outlineBytes += commands.size
        mutableResources.add(PdfVisualResource.Outline(commands, bounds))
        return mutableResources.lastIndex
    }

    /** Reserves before allocation; one failed or cancelled page is discarded in its entirety. */
    fun reserveBitmap(width: Int, height: Int) {
        checkResourceCapacity()
        require(width > 0 && height > 0)
        val pixels = width.toLong() * height
        if (pixels > MAXIMUM_PAGE_BITMAP_PIXELS - bitmapPixels) {
            throw IOException("printed page exceeds its bitmap fallback limit")
        }
        bitmapPixels += pixels
    }

    fun addReservedBitmap(bitmap: Bitmap): Int {
        checkResourceCapacity()
        mutableResources.add(PdfVisualResource.Image(bitmap))
        return mutableResources.lastIndex
    }

    fun writeDrawing(destination: OutputStream) {
        check(!closed)
        drawing.writeTo(destination)
    }

    override fun close() {
        if (closed) return
        closed = true
        mutableResources.forEach { if (it is PdfVisualResource.Image) it.bitmap.recycle() }
        mutableResources.clear()
        drawing.reset()
    }

    private fun checkResourceCapacity() {
        check(!closed)
        if (mutableResources.size >= MAXIMUM_PAGE_VISUAL_RESOURCES) {
            throw IOException("printed page exceeds its visual resource limit")
        }
    }
}

/** Converts validated quadratic and cubic contours into PDF paths without flattening curves. */
internal fun pdfOutlineCommands(
    components: FloatArray,
    offsetX: Float = 0f,
    offsetY: Float = 0f,
    scale: Float = 1f,
    evenOdd: Boolean = false,
    clip: Boolean = false
): String = buildString {
    var index = 0
    var x = 0f
    var y = 0f
    var startX = 0f
    var startY = 0f
    fun coordinate() = components[index++]
    fun point(px: Float, py: Float) {
        append(pdfCoordinate(offsetX + px * scale)).append(' ')
        append(pdfCoordinate(offsetY + py * scale)).append(' ')
    }
    while (index < components.size) {
        when (coordinate()) {
            0f -> {
                x = coordinate()
                y = coordinate()
                startX = x
                startY = y
                point(x, y)
                append("m\n")
            }

            1f -> {
                x = coordinate()
                y = coordinate()
                point(x, y)
                append("l\n")
            }

            2f -> {
                val cx = coordinate()
                val cy = coordinate()
                val endX = coordinate()
                val endY = coordinate()
                point(x + (cx - x) * (2f / 3f), y + (cy - y) * (2f / 3f))
                point(endX + (cx - endX) * (2f / 3f), endY + (cy - endY) * (2f / 3f))
                point(endX, endY)
                append("c\n")
                x = endX
                y = endY
            }

            3f -> {
                point(coordinate(), coordinate())
                point(coordinate(), coordinate())
                x = coordinate()
                y = coordinate()
                point(x, y)
                append("c\n")
            }

            4f -> {
                append("h\n")
                x = startX
                y = startY
            }

            else -> error("unvalidated print glyph command")
        }
    }
    append(
        if (clip) {
            if (evenOdd) "W* n\n" else "W n\n"
        } else {
            if (evenOdd) "f*\n" else "f\n"
        }
    )
}
