package dev.soupslurpr.beautyxt.illustration

import dev.soupslurpr.beautyxt.markdown.MARKDOWN_SPAN_STYLE_DISPLAY_MATH
import dev.soupslurpr.beautyxt.markdown.MARKDOWN_SPAN_STYLE_MATH
import dev.soupslurpr.beautyxt.markdown.MarkdownBlockKind
import dev.soupslurpr.beautyxt.markdown.MarkdownInlineSpan
import dev.soupslurpr.beautyxt.markdown.MarkdownRenderBlock
import dev.soupslurpr.beautyxt.markdown.MarkdownSourceRange

/** Exact source/style identity; never persisted or shared between document previews. */
internal data class IllustrationRequest(
    val source: String,
    val display: Boolean,
    val kind: IllustrationKind
)

/** Prepares provenance once off the UI thread. Drawings never enter this immutable index. */
internal class MarkdownIllustrationPlan(private val blocks: List<MarkdownRenderBlock>) {
    private data class Target(
        val request: IllustrationRequest?,
        val fallback: IllustrationResult.Fallback?,
        val spanIndex: Int?,
        val joined: MarkdownRenderBlock?
    )

    private val targets = HashMap<Int, List<Target>>()
    private val fenceOwners = IntArray(blocks.size) { -1 }
    val isEmpty: Boolean get() = targets.isEmpty()

    init {
        var index = 0
        while (index < blocks.size) {
            val block = blocks[index]
            val kind = when (block.metadata.trim().lowercase()) {
                "math" -> IllustrationKind.Math
                "mermaid" -> IllustrationKind.Diagram
                else -> null
            }
            if (block.kind == MarkdownBlockKind.Code && !block.continuesPrevious &&
                kind != null && block.text.isNotEmpty() &&
                block.spans.none { it.illustration != null }
            ) {
                var end = index + 1
                var units = block.text.length.toLong()
                while (end < blocks.size && blocks[end].kind == MarkdownBlockKind.Code &&
                    blocks[end].continuesPrevious
                ) {
                    units += blocks[end++].text.length
                }
                val maximum = sourceLimit(kind)
                val source = if (units <= maximum) {
                    buildString(units.toInt()) {
                        for (fragment in index until end) append(blocks[fragment].text)
                    }
                } else {
                    null
                }
                val failure = if (source == null) {
                    IllustrationFailure.TooLarge
                } else {
                    admission(source, true, kind)
                }
                val request = source?.takeIf {
                    failure == null
                }?.let { IllustrationRequest(it, true, kind) }
                var offset = 0
                val joined = request?.let {
                    block.copy(
                        text = it.source,
                        source = MarkdownSourceRange(
                            block.source.start,
                            blocks[end - 1].source.end
                        ),
                        sourceMaps = blocks.subList(index, end).flatMap { fragment ->
                            fragment.sourceMaps.map { map ->
                                map.copy(
                                    renderedStart = map.renderedStart + offset,
                                    renderedEnd =
                                        map.renderedEnd + offset
                                )
                            }.also { offset += fragment.text.length }
                        }
                    )
                }
                targets[index] =
                    listOf(
                        Target(
                            request,
                            failure?.let {
                                IllustrationResult.Fallback(it, kind)
                            },
                            null,
                            joined
                        )
                    )
                for (fragment in index until end) fenceOwners[fragment] = index
                index = end
            } else {
                val found = block.spans.mapIndexedNotNull { spanIndex, span ->
                    if (span.styles and MARKDOWN_SPAN_STYLE_MATH == 0 ||
                        span.illustration != null
                    ) {
                        null
                    } else {
                        val source = block.text.substring(span.start, span.end)
                        val display = span.styles and MARKDOWN_SPAN_STYLE_DISPLAY_MATH != 0
                        val multiline =
                            ('\n' in source || '\r' in source) &&
                                (span.start != 0 || span.end != block.text.length)
                        val failure = if (multiline) {
                            IllustrationFailure.Unsupported
                        } else {
                            admission(
                                source,
                                display,
                                IllustrationKind.Math
                            )
                        }
                        Target(
                            if (failure ==
                                null
                            ) {
                                IllustrationRequest(source, display, IllustrationKind.Math)
                            } else {
                                null
                            },
                            failure?.let { IllustrationResult.Fallback(it) },
                            spanIndex,
                            null
                        )
                    }
                }
                if (found.isNotEmpty()) targets[index] = found
                index++
            }
        }
    }

    /** Visible blocks precede prefetch blocks, and repeats consume only one cache entry. */
    fun requests(blockIndices: Iterable<Int>): List<IllustrationRequest> {
        val result = LinkedHashSet<IllustrationRequest>()
        for (index in blockIndices) {
            if (index !in blocks.indices) continue
            val owner = fenceOwners[index].takeIf { it >= 0 } ?: index
            for (target in targets[owner].orEmpty()) {
                target.request?.let(result::add)
                if (result.size == MAX_DOCUMENT_ILLUSTRATIONS) return result.toList()
            }
        }
        return result.toList()
    }

    /** Retains block indices even when a complete fence covers several original transport slots. */
    fun decorate(
        index: Int,
        resolve: (IllustrationRequest) -> IllustrationResult
    ): MarkdownRenderBlock {
        val original = blocks[index]
        val owner = fenceOwners[index]
        if (owner >= 0) {
            val target = targets.getValue(owner).single()
            val result = target.fallback ?: resolve(checkNotNull(target.request))
            if (owner != index) {
                return if (result is IllustrationResult.Rendered) {
                    original.copy(
                        text = "",
                        spans = emptyList(),
                        sourceMaps = emptyList(),
                        illustrationContinuation = true
                    )
                } else {
                    original
                }
            }
            val block = if (result is IllustrationResult.Rendered) {
                checkNotNull(
                    target.joined
                )
            } else {
                original
            }
            return block.copy(
                spans = listOf(
                    MarkdownInlineSpan(
                        0,
                        block.text.length,
                        if (result.kind ==
                            IllustrationKind.Math
                        ) {
                            MARKDOWN_SPAN_STYLE_MATH or
                                MARKDOWN_SPAN_STYLE_DISPLAY_MATH
                        } else {
                            0
                        },
                        null,
                        illustration = result
                    )
                )
            )
        }
        val replacements = targets[index] ?: return original
        val spans = original.spans.toMutableList()
        for (target in replacements) {
            val span = checkNotNull(target.spanIndex)
            spans[span] =
                spans[span].copy(
                    illustration =
                        target.fallback ?: resolve(checkNotNull(target.request))
                )
        }
        return original.copy(spans = spans)
    }
}

private fun sourceLimit(kind: IllustrationKind) = if (kind == IllustrationKind.Math) {
    IllustrationLimits.MAX_MATH_SOURCE_BYTES
} else {
    IllustrationLimits.MAX_DIAGRAM_SOURCE_BYTES
}

private fun admission(
    source: String,
    display: Boolean,
    kind: IllustrationKind
): IllustrationFailure? = when {
    source.isBlank() -> IllustrationFailure.Unsupported

    !display && ('\n' in source || '\r' in source) -> IllustrationFailure.Unsupported

    source.length > sourceLimit(
        kind
    ) || source.toByteArray(Charsets.UTF_8).size > sourceLimit(kind) -> IllustrationFailure.TooLarge

    else -> null
}
