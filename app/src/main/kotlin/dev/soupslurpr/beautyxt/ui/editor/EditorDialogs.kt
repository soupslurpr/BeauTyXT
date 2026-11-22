@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

/* Displays explicit save, navigation, QR, and NFC choices. */
package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.document.DocumentFormat
import dev.soupslurpr.beautyxt.transfer.TransferProtocol
import dev.soupslurpr.beautyxt.transfer.canonicalNfcTagLabelOrNull
import dev.soupslurpr.beautyxt.transfer.isValidNfcTagLabelInput
import dev.soupslurpr.beautyxt.transfer.suggestNfcTagLabel
import dev.soupslurpr.beautyxt.ui.asString
import dev.soupslurpr.beautyxt.ui.designsystem.SingleChoiceButtons
import dev.soupslurpr.beautyxt.ui.designsystem.SingleChoiceOption
import java.util.Locale

private val CompactQrDialogHeight = 600.dp

private val CompactQrCodeMaxWidth = 160.dp

private val QrCodeMaxWidth = 360.dp

/** Names the selected transfer format for display. */

private val DocumentFormat.displayName: String
    @Composable get() =
        when (this) {
            DocumentFormat.PlainText -> stringResource(R.string.format_plain_text)
            DocumentFormat.Markdown -> stringResource(R.string.format_markdown)
        }

/** Returns a QR size that stays completely visible in a short dialog viewport. */
internal fun qrCodeDialogMaximumWidth(windowHeight: Dp): Dp {
    require(windowHeight >= 0.dp) { "window height for the QR dialog must not be negative" }
    return if (windowHeight < CompactQrDialogHeight) {
        CompactQrCodeMaxWidth
    } else {
        QrCodeMaxWidth
    }
}

/** Collects one optional physical tag label before isolated NFC preparation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NfcTagLabelDialog(
    status: NfcWriteStatus.Configuring,
    onConfirm: (Long, String?) -> Boolean,
    onDismiss: () -> Unit
) {
    val inputState = retain(status.generation) { TextFieldState() }
    val confirm = {
        onConfirm(status.generation, canonicalNfcTagLabelOrNull(inputState.text.toString()))
        Unit
    }
    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(decorFitsSystemWindows = false)
    ) {
        Surface(
            modifier = Modifier.safeDrawingPadding().imePadding(),
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(EditorSectionSpacing)
            ) {
                Text(
                    stringResource(R.string.nfc_label_title),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    stringResource(R.string.nfc_label_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    state = inputState,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.nfc_label_optional)) },
                    placeholder = { Text(stringResource(R.string.nfc_label_example)) },
                    supportingText = { Text(stringResource(R.string.nfc_label_help)) },
                    inputTransformation = InputTransformation {
                        val candidate = asCharSequence().toString()
                        if (!isValidNfcTagLabelInput(candidate)) {
                            revertAllChanges()
                        } else {
                            val upper = candidate.uppercase(Locale.ROOT)
                            if (upper != candidate) {
                                val currentSelection = selection
                                replace(0, length, upper)
                                selection = currentSelection
                            }
                        }
                    },
                    keyboardOptions =
                        KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Done
                        ),
                    onKeyboardAction = { confirm() },
                    lineLimits = TextFieldLineLimits.SingleLine
                )
                TextButton(
                    onClick = { inputState.setTextAndPlaceCursorAtEnd(suggestNfcTagLabel()) },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.nfc_label_suggest))
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(
                        EditorCompactSpacing,
                        Alignment.End
                    ),
                    verticalArrangement = Arrangement.spacedBy(EditorCompactSpacing)
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                    Button(
                        onClick = confirm,
                        shapes = ButtonDefaults.shapes(),
                        contentPadding = ButtonDefaults.ContentPadding
                    ) {
                        Text(stringResource(R.string.action_continue))
                    }
                }
            }
        }
    }
}

/** Displays one bounded QR code with disclosure and lossless image export. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QrShareDialog(
    status: QrShareStatus.Ready,
    imageSaveStatus: QrImageSaveStatus,
    imageFormat: QrImageFormat,
    onFormatChange: (QrImageFormat) -> Unit,
    onSaveImage: () -> Unit,
    onDismiss: () -> Unit
) {
    val imageSaveActive = imageSaveStatus.isActive()
    val density = LocalDensity.current
    val windowHeight =
        with(density) {
            LocalWindowInfo.current.containerSize.height.toDp()
        }
    val qrCodeMaxWidth = qrCodeDialogMaximumWidth(windowHeight)
    val contentScrollState = rememberScrollState()
    LaunchedEffect(imageSaveStatus) {
        if (
            imageSaveStatus == QrImageSaveStatus.Succeeded ||
            imageSaveStatus == QrImageSaveStatus.Failed
        ) {
            withFrameNanos { }
            contentScrollState.animateScrollTo(contentScrollState.maxValue)
        }
    }
    // Fit system bars at the window level so its initial size matches its content.
    BasicAlertDialog(
        onDismissRequest = {
            if (!imageSaveActive) {
                onDismiss()
            }
        }
    ) {
        Surface(
            modifier = Modifier.safeDrawingPadding().imePadding(),
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation
        ) {
            Column(
                modifier = Modifier.verticalScroll(contentScrollState).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(EditorSectionSpacing)
            ) {
                Text(
                    stringResource(R.string.qr_share_title),
                    modifier = Modifier.align(Alignment.Start).semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall
                )
                ExpressiveQrCode(
                    grid = status.grid,
                    modifier =
                        Modifier
                            .widthIn(max = qrCodeMaxWidth)
                            .fillMaxWidth()
                            .aspectRatio(1f)
                )
                Text(
                    text = stringResource(R.string.qr_share_disclosure),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text =
                        stringResource(
                            R.string.qr_share_capacity,
                            status.format.displayName,
                            formatTransferByteCount(status.textBytes),
                            formatTransferByteCount(TransferProtocol.MAX_QR_TEXT_BYTES)
                        ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium
                )
                Column(verticalArrangement = Arrangement.spacedBy(EditorCompactSpacing)) {
                    Text(
                        stringResource(R.string.qr_image_format),
                        style = MaterialTheme.typography.labelLarge
                    )
                    SingleChoiceButtons(
                        choices = QrImageFormat.entries.map { format ->
                            SingleChoiceOption(
                                label = stringResource(when (format) {
                                    QrImageFormat.WebP -> R.string.qr_image_format_webp
                                    QrImageFormat.Png -> R.string.qr_image_format_png
                                }),
                                selected = imageFormat == format,
                                enabled = !imageSaveActive,
                                onClick = { onFormatChange(format) }
                            )
                        }
                    )
                    Text(
                        stringResource(R.string.qr_image_format_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                when (imageSaveStatus) {
                    QrImageSaveStatus.ChoosingDestination ->
                        Text(
                            text = stringResource(R.string.qr_image_destination),
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )

                    QrImageSaveStatus.Saving ->
                        Row(
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            horizontalArrangement =
                                Arrangement.spacedBy(EditorCompactSpacing),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(ProgressIndicatorSize),
                                strokeWidth = CompactStrokeWidth
                            )
                            Text(
                                stringResource(R.string.qr_image_saving),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                    QrImageSaveStatus.Succeeded ->
                        Text(
                            text = stringResource(R.string.qr_image_saved),
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )

                    QrImageSaveStatus.Failed ->
                        Text(
                            text = stringResource(R.string.qr_image_failed),
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )

                    QrImageSaveStatus.Idle -> Unit
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(
                        EditorCompactSpacing,
                        Alignment.End
                    ),
                    verticalArrangement = Arrangement.spacedBy(EditorCompactSpacing)
                ) {
                    TextButton(onClick = onSaveImage, enabled = !imageSaveActive) {
                        Text(
                            when (imageSaveStatus) {
                                QrImageSaveStatus.Succeeded -> stringResource(
                                    R.string.qr_image_save_another
                                )

                                QrImageSaveStatus.Failed -> stringResource(
                                    R.string.qr_image_try_another
                                )

                                QrImageSaveStatus.ChoosingDestination -> stringResource(
                                    R.string.qr_image_choosing_short
                                )

                                QrImageSaveStatus.Saving -> stringResource(
                                    R.string.qr_image_saving_short
                                )

                                QrImageSaveStatus.Idle -> stringResource(R.string.qr_image_save)
                            }
                        )
                    }
                    Button(
                        onClick = onDismiss,
                        enabled = !imageSaveActive,
                        shapes = ButtonDefaults.shapes(),
                        contentPadding = ButtonDefaults.ContentPadding
                    ) {
                        Text(stringResource(R.string.action_done))
                    }
                }
            }
        }
    }
}

/** Collects one retained one-based line target for bounded viewport navigation. */
@Composable
internal fun GoToLineDialog(session: EditorSession) {
    val lineCount = session.state.metrics?.lineCount ?: return
    require(lineCount > 0L) { "document line count must be positive" }
    val focusRequester = remember(session) { FocusRequester() }
    val softwareKeyboardController = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val windowSize = LocalWindowInfo.current.containerSize
    val useCompactImeLayout =
        usesImeFocusLayout(
            imeBottomInsetPixels = WindowInsets.ime.getBottom(density),
            windowWidthPixels = windowSize.width,
            windowHeightPixels = windowSize.height
        )
    val syncingFromSession = remember(session) { BooleanArray(1) }
    val fieldState =
        remember(session) {
            val input = session.goToLineInput
            TextFieldState(
                initialText = input,
                initialSelection = TextRange(start = 0, end = input.length),
                onTextChanged = { updated ->
                    if (!syncingFromSession[0]) {
                        session.updateGoToLineInput(updated.toString())
                    }
                }
            )
        }
    val errorMessage = session.goToLineErrorMessage
    val retainedInput = session.goToLineInput
    if (fieldState.text.toString() != retainedInput) {
        SideEffect {
            if (fieldState.text.toString() != retainedInput) {
                syncingFromSession[0] = true
                try {
                    fieldState.setTextAndPlaceCursorAtEnd(retainedInput)
                } finally {
                    syncingFromSession[0] = false
                }
            }
        }
    }
    LaunchedEffect(session) {
        focusRequester.requestFocus()
        withFrameNanos { }
        softwareKeyboardController?.show()
    }
    AlertDialog(
        onDismissRequest = session::dismissGoToLineDialog,
        title =
            if (useCompactImeLayout) {
                null
            } else {
                { Text(stringResource(R.string.editor_go_to_line)) }
            },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(EditorSectionSpacing)
            ) {
                if (useCompactImeLayout) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.editor_go_to_line),
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .semantics { heading() },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.headlineSmall
                        )
                        TextButton(onClick = session::dismissGoToLineDialog) {
                            Text(stringResource(R.string.action_cancel))
                        }
                        TextButton(
                            onClick = { session.confirmGoToLine() },
                            enabled = session.canNavigateToLine
                        ) {
                            Text(stringResource(R.string.action_go))
                        }
                    }
                } else {
                    Text(stringResource(R.string.line_number_help, lineCount))
                }
                TextField(
                    state = fieldState,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                    label = {
                        Text(
                            if (useCompactImeLayout) {
                                stringResource(R.string.line_number_range, lineCount)
                            } else {
                                stringResource(R.string.line_number)
                            }
                        )
                    },
                    supportingText =
                        errorMessage?.let { message ->
                            {
                                Text(
                                    text = message.asString(),
                                    modifier =
                                        Modifier.semantics {
                                            liveRegion = LiveRegionMode.Polite
                                        }
                                )
                            }
                        },
                    isError = errorMessage != null,
                    inputTransformation = InputTransformation {
                        val candidate = asCharSequence().toString()
                        if (
                            !session.isGoToLineDialogVisible ||
                            candidate.length > MAX_GO_TO_LINE_INPUT_CHARACTERS ||
                            candidate.any { character -> character !in '0'..'9' }
                        ) {
                            revertAllChanges()
                        }
                    },
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Go
                        ),
                    onKeyboardAction = { session.confirmGoToLine() },
                    lineLimits = TextFieldLineLimits.SingleLine
                )
            }
        },
        confirmButton = {
            if (!useCompactImeLayout) {
                TextButton(
                    onClick = { session.confirmGoToLine() },
                    enabled = session.canNavigateToLine
                ) {
                    Text(stringResource(R.string.action_go))
                }
            }
        },
        dismissButton = {
            if (!useCompactImeLayout) {
                TextButton(onClick = session::dismissGoToLineDialog) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    )
}

/** Selects one concrete text format before the system destination picker opens. */
@Composable
internal fun SaveFormatDialog(
    purpose: SaveDestinationPurpose,
    replacesExistingSource: Boolean,
    isViewOnly: Boolean,
    selectionEnabled: Boolean,
    onSelect: (DocumentFormat) -> Unit,
    onDismiss: () -> Unit
) {
    val isCopy = purpose == SaveDestinationPurpose.Copy
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    isCopy -> stringResource(R.string.save_copy)
                    isViewOnly -> stringResource(R.string.editor_save_editable)
                    replacesExistingSource -> stringResource(R.string.save_new)
                    else -> stringResource(R.string.editor_save_document)
                }
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(EditorSectionSpacing)
            ) {
                Text(
                    saveFormatDescription(
                        purpose = purpose,
                        replacesReadOnlySource = isViewOnly
                    ).asString()
                )
                OutlinedButton(
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.ContentPadding,
                    onClick = { onSelect(DocumentFormat.PlainText) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = selectionEnabled
                ) {
                    Text(stringResource(R.string.format_plain_text_file))
                }
                OutlinedButton(
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.ContentPadding,
                    onClick = { onSelect(DocumentFormat.Markdown) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = selectionEnabled
                ) {
                    Text(stringResource(R.string.format_markdown_file))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
