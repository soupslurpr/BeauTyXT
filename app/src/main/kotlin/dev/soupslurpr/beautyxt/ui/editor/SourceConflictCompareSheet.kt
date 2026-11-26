/* Displays a read-only bounded diff between external file bytes and open local edits. */
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.document.DIFF_LINE_ENDING_CRLF_BIT
import dev.soupslurpr.beautyxt.document.DIFF_LINE_ENDING_CR_BIT
import dev.soupslurpr.beautyxt.document.DIFF_LINE_ENDING_LF_BIT
import dev.soupslurpr.beautyxt.document.DiffHunk
import dev.soupslurpr.beautyxt.document.DiffLine
import dev.soupslurpr.beautyxt.document.DiffLineKind
import dev.soupslurpr.beautyxt.document.DocumentDiff
import dev.soupslurpr.beautyxt.ui.asString

/** Identifies whether the compare sheet is resolving a source conflict or inspecting a read-only diff. */
internal sealed interface DocumentCompareMode {
    data object SourceConflict : DocumentCompareMode
    data class ReadOnlyFile(val otherTitle: String, val localTitle: String) : DocumentCompareMode
    data class ReadOnlyClipboard(val localTitle: String) : DocumentCompareMode
}

/** Pairs a read-only comparison target with its live diff state. */
internal data class GeneralDocumentComparison(
    val mode: DocumentCompareMode,
    val state: SourceConflictComparisonState
)

/** Presents a read-only comparison of external vs local changes before conflict resolution. */
@Composable
internal fun SourceConflictCompareSheet(
    state: SourceConflictComparisonState,
    hasNewerLocalEdits: Boolean,
    canSaveAsNewFile: Boolean,
    canReload: Boolean,
    canOverwriteReviewed: Boolean,
    onRefresh: () -> Unit,
    onSaveAsNewFile: () -> Unit,
    onReload: () -> Unit,
    onOverwriteReviewed: () -> Unit,
    onDismiss: () -> Unit,
    mode: DocumentCompareMode = DocumentCompareMode.SourceConflict
) {
    val isConflictMode = mode == DocumentCompareMode.SourceConflict
    val headerTitle = when (mode) {
        DocumentCompareMode.SourceConflict -> stringResource(R.string.feedback_compare_changes)
        is DocumentCompareMode.ReadOnlyFile -> stringResource(R.string.compare_file_title)
        is DocumentCompareMode.ReadOnlyClipboard -> stringResource(R.string.compare_clipboard_title)
    }
    val directionSubtitle = when (mode) {
        DocumentCompareMode.SourceConflict -> null
        is DocumentCompareMode.ReadOnlyFile -> stringResource(
            R.string.compare_direction_subtitle,
            mode.otherTitle.ifBlank { stringResource(R.string.compare_file_label) },
            mode.localTitle
        )
        is DocumentCompareMode.ReadOnlyClipboard -> stringResource(
            R.string.compare_direction_subtitle,
            stringResource(R.string.compare_clipboard_label),
            mode.localTitle
        )
    }
    val listState = rememberLazyListState()
    val headerDividerAlpha by animateFloatAsState(
        targetValue = if (listState.canScrollBackward) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "compareHeaderDividerAlpha"
    )
    DocumentSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 8.dp, top = 4.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = headerTitle,
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.semantics { heading() }
                        )
                        if (directionSubtitle != null) {
                            Text(
                                text = directionSubtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (state is SourceConflictComparisonState.Ready) {
                            val addedCount = state.diff.addedLines.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                            val removedCount = state.diff.removedLines.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                            Text(
                                text = stringResource(
                                    R.string.feedback_compare_summary,
                                    pluralStringResource(
                                        R.plurals.feedback_compare_local_lines,
                                        addedCount,
                                        state.diff.addedLines
                                    ),
                                    pluralStringResource(
                                        R.plurals.feedback_compare_file_lines,
                                        removedCount,
                                        state.diff.removedLines
                                    )
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.action_close)
                        )
                    }
                }
                HorizontalDivider(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = headerDividerAlpha)
                )
            }

            when (state) {
                SourceConflictComparisonState.Loading -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            text = stringResource(R.string.feedback_compare_loading),
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                is SourceConflictComparisonState.Failed -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = state.message.asString(),
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                        FilledTonalButton(
                            onClick = onRefresh,
                            modifier = Modifier.fillMaxWidth(),
                            shapes = ButtonDefaults.shapes()
                        ) {
                            Text(stringResource(R.string.feedback_try_again))
                        }
                    }
                }

                is SourceConflictComparisonState.Ready -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (hasNewerLocalEdits) {
                            item(key = "local-edited-notice") {
                                Surface(
                                    shape = MaterialTheme.shapes.large,
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.feedback_compare_local_edited),
                                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        FilledTonalButton(
                                            onClick = onRefresh,
                                            shapes = ButtonDefaults.shapes()
                                        ) {
                                            Text(stringResource(R.string.feedback_compare_refresh))
                                        }
                                    }
                                }
                            }
                        }
                        if (state.diff.isPartial) {
                            item(key = "partial-notice") {
                                Surface(
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ) {
                                    Text(
                                        text = stringResource(R.string.feedback_compare_partial),
                                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                        if (state.diff.hasFormatDifference) {
                            item(key = "format-notice") {
                                FormatDifferenceCard(diff = state.diff)
                            }
                        }
                        if (state.diff.textIdentical) {
                            item(key = "text-identical") {
                                Surface(
                                    shape = MaterialTheme.shapes.large,
                                    color = MaterialTheme.colorScheme.surfaceContainer
                                ) {
                                    Text(
                                        text = stringResource(
                                            if (state.diff.hasFormatDifference) {
                                                R.string.feedback_compare_text_identical
                                            } else {
                                                R.string.feedback_compare_no_differences
                                            }
                                        ),
                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else if (state.diff.hunks.isEmpty()) {
                            item(key = "differences-beyond-preview") {
                                Surface(
                                    shape = MaterialTheme.shapes.large,
                                    color = MaterialTheme.colorScheme.surfaceContainer
                                ) {
                                    val comparedCount = state.diff.comparedLines.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                                    Text(
                                        text = pluralStringResource(
                                            R.plurals.feedback_compare_differences_beyond_preview,
                                            comparedCount,
                                            state.diff.comparedLines
                                        ),
                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            items(state.diff.hunks) { hunk ->
                                DiffHunkCard(hunk = hunk)
                            }
                        }
                        if (isConflictMode) {
                            item(key = "compare-actions") {
                                CompareResolutionActions(
                                    state = state,
                                    hasNewerLocalEdits = hasNewerLocalEdits,
                                    canSaveAsNewFile = canSaveAsNewFile,
                                    canReload = canReload,
                                    canOverwriteReviewed = canOverwriteReviewed,
                                    onSaveAsNewFile = onSaveAsNewFile,
                                    onReload = onReload,
                                    onOverwriteReviewed = onOverwriteReviewed,
                                    onDismiss = onDismiss
                                )
                            }
                        }
                    }
                }
            }

            if (isConflictMode && state !is SourceConflictComparisonState.Ready) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    CompareResolutionActions(
                        state = state,
                        hasNewerLocalEdits = hasNewerLocalEdits,
                        canSaveAsNewFile = canSaveAsNewFile,
                        canReload = canReload,
                        canOverwriteReviewed = canOverwriteReviewed,
                        onSaveAsNewFile = onSaveAsNewFile,
                        onReload = onReload,
                        onOverwriteReviewed = onOverwriteReviewed,
                        onDismiss = onDismiss
                    )
                }
            }
        }
    }
}

@Composable
private fun CompareResolutionActions(
    state: SourceConflictComparisonState,
    hasNewerLocalEdits: Boolean,
    canSaveAsNewFile: Boolean,
    canReload: Boolean,
    canOverwriteReviewed: Boolean,
    onSaveAsNewFile: () -> Unit,
    onReload: () -> Unit,
    onOverwriteReviewed: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = {
                onDismiss()
                onSaveAsNewFile()
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            enabled = canSaveAsNewFile,
            shapes = ButtonDefaults.shapes()
        ) {
            Text(stringResource(R.string.feedback_save_new))
        }
        OutlinedButton(
            onClick = onReload,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            enabled = canReload,
            shapes = ButtonDefaults.shapes()
        ) {
            Text(
                text = stringResource(R.string.feedback_reload_source),
                textAlign = TextAlign.Center
            )
        }
        if (state is SourceConflictComparisonState.Ready) {
            TextButton(
                onClick = onOverwriteReviewed,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                enabled = canOverwriteReviewed && !hasNewerLocalEdits,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(
                    text = stringResource(
                        if (state.diff.isPartial) {
                            R.string.feedback_compare_overwrite_partial
                        } else {
                            R.string.feedback_compare_overwrite_reviewed
                        }
                    ),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun FormatDifferenceCard(diff: DocumentDiff) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (diff.localHasBom != diff.externalHasBom) {
                Text(
                    text = stringResource(
                        if (diff.localHasBom) {
                            R.string.feedback_compare_format_bom_added
                        } else {
                            R.string.feedback_compare_format_bom_removed
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
            }
            if (diff.externalLineEndings != diff.localLineEndings) {
                Text(
                    text = stringResource(
                        R.string.feedback_compare_format_line_endings,
                        formatLineEndingsLabel(diff.externalLineEndings),
                        formatLineEndingsLabel(diff.localLineEndings)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
            } else if (diff.formatDiffers && diff.localHasBom == diff.externalHasBom) {
                Text(
                    text = stringResource(R.string.feedback_compare_format_line_ending_positions),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun formatLineEndingsLabel(mask: Int): String {
    val parts = buildList {
        if (mask and DIFF_LINE_ENDING_LF_BIT != 0) add(stringResource(R.string.feedback_compare_line_endings_lf))
        if (mask and DIFF_LINE_ENDING_CRLF_BIT != 0) add(stringResource(R.string.feedback_compare_line_endings_crlf))
        if (mask and DIFF_LINE_ENDING_CR_BIT != 0) add(stringResource(R.string.feedback_compare_line_endings_cr))
    }
    return if (parts.isEmpty()) stringResource(R.string.feedback_compare_line_endings_none) else parts.joinToString(" / ")
}

@Composable
private fun DiffHunkCard(hunk: DiffHunk) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(
                    R.string.feedback_compare_hunk_header,
                    hunk.externalStartLine,
                    hunk.localStartLine
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            hunk.lines.forEach { line ->
                DiffLineRow(line = line)
            }
        }
    }
}

@Composable
private fun DiffLineRow(line: DiffLine) {
    val background: Color
    val foreground: Color
    val prefix: String
    val spoken: String
    when (line.kind) {
        DiffLineKind.Removed -> {
            background = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f)
            foreground = MaterialTheme.colorScheme.onErrorContainer
            prefix = "− "
            spoken = stringResource(R.string.feedback_compare_line_removed, line.text)
        }
        DiffLineKind.Added -> {
            background = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.75f)
            foreground = MaterialTheme.colorScheme.onTertiaryContainer
            prefix = "+ "
            spoken = stringResource(R.string.feedback_compare_line_added, line.text)
        }
        DiffLineKind.Context -> {
            background = Color.Transparent
            foreground = MaterialTheme.colorScheme.onSurfaceVariant
            prefix = "  "
            spoken = stringResource(R.string.feedback_compare_line_context, line.text)
        }
    }
    Text(
        text = prefix + line.text,
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .semantics { contentDescription = spoken },
        color = foreground,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall
    )
}
