/* Keeps visible strong emphasis in the device's built-in reading fonts. */
package dev.soupslurpr.beautyxt.ui.editor

import android.graphics.Paint
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.text.TextRunShaper

private data class ResolvedWeight(val font: Font, val syntheticBold: Boolean, val variationWeight: Float)

/** Probe the primary built-in face; the requested Typeface weight alone cannot identify it. */
private fun resolvedWeight(family: Int, weight: Int, italic: Boolean): ResolvedWeight {
    val base = when (family) {
        1 -> Typeface.SERIF
        2 -> Typeface.MONOSPACE
        else -> Typeface.DEFAULT
    }
    val paint = Paint().apply {
        typeface = Typeface.create(base, weight, italic)
        textSize = 32f
    }
    val glyphs = TextRunShaper.shapeTextRun("A", 0, 1, 0, 1, 0f, 0f, false, paint)
    return ResolvedWeight(glyphs.getFont(0), glyphs.getFakeBold(0), glyphs.getWeightOverride(0))
}

internal fun markdownStrongWeight(bodyWeight: Int): Int = (bodyWeight + 300).coerceIn(700, 1000)

private fun shapeEmphasis(family: Int, bodyWeight: Int, adjustment: Int): MarkdownEmphasis {
    val strongWeight = markdownStrongWeight(bodyWeight)
    fun needsUnderline(italic: Boolean): Boolean {
        val body = resolvedWeight(family, (bodyWeight + adjustment).coerceIn(1, 1000), italic)
        val strong = resolvedWeight(family, (strongWeight + adjustment).coerceIn(1, 1000), italic)
        // Ordinary synthesized bold is useful for single-face fonts. Extra synthesis over
        // an already-bold face is too subtle to serve as the only emphasis distinction.
        return body == strong || body.font == strong.font && body.font.style.weight >= 700 && strong.syntheticBold
    }
    return MarkdownEmphasis(bodyWeight, strongWeight, needsUnderline(false), needsUnderline(true))
}

/** Shaping depends only on the requested built-in face, weight and accessibility adjustment. */
internal class MarkdownEmphasisCache(
    private val shaper: (Int, Int, Int) -> MarkdownEmphasis = ::shapeEmphasis
) {
    private data class Key(val family: Int, val weight: Int, val adjustment: Int)
    private val results = object : LinkedHashMap<Key, MarkdownEmphasis>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, MarkdownEmphasis>): Boolean = size > 64
    }

    @Synchronized
    fun resolve(family: Int, weight: Int, adjustment: Int): MarkdownEmphasis {
        val normalized = if (adjustment == Configuration.FONT_WEIGHT_ADJUSTMENT_UNDEFINED) 0 else adjustment
        return results.getOrPut(Key(family, weight, normalized)) { shaper(family, weight, normalized) }
    }
}

private val emphasisCache = MarkdownEmphasisCache()

internal fun markdownEmphasis(font: Float, bodyWeight: Int, weightAdjustment: Int): MarkdownEmphasis =
    emphasisCache.resolve(font.toInt(), bodyWeight, weightAdjustment)
