/* Indexes stable local heading destinations without rescanning rendered text. */
package dev.soupslurpr.beautyxt.markdown

import java.text.Normalizer
import java.util.Locale

/** Indexes one immutable preview's headings while preserving duplicate-name collisions. */
internal class MarkdownHeadingIndex(items: Iterable<MarkdownPreviewHeadingItem>) {
    private val itemIndices: Map<String, Int>
    private val hasItems: Boolean

    init {
        val indices = HashMap<String, Int>()
        val nextSuffixes = HashMap<String, Int>()
        var containsItems = false
        items.forEach { item ->
            require(item.itemIndex >= 0) { "Markdown heading item index must be nonnegative" }
            containsItems = true
            if (!item.isHeading) return@forEach
            val baseAnchor = markdownHeadingAnchor(item.text)
            if (baseAnchor.isEmpty()) return@forEach
            var suffix = nextSuffixes[baseAnchor] ?: 0
            var anchor = if (suffix == 0) baseAnchor else "$baseAnchor-$suffix"
            while (indices.putIfAbsent(anchor, item.itemIndex) != null) {
                suffix = Math.incrementExact(suffix)
                anchor = "$baseAnchor-$suffix"
            }
            nextSuffixes[baseAnchor] = Math.incrementExact(suffix)
        }
        itemIndices = indices
        hasItems = containsItems
    }

    /** Returns one lazy preview target without reading document text again. */
    fun itemIndex(fragment: String): Int? = if (fragment.isEmpty()) {
        if (hasItems) 0 else null
    } else {
        itemIndices[markdownHeadingAnchor(fragment)]
    }
}

/** Contains the heading-relevant fields of one lazy Markdown preview item. */
internal data class MarkdownPreviewHeadingItem(
    val itemIndex: Int,
    val isHeading: Boolean,
    val text: String
)

/** Returns one stable, Unicode-preserving heading anchor. */
internal fun markdownHeadingAnchor(text: String): String {
    val normalized = Normalizer.normalize(text, Normalizer.Form.NFC).lowercase(Locale.ROOT)
    val anchor = StringBuilder(normalized.length)
    var pendingSeparator = false
    var offset = 0
    while (offset < normalized.length) {
        val codePoint = Character.codePointAt(normalized, offset)
        offset += Character.charCount(codePoint)
        when {
            Character.isWhitespace(codePoint) -> pendingSeparator = anchor.isNotEmpty()

            isMarkdownHeadingAnchorCodePoint(codePoint) -> {
                if (pendingSeparator && anchor.lastOrNull() != '-') {
                    anchor.append('-')
                }
                pendingSeparator = false
                anchor.appendCodePoint(codePoint)
            }
        }
    }
    return anchor.toString().trim('-')
}

/** Returns whether one Unicode code point belongs in a generated heading anchor. */
private fun isMarkdownHeadingAnchorCodePoint(codePoint: Int): Boolean {
    val type = Character.getType(codePoint)
    return Character.isLetterOrDigit(codePoint) ||
        codePoint == '-'.code ||
        codePoint == '_'.code ||
        type == Character.COMBINING_SPACING_MARK.toInt() ||
        type == Character.NON_SPACING_MARK.toInt()
}
