package dev.soupslurpr.beautyxt.printing

/** Moves a group only if it fits a fresh page; oversized content must still make progress. */
internal fun shouldAdvanceMarkdownPrintGroup(
    bodyTopPixels: Int,
    bodyBottomPixels: Int,
    bodyY: Int,
    groupHeightPixels: Int,
    leadingSpacingPixels: Int = 0
): Boolean {
    require(bodyTopPixels < bodyBottomPixels) { "Markdown print body must be nonempty" }
    require(bodyY in bodyTopPixels..bodyBottomPixels) {
        "Markdown print body position is outside the page"
    }
    require(groupHeightPixels > 0 && leadingSpacingPixels >= 0) {
        "Markdown print group dimensions must be positive"
    }
    return bodyY > bodyTopPixels &&
        groupHeightPixels <= bodyBottomPixels - bodyTopPixels &&
        groupHeightPixels.toLong() + leadingSpacingPixels > bodyBottomPixels - bodyY
}
