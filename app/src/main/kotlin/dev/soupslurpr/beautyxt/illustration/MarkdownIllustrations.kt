package dev.soupslurpr.beautyxt.illustration

import dev.soupslurpr.beautyxt.markdown.MARKDOWN_SPAN_STYLE_DISPLAY_MATH
import dev.soupslurpr.beautyxt.markdown.MARKDOWN_SPAN_STYLE_MATH
import dev.soupslurpr.beautyxt.markdown.MarkdownBlockKind
import dev.soupslurpr.beautyxt.markdown.MarkdownInlineSpan
import dev.soupslurpr.beautyxt.markdown.MarkdownPreviewDocument
import dev.soupslurpr.beautyxt.markdown.MarkdownRenderBlock
import dev.soupslurpr.beautyxt.markdown.MarkdownSourceRange
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

internal const val MAX_DOCUMENT_ILLUSTRATION_BYTES = 8 * 1024 * 1024
internal const val MAX_DOCUMENT_ILLUSTRATIONS = 128
private const val DOCUMENT_ILLUSTRATION_MILLIS = 12_000L

/** Only exact source/style matches share an immutable appearance, within this one preview. */
private data class IllustrationKey(
    val source: String,
    val display: Boolean,
    val kind: IllustrationKind
)

/** Adds bounded appearances; complete illustrated fences join without changing their provenance. */
internal suspend fun illustrateMarkdown(
    document: MarkdownPreviewDocument,
    renderMath: suspend (String, Boolean) -> IllustrationResult,
    nanoTime: () -> Long = System::nanoTime,
    renderDiagram: suspend (String, Boolean) -> IllustrationResult = { _, _ ->
        IllustrationResult.Fallback(IllustrationFailure.Unavailable)
    }
): MarkdownPreviewDocument {
    val started = nanoTime()
    val cache = HashMap<IllustrationKey, IllustrationResult>()
    var retainedBytes = 0
    suspend fun illustrate(
        source: String,
        display: Boolean,
        kind: IllustrationKind = IllustrationKind.Math
    ): IllustrationResult {
        currentCoroutineContext().ensureActive()
        if (source.isBlank()) return IllustrationResult.Fallback(IllustrationFailure.Unsupported)
        if (!display && ('\n' in source || '\r' in source)) {
            return IllustrationResult.Fallback(IllustrationFailure.Unsupported)
        }
        val limit = if (kind == IllustrationKind.Math) {
            IllustrationLimits.MAX_MATH_SOURCE_BYTES
        } else {
            IllustrationLimits.MAX_DIAGRAM_SOURCE_BYTES
        }
        if (source.length > limit || source.toByteArray(Charsets.UTF_8).size > limit) {
            return IllustrationResult.Fallback(IllustrationFailure.TooLarge)
        }
        val key = IllustrationKey(source, display, kind)
        cache[key]?.let { return it }
        if (cache.size >= MAX_DOCUMENT_ILLUSTRATIONS ||
            retainedBytes >= MAX_DOCUMENT_ILLUSTRATION_BYTES
        ) {
            return IllustrationResult.Fallback(IllustrationFailure.Budget)
        }
        val remaining = DOCUMENT_ILLUSTRATION_MILLIS - (nanoTime() - started) / 1_000_000L
        if (remaining <= 0) return IllustrationResult.Fallback(IllustrationFailure.Budget)
        val rendered = withTimeoutOrNull(remaining) {
            if (kind == IllustrationKind.Math) {
                renderMath(source, display)
            } else {
                renderDiagram(source, true)
            }
        }
            ?: IllustrationResult.Fallback(IllustrationFailure.TimedOut)
        val admitted = if (rendered is IllustrationResult.Rendered) {
            if (rendered.drawing.packetBytes > MAX_DOCUMENT_ILLUSTRATION_BYTES - retainedBytes) {
                IllustrationResult.Fallback(IllustrationFailure.Budget)
            } else {
                retainedBytes += rendered.drawing.packetBytes
                rendered
            }
        } else {
            rendered
        }
        cache[key] = admitted
        return admitted
    }
    val blocks = ArrayList<MarkdownRenderBlock>(document.blocks.size)
    var index = 0
    while (index < document.blocks.size) {
        currentCoroutineContext().ensureActive()
        val block = document.blocks[index]
        val fenceKind = when (block.metadata.trim().lowercase()) {
            "math" -> IllustrationKind.Math
            "mermaid" -> IllustrationKind.Diagram
            else -> null
        }
        if (block.kind == MarkdownBlockKind.Code &&
            !block.continuesPrevious && fenceKind != null &&
            block.text.isNotEmpty()
        ) {
            var end = index + 1
            var sourceUnits = block.text.length.toLong()
            while (end < document.blocks.size && document.blocks[end].continuesPrevious &&
                document.blocks[end].kind == MarkdownBlockKind.Code
            ) {
                currentCoroutineContext().ensureActive()
                sourceUnits += document.blocks[end++].text.length
            }
            val limit = if (fenceKind == IllustrationKind.Math) {
                IllustrationLimits.MAX_MATH_SOURCE_BYTES
            } else {
                IllustrationLimits.MAX_DIAGRAM_SOURCE_BYTES
            }
            val source = if (sourceUnits <= limit) {
                buildString(sourceUnits.toInt()) {
                    for (fragment in index until end) append(document.blocks[fragment].text)
                }
            } else {
                null
            }
            val result = if (source == null) {
                IllustrationResult.Fallback(IllustrationFailure.TooLarge)
            } else {
                illustrate(source, true, fenceKind)
            }.forKind(fenceKind)
            // Rejected fences keep their bounded source fragments, never a partial rendering.
            val illustrated = if (result is IllustrationResult.Rendered && end > index + 1) {
                var offset = 0
                val maps = document.blocks.subList(index, end).flatMap { fragment ->
                    fragment.sourceMaps.map { map ->
                        map.copy(
                            renderedStart = map.renderedStart + offset,
                            renderedEnd = map.renderedEnd + offset
                        )
                    }.also { offset += fragment.text.length }
                }
                block.copy(
                    text = requireNotNull(source),
                    source = MarkdownSourceRange(
                        block.source.start,
                        document.blocks[end - 1].source.end
                    ),
                    sourceMaps = maps
                )
            } else {
                block
            }
            val spans = listOf(
                MarkdownInlineSpan(
                    0,
                    illustrated.text.length,
                    if (fenceKind == IllustrationKind.Math) {
                        MARKDOWN_SPAN_STYLE_MATH or MARKDOWN_SPAN_STYLE_DISPLAY_MATH
                    } else {
                        0 // Caller-owned complete diagram, not a new Markdown wire-protocol flag.
                    },
                    destination = null,
                    illustration = result
                )
            )
            blocks += illustrated.copy(spans = spans)
            if (result !is IllustrationResult.Rendered) {
                blocks.addAll(document.blocks.subList(index + 1, end))
            }
            index = end
        } else {
            val spans = block.spans.map { span ->
                if (span.styles and MARKDOWN_SPAN_STYLE_MATH == 0) {
                    span
                } else {
                    val source = block.text.substring(span.start, span.end)
                    val embeddedMultiline = ('\n' in source || '\r' in source) &&
                        (span.start != 0 || span.end != block.text.length)
                    span.copy(
                        illustration = if (embeddedMultiline) {
                            // A Compose inline placeholder cannot cross paragraph boundaries.
                            IllustrationResult.Fallback(IllustrationFailure.Unsupported)
                        } else {
                            illustrate(
                                source,
                                span.styles and MARKDOWN_SPAN_STYLE_DISPLAY_MATH != 0
                            )
                        }
                    )
                }
            }
            blocks += if (spans == block.spans) block else block.copy(spans = spans)
            index++
        }
    }
    return document.copy(
        blocks = blocks,
        spanCount = blocks.sumOf { it.spans.size },
        sourceMapCount = blocks.sumOf { it.sourceMaps.size }
    )
}
