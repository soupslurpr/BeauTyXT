/* Displays document identity, navigation, and secondary actions. */
package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.ui.UiText
import dev.soupslurpr.beautyxt.ui.asString
import dev.soupslurpr.beautyxt.ui.designsystem.ActionMenuItem

/** Groups secondary navigation without duplicating document capability state. */
internal data class EditorNavigationActions(
    val onFind: () -> Unit,
    val onContents: () -> Unit,
    val onGoToLine: () -> Unit,
    val onFileInfo: () -> Unit,
    val onScanQr: () -> Unit,
    val onReadNfc: () -> Unit,
    val onKeyboardShortcuts: () -> Unit = {},
    val onTextAppearance: () -> Unit = {},
    val onAppTheme: () -> Unit = {},
    val onCompareFile: () -> Unit = {},
    val onCompareClipboard: () -> Unit = {},
    val onToggleSplitView: () -> Unit = {},
    val onOpenNewWindow: () -> Unit = {}
)

/** Describes the single operation that currently offers cancellation. */
private data class EditorCancellation(val label: String, val onClick: () -> Unit)

/** Selects cancellation in the same order as serialized document operations. */
@Composable
private fun editorCancellation(
    session: EditorSession,
    onCancelSave: () -> Unit
): EditorCancellation? = when {
    session.shareStatus is ShareStatus.Queued ||
        session.shareStatus is ShareStatus.Preparing || session.shareStatus is ShareStatus.Ready ->
        EditorCancellation(stringResource(R.string.editor_cancel_share), session::cancelShare)

    session.printStatus is PrintStatus.Queued ||
        session.printStatus is PrintStatus.Preparing || session.printStatus is PrintStatus.Ready ->
        EditorCancellation(stringResource(R.string.editor_cancel_print), session::cancelPrint)

    session.qrShareStatus is QrShareStatus.Queued ||
        session.qrShareStatus is QrShareStatus.Preparing ||
        session.qrShareStatus is QrShareStatus.Ready ->
        EditorCancellation(stringResource(R.string.editor_cancel_qr), session::cancelQrShare)

    session.nfcWriteStatus is NfcWriteStatus.Queued ||
        session.nfcWriteStatus is NfcWriteStatus.Preparing ||
        session.nfcWriteStatus is NfcWriteStatus.Ready ->
        EditorCancellation(stringResource(R.string.editor_cancel_nfc), session::cancelNfcWrite)

    session.saveStatus is SaveStatus.Queued ||
        session.saveStatus is SaveStatus.PreparingDestination ||
        session.saveStatus is SaveStatus.Exporting ->
        EditorCancellation(stringResource(R.string.editor_cancel_save), onCancelSave)

    else -> null
}

/** Displays identity and save state above one focused navigation affordance. */
@Composable
internal fun EditorTopBar(
    session: EditorSession,
    actions: EditorNavigationActions,
    availableWidth: Dp,
    availableHeight: Dp,
    contentsEnabled: Boolean,
    scanQrEnabled: Boolean,
    readNfcEnabled: Boolean,
    navigationContentDescription: String,
    navigationEnabled: Boolean,
    onCancelSave: () -> Unit,
    isOverflowExpanded: Boolean,
    onOverflowExpandedChange: (Boolean) -> Unit,
    onClose: () -> Unit
) {
    val showInlineContents = session.presentation == EditorPresentation.MarkdownPreview &&
        usesInlineEditorContentsAction(
            availableWidth = availableWidth,
            fontScale = LocalDensity.current.fontScale,
            availableHeight = availableHeight
        )
    val showOverflowContents =
        session.presentation == EditorPresentation.MarkdownPreview && !showInlineContents
    val cancellation = editorCancellation(session, onCancelSave)
    val isCancelling = session.shareStatus is ShareStatus.Cancelling ||
        session.printStatus is PrintStatus.Cancelling ||
        session.qrShareStatus is QrShareStatus.Cancelling ||
        session.nfcWriteStatus is NfcWriteStatus.Cancelling ||
        session.saveStatus is SaveStatus.CancellingDestinationPreparation ||
        session.saveStatus is SaveStatus.CancellingExport
    val showProgress = session.isClosePending || session.isNewDocumentAutosaveCreating ||
        session.sourceSaveStatus.isActive() ||
        session.saveStatus.isActive() || session.shareStatus.isActive() ||
        session.printStatus.isActive() || session.qrShareStatus.isActive() ||
        session.nfcWriteStatus.isActive()
    val statusDescription = if (session.isClosePending) {
        stringResource(R.string.editor_finishing_operation)
    } else {
        saveStateDescription(
            sourceSaveStatus = session.sourceSaveStatus,
            saveStatus = session.saveStatus,
            shareStatus = session.shareStatus,
            printStatus = session.printStatus,
            qrShareStatus = session.qrShareStatus,
            nfcWriteStatus = session.nfcWriteStatus,
            hasUnsavedChanges = session.hasUnsavedChanges,
            saveUnavailableReason = session.saveUnavailableReason,
            isViewOnly = session.isViewOnly,
            newDocumentAutosaveState = session.effectiveNewDocumentAutosaveState
        ).asString()
    }
    val statusTone = if (session.isClosePending) {
        EditorStatusTone.Standard
    } else {
        saveStateTone(
            sourceSaveStatus = session.sourceSaveStatus,
            saveStatus = session.saveStatus,
            hasUnsavedChanges = session.hasUnsavedChanges,
            newDocumentAutosaveState = session.effectiveNewDocumentAutosaveState
        )
    }
    AdaptiveEditorTopAppBar(
        availableHeight = availableHeight,
        navigationIcon = {
            IconButton(onClick = onClose, enabled = navigationEnabled) {
                Icon(
                    painterResource(R.drawable.ic_arrow_back),
                    contentDescription = navigationContentDescription
                )
            }
        },
        title = { expanded ->
            Column(
                modifier = Modifier.clickable(
                    enabled = session.canShowFileInfo,
                    onClickLabel = stringResource(R.string.editor_file_info),
                    onClick = actions.onFileInfo
                )
            ) {
                Text(
                    text = session.title,
                    modifier = Modifier.semantics { heading() },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                EditorStatusLine(statusDescription, showProgress, expanded, statusTone)
            }
        },
        actions = {
            if (cancellation != null) {
                IconButton(onClick = cancellation.onClick) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = cancellation.label)
                }
            } else if (!isCancelling && !session.isClosePending) {
                run {
                    IconButton(onClick = actions.onFind, enabled = session.canShowFind) {
                        Icon(
                            painterResource(R.drawable.ic_search),
                            contentDescription = stringResource(R.string.find_in_document)
                        )
                    }
                }
                if (showInlineContents) {
                    IconButton(onClick = actions.onContents, enabled = contentsEnabled) {
                        Icon(
                            painterResource(R.drawable.ic_format_list_bulleted),
                            contentDescription = stringResource(R.string.editor_contents)
                        )
                    }
                }
                if (availableWidth >= 840.dp && session.canToggleSplitView) {
                    val splitActive = session.isSplitViewEnabled &&
                        session.presentation == EditorPresentation.Text
                    IconButton(onClick = actions.onToggleSplitView) {
                        Icon(
                            painterResource(R.drawable.ic_description),
                            contentDescription = stringResource(
                                if (splitActive) {
                                    R.string.editor_split_view_hide
                                } else {
                                    R.string.editor_split_view_show
                                }
                            ),
                            tint = if (splitActive) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
                IconButton(onClick = {
                    session.flushPendingEdit()
                    onOverflowExpandedChange(true)
                }) {
                    Icon(
                        painterResource(R.drawable.ic_more_vert),
                        contentDescription = stringResource(R.string.editor_more_options)
                    )
                }
                DropdownMenuPopup(
                    expanded = isOverflowExpanded,
                    onDismissRequest = { onOverflowExpandedChange(false) }
                ) {
                    DropdownMenuGroup(
                        shapes = MenuDefaults.groupShape(index = 0, count = 1),
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        containerColor = MenuDefaults.groupStandardContainerColor
                    ) {
                        if (showOverflowContents) {
                            ActionMenuItem(
                                shape = MenuDefaults.leadingItemShape,
                                label = stringResource(R.string.editor_contents),
                                leadingIcon = {
                                    Icon(
                                        painterResource(R.drawable.ic_format_list_bulleted),
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    onOverflowExpandedChange(false)
                                    actions.onContents()
                                },
                                enabled = contentsEnabled
                            )
                        }
                        if (session.presentation == EditorPresentation.Text) {
                            ActionMenuItem(
                                shape = MenuDefaults.leadingItemShape,
                                label = stringResource(R.string.editor_go_to_line),
                                leadingIcon = {
                                    Text("#", modifier = Modifier.clearAndSetSemantics {})
                                },
                                onClick = {
                                    onOverflowExpandedChange(false)
                                    actions.onGoToLine()
                                },
                                enabled = session.canNavigateToLine
                            )
                        }
                        ActionMenuItem(
                            shape = if (
                                session.presentation == EditorPresentation.Text ||
                                showOverflowContents
                            ) {
                                MenuDefaults.middleItemShape
                            } else {
                                MenuDefaults.leadingItemShape
                            },
                            label = stringResource(R.string.editor_file_info),
                            leadingIcon = {
                                Icon(painterResource(R.drawable.ic_info), contentDescription = null)
                            },
                            onClick = {
                                onOverflowExpandedChange(false)
                                actions.onFileInfo()
                            },
                            enabled = session.canShowFileInfo
                        )
                        ActionMenuItem(
                            shape = MenuDefaults.middleItemShape,
                            label = stringResource(R.string.appearance_title),
                            leadingIcon = { Icon(painterResource(R.drawable.ic_tune), contentDescription = null) },
                            onClick = { onOverflowExpandedChange(false); actions.onTextAppearance() }
                        )
                        ActionMenuItem(
                            shape = MenuDefaults.middleItemShape,
                            label = stringResource(R.string.app_theme_title),
                            leadingIcon = { Icon(painterResource(R.drawable.ic_tune), contentDescription = null) },
                            onClick = { onOverflowExpandedChange(false); actions.onAppTheme() }
                        )
                        if (session.hasPreviousLocation || session.hasNextLocation) {
                            ActionMenuItem(
                                shape = MenuDefaults.middleItemShape,
                                label = stringResource(R.string.location_previous),
                                leadingIcon = { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = null) },
                                enabled = session.hasPreviousLocation,
                                onClick = { onOverflowExpandedChange(false); session.returnToDocumentLocation(false) }
                            )
                            ActionMenuItem(
                                shape = MenuDefaults.middleItemShape,
                                label = stringResource(R.string.location_next),
                                leadingIcon = { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = null,
                                    modifier = Modifier.rotate(180f)) },
                                enabled = session.hasNextLocation,
                                onClick = { onOverflowExpandedChange(false); session.returnToDocumentLocation(true) }
                            )
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(MenuDefaults.HorizontalDividerPadding)
                        )
                        ActionMenuItem(
                            shape = MenuDefaults.middleItemShape,
                            label = stringResource(R.string.compare_with_file),
                            leadingIcon = { Icon(painterResource(R.drawable.ic_file_open), contentDescription = null) },
                            enabled = session.canShowFileInfo,
                            onClick = { onOverflowExpandedChange(false); actions.onCompareFile() }
                        )
                        ActionMenuItem(
                            shape = MenuDefaults.middleItemShape,
                            label = stringResource(R.string.compare_with_clipboard),
                            leadingIcon = { Icon(painterResource(R.drawable.ic_content_copy), contentDescription = null) },
                            enabled = session.canShowFileInfo,
                            onClick = { onOverflowExpandedChange(false); actions.onCompareClipboard() }
                        )
                        ActionMenuItem(
                            shape = MenuDefaults.middleItemShape,
                            label = stringResource(R.string.editor_new_window),
                            leadingIcon = { Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = null) },
                            onClick = { onOverflowExpandedChange(false); actions.onOpenNewWindow() }
                        )
                        ActionMenuItem(
                            shape = MenuDefaults.middleItemShape,
                            label = stringResource(R.string.document_keyboard_shortcuts),
                            leadingIcon = { Icon(painterResource(R.drawable.ic_info), contentDescription = null) },
                            onClick = { onOverflowExpandedChange(false); actions.onKeyboardShortcuts() }
                        )
                        ActionMenuItem(
                            shape = MenuDefaults.middleItemShape,
                            label = stringResource(R.string.action_scan_qr),
                            leadingIcon = {
                                Icon(painterResource(R.drawable.ic_qr_code_2), contentDescription = null)
                            },
                            onClick = {
                                onOverflowExpandedChange(false)
                                actions.onScanQr()
                            },
                            enabled = scanQrEnabled
                        )
                        ActionMenuItem(
                            shape = MenuDefaults.trailingItemShape,
                            label = stringResource(R.string.action_read_nfc),
                            leadingIcon = {
                                Icon(painterResource(R.drawable.ic_nfc), contentDescription = null)
                            },
                            onClick = {
                                onOverflowExpandedChange(false)
                                actions.onReadNfc()
                            },
                            enabled = readNfcEnabled
                        )
                    }
                }
            }
        }
    )
}

/** Adapts the title to large text while preserving the same action positions. */
@Composable
private fun AdaptiveEditorTopAppBar(
    availableHeight: Dp,
    navigationIcon: @Composable () -> Unit,
    title: @Composable (Boolean) -> Unit,
    actions: @Composable RowScope.() -> Unit
) {
    val colors = TopAppBarDefaults.topAppBarColors(
        containerColor = Color.Transparent,
        scrolledContainerColor = Color.Transparent
    )
    val insets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    val expanded = usesExpandedEditorTopBar(LocalDensity.current.fontScale, availableHeight)
    if (expanded) {
        LargeTopAppBar(
            title = { title(true) },
            navigationIcon = navigationIcon,
            actions = actions,
            windowInsets = insets,
            colors = colors
        )
    } else {
        TopAppBar(
            title = { title(false) },
            navigationIcon = navigationIcon,
            actions = actions,
            windowInsets = insets,
            colors = colors
        )
    }
}

/** Paints the document header edge to edge on surface with a zero-shift scroll divider. */
@Composable
internal fun EditorChromeSurface(
    showDivider: Boolean = false,
    content: @Composable () -> Unit
) {
    val dividerAlpha by animateFloatAsState(
        targetValue = if (showDivider) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "editorChromeDividerAlpha"
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) { content() }
            HorizontalDivider(
                modifier = Modifier.align(Alignment.BottomCenter),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = dividerAlpha)
            )
        }
    }
}

/** Announces save activity without adding a second status capsule. */
@Composable
private fun EditorStatusLine(
    message: String,
    showProgress: Boolean,
    expanded: Boolean,
    tone: EditorStatusTone = EditorStatusTone.Standard
) {
    val textColor = when (tone) {
        EditorStatusTone.Standard -> MaterialTheme.colorScheme.onSurfaceVariant
        EditorStatusTone.UnsavedAttention -> MaterialTheme.colorScheme.tertiary
        EditorStatusTone.Error -> MaterialTheme.colorScheme.error
    }
    val fontWeight = if (tone == EditorStatusTone.Standard) {
        FontWeight.Normal
    } else {
        FontWeight.SemiBold
    }
    Row(
        modifier = Modifier.then(if (expanded) Modifier.fillMaxWidth() else Modifier)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(EditorCompactSpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showProgress) {
            CircularProgressIndicator(
                modifier = Modifier.size(TopBarProgressIndicatorSize).clearAndSetSemantics {},
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = CompactStrokeWidth
            )
        } else if (tone == EditorStatusTone.UnsavedAttention) {
            Box(
                modifier = Modifier
                    .size(EditorCompactSpacing)
                    .background(color = textColor, shape = CircleShape)
                    .clearAndSetSemantics {}
            )
        }
        Text(
            text = message,
            color = textColor,
            fontWeight = fontWeight,
            maxLines = if (expanded) Int.MAX_VALUE else 1,
            overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

/** Returns a concise transfer-capacity description for exact or settling text. */
internal fun transferCapacityDescription(capacity: DocumentTransferCapacity): UiText {
    val textBytes = capacity.textBytes ?: return UiText.Resource(R.string.transfer_checking)
    val formattedTextBytes = formatTransferByteCount(textBytes)
    val formattedMaxTextBytes = formatTransferByteCount(capacity.maxTextBytes)
    return if (textBytes <= capacity.maxTextBytes) {
        UiText.Resource(R.string.transfer_fits, listOf(formattedTextBytes, formattedMaxTextBytes))
    } else {
        UiText.Resource(
            R.string.transfer_too_large,
            listOf(formattedTextBytes, formattedMaxTextBytes)
        )
    }
}

/** Formats one nonnegative byte count with stable English digit grouping. */
internal fun formatTransferByteCount(byteCount: Long): String {
    require(byteCount >= 0L) { "transfer byte count must be nonnegative" }
    val digits = byteCount.toString()
    return buildString(digits.length + digits.length / 3) {
        digits.forEachIndexed { digitIndex, digit ->
            if (digitIndex > 0 && (digits.length - digitIndex) % 3 == 0) {
                append(',')
            }
            append(digit)
        }
    }
}

/** Distinguishes the application payload bound from a physical tag's unknown capacity. */
internal fun nfcWriteCapacityDescription(capacity: DocumentTransferCapacity): UiText =
    if (capacity.fits == true) {
        UiText.Resource(
            R.string.nfc_write_capacity,
            listOf(formatTransferByteCount(requireNotNull(capacity.textBytes)))
        )
    } else {
        transferCapacityDescription(capacity)
    }
