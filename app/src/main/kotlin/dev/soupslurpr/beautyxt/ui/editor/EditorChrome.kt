/* Displays document identity, navigation, and secondary actions. */
package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.ui.HomeNfcIcon
import dev.soupslurpr.beautyxt.ui.HomeQrIcon
import dev.soupslurpr.beautyxt.ui.UiText
import dev.soupslurpr.beautyxt.ui.asString

/** Groups secondary navigation without duplicating document capability state. */
internal data class EditorNavigationActions(
    val onFind: () -> Unit,
    val onContents: () -> Unit,
    val onGoToLine: () -> Unit,
    val onFileInfo: () -> Unit,
    val onScanQr: () -> Unit,
    val onReadNfc: () -> Unit
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
    val cancellation = editorCancellation(session, onCancelSave)
    val isCancelling = session.shareStatus is ShareStatus.Cancelling ||
        session.printStatus is PrintStatus.Cancelling ||
        session.qrShareStatus is QrShareStatus.Cancelling ||
        session.nfcWriteStatus is NfcWriteStatus.Cancelling ||
        session.saveStatus is SaveStatus.CancellingDestinationPreparation ||
        session.saveStatus is SaveStatus.CancellingExport
    val showProgress = session.isClosePending || session.sourceSaveStatus.isActive() ||
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
            isViewOnly = session.isViewOnly
        ).asString()
    }
    AdaptiveEditorTopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose, enabled = navigationEnabled) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
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
                EditorStatusLine(statusDescription, showProgress, expanded)
            }
        },
        actions = {
            if (cancellation != null) {
                IconButton(onClick = cancellation.onClick) {
                    Icon(Icons.Default.Close, contentDescription = cancellation.label)
                }
            } else if (!isCancelling && !session.isClosePending) {
                if (session.presentation == EditorPresentation.Text) {
                    IconButton(onClick = actions.onFind, enabled = session.canShowFind) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = stringResource(R.string.find_in_document)
                        )
                    }
                } else {
                    IconButton(onClick = actions.onContents, enabled = contentsEnabled) {
                        Icon(
                            EditorContentsIcon,
                            contentDescription = stringResource(R.string.editor_contents)
                        )
                    }
                }
                IconButton(onClick = {
                    session.flushPendingEdit()
                    onOverflowExpandedChange(true)
                }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.editor_more_options)
                    )
                }
                DropdownMenu(
                    expanded = isOverflowExpanded,
                    onDismissRequest = { onOverflowExpandedChange(false) }
                ) {
                    if (session.presentation == EditorPresentation.Text) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.editor_go_to_line)) },
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
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.editor_file_info)) },
                        leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                        onClick = {
                            onOverflowExpandedChange(false)
                            actions.onFileInfo()
                        },
                        enabled = session.canShowFileInfo
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_scan_qr)) },
                        leadingIcon = { Icon(HomeQrIcon, contentDescription = null) },
                        onClick = {
                            onOverflowExpandedChange(false)
                            actions.onScanQr()
                        },
                        enabled = scanQrEnabled
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_read_nfc)) },
                        leadingIcon = { Icon(HomeNfcIcon, contentDescription = null) },
                        onClick = {
                            onOverflowExpandedChange(false)
                            actions.onReadNfc()
                        },
                        enabled = readNfcEnabled
                    )
                }
            }
        }
    )
}

/** Adapts the title to large text while preserving the same action positions. */
@Composable
private fun AdaptiveEditorTopAppBar(
    navigationIcon: @Composable () -> Unit,
    title: @Composable (Boolean) -> Unit,
    actions: @Composable RowScope.() -> Unit
) {
    val colors = TopAppBarDefaults.topAppBarColors(
        containerColor = Color.Transparent,
        scrolledContainerColor = Color.Transparent
    )
    val insets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    BoxWithConstraints {
        val expanded = usesExpandedEditorTopBar(LocalDensity.current.fontScale, maxHeight)
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
}

/** Separates the document's controls from its uninterrupted reading surface. */
@Composable
internal fun EditorChromeSurface(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().windowInsetsPadding(
            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
        ),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column { content() }
    }
}

/** Announces save activity without adding a second status capsule. */
@Composable
private fun EditorStatusLine(message: String, showProgress: Boolean, expanded: Boolean) {
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
        }
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
