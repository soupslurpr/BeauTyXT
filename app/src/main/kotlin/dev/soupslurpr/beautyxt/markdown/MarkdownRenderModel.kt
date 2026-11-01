package dev.soupslurpr.beautyxt.markdown

import dev.soupslurpr.beautyxt.illustration.IllustrationResult

internal const val MARKDOWN_UNCHECKED_TASK_PREFIX = "☐ "
internal const val MARKDOWN_CHECKED_TASK_PREFIX = "☑ "

/** Identifies one supported Markdown preview block. */
internal enum class MarkdownBlockKind(val protocolValue: Int) {
    Paragraph(1),
    Heading(2),
    Code(3),
    ListItem(4),
    Rule(5),
    HtmlLiteral(6),
    TableRow(7),
    Footnote(8);

    companion object {
        /** Returns the block kind for one validated protocol value. */
        fun fromProtocolValue(value: Int): MarkdownBlockKind =
            entries.firstOrNull { kind -> kind.protocolValue == value }
                ?: throw MarkdownProtocolException("unsupported Markdown block kind $value")
    }
}

/** Identifies the purpose of one inline destination. */
internal enum class MarkdownInlineDestinationKind {
    Link,
    FootnoteReference
}

/** Identifies one GitHub-style alert carried by a quoted block. */
internal enum class MarkdownQuoteKind {
    Note,
    Tip,
    Important,
    Warning,
    Caution
}

/** Identifies the horizontal alignment of one Markdown table column. */
internal enum class MarkdownTableAlignment(val protocolValue: Char) {
    None('n'),
    Left('l'),
    Center('c'),
    Right('r');

    companion object {
        /** Returns the alignment for one validated protocol value. */
        fun fromProtocolValue(value: Char): MarkdownTableAlignment =
            entries.firstOrNull { alignment -> alignment.protocolValue == value }
                ?: throw MarkdownProtocolException("unsupported Markdown table alignment $value")
    }
}

/** Contains one bounded styled UTF-16 range within a rendered block. */
internal data class MarkdownInlineSpan(
    val start: Int,
    val end: Int,
    val styles: Int,
    val destination: String?,
    val destinationKind: MarkdownInlineDestinationKind? =
        destination?.let { MarkdownInlineDestinationKind.Link },
    val illustration: IllustrationResult? = null
)

/** Contains one half-open logical UTF-16 source range. */
internal data class MarkdownSourceRange(val start: Long, val end: Long)

/** Maps one rendered UTF-16 range to its exact logical source provenance. */
internal data class MarkdownSourceMap(
    val renderedStart: Int,
    val renderedEnd: Int,
    val source: MarkdownSourceRange
)

/** Contains one bounded, presentation-ready Markdown block. */
internal data class MarkdownRenderBlock(
    val kind: MarkdownBlockKind,
    val continuesPrevious: Boolean,
    val isOrderedListItem: Boolean,
    val isTaskChecked: Boolean,
    val isTaskUnchecked: Boolean,
    val isTableHeader: Boolean,
    val containsRawHtml: Boolean,
    val headingLevel: Int,
    val quoteDepth: Int,
    val listDepth: Int,
    val listNumber: Long,
    val text: String,
    val metadata: String,
    val spans: List<MarkdownInlineSpan>,
    val source: MarkdownSourceRange = MarkdownSourceRange(0, 0),
    val sourceMaps: List<MarkdownSourceMap> = emptyList(),
    val quoteKind: MarkdownQuoteKind? = null,
    val startsQuoteAlert: Boolean = false,
    val tableAlignments: List<MarkdownTableAlignment> = emptyList(),
    val startsTable: Boolean = false,
    val continuesListItem: Boolean = false,
    // Caller-owned presentation only: retain a stable block slot covered by a complete drawing.
    val illustrationContinuation: Boolean = false,
    val startsList: Boolean = false,
    // Only these tabs delimit cells; other tabs are authored cell content.
    val tableCellSeparators: List<Int> = emptyList(),
    // Independent of block kind and metadata (for example, a code language).
    val footnoteLabel: String? = null,
    // The enclosing alert may begin outside this block's innermost quote.
    val quoteAlertDepth: Int = 0
)

/** Contains one half-open rendered cell range, including an empty cell. */
internal data class MarkdownTableCellRange(val start: Int, val end: Int)

/** Uses explicit structure so literal tabs never acquire another column. */
internal fun MarkdownRenderBlock.markdownTableCellRanges(): List<MarkdownTableCellRange> {
    require(kind == MarkdownBlockKind.TableRow)
    val result = ArrayList<MarkdownTableCellRange>(tableCellSeparators.size + 1)
    var start = 0
    for (separator in tableCellSeparators) {
        result += MarkdownTableCellRange(start, separator)
        start = separator + 1
    }
    result += MarkdownTableCellRange(start, text.length)
    return result
}

/** Returns independent ownership, with the conventional paragraph label as a fallback. */
internal fun MarkdownRenderBlock.markdownFootnoteLabel(): String? =
    footnoteLabel ?: metadata.takeIf { kind == MarkdownBlockKind.Footnote && it.isNotEmpty() }

/** Contains one immutable, bounded Markdown preview model. */
internal data class MarkdownPreviewDocument(
    val inputByteLength: Long,
    val blocks: List<MarkdownRenderBlock>,
    val spanCount: Int,
    val containsRawHtml: Boolean,
    val inputUtf16Length: Long = inputByteLength,
    val sourceMapCount: Int = blocks.sumOf { block -> block.sourceMaps.size }
)
