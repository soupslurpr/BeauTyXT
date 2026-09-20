/* Groups explicit outgoing document operations without changing their capabilities. */
package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.ui.HomeNfcIcon
import dev.soupslurpr.beautyxt.ui.HomeQrIcon
import dev.soupslurpr.beautyxt.ui.UiText
import dev.soupslurpr.beautyxt.ui.asString

/** Offers the available ways to send or export the current document. */
@Composable
internal fun DocumentSendSheet(
    session: EditorSession,
    nfcAvailable: Boolean,
    onShare: () -> Unit,
    onQrShare: () -> Unit,
    onNfcWrite: () -> Unit,
    onPrint: () -> Unit,
    onSaveCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    /** Closes the chooser before starting one explicit operation. */
    fun select(action: () -> Unit) {
        onDismiss()
        action()
    }

    DocumentSheet(onDismiss = onDismiss) {
        DocumentSheetHeading(
            title = stringResource(R.string.editor_send_export),
            subtitle = session.title
        )
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DocumentSheetAction(
                title = if (session.hasDocumentSource) {
                    stringResource(
                        R.string.share_file
                    )
                } else {
                    stringResource(R.string.share_text)
                },
                description = if (session.hasDocumentSource) {
                    stringResource(R.string.share_file_description)
                } else {
                    stringResource(R.string.share_text_description)
                },
                icon = Icons.Default.Share,
                enabled = session.canStartShare,
                onClick = { select(onShare) }
            )
            DocumentSheetAction(
                title = stringResource(R.string.share_qr),
                description = transferCapacityDescription(session.qrShareCapacity).asString(),
                icon = HomeQrIcon,
                enabled = session.canStartQrShare,
                onClick = { select(onQrShare) }
            )
            DocumentSheetAction(
                title = stringResource(R.string.share_nfc),
                description = if (nfcAvailable) {
                    nfcWriteCapacityDescription(session.nfcWriteCapacity).asString()
                } else {
                    stringResource(R.string.share_nfc_unavailable)
                },
                icon = HomeNfcIcon,
                enabled = nfcAvailable && session.canStartNfcWrite,
                onClick = { select(onNfcWrite) }
            )
            DocumentSheetAction(
                title = stringResource(R.string.share_print),
                description = stringResource(R.string.share_print_description),
                icon = EditorPrintIcon,
                enabled = session.canStartPrint,
                onClick = { select(onPrint) }
            )
            if (session.hasDocumentSource) {
                DocumentSheetAction(
                    title = stringResource(R.string.save_copy),
                    description = stringResource(R.string.share_copy_description),
                    icon = EditorSaveIcon,
                    enabled = session.canStartSaveAs,
                    onClick = { select(onSaveCopy) }
                )
            }
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
