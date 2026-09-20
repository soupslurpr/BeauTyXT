package dev.soupslurpr.beautyxt.markdown

import dev.soupslurpr.beautyxt.document.isScalarBoundary

/** Identifies one rendered Markdown offset and its owning presentation block. */
internal data class MarkdownRenderedSourcePosition(
    val blockIndex: Int,
    val renderedUtf16Offset: Int
) {
    init {
        require(blockIndex >= 0) { "Markdown block index must be nonnegative" }
        require(renderedUtf16Offset >= 0) {
            "rendered Markdown offset must be nonnegative"
        }
    }
}

/** Maps one logical source position to the nearest exact rendered provenance. */
internal fun markdownRenderedPositionForSourceOffset(
    blocks: List<MarkdownRenderBlock>,
    sourceUtf16Offset: Long
): MarkdownRenderedSourcePosition? {
    require(sourceUtf16Offset >= 0L) { "Markdown source offset must be nonnegative" }
    if (blocks.isEmpty()) {
        return null
    }
    val containingBlockIndex =
        blocks.indexOfFirst { block ->
            block.source.start < block.source.end &&
                sourceUtf16Offset >= block.source.start &&
                sourceUtf16Offset < block.source.end
        }
    if (containingBlockIndex >= 0) {
        val block = blocks[containingBlockIndex]
        return MarkdownRenderedSourcePosition(
            blockIndex = containingBlockIndex,
            renderedUtf16Offset = block.renderedUtf16OffsetForSource(sourceUtf16Offset)
        )
    }
    val followingBlockIndex =
        blocks.indexOfFirst { block ->
            block.source.start < block.source.end && block.source.start > sourceUtf16Offset
        }
    if (followingBlockIndex >= 0) {
        return MarkdownRenderedSourcePosition(
            blockIndex = followingBlockIndex,
            renderedUtf16Offset = 0
        )
    }
    val precedingBlockIndex =
        blocks.indexOfLast { block -> block.source.start < block.source.end }
            .takeIf { blockIndex -> blockIndex >= 0 }
            ?: blocks.lastIndex
    return MarkdownRenderedSourcePosition(
        blockIndex = precedingBlockIndex,
        renderedUtf16Offset = blocks[precedingBlockIndex].text.length
    )
}

/** Maps one rendered caret to a scalar-safe logical source provenance boundary. */
internal fun MarkdownRenderBlock.sourceUtf16OffsetForRendered(renderedUtf16Offset: Int): Long {
    require(renderedUtf16Offset in 0..text.length) {
        "rendered Markdown offset exceeds its block"
    }
    val caret = text.scalarOffsetAtOrBefore(renderedUtf16Offset)
    val sourceMap =
        sourceMaps.firstOrNull { mapping ->
            caret >= mapping.renderedStart &&
                (
                    caret < mapping.renderedEnd ||
                        (
                            caret == text.length &&
                                mapping.renderedEnd == text.length
                            )
                    )
        }
    if (sourceMap != null) {
        return mapRenderedOffsetToSource(sourceMap, caret)
    }
    if (source.start >= source.end) {
        return 0L
    }
    val sourceUtf16Units = source.end - source.start
    return when {
        caret == text.length -> source.end
        sourceUtf16Units == text.length.toLong() -> source.start + caret
        else -> source.start
    }
}

/** Maps one source position within this block to its nearest rendered caret. */
internal fun MarkdownRenderBlock.renderedUtf16OffsetForSource(sourceUtf16Offset: Long): Int {
    require(sourceUtf16Offset >= 0L) { "Markdown source offset must be nonnegative" }
    val sourceMap =
        sourceMaps.firstOrNull { mapping ->
            sourceUtf16Offset >= mapping.source.start &&
                (
                    sourceUtf16Offset < mapping.source.end ||
                        (
                            sourceUtf16Offset == source.end &&
                                mapping.source.end == source.end
                            )
                    )
        }
    if (sourceMap != null) {
        return text.scalarOffsetAtOrBefore(mapSourceOffsetToRendered(sourceMap, sourceUtf16Offset))
    }
    val followingMap =
        sourceMaps.firstOrNull { mapping -> mapping.source.start > sourceUtf16Offset }
    if (followingMap != null) {
        return followingMap.renderedStart
    }
    if (sourceMaps.isNotEmpty() && sourceUtf16Offset >= sourceMaps.last().source.end) {
        return text.length
    }
    if (source.start >= source.end) {
        return 0
    }
    val sourceUtf16Units = source.end - source.start
    return when {
        sourceUtf16Offset <= source.start -> 0

        sourceUtf16Offset >= source.end -> text.length

        sourceUtf16Units == text.length.toLong() ->
            text.scalarOffsetAtOrBefore(Math.toIntExact(sourceUtf16Offset - source.start))

        else -> 0
    }
}

/** Moves an imprecise UTF-16 offset to the beginning of its supplementary scalar. */
private fun String.scalarOffsetAtOrBefore(offset: Int): Int =
    if (isScalarBoundary(offset)) offset else offset - 1

/** Maps one offset through a rendered/source run without inventing syntax positions. */
private fun mapRenderedOffsetToSource(
    sourceMap: MarkdownSourceMap,
    renderedUtf16Offset: Int
): Long {
    val renderedUtf16Units = sourceMap.renderedEnd - sourceMap.renderedStart
    val sourceUtf16Units = sourceMap.source.end - sourceMap.source.start
    return when {
        renderedUtf16Offset == sourceMap.renderedEnd -> sourceMap.source.end

        sourceUtf16Units == renderedUtf16Units.toLong() ->
            sourceMap.source.start + renderedUtf16Offset - sourceMap.renderedStart

        else -> sourceMap.source.start
    }
}

/** Maps one source offset through a rendered/source run without splitting syntax. */
private fun mapSourceOffsetToRendered(sourceMap: MarkdownSourceMap, sourceUtf16Offset: Long): Int {
    val renderedUtf16Units = sourceMap.renderedEnd - sourceMap.renderedStart
    val sourceUtf16Units = sourceMap.source.end - sourceMap.source.start
    return when {
        sourceUtf16Offset == sourceMap.source.end -> sourceMap.renderedEnd

        sourceUtf16Units == renderedUtf16Units.toLong() ->
            Math.addExact(
                sourceMap.renderedStart,
                Math.toIntExact(sourceUtf16Offset - sourceMap.source.start)
            )

        else -> sourceMap.renderedStart
    }
}
