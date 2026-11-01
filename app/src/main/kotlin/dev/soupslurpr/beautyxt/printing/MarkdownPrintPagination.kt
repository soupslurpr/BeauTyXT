package dev.soupslurpr.beautyxt.printing

/** Keeps a visible definition with content, yielding optional following content when necessary. */
internal fun markdownPrintDefinitionGroupHeight(
    labelHeightPixels: Int,
    contentHeightPixels: Int,
    bodyHeightPixels: Int,
    followingHeightPixels: Int = 0
): Int {
    require(labelHeightPixels > 0 && contentHeightPixels > 0 && bodyHeightPixels > 0) {
        "Markdown definition group dimensions must be positive"
    }
    require(followingHeightPixels >= 0) { "Markdown following height must be nonnegative" }
    val first = Math.addExact(labelHeightPixels, contentHeightPixels)
    val complete = Math.addExact(first, followingHeightPixels)
    // Returning an oversized first group lets ordinary streaming make progress on a fresh page.
    return if (complete <= bodyHeightPixels) complete else first
}

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
