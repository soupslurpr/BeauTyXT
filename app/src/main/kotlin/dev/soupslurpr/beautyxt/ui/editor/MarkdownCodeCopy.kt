/* Keeps explicit code copying complete, bounded, and independent of lazy presentation. */
package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.markdown.MarkdownBlockKind
import dev.soupslurpr.beautyxt.markdown.MarkdownRenderBlock

internal const val MAX_MARKDOWN_CODE_COPY_UTF16_UNITS = 65_536

/** References one complete semantic code block without retaining an extra text copy. */
internal class MarkdownCodeCopy internal constructor(
    private val blocks: List<MarkdownRenderBlock>
) {
    val utf16Length: Long = blocks.sumOf { block -> block.text.length.toLong() }
    val isEmpty: Boolean get() = utf16Length == 0L
    val canCopy: Boolean get() = utf16Length in 1L..MAX_MARKDOWN_CODE_COPY_UTF16_UNITS.toLong()

    /** Joins renderer fragments exactly, and never substitutes a truncated prefix. */
    fun textOrNull(): String? = when {
        !canCopy -> null

        blocks.size == 1 -> blocks.single().text

        else -> buildString(utf16Length.toInt()) {
            blocks.forEach { block -> append(block.text) }
        }
    }
}

/** Indexes semantic starts once off the UI thread, including quoted and HTML code. */
internal fun markdownCodeCopies(blocks: List<MarkdownRenderBlock>): Map<Int, MarkdownCodeCopy> {
    val copies = LinkedHashMap<Int, MarkdownCodeCopy>()
    var index = 0
    while (index < blocks.size) {
        val block = blocks[index]
        if (block.kind != MarkdownBlockKind.Code || block.continuesPrevious) {
            index += 1
            continue
        }
        val start = index++
        while (
            index < blocks.size &&
            blocks[index].kind == MarkdownBlockKind.Code &&
            blocks[index].continuesPrevious
        ) {
            index += 1
        }
        copies[start] = MarkdownCodeCopy(blocks.subList(start, index))
    }
    return copies
}
