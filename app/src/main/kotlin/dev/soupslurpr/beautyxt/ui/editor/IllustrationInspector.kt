/* Full-screen pinch-to-zoom inspectors for native illustrations and Markdown tables. */
@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package dev.soupslurpr.beautyxt.ui.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.horizontalScrollAxisRange
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.illustration.IllustrationColors
import dev.soupslurpr.beautyxt.illustration.IllustrationDrawing
import dev.soupslurpr.beautyxt.illustration.IllustrationKind
import dev.soupslurpr.beautyxt.illustration.NativeIllustration
import dev.soupslurpr.beautyxt.illustration.authoredDescription
import dev.soupslurpr.beautyxt.markdown.MarkdownBlockKind
import dev.soupslurpr.beautyxt.markdown.MarkdownRenderBlock
import dev.soupslurpr.beautyxt.markdown.MarkdownTableAlignment
import dev.soupslurpr.beautyxt.markdown.markdownTableCellRanges
import dev.soupslurpr.beautyxt.sharing.ExcerptShareLease
import dev.soupslurpr.beautyxt.ui.designsystem.SingleChoiceButtons
import dev.soupslurpr.beautyxt.ui.designsystem.SingleChoiceOption
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private const val MAX_TABLE_COPY_UTF16_UNITS = 65_536
private const val MAX_INSPECTOR_CELL_DISPLAY_CHARS = 2_048

/** Identifies an active full-screen inspector overlay in Reading mode. */
internal sealed interface ReadingInspectorTarget {
    data class Illustration(
        val kind: IllustrationKind,
        val drawing: NativeIllustration,
        val sourceText: String
    ) : ReadingInspectorTarget

    data class Table(
        val blocks: List<MarkdownRenderBlock>
    ) : ReadingInspectorTarget
}

/** Formats Markdown table blocks back into a canonical GFM pipe table within the copy cap. */
internal fun formatTableAsMarkdown(blocks: List<MarkdownRenderBlock>): String? {
    if (!tableFitsCopyBudget(blocks, minDelimiterUnitsPerCell = 3, maxTotalUtf16Units = MAX_TABLE_COPY_UTF16_UNITS)) {
        return null
    }
    val rows = mergeLogicalTableRows(blocks, maxTotalUtf16Units = MAX_TABLE_COPY_UTF16_UNITS)
        ?: return null
    if (rows.isEmpty()) return null
    val columnCount = rows.maxOf { it.cells.size }.coerceAtLeast(1)
    val alignments = collectTableAlignments(blocks)
    val builder = StringBuilder()
    fun appendBounded(piece: String): Boolean {
        if (builder.length + piece.length > MAX_TABLE_COPY_UTF16_UNITS) return false
        builder.append(piece)
        return true
    }
    rows.forEachIndexed { rowIndex, row ->
        if (!appendBounded("|")) return null
        for (col in 0 until columnCount) {
            val raw = row.cells.getOrElse(col) { "" }
            val escaped = escapeMarkdownTableCell(raw)
            if (!appendBounded(" $escaped |")) return null
        }
        if (rowIndex == 0) {
            if (!appendBounded("\n|")) return null
            for (col in 0 until columnCount) {
                val sep = when (alignments.getOrElse(col) { MarkdownTableAlignment.None }) {
                    MarkdownTableAlignment.Left -> " :--- |"
                    MarkdownTableAlignment.Center -> " :---: |"
                    MarkdownTableAlignment.Right -> " ---: |"
                    MarkdownTableAlignment.None -> " --- |"
                }
                if (!appendBounded(sep)) return null
            }
        }
        if (rowIndex < rows.lastIndex) {
            if (!appendBounded("\n")) return null
        }
    }
    return builder.toString()
}

/** Formats Markdown table blocks as strict RFC 4180 CSV within the 65,536 UTF-16 unit cap. */
internal fun formatTableAsCsv(blocks: List<MarkdownRenderBlock>): String? {
    if (!tableFitsCopyBudget(blocks, minDelimiterUnitsPerCell = 1, maxTotalUtf16Units = MAX_TABLE_COPY_UTF16_UNITS)) {
        return null
    }
    val rows = mergeLogicalTableRows(blocks, maxTotalUtf16Units = MAX_TABLE_COPY_UTF16_UNITS)
        ?: return null
    if (rows.isEmpty()) return null
    val columnCount = rows.maxOf { it.cells.size }.coerceAtLeast(1)
    val builder = StringBuilder()
    rows.forEachIndexed { rowIndex, row ->
        for (col in 0 until columnCount) {
            if (col > 0) {
                if (builder.length + 1 > MAX_TABLE_COPY_UTF16_UNITS) return null
                builder.append(',')
            }
            val field = escapeCsvField(row.cells.getOrElse(col) { "" })
            if (builder.length + field.length > MAX_TABLE_COPY_UTF16_UNITS) return null
            builder.append(field)
        }
        if (rowIndex < rows.lastIndex) {
            if (builder.length + 2 > MAX_TABLE_COPY_UTF16_UNITS) return null
            builder.append("\r\n")
        }
    }
    return builder.toString()
}

/** Escapes displayed table cell text so copying and reparsing as GFM preserves literal text. */
internal fun escapeMarkdownTableCell(value: String): String = buildString(value.length) {
    var i = 0
    while (i < value.length) {
        when (val ch = value[i]) {
            '\r' -> {
                if (i + 1 < value.length && value[i + 1] == '\n') i += 1
                append(' ')
            }
            '\n' -> append(' ')
            '&' -> append("&amp;")
            '\\', '|', '`', '*', '_', '{', '}', '[', ']', '<', '>', '#', '!', '~', '$', '^', '(', ')', '.', '+', '-' -> {
                append('\\')
                append(ch)
            }
            else -> append(ch)
        }
        i += 1
    }
}

/** Escapes one field according to RFC 4180. */
internal fun escapeCsvField(value: String): String {
    val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
    if (!needsQuotes) return value
    return buildString(value.length + 2) {
        append('"')
        value.forEach { ch ->
            if (ch == '"') append("\"\"") else append(ch)
        }
        append('"')
    }
}

internal data class MergedTableRow(
    val isHeader: Boolean,
    val cells: List<String>
)

/** Lightweight logical-row index over fragmented table blocks (zero cell string allocations). */
internal data class IndexedTableRow(
    val isHeader: Boolean,
    val firstBlockIndex: Int,
    val lastBlockIndex: Int,
    val columnCount: Int
)

/** Allocation-free preflight rejecting tables whose serialized cells plus delimiters exceed [maxTotalUtf16Units]. */
private fun tableFitsCopyBudget(
    blocks: List<MarkdownRenderBlock>,
    minDelimiterUnitsPerCell: Int,
    maxTotalUtf16Units: Int
): Boolean {
    var totalUnits = 0L
    var seenRows = 0
    for (block in blocks) {
        if (block.kind != MarkdownBlockKind.TableRow) continue
        val fragmentCells = block.tableCellSeparators.size + 1
        val addedCells = if (block.continuesPrevious && seenRows > 0) {
            fragmentCells - 1
        } else {
            seenRows += 1
            fragmentCells
        }
        val textUnits = (block.text.length - block.tableCellSeparators.size).coerceAtLeast(0)
        // Subtract 1 on the very first cell of the table since only inter-cell/row separators add bytes.
        val delimiterCells = if (seenRows == 1 && !block.continuesPrevious && addedCells > 0) {
            addedCells - 1
        } else {
            addedCells
        }
        totalUnits += textUnits.toLong() + delimiterCells.toLong() * minDelimiterUnitsPerCell
        if (totalUnits > maxTotalUtf16Units.toLong()) return false
    }
    return true
}

/** Indexes logical rows and column counts in O(blocks) without allocating cell strings. */
internal fun indexLogicalTableRows(blocks: List<MarkdownRenderBlock>): List<IndexedTableRow> {
    val rows = ArrayList<IndexedTableRow>()
    for (blockIndex in blocks.indices) {
        val block = blocks[blockIndex]
        if (block.kind != MarkdownBlockKind.TableRow) continue
        val fragmentCells = block.tableCellSeparators.size + 1
        if (block.continuesPrevious && rows.isNotEmpty()) {
            val prev = rows[rows.lastIndex]
            rows[rows.lastIndex] = prev.copy(
                lastBlockIndex = blockIndex,
                columnCount = prev.columnCount + (fragmentCells - 1)
            )
        } else {
            rows.add(
                IndexedTableRow(
                    isHeader = block.isTableHeader,
                    firstBlockIndex = blockIndex,
                    lastBlockIndex = blockIndex,
                    columnCount = fragmentCells
                )
            )
        }
    }
    return rows
}

/** Materializes only the visible column window `[firstCol..lastCol]` without allocating off-screen ranges. */
internal fun extractVisibleTableRowCells(
    blocks: List<MarkdownRenderBlock>,
    row: IndexedTableRow,
    firstCol: Int,
    lastCol: Int,
    maxCellDisplayChars: Int = MAX_INSPECTOR_CELL_DISPLAY_CHARS
): List<String> {
    if (firstCol > lastCol) return emptyList()
    val windowSize = lastCol - firstCol + 1
    val builders = Array(windowSize) { MutableTableCell(maxCellDisplayChars) }
    var currentCol = -1
    for (blockIndex in row.firstBlockIndex..row.lastBlockIndex) {
        val block = blocks[blockIndex]
        if (block.kind != MarkdownBlockKind.TableRow) continue
        val seps = block.tableCellSeparators
        val fragmentCells = seps.size + 1
        val resumesPrev = block.continuesPrevious && currentCol >= 0
        val fragmentFirstCol = if (resumesPrev) currentCol else currentCol + 1
        val fragmentLastCol = fragmentFirstCol + fragmentCells - 1
        if (fragmentLastCol < firstCol) {
            currentCol = fragmentLastCol
            continue
        }
        if (fragmentFirstCol > lastCol) break
        val localStart = (firstCol - fragmentFirstCol).coerceAtLeast(0)
        val localEnd = (lastCol - fragmentFirstCol).coerceAtMost(fragmentCells - 1)
        for (localIdx in localStart..localEnd) {
            val start = if (localIdx == 0) 0 else seps[localIdx - 1] + 1
            val end = if (localIdx < seps.size) seps[localIdx] else block.text.length
            builders[fragmentFirstCol + localIdx - firstCol].appendSlice(block.text, start, end)
        }
        currentCol = fragmentLastCol
        if (currentCol > lastCol) break
    }
    return builders.map(MutableTableCell::build)
}

private class MutableTableCell(private val maxDisplayChars: Int?) {
    private val builder = StringBuilder()
    private var truncated = false

    fun appendSlice(source: CharSequence, start: Int, end: Int) {
        if (truncated || start >= end) return
        if (maxDisplayChars == null) {
            builder.append(source, start, end)
            return
        }
        val remaining = maxDisplayChars - builder.length
        val sliceLen = end - start
        if (sliceLen <= remaining) {
            builder.append(source, start, end)
        } else {
            if (remaining > 0) {
                builder.append(source, start, start + remaining)
            }
            builder.append('…')
            truncated = true
        }
    }

    fun build(): String = builder.toString()
}

private class MutableMergedTableRow(
    val isHeader: Boolean,
    val cells: ArrayList<MutableTableCell>
)

/** Joins fragmented table rows linearly along the separator stream (continuation fragments resume the last cell). */
internal fun mergeLogicalTableRows(
    blocks: List<MarkdownRenderBlock>,
    maxTotalUtf16Units: Int? = null,
    maxCellDisplayChars: Int? = null
): List<MergedTableRow>? {
    val rows = ArrayList<MutableMergedTableRow>()
    var totalUnits = 0
    for (block in blocks) {
        if (block.kind != MarkdownBlockKind.TableRow) continue
        val ranges = block.markdownTableCellRanges()
        if (maxTotalUtf16Units != null) {
            for ((start, end) in ranges) {
                totalUnits += (end - start)
                if (totalUnits > maxTotalUtf16Units) return null
            }
        }
        if (block.continuesPrevious && rows.isNotEmpty()) {
            val prev = rows.last()
            if (ranges.isNotEmpty()) {
                if (prev.cells.isEmpty()) {
                    for ((start, end) in ranges) {
                        prev.cells.add(MutableTableCell(maxCellDisplayChars).apply {
                            appendSlice(block.text, start, end)
                        })
                    }
                } else {
                    // The continuation fragment's first cell appends in O(1) amortized to the trailing cell.
                    val (firstStart, firstEnd) = ranges[0]
                    prev.cells.last().appendSlice(block.text, firstStart, firstEnd)
                    for (index in 1 until ranges.size) {
                        val (start, end) = ranges[index]
                        prev.cells.add(MutableTableCell(maxCellDisplayChars).apply {
                            appendSlice(block.text, start, end)
                        })
                    }
                }
            }
        } else {
            val cells = ArrayList<MutableTableCell>(ranges.size)
            for ((start, end) in ranges) {
                cells.add(MutableTableCell(maxCellDisplayChars).apply {
                    appendSlice(block.text, start, end)
                })
            }
            rows.add(MutableMergedTableRow(isHeader = block.isTableHeader, cells = cells))
        }
    }
    return rows.map { row ->
        MergedTableRow(isHeader = row.isHeader, cells = row.cells.map(MutableTableCell::build))
    }
}

private fun collectTableAlignments(blocks: List<MarkdownRenderBlock>): List<MarkdownTableAlignment> {
    val alignments = ArrayList<MarkdownTableAlignment>()
    var columnOffset = 0
    for (block in blocks) {
        if (block.kind != MarkdownBlockKind.TableRow) continue
        if (!block.continuesPrevious && alignments.isNotEmpty()) break
        val fragmentColumns = block.tableCellSeparators.size + 1
        for (localCol in 0 until fragmentColumns) {
            val col = columnOffset + localCol
            if (col >= alignments.size) {
                alignments.add(block.tableAlignments.getOrElse(localCol) { MarkdownTableAlignment.None })
            }
        }
        columnOffset += fragmentColumns - 1
    }
    return alignments
}

/** Computes the minimum-zoom scale that fits an illustration inside the inspector viewport. */
internal fun computeIllustrationFitScale(
    viewportW: Float,
    viewportH: Float,
    paddingPx: Float,
    drawingWidth: Float,
    drawingHeight: Float
): Float {
    val usableW = (viewportW - paddingPx * 2f).coerceAtLeast(1f)
    val usableH = (viewportH - paddingPx * 2f).coerceAtLeast(1f)
    return minOf(
        usableW / drawingWidth.coerceAtLeast(1e-4f),
        usableH / drawingHeight.coerceAtLeast(1e-4f)
    ).coerceAtLeast(1e-4f)
}

/** Full-screen pinch-to-zoom vector inspector with Copy source, Save image, and Share image. */
@Composable
internal fun IllustrationInspectorDialog(
    target: ReadingInspectorTarget.Illustration,
    documentTitle: String,
    isSavingImage: Boolean,
    saveImageOutcome: Pair<Long, Boolean>?,
    onRequestSaveImage: (NativeIllustration, IllustrationColors, QrImageFormat, String) -> Long,
    onRetainShareLease: (ExcerptShareLease) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val colors = IllustrationColors(
        ink = scheme.onSurface.toArgb(),
        surface = scheme.surfaceContainerLow.toArgb(),
        accent = scheme.primary.toArgb(),
        tone = scheme.secondaryContainer.toArgb()
    )
    val drawing = target.drawing
    val appearance = remember(drawing) { IllustrationDrawing(drawing) }
    var zoom by retain(drawing) { mutableFloatStateOf(1f) }
    var pan by retain(drawing) { mutableStateOf(Offset.Zero) }
    var imageFormat by retain(drawing) { mutableStateOf(QrImageFormat.WebP) }
    var statusMessage by retain(drawing) { mutableStateOf<String?>(null) }
    var isExporting by retain(drawing) { mutableStateOf(false) }

    val savedMessage = stringResource(R.string.markdown_inspector_image_saved)
    val exportFailedMessage = stringResource(R.string.markdown_inspector_image_failed)
    val copiedMessage = stringResource(R.string.markdown_inspector_copied)
    val shareChooserTitle = stringResource(R.string.markdown_inspector_share_image)
    val titleText = stringResource(
        if (target.kind == IllustrationKind.Diagram) {
            R.string.markdown_inspector_diagram_title
        } else {
            R.string.markdown_formula
        }
    )

    var pendingSaveId by retain(target) { mutableStateOf<Long?>(null) }
    androidx.compose.runtime.LaunchedEffect(saveImageOutcome, pendingSaveId) {
        val id = pendingSaveId ?: return@LaunchedEffect
        val outcome = saveImageOutcome ?: return@LaunchedEffect
        if (outcome.first == id) {
            statusMessage = if (outcome.second) savedMessage else exportFailedMessage
            pendingSaveId = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = titleText,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.semantics { heading() },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        drawing.authoredDescription()?.let { desc ->
                            Text(
                                text = desc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.action_close)
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    var clampPanAtZoom by remember(drawing) {
                        mutableStateOf<(Offset, Float) -> Offset>({ raw, _ -> raw })
                    }
                    BoxWithConstraints(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clipToBounds()
                    ) {
                        val density = LocalDensity.current
                        val viewportW = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
                        val viewportH = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
                        val paddingPx = with(density) { 24.dp.toPx() }
                        val fitScale = computeIllustrationFitScale(
                            viewportW = viewportW,
                            viewportH = viewportH,
                            paddingPx = paddingPx,
                            drawingWidth = drawing.width,
                            drawingHeight = drawing.height
                        )
                        fun clampPan(raw: Offset, currentZoom: Float): Offset {
                            val contentW = drawing.width * fitScale * currentZoom
                            val contentH = drawing.height * fitScale * currentZoom
                            val maxX = ((contentW - viewportW) / 2f).coerceAtLeast(0f) + paddingPx
                            val maxY = ((contentH - viewportH) / 2f).coerceAtLeast(0f) + paddingPx
                            return Offset(
                                x = raw.x.coerceIn(-maxX, maxX),
                                y = raw.y.coerceIn(-maxY, maxY)
                            )
                        }
                        SideEffect {
                            clampPanAtZoom = ::clampPan
                        }
                        androidx.compose.runtime.LaunchedEffect(viewportW, viewportH, fitScale) {
                            pan = clampPan(pan, zoom)
                        }
                        val transformState = rememberTransformableState { _, zoomChange, panChange, _ ->
                            val nextZoom = (zoom * zoomChange).coerceIn(1f, 8f)
                            zoom = nextZoom
                            pan = clampPan(pan + panChange, nextZoom)
                        }
                        val description = drawing.authoredDescription() ?: titleText
                        Surface(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(drawing) {
                                    detectTapGestures(
                                        onDoubleTap = {
                                            if (zoom > 1.1f) {
                                                zoom = 1f
                                                pan = Offset.Zero
                                            } else {
                                                zoom = 2.5f
                                                pan = clampPan(pan, 2.5f)
                                            }
                                        }
                                    )
                                }
                                .transformable(state = transformState)
                                .semantics { contentDescription = description },
                            color = MaterialTheme.colorScheme.surfaceContainerLow
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val effectiveScale = fitScale * zoom
                                val drawW = drawing.width * effectiveScale
                                val drawH = drawing.height * effectiveScale
                                val clampedPan = clampPan(pan, zoom)
                                val originX = (size.width - drawW) / 2f + clampedPan.x
                                val originY = (size.height - drawH) / 2f + clampedPan.y
                                appearance.draw(
                                    canvas = drawContext.canvas.nativeCanvas,
                                    x = originX,
                                    y = originY,
                                    scale = effectiveScale,
                                    colors = colors
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        statusMessage?.let { msg ->
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = {
                                        val nextZoom = (zoom / 1.5f).coerceIn(1f, 8f)
                                        zoom = nextZoom
                                        pan = if (nextZoom <= 1.01f) {
                                            Offset.Zero
                                        } else {
                                            clampPanAtZoom(pan, nextZoom)
                                        }
                                    },
                                    enabled = zoom > 1.01f
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_zoom_out),
                                        contentDescription = stringResource(R.string.markdown_inspector_zoom_out)
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        zoom = 1f
                                        pan = Offset.Zero
                                    }
                                ) {
                                    Text("${(zoom * 100f).roundToInt()}%")
                                }
                                IconButton(
                                    onClick = {
                                        val nextZoom = (zoom * 1.5f).coerceIn(1f, 8f)
                                        zoom = nextZoom
                                        pan = clampPanAtZoom(pan, nextZoom)
                                    },
                                    enabled = zoom < 7.99f
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_zoom_in),
                                        contentDescription = stringResource(R.string.markdown_inspector_zoom_in)
                                    )
                                }
                            }
                        SingleChoiceButtons(
                            choices = listOf(
                                SingleChoiceOption("WebP", imageFormat == QrImageFormat.WebP, { imageFormat = QrImageFormat.WebP }),
                                SingleChoiceOption("PNG", imageFormat == QrImageFormat.Png, { imageFormat = QrImageFormat.Png })
                            )
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(ClipboardManager::class.java)
                                clipboard?.setPrimaryClip(ClipData.newPlainText(titleText, target.sourceText))
                                statusMessage = copiedMessage
                            }
                        ) {
                            Text(stringResource(R.string.markdown_inspector_copy_source))
                        }
                        FilledTonalButton(
                            enabled = !isExporting && !isSavingImage,
                            onClick = {
                                statusMessage = null
                                val suggestedFilename = suggestIllustrationImageName(
                                    documentTitle = documentTitle,
                                    kind = target.kind,
                                    format = imageFormat
                                )
                                pendingSaveId = onRequestSaveImage(
                                    drawing,
                                    colors,
                                    imageFormat,
                                    suggestedFilename
                                )
                            }
                        ) {
                            Text(stringResource(R.string.markdown_inspector_save_image))
                        }
                        FilledTonalButton(
                            enabled = !isExporting && !isSavingImage,
                            onClick = {
                                isExporting = true
                                statusMessage = null
                                scope.launch {
                                    val allocatedLease = java.util.concurrent.atomic.AtomicReference<ExcerptShareLease?>(null)
                                    try {
                                        val share = prepareIllustrationImageShare(
                                            context = context.applicationContext,
                                            documentTitle = documentTitle,
                                            kind = target.kind,
                                            drawing = drawing,
                                            colors = colors,
                                            format = imageFormat,
                                            onLeaseAllocated = { lease ->
                                                allocatedLease.getAndSet(lease)?.close()
                                            }
                                        )
                                        context.startActivity(Intent.createChooser(share.intent, shareChooserTitle))
                                        allocatedLease.getAndSet(null)?.let { committedLease ->
                                            onRetainShareLease(committedLease)
                                            dev.soupslurpr.beautyxt.sharing.ExcerptShares.commitIllustrationShare(
                                                context.applicationContext,
                                                committedLease.uri
                                            )
                                        }
                                    } catch (_: Exception) {
                                        allocatedLease.getAndSet(null)?.close()
                                        statusMessage = exportFailedMessage
                                    } finally {
                                        allocatedLease.getAndSet(null)?.close()
                                        isExporting = false
                                    }
                                }
                            }
                        ) {
                            Text(stringResource(R.string.markdown_inspector_share_image))
                        }
                    }
                }
            }
        }
    }
}

/** Full-screen 2D-scrollable Markdown table inspector with Copy as Markdown and Copy as CSV. */
@Composable
internal fun TableInspectorDialog(
    target: ReadingInspectorTarget.Table,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val rows = remember(target.blocks) { indexLogicalTableRows(target.blocks) }
    val columnCount = remember(rows) { rows.maxOfOrNull { it.columnCount } ?: 0 }
    val alignments = remember(target.blocks) { collectTableAlignments(target.blocks) }
    var scale by retain(target.blocks) { mutableFloatStateOf(1f) }
    var statusMessage by retain(target.blocks) { mutableStateOf<String?>(null) }
    val copiedMarkdownMessage = stringResource(R.string.markdown_inspector_copied_markdown)
    val copiedCsvMessage = stringResource(R.string.markdown_inspector_copied_csv)
    val copyLimitMessage = stringResource(R.string.markdown_table_copy_limit)
    val tableTitle = stringResource(R.string.markdown_inspector_table_title)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = tableTitle,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).semantics { heading() }
                    )
                    IconButton(onClick = { scale = (scale - 0.15f).coerceIn(0.7f, 2f) }, enabled = scale > 0.71f) {
                        Icon(painterResource(R.drawable.ic_zoom_out), stringResource(R.string.markdown_inspector_zoom_out))
                    }
                    IconButton(onClick = { scale = (scale + 0.15f).coerceIn(0.7f, 2f) }, enabled = scale < 1.99f) {
                        Icon(painterResource(R.drawable.ic_zoom_in), stringResource(R.string.markdown_inspector_zoom_in))
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(painterResource(R.drawable.ic_close), stringResource(R.string.action_close))
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val cellWidth = (140f * scale).dp
                val dividerWidth = 1.dp
                val colStrideDp = cellWidth + dividerWidth
                val textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = MaterialTheme.typography.bodyMedium.fontSize * scale,
                    lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * scale
                )
                var horizontalOffsetPx by retain(target.blocks) { mutableFloatStateOf(0f) }
                val density = LocalDensity.current
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    val viewportWidthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
                    val colStridePx = with(density) { colStrideDp.toPx() }.coerceAtLeast(1f)
                    val totalContentWidthPx = if (columnCount > 0) {
                        columnCount * colStridePx
                    } else {
                        viewportWidthPx
                    }
                    val maxHorizontalOffsetPx = (totalContentWidthPx - viewportWidthPx).coerceAtLeast(0f)
                    androidx.compose.runtime.LaunchedEffect(maxHorizontalOffsetPx) {
                        horizontalOffsetPx = horizontalOffsetPx.coerceIn(0f, maxHorizontalOffsetPx)
                    }
                    val visibleColumns by remember(
                        viewportWidthPx,
                        colStridePx,
                        columnCount
                    ) {
                        derivedStateOf {
                            if (columnCount == 0) {
                                0..-1
                            } else {
                                val offset = horizontalOffsetPx.coerceIn(0f, maxHorizontalOffsetPx)
                                val first = (offset / colStridePx).toInt().coerceIn(0, columnCount - 1)
                                val last = (((offset + viewportWidthPx) / colStridePx).toInt() + 1)
                                    .coerceIn(first, columnCount - 1)
                                first..last
                            }
                        }
                    }
                    val firstCol = visibleColumns.first
                    val lastCol = visibleColumns.last
                    val isRtl = androidx.compose.ui.platform.LocalLayoutDirection.current ==
                        androidx.compose.ui.unit.LayoutDirection.Rtl
                    val dragState = rememberDraggableState { delta ->
                        val signedDelta = if (isRtl) delta else -delta
                        horizontalOffsetPx = (horizontalOffsetPx + signedDelta).coerceIn(0f, maxHorizontalOffsetPx)
                    }
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier
                            .fillMaxSize()
                            .clipToBounds()
                            .focusable()
                            .onPreviewKeyEvent { keyEvent ->
                                if (keyEvent.type != KeyEventType.KeyDown || maxHorizontalOffsetPx <= 0f) {
                                    return@onPreviewKeyEvent false
                                }
                                val stepForward = if (isRtl) Key.DirectionLeft else Key.DirectionRight
                                val stepBackward = if (isRtl) Key.DirectionRight else Key.DirectionLeft
                                val nextOffset = when (keyEvent.key) {
                                    stepForward -> (horizontalOffsetPx + colStridePx).coerceIn(0f, maxHorizontalOffsetPx)
                                    stepBackward -> (horizontalOffsetPx - colStridePx).coerceIn(0f, maxHorizontalOffsetPx)
                                    Key.MoveHome -> 0f
                                    Key.MoveEnd -> maxHorizontalOffsetPx
                                    else -> return@onPreviewKeyEvent false
                                }
                                val moved = nextOffset != horizontalOffsetPx
                                horizontalOffsetPx = nextOffset
                                moved
                            }
                            .semantics {
                                horizontalScrollAxisRange = androidx.compose.ui.semantics.ScrollAxisRange(
                                    value = { horizontalOffsetPx.coerceIn(0f, maxHorizontalOffsetPx) },
                                    maxValue = { maxHorizontalOffsetPx },
                                    reverseScrolling = false
                                )
                                scrollBy { x, _ ->
                                    if (x == 0f || maxHorizontalOffsetPx <= 0f) {
                                        false
                                    } else {
                                        // Offset increases in reading order; Compose maps RTL
                                        // left/right accessibility actions automatically when
                                        // reverseScrolling is false.
                                        val next = (horizontalOffsetPx + x).coerceIn(0f, maxHorizontalOffsetPx)
                                        val moved = next != horizontalOffsetPx
                                        horizontalOffsetPx = next
                                        moved
                                    }
                                }
                            }
                            .draggable(state = dragState, orientation = Orientation.Horizontal)
                    ) {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(
                                items = rows,
                                key = { index, _ -> index }
                            ) { rowIndex, row ->
                                if (rowIndex > 0) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                }
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = if (row.isHeader) {
                                        MaterialTheme.colorScheme.surfaceContainerHigh
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerLow
                                    }
                                ) {
                                    Box(modifier = Modifier.fillMaxWidth().clipToBounds()) {
                                        val visibleCells = remember(target.blocks, row, firstCol, lastCol) {
                                            extractVisibleTableRowCells(
                                                blocks = target.blocks,
                                                row = row,
                                                firstCol = firstCol,
                                                lastCol = lastCol
                                            )
                                        }
                                        Row(
                                            modifier = Modifier
                                                .wrapContentWidth(align = Alignment.Start, unbounded = true)
                                                .offset {
                                                    val offset = horizontalOffsetPx.coerceIn(0f, maxHorizontalOffsetPx)
                                                    val intra = (offset - firstCol * colStridePx)
                                                        .roundToInt()
                                                    IntOffset(-intra, 0)
                                                }
                                        ) {
                                            for (colIndex in firstCol..lastCol) {
                                                if (colIndex > firstCol) {
                                                    Spacer(Modifier.width(dividerWidth))
                                                }
                                                Text(
                                                    text = visibleCells.getOrElse(colIndex - firstCol) { "" },
                                                    modifier = Modifier
                                                        .width(cellWidth)
                                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                                    style = textStyle,
                                                    fontWeight = if (row.isHeader) FontWeight.Bold else null,
                                                    textAlign = markdownTableTextAlign(
                                                        alignments.getOrElse(colIndex) { MarkdownTableAlignment.None }
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    statusMessage?.let { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = {
                                val md = formatTableAsMarkdown(target.blocks)
                                if (md == null) {
                                    statusMessage = copyLimitMessage
                                } else {
                                    context.getSystemService(ClipboardManager::class.java)
                                        ?.setPrimaryClip(ClipData.newPlainText(tableTitle, md))
                                    statusMessage = copiedMarkdownMessage
                                }
                            }
                        ) {
                            Text(stringResource(R.string.markdown_table_copy_markdown))
                        }
                        FilledTonalButton(
                            onClick = {
                                val csv = formatTableAsCsv(target.blocks)
                                if (csv == null) {
                                    statusMessage = copyLimitMessage
                                } else {
                                    context.getSystemService(ClipboardManager::class.java)
                                        ?.setPrimaryClip(ClipData.newPlainText(tableTitle, csv))
                                    statusMessage = copiedCsvMessage
                                }
                            }
                        ) {
                            Text(stringResource(R.string.markdown_table_copy_csv))
                        }
                    }
                }
            }
        }
    }
}
