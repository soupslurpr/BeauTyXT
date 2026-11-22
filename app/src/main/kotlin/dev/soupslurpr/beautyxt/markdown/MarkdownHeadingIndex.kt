/* Indexes stable local heading destinations without rescanning rendered text. */
package dev.soupslurpr.beautyxt.markdown

import java.text.Normalizer
import java.util.Locale

/** Identifies a heading's owning lazy item and exact render block. */
internal data class MarkdownHeadingTarget(
    val itemIndex: Int,
    val blockIndex: Int
)

/** Indexes one immutable preview's headings while preserving duplicate-name collisions. */
internal class MarkdownHeadingIndex(items: Iterable<MarkdownPreviewHeadingItem>) {
    private val itemIndices: Map<String, Int>
    private val targetsByAnchor: Map<String, MarkdownHeadingTarget>
    private val firstTarget: MarkdownHeadingTarget?
    private val hasItems: Boolean

    /** Exposes immutable heading identities to excerpt and PDF destination generation. */
    val destinations: Map<String, Int> get() = itemIndices

    init {
        val indices = HashMap<String, Int>()
        val targets = HashMap<String, MarkdownHeadingTarget>()
        val nextSuffixes = HashMap<String, Int>()
        var containsItems = false
        var initialTarget: MarkdownHeadingTarget? = null
        items.forEach { item ->
            require(item.itemIndex >= 0) { "Markdown heading item index must be nonnegative" }
            require(item.blockIndex >= 0) { "Markdown heading block index must be nonnegative" }
            if (!containsItems) {
                initialTarget = MarkdownHeadingTarget(item.itemIndex, item.blockIndex)
            }
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
            targets[anchor] = MarkdownHeadingTarget(item.itemIndex, item.blockIndex)
            nextSuffixes[baseAnchor] = Math.incrementExact(suffix)
        }
        itemIndices = indices
        targetsByAnchor = targets
        firstTarget = initialTarget
        hasItems = containsItems
    }

    /** Returns one lazy preview target without reading document text again. */
    fun itemIndex(fragment: String): Int? = if (fragment.isEmpty()) {
        if (hasItems) 0 else null
    } else {
        itemIndices[markdownHeadingAnchor(fragment)]
    }

    /** Returns both lazy item and exact render block for a heading fragment. */
    fun target(fragment: String): MarkdownHeadingTarget? = if (fragment.isEmpty()) {
        firstTarget
    } else {
        targetsByAnchor[markdownHeadingAnchor(fragment)]
    }
}

/** Contains the heading-relevant fields of one lazy Markdown preview item. */
internal data class MarkdownPreviewHeadingItem(
    val itemIndex: Int,
    val isHeading: Boolean,
    val text: String,
    val blockIndex: Int = itemIndex
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
