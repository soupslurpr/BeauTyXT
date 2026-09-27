@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.soupslurpr.beautyxt.ui.editor

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.ui.designsystem.SingleChoiceButtons
import dev.soupslurpr.beautyxt.ui.designsystem.SingleChoiceOption

/** Keeps every destination visible, with explicit scope and a separate route to detailed review. */
@Composable
internal fun DocumentExportSummary(
    controller: ExcerptExportController,
    canChoose: Boolean,
    nfcEnabled: Boolean,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onPreview: () -> Unit,
    onDestination: (ExcerptDestination) -> Unit
) {
    val context = LocalContext.current
    DocumentSheet(controller::dismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.editor_send_export), style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() })
                    Text(stringResource(if (controller.exportScope == ExportScope.Document)
                        R.string.export_whole_document else R.string.export_selected_text),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(controller::dismiss, enabled = !controller.handingOff) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.action_close))
                }
            }
            ExportFormatChoices(controller)
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(painterResource(R.drawable.ic_description), null, Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(controller.fileName.ifEmpty { stringResource(R.string.excerpt_prepare) },
                                style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            controller.outputBytes?.let { bytes ->
                                Text(android.text.format.Formatter.formatShortFileSize(context, bytes),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (controller.exportScope == ExportScope.Selection) {
                        if (controller.loadingTextPreview) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (controller.textPreviewFailed) {
                            Text(stringResource(R.string.excerpt_preview_failed),
                                Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            TextButton(controller::retryTextPreview) { Text(stringResource(R.string.excerpt_retry_preview)) }
                        } else {
                            val text = controller.textPreview?.blocks.orEmpty().joinToString("") { block ->
                                block.text + if (block.lineTerminatorUtf16Units > 0) "\n" else ""
                            }
                            if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.bodyMedium,
                                maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (controller.format == ExcerptFormat.Pdf) {
                        controller.prepared?.pdfPages?.let { pages ->
                            Text(pluralStringResource(R.plurals.export_pdf_summary, pages, pages,
                                stringResource(if (controller.paperLetter) R.string.excerpt_letter else R.string.excerpt_a4)),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (controller.isSharingSourceFile) {
                        Text(stringResource(R.string.export_original_file), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (controller.format != ExcerptFormat.Pdf) SingleChoiceButtons(listOf(
                SingleChoiceOption(stringResource(R.string.export_send_file), controller.shareAsFile,
                    { controller.updateShareAsFile(true) }, controller.canConfigure),
                SingleChoiceOption(stringResource(R.string.export_send_text), !controller.shareAsFile,
                    { controller.updateShareAsFile(false) }, controller.canConfigure)
            ))
            if (controller.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            ExportFeedback(controller)
            controller.prepared?.notices?.forEach { ExportNotice(it) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onShare, shapes = ButtonDefaults.shapes(),
                    enabled = canChoose && controller.fitsDestination(ExcerptDestination.Share),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Icon(painterResource(R.drawable.ic_share), null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (controller.format == ExcerptFormat.Pdf) R.string.export_share_pdf
                        else if (controller.shareAsFile) R.string.share_file else R.string.share_text))
                }
                OutlinedButton(onSave, shapes = ButtonDefaults.shapes(),
                    enabled = canChoose && controller.fitsDestination(ExcerptDestination.Save),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Icon(painterResource(R.drawable.ic_save), null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.export_save_copy))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.export_more_ways), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { heading() })
                ExportDestinationGrid(controller.canConfigure && !controller.busy, nfcEnabled, onDestination)
            }
            TextButton(onPreview, enabled = controller.canConfigure, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(painterResource(R.drawable.ic_tune), null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (controller.format == ExcerptFormat.Pdf)
                    R.string.export_preview_page_settings else R.string.export_preview_options))
            }
        }
    }
}

private data class ExportDestinationAction(
    val destination: ExcerptDestination,
    @StringRes val label: Int,
    @DrawableRes val icon: Int
)

private val exportDestinationActions = listOf(
    ExportDestinationAction(ExcerptDestination.Qr, R.string.excerpt_qr, R.drawable.ic_qr_code_2),
    ExportDestinationAction(ExcerptDestination.Nfc, R.string.export_write_nfc, R.drawable.ic_nfc),
    ExportDestinationAction(ExcerptDestination.Copy, R.string.export_copy_text, R.drawable.ic_content_copy),
    ExportDestinationAction(ExcerptDestination.Print, R.string.excerpt_print, R.drawable.ic_print)
)

@Composable
private fun ExportDestinationGrid(enabled: Boolean, nfcEnabled: Boolean, onChoose: (ExcerptDestination) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 312.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            exportDestinationActions.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                    row.forEach { action ->
                        val actionEnabled = enabled && (action.destination != ExcerptDestination.Nfc || nfcEnabled)
                        Surface(onClick = { onChoose(action.destination) }, enabled = actionEnabled,
                            shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (actionEnabled) 1f else 0.38f),
                            modifier = Modifier.weight(1f).fillMaxHeight().semantics { role = Role.Button }) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(painterResource(action.icon), null, Modifier.size(24.dp))
                                Text(stringResource(action.label), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }
    }
}
