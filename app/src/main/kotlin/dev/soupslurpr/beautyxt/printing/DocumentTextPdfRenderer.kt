/* Streams bounded source and semantic Markdown layouts into vector Unicode PDFs. */
package dev.soupslurpr.beautyxt.printing

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.print.PageRange
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withClip
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.document.DocumentMetrics
import dev.soupslurpr.beautyxt.document.EditorDocumentSnapshot
import dev.soupslurpr.beautyxt.document.RenderBlock
import dev.soupslurpr.beautyxt.document.ViewportCursor
import dev.soupslurpr.beautyxt.document.ViewportLimits
import dev.soupslurpr.beautyxt.illustration.IllustrationResult
import dev.soupslurpr.beautyxt.illustration.illustrationFallbackMessages
import dev.soupslurpr.beautyxt.markdown.MARKDOWN_SPAN_STYLE_CODE
import dev.soupslurpr.beautyxt.markdown.MARKDOWN_SPAN_STYLE_EMPHASIS
import dev.soupslurpr.beautyxt.markdown.MARKDOWN_SPAN_STYLE_STRIKETHROUGH
import dev.soupslurpr.beautyxt.markdown.MARKDOWN_SPAN_STYLE_STRONG
import dev.soupslurpr.beautyxt.markdown.MARKDOWN_SPAN_STYLE_SUBSCRIPT
import dev.soupslurpr.beautyxt.markdown.MARKDOWN_SPAN_STYLE_SUPERSCRIPT
import dev.soupslurpr.beautyxt.markdown.MarkdownBlockKind
import dev.soupslurpr.beautyxt.markdown.MarkdownInlineSpan
import dev.soupslurpr.beautyxt.markdown.MarkdownPreviewDocument
import dev.soupslurpr.beautyxt.markdown.MarkdownQuoteKind
import dev.soupslurpr.beautyxt.markdown.MarkdownRenderBlock
import dev.soupslurpr.beautyxt.markdown.MarkdownTableAlignment
import dev.soupslurpr.beautyxt.markdown.markdownFootnoteNumbers
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private const val PRINT_VIEWPORT_MAX_BLOCKS = 64
private const val PRINT_VIEWPORT_MAX_BLOCK_UTF16_UNITS = 16 * 1_024
private const val PRINT_VIEWPORT_MAX_TOTAL_UTF16_UNITS = 64 * 1_024
private const val MAXIMUM_LOGICAL_PRINT_PAGES = 100_000
private const val BODY_LINE_SPACING_MULTIPLIER = 1.15f
private const val DIVIDER_STROKE_POINTS = 0.5f
private const val POINTS_PER_INCH = 72f
private const val HEADER_ALPHA = 180
private const val FOOTER_ALPHA = 160
private const val DIVIDER_ALPHA = 80
private const val MARKDOWN_CODE_BACKGROUND_ALPHA = 24
private const val MARKDOWN_BLOCK_BACKGROUND_ALPHA = 16
private const val MARKDOWN_TABLE_HEADER_BACKGROUND_ALPHA = 24
private const val MARKDOWN_QUOTE_RAIL_ALPHA = 144
private const val MARKDOWN_MAXIMUM_INDENT_FRACTION = 3
private const val MARKDOWN_INDENT_SPACES = 2
private const val MARKDOWN_QUOTE_RAIL_WIDTH_POINTS = 1.5f
private const val MARKDOWN_TABLE_CELL_HORIZONTAL_PADDING_POINTS = 5f
private const val MARKDOWN_TABLE_CELL_VERTICAL_PADDING_POINTS = 3.5f
private const val MARKDOWN_PRINT_INTRODUCTION_MAX_LINES = 3
private const val MARKDOWN_PRINT_COMPACT_CODE_MAX_LINES = 8

// Fixed paper colors remain legible on white and never inherit a dark screen theme.
internal val PrintAccentColor = Color.rgb(36, 83, 89)
internal val PrintBlockBackgroundColor = ColorUtils.compositeColors(
    ColorUtils.setAlphaComponent(PrintAccentColor, MARKDOWN_BLOCK_BACKGROUND_ALPHA),
    Color.WHITE
)

private val PrintViewportLimits =
    ViewportLimits(
        maxBlocks = PRINT_VIEWPORT_MAX_BLOCKS,
        maxBlockUtf16Units = PRINT_VIEWPORT_MAX_BLOCK_UTF16_UNITS,
        maxTotalUtf16Units = PRINT_VIEWPORT_MAX_TOTAL_UTF16_UNITS
    )
private val PrintParagraphViewportLimits = PrintViewportLimits.copy(
    maxBlocks = 1,
    maxTotalUtf16Units = PRINT_VIEWPORT_MAX_BLOCK_UTF16_UNITS
)

/** Streams one immutable text revision into a bounded PDF with measured Unicode text. */
internal suspend fun renderDocumentTextPdf(
    destination: OutputStream,
    snapshot: EditorDocumentSnapshot,
    metrics: DocumentMetrics,
    title: String,
    layout: PrintRasterLayout,
    settings: PrintSettings = defaultPrintSettings(formattedMarkdown = false),
    requestedPages: Array<out PageRange>,
    resources: Resources,
    isCancelled: () -> Boolean = { false }
): PrintRenderResult {
    require(title.isNotBlank()) { "printed document title must not be blank" }
    require(metrics.revision >= 0L) { "printed document revision must be nonnegative" }
    val selection = PrintPageSelection(requestedPages)
    val writer = createPrintPdfWriter(destination, isCancelled)
    val pages =
        TextPrintPages(
            title = title,
            layout = layout,
            settings = settings,
            selection = selection,
            resources = resources,
            writer = writer
        )
    try {
        var cursor = ViewportCursor(revision = metrics.revision, line = 0L, utf16Offset = 0L)
        var printedWholeParagraph = false
        while (true) {
            ensurePrintActive(isCancelled)
            val viewport = snapshot.viewport(cursor = cursor, limits = PrintViewportLimits)
            check(viewport.metrics == metrics) {
                "print viewport metrics changed within an immutable revision"
            }
            check(viewport.blocks.isNotEmpty() || viewport.next == null) {
                "print viewport made no bounded progress"
            }
            viewport.blocks.forEach { block ->
                ensurePrintActive(isCancelled)
                if (!block.continuesAtStart) {
                    val paragraph = completeBidirectionalPrintParagraph(
                        snapshot,
                        metrics,
                        block,
                        PrintParagraphViewportLimits
                    ) { ensurePrintActive(isCancelled) }
                    printedWholeParagraph = paragraph != null
                    if (paragraph != null) pages.addParagraph(paragraph)
                }
                if (!printedWholeParagraph) pages.add(block)
            }
            val next = viewport.next ?: break
            check(next != cursor) { "print viewport continuation did not advance" }
            cursor = next
        }
        ensurePrintActive(isCancelled)
        return pages.finish(selection.includesEveryPage)
    } finally {
        pages.close()
    }
}

/** Streams one bounded semantic Markdown model into a PDF with original rendered text. */
internal suspend fun renderMarkdownDocumentPdf(
    destination: OutputStream,
    document: MarkdownPreviewDocument,
    title: String,
    layout: PrintRasterLayout,
    settings: PrintSettings,
    requestedPages: Array<out PageRange>,
    resources: Resources,
    isCancelled: () -> Boolean = { false },
    maxBlockUtf16Units: Int = MAXIMUM_MARKDOWN_PRINT_BLOCK_UNITS
): PrintRenderResult {
    require(title.isNotBlank()) { "printed document title must not be blank" }
    require(maxBlockUtf16Units in 1..MAXIMUM_MARKDOWN_PRINT_BLOCK_UNITS) {
        "Markdown print layout limit is outside its bounds"
    }
    require(settings.contentMode == PrintContentMode.FormattedMarkdown) {
        "semantic Markdown requires formatted print content"
    }
    val selection = PrintPageSelection(requestedPages)
    val writer = createPrintPdfWriter(destination, isCancelled)
    val pages =
        TextPrintPages(
            title = title,
            layout = layout,
            settings = settings,
            selection = selection,
            resources = resources,
            writer = writer
        )
    try {
        val footnoteNumbers = markdownFootnoteNumbers(document.blocks)
        var blockIndex = 0
        var blockOffset = 0
        while (blockIndex < document.blocks.size) {
            ensurePrintActive(isCancelled)
            val current =
                readMarkdownPrintBlock(
                    document.blocks,
                    blockIndex,
                    blockOffset,
                    maxBlockUtf16Units
                ) {
                    ensurePrintActive(isCancelled)
                }
            val block = current.block
            val previousBlock = document.blocks.getOrNull(blockIndex - 1)
            val connectsAlertSpacing =
                block.quoteKind != null &&
                    !block.startsQuoteAlert &&
                    previousBlock?.quoteKind == block.quoteKind
            if (block.kind != MarkdownBlockKind.TableRow) {
                // At most two bounded semantic blocks, never a whole-section layout buffer.
                val followingBlocks = ArrayList<MarkdownPrintBlock>(2)
                if (block.kind == MarkdownBlockKind.Heading &&
                    current.nextIndex < document.blocks.size
                ) {
                    val following = readMarkdownPrintBlock(
                        document.blocks,
                        current.nextIndex,
                        maxBlockUtf16Units = maxBlockUtf16Units
                    ) { ensurePrintActive(isCancelled) }
                    followingBlocks += following
                    if (following.block.kind == MarkdownBlockKind.Paragraph &&
                        following.nextIndex < document.blocks.size &&
                        document.blocks[following.nextIndex].kind == MarkdownBlockKind.Code
                    ) {
                        followingBlocks += readMarkdownPrintBlock(
                            document.blocks,
                            following.nextIndex,
                            maxBlockUtf16Units = maxBlockUtf16Units
                        ) { ensurePrintActive(isCancelled) }
                    }
                }
                pages.addMarkdown(
                    block = block,
                    footnoteNumbers = footnoteNumbers,
                    connectsAlertSpacing = connectsAlertSpacing,
                    continuesAtEnd = current.continuesAtEnd,
                    followingBlocks = followingBlocks
                )
                blockIndex = current.nextIndex
                blockOffset = current.nextOffset
                continue
            }
            var tableEnd = current.nextIndex
            var columnCount = markdownPrintTableColumnCount(block)
            while (
                tableEnd < document.blocks.size &&
                document.blocks[tableEnd].kind == MarkdownBlockKind.TableRow &&
                !document.blocks[tableEnd].startsTable
            ) {
                val row =
                    readMarkdownPrintBlock(
                        document.blocks,
                        tableEnd,
                        maxBlockUtf16Units = maxBlockUtf16Units
                    ) {
                        ensurePrintActive(isCancelled)
                    }
                columnCount = maxOf(columnCount, markdownPrintTableColumnCount(row.block))
                tableEnd = row.nextIndex
            }
            var rowIndex = blockIndex
            var currentRow = current
            while (rowIndex < tableEnd) {
                ensurePrintActive(isCancelled)
                val row = currentRow.block
                val precedingRow = document.blocks.getOrNull(rowIndex - 1)
                val following = if (currentRow.nextIndex < tableEnd) {
                    readMarkdownPrintBlock(
                        document.blocks,
                        currentRow.nextIndex,
                        maxBlockUtf16Units = maxBlockUtf16Units
                    ) {
                        ensurePrintActive(isCancelled)
                    }
                } else {
                    null
                }
                pages.addMarkdownTableRow(
                    block = row,
                    followingRow = following?.block,
                    columnCount = columnCount,
                    isFirstRow = rowIndex == blockIndex,
                    footnoteNumbers = footnoteNumbers,
                    connectsAlertSpacing =
                        row.quoteKind != null &&
                            !row.startsQuoteAlert &&
                            precedingRow?.quoteKind == row.quoteKind
                )
                rowIndex = currentRow.nextIndex
                currentRow = following ?: break
            }
            blockIndex = tableEnd
        }
        ensurePrintActive(isCancelled)
        return pages.finish(selection.includesEveryPage)
    } finally {
        pages.close()
    }
}

/** Propagates coroutine and print-service cancellation into the page writer's inner loops. */
private suspend fun createPrintPdfWriter(
    destination: OutputStream,
    isCancelled: () -> Boolean
): StreamingPdfWriter {
    val context = currentCoroutineContext()
    return StreamingPdfWriter(destination) {
        context.ensureActive()
        if (isCancelled()) throw CancellationException("print rendering was cancelled")
    }
}

/** Throws cancellation before the next bounded print operation begins. */
private suspend fun ensurePrintActive(isCancelled: () -> Boolean) {
    currentCoroutineContext().ensureActive()
    if (isCancelled()) {
        throw CancellationException("print rendering was cancelled")
    }
}

/** Contains one bounded semantic table row layout. */
private data class MarkdownPrintTableRowLayout(
    val cellLayouts: List<StaticLayout>,
    val heightPixels: Int
)

/** Owns its paint so bounded lookahead cannot change an earlier block's appearance. */
private data class MarkdownPrintLayout(val text: StaticLayout, val leadingLineCount: Int) {
    val leadingHeight: Int get() = text.getLineBottom(leadingLineCount - 1)
}

/** Paginates bounded logical blocks while retaining at most one vector page. */
private class TextPrintPages(
    private val title: String,
    private val layout: PrintRasterLayout,
    private val settings: PrintSettings,
    private val selection: PrintPageSelection,
    private val resources: Resources,
    private val writer: StreamingPdfWriter
) : AutoCloseable {
    private val bodyPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = Color.BLACK
            textSize = layout.bodyTextSizePixels
            typeface = settings.fontFamily.typeface
        }
    private val markdownPaint = TextPaint(bodyPaint)
    private val headerPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = Color.BLACK
            alpha = HEADER_ALPHA
            textSize = layout.headerTextSizePixels
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    private val footerPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = Color.BLACK
            alpha = FOOTER_ALPHA
            textSize = layout.footerTextSizePixels
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.DEFAULT
        }
    private val dividerPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            alpha = DIVIDER_ALPHA
            strokeWidth = DIVIDER_STROKE_POINTS / POINTS_PER_INCH * layout.rasterDpi
        }
    private val quoteRailPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PrintAccentColor
            alpha = MARKDOWN_QUOTE_RAIL_ALPHA
            style = Paint.Style.FILL
        }
    private val tableHeaderPaint =
        Paint().apply {
            color = PrintAccentColor
            alpha = MARKDOWN_TABLE_HEADER_BACKGROUND_ALPHA
            style = Paint.Style.FILL
        }
    private val markdownBlockBackgroundPaint =
        Paint().apply {
            color = PrintAccentColor
            alpha = MARKDOWN_BLOCK_BACKGROUND_ALPHA
            style = Paint.Style.FILL
        }
    private val writtenRanges = WrittenPageRanges()
    private val emptyLineHeight =
        ceil(bodyPaint.fontSpacing * BODY_LINE_SPACING_MULTIPLIER).toInt()
    private var vectorPage: PdfVectorPage? = null
    private var pageCanvas: Canvas? = null
    private val pageText = PdfTextPage()
    private var pageIndex = 0
    private var bodyY = layout.bodyTopPixels
    private var closed = false
    private var sourceLineTail = ""
    private var skipsOverflowSpaces = false

    init {
        require(emptyLineHeight > 0) { "print line height must be positive" }
        require(emptyLineHeight <= layout.bodyBottomPixels - layout.bodyTopPixels) {
            "print line height exceeds the page body"
        }
        try {
            prepareCurrentPage()
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    /** Prints complete visual lines and retains a bounded unfinished line across source fragments. */
    fun add(block: RenderBlock) = addSource(
        block.text,
        block.continuesAtStart,
        block.continuesAtEnd,
        isRtl = false
    )

    /** Keeps Android's bidi resolution within the original complete logical paragraph. */
    fun addParagraph(text: String) = addSource(
        text,
        continuesAtStart = false,
        continuesAtEnd = false,
        isRtl = TextDirectionHeuristics.FIRSTSTRONG_LTR.isRtl(text, 0, text.length)
    )

    /** Lays out source text without introducing fragment-boundary wrapping or empty lines. */
    private fun addSource(
        fragment: String,
        continuesAtStart: Boolean,
        continuesAtEnd: Boolean,
        isRtl: Boolean
    ) {
        check(!closed) { "print pages are closed" }
        if (!settings.wrapLongLines && continuesAtStart) {
            return
        }
        if (!continuesAtStart) {
            check(sourceLineTail.isEmpty()) { "print source line ended without its final fragment" }
            skipsOverflowSpaces = false
        }
        val nextText = if (skipsOverflowSpaces) fragment.trimStart(' ') else fragment
        if (skipsOverflowSpaces && nextText.isEmpty() && continuesAtEnd) return
        skipsOverflowSpaces = false
        val text = if (sourceLineTail.isEmpty()) nextText else sourceLineTail + nextText
        if (text.isEmpty()) {
            addEmptyLine()
            return
        }
        val textWidth =
            if (settings.wrapLongLines) {
                layout.bodyWidthPixels
            } else {
                maxOf(
                    layout.bodyWidthPixels,
                    ceil(Layout.getDesiredWidth(text, bodyPaint)).toInt()
                )
            }
        val textLayout =
            StaticLayout.Builder
                .obtain(text, 0, text.length, bodyPaint, textWidth)
                .setTextDirection(
                    if (isRtl) {
                        TextDirectionHeuristics.RTL
                    } else {
                        TextDirectionHeuristics.LTR
                    }
                )
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .setIncludePad(false)
                .setLineSpacing(0f, BODY_LINE_SPACING_MULTIPLIER)
                .build()
        check(textLayout.lineCount > 0) { "nonempty print block produced no layout lines" }
        val horizontalOffset =
            if (!settings.wrapLongLines &&
                textLayout.getParagraphDirection(0) == Layout.DIR_RIGHT_TO_LEFT
            ) {
                layout.bodyWidthPixels - textWidth
            } else {
                0
            }
        // Retain the last visual line until its next fragment can finish word wrapping.
        val completedLines = if (settings.wrapLongLines && continuesAtEnd) {
            textLayout.lineCount - 1
        } else {
            textLayout.lineCount
        }
        sourceLineTail = if (completedLines < textLayout.lineCount) {
            val tailStart = textLayout.getLineStart(completedLines)
            if (text.length - tailStart > PRINT_VIEWPORT_MAX_BLOCK_UTF16_UNITS) {
                val trailingSpaceStart = text.indexOfLast { it != ' ' } + 1
                val retainedEnd = tailStart + PRINT_VIEWPORT_MAX_BLOCK_UTF16_UNITS
                if (trailingSpaceStart > retainedEnd ||
                    bodyPaint.measureText(text, trailingSpaceStart, retainedEnd) <= textWidth
                ) {
                    throw IOException("print line exceeds the bounded layout limit")
                }
                // Retain the visible line and enough spaces to preserve its overflowing wrap.
                skipsOverflowSpaces = true
                text.substring(tailStart, retainedEnd)
            } else {
                text.substring(tailStart)
            }
        } else {
            ""
        }
        addLayout(
            textLayout,
            endLineExclusive = completedLines,
            horizontalOffsetPixels = horizontalOffset
        )
    }

    /** Adds one presentation-ready semantic Markdown block. */
    fun addMarkdown(
        block: MarkdownRenderBlock,
        footnoteNumbers: Map<String, Int>,
        connectsAlertSpacing: Boolean,
        continuesAtEnd: Boolean = false,
        followingBlocks: List<MarkdownPrintBlock> = emptyList()
    ) {
        check(!closed) { "print pages are closed" }
        require(!continuesAtEnd || block.text.endsWith('\n')) {
            "Markdown print slice must end at a logical line boundary"
        }
        val measured = if (block.kind == MarkdownBlockKind.Rule) {
            null
        } else {
            layoutMarkdownBlock(block, footnoteNumbers, continuesAtEnd)
        }
        if (measured != null) {
            val keepHeight = if (block.kind == MarkdownBlockKind.Heading) {
                markdownHeadingKeepHeight(measured, followingBlocks, footnoteNumbers)
            } else {
                measured.leadingHeight
            }
            if (shouldAdvanceMarkdownPrintGroup(
                    layout.bodyTopPixels,
                    layout.bodyBottomPixels,
                    bodyY,
                    keepHeight,
                    if (block.continuesPrevious) 0 else blockSpacing
                )
            ) {
                advancePage()
            }
        }
        if (!block.continuesPrevious && bodyY > layout.bodyTopPixels) {
            addBlockSpacing(
                quoteRailInsetPixels =
                    if (connectsAlertSpacing) markdownQuoteRailInset(block) else null
            )
        }
        if (block.kind == MarkdownBlockKind.Rule) {
            addRule(block)
            return
        }
        val indent = markdownBlockIndent(block)
        if (measured == null) {
            addEmptyLine()
            return
        }
        val textLayout = measured.text
        addLayout(
            textLayout = textLayout,
            endLineExclusive = textLayout.lineCount - if (continuesAtEnd) 1 else 0,
            leftInsetPixels = indent,
            quoteRailInsetPixels = markdownQuoteRailInset(block),
            drawsBlockBackground =
                block.kind == MarkdownBlockKind.Code ||
                    block.kind == MarkdownBlockKind.HtmlLiteral,
            textRole = markdownPdfTextRole(block)
        )
    }

    /** Measures both the visible prefix and its first content line using the final print styles. */
    private fun layoutMarkdownBlock(
        block: MarkdownRenderBlock,
        footnoteNumbers: Map<String, Int>,
        continuesAtEnd: Boolean
    ): MarkdownPrintLayout? {
        configureMarkdownPaint(block)
        val availableWidth = layout.bodyWidthPixels - markdownBlockIndent(block)
        check(availableWidth > 0) { "Markdown print indent leaves no text width" }
        val content = markdownPrintText(
            block,
            footnoteNumbers,
            resources,
            formulaBounds(availableWidth)
        )
        val text = content.text
        if (text.isEmpty()) return null
        val textLayout = StaticLayout.Builder
            .obtain(text, 0, text.length, TextPaint(markdownPaint), availableWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, BODY_LINE_SPACING_MULTIPLIER)
            .build()
        check(textLayout.lineCount > 0) { "nonempty Markdown block produced no layout lines" }
        return MarkdownPrintLayout(
            textLayout,
            if (block.kind == MarkdownBlockKind.Code &&
                !block.continuesPrevious && !continuesAtEnd &&
                textLayout.lineCount <= MARKDOWN_PRINT_COMPACT_CODE_MAX_LINES
            ) {
                textLayout.lineCount
            } else {
                textLayout.getLineForOffset(content.contentStart.coerceAtMost(text.lastIndex)) + 1
            }
        )
    }

    /** Keeps a heading with useful content, including a short introduction to an illustration. */
    private fun markdownHeadingKeepHeight(
        heading: MarkdownPrintLayout,
        followingBlocks: List<MarkdownPrintBlock>,
        footnoteNumbers: Map<String, Int>
    ): Int {
        val following = followingBlocks.firstOrNull() ?: return heading.text.height
        val block = following.block
        // Tables have their own cell layout and header/first-row pagination.
        if (block.kind == MarkdownBlockKind.TableRow ||
            block.kind == MarkdownBlockKind.Rule
        ) {
            return heading.text.height
        }
        val next = layoutMarkdownBlock(block, footnoteNumbers, following.continuesAtEnd)
            ?: return heading.text.height
        val followingLines = if (block.kind == MarkdownBlockKind.Heading) {
            next.text.lineCount
        } else {
            maxOf(next.leadingLineCount, minOf(2, next.text.lineCount))
        }
        val minimum =
            heading.text.height + blockSpacing + next.text.getLineBottom(followingLines - 1)
        val illustration = followingBlocks.getOrNull(1)
        if (block.kind == MarkdownBlockKind.Paragraph &&
            next.text.lineCount <= MARKDOWN_PRINT_INTRODUCTION_MAX_LINES &&
            illustration != null
        ) {
            val drawing = layoutMarkdownBlock(
                illustration.block,
                footnoteNumbers,
                illustration.continuesAtEnd
            )
            if (drawing != null && !illustration.continuesAtEnd &&
                drawing.leadingLineCount == drawing.text.lineCount
            ) {
                val group = heading.text.height + blockSpacing + next.text.height +
                    blockSpacing + drawing.leadingHeight
                if (group <= layout.bodyBottomPixels - layout.bodyTopPixels) return group
            }
        }
        return if (minimum <= layout.bodyBottomPixels - layout.bodyTopPixels) {
            minimum
        } else {
            heading.text.height
        }
    }

    /** Adds one bounded semantic table row with aligned equal-width cells. */
    fun addMarkdownTableRow(
        block: MarkdownRenderBlock,
        followingRow: MarkdownRenderBlock?,
        columnCount: Int,
        isFirstRow: Boolean,
        footnoteNumbers: Map<String, Int>,
        connectsAlertSpacing: Boolean
    ) {
        check(!closed) { "print pages are closed" }
        require(block.kind == MarkdownBlockKind.TableRow) { "Markdown block is not a table row" }
        require(followingRow == null || followingRow.kind == MarkdownBlockKind.TableRow) {
            "Markdown table continuation is not a table row"
        }
        require(columnCount >= markdownPrintTableColumnCount(block)) {
            "Markdown print table has too few columns"
        }
        val indent = markdownBlockIndent(block)
        val availableWidth = layout.bodyWidthPixels - indent
        val horizontalPadding = pointsToPixels(MARKDOWN_TABLE_CELL_HORIZONTAL_PADDING_POINTS)
        val verticalPadding = pointsToPixels(MARKDOWN_TABLE_CELL_VERTICAL_PADDING_POINTS)
        val usesSemanticTable =
            supportsSemanticMarkdownPrintTable(
                columnCount = columnCount,
                availableWidthPixels = availableWidth,
                horizontalPaddingPixels = horizontalPadding
            )
        if (!usesSemanticTable) {
            addMarkdown(
                block =
                    if (isFirstRow) {
                        block
                    } else {
                        block.copy(continuesPrevious = true)
                    },
                footnoteNumbers = footnoteNumbers,
                connectsAlertSpacing = connectsAlertSpacing
            )
            return
        }
        val rowLayout =
            layoutMarkdownTableRow(
                block = block,
                columnCount = columnCount,
                availableWidth = availableWidth,
                horizontalPadding = horizontalPadding,
                verticalPadding = verticalPadding,
                footnoteNumbers = footnoteNumbers
            )
        val rowHeight = rowLayout.heightPixels
        if (rowHeight > layout.bodyBottomPixels - layout.bodyTopPixels) {
            addMarkdown(
                block =
                    if (isFirstRow) {
                        block
                    } else {
                        block.copy(continuesPrevious = true)
                    },
                footnoteNumbers = footnoteNumbers,
                connectsAlertSpacing = connectsAlertSpacing
            )
            return
        }
        if (isFirstRow && !block.continuesPrevious && bodyY > layout.bodyTopPixels) {
            addBlockSpacing(
                quoteRailInsetPixels =
                    if (connectsAlertSpacing) markdownQuoteRailInset(block) else null
            )
        }
        if (isFirstRow && block.startsQuoteAlert) {
            addMarkdownAlertLabel(block)
            addBlockSpacing(markdownQuoteRailInset(block))
        }
        if (block.isTableHeader && followingRow != null) {
            val firstRowLayout =
                layoutMarkdownTableRow(
                    block = followingRow,
                    columnCount = columnCount,
                    availableWidth = availableWidth,
                    horizontalPadding = horizontalPadding,
                    verticalPadding = verticalPadding,
                    footnoteNumbers = footnoteNumbers
                )
            if (
                shouldAdvanceMarkdownPrintTableHeader(
                    bodyTopPixels = layout.bodyTopPixels,
                    bodyBottomPixels = layout.bodyBottomPixels,
                    bodyY = bodyY,
                    headerHeightPixels = rowHeight,
                    firstRowHeightPixels = firstRowLayout.heightPixels
                )
            ) {
                advancePage()
            }
        }
        if (bodyY + rowHeight > layout.bodyBottomPixels) {
            advancePage()
        }
        val rowTop = bodyY
        val rowBottom = Math.addExact(rowTop, rowHeight)
        val tableLeft = Math.addExact(layout.bodyLeftPixels, indent)
        pageCanvas?.let { canvas ->
            drawQuoteRail(canvas, block, rowTop, rowBottom)
            if (block.isTableHeader) {
                canvas.drawRect(
                    tableLeft.toFloat(),
                    rowTop.toFloat(),
                    layout.bodyRightPixels.toFloat(),
                    rowBottom.toFloat(),
                    tableHeaderPaint
                )
            }
            canvas.drawLine(
                tableLeft.toFloat(),
                rowTop.toFloat(),
                layout.bodyRightPixels.toFloat(),
                rowTop.toFloat(),
                dividerPaint
            )
            canvas.drawLine(
                tableLeft.toFloat(),
                rowBottom.toFloat(),
                layout.bodyRightPixels.toFloat(),
                rowBottom.toFloat(),
                dividerPaint
            )
            rowLayout.cellLayouts.forEachIndexed { columnIndex, cellLayout ->
                val cellLeft = availableWidth * columnIndex / columnCount
                val cellRight = availableWidth * (columnIndex + 1) / columnCount
                if (columnIndex != 0) {
                    val dividerX = tableLeft + cellLeft
                    canvas.drawLine(
                        dividerX.toFloat(),
                        rowTop.toFloat(),
                        dividerX.toFloat(),
                        rowBottom.toFloat(),
                        dividerPaint
                    )
                }
                canvas.withClip(
                    tableLeft + cellLeft,
                    rowTop,
                    tableLeft + cellRight,
                    rowBottom
                ) {
                    translate(
                        (tableLeft + cellLeft + horizontalPadding).toFloat(),
                        (rowTop + verticalPadding).toFloat()
                    )
                    cellLayout.draw(this)
                }
                pageText.add(
                    layout = cellLayout,
                    startLine = 0,
                    endLine = cellLayout.lineCount,
                    originLeft = (tableLeft + cellLeft + horizontalPadding).toFloat(),
                    originTop = (rowTop + verticalPadding).toFloat(),
                    clipLeft = (tableLeft + cellLeft).toFloat(),
                    clipTop = rowTop.toFloat(),
                    clipRight = (tableLeft + cellRight).toFloat(),
                    clipBottom = rowBottom.toFloat()
                )
            }
        }
        bodyY = rowBottom
    }

    /** Measures one bounded semantic table row without retaining document content. */
    private fun layoutMarkdownTableRow(
        block: MarkdownRenderBlock,
        columnCount: Int,
        availableWidth: Int,
        horizontalPadding: Int,
        verticalPadding: Int,
        footnoteNumbers: Map<String, Int>
    ): MarkdownPrintTableRowLayout {
        require(block.kind == MarkdownBlockKind.TableRow) { "Markdown block is not a table row" }
        require(columnCount >= markdownPrintTableColumnCount(block)) {
            "Markdown print table has too few columns"
        }
        configureMarkdownPaint(block)
        val rowPaint = TextPaint(markdownPaint)
        val cells = markdownPrintCells(block, footnoteNumbers)
        val cellLayouts =
            List(columnCount) { columnIndex ->
                val cell = cells.getOrNull(columnIndex)
                val cellLeft = availableWidth * columnIndex / columnCount
                val cellRight = availableWidth * (columnIndex + 1) / columnCount
                val cellWidth = cellRight - cellLeft - horizontalPadding * 2
                check(cellWidth > 0) {
                    "Markdown print table cell has no content width"
                }
                val cellText =
                    markdownPrintStyledText(
                        text = cell?.text.orEmpty(),
                        spans = cell?.spans.orEmpty(),
                        bold = block.isTableHeader,
                        formulaBounds = formulaBounds(cellWidth, verticalPadding * 2)
                    ).ifEmpty { " " }
                StaticLayout.Builder
                    .obtain(cellText, 0, cellText.length, rowPaint, cellWidth)
                    .setAlignment(markdownPrintTableAlignment(cell?.alignment))
                    .setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                    .setIncludePad(false)
                    .setLineSpacing(0f, BODY_LINE_SPACING_MULTIPLIER)
                    .build()
            }
        return MarkdownPrintTableRowLayout(
            cellLayouts = cellLayouts,
            heightPixels =
                Math.addExact(
                    cellLayouts.maxOf(StaticLayout::getHeight),
                    Math.multiplyExact(verticalPadding, 2)
                )
        )
    }

    /** Atomic formulas fit on the selected paper without cropping their vectors. */
    private fun formulaBounds(width: Int, padding: Int = 0) = PrintFormulaBounds(
        width = width.toFloat(),
        height = (
            (layout.bodyBottomPixels - layout.bodyTopPixels - padding) /
                BODY_LINE_SPACING_MULTIPLIER - 2f * markdownPaint.fontSpacing
            ).coerceAtLeast(1f)
    )

    /** Finishes the final logical page and writes the PDF page tree. */
    fun finish(includesEveryPage: Boolean): PrintRenderResult {
        check(!closed) { "print pages are closed" }
        check(sourceLineTail.isEmpty()) { "print document ended with an incomplete source line" }
        finishCurrentPage()
        if (writer.writtenPageCount == 0) {
            throw IOException("requested print range is outside this document")
        }
        writer.finish()
        val ranges =
            if (includesEveryPage) {
                listOf(PageRange.ALL_PAGES)
            } else {
                writtenRanges.toList()
            }
        return PrintRenderResult(
            totalPageCount = Math.incrementExact(pageIndex),
            writtenPageRanges = ranges
        )
    }

    /** Releases the only retained page, including its localized bitmap fallbacks. */
    override fun close() {
        if (closed) {
            return
        }
        closed = true
        pageCanvas = null
        vectorPage?.close()
        vectorPage = null
    }

    /** Adds one blank logical line with the same body rhythm as text. */
    private fun addEmptyLine() {
        if (bodyY > layout.bodyTopPixels && bodyY + emptyLineHeight > layout.bodyBottomPixels) {
            advancePage()
        }
        check(bodyY + emptyLineHeight <= layout.bodyBottomPixels) {
            "blank print line does not fit an empty page"
        }
        bodyY += emptyLineHeight
    }

    /** Adds compact separation without creating an otherwise empty page. */
    private fun addBlockSpacing(quoteRailInsetPixels: Int? = null) {
        val spacing = blockSpacing
        if (bodyY + spacing > layout.bodyBottomPixels) {
            advancePage()
        } else {
            quoteRailInsetPixels?.let { railInset ->
                pageCanvas?.let { canvas ->
                    drawQuoteRail(
                        canvas = canvas,
                        railInsetPixels = railInset,
                        top = bodyY,
                        bottom = bodyY + spacing
                    )
                }
            }
            bodyY += spacing
        }
    }

    private val blockSpacing: Int get() = (emptyLineHeight / 2).coerceAtLeast(1)

    /** Draws one semantic horizontal rule within a line-height slot. */
    private fun addRule(block: MarkdownRenderBlock) {
        if (bodyY > layout.bodyTopPixels && bodyY + emptyLineHeight > layout.bodyBottomPixels) {
            advancePage()
        }
        check(bodyY + emptyLineHeight <= layout.bodyBottomPixels) {
            "Markdown rule does not fit an empty page"
        }
        val ruleBottom = bodyY + emptyLineHeight
        pageCanvas?.let { canvas ->
            drawQuoteRail(canvas, block, bodyY, ruleBottom)
            canvas.drawLine(
                (layout.bodyLeftPixels + markdownBlockIndent(block)).toFloat(),
                bodyY + emptyLineHeight / 2f,
                layout.bodyRightPixels.toFloat(),
                bodyY + emptyLineHeight / 2f,
                dividerPaint
            )
        }
        bodyY += emptyLineHeight
    }

    /** Draws one Android text layout across as many logical pages as required. */
    private fun addLayout(
        textLayout: StaticLayout,
        endLineExclusive: Int = textLayout.lineCount,
        leftInsetPixels: Int = 0,
        quoteRailInsetPixels: Int? = null,
        drawsBlockBackground: Boolean = false,
        textRole: PdfTextRole = PdfTextRole.Paragraph,
        horizontalOffsetPixels: Int = 0
    ) {
        require(leftInsetPixels in 0 until layout.bodyWidthPixels) {
            "print text inset is outside the page body"
        }
        require(endLineExclusive in 0..textLayout.lineCount) {
            "print text line range exceeds its layout"
        }
        var startLine = 0
        while (startLine < endLineExclusive) {
            val lineTop = textLayout.getLineTop(startLine)
            val availableHeight = layout.bodyBottomPixels - bodyY
            var endLine = startLine
            while (endLine < endLineExclusive) {
                val candidateHeight = textLayout.getLineBottom(endLine) - lineTop
                if (candidateHeight > availableHeight) {
                    break
                }
                endLine++
            }
            if (endLine == startLine) {
                check(bodyY > layout.bodyTopPixels) {
                    "print text line does not fit an empty page"
                }
                advancePage()
                continue
            }
            val sliceHeight = textLayout.getLineBottom(endLine - 1) - lineTop
            val textLeft =
                (layout.bodyLeftPixels + leftInsetPixels + horizontalOffsetPixels).toFloat()
            pageCanvas?.let { canvas ->
                if (drawsBlockBackground) {
                    canvas.drawRect(
                        (layout.bodyLeftPixels + leftInsetPixels).toFloat(),
                        bodyY.toFloat(),
                        layout.bodyRightPixels.toFloat(),
                        (bodyY + sliceHeight).toFloat(),
                        markdownBlockBackgroundPaint
                    )
                }
                quoteRailInsetPixels?.let { railInset ->
                    drawQuoteRail(
                        canvas = canvas,
                        railInsetPixels = railInset,
                        top = bodyY,
                        bottom = bodyY + sliceHeight
                    )
                }
                canvas.withClip(
                    layout.bodyLeftPixels,
                    bodyY,
                    layout.bodyRightPixels,
                    bodyY + sliceHeight
                ) {
                    translate(
                        textLeft,
                        (bodyY - lineTop).toFloat()
                    )
                    textLayout.draw(this)
                }
                pageText.add(
                    layout = textLayout,
                    startLine = startLine,
                    endLine = endLine,
                    originLeft = textLeft,
                    originTop = (bodyY - lineTop).toFloat(),
                    clipLeft = layout.bodyLeftPixels.toFloat(),
                    clipTop = bodyY.toFloat(),
                    clipRight = layout.bodyRightPixels.toFloat(),
                    clipBottom = (bodyY + sliceHeight).toFloat(),
                    role = textRole
                )
            }
            bodyY += sliceHeight
            startLine = endLine
            if (startLine < endLineExclusive) {
                advancePage()
            }
        }
    }

    /** Adds one bold alert label before semantic table content. */
    private fun addMarkdownAlertLabel(block: MarkdownRenderBlock) {
        val label = markdownAlertPrintLabel(block, resources) ?: return
        val labelText = SpannableStringBuilder(label)
        labelText.setSpan(
            StyleSpan(Typeface.BOLD),
            0,
            labelText.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        val indent = markdownBlockIndent(block)
        val availableWidth = layout.bodyWidthPixels - indent
        val labelLayout =
            StaticLayout.Builder
                .obtain(labelText, 0, labelText.length, markdownPaint, availableWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .setIncludePad(false)
                .setLineSpacing(0f, BODY_LINE_SPACING_MULTIPLIER)
                .build()
        addLayout(
            textLayout = labelLayout,
            leftInsetPixels = indent,
            quoteRailInsetPixels = markdownQuoteRailInset(block)
        )
    }

    /** Configures the reusable base paint for one semantic block kind. */
    private fun configureMarkdownPaint(block: MarkdownRenderBlock) {
        markdownPaint.color =
            if (block.kind == MarkdownBlockKind.Heading) PrintAccentColor else Color.BLACK
        markdownPaint.textSize =
            layout.bodyTextSizePixels * markdownHeadingScale(block)
        markdownPaint.typeface =
            when (block.kind) {
                MarkdownBlockKind.Code,
                MarkdownBlockKind.HtmlLiteral -> Typeface.MONOSPACE

                MarkdownBlockKind.Heading ->
                    Typeface.create(settings.fontFamily.typeface, Typeface.BOLD)

                else -> settings.fontFamily.typeface
            }
    }

    /** Returns the bounded physical indent for nested lists and quotations. */
    private fun markdownBlockIndent(block: MarkdownRenderBlock): Int {
        val depth = block.quoteDepth + (block.listDepth - 1).coerceAtLeast(0)
        if (depth == 0) {
            return 0
        }
        val nominalIndent =
            bodyPaint.measureText(" ".repeat(MARKDOWN_INDENT_SPACES)).toInt() * depth
        return nominalIndent.coerceAtMost(
            layout.bodyWidthPixels / MARKDOWN_MAXIMUM_INDENT_FRACTION
        )
    }

    /** Returns the left inset for one visible quote or alert rail. */
    private fun markdownQuoteRailInset(block: MarkdownRenderBlock): Int? {
        if (block.quoteDepth == 0) {
            return null
        }
        val indentUnit =
            bodyPaint.measureText(" ".repeat(MARKDOWN_INDENT_SPACES)).toInt().coerceAtLeast(1)
        val requestedInset = Math.multiplyExact(indentUnit, block.quoteDepth - 1)
        val maximumInset =
            (markdownBlockIndent(block) - pointsToPixels(MARKDOWN_QUOTE_RAIL_WIDTH_POINTS))
                .coerceAtLeast(0)
        return requestedInset.coerceAtMost(maximumInset)
    }

    /** Draws one block's quote rail when it belongs to a quotation. */
    private fun drawQuoteRail(canvas: Canvas, block: MarkdownRenderBlock, top: Int, bottom: Int) {
        markdownQuoteRailInset(block)?.let { railInset ->
            drawQuoteRail(canvas, railInset, top, bottom)
        }
    }

    /** Draws one exact quote-rail segment inside the page body. */
    private fun drawQuoteRail(canvas: Canvas, railInsetPixels: Int, top: Int, bottom: Int) {
        require(railInsetPixels >= 0) { "Markdown quote rail inset must be nonnegative" }
        require(top <= bottom) { "Markdown quote rail bounds are reversed" }
        val left = Math.addExact(layout.bodyLeftPixels, railInsetPixels)
        val right = Math.addExact(left, pointsToPixels(MARKDOWN_QUOTE_RAIL_WIDTH_POINTS))
        canvas.drawRect(
            left.toFloat(),
            top.toFloat(),
            right.toFloat(),
            bottom.toFloat(),
            quoteRailPaint
        )
    }

    /** Converts a positive physical point length to at least one raster pixel. */
    private fun pointsToPixels(points: Float): Int {
        require(points > 0f) { "print point length must be positive" }
        return ceil(points / POINTS_PER_INCH * layout.rasterDpi).toInt().coerceAtLeast(1)
    }

    /** Finishes one page and prepares the next bounded logical page. */
    private fun advancePage() {
        finishCurrentPage()
        if (pageIndex >= MAXIMUM_LOGICAL_PRINT_PAGES - 1) {
            throw IOException("document exceeds the print page limit")
        }
        pageIndex = Math.incrementExact(pageIndex)
        bodyY = layout.bodyTopPixels
        prepareCurrentPage()
    }

    /** Creates and decorates a vector page only when it was requested. */
    private fun prepareCurrentPage() {
        pageText.clear()
        if (!selection.contains(pageIndex)) {
            pageCanvas = null
            return
        }
        check(vectorPage == null) { "previous print page was retained" }
        val page = PdfVectorPage(layout.monochrome).also { vectorPage = it }
        val canvas = writer.canvas(page, layout)
        pageCanvas = canvas
        if (settings.showFileName) {
            val titleText =
                TextUtils.ellipsize(
                    title,
                    headerPaint,
                    layout.bodyWidthPixels.toFloat(),
                    TextUtils.TruncateAt.END
                ).toString()
            canvas.drawText(
                titleText,
                layout.bodyLeftPixels.toFloat(),
                layout.headerBaselinePixels,
                headerPaint
            )
            canvas.drawLine(
                layout.bodyLeftPixels.toFloat(),
                layout.headerDividerPixels,
                layout.bodyRightPixels.toFloat(),
                layout.headerDividerPixels,
                dividerPaint
            )
        }
        if (settings.showPageNumbers) {
            canvas.drawLine(
                layout.bodyLeftPixels.toFloat(),
                layout.footerDividerPixels,
                layout.bodyRightPixels.toFloat(),
                layout.footerDividerPixels,
                dividerPaint
            )
            canvas.drawText(
                resources.getString(R.string.print_page_number, Math.incrementExact(pageIndex)),
                layout.bodyRightPixels.toFloat(),
                layout.footerBaselinePixels,
                footerPaint
            )
        }
    }

    /** Writes and forgets the current page only when it was requested. */
    private fun finishCurrentPage() {
        if (selection.contains(pageIndex)) {
            val page = checkNotNull(vectorPage) { "requested print page has no drawing" }
            writer.writePage(
                page = page,
                layout = layout,
                text = pageText
            )
            writtenRanges.append(pageIndex)
        }
        pageCanvas = null
        vectorPage?.close()
        vectorPage = null
        pageText.clear()
    }
}

/** Maps rendered block semantics to standard PDF text roles without exporting Markdown syntax. */
private fun markdownPdfTextRole(block: MarkdownRenderBlock): PdfTextRole = when (block.kind) {
    MarkdownBlockKind.Heading -> when (block.headingLevel) {
        1 -> PdfTextRole.Heading1
        2 -> PdfTextRole.Heading2
        3 -> PdfTextRole.Heading3
        4 -> PdfTextRole.Heading4
        5 -> PdfTextRole.Heading5
        else -> PdfTextRole.Heading6
    }

    MarkdownBlockKind.Code, MarkdownBlockKind.HtmlLiteral -> PdfTextRole.Code

    else -> PdfTextRole.Paragraph
}

/** Builds printable text and inert visual spans for one semantic Markdown block. */
private data class PrintFormulaBounds(val width: Float, val height: Float)

private data class MarkdownPrintText(val text: CharSequence, val contentStart: Int)

private fun markdownPrintText(
    block: MarkdownRenderBlock,
    footnoteNumbers: Map<String, Int>,
    resources: Resources,
    formulaBounds: PrintFormulaBounds
): MarkdownPrintText {
    val builder = SpannableStringBuilder()
    if (!block.continuesPrevious) {
        markdownAlertPrintLabel(block, resources)?.let { label ->
            val labelStart = builder.length
            builder.append(label)
            builder.setSpan(
                StyleSpan(Typeface.BOLD),
                labelStart,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            builder.append('\n')
        }
        val prefixStart = builder.length
        when (block.kind) {
            MarkdownBlockKind.ListItem ->
                when {
                    block.continuesListItem -> Unit
                    block.isTaskChecked || block.isTaskUnchecked -> Unit
                    block.isOrderedListItem -> builder.append("${block.listNumber}. ")
                    else -> builder.append("• ")
                }

            MarkdownBlockKind.Footnote ->
                builder.append("${footnoteNumbers[block.metadata] ?: block.metadata}. ")

            MarkdownBlockKind.HtmlLiteral ->
                builder.append(resources.getString(R.string.markdown_inert_html)).append('\n')

            MarkdownBlockKind.Code ->
                if (block.metadata.isNotEmpty()) {
                    builder.append(block.metadata)
                    builder.append('\n')
                }

            else -> Unit
        }
        if (prefixStart < builder.length) {
            builder.setSpan(
                StyleSpan(Typeface.BOLD),
                prefixStart,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            if (
                block.kind == MarkdownBlockKind.HtmlLiteral ||
                block.kind == MarkdownBlockKind.Code
            ) {
                builder.setSpan(
                    TypefaceSpan("sans-serif"),
                    prefixStart,
                    builder.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }
    val printContent = markdownPrintContent(block, footnoteNumbers)
    val contentStart = builder.length
    builder.append(printContent.text)
    applyMarkdownPrintSpans(
        builder = builder,
        spans = printContent.spans,
        offset = contentStart,
        formulaBounds = formulaBounds,
        surfaceColor = if (block.kind == MarkdownBlockKind.Code ||
            block.kind == MarkdownBlockKind.HtmlLiteral
        ) {
            PrintBlockBackgroundColor
        } else {
            Color.WHITE
        }
    )
    illustrationFallbackMessages(block.spans).forEach { message ->
        builder.append('\n')
        val start = builder.length
        builder.append(
            resources.getString(message)
        )
        builder.setSpan(
            TypefaceSpan("sans-serif"),
            start,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        builder.setSpan(
            RelativeSizeSpan(0.8f),
            start,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
    if (block.isTableHeader && builder.isNotEmpty()) {
        builder.setSpan(
            StyleSpan(Typeface.BOLD),
            0,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
    return MarkdownPrintText(builder, contentStart)
}

/** Builds printable styled text without adding block-level labels or markers. */
private fun markdownPrintStyledText(
    text: String,
    spans: List<MarkdownInlineSpan>,
    bold: Boolean = false,
    formulaBounds: PrintFormulaBounds
): CharSequence {
    val builder = SpannableStringBuilder(text)
    applyMarkdownPrintSpans(
        builder = builder,
        spans = spans,
        offset = 0,
        formulaBounds = formulaBounds
    )
    if (bold && builder.isNotEmpty()) {
        builder.setSpan(
            StyleSpan(Typeface.BOLD),
            0,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
    return builder
}

/** Applies validated inline Markdown styling at one printable text offset. */
private fun applyMarkdownPrintSpans(
    builder: SpannableStringBuilder,
    spans: List<MarkdownInlineSpan>,
    offset: Int,
    formulaBounds: PrintFormulaBounds,
    surfaceColor: Int = Color.WHITE
) {
    require(offset in 0..builder.length) { "Markdown print span offset is outside the text" }
    spans.forEach { span ->
        val start = Math.addExact(offset, span.start)
        val end = Math.addExact(offset, span.end)
        require(start in offset..end && end <= builder.length) {
            "Markdown print span is outside the text"
        }
        if (span.styles and MARKDOWN_SPAN_STYLE_STRONG != 0) {
            builder.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (span.styles and MARKDOWN_SPAN_STYLE_EMPHASIS != 0) {
            builder.setSpan(
                StyleSpan(Typeface.ITALIC),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        if (span.styles and MARKDOWN_SPAN_STYLE_CODE != 0 ||
            span.illustration is IllustrationResult.Fallback
        ) {
            builder.setSpan(TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(
                BackgroundColorSpan(Color.argb(MARKDOWN_CODE_BACKGROUND_ALPHA, 0, 0, 0)),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        if (span.styles and MARKDOWN_SPAN_STYLE_STRIKETHROUGH != 0) {
            builder.setSpan(StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (span.styles and MARKDOWN_SPAN_STYLE_SUPERSCRIPT != 0) {
            builder.setSpan(SuperscriptSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(RelativeSizeSpan(0.75f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (span.styles and MARKDOWN_SPAN_STYLE_SUBSCRIPT != 0) {
            builder.setSpan(SubscriptSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(RelativeSizeSpan(0.75f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (span.destination != null) {
            builder.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(
                ForegroundColorSpan(PrintAccentColor),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }
    // Replace from the end so every original range and existing style remains correctly rebased.
    // Newlines inside TeX must not split the formula into unrelated Android paragraphs.
    spans.asReversed().forEach { span ->
        val rendered = span.illustration as? IllustrationResult.Rendered ?: return@forEach
        val start = offset + span.start
        val end = offset + span.end
        val source = builder.subSequence(start, end).toString()
        builder.replace(start, end, "\uFFFC")
        builder.setSpan(
            IllustrationPrintSpan(
                rendered.drawing,
                source,
                formulaBounds.width,
                formulaBounds.height,
                surfaceColor
            ),
            start,
            start + 1,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
}

/** Returns a visible print label for the first block of one GFM alert. */
private fun markdownAlertPrintLabel(block: MarkdownRenderBlock, resources: Resources): String? {
    if (!block.startsQuoteAlert) {
        return null
    }
    val label =
        when (block.quoteKind) {
            MarkdownQuoteKind.Note -> resources.getString(R.string.markdown_alert_note)
            MarkdownQuoteKind.Tip -> resources.getString(R.string.markdown_alert_tip)
            MarkdownQuoteKind.Important -> resources.getString(R.string.markdown_alert_important)
            MarkdownQuoteKind.Warning -> resources.getString(R.string.markdown_alert_warning)
            MarkdownQuoteKind.Caution -> resources.getString(R.string.markdown_alert_caution)
            null -> error("Markdown alert start lacks its validated kind")
        }
    return label
}

/** Maps one semantic table alignment to Android's paragraph alignment. */
private fun markdownPrintTableAlignment(alignment: MarkdownTableAlignment?): Layout.Alignment =
    when (alignment) {
        MarkdownTableAlignment.Center -> Layout.Alignment.ALIGN_CENTER

        MarkdownTableAlignment.Right -> Layout.Alignment.ALIGN_OPPOSITE

        MarkdownTableAlignment.None,
        MarkdownTableAlignment.Left,
        null -> Layout.Alignment.ALIGN_NORMAL
    }

/** Returns the base-size multiplier for one semantic heading level. */
private fun markdownHeadingScale(block: MarkdownRenderBlock): Float {
    if (block.kind != MarkdownBlockKind.Heading) {
        return 1f
    }
    return when (block.headingLevel) {
        1 -> 1.8f
        2 -> 1.6f
        3 -> 1.4f
        4 -> 1.25f
        5 -> 1.12f
        else -> 1f
    }
}

/** Returns the platform typeface selected for source printing. */
private val PrintFontFamily.typeface: Typeface
    get() =
        when (this) {
            PrintFontFamily.SansSerif -> Typeface.SANS_SERIF
            PrintFontFamily.Serif -> Typeface.SERIF
            PrintFontFamily.Monospace -> Typeface.MONOSPACE
        }
