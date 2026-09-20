/* Preserves bounded bidirectional paragraphs before Android resolves their visual order. */
package dev.soupslurpr.beautyxt.printing

import dev.soupslurpr.beautyxt.document.DocumentMetrics
import dev.soupslurpr.beautyxt.document.EditorDocumentSnapshot
import dev.soupslurpr.beautyxt.document.RenderBlock
import dev.soupslurpr.beautyxt.document.ViewportCursor
import dev.soupslurpr.beautyxt.document.ViewportLimits
import java.io.IOException

// Bound the complete context required by Android's paragraph-level bidi resolution.
internal const val MAXIMUM_BIDI_PRINT_PARAGRAPH_UNITS = 64 * 1_024

/** Reports a paragraph that cannot retain its bidirectional context within the layout budget. */
internal class PrintParagraphLimitException :
    IOException(
        "bidirectional print paragraph exceeds the bounded layout limit"
    )

/** Returns a complete bounded bidi paragraph, or null for independently streamed LTR text. */
internal suspend fun completeBidirectionalPrintParagraph(
    snapshot: EditorDocumentSnapshot,
    metrics: DocumentMetrics,
    firstBlock: RenderBlock,
    limits: ViewportLimits,
    ensureActive: suspend () -> Unit
): String? {
    require(!firstBlock.continuesAtStart) {
        "print paragraph must start at a logical line boundary"
    }
    require(limits.maxBlocks == 1) { "print paragraph reads must return at most one block" }
    var block = firstBlock
    var needsBidi = false
    while (true) {
        ensureActive()
        needsBidi = needsBidi || requiresParagraphBidi(block.text)
        if (needsBidi &&
            block.globalUtf16End - firstBlock.globalUtf16Start > MAXIMUM_BIDI_PRINT_PARAGRAPH_UNITS
        ) {
            throw PrintParagraphLimitException()
        }
        if (!block.continuesAtEnd) break
        block = nextPrintParagraphBlock(snapshot, metrics, firstBlock, block, limits)
    }
    if (!needsBidi) return null
    if (block === firstBlock) return firstBlock.text
    val text = StringBuilder((block.globalUtf16End - firstBlock.globalUtf16Start).toInt())
    block = firstBlock
    while (true) {
        ensureActive()
        text.append(block.text)
        if (!block.continuesAtEnd) return text.toString()
        block = nextPrintParagraphBlock(snapshot, metrics, firstBlock, block, limits)
    }
}

/** Recognizes directionality that depends on context outside a streamed visual line. */
internal fun requiresParagraphBidi(text: String): Boolean {
    var offset = 0
    while (offset < text.length) {
        val codePoint = text.codePointAt(offset)
        when (Character.getDirectionality(codePoint)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
            Character.DIRECTIONALITY_ARABIC_NUMBER,
            Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING,
            Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE,
            Character.DIRECTIONALITY_POP_DIRECTIONAL_FORMAT,
            Character.DIRECTIONALITY_LEFT_TO_RIGHT_ISOLATE,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ISOLATE,
            Character.DIRECTIONALITY_FIRST_STRONG_ISOLATE,
            Character.DIRECTIONALITY_POP_DIRECTIONAL_ISOLATE -> return true
        }
        offset += Character.charCount(codePoint)
    }
    return false
}

/** Reads the next bounded fragment while checking the immutable logical-line boundary. */
private suspend fun nextPrintParagraphBlock(
    snapshot: EditorDocumentSnapshot,
    metrics: DocumentMetrics,
    firstBlock: RenderBlock,
    previousBlock: RenderBlock,
    limits: ViewportLimits
): RenderBlock {
    val continuation = snapshot.viewport(
        cursor = ViewportCursor(
            revision = metrics.revision,
            line = firstBlock.logicalLine,
            utf16Offset = previousBlock.globalUtf16End - firstBlock.globalUtf16Start
        ),
        limits = limits
    )
    check(continuation.metrics == metrics) { "print paragraph revision changed" }
    val next = continuation.blocks.single()
    check(
        next.logicalLine == firstBlock.logicalLine &&
            next.globalUtf16Start == previousBlock.globalUtf16End &&
            next.globalUtf16End > previousBlock.globalUtf16End && next.continuesAtStart
    ) { "print paragraph scan did not advance within the logical line" }
    return next
}
