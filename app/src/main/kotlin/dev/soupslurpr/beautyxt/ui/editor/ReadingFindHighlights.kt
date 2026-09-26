package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.runtime.staticCompositionLocalOf
internal data class ReadingFindHighlight(val start: Int, val end: Int, val current: Boolean,
    val markerStart: Int = 0, val markerEnd: Int = 0) {
    val collapsed get() = start == end && markerStart == markerEnd
}
internal val LocalReadingFindHighlights = staticCompositionLocalOf<Map<Int, List<ReadingFindHighlight>>> { emptyMap() }
internal data class IllustrationFindHighlight(val target: IllustrationSearchTarget, val current: Boolean)
internal val LocalIllustrationFindHighlights = staticCompositionLocalOf<List<IllustrationFindHighlight>> { emptyList() }

internal fun readingFindHighlights(session: EditorSession): Map<Int, List<ReadingFindHighlight>> {
    if (!session.isFindVisible) return emptyMap()
    val result = HashMap<Int, MutableList<ReadingFindHighlight>>()
    session.findResults.forEachIndexed { index, match ->
        match.segments.forEach { part ->
            result.getOrPut(part.block) { ArrayList() }.add(ReadingFindHighlight(
                part.blockStart, part.blockEnd, index == session.findResultIndex, part.markerStart, part.markerEnd))
        }
    }
    return result
}
