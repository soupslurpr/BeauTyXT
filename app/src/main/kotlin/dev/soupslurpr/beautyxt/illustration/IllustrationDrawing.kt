package dev.soupslurpr.beautyxt.illustration

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import dev.soupslurpr.beautyxt.printing.VectorPrintCanvas

internal data class IllustrationColors(
    val ink: Int,
    val surface: Int,
    val accent: Int,
    val tone: Int
) {
    fun resolve(color: Int): Int = when (color) {
        0 -> ink
        1 -> surface
        2 -> accent
        3 -> tone
        else -> color
    }
}

/** Materializes only the visible illustration, never a whole document's Android paths. */
internal class IllustrationDrawing(private val drawing: NativeIllustration) {
    private val paths by lazy(LazyThreadSafetyMode.NONE) {
        drawing.paths.map { outline ->
            materialize(outline.components, outline.evenOdd) to
                outline.clips.map { materialize(it.components, it.evenOdd) }
        }
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Preview and printing consume exactly the same admitted filled-path appearance. */
    fun draw(canvas: Canvas, x: Float, y: Float, scale: Float, colors: IllustrationColors) {
        require(x.isFinite() && y.isFinite() && scale.isFinite() && scale > 0)
        val saved = canvas.save()
        try {
            // A compromised worker cannot draw outside the rectangle admitted by layout.
            canvas.clipRect(x, y, x + drawing.width * scale, y + drawing.height * scale)
            if (canvas is VectorPrintCanvas) {
                canvas.drawIllustration(drawing, x, y, scale, colors)
                return
            }
            canvas.translate(x, y)
            canvas.scale(scale, scale)
            paths.forEachIndexed { index, (path, clips) ->
                val pathSave = canvas.save()
                try {
                    clips.forEach { canvas.clipPath(it) }
                    paint.color = colors.resolve(drawing.paths[index].color)
                    canvas.drawPath(path, paint)
                } finally {
                    canvas.restoreToCount(pathSave)
                }
            }
        } finally {
            canvas.restoreToCount(saved)
        }
    }
}

private fun materialize(components: FloatArray, evenOdd: Boolean) = Path().apply {
    fillType = if (evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
    var index = 0
    fun next() = components[index++]
    while (index < components.size) {
        when (next()) {
            0f -> moveTo(next(), next())
            1f -> lineTo(next(), next())
            2f -> quadTo(next(), next(), next(), next())
            3f -> cubicTo(next(), next(), next(), next(), next(), next())
            4f -> close()
            else -> error("Unvalidated illustration path")
        }
    }
}

/** A TextCenter placeholder reserves both sides of a formula's actual baseline. */
internal data class FormulaPlaceholder(val height: Float, val top: Float)

/** A final pixel-space cap keeps even a valid but hostile packet inside Compose's layout range. */
internal data class IllustrationPreviewGeometry(
    val width: Float,
    val height: Float,
    val top: Float,
    val scale: Float
)

internal fun illustrationPreviewGeometry(
    drawing: NativeIllustration,
    fontSize: Float,
    ascent: Float,
    descent: Float,
    diagram: Boolean
): IllustrationPreviewGeometry {
    require(fontSize.isFinite() && fontSize > 0 && ascent.isFinite() && descent.isFinite())
    val limit = 4_096f
    val center = (ascent + descent) / 2f
    require(ascent < descent && kotlin.math.abs(center) < limit / 2f)
    var scale = minOf(fontSize, limit / drawing.width)
    if (diagram) {
        scale = minOf(scale, limit / drawing.height)
        return IllustrationPreviewGeometry(drawing.width * scale, drawing.height * scale, 0f, scale)
    }
    if (drawing.baseline > 0) {
        scale = minOf(scale, (limit / 2 - center) / drawing.baseline)
    }
    if (drawing.height > drawing.baseline) {
        scale = minOf(scale, (limit / 2 + center) / (drawing.height - drawing.baseline))
    }
    val placeholder = formulaPlaceholder(drawing, ascent / scale, descent / scale)
    return IllustrationPreviewGeometry(
        drawing.width * scale,
        placeholder.height * scale,
        placeholder.top * scale,
        scale
    )
}

internal fun formulaPlaceholder(
    drawing: NativeIllustration,
    ascentEm: Float,
    descentEm: Float
): FormulaPlaceholder {
    require(ascentEm.isFinite() && descentEm.isFinite() && ascentEm < descentEm)
    val center = (ascentEm + descentEm) / 2f
    val half = maxOf(drawing.baseline + center, drawing.height - drawing.baseline - center)
    return FormulaPlaceholder(2f * half, half - center - drawing.baseline)
}
