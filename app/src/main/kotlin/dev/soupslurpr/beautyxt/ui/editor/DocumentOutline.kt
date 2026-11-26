/* Builds and displays a transient outline of one rendered document revision. */
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.maxLengthTrim
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.markdown.MarkdownBlockKind
import dev.soupslurpr.beautyxt.ui.UiText
import dev.soupslurpr.beautyxt.ui.asString

private const val OUTLINE_TITLE_MAX_CODE_POINTS = 160
private val OutlineLevelIndent = 12.dp

/** Represents one outline heading under an optional filter query. */
internal data class FilteredOutlineEntry(
    val entryIndex: Int,
    val entry: DocumentOutlineEntry,
    val isContextOnly: Boolean
)

/** Filters headings case-insensitively while preserving ancestor headings for hierarchy context. */
internal fun filterDocumentOutline(
    entries: List<DocumentOutlineEntry>,
    query: String
): List<FilteredOutlineEntry> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) {
        return entries.mapIndexed { index, entry ->
            FilteredOutlineEntry(entryIndex = index, entry = entry, isContextOnly = false)
        }
    }
    val matched = BooleanArray(entries.size) { index ->
        entries[index].title.contains(trimmed, ignoreCase = true)
    }
    val included = matched.copyOf()
    val ancestorStack = ArrayList<Int>(6)
    entries.forEachIndexed { index, entry ->
        while (ancestorStack.isNotEmpty() && entries[ancestorStack.last()].level >= entry.level) {
            ancestorStack.removeAt(ancestorStack.lastIndex)
        }
        if (matched[index]) {
            ancestorStack.forEach { ancestorIndex -> included[ancestorIndex] = true }
        }
        ancestorStack.add(index)
    }
    return buildList {
        entries.forEachIndexed { index, entry ->
            if (included[index]) {
                add(
                    FilteredOutlineEntry(
                        entryIndex = index,
                        entry = entry,
                        isContextOnly = !matched[index]
                    )
                )
            }
        }
    }
}

/** Identifies a heading without retaining another copy of its full source. */
internal data class DocumentOutlineEntry(
    val itemIndex: Int,
    val level: Int,
    val title: String,
    val sourceOffset: Long = 0L,
    val blockIndex: Int = itemIndex,
    val itemPositionFraction: Float = 0f
) {
    init {
        require(itemIndex >= 0) { "outline item index must be nonnegative" }
        require(level in 1..6) { "outline heading level must be between one and six" }
        require(sourceOffset >= 0L) { "outline source offset must be nonnegative" }
        require(itemPositionFraction in 0f..1f) { "outline position fraction must be in 0..1" }
    }
}

/** Builds bounded display titles on the same worker that prepares preview layout. */
internal fun documentOutline(items: List<MarkdownPreviewItem>): List<DocumentOutlineEntry> =
    buildList {
        items.forEachIndexed { itemIndex, item ->
            // Estimate visual line weight per block (respecting hard line breaks) so a long heading
            // or multi-line prose block does not skew section thresholds inside grouped items.
            fun blockVisualWeight(block: dev.soupslurpr.beautyxt.markdown.MarkdownRenderBlock): Int {
                val wrappedLines = block.text.split('\n')
                    .sumOf { line -> ((line.length + 39) / 40).coerceAtLeast(1) }
                return if (block.kind == MarkdownBlockKind.Heading) wrappedLines + 2 else wrappedLines + 1
            }
            val totalWeight = item.blocks.sumOf(::blockVisualWeight).coerceAtLeast(1)
            var weightBefore = 0
            item.blocks.forEachIndexed { localIndex, block ->
                val fraction = (weightBefore.toFloat() / totalWeight.toFloat()).coerceIn(0f, 1f)
                if (block.kind == MarkdownBlockKind.Heading && !block.continuesPrevious) {
                    val titleEnd = block.text.offsetByCodePoints(
                        0,
                        minOf(
                            OUTLINE_TITLE_MAX_CODE_POINTS,
                            block.text.codePointCount(0, block.text.length)
                        )
                    )
                    val title = block.text.substring(0, titleEnd).trim()
                    add(
                        DocumentOutlineEntry(
                            itemIndex = itemIndex,
                            level = block.headingLevel,
                            title = title,
                            sourceOffset = block.source.start,
                            blockIndex = item.firstBlockIndex + localIndex,
                            itemPositionFraction = fraction
                        )
                    )
                }
                weightBefore += blockVisualWeight(block)
            }
        }
    }

/** Precomputed visible-item lookup for O(log N) allocation-free active-heading tracking during scroll. */
internal class VisibleOutlineLookup(
    /** Pairs of `(outlineEntryIndex, visibleReadingItemIndex)` sorted by `visibleReadingItemIndex`. */
    val visibleEntries: List<Pair<Int, Int>>
)

/** Precomputes the sorted visible-outline lookup whenever layout or `<details>` expansion changes. */
internal fun visibleOutlineLookup(
    layout: MarkdownPreviewLayout,
    detailsExpanded: Map<String, Boolean>
): VisibleOutlineLookup {
    val outline = layout.outline
    if (outline.isEmpty()) return VisibleOutlineLookup(emptyList())
    if (layout.detailsSummaryKeyById.isEmpty()) {
        return VisibleOutlineLookup(outline.mapIndexed { idx, entry -> idx to entry.itemIndex })
    }
    val visibleItems = visibleMarkdownPreviewItems(layout, detailsExpanded)
    val pairs = ArrayList<Pair<Int, Int>>(outline.size)
    var visibleCursor = 0
    outline.forEachIndexed { entryIdx, entry ->
        val targetBlock = entry.blockIndex
        while (
            visibleCursor < visibleItems.size &&
            visibleItems[visibleCursor].firstBlockIndex + visibleItems[visibleCursor].blocks.size <= targetBlock
        ) {
            visibleCursor += 1
        }
        val item = visibleItems.getOrNull(visibleCursor)
        if (item != null && targetBlock in item.firstBlockIndex until (item.firstBlockIndex + item.blocks.size)) {
            pairs.add(entryIdx to visibleCursor)
        }
    }
    return VisibleOutlineLookup(pairs)
}

/** Finds the active heading at or before the visible item via binary search with zero per-scroll allocations. */
internal fun activeOutlineEntry(
    entries: List<DocumentOutlineEntry>,
    itemIndex: Int,
    itemScrollOffset: Int = 0,
    itemSize: Int = 0,
    visibleLookup: VisibleOutlineLookup? = null
): Int? {
    require(itemIndex >= 0) { "visible item index must be nonnegative" }
    val scrollFraction = if (itemScrollOffset > 0 && itemSize > 0) {
        (itemScrollOffset.toFloat() / itemSize.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    if (visibleLookup != null) {
        val pairs = visibleLookup.visibleEntries
        var start = 0
        var end = pairs.size
        while (start < end) {
            val middle = start + (end - start) / 2
            if (pairs[middle].second <= itemIndex) {
                start = middle + 1
            } else {
                end = middle
            }
        }
        val lastAtOrBeforePos = start - 1
        if (lastAtOrBeforePos < 0) return null
        if (pairs[lastAtOrBeforePos].second < itemIndex) {
            return pairs[lastAtOrBeforePos].first
        }
        var firstAtItemPos = lastAtOrBeforePos
        while (firstAtItemPos > 0 && pairs[firstAtItemPos - 1].second == itemIndex) {
            firstAtItemPos -= 1
        }
        val countAtItem = lastAtOrBeforePos - firstAtItemPos + 1
        if (scrollFraction < entries[pairs[firstAtItemPos].first].itemPositionFraction && firstAtItemPos > 0) {
            return pairs[firstAtItemPos - 1].first
        }
        if (countAtItem <= 1 || itemScrollOffset <= 0 || itemSize <= 0) {
            return pairs[firstAtItemPos].first
        }
        var activePos = firstAtItemPos
        for (pos in (firstAtItemPos + 1)..lastAtOrBeforePos) {
            val entryFraction = entries[pairs[pos].first].itemPositionFraction
            val threshold = if (entryFraction > 0f) entryFraction else (pos - firstAtItemPos).toFloat() / countAtItem.toFloat()
            if (scrollFraction >= threshold) {
                activePos = pos
            } else {
                break
            }
        }
        return pairs[activePos].first
    }
    var start = 0
    var end = entries.size
    while (start < end) {
        val middle = start + (end - start) / 2
        if (entries[middle].itemIndex <= itemIndex) {
            start = middle + 1
        } else {
            end = middle
        }
    }
    val lastAtOrBefore = start - 1
    if (lastAtOrBefore < 0) return null
    if (entries[lastAtOrBefore].itemIndex < itemIndex) return lastAtOrBefore
    var firstAtItem = lastAtOrBefore
    while (firstAtItem > 0 && entries[firstAtItem - 1].itemIndex == itemIndex) {
        firstAtItem -= 1
    }
    val countAtItem = lastAtOrBefore - firstAtItem + 1
    if (scrollFraction < entries[firstAtItem].itemPositionFraction && firstAtItem > 0) {
        return firstAtItem - 1
    }
    if (countAtItem <= 1 || itemScrollOffset <= 0 || itemSize <= 0) {
        return firstAtItem
    }
    var active = firstAtItem
    for (idx in (firstAtItem + 1)..lastAtOrBefore) {
        val entryFraction = entries[idx].itemPositionFraction
        val threshold = if (entryFraction > 0f) entryFraction else (idx - firstAtItem).toFloat() / countAtItem.toFloat()
        if (scrollFraction >= threshold) {
            active = idx
        } else {
            break
        }
    }
    return active
}

/** Presents a virtualized table of contents with the current section identified. */
@Composable
internal fun DocumentOutlineSheet(
    title: String,
    entries: List<DocumentOutlineEntry>,
    documentListState: LazyListState,
    onSelect: (DocumentOutlineEntry) -> Unit,
    onFind: (Boolean) -> Boolean,
    onDismiss: () -> Unit,
    visibleLookup: VisibleOutlineLookup? = null
) {
    DocumentSheet(onDismiss = onDismiss, modifier = Modifier.documentFindShortcuts(onFind)) {
        DocumentOutlineContent(
            title = title,
            entries = entries,
            documentListState = documentListState,
            onSelect = onSelect,
            modifier = Modifier.documentToolFocus(),
            visibleLookup = visibleLookup
        )
    }
}

/** Shares one virtualized outline between the compact sheet and the wide document pane. */
@Composable
internal fun DocumentOutlineContent(
    title: String,
    entries: List<DocumentOutlineEntry>,
    documentListState: LazyListState,
    onSelect: (DocumentOutlineEntry) -> Unit,
    modifier: Modifier = Modifier,
    isWidePane: Boolean = false,
    showTitleHeading: Boolean = true,
    visibleLookup: VisibleOutlineLookup? = null
) {
    val activeEntry by remember(entries, visibleLookup, documentListState) {
        derivedStateOf {
            val firstIndex = documentListState.firstVisibleItemIndex
            val firstSize = documentListState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == firstIndex }
                ?.size ?: 0
            activeOutlineEntry(
                entries = entries,
                itemIndex = firstIndex,
                itemScrollOffset = documentListState.firstVisibleItemScrollOffset,
                itemSize = firstSize,
                visibleLookup = visibleLookup
            )
        }
    }
    val filterState = remember { androidx.compose.foundation.text.input.TextFieldState() }
    val filterQuery = filterState.text.toString()
    val filteredEntries = remember(entries, filterQuery) {
        filterDocumentOutline(entries, filterQuery)
    }
    val activeFilteredIndex = remember(activeEntry, filteredEntries) {
        activeEntry?.let { idx ->
            filteredEntries.indexOfFirst { it.entryIndex == idx }.takeIf { it >= 0 }
        }
    }
    val outlineListState = rememberLazyListState(initialFirstVisibleItemIndex = activeFilteredIndex ?: 0)
    val headerDividerAlpha by animateFloatAsState(
        targetValue = if (outlineListState.canScrollBackward) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "outlineHeaderDividerAlpha"
    )
    if (isWidePane) {
        LaunchedEffect(activeFilteredIndex, documentListState.isScrollInProgress) {
            val target = activeFilteredIndex ?: return@LaunchedEffect
            if (!documentListState.isScrollInProgress || outlineListState.isScrollInProgress) {
                return@LaunchedEffect
            }
            val layoutInfo = outlineListState.layoutInfo
            val visible = layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
            val fullyVisible = visible != null &&
                visible.offset >= layoutInfo.viewportStartOffset &&
                visible.offset + visible.size <= layoutInfo.viewportEndOffset
            if (!fullyVisible) {
                outlineListState.animateScrollToItem(target)
            }
        }
    }
    Column(modifier) {
        Box(Modifier.fillMaxWidth()) {
            if (showTitleHeading) {
                DocumentSheetHeading(title = stringResource(R.string.outline_title), subtitle = title)
            } else {
                Text(
                    text = title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            HorizontalDivider(
                modifier = Modifier.align(Alignment.BottomCenter),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = headerDividerAlpha)
            )
        }
        if (entries.isEmpty()) {
            Text(
                text = stringResource(R.string.outline_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                style = MaterialTheme.typography.bodyLarge
            )
        } else {
            val directMatchCount = remember(filteredEntries) {
                filteredEntries.count { !it.isContextOnly }
            }
            OutlinedTextField(
                state = filterState,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .onKeyEvent { event ->
                        // Keep Ctrl+Z / Ctrl+Y scoped to the filter field even when its own undo
                        // stack is empty so Reading-mode document history shortcuts never fire.
                        event.type == KeyEventType.KeyDown &&
                            event.isCtrlPressed &&
                            !event.isAltPressed &&
                            (event.key == Key.Z || event.key == Key.Y)
                    },
                lineLimits = TextFieldLineLimits.SingleLine,
                inputTransformation = androidx.compose.foundation.text.input.InputTransformation
                    .maxLengthTrim(OUTLINE_TITLE_MAX_CODE_POINTS),
                placeholder = { Text(stringResource(R.string.outline_filter_placeholder)) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_search),
                        contentDescription = null
                    )
                },
                trailingIcon = if (filterQuery.isNotEmpty()) {
                    {
                        IconButton(onClick = { filterState.clearText() }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_close),
                                contentDescription = stringResource(R.string.outline_filter_clear)
                            )
                        }
                    }
                } else {
                    null
                }
            )
            if (filterQuery.isNotBlank()) {
                Text(
                    text = if (directMatchCount == 0) {
                        stringResource(R.string.outline_filter_empty)
                    } else {
                        pluralStringResource(
                            R.plurals.outline_filter_matches,
                            directMatchCount,
                            directMatchCount
                        )
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                state = outlineListState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(
                    items = filteredEntries,
                    key = { _, item -> "${item.entryIndex}:${item.entry.blockIndex}" }
                ) { _, item ->
                    val entry = item.entry
                    val isCurrent = item.entryIndex == activeEntry
                    val levelDescription = stringResource(R.string.outline_level, entry.level)
                    Surface(
                        onClick = { onSelect(entry) },
                        modifier = Modifier.fillMaxWidth().semantics {
                            selected = isCurrent
                            role = Role.Button
                            stateDescription = levelDescription
                        },
                        shape = MaterialTheme.shapes.medium,
                        color = if (isCurrent) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            Color.Transparent
                        },
                        contentColor = when {
                            isCurrent -> MaterialTheme.colorScheme.onSecondaryContainer
                            item.isContextOnly -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    ) {
                        Row(
                            modifier = Modifier.heightIn(min = 56.dp)
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                .padding(start = OutlineLevelIndent * (entry.level - 1)),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = outlineEntryTitle(entry).asString(),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (entry.level <= 2 && !item.isContextOnly) {
                                    FontWeight.Medium
                                } else {
                                    FontWeight.Normal
                                },
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Resolves blank headings only at presentation, without changing source-owned titles. */
internal fun outlineEntryTitle(entry: DocumentOutlineEntry): UiText = if (entry.title.isEmpty()) {
    UiText.Resource(
        R.string.outline_untitled
    )
} else {
    UiText.Literal(entry.title)
}
