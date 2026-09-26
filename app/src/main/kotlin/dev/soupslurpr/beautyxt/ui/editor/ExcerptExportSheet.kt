@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.soupslurpr.beautyxt.ui.editor

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.markdown.markdownFootnoteNumbers
import dev.soupslurpr.beautyxt.printing.*
import dev.soupslurpr.beautyxt.ui.asString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private data class ExcerptDestinationRequest(val name: String, val mimeType: String)
private class ExcerptDestinationContract : ActivityResultContract<ExcerptDestinationRequest, Uri?>() {
    override fun createIntent(context: Context, input: ExcerptDestinationRequest) = Intent(Intent.ACTION_CREATE_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE).setType(input.mimeType).putExtra(Intent.EXTRA_TITLE, input.name)
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.data.takeIf { resultCode == android.app.Activity.RESULT_OK }
}

/** Destination, representation, additions, and the exact frozen preview share one review surface. */
@Composable
internal fun ExcerptExportSheet(controller: ExcerptExportController, nfcEnabled: Boolean) {
    val context = LocalContext.current
    val destination = rememberLauncherForActivityResult(ExcerptDestinationContract(), controller::destinationReturned)
    // Only the opaque request identity enters saved state; excerpt bytes and URIs stay in memory.
    var shareRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    val share = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        shareRequestId?.let(controller::shareChooserReturned)
        shareRequestId = null
    }
    if (!controller.visible) return
    var rendered by remember(controller) { mutableStateOf(true) }
    val payload = controller.prepared
    val previewItems = remember(payload?.formatted) { payload?.formatted?.let { markdownPreviewItems(it.blocks) }.orEmpty() }
    val footnotes = remember(payload?.formatted) { payload?.formatted?.let { markdownFootnoteNumbers(it.blocks) }.orEmpty() }
    val immutable = controller.handingOff
    val canConfigure = controller.canConfigure
    DocumentSheet(controller::dismiss) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(stringResource(R.string.excerpt_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.excerpt_scope), style = MaterialTheme.typography.bodyMedium)
            }
            item {
                Text(stringResource(R.string.excerpt_destination), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExcerptDestination.entries.forEach { choice ->
                        FilterChip(controller.destination == choice, { controller.selectDestination(choice) }, enabled = canConfigure && (choice != ExcerptDestination.Nfc || nfcEnabled),
                            label = { Text(stringResource(choice.label())) })
                    }
                }
                Text(stringResource(R.string.excerpt_format), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (controller.destination != ExcerptDestination.Print) {
                        FilterChip(controller.format == ExcerptFormat.Text, { controller.selectFormat(ExcerptFormat.Text) }, enabled = canConfigure,
                            label = { Text(stringResource(if (controller.capture?.exactSource == true) R.string.excerpt_exact else R.string.excerpt_text)) })
                        if (controller.canFormat) FilterChip(controller.format == ExcerptFormat.Markdown, { controller.selectFormat(ExcerptFormat.Markdown) }, enabled = canConfigure,
                            label = { Text(stringResource(R.string.excerpt_markdown)) })
                    }
                    if (controller.destination in listOf(ExcerptDestination.Share, ExcerptDestination.Save, ExcerptDestination.Print))
                        FilterChip(controller.format == ExcerptFormat.Pdf, { controller.selectFormat(ExcerptFormat.Pdf) }, enabled = canConfigure,
                            label = { Text(stringResource(R.string.excerpt_pdf)) })
                }
                if (!controller.canFormat && controller.capture?.exactSource == true)
                    Text(stringResource(R.string.excerpt_format_unavailable), style = MaterialTheme.typography.bodySmall)
            }
            if (controller.destination == ExcerptDestination.Share && controller.format != ExcerptFormat.Pdf) item {
                ExcerptToggle(stringResource(R.string.excerpt_share_file), controller.shareAsFile, canConfigure, controller::updateShareAsFile)
            }
            if (controller.requiresFileName) item {
                OutlinedTextField(controller.fileName, controller::updateFileName, enabled = canConfigure, singleLine = true,
                    isError = !controller.validFileName,
                    supportingText = if (!controller.validFileName) ({ Text(stringResource(R.string.excerpt_invalid_filename)) }) else null,
                    label = { Text(stringResource(R.string.excerpt_filename)) }, modifier = Modifier.fillMaxWidth())
            }
            if (controller.destination == ExcerptDestination.Nfc) item {
                OutlinedTextField(controller.tagLabel, controller::updateTagLabel, enabled = canConfigure, singleLine = true,
                    label = { Text(stringResource(R.string.excerpt_label)) }, modifier = Modifier.fillMaxWidth())
            }
            if (controller.format == ExcerptFormat.Pdf) item { ExcerptPrintSettings(controller) }
            item {
                if (controller.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(if (controller.canCancelSave) R.string.excerpt_saving else R.string.excerpt_prepare))
                }
                if (controller.isStale) {
                    Text(stringResource(R.string.excerpt_stale), color = MaterialTheme.colorScheme.error)
                    TextButton(controller::refresh, enabled = !immutable) { Text(stringResource(R.string.excerpt_refresh)) }
                }
                controller.outputBytes?.let { bytes ->
                    Text(stringResource(R.string.excerpt_capacity, formatTransferByteCount(bytes), formatTransferByteCount(controller.maximumBytes)))
                    if (!controller.fits) Text(stringResource(R.string.excerpt_over_limit), color = MaterialTheme.colorScheme.error)
                }
                controller.message?.let { Text(it.asString(), Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                if (controller.canRetryPreparation) TextButton(controller::retryPreparation) { Text(stringResource(R.string.excerpt_retry)) }
                if (controller.canEndPreviousShares) TextButton(controller::endPreviousShares) { Text(stringResource(R.string.excerpt_end_shares)) }
                if (controller.canCancelSave) TextButton(controller::cancelSave) { Text(stringResource(R.string.editor_cancel_save)) }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(controller::dismiss, enabled = !immutable) { Text(stringResource(R.string.action_cancel)) }
                    Button(enabled = controller.canApply, onClick = {
                        if (controller.destination == ExcerptDestination.Save) {
                            if (controller.chooseSaveDestination()) try { destination.launch(ExcerptDestinationRequest(controller.fileName, controller.mimeType)) }
                            catch (_: Exception) { controller.destinationReturned(null) }
                        } else controller.apply(context) { request ->
                            shareRequestId = request.id
                            try { share.launch(request.chooser) }
                            catch (failure: Throwable) { shareRequestId = null; throw failure }
                        }
                    }) { Text(stringResource(when (controller.destination) {
                        ExcerptDestination.Qr -> R.string.excerpt_show_qr
                        ExcerptDestination.Nfc -> R.string.excerpt_write
                        else -> controller.destination.label()
                    })) }
                }
            }
            items(payload?.notices.orEmpty()) { notice ->
                Text(stringResource(when (notice.kind) {
                    ExcerptNoticeKind.AddedHeaders -> R.string.excerpt_added_headers
                    ExcerptNoticeKind.AddedNote -> R.string.excerpt_added_note
                    ExcerptNoticeKind.RemovedLink -> R.string.excerpt_removed_link
                    ExcerptNoticeKind.MissingNote -> R.string.excerpt_missing_note
                }, notice.detail), style = MaterialTheme.typography.bodySmall)
            }
            item {
                HorizontalDivider()
                Text(stringResource(R.string.excerpt_preview), style = MaterialTheme.typography.titleMedium)
                if (controller.format == ExcerptFormat.Markdown) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(rendered, { rendered = true }, label = { Text(stringResource(R.string.excerpt_rendered)) })
                    FilterChip(!rendered, { rendered = false }, label = { Text(stringResource(R.string.excerpt_generated)) })
                }
            }
            if (controller.format == ExcerptFormat.Pdf && payload != null) {
                item { ExcerptPdfPreview(payload) }
            } else if (controller.format == ExcerptFormat.Markdown && rendered && payload?.formatted != null) {
                itemsIndexed(previewItems) { _, item ->
                    CompositionLocalProvider(LocalDocumentSelectionLayout provides null, LocalMarkdownFootnoteNumbers provides footnotes,
                        LocalMarkdownPreviewBlockIndex provides item.firstBlockIndex) {
                        if (item.isTable) MarkdownTable(item.blocks, item.firstBlockIndex, item.showsWrappedPreview, {})
                        else item.blocks.forEachIndexed { offset, block ->
                            if (!block.illustrationContinuation) CompositionLocalProvider(LocalMarkdownPreviewBlockIndex provides item.firstBlockIndex + offset) {
                                MarkdownPreviewBlock(block, {})
                            }
                        }
                    }
                }
            } else {
                if (controller.loadingTextPreview) item {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.excerpt_preview_loading))
                }
                if (controller.textPreviewFailed) item {
                    Text(stringResource(R.string.excerpt_preview_failed), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    TextButton(controller::retryTextPreview) { Text(stringResource(R.string.excerpt_retry_preview)) }
                }
                if (controller.canPreviousPreview || controller.textPreview?.next != null) item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton({ controller.loadTextPreview(false) }, enabled = controller.canPreviousPreview && !controller.loadingTextPreview) { Text(stringResource(R.string.excerpt_previous)) }
                        TextButton({ controller.loadTextPreview(true) }, enabled = controller.textPreview?.next != null && !controller.loadingTextPreview) { Text(stringResource(R.string.excerpt_next)) }
                    }
                }
                item {
                    val text = controller.textPreview?.blocks.orEmpty().joinToString("") { block ->
                        block.text + if (block.lineTerminatorUtf16Units > 0) "\n" else ""
                    }
                    Text(text.ifEmpty { "\n" }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                }
            }
            val links = payload?.formatted?.blocks?.flatMap { it.spans }?.filter {
                it.destinationKind == dev.soupslurpr.beautyxt.markdown.MarkdownInlineDestinationKind.Link
            }?.mapNotNull { it.destination }?.distinct().orEmpty()
            if (links.isNotEmpty()) {
                item { Text(stringResource(R.string.excerpt_links), style = MaterialTheme.typography.titleSmall) }
                items(links) { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

private fun ExcerptDestination.label() = when (this) {
    ExcerptDestination.Copy -> R.string.excerpt_copy
    ExcerptDestination.Share -> R.string.excerpt_share
    ExcerptDestination.Save -> R.string.excerpt_save
    ExcerptDestination.Qr -> R.string.excerpt_qr
    ExcerptDestination.Nfc -> R.string.excerpt_nfc
    ExcerptDestination.Print -> R.string.excerpt_print
}

@Composable
private fun ExcerptToggle(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).padding(end = 12.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f))
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun ExcerptPrintSettings(controller: ExcerptExportController) {
    val draft = controller.printDraft
    val enabled = controller.canConfigure
    var expanded by remember { mutableStateOf(false) }
    Column {
        if (controller.canFormat) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrintContentMode.entries.forEach { mode ->
                FilterChip(draft.contentMode == mode, { controller.updatePrintDraft(draft.copy(contentMode = mode)) }, enabled = enabled,
                    label = { Text(stringResource(if (mode == PrintContentMode.Source) R.string.excerpt_pdf_source else R.string.excerpt_pdf_formatted)) })
            }
        }
        TextButton({ expanded = !expanded }) { Text(stringResource(R.string.excerpt_settings)) }
        if (expanded) {
            Text(stringResource(R.string.excerpt_paper), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!controller.paperLetter, { controller.updatePaperLetter(false) }, enabled = enabled, label = { Text(stringResource(R.string.excerpt_a4)) })
                FilterChip(controller.paperLetter, { controller.updatePaperLetter(true) }, enabled = enabled, label = { Text(stringResource(R.string.excerpt_letter)) })
                PrintFontFamily.entries.forEach { family ->
                    FilterChip(draft.fontFamily == family, { controller.updatePrintDraft(draft.copy(fontFamily = family)) }, enabled = enabled,
                        label = { Text(stringResource(when (family) {
                            PrintFontFamily.SansSerif -> R.string.print_font_sans
                            PrintFontFamily.Serif -> R.string.print_font_serif
                            PrintFontFamily.Monospace -> R.string.print_font_mono
                        })) })
                }
            }
            OutlinedTextField(draft.fontSize, { controller.updatePrintDraft(draft.copy(fontSize = it)) }, enabled = enabled,
                label = { Text(stringResource(R.string.print_text_size)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            ExcerptToggle(stringResource(R.string.print_wrap_source_lines), draft.wrapLongLines, enabled) { controller.updatePrintDraft(draft.copy(wrapLongLines = it)) }
            ExcerptToggle(stringResource(R.string.print_file_name_header), draft.showFileName, enabled) { controller.updatePrintDraft(draft.copy(showFileName = it)) }
            ExcerptToggle(stringResource(R.string.print_page_numbers), draft.showPageNumbers, enabled) { controller.updatePrintDraft(draft.copy(showPageNumbers = it)) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrintMarginUnit.entries.forEach { unit ->
                    FilterChip(draft.marginUnit == unit, { controller.updatePrintDraft(convertPrintMarginUnit(draft, unit)) }, enabled = enabled,
                        label = { Text(stringResource(if (unit == PrintMarginUnit.Inches) R.string.print_inches else R.string.print_millimetres)) })
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(R.string.print_margin_top to draft.topMargin, R.string.print_margin_bottom to draft.bottomMargin,
                    R.string.print_margin_left to draft.leftMargin, R.string.print_margin_right to draft.rightMargin).forEachIndexed { index, (label, value) ->
                    OutlinedTextField(value, { value -> controller.updatePrintDraft(when (index) {
                        0 -> draft.copy(topMargin = value); 1 -> draft.copy(bottomMargin = value)
                        2 -> draft.copy(leftMargin = value); else -> draft.copy(rightMargin = value)
                    }) }, enabled = enabled, label = { Text("${stringResource(label)} (${stringResource(if (draft.marginUnit == PrintMarginUnit.Inches) R.string.print_inch_unit else R.string.print_millimetre_unit)})") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.width(130.dp))
                }
            }
        }
    }
}

@Composable
private fun ExcerptPdfPreview(payload: PreparedExcerpt) {
    val context = LocalContext.current
    var index by remember(payload) { mutableIntStateOf(0) }
    var bitmap by remember(payload) { mutableStateOf<Bitmap?>(null) }
    var bitmapIndex by remember(payload) { mutableIntStateOf(-1) }
    var pageText by remember(payload) { mutableStateOf("") }
    var failedIndex by remember(payload) { mutableStateOf<Int?>(null) }
    var retry by remember(payload) { mutableIntStateOf(0) }
    val pageIndex = index
    LaunchedEffect(payload, pageIndex, retry) {
        if (bitmapIndex == pageIndex) return@LaunchedEffect
        failedIndex = null
        var ownedBitmap: Bitmap? = null
        try {
            val input = checkNotNull(payload.pdf).openReadOnly(context)
            var text = ""
            input.use {
                withContext(Dispatchers.Default) {
                    PdfRenderer(it).use { renderer ->
                        renderer.openPage(pageIndex).use { page ->
                            val width = 720
                            val height = (width.toFloat() * page.height / page.width).toInt().coerceIn(1, 2048)
                            ownedBitmap = createBitmap(width, height).apply {
                                eraseColor(android.graphics.Color.WHITE)
                                page.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                            text = page.textContents.joinToString("\n") { it.text }
                        }
                    }
                }
            }
            bitmap?.recycle(); bitmap = ownedBitmap; ownedBitmap = null
            pageText = text; bitmapIndex = pageIndex; failedIndex = null
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { if (isActive) failedIndex = pageIndex }
        finally { ownedBitmap?.recycle() }
    }
    DisposableEffect(payload) { onDispose { bitmap?.recycle() } }
    fun canNavigate() = bitmapIndex == index || failedIndex == index
    Column {
        if (payload.pdfPages > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton({ if (canNavigate() && index > 0) index-- }, enabled = canNavigate() && index > 0) { Text(stringResource(R.string.excerpt_previous)) }
            TextButton({ if (canNavigate() && index + 1 < payload.pdfPages) index++ }, enabled = canNavigate() && index + 1 < payload.pdfPages) { Text(stringResource(R.string.excerpt_next)) }
        }
        Text(stringResource(R.string.excerpt_page, index + 1, payload.pdfPages))
        if (failedIndex == index) {
            Text(stringResource(R.string.excerpt_preview_failed))
            TextButton({ failedIndex = null; retry++ }) { Text(stringResource(R.string.excerpt_retry_preview)) }
        } else if (bitmapIndex != index) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.excerpt_preview_loading))
        }
        bitmap?.takeIf { bitmapIndex == index }?.let {
            Image(it.asImageBitmap(), pageText.ifEmpty { stringResource(R.string.excerpt_preview_description) }, Modifier.fillMaxWidth())
        }
    }
}
