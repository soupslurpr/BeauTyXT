/* Displays editor recovery, operation feedback, and source-save failures. */
package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.then
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.ui.asString

/** Keeps recovery actions reachable without allowing them to displace the editor. */
@Composable
internal fun EditorBody(
    session: EditorSession,
    activeDraft: ActiveEditDraft?,
    onSaveAsNewFile: () -> Unit,
    onRestartExplicitSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sourceSaveStatus = session.sourceSaveStatus
    val saveStatus = session.saveStatus
    val shareStatus = session.shareStatus
    val printStatus = session.printStatus
    val qrShareStatus = session.qrShareStatus
    val nfcWriteStatus = session.nfcWriteStatus
    val hasRecoveryMessages =
        session.isClosePending ||
            sourceSaveStatus.isTerminalFailure() ||
            shareStatus is ShareStatus.Failed ||
            printStatus is PrintStatus.Failed ||
            qrShareStatus is QrShareStatus.Failed ||
            nfcWriteStatus is NfcWriteStatus.Failed ||
            nfcWriteStatus is NfcWriteStatus.Succeeded ||
            saveStatus is SaveStatus.Failed ||
            saveStatus is SaveStatus.Cancelled ||
            saveStatus is SaveStatus.Succeeded
    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
                )
    ) {
        val maximumRecoveryHeight = recoveryPanelMaxHeight(maxHeight)
        Column(modifier = Modifier.fillMaxSize()) {
            if (hasRecoveryMessages) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = maximumRecoveryHeight)
                            .verticalScroll(rememberScrollState())
                ) {
                    PendingCloseBanner(
                        visible = session.isClosePending,
                        onKeepEditing = session::cancelPendingClose
                    )
                    SourceSaveFailureBanner(
                        status = sourceSaveStatus,
                        isViewOnly = session.isViewOnly,
                        actionEnabled = session.canStartSaveAs,
                        reloadEnabled = session.canRequestSourceReload,
                        overwriteEnabled = session.canRequestSourceOverwrite,
                        onRetry = session::retrySourceSave,
                        onReload = session::requestSourceReloadConfirmation,
                        onOverwrite = session::requestSourceOverwriteConfirmation,
                        onSaveAsNewFile = onSaveAsNewFile
                    )
                    SaveFeedback(
                        status = saveStatus,
                        actionEnabled = session.canStartSaveAs,
                        onSaveAgain = onRestartExplicitSave,
                        onDismiss = session::dismissExplicitSaveResult
                    )
                    ShareFeedback(
                        status = shareStatus,
                        retryEnabled = session.canStartShare,
                        onRetry = session::requestShare,
                        onDismiss = session::dismissShareFailure
                    )
                    PrintFeedback(
                        status = printStatus,
                        retryEnabled = session.canStartPrint,
                        onRetry = session::retryPrint,
                        onDismiss = session::dismissPrintFailure
                    )
                    QrShareFeedback(
                        status = qrShareStatus,
                        retryEnabled = session.canStartQrShare,
                        onRetry = session::requestQrShare,
                        onDismiss = session::dismissQrShareFailure
                    )
                    NfcWriteFeedback(
                        status = nfcWriteStatus,
                        retryEnabled = session.canStartNfcWrite,
                        onRetry = session::requestNfcWrite,
                        onDismiss = session::dismissNfcWriteResult
                    )
                }
            }
            EditorContent(
                session = session,
                activeDraft = activeDraft,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** Displays one cancellable close request while retained work finishes. */
@Composable
private fun PendingCloseBanner(visible: Boolean, onKeepEditing: () -> Unit) {
    if (!visible) {
        return
    }
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = EditorHorizontalPadding,
                    vertical = EditorCompactSpacing
                )
                .semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.padding(EditorSectionSpacing),
            verticalArrangement = Arrangement.spacedBy(EditorCompactSpacing)
        ) {
            Text(
                text = stringResource(R.string.feedback_closing),
                modifier = Modifier.semantics { heading() },
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall
            )
            TextButton(
                onClick = onKeepEditing,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(stringResource(R.string.feedback_keep_editing))
            }
        }
    }
}

/** Displays one accessible retained-source failure with safe recovery actions. */
@Composable
private fun SourceSaveFailureBanner(
    status: SourceSaveStatus,
    isViewOnly: Boolean,
    actionEnabled: Boolean,
    reloadEnabled: Boolean,
    overwriteEnabled: Boolean,
    onRetry: () -> Unit,
    onReload: () -> Unit,
    onOverwrite: () -> Unit,
    onSaveAsNewFile: () -> Unit
) {
    if (!status.isTerminalFailure()) {
        return
    }
    val title =
        when (status) {
            is SourceSaveStatus.Failed ->
                if (isViewOnly) {
                    stringResource(R.string.feedback_editable_not_created)
                } else {
                    stringResource(R.string.feedback_not_saved)
                }

            is SourceSaveStatus.Conflict -> stringResource(R.string.feedback_source_changed)

            is SourceSaveStatus.Uncertain -> stringResource(R.string.feedback_check_original)

            else -> error("source failure banner requires a terminal failure")
        }
    val message =
        when (status) {
            is SourceSaveStatus.Failed -> status.message.asString()
            is SourceSaveStatus.Conflict -> status.message.asString()
            is SourceSaveStatus.Uncertain -> status.message.asString()
        }
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = EditorHorizontalPadding,
                    vertical = EditorCompactSpacing
                )
                .semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.padding(EditorSectionSpacing),
            verticalArrangement = Arrangement.spacedBy(EditorCompactSpacing)
        ) {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall
            )
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
            if (status is SourceSaveStatus.Failed) {
                Button(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = actionEnabled
                ) {
                    Text(stringResource(R.string.feedback_retry_save))
                }
            }
            if (status is SourceSaveStatus.Conflict) {
                Button(
                    onClick = onSaveAsNewFile,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = actionEnabled
                ) {
                    Text(
                        if (isViewOnly) {
                            stringResource(
                                R.string.feedback_choose_another
                            )
                        } else {
                            stringResource(R.string.feedback_save_new)
                        }
                    )
                }
                OutlinedButton(
                    onClick = onReload,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = reloadEnabled
                ) {
                    Text(stringResource(R.string.feedback_reload_source))
                }
                TextButton(
                    onClick = onOverwrite,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = overwriteEnabled,
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                ) {
                    Text(stringResource(R.string.feedback_overwrite_source))
                }
            } else {
                OutlinedButton(
                    onClick = onSaveAsNewFile,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = actionEnabled
                ) {
                    Text(
                        if (isViewOnly) {
                            stringResource(
                                R.string.feedback_choose_another
                            )
                        } else {
                            stringResource(R.string.feedback_save_new)
                        }
                    )
                }
            }
        }
    }
}

/** Confirms one source-conflict choice that discards either local or external changes. */
@Composable
internal fun SourceConflictConfirmationDialog(
    resolution: SourceConflictResolution,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val reloadsSource = resolution == SourceConflictResolution.Reload
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (reloadsSource) {
                    stringResource(
                        R.string.feedback_reload_title
                    )
                } else {
                    stringResource(R.string.feedback_overwrite_title)
                }
            )
        },
        text = {
            Text(
                if (reloadsSource) {
                    stringResource(R.string.feedback_reload_confirmation)
                } else {
                    stringResource(R.string.feedback_overwrite_confirmation)
                }
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors =
                    if (reloadsSource) {
                        ButtonDefaults.buttonColors()
                    } else {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    }
            ) {
                Text(
                    if (reloadsSource) {
                        stringResource(
                            R.string.feedback_reload
                        )
                    } else {
                        stringResource(R.string.feedback_overwrite)
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.feedback_cancel))
            }
        }
    )
}

/** Displays one accessible explicit-save result that requires attention. */
@Composable
private fun SaveFeedback(
    status: SaveStatus,
    actionEnabled: Boolean,
    onSaveAgain: () -> Unit,
    onDismiss: (Long) -> Unit
) {
    when (status) {
        SaveStatus.Idle,
        is SaveStatus.Queued,
        is SaveStatus.ChoosingFormat,
        is SaveStatus.DestinationReady,
        is SaveStatus.SelectingDestination -> Unit

        is SaveStatus.PreparingDestination,
        is SaveStatus.CancellingDestinationPreparation,
        is SaveStatus.Exporting,
        is SaveStatus.CancellingExport -> Unit

        is SaveStatus.Failed ->
            ExplicitSaveResultBanner(
                title = failedSaveTitle(status.request.purpose).asString(),
                message = status.message.asString(),
                actionEnabled = actionEnabled,
                generation = status.request.generation,
                onSaveAgain = onSaveAgain,
                onDismiss = onDismiss
            )

        is SaveStatus.Cancelled ->
            ExplicitSaveResultBanner(
                title = cancelledSaveTitle(status.request.purpose).asString(),
                message = CANCELLED_SAVE_MESSAGE.asString(),
                actionEnabled = actionEnabled,
                generation = status.request.generation,
                onSaveAgain = onSaveAgain,
                onDismiss = onDismiss
            )

        is SaveStatus.Succeeded -> {
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = EditorHorizontalPadding,
                            vertical = EditorCompactSpacing
                        )
                        .semantics(mergeDescendants = true) {
                            liveRegion = LiveRegionMode.Polite
                        },
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = MaterialTheme.shapes.large
            ) {
                Column(
                    modifier = Modifier.padding(EditorSectionSpacing),
                    verticalArrangement = Arrangement.spacedBy(EditorCompactSpacing)
                ) {
                    Text(
                        text = stringResource(R.string.feedback_copy_saved),
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (status.hasNewerChanges) {
                        Text(
                            text = stringResource(R.string.feedback_newer_changes),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

/** Displays one retryable, capability-free document-share failure. */
@Composable
private fun ShareFeedback(
    status: ShareStatus,
    retryEnabled: Boolean,
    onRetry: () -> Boolean,
    onDismiss: (Long) -> Unit
) {
    val failure = status as? ShareStatus.Failed ?: return
    EditorFailureBanner(
        title = stringResource(R.string.feedback_share_failed),
        message = failure.message.asString(),
        actionLabel = stringResource(R.string.feedback_try_again),
        actionEnabled = retryEnabled,
        onAction = { onRetry() },
        onDismiss = { onDismiss(failure.generation) }
    )
}

/** Displays one retryable, capability-free native print failure. */
@Composable
private fun PrintFeedback(
    status: PrintStatus,
    retryEnabled: Boolean,
    onRetry: () -> Boolean,
    onDismiss: (Long) -> Unit
) {
    val failure = status as? PrintStatus.Failed ?: return
    EditorFailureBanner(
        title = stringResource(R.string.feedback_print_failed),
        message = failure.message.asString(),
        actionLabel = stringResource(R.string.feedback_try_again),
        actionEnabled = retryEnabled,
        onAction = { onRetry() },
        onDismiss = { onDismiss(failure.generation) }
    )
}

/** Displays one retryable, capability-free QR creation failure. */
@Composable
private fun QrShareFeedback(
    status: QrShareStatus,
    retryEnabled: Boolean,
    onRetry: () -> Boolean,
    onDismiss: (Long) -> Unit
) {
    val failure = status as? QrShareStatus.Failed ?: return
    EditorFailureBanner(
        title = stringResource(R.string.feedback_qr_failed),
        message = failure.message.asString(),
        actionLabel = stringResource(R.string.feedback_try_again),
        actionEnabled = retryEnabled,
        onAction = { onRetry() },
        onDismiss = { onDismiss(failure.generation) }
    )
}

/** Displays one retryable NFC preparation failure or verified write result. */
@Composable
private fun NfcWriteFeedback(
    status: NfcWriteStatus,
    retryEnabled: Boolean,
    onRetry: () -> Boolean,
    onDismiss: (Long) -> Unit
) {
    when (status) {
        is NfcWriteStatus.Failed ->
            EditorFailureBanner(
                title = stringResource(R.string.feedback_nfc_failed),
                message = status.message.asString(),
                actionLabel = stringResource(R.string.feedback_try_again),
                actionEnabled = retryEnabled,
                onAction = { onRetry() },
                onDismiss = { onDismiss(status.generation) }
            )

        is NfcWriteStatus.Succeeded ->
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = EditorHorizontalPadding,
                            vertical = EditorCompactSpacing
                        ).semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = MaterialTheme.shapes.large
            ) {
                Row(
                    modifier = Modifier.padding(EditorSectionSpacing),
                    horizontalArrangement = Arrangement.spacedBy(EditorCompactSpacing),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.feedback_nfc_written),
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    TextButton(onClick = { onDismiss(status.generation) }) {
                        Text(stringResource(R.string.feedback_dismiss))
                    }
                }
            }

        NfcWriteStatus.Idle,
        is NfcWriteStatus.Configuring,
        is NfcWriteStatus.Queued,
        is NfcWriteStatus.Preparing,
        is NfcWriteStatus.Cancelling,
        is NfcWriteStatus.Ready -> Unit
    }
}

/** Displays one dismissible explicit-save failure or cancellation. */
@Composable
private fun ExplicitSaveResultBanner(
    title: String,
    message: String,
    actionEnabled: Boolean,
    generation: Long,
    onSaveAgain: () -> Unit,
    onDismiss: (Long) -> Unit
) {
    EditorFailureBanner(
        title = title,
        message = message,
        actionLabel = stringResource(R.string.feedback_save_again),
        actionEnabled = actionEnabled,
        onAction = onSaveAgain,
        onDismiss = { onDismiss(generation) }
    )
}

/** Presents consistent retry and dismissal controls for an explicit operation failure. */
@Composable
private fun EditorFailureBanner(
    title: String,
    message: String,
    actionLabel: String,
    actionEnabled: Boolean,
    onAction: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = EditorHorizontalPadding,
                    vertical = EditorCompactSpacing
                )
                .semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.padding(EditorSectionSpacing),
            verticalArrangement = Arrangement.spacedBy(EditorCompactSpacing)
        ) {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall
            )
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = onAction,
                modifier = Modifier.fillMaxWidth(),
                enabled = actionEnabled
            ) {
                Text(actionLabel)
            }
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.feedback_dismiss))
            }
        }
    }
}

/** Displays and triggers a revision-safe reload after stale data is discarded. */
@Composable
internal fun StaleDocumentMessage(session: EditorSession, modifier: Modifier = Modifier) {
    CenteredActionMessage(
        message = stringResource(R.string.feedback_stale_view),
        actionLabel = stringResource(R.string.feedback_reload),
        onAction = session::reloadStaleViewport,
        modifier = modifier
    )
}

/** Displays and retries an initial viewport failure. */
@Composable
internal fun FailedViewportMessage(
    session: EditorSession,
    message: String,
    modifier: Modifier = Modifier
) {
    CenteredActionMessage(
        message = message,
        actionLabel = stringResource(R.string.feedback_retry),
        onAction = session::retryViewport,
        modifier = modifier
    )
}

/** Displays a centered message with an optional indeterminate progress indicator. */
@Composable
internal fun CenteredEditorMessage(
    message: String,
    showProgress: Boolean,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val minimumContentHeight = maxHeight
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = minimumContentHeight)
                    .padding(EditorHorizontalPadding)
                    .semantics(mergeDescendants = true) {
                        liveRegion = LiveRegionMode.Polite
                    },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement =
                Arrangement.spacedBy(
                    space = EditorSectionSpacing,
                    alignment = Alignment.CenterVertically
                )
        ) {
            if (showProgress) {
                CircularProgressIndicator(
                    modifier =
                        Modifier
                            .size(ProgressIndicatorSize)
                            .clearAndSetSemantics {}
                )
            }
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Displays a centered recovery action. */
@Composable
internal fun CenteredActionMessage(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val minimumContentHeight = maxHeight
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = minimumContentHeight)
                    .padding(EditorHorizontalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement =
                Arrangement.spacedBy(
                    space = EditorSectionSpacing,
                    alignment = Alignment.CenterVertically
                )
        ) {
            Text(
                text = message,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .semantics {
                            heading()
                            liveRegion = LiveRegionMode.Polite
                        },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
            FilledTonalButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

/** Displays one labeled, accessible viewport operation without blocking cached text. */
@Composable
internal fun ViewportProgressMessage(message: String, modifier: Modifier = Modifier) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                },
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.large
    ) {
        Row(
            modifier = Modifier.padding(EditorSectionSpacing),
            horizontalArrangement = Arrangement.spacedBy(EditorSectionSpacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier =
                    Modifier
                        .size(ProgressIndicatorSize)
                        .clearAndSetSemantics {}
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/** Displays retry and optional dismissal actions without discarding cached blocks. */
@Composable
internal fun FailedViewportRow(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null
) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                },
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.padding(EditorSectionSpacing),
            verticalArrangement = Arrangement.spacedBy(EditorCompactSpacing)
        ) {
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
            if (onDismiss == null) {
                FilledTonalButton(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.feedback_retry))
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(EditorCompactSpacing)
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors =
                            ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                    ) {
                        Text(stringResource(R.string.feedback_dismiss))
                    }
                    FilledTonalButton(
                        onClick = onRetry,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.feedback_retry))
                    }
                }
            }
        }
    }
}

/** Returns whether autosave requires explicit user recovery. */
private fun SourceSaveStatus.isTerminalFailure(): Boolean = this is SourceSaveStatus.Failed ||
    this is SourceSaveStatus.Conflict ||
    this is SourceSaveStatus.Uncertain
