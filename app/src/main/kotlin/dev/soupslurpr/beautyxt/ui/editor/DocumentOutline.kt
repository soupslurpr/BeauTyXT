/* Builds and displays a transient outline of one rendered document revision. */
package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val sourceOffset: Long = 0L
) {
    init {
        require(itemIndex >= 0) { "outline item index must be nonnegative" }
        require(level in 1..6) { "outline heading level must be between one and six" }
        require(sourceOffset >= 0L) { "outline source offset must be nonnegative" }
    }
}

/** Builds bounded display titles on the same worker that prepares preview layout. */
internal fun documentOutline(items: List<MarkdownPreviewItem>): List<DocumentOutlineEntry> =
    buildList {
        items.forEachIndexed { itemIndex, item ->
            item.blocks.forEach { block ->
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
                            itemIndex,
                            block.headingLevel,
                            title,
                            block.source.start
                        )
                    )
                }
            }
        }
    }

/** Finds the last heading at or before the visible item using logarithmic lookup. */
internal fun activeOutlineEntry(entries: List<DocumentOutlineEntry>, itemIndex: Int): Int? {
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
    return (start - 1).takeIf { index -> index >= 0 }
}

/** Presents a virtualized table of contents with the current section identified. */
@Composable
internal fun DocumentOutlineSheet(
    title: String,
    entries: List<DocumentOutlineEntry>,
    documentListState: LazyListState,
    onSelect: (DocumentOutlineEntry) -> Unit,
    onDismiss: () -> Unit
) {
    val activeEntry by remember(entries, documentListState) {
        derivedStateOf { activeOutlineEntry(entries, documentListState.firstVisibleItemIndex) }
    }
    DocumentSheet(onDismiss = onDismiss) {
        DocumentSheetHeading(title = stringResource(R.string.outline_title), subtitle = title)
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
                state = rememberLazyListState(initialFirstVisibleItemIndex = activeEntry ?: 0),
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
                            MaterialTheme.colorScheme.surfaceContainerLow
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
