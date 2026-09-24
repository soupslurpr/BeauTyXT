package dev.soupslurpr.beautyxt.ui.editor

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.document.DocumentFormat
import dev.soupslurpr.beautyxt.document.DocumentLineEnding
import dev.soupslurpr.beautyxt.document.DocumentMetrics
import dev.soupslurpr.beautyxt.document.DocumentRemovalAction
import dev.soupslurpr.beautyxt.document.DocumentRemovalCapabilities
import dev.soupslurpr.beautyxt.ui.UiText
import dev.soupslurpr.beautyxt.ui.asString
import java.text.NumberFormat

private val FileInfoHorizontalPadding = 24.dp
private val FileInfoSectionSpacing = 20.dp
private val FileInfoEntryPadding = 16.dp
private val FileInfoCompactVerticalPadding = 8.dp
private val FileInfoActionSpacing = 12.dp
private val FileInfoProgressSize = 20.dp
private val FileInfoHorizontalRowMinWidth = 320.dp

/** Contains one label, value, and optional explanation shown in File info. */
private data class FileInfoEntry(
    val label: String,
    val value: String,
    val supportingText: String? = null
)

/** Displays live document and source facts without exposing source identity. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FileInfoSheet(session: EditorSession, onDismiss: () -> Unit) {
    val metrics = session.state.metrics
    if (metrics == null) {
        LaunchedEffect(session) { onDismiss() }
        return
    }
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    val numberFormat = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    val sheetState =
        rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
        )
    val sourceMetadata = session.sourceMetadata
    val documentRemovalStatus = session.documentRemovalStatus
    val removalInProgress =
        documentRemovalStatus is DocumentRemovalStatus.Removing ||
            documentRemovalStatus is DocumentRemovalStatus.Succeeded
    LaunchedEffect(session, session.hasDocumentSource) {
        if (session.hasDocumentSource) {
            session.refreshDocumentRemovalCapabilities()
        }
    }
    val documentEntries =
        listOf(
            FileInfoEntry(
                label = stringResource(R.string.file_info_format),
                value = stringResource(
                    if (session.documentFormat ==
                        DocumentFormat.Markdown
                    ) {
                        R.string.format_markdown
                    } else {
                        R.string.format_plain_text
                    }
                )
            ),
            FileInfoEntry(
                label = stringResource(R.string.file_info_size),
                value = Formatter.formatFileSize(context, metrics.serializedByteLength),
                supportingText = fileInfoExactBytes(
                    context,
                    metrics.serializedByteLength,
                    numberFormat
                )
            ),
            FileInfoEntry(
                label = stringResource(R.string.file_info_lines),
                value = numberFormat.format(metrics.lineCount)
            ),
            FileInfoEntry(
                label = stringResource(R.string.file_info_words),
                value = numberFormat.format(metrics.wordCount),
                supportingText = stringResource(R.string.file_info_word_description)
            ),
            FileInfoEntry(
                label = stringResource(R.string.file_info_characters),
                value = numberFormat.format(metrics.characterLength)
            )
        )
    val encodingEntries =
        listOf(
            FileInfoEntry(
                label = stringResource(R.string.file_info_encoding),
                value = if (metrics.hasUtf8Bom) {
                    stringResource(
                        R.string.file_info_utf8_bom
                    )
                } else {
                    stringResource(R.string.file_info_utf8)
                },
                supportingText =
                    if (metrics.hasUtf8Bom) {
                        stringResource(R.string.file_info_bom_preserved)
                    } else {
                        stringResource(R.string.file_info_no_bom)
                    }
            ),
            FileInfoEntry(
                label = stringResource(R.string.file_info_line_endings),
                value = fileInfoLineEndings(metrics).asString()
            ),
            FileInfoEntry(
                label = stringResource(R.string.file_info_new_lines),
                value = fileInfoLineEnding(metrics.insertedLineEnding)
            )
        )
    val sourceEntries =
        buildList {
            add(
                FileInfoEntry(
                    label = stringResource(R.string.file_info_access),
                    value = fileInfoSourceAccess(
                        session.hasDocumentSource,
                        session.isViewOnly
                    ).asString()
                )
            )
            add(
                FileInfoEntry(
                    label = stringResource(R.string.file_info_save_state),
                    value = fileInfoSaveState(
                        session.sourceSaveStatus,
                        session.isViewOnly
                    ).asString()
                )
            )
            sourceMetadata?.mimeType?.let { mimeType ->
                add(
                    FileInfoEntry(
                        label = stringResource(R.string.file_info_provider_type),
                        value = mimeType
                    )
                )
            }
            sourceMetadata?.lastModifiedEpochMillis?.let { modified ->
                add(
                    FileInfoEntry(
                        label = stringResource(R.string.file_info_provider_modified),
                        value = formatProviderModifiedTime(context, modified),
                        supportingText = stringResource(
                            R.string.file_info_provider_timestamp_description
                        )
                    )
                )
            }
        }

    ModalBottomSheet(
        onDismissRequest = {
            if (!removalInProgress) {
                onDismiss()
            }
        },
        sheetState = sheetState,
        sheetGesturesEnabled = !removalInProgress
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                        )
                    )
                    .padding(horizontal = FileInfoHorizontalPadding)
                    .padding(bottom = FileInfoHorizontalPadding)
        ) {
            Text(
                text = stringResource(R.string.file_info_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(Modifier.height(8.dp))
            SelectionContainer {
                Text(
                    text = session.title,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Spacer(Modifier.height(FileInfoSectionSpacing))
            FileInfoSection(
                title = stringResource(R.string.file_info_document),
                entries = documentEntries
            )
            Spacer(Modifier.height(FileInfoSectionSpacing))
            FileInfoSection(
                title = stringResource(R.string.file_info_text_format),
                entries = encodingEntries
            )
            Spacer(Modifier.height(FileInfoSectionSpacing))
            FileInfoSection(
                title = stringResource(R.string.file_info_source),
                entries = sourceEntries
            )
            if (session.hasDocumentSource) {
                Spacer(Modifier.height(FileInfoSectionSpacing))
                FileActionsSection(
                    status = documentRemovalStatus,
                    unavailableReason = session.documentRemovalUnavailableReason,
                    onRefresh = session::refreshDocumentRemovalCapabilities,
                    onRequest = session::requestDocumentRemovalConfirmation
                )
            }
        }
    }
    val confirming = documentRemovalStatus as? DocumentRemovalStatus.Confirming
    if (confirming != null) {
        DocumentRemovalConfirmationDialog(
            action = confirming.action,
            onConfirm = session::confirmDocumentRemoval,
            onDismiss = session::dismissDocumentRemovalConfirmation
        )
    }
}

/** Displays provider-advertised trash and permanent deletion actions. */
@Composable
private fun FileActionsSection(
    status: DocumentRemovalStatus,
    unavailableReason: UiText?,
    onRefresh: () -> Unit,
    onRequest: (DocumentRemovalAction) -> Unit
) {
    val capabilities =
        when (status) {
            is DocumentRemovalStatus.Ready -> status.capabilities
            is DocumentRemovalStatus.Confirming -> status.capabilities
            else -> null
        }
    // Keep the sheet's height and scroll position stable beneath confirmation dialogs.
    if (capabilities != null &&
        (capabilities.canTrash || capabilities.canDelete)
    ) {
        SegmentedFileActionsSection(
            capabilities = capabilities,
            unavailableReason = unavailableReason,
            onRequest = onRequest
        )
        return
    }
    Text(
        text = stringResource(R.string.file_info_actions),
        modifier =
            Modifier
                .padding(start = 4.dp, bottom = 8.dp)
                .semantics { heading() },
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.titleSmall
    )
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(FileInfoEntryPadding),
            verticalArrangement = Arrangement.spacedBy(FileInfoActionSpacing)
        ) {
            when (status) {
                DocumentRemovalStatus.Idle,
                DocumentRemovalStatus.Checking ->
                    FileActionProgress(stringResource(R.string.file_info_checking_actions))

                is DocumentRemovalStatus.Ready ->
                    Text(
                        text = stringResource(R.string.file_info_no_actions),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )

                is DocumentRemovalStatus.Confirming ->
                    Text(
                        text = stringResource(R.string.file_info_waiting_confirmation),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )

                is DocumentRemovalStatus.Removing ->
                    FileActionProgress(
                        if (status.action == DocumentRemovalAction.Trash) {
                            stringResource(R.string.file_info_trashing)
                        } else {
                            stringResource(R.string.file_info_deleting)
                        }
                    )

                is DocumentRemovalStatus.Failed -> {
                    Text(
                        text = status.message.asString(),
                        modifier =
                            Modifier.semantics {
                                liveRegion = LiveRegionMode.Polite
                            },
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.file_info_check_again))
                    }
                }

                is DocumentRemovalStatus.Succeeded ->
                    FileActionProgress(stringResource(R.string.file_info_closing))
            }
        }
    }
}

/** Displays available provider actions as one compact segmented group. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SegmentedFileActionsSection(
    capabilities: DocumentRemovalCapabilities,
    unavailableReason: UiText?,
    onRequest: (DocumentRemovalAction) -> Unit
) {
    require(capabilities.canTrash || capabilities.canDelete) {
        "segmented file actions require at least one available action"
    }
    val actions =
        buildList {
            if (capabilities.canTrash) {
                add(DocumentRemovalAction.Trash)
            }
            if (capabilities.canDelete) {
                add(DocumentRemovalAction.Delete)
            }
        }
    Text(
        text = stringResource(R.string.file_info_actions),
        modifier =
            Modifier
                .padding(start = 4.dp, bottom = 8.dp)
                .semantics { heading() },
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.titleSmall
    )
    unavailableReason?.let { reason ->
        Text(
            text = reason.asString(),
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
    }
    val defaultShapes = ListItemDefaults.shapes()
    val colors =
        ListItemDefaults.segmentedColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        actions.forEachIndexed { actionIndex, action ->
            val permanentlyDeletes = action == DocumentRemovalAction.Delete
            val enabled = unavailableReason == null
            SegmentedListItem(
                onClick = { onRequest(action) },
                shapes =
                    ListItemDefaults.segmentedShapes(
                        index = actionIndex,
                        count = actions.size,
                        defaultShapes = defaultShapes
                    ),
                enabled = enabled,
                colors = colors,
                content = {
                    if (permanentlyDeletes && enabled) {
                        Text(
                            text = stringResource(R.string.file_info_delete),
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Text(
                            if (permanentlyDeletes) {
                                stringResource(
                                    R.string.file_info_delete
                                )
                            } else {
                                stringResource(R.string.file_info_trash)
                            }
                        )
                    }
                },
                supportingContent = {
                    Text(
                        if (permanentlyDeletes) {
                            stringResource(R.string.file_info_delete_description)
                        } else {
                            stringResource(R.string.file_info_trash_description)
                        }
                    )
                }
            )
        }
    }
}

/** Displays one compact live provider-operation status. */
@Composable
private fun FileActionProgress(message: String) {
    require(message.isNotBlank()) { "file action progress message must not be blank" }
    Row(
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(FileInfoActionSpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(FileInfoProgressSize),
            strokeWidth = 2.dp
        )
        Text(text = message, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Confirms one provider-owned destructive operation with distinct consequences. */
@Composable
private fun DocumentRemovalConfirmationDialog(
    action: DocumentRemovalAction,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val permanentlyDeletes = action == DocumentRemovalAction.Delete
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (permanentlyDeletes) {
                    stringResource(
                        R.string.file_info_delete_title
                    )
                } else {
                    stringResource(R.string.file_info_trash_title)
                }
            )
        },
        text = {
            Text(
                if (permanentlyDeletes) {
                    stringResource(R.string.file_info_delete_confirmation)
                } else {
                    stringResource(R.string.file_info_trash_confirmation)
                }
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors =
                    ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
            ) {
                Text(
                    if (permanentlyDeletes) {
                        stringResource(
                            R.string.file_info_delete
                        )
                    } else {
                        stringResource(R.string.file_info_trash)
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.file_info_cancel))
            }
        }
    )
}

/** Groups readable file facts with one section divider instead of nested cards. */
@Composable
private fun FileInfoSection(title: String, entries: List<FileInfoEntry>) {
    require(title.isNotBlank() && entries.isNotEmpty()) { "file info section must not be empty" }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            modifier = Modifier.semantics {
                heading()
            }.padding(bottom = FileInfoCompactVerticalPadding),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleSmall
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        entries.forEach { entry -> FileInfoFact(entry) }
    }
}

/** Stacks a fact only when scaled text no longer fits a readable two-column row. */
@Composable
private fun FileInfoFact(entry: FileInfoEntry) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().padding(vertical = FileInfoCompactVerticalPadding)
    ) {
        val label: @Composable () -> Unit = {
            Text(
                text = entry.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        val value: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SelectionContainer {
                    Text(text = entry.value, style = MaterialTheme.typography.bodyMedium)
                }
                entry.supportingText?.let { text ->
                    Text(
                        text = text,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        if (maxWidth >= FileInfoHorizontalRowMinWidth * LocalDensity.current.fontScale) {
            Row(horizontalArrangement = Arrangement.spacedBy(FileInfoEntryPadding)) {
                Column(modifier = Modifier.weight(1f)) { label() }
                value(Modifier.weight(1.4f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                label()
                value(Modifier.fillMaxWidth())
            }
        }
    }
}

/** Returns every serialized line-ending style currently present. */
internal fun fileInfoLineEndings(metrics: DocumentMetrics): UiText {
    val lineEndings =
        buildList {
            if (metrics.hasLfLineEndings) add("LF")
            if (metrics.hasCrlfLineEndings) add("CRLF")
            if (metrics.hasCrLineEndings) add("CR")
        }
    return when (lineEndings.size) {
        0 -> UiText.Resource(R.string.file_info_none)

        1 -> UiText.Literal(lineEndings.single())

        else -> UiText.Resource(
            R.string.file_info_mixed_line_endings,
            listOf(lineEndings.joinToString(", "))
        )
    }
}

/** Returns the concise name for one serialized line-ending style. */
internal fun fileInfoLineEnding(lineEnding: DocumentLineEnding): String = when (lineEnding) {
    DocumentLineEnding.Lf -> "LF"
    DocumentLineEnding.CrLf -> "CRLF"
    DocumentLineEnding.Cr -> "CR"
}

/** Returns the source capability without revealing source identity. */
internal fun fileInfoSourceAccess(hasDocumentSource: Boolean, isViewOnly: Boolean): UiText =
    UiText.Resource(
        when {
            !hasDocumentSource -> R.string.file_info_transient
            isViewOnly -> R.string.file_info_view_only
            else -> R.string.file_info_editable
        }
    )

/** Returns a concise source-save state for File info. */
internal fun fileInfoSaveState(status: SourceSaveStatus, isViewOnly: Boolean): UiText =
    UiText.Resource(
        when (status) {
            SourceSaveStatus.NoSource -> R.string.status_not_saved

            SourceSaveStatus.Saved ->
                if (isViewOnly) R.string.file_info_opened_version else R.string.file_info_saved

            SourceSaveStatus.Pending -> R.string.status_autosave_pending

            SourceSaveStatus.Saving -> R.string.file_info_saving

            SourceSaveStatus.Reloading -> R.string.status_source_reloading

            is SourceSaveStatus.Failed -> R.string.file_info_save_failed

            is SourceSaveStatus.Conflict -> R.string.file_info_source_changed

            is SourceSaveStatus.Uncertain -> R.string.file_info_save_uncertain
        }
    )

/** Returns an exact localized byte count with correct singular grammar. */
private fun fileInfoExactBytes(context: Context, bytes: Long, numberFormat: NumberFormat): String {
    require(bytes >= 0L) { "file byte count must be nonnegative" }
    return context.resources.getQuantityString(
        R.plurals.file_info_bytes,
        bytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        numberFormat.format(bytes)
    )
}

/** Formats one positive provider timestamp in the current local time zone. */
private fun formatProviderModifiedTime(context: Context, epochMillis: Long): String {
    require(epochMillis > 0L) { "provider modification time must be positive" }
    return DateUtils.formatDateTime(
        context,
        epochMillis,
        DateUtils.FORMAT_SHOW_DATE or
            DateUtils.FORMAT_SHOW_TIME or
            DateUtils.FORMAT_ABBREV_MONTH
    )
}
