package dev.soupslurpr.beautyxt.document

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.text.PositionedGlyphs
import android.graphics.text.TextRunShaper
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.StyleSpan
import android.util.Log
import dev.soupslurpr.beautyxt.printing.NativePrintFont
import dev.soupslurpr.beautyxt.printing.PRINT_OUTLINE_EM_SIZE
import dev.soupslurpr.beautyxt.printing.printGlyphPath
import java.io.File

/** Compares Android layout against its positioned glyphs rendered from bounded font outlines. */
internal fun verifyPrintFontOutlines(context: Context) {
    val samples = listOf(
        "A café with e\u0301 and ffi",
        "العربية — السلام عليكم",
        "שלום עולם — 123",
        "日本語 Ελληνικά 中文",
        "नमस्ते दुनिया",
        "👩‍💻 family 👨‍👩‍👧‍👦 🟧",
        "Bold, italic, and a variable font",
        "太字 العربية नमस्ते"
    )
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 38f
        typeface = Typeface.DEFAULT
    }
    val bitmap = Bitmap.createBitmap(1_360, samples.size * 150 + 80, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(Color.WHITE)
    val native = Canvas(bitmap)
    val outlined = OutlineProbeCanvas(bitmap)
    native.drawText("Android", 24f, 48f, paint)
    native.drawText("Positioned font outlines", 704f, 48f, paint)
    for ((index, sample) in samples.withIndex()) {
        val text = SpannableString(sample)
        if (index >= 6) {
            text.setSpan(
                StyleSpan(Typeface.BOLD_ITALIC),
                0,
                text.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, 620)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
        for ((canvas, left) in listOf(native to 24f, outlined to 704f)) {
            val saved = canvas.save()
            canvas.translate(left, 90f + index * 150)
            layout.draw(canvas)
            canvas.restoreToCount(saved)
        }
    }
    check(outlined.outlineCount > 100) { "font probe did not capture Android text runs" }
    check(outlined.bitmapCount > 0) { "emoji did not request native bitmap fallback" }
    val root = File(checkNotNull(context.getExternalFilesDir(null)), "print-outline-verification")
    check(root.mkdirs() || root.isDirectory)
    File(root, "font-outlines.webp").outputStream().use {
        check(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, it))
    }
    bitmap.recycle()
    Log.i("PrintFontOutline", "outlined=${outlined.outlineCount} bitmap=${outlined.bitmapCount}")
}

private class OutlineProbeCanvas(bitmap: Bitmap) : Canvas(bitmap) {
    var outlineCount = 0
    var bitmapCount = 0

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

    private fun drawPositioned(glyphs: PositionedGlyphs, x: Float, y: Float, paint: Paint) {
        val origin = x - when (paint.textAlign) {
            Paint.Align.RIGHT -> glyphs.advance
            Paint.Align.CENTER -> glyphs.advance / 2
            else -> 0f
        }
        for (index in 0 until glyphs.glyphCount()) {
            val font = glyphs.getFont(index)
            val id = glyphs.getGlyphId(index)
            val outline = NativePrintFont.glyphOutline(
                font,
                id,
                glyphs.getWeightOverride(index),
                glyphs.getItalicOverride(index)
            )
            val glyphPaint = Paint(paint).apply {
                isFakeBoldText = paint.isFakeBoldText || glyphs.getFakeBold(index)
                textSkewX = paint.textSkewX + if (glyphs.getFakeItalic(index)) -0.25f else 0f
            }
            val glyphX = origin + glyphs.getGlyphX(index)
            val glyphY = y + glyphs.getGlyphY(index)
            if (outline == null || glyphPaint.isFakeBoldText) {
                bitmapCount++
                drawGlyphs(intArrayOf(id), 0, floatArrayOf(glyphX, glyphY), 0, 1, font, glyphPaint)
            } else {
                outlineCount++
                val saved = save()
                translate(glyphX, glyphY)
                scale(
                    paint.textSize * paint.textScaleX / PRINT_OUTLINE_EM_SIZE,
                    paint.textSize / PRINT_OUTLINE_EM_SIZE
                )
                skew(glyphPaint.textSkewX, 0f)
                drawPath(printGlyphPath(outline), glyphPaint)
                restoreToCount(saved)
            }
        }
    }
}
