package dev.soupslurpr.beautyxt.printing

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.fonts.Font
import android.graphics.fonts.FontVariationAxis
import android.graphics.text.MeasuredText
import android.graphics.text.PositionedGlyphs
import android.graphics.text.TextRunShaper
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextShaper
import androidx.core.graphics.createBitmap
import dev.soupslurpr.beautyxt.illustration.IllustrationColors
import dev.soupslurpr.beautyxt.illustration.NativeIllustration
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.floor

private const val MAXIMUM_PRINT_CANVAS_DEPTH = 64

private data class PrintCanvasState(val x: Float, val y: Float, val clip: RectF)

private data class PrintFontInstance(val font: Font, val weight: Float, val italic: Float)

private data class PrintOutlineKey(val font: Font, val glyph: Int)

/**
 * Records the text, translation, rectangular clipping, and rules used by our print layouts.
 * Android still performs shaping and decorations; no document HTML, CSS, or font program runs here.
 * Unsupported path/layout operations fail rather than silently disappearing from the output.
 */
internal class VectorPrintCanvas(
    private val page: PdfVectorPage,
    private val pageWidth: Int,
    private val pageHeight: Int,
    private val checkCancellation: () -> Unit
) : Canvas() {
    private val states = mutableListOf(
        PrintCanvasState(0f, 0f, RectF(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat()))
    )
    private val outlines = HashMap<PrintOutlineKey, Int?>()
    private val fonts = HashMap<PrintFontInstance, Font>()
    private val state get() = states.last()

    /** Explicit closed path entry point; arbitrary Android Canvas paths remain unsupported. */
    fun drawIllustration(
        drawing: NativeIllustration,
        x: Float,
        y: Float,
        scale: Float,
        colors: IllustrationColors
    ) {
        require(x.isFinite() && y.isFinite() && scale.isFinite() && scale > 0)
        val paint = Paint()
        drawing.paths.forEach { path ->
            checkCancellation()
            page.command("q\n")
            path.clips.forEach { clip ->
                page.command(
                    pdfOutlineCommands(
                        clip.components,
                        x + state.x,
                        y + state.y,
                        scale,
                        clip.evenOdd,
                        clip = true
                    )
                )
            }
            paint.color = colors.resolve(path.color)
            usePaint(paint)
            page.command(
                pdfOutlineCommands(path.components, x + state.x, y + state.y, scale, path.evenOdd)
            )
            page.command("Q\n")
        }
    }

    override fun getWidth() = pageWidth
    override fun getHeight() = pageHeight
    override fun getSaveCount() = states.size

    override fun save(): Int {
        checkCancellation()
        check(states.size < MAXIMUM_PRINT_CANVAS_DEPTH) { "print drawing stack is too deep" }
        val count = states.size
        states.add(state.copy(clip = RectF(state.clip)))
        page.command("q\n")
        return count
    }

    override fun restore() {
        check(states.size > 1) { "unbalanced print drawing restore" }
        states.removeAt(states.lastIndex)
        page.command("Q\n")
    }

    override fun restoreToCount(saveCount: Int) {
        require(saveCount in 1..states.size)
        while (states.size > saveCount) restore()
    }

    override fun translate(dx: Float, dy: Float) {
        require(dx.isFinite() && dy.isFinite())
        // Flatten layout translations into page coordinates. Nested PDF matrix multiplication
        // otherwise makes rasterized text depend on where an input fragment happened to split.
        states[states.lastIndex] = state.copy(x = state.x + dx, y = state.y + dy)
    }

    override fun getClipBounds(bounds: Rect): Boolean {
        if (state.clip.isEmpty) {
            bounds.setEmpty()
            return false
        }
        val local = RectF(state.clip)
        local.offset(-state.x, -state.y)
        local.roundOut(bounds)
        return !bounds.isEmpty
    }

    override fun clipRect(left: Float, top: Float, right: Float, bottom: Float): Boolean {
        val next = RectF(left + state.x, top + state.y, right + state.x, bottom + state.y)
        if (!state.clip.intersect(next)) state.clip.setEmpty()
        rectangle(left, top, (right - left).coerceAtLeast(0f), (bottom - top).coerceAtLeast(0f))
        page.command("W n\n")
        return !state.clip.isEmpty
    }

    override fun clipRect(left: Int, top: Int, right: Int, bottom: Int) =
        clipRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

    override fun clipRect(rect: Rect) = clipRect(rect.left, rect.top, rect.right, rect.bottom)
    override fun clipRect(rect: RectF) = clipRect(rect.left, rect.top, rect.right, rect.bottom)

    override fun quickReject(left: Float, top: Float, right: Float, bottom: Float) =
        !RectF.intersects(
            state.clip,
            RectF(left + state.x, top + state.y, right + state.x, bottom + state.y)
        )

    override fun quickReject(rect: RectF) =
        quickReject(rect.left, rect.top, rect.right, rect.bottom)

    override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
        if (left >= right || top >= bottom || paint.alpha == 0) return
        usePaint(paint)
        rectangle(left, top, right - left, bottom - top)
        page.command(
            when (paint.style) {
                Paint.Style.STROKE -> "S\n"
                Paint.Style.FILL_AND_STROKE -> "B\n"
                else -> "f\n"
            }
        )
    }

    override fun drawRect(rect: Rect, paint: Paint) = drawRect(
        rect.left.toFloat(),
        rect.top.toFloat(),
        rect.right.toFloat(),
        rect.bottom.toFloat(),
        paint
    )

    override fun drawRect(rect: RectF, paint: Paint) =
        drawRect(rect.left, rect.top, rect.right, rect.bottom, paint)

    override fun drawLine(startX: Float, startY: Float, stopX: Float, stopY: Float, paint: Paint) {
        if (paint.alpha == 0) return
        usePaint(paint)
        page.command(
            "${pdfCoordinate(startX + state.x)} ${pdfCoordinate(startY + state.y)} m " +
                "${pdfCoordinate(stopX + state.x)} ${pdfCoordinate(stopY + state.y)} l S\n"
        )
    }

    override fun drawTextRun(
        text: CharArray,
        index: Int,
        count: Int,
        contextIndex: Int,
        contextCount: Int,
        x: Float,
        y: Float,
        isRtl: Boolean,
        paint: Paint
    ) = drawPositioned(
        TextRunShaper.shapeTextRun(
            text, index, count, contextIndex, contextCount, 0f, 0f, isRtl, paint
        ),
        x,
        y,
        paint
    )

    override fun drawTextRun(
        text: CharSequence,
        start: Int,
        end: Int,
        contextStart: Int,
        contextEnd: Int,
        x: Float,
        y: Float,
        isRtl: Boolean,
        paint: Paint
    ) = drawPositioned(
        TextRunShaper.shapeTextRun(
            text, start, end - start, contextStart, contextEnd - contextStart,
            0f, 0f, isRtl, paint
        ),
        x,
        y,
        paint
    )

    override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
        val direction = TextDirectionHeuristics.FIRSTSTRONG_LTR
        val advance = paint.measureText(text)
        val left = x - textAlignmentOffset(paint, advance)
        val origin = left + if (direction.isRtl(text, 0, text.length)) advance else 0f
        val textPaint = TextPaint(paint).apply { textAlign = Paint.Align.LEFT }
        TextShaper.shapeText(text, 0, text.length, direction, textPaint) { _, _, glyphs, runPaint ->
            drawPositioned(glyphs, origin, y, runPaint)
        }
    }

    override fun drawText(text: String, start: Int, end: Int, x: Float, y: Float, paint: Paint) =
        drawText(text.substring(start, end), x, y, paint)

    override fun drawText(
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        y: Float,
        paint: Paint
    ) = drawText(text.subSequence(start, end).toString(), x, y, paint)

    override fun drawText(
        text: CharArray,
        index: Int,
        count: Int,
        x: Float,
        y: Float,
        paint: Paint
    ) = drawText(String(text, index, count), x, y, paint)

    override fun drawTextRun(
        text: MeasuredText,
        start: Int,
        end: Int,
        contextStart: Int,
        contextEnd: Int,
        x: Float,
        y: Float,
        isRtl: Boolean,
        paint: Paint
    ): Unit = unsupported()

    override fun drawPath(path: Path, paint: Paint): Unit = unsupported()
    override fun scale(sx: Float, sy: Float): Unit = unsupported()
    override fun skew(sx: Float, sy: Float): Unit = unsupported()
    override fun rotate(degrees: Float): Unit = unsupported()

    private fun drawPositioned(glyphs: PositionedGlyphs, x: Float, y: Float, paint: Paint) {
        checkCancellation()
        if (paint.alpha == 0 || state.clip.isEmpty) return
        usePaint(paint)
        val origin = x - textAlignmentOffset(paint, glyphs.advance)
        for (index in 0 until glyphs.glyphCount()) {
            if (index % 128 == 0) checkCancellation()
            val instance = PrintFontInstance(
                glyphs.getFont(index),
                glyphs.getWeightOverride(index),
                glyphs.getItalicOverride(index)
            )
            val font = fonts.getOrPut(instance) {
                if (fonts.size >= 128) throw IOException("printed page exceeds its font limit")
                effectivePrintFont(instance)
            }
            val key = PrintOutlineKey(font, glyphs.getGlyphId(index))
            val glyphPaint = Paint(paint).apply {
                isFakeBoldText = paint.isFakeBoldText || glyphs.getFakeBold(index)
                textSkewX = paint.textSkewX + if (glyphs.getFakeItalic(index)) -0.25f else 0f
            }
            val gx = origin + glyphs.getGlyphX(index)
            val gy = y + glyphs.getGlyphY(index)
            val bounds = RectF()
            font.getGlyphBounds(key.glyph, glyphPaint, bounds)
            if (bounds.isEmpty) continue
            bounds.offset(gx + state.x, gy + state.y)
            bounds.inset(-1f, -1f)
            val visible = RectF(bounds)
            if (!visible.intersect(state.clip)) continue
            val clipped = !state.clip.contains(bounds)
            val resource = if (!clipped && !glyphPaint.isFakeBoldText) {
                if (outlines.containsKey(key)) {
                    outlines[key]
                } else {
                    NativePrintFont.glyphOutline(key.font, key.glyph)
                        ?.let(page::addOutline).also { outlines[key] = it }
                }
            } else {
                null
            }
            if (resource == null) {
                drawBitmapGlyph(key.glyph, font, glyphPaint, gx, gy, visible)
            } else {
                val sx = paint.textSize * paint.textScaleX / PRINT_OUTLINE_EM_SIZE
                val sy = paint.textSize / PRINT_OUTLINE_EM_SIZE
                val skew = paint.textSize * glyphPaint.textSkewX / PRINT_OUTLINE_EM_SIZE
                page.command(
                    "q ${pdfMatrixComponent(sx)} 0 ${pdfMatrixComponent(skew)} " +
                        "${pdfMatrixComponent(sy)} " +
                        "${pdfCoordinate(gx + state.x)} ${pdfCoordinate(gy + state.y)} " +
                        "cm /V$resource Do Q\n"
                )
            }
        }
    }

    /** Stores only visible pixels for clipped glyphs, not recoverable off-page vector geometry. */
    private fun drawBitmapGlyph(
        glyph: Int,
        font: Font,
        paint: Paint,
        x: Float,
        y: Float,
        visible: RectF
    ) {
        val left = floor(visible.left).toInt()
        val top = floor(visible.top).toInt()
        val width = ceil(visible.right).toInt() - left
        val height = ceil(visible.bottom).toInt() - top
        page.reserveBitmap(width, height)
        val bitmap = createBitmap(width, height)
        try {
            val canvas = Canvas(bitmap)
            canvas.clipRect(
                visible.left - left,
                visible.top - top,
                visible.right - left,
                visible.bottom - top
            )
            canvas.drawGlyphs(
                intArrayOf(glyph),
                0,
                floatArrayOf(x + state.x - left, y + state.y - top),
                0,
                1,
                font,
                Paint(paint).apply { alpha = 255 }
            )
            val resource = page.addReservedBitmap(bitmap)
            // PDF image samples run top-down, while an image XObject's unit square is y-up.
            page.command(
                "q $width 0 0 -$height $left ${top + height} cm /V$resource Do Q\n"
            )
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }

    private fun usePaint(paint: Paint) {
        check(paint.shader == null && paint.colorFilter == null && paint.pathEffect == null) {
            "unsupported print paint effect"
        }
        check(paint.blendMode == null || paint.blendMode == BlendMode.SRC_OVER) {
            "unsupported print blend mode"
        }
        val red = Color.red(paint.color) / 255f
        val green = Color.green(paint.color) / 255f
        val blue = Color.blue(paint.color) / 255f
        val color = if (page.monochrome) {
            val gray = pdfCoordinate((red * 77f + green * 150f + blue * 29f) / 256f)
            "$gray g $gray G"
        } else {
            val rgb = "${pdfCoordinate(red)} ${pdfCoordinate(green)} ${pdfCoordinate(blue)}"
            "$rgb rg $rgb RG"
        }
        page.alphaValues.add(paint.alpha)
        val cap = when (paint.strokeCap) {
            Paint.Cap.ROUND -> 1
            Paint.Cap.SQUARE -> 2
            else -> 0
        }
        val join = when (paint.strokeJoin) {
            Paint.Join.ROUND -> 1
            Paint.Join.BEVEL -> 2
            else -> 0
        }
        page.command(
            "$color /A${paint.alpha} gs ${pdfCoordinate(paint.strokeWidth)} w " +
                "$cap J $join j ${pdfCoordinate(paint.strokeMiter.coerceAtLeast(1f))} M\n"
        )
    }

    private fun rectangle(left: Float, top: Float, width: Float, height: Float) {
        page.command(
            "${pdfCoordinate(left + state.x)} ${pdfCoordinate(top + state.y)} " +
                "${pdfCoordinate(width)} ${pdfCoordinate(height)} re "
        )
    }

    private fun unsupported(): Nothing = throw IOException("unsupported vector print operation")
}

private fun textAlignmentOffset(paint: Paint, advance: Float): Float = when (paint.textAlign) {
    Paint.Align.RIGHT -> advance
    Paint.Align.CENTER -> advance / 2f
    else -> 0f
}

/** Resolves the exact variable instance for bounds and native bitmap fallback drawing. */
private fun effectivePrintFont(key: PrintFontInstance): Font {
    if (key.weight == PositionedGlyphs.NO_OVERRIDE && key.italic == PositionedGlyphs.NO_OVERRIDE) {
        return key.font
    }
    val axes = key.font.axes.orEmpty().toMutableList()
    for ((tag, value) in listOf("wght" to key.weight, "ital" to key.italic)) {
        if (value != PositionedGlyphs.NO_OVERRIDE) {
            axes.removeAll { it.tag == tag }
            axes.add(FontVariationAxis(tag, value))
        }
    }
    return Font.Builder(key.font).setFontVariationSettings(axes.toTypedArray()).build()
}
