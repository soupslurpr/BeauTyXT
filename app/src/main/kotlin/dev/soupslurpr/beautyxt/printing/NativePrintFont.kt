package dev.soupslurpr.beautyxt.printing

import android.graphics.fonts.Font
import android.graphics.fonts.FontVariationAxis
import android.graphics.text.PositionedGlyphs
import java.lang.ref.Reference
import java.nio.ByteBuffer

/** Reads only Android-selected platform fonts; document-supplied fonts never enter this bridge. */
internal object NativePrintFont {
    init {
        System.loadLibrary("beautyxt_print_jni")
    }

    /** Returns a 1000-em, y-down outline, or null for a native color/bitmap glyph fallback. */
    fun glyphOutline(
        font: Font,
        glyphId: Int,
        weightOverride: Float = PositionedGlyphs.NO_OVERRIDE,
        italicOverride: Float = PositionedGlyphs.NO_OVERRIDE
    ): FloatArray? {
        val axes = font.axes.orEmpty().toMutableList()
        for ((tag, value) in listOf("wght" to weightOverride, "ital" to italicOverride)) {
            if (value != PositionedGlyphs.NO_OVERRIDE) {
                axes.removeAll { it.tag == tag }
                axes.add(FontVariationAxis(tag, value))
            }
        }
        val tags = IntArray(axes.size) { index ->
            axes[index].tag.fold(0) { value, character -> (value shl 8) or character.code }
        }
        val buffer = font.buffer
        check(buffer.isDirect && buffer.isReadOnly) {
            "platform print font is not a read-only mapping"
        }
        return try {
            outline(
                buffer,
                font.ttcIndex,
                glyphId,
                tags,
                FloatArray(axes.size) {
                    axes[it].styleValue
                }
            )
        } finally {
            Reference.reachabilityFence(font)
        }
    }

    @JvmStatic
    private external fun outline(
        fontBuffer: ByteBuffer,
        faceIndex: Int,
        glyphId: Int,
        axisTags: IntArray,
        axisValues: FloatArray
    ): FloatArray?
}
