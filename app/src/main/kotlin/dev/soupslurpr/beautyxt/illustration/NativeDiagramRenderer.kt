package dev.soupslurpr.beautyxt.illustration

import android.graphics.Typeface
import android.graphics.fonts.Font
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextShaper
import java.lang.ref.Reference
import java.nio.ByteBuffer

/** Only the diagram process loads this library; source never selects a font path. */
internal object NativeDiagramRenderer {
    init {
        System.loadLibrary("beautyxt_diagram_jni")
    }

    fun draw(source: ByteArray): ByteArray {
        check(source.size in 1..IllustrationLimits.MAX_DIAGRAM_SOURCE_BYTES)
        // System shaping selects only the platform fonts actually needed by this bounded source.
        // No font path or font program from the document is interpreted or opened.
        val text = "A " + source.toString(Charsets.UTF_8)
        val fonts = LinkedHashMap<String, Font>()
        for (style in listOf(Typeface.NORMAL, Typeface.BOLD, Typeface.ITALIC)) {
            val paint = TextPaint().apply {
                typeface = Typeface.create("sans-serif", style)
                textSize =
                    16f
            }
            TextShaper.shapeText(
                text,
                0,
                text.length,
                TextDirectionHeuristics.FIRSTSTRONG_LTR,
                paint
            ) {
                    _,
                    _,
                    glyphs,
                    _
                ->
                repeat(glyphs.glyphCount()) { index ->
                    val font = glyphs.getFont(index)
                    val file = checkNotNull(font.file) { "platform font has no system backing" }
                    if (file.path !in fonts) {
                        if (fonts.size >= 16) throw IllustrationResourceLimitException()
                        fonts[file.path] = font
                    }
                }
            }
        }
        var total = 0
        val buffers = fonts.values.map { font ->
            font.buffer.duplicate().apply {
                check(isDirect && isReadOnly)
                clear()
                if (remaining() !in 1..48 * 1024 * 1024 - total) {
                    throw IllustrationResourceLimitException()
                }
                total += remaining()
            }
        }
        return try {
            render(source, buffers.toTypedArray())
        } finally {
            Reference.reachabilityFence(fonts)
        }
    }

    @JvmStatic
    private external fun render(source: ByteArray, fonts: Array<ByteBuffer>): ByteArray
}
