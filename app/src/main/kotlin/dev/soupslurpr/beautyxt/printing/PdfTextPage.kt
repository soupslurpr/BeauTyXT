package dev.soupslurpr.beautyxt.printing

import android.icu.text.BreakIterator
import android.text.Layout
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import java.io.IOException
import java.util.Locale
import kotlin.math.ceil

private const val BOUNDS_COMPONENTS = 4
private const val MAXIMUM_PAGE_TEXT_UNITS = 128 * 1_024

// Keep UTF-16 hex plus its delimiters below readers' 256-byte CMap-token buffers.
// Complete logical source is separately retained in the segment's ActualText.
private const val MAXIMUM_GLYPH_TEXT_UNITS = 63

/** Identifies the standard PDF structure role of one printed text segment. */
internal enum class PdfTextRole(val token: String) {
    Paragraph("P"),
    Heading1("H1"),
    Heading2("H2"),
    Heading3("H3"),
    Heading4("H4"),
    Heading5("H5"),
    Heading6("H6"),
    Code("Code")
}

/** Associates original Unicode text with its visible raster-space selection rectangle. */
internal data class PdfTextGlyph(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

/** Retains one logical text segment in document order, independently of visual direction. */
internal data class PdfTextSegment(
    val role: PdfTextRole,
    val glyphs: List<PdfTextGlyph>,
    val actualText: String?
)

/** Collects only the selected page's visible text, with a separate bounded memory budget. */
internal class PdfTextPage {
    private val collected = mutableListOf<PdfTextSegment>()
    private val characters = BreakIterator.getCharacterInstance(Locale.ROOT)
    private var textUnits = 0

    val segments: List<PdfTextSegment> get() = collected

    /** Releases completed page content while retaining the reusable grapheme iterator. */
    fun clear() {
        collected.clear()
        textUnits = 0
    }

    /** Copies measured visible graphemes without retaining the Android layout or source block. */
    fun add(
        layout: StaticLayout,
        startLine: Int,
        endLine: Int,
        originLeft: Float,
        originTop: Float,
        clipLeft: Float,
        clipTop: Float,
        clipRight: Float,
        clipBottom: Float,
        role: PdfTextRole = PdfTextRole.Paragraph
    ) {
        require(startLine >= 0 && startLine < endLine && endLine <= layout.lineCount) {
            "printed text lines are outside the layout"
        }
        require(clipLeft < clipRight && clipTop < clipBottom) {
            "printed text clip is empty"
        }
        val start = layout.getLineStart(startLine)
        val end = layout.getLineEnd(endLine - 1)
        if (start == end) return
        val text = layout.text.subSequence(start, end).toString()
        val bounds = FloatArray(Math.multiplyExact(text.length, BOUNDS_COMPONENTS))
        layout.fillCharacterBounds(start, end, bounds, 0)
        val glyphs = mutableListOf<PdfTextGlyph>()
        val logicalText = StringBuilder()
        var needsActualText = false
        characters.setText(text)
        try {
            var clusterStart = characters.first()
            var clusterEnd = characters.next()
            while (clusterEnd != BreakIterator.DONE) {
                var left = Float.POSITIVE_INFINITY
                var top = Float.POSITIVE_INFINITY
                var right = Float.NEGATIVE_INFINITY
                var bottom = Float.NEGATIVE_INFINITY
                for (offset in clusterStart until clusterEnd) {
                    val index = offset * BOUNDS_COMPONENTS
                    left = minOf(left, bounds[index] + originLeft)
                    top = minOf(top, bounds[index + 1] + originTop)
                    right = maxOf(right, bounds[index + 2] + originLeft)
                    bottom = maxOf(bottom, bounds[index + 3] + originTop)
                }
                val isLineBreak = text[clusterStart] == '\n' || text[clusterStart] == '\r'
                if (isLineBreak && top >= clipTop && bottom <= clipBottom) {
                    logicalText.append(text, clusterStart, clusterEnd)
                    needsActualText = true
                }
                // Exclude clipped text rather than embedding invisible source content.
                if (
                    left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
                    right > left && bottom > top &&
                    left >= clipLeft && right <= clipRight &&
                    top >= clipTop && bottom <= clipBottom && !isLineBreak
                ) {
                    val formula = (layout.text as? Spanned)?.getSpans(
                        start + clusterStart,
                        start + clusterEnd,
                        IllustrationPrintSpan::class.java
                    )?.singleOrNull()
                    if (formula != null) {
                        appendIllustrationSource(
                            glyphs,
                            formula.source,
                            left,
                            top,
                            right,
                            bottom
                        )
                        logicalText.append(formula.source)
                        needsActualText = true
                    } else {
                        appendCluster(
                            glyphs,
                            text,
                            clusterStart,
                            clusterEnd,
                            left,
                            top,
                            right,
                            bottom
                        )
                        logicalText.append(text, clusterStart, clusterEnd)
                        needsActualText =
                            needsActualText || layout.isRtlCharAt(start + clusterStart)
                    }
                }
                clusterStart = clusterEnd
                clusterEnd = characters.next()
            }
        } finally {
            characters.setText("")
        }
        if (glyphs.isNotEmpty()) {
            collected.add(
                PdfTextSegment(role, glyphs, if (needsActualText) logicalText.toString() else null)
            )
        }
    }

    /**
     * A source alternative needs real bidi positions too. Encoding a whole mixed-direction label
     * as one CMap glyph makes PDF readers reverse it again, breaking search. Measure plain source
     * with Android, then fit its nonpainting selection rectangles inside the illustration. This
     * does not alter the visible diagram or accept layout/styles from the document.
     */
    private fun appendIllustrationSource(
        glyphs: MutableList<PdfTextGlyph>,
        source: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ) {
        if (source.length > MAXIMUM_PAGE_TEXT_UNITS - textUnits) {
            throw IOException("printed page exceeds the text-layer limit")
        }
        val paint = TextPaint().apply { textSize = 16f }
        val width = ceil(Layout.getDesiredWidth(source, paint)).toInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(source, 0, source.length, paint, width)
            .setIncludePad(false)
            .build()
        val alternative = PdfTextPage()
        alternative.add(
            layout, 0, layout.lineCount, 0f, 0f,
            -Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE
        )
        textUnits += source.length
        val sourceGlyphs = alternative.segments.flatMap { it.glyphs }
        if (sourceGlyphs.isEmpty()) return
        val originX = minOf(0f, sourceGlyphs.minOf { it.left })
        val originY = minOf(0f, sourceGlyphs.minOf { it.top })
        val scaleX =
            (right - left) / (maxOf(width.toFloat(), sourceGlyphs.maxOf { it.right }) - originX)
        val scaleY =
            (bottom - top) /
                (maxOf(layout.height.toFloat(), sourceGlyphs.maxOf { it.bottom }) - originY)
        sourceGlyphs.mapTo(glyphs) { glyph ->
            glyph.copy(
                left = left + (glyph.left - originX) * scaleX,
                top = top + (glyph.top - originY) * scaleY,
                right = left + (glyph.right - originX) * scaleX,
                bottom = top + (glyph.bottom - originY) * scaleY
            )
        }
    }

    /** Splits pathological combining sequences at scalar boundaries within the PDF string bound. */
    private fun appendCluster(
        glyphs: MutableList<PdfTextGlyph>,
        text: String,
        start: Int,
        end: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ) {
        if (end - start > MAXIMUM_PAGE_TEXT_UNITS - textUnits) {
            throw IOException("printed page exceeds the text-layer limit")
        }
        textUnits += end - start
        var offset = start
        while (offset < end) {
            var next = minOf(end, offset + MAXIMUM_GLYPH_TEXT_UNITS)
            if (next < end && text[next - 1].isHighSurrogate() &&
                text[next].isLowSurrogate()
            ) {
                next--
            }
            // A long cluster/source alternative needs several bounded CMap entries. Giving
            // identical chunks the same rectangle makes PDF readers discard them as duplicate
            // overprinted glyphs. Partition only the nonpainting selection rectangle; the
            // actual cluster/illustration appearance is still drawn once, unchanged.
            val width = right - left
            val chunkLeft = left + width * ((offset - start).toFloat() / (end - start))
            val chunkRight = if (next == end) {
                right
            } else {
                left + width * ((next - start).toFloat() / (end - start))
            }
            glyphs.add(
                PdfTextGlyph(text.substring(offset, next), chunkLeft, top, chunkRight, bottom)
            )
            offset = next
        }
    }
}
