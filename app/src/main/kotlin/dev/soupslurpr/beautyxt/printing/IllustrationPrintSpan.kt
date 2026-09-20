package dev.soupslurpr.beautyxt.printing

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.style.ReplacementSpan
import dev.soupslurpr.beautyxt.illustration.IllustrationColors
import dev.soupslurpr.beautyxt.illustration.IllustrationDrawing
import dev.soupslurpr.beautyxt.illustration.NativeIllustration
import kotlin.math.ceil
import kotlin.math.floor

/** Retains original TeX/Mermaid in the PDF text layer while drawing the same native appearance. */
internal class IllustrationPrintSpan(
    private val drawing: NativeIllustration,
    val source: String,
    private val maximumWidth: Float,
    private val maximumHeight: Float,
    private val surfaceColor: Int = Color.WHITE
) : ReplacementSpan() {
    private val appearance = IllustrationDrawing(drawing)

    init {
        require(maximumWidth.isFinite() && maximumWidth > 0)
        require(maximumHeight.isFinite() && maximumHeight > 0)
    }

    private fun scale(paint: Paint): Float =
        minOf(paint.textSize, maximumWidth / drawing.width, maximumHeight / drawing.height)

    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        val scale = scale(paint)
        fm?.let {
            val original = paint.fontMetricsInt
            it.ascent = minOf(original.ascent, floor(-drawing.baseline * scale).toInt())
            it.descent = maxOf(
                original.descent,
                ceil((drawing.height - drawing.baseline) * scale).toInt()
            )
            it.top = minOf(original.top, it.ascent)
            it.bottom = maxOf(original.bottom, it.descent)
            it.leading = original.leading
        }
        return ceil(drawing.width * scale).toInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        val scale = scale(paint)
        appearance.draw(
            canvas,
            x,
            y - drawing.baseline * scale,
            scale,
            IllustrationColors(paint.color, surfaceColor, PrintAccentColor, Color.LTGRAY)
        )
    }
}
