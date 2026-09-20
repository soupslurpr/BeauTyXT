package dev.soupslurpr.beautyxt.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.document.DocumentFormat
import dev.soupslurpr.beautyxt.sharing.IncomingDocumentShare
import dev.soupslurpr.beautyxt.sharing.IncomingSourcePurpose

private val IncomingShareSpacing = 12.dp
private val IncomingSharePreviewPadding = 16.dp
private const val INCOMING_SHARE_PREVIEW_LINES = 10
private const val INCOMING_SHARE_PREVIEW_UTF16_UNITS = 4 * 1024

/** Displays explicit review before shared content can replace the current screen. */
@Composable
internal fun IncomingShareDialog(
    share: IncomingDocumentShare,
    currentDocumentTitle: String?,
    canOpen: Boolean,
    onDismiss: () -> Unit,
    onCloseCurrentDocument: () -> Unit,
    onOpenText: () -> Unit,
    onOpenSource: () -> Unit
) {
    if (currentDocumentTitle != null && share !is IncomingDocumentShare.Rejected) {
        val opensRequestedFile =
            share is IncomingDocumentShare.Source &&
                share.purpose != IncomingSourcePurpose.Share
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    if (opensRequestedFile) {
                        stringResource(
                            R.string.incoming_another_file_title
                        )
                    } else {
                        stringResource(R.string.incoming_shared_content_title)
                    }
                )
            },
            text = {
                Text(
                    stringResource(
                        if (opensRequestedFile) {
                            R.string.incoming_close_for_file
                        } else {
                            R.string.incoming_close_for_share
                        },
                        currentDocumentTitle
                    )
                )
            },
            confirmButton = {
                Button(onClick = onDismiss) {
                    Text(stringResource(R.string.incoming_keep_current))
                }
            },
            dismissButton = {
                TextButton(onClick = onCloseCurrentDocument) {
                    Text(stringResource(R.string.incoming_close_current))
                }
            }
        )
        return
    }
    when (share) {
        is IncomingDocumentShare.Rejected ->
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.incoming_rejected_title)) },
                text = { Text(share.message.asString()) },
                confirmButton = {
                    Button(onClick = onDismiss) {
                        Text(stringResource(R.string.action_dismiss))
                    }
                }
            )

        is IncomingDocumentShare.Source ->
            AlertDialog(
                onDismissRequest = onDismiss,
                title = {
                    Text(
                        when (share.purpose) {
                            IncomingSourcePurpose.Share -> stringResource(
                                R.string.incoming_shared_file_title
                            )

                            IncomingSourcePurpose.View -> stringResource(
                                R.string.incoming_view_file_title
                            )

                            IncomingSourcePurpose.Edit -> stringResource(
                                R.string.incoming_edit_file_title
                            )
                        }
                    )
                },
                text = {
                    Text(
                        when (share.purpose) {
                            IncomingSourcePurpose.Share ->
                                stringResource(
                                    R.string.incoming_shared_file_description,
                                    share.format.displayName
                                )

                            IncomingSourcePurpose.View ->
                                stringResource(
                                    R.string.incoming_view_file_description,
                                    share.format.displayName
                                )

                            IncomingSourcePurpose.Edit ->
                                stringResource(
                                    R.string.incoming_edit_file_description,
                                    share.format.displayName
                                )
                        }
                    )
                },
                confirmButton = {
                    Button(onClick = onOpenSource, enabled = canOpen) {
                        Text(
                            if (share.purpose == IncomingSourcePurpose.Edit) {
                                stringResource(R.string.editor_edit)
                            } else {
                                stringResource(R.string.action_open)
                            }
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )

        is IncomingDocumentShare.Text ->
            AlertDialog(
                onDismissRequest = onDismiss,
                title = {
                    Text(
                        if (share.nfcMetadata == null) {
                            stringResource(R.string.incoming_review_text)
                        } else {
                            stringResource(R.string.incoming_review_nfc)
                        }
                    )
                },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(IncomingShareSpacing)
                    ) {
                        Text(
                            stringResource(
                                if (share.nfcMetadata == null) {
                                    R.string.incoming_text_description
                                } else {
                                    R.string.incoming_nfc_description
                                },
                                share.format.displayName
                            )
                        )
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = MaterialTheme.shapes.medium
                        ) {
                            SelectionContainer {
                                Text(
                                    text = incomingSharePreview(share.text),
                                    modifier = Modifier.padding(IncomingSharePreviewPadding),
                                    maxLines = INCOMING_SHARE_PREVIEW_LINES,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                        Text(
                            text = remember(share.text) {
                                incomingShareCharacterCount(share.text)
                            }.asString(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium
                        )
                        share.nfcMetadata?.let { metadata ->
                            NfcTransferDetails(metadata = metadata)
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = onOpenText, enabled = canOpen) {
                        Text(stringResource(R.string.action_open))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = onDismiss,
                        colors =
                            ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
    }
}

/** Returns a Unicode-scalar count consistent with the document information screen. */
internal fun incomingShareCharacterCount(text: String): UiText.Quantity {
    val count = text.codePointCount(0, text.length)
    return UiText.Quantity(R.plurals.incoming_unicode_characters, count, listOf(count))
}

/** Returns one scalar-safe prefix so review never lays out an entire large transfer. */
private fun incomingSharePreview(text: String): String {
    var end = minOf(text.length, INCOMING_SHARE_PREVIEW_UTF16_UNITS)
    if (end < text.length && end > 0 && text[end - 1].isHighSurrogate()) {
        end -= 1
    }
    return text.substring(startIndex = 0, endIndex = end)
}

/** Returns the user-facing name of one incoming text format. */
private val DocumentFormat.displayName: String
    @Composable get() = when (this) {
        DocumentFormat.PlainText -> stringResource(R.string.incoming_format_plain_text)
        DocumentFormat.Markdown -> stringResource(R.string.format_markdown)
    }
