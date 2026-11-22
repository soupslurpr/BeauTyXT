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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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

/** Finds the active heading at or before the visible item, disambiguating multiple headings in one item. */
internal fun activeOutlineEntry(
    entries: List<DocumentOutlineEntry>,
    itemIndex: Int,
    itemScrollOffset: Int = 0,
    itemSize: Int = 0
): Int? {
    require(itemIndex >= 0) { "visible item index must be nonnegative" }
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
    val scrollFraction = if (itemScrollOffset > 0 && itemSize > 0) {
        (itemScrollOffset.toFloat() / itemSize.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
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
    onDismiss: () -> Unit
) {
    DocumentSheet(onDismiss = onDismiss, modifier = Modifier.documentFindShortcuts(onFind)) {
        DocumentOutlineContent(title, entries, documentListState, onSelect, Modifier.documentToolFocus())
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
    showTitleHeading: Boolean = true
) {
    val activeEntry by remember(entries, documentListState) {
        derivedStateOf {
            val firstIndex = documentListState.firstVisibleItemIndex
            val firstSize = documentListState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == firstIndex }
                ?.size ?: 0
            activeOutlineEntry(
                entries = entries,
                itemIndex = firstIndex,
                itemScrollOffset = documentListState.firstVisibleItemScrollOffset,
                itemSize = firstSize
            )
        }
    }
    val outlineListState = rememberLazyListState(initialFirstVisibleItemIndex = activeEntry ?: 0)
    val headerDividerAlpha by animateFloatAsState(
        targetValue = if (outlineListState.canScrollBackward) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "outlineHeaderDividerAlpha"
    )
    if (isWidePane) {
        LaunchedEffect(activeEntry, documentListState.isScrollInProgress) {
            val target = activeEntry ?: return@LaunchedEffect
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
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                state = outlineListState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(entries) { index, entry ->
                    val isCurrent = index == activeEntry
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
                        contentColor = if (isCurrent) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
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
                                fontWeight = if (entry.level <=
                                    2
                                ) {
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
