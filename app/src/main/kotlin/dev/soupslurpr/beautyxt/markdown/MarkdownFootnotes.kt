package dev.soupslurpr.beautyxt.markdown

import dev.soupslurpr.beautyxt.illustration.IllustrationKind
import dev.soupslurpr.beautyxt.illustration.IllustrationResult

/** Holds text and spans after atomic inline substitutions, with exact source-offset mapping. */
internal data class MarkdownInlinePresentation(
    val text: String,
    val spans: List<MarkdownInlineSpan>
) {
    /** Maps one displayed caret back through numbered footnote substitutions. */
    fun sourceUtf16OffsetForPresentation(
        presentationUtf16Offset: Int,
        sourceTextLength: Int,
        sourceSpans: List<MarkdownInlineSpan>
    ): Int = mapFootnotePresentationOffset(
        offset = presentationUtf16Offset,
        fromTextLength = text.length,
        fromSpans = spans,
        toTextLength = sourceTextLength,
        toSpans = sourceSpans
    )

    /** Maps one original rendered caret into the numbered footnote presentation. */
    fun presentationUtf16OffsetForSource(
        sourceUtf16Offset: Int,
        sourceTextLength: Int,
        sourceSpans: List<MarkdownInlineSpan>
    ): Int = mapFootnotePresentationOffset(
        offset = sourceUtf16Offset,
        fromTextLength = sourceTextLength,
        fromSpans = sourceSpans,
        toTextLength = text.length,
        toSpans = spans
    )
}

/** A displayed formula is one object even when its exact TeX contains paragraph breaks. */
internal fun standaloneDisplayIllustration(text: String, spans: List<MarkdownInlineSpan>): Boolean {
    val span = spans.singleOrNull() ?: return false
    return span.start == 0 && span.end == text.length &&
        (
            span.styles and MARKDOWN_SPAN_STYLE_DISPLAY_MATH != 0 ||
                span.illustration?.kind == IllustrationKind.Diagram
            ) &&
        span.illustration is IllustrationResult.Rendered
}

/** Leaves normal selection intact; complete display formulas use explicit exact-source copying. */
internal fun markdownInlinePresentation(
    text: String,
    spans: List<MarkdownInlineSpan>,
    footnoteNumbers: Map<String, Int>
): MarkdownInlinePresentation? = if (standaloneDisplayIllustration(text, spans)) {
    MarkdownInlinePresentation("\uFFFC", listOf(spans.single().copy(start = 0, end = 1)))
} else {
    markdownFootnotePresentation(text, spans, footnoteNumbers)
}

/** Assigns stable footnote numbers by first reference, then definition order. */
internal fun markdownFootnoteNumbers(blocks: Iterable<MarkdownRenderBlock>): Map<String, Int> {
    val references = LinkedHashSet<String>()
    val definitions = LinkedHashSet<String>()
    blocks.forEach { block ->
        if (block.kind == MarkdownBlockKind.Footnote) {
            definitions += block.metadata
        }
        block.spans.forEach { span ->
            if (span.destinationKind == MarkdownInlineDestinationKind.FootnoteReference) {
                span.destination?.let(references::add)
            }
        }
    }
    val numbers = LinkedHashMap<String, Int>(references.size + definitions.size)
    references.forEach { label -> numbers[label] = numbers.size + 1 }
    definitions.forEach { label -> numbers.putIfAbsent(label, numbers.size + 1) }
    return numbers
}

/** Replaces mapped footnote source labels while preserving every later span offset. */
internal fun markdownFootnotePresentation(
    text: String,
    spans: List<MarkdownInlineSpan>,
    footnoteNumbers: Map<String, Int>
): MarkdownInlinePresentation? {
    val replacesFootnote =
        spans.any { span ->
            span.destinationKind == MarkdownInlineDestinationKind.FootnoteReference &&
                span.destination?.let(footnoteNumbers::containsKey) == true
        }
    if (!replacesFootnote) {
        return null
    }
    val presentationText = StringBuilder(text.length)
    val presentationSpans = ArrayList<MarkdownInlineSpan>(spans.size)
    var sourceIndex = 0
    spans.forEach { span ->
        presentationText.append(text, sourceIndex, span.start)
        val presentationStart = presentationText.length
        val footnoteNumber =
            if (span.destinationKind == MarkdownInlineDestinationKind.FootnoteReference) {
                span.destination?.let(footnoteNumbers::get)
            } else {
                null
            }
        if (footnoteNumber == null) {
            presentationText.append(text, span.start, span.end)
        } else {
            presentationText.append(footnoteNumber)
        }
        val presentationStyles =
            if (footnoteNumber == null) {
                span.styles
            } else {
                (span.styles and MARKDOWN_SPAN_STYLE_CODE.inv()) or
                    MARKDOWN_SPAN_STYLE_SUPERSCRIPT
            }
        presentationSpans +=
            span.copy(
                start = presentationStart,
                end = presentationText.length,
                styles = presentationStyles
            )
        sourceIndex = span.end
    }
    presentationText.append(text, sourceIndex, text.length)
    return MarkdownInlinePresentation(
        text = presentationText.toString(),
        spans = presentationSpans
    )
}

/** Maps one offset between corresponding original and presentation span sequences. */
private fun mapFootnotePresentationOffset(
    offset: Int,
    fromTextLength: Int,
    fromSpans: List<MarkdownInlineSpan>,
    toTextLength: Int,
    toSpans: List<MarkdownInlineSpan>
): Int {
    require(offset in 0..fromTextLength) { "footnote offset exceeds its text" }
    require(fromSpans.size == toSpans.size) {
        "footnote presentation span count changed"
    }
    var fromCursor = 0
    var toCursor = 0
    fromSpans.indices.forEach { spanIndex ->
        val fromSpan = fromSpans[spanIndex]
        val toSpan = toSpans[spanIndex]
        val fromPlainLength = fromSpan.start - fromCursor
        val toPlainLength = toSpan.start - toCursor
        check(fromPlainLength == toPlainLength) {
            "footnote presentation changed unstyled text"
        }
        if (offset <= fromSpan.start) {
            return Math.addExact(toCursor, offset - fromCursor)
        }
        if (offset <= fromSpan.end) {
            val fromSpanLength = fromSpan.end - fromSpan.start
            val toSpanLength = toSpan.end - toSpan.start
            return when {
                offset == fromSpan.end -> toSpan.end

                fromSpanLength == toSpanLength ->
                    Math.addExact(toSpan.start, offset - fromSpan.start)

                else -> toSpan.start
            }
        }
        fromCursor = fromSpan.end
        toCursor = toSpan.end
    }
    check(fromTextLength - fromCursor == toTextLength - toCursor) {
        "footnote presentation changed trailing text"
    }
    return Math.addExact(toCursor, offset - fromCursor)
}
