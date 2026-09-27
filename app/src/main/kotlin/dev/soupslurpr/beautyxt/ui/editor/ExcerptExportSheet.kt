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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import dev.soupslurpr.beautyxt.document.DocumentFormat
import dev.soupslurpr.beautyxt.ui.designsystem.SingleChoiceButtons
import dev.soupslurpr.beautyxt.ui.designsystem.SingleChoiceOption
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

/** Reviews whole documents and excerpts with one preview and explicit output actions. */
@Composable
internal fun ExcerptExportSheet(
    controller: ExcerptExportController,
    nfcEnabled: Boolean,
    onShareSourceFile: () -> Unit = {}
) {
    val context = LocalContext.current
    val destination = rememberLauncherForActivityResult(ExcerptDestinationContract(), controller::destinationReturned)
    // Only the opaque request identity enters saved state; bytes and URIs stay in memory.
    var shareRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    val share = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        shareRequestId?.let(controller::shareChooserReturned)
        shareRequestId = null
    }
    if (!controller.visible) return
    var settingsVisible by retain(controller) { mutableStateOf(false) }
    var previewVisible by retain(controller) { mutableStateOf(false) }
    var moreVisible by remember(controller) { mutableStateOf(false) }
    var rendered by retain(controller) { mutableStateOf(true) }
    val payload = controller.prepared
    val previewItems = remember(payload?.formatted) { payload?.formatted?.let { markdownPreviewItems(it.blocks) }.orEmpty() }
    val footnotes = remember(payload?.formatted) { payload?.formatted?.let { markdownFootnoteNumbers(it.blocks) }.orEmpty() }
    val links = remember(payload?.formatted) {
        payload?.formatted?.blocks?.flatMap { it.spans }?.filter {
            it.destinationKind == dev.soupslurpr.beautyxt.markdown.MarkdownInlineDestinationKind.Link
        }?.mapNotNull { it.destination }?.distinct().orEmpty()
    }
    val ordinaryOutput = controller.destination in listOf(ExcerptDestination.Share, ExcerptDestination.Save)
    val canChoose = controller.canConfigure && payload != null && !controller.busy && !controller.isStale && controller.validFileName

    fun applyOutput() {
        controller.apply(context) { request ->
            shareRequestId = request.id
            try { share.launch(request.chooser) }
            catch (failure: Throwable) { shareRequestId = null; throw failure }
        }
    }
    fun saveOutput() {
        if (controller.usePreparedDestination(ExcerptDestination.Save) && controller.chooseSaveDestination()) {
            try { destination.launch(ExcerptDestinationRequest(controller.fileName, controller.mimeType)) }
            catch (_: Exception) { controller.destinationReturned(null) }
        }
    }
    fun shareOutput() {
        if (!controller.usePreparedDestination(ExcerptDestination.Share)) return
        if (controller.isSharingSourceFile) {
            controller.dismiss()
            onShareSourceFile()
        } else applyOutput()
    }

    if (!previewVisible && !settingsVisible && ordinaryOutput) {
        DocumentExportSummary(controller, canChoose, nfcEnabled, onShare = ::shareOutput, onSave = ::saveOutput,
            onPreview = { previewVisible = true }, onDestination = { choice ->
                controller.selectDestination(choice)
                previewVisible = true
            })
        return
    }

    if (!settingsVisible) DocumentSheet(controller::dismiss) {
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f, fill = false),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.editor_send_export), style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.semantics { heading() })
                        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                            Text(stringResource(if (controller.exportScope == ExportScope.Document)
                                R.string.export_whole_document else R.string.excerpt_scope),
                                Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    Box {
                        IconButton({ moreVisible = true }, enabled = controller.canConfigure) {
                            Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.export_more))
                        }
                        DropdownMenu(moreVisible, { moreVisible = false }) {
                            listOf(ExcerptDestination.Copy, ExcerptDestination.Qr, ExcerptDestination.Nfc, ExcerptDestination.Print).forEach { choice ->
                                DropdownMenuItem(text = { Text(stringResource(choice.label())) },
                                    enabled = controller.canConfigure && (choice != ExcerptDestination.Nfc || nfcEnabled),
                                    onClick = { moreVisible = false; controller.selectDestination(choice) })
                            }
                        }
                    }
                    IconButton(controller::dismiss, enabled = !controller.handingOff) {
                        Icon(painterResource(R.drawable.ic_close), stringResource(R.string.action_close))
                    }
                }
            }
            if (!ordinaryOutput) item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(controller.destination.label()), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton({ controller.selectDestination(ExcerptDestination.Share) }, enabled = controller.canConfigure) {
                        Text(stringResource(R.string.export_share_or_save))
                    }
                }
                if (controller.destination == ExcerptDestination.Print) {
                    Text(stringResource(R.string.print_service_disclosure), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { ExportFormatChoices(controller) }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(controller.fileName.ifEmpty { stringResource(R.string.excerpt_prepare) },
                            style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        controller.outputBytes?.let { bytes ->
                            Text(android.text.format.Formatter.formatShortFileSize(context, bytes),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    TextButton({ settingsVisible = true }, enabled = controller.canConfigure) {
                        Text(stringResource(if (controller.format == ExcerptFormat.Pdf) R.string.excerpt_settings else R.string.export_options))
                    }
                }
                if (controller.isSharingSourceFile && ordinaryOutput) {
                    Text(stringResource(R.string.export_original_file), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { ExportFeedback(controller) }
            items(payload?.notices.orEmpty()) { ExportNotice(it) }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.excerpt_preview), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).semantics { heading() })
                    if (controller.format == ExcerptFormat.Markdown) TextButton({ rendered = !rendered }) {
                        Text(stringResource(if (rendered) R.string.excerpt_generated else R.string.excerpt_rendered))
                    }
                }
            }
            if (controller.busy) item {
                Column(Modifier.fillMaxWidth().heightIn(min = 160.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(if (controller.canCancelSave) R.string.excerpt_saving else R.string.excerpt_prepare))
                }
            } else if (controller.format == ExcerptFormat.Pdf && payload != null) {
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
                if (controller.loadingTextPreview) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (controller.textPreviewFailed) item {
                    Text(stringResource(R.string.excerpt_preview_failed), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    TextButton(controller::retryTextPreview) { Text(stringResource(R.string.excerpt_retry_preview)) }
                }
                item {
                    val text = controller.textPreview?.blocks.orEmpty().joinToString("") { block ->
                        block.text + if (block.lineTerminatorUtf16Units > 0) "\n" else ""
                    }
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                        Text(text.ifEmpty { "\n" }, Modifier.fillMaxWidth().padding(16.dp),
                            fontFamily = if (controller.capture?.exactSource == true && controller.format == ExcerptFormat.Text)
                                FontFamily.Monospace else null, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (controller.canPreviousPreview || controller.textPreview?.next != null) item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton({ controller.loadTextPreview(false) }, enabled = controller.canPreviousPreview && !controller.loadingTextPreview) { Text(stringResource(R.string.excerpt_previous)) }
                        TextButton({ controller.loadTextPreview(true) }, enabled = controller.textPreview?.next != null && !controller.loadingTextPreview) { Text(stringResource(R.string.excerpt_next)) }
                    }
                }
            }
            if (links.isNotEmpty()) {
                item { Text(stringResource(R.string.excerpt_links), style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() }) }
                items(links) { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        HorizontalDivider()
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
                val shareLabel = stringResource(if (controller.format == ExcerptFormat.Pdf) R.string.export_share_pdf
                    else if (controller.shareAsFile) R.string.share_file else R.string.share_text)
                val saveLabel = stringResource(R.string.export_save_copy)
                @Composable fun saveButton(modifier: Modifier = Modifier) {
                    OutlinedButton(::saveOutput, modifier.heightIn(min = 48.dp), enabled = canChoose && controller.fitsDestination(ExcerptDestination.Save)) {
                        Text(saveLabel)
                    }
                }
                @Composable fun shareButton(modifier: Modifier = Modifier) {
                    Button(::shareOutput, modifier.heightIn(min = 48.dp), enabled = canChoose && controller.fitsDestination(ExcerptDestination.Share)) {
                        Icon(painterResource(R.drawable.ic_share), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(shareLabel)
                    }
                }
                if (ordinaryOutput) {
                    if (maxWidth < 300.dp * maxOf(1f, LocalDensity.current.fontScale)) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            shareButton(Modifier.fillMaxWidth()); saveButton(Modifier.fillMaxWidth())
                        }
                    } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        saveButton(Modifier.weight(1f)); shareButton(Modifier.weight(1f))
                    }
                } else Button(::applyOutput, Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    enabled = controller.canApply && (controller.destination != ExcerptDestination.Nfc || nfcEnabled)) {
                    if (controller.destination == ExcerptDestination.Print) {
                        Icon(painterResource(R.drawable.ic_print), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(when (controller.destination) {
                        ExcerptDestination.Qr -> R.string.excerpt_show_qr
                        ExcerptDestination.Nfc -> R.string.excerpt_write
                        else -> controller.destination.label()
                    }))
                }
            }
        }
    }
    if (settingsVisible) ExportOptionsSheet(controller, ordinaryOutput) { settingsVisible = false }
}

/** Scrolls the complete form in short windows so the IME cannot consume its field area. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportOptionsSheet(controller: ExcerptExportController, ordinaryOutput: Boolean, onDone: () -> Unit) {
    val density = LocalDensity.current
    val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    // Keep this decision independent of the animated IME inset, preserving field focus while it opens.
    val scrollActions = windowHeight < 480.dp * density.fontScale.coerceAtLeast(1f)
    val scroll = rememberScrollState()
    ModalBottomSheet(
        onDismissRequest = onDone,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
        dragHandle = if (scrollActions) null else ({ BottomSheetDefaults.DragHandle() }),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(Modifier.fillMaxWidth().then(if (scrollActions) Modifier.verticalScroll(scroll) else Modifier)) {
            Column(Modifier.then(if (scrollActions) Modifier else Modifier.weight(1f, fill = false).verticalScroll(scroll))
                .fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(if (controller.format == ExcerptFormat.Pdf) R.string.excerpt_settings else R.string.export_options),
                    style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                OutlinedTextField(controller.fileName, controller::updateFileName, enabled = controller.canConfigure,
                    label = { Text(stringResource(R.string.export_filename)) }, singleLine = true,
                    isError = !controller.validFileName, modifier = Modifier.fillMaxWidth())
                if (!controller.validFileName) Text(stringResource(R.string.excerpt_invalid_filename))
                if (ordinaryOutput && controller.format != ExcerptFormat.Pdf) {
                    ExcerptToggle(stringResource(R.string.excerpt_share_file), controller.shareAsFile, controller.canConfigure, controller::updateShareAsFile)
                }
                if (controller.destination == ExcerptDestination.Nfc) {
                    OutlinedTextField(controller.tagLabel, controller::updateTagLabel, enabled = controller.canConfigure, singleLine = true,
                        label = { Text(stringResource(R.string.excerpt_label)) }, modifier = Modifier.fillMaxWidth())
                }
                if (controller.format == ExcerptFormat.Pdf) ExcerptPrintSettings(controller)
            }
            HorizontalDivider()
            TextButton(onDone, Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .padding(horizontal = 24.dp, vertical = 12.dp).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.export_done))
            }
        }
    }
}

@Composable
internal fun exportFormatLabel(controller: ExcerptExportController, format: ExcerptFormat): String = stringResource(when (format) {
    ExcerptFormat.Pdf -> R.string.excerpt_pdf
    ExcerptFormat.Markdown -> R.string.export_markdown
    ExcerptFormat.ReadingText -> R.string.excerpt_text
    ExcerptFormat.Text -> when {
        controller.capture?.textFormat == DocumentFormat.Markdown && controller.exportScope == ExportScope.Document -> R.string.export_markdown
        controller.capture?.exactSource == true && controller.exportScope == ExportScope.Selection -> R.string.excerpt_exact
        controller.canExportReadingText -> R.string.print_source_text
        else -> R.string.excerpt_text
    }
})

@Composable
internal fun ExportNotice(notice: ExcerptNotice) {
    Text(stringResource(when (notice.kind) {
        ExcerptNoticeKind.AddedHeaders -> R.string.excerpt_added_headers
        ExcerptNoticeKind.AddedNote -> R.string.excerpt_added_note
        ExcerptNoticeKind.RemovedLink -> R.string.excerpt_removed_link
        ExcerptNoticeKind.MissingNote -> R.string.excerpt_missing_note
    }, notice.detail), style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun ExportFeedback(controller: ExcerptExportController) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        if (controller.isStale) {
            Text(stringResource(R.string.excerpt_stale), color = MaterialTheme.colorScheme.error)
            TextButton(controller::refresh, enabled = !controller.handingOff) { Text(stringResource(R.string.excerpt_refresh)) }
        }
        controller.message?.let { Text(it.asString()) }
        controller.savedCopyReminder?.let {
            Text(it.asString(), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (controller.canRetryPreparation) TextButton(controller::retryPreparation) { Text(stringResource(R.string.excerpt_retry)) }
        if (controller.canEndPreviousShares) TextButton(controller::endPreviousShares) { Text(stringResource(R.string.excerpt_end_shares)) }
        if (controller.canCancelSave) TextButton(controller::cancelSave) { Text(stringResource(R.string.editor_cancel_save)) }
        if (controller.outputBytes != null && !controller.fits) {
            Text(stringResource(R.string.excerpt_over_limit))
            if (controller.destination == ExcerptDestination.Share && !controller.shareAsFile) {
                TextButton({ controller.updateShareAsFile(true) }, enabled = controller.canConfigure) {
                    Text(stringResource(R.string.excerpt_share_file))
                }
            }
        }
    }
}

private fun ExcerptDestination.label() = when (this) {
    ExcerptDestination.Copy -> R.string.excerpt_copy
    ExcerptDestination.Share -> R.string.excerpt_share
    ExcerptDestination.Save -> R.string.export_save_copy
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
    val validation = validatePrintSetup(draft)
    val enabled = controller.canConfigure
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (controller.canFormat) {
            SingleChoiceButtons(PrintContentMode.entries.map { mode ->
                SingleChoiceOption(
                    stringResource(if (mode == PrintContentMode.Source) R.string.excerpt_pdf_source else R.string.excerpt_pdf_formatted),
                    draft.contentMode == mode, { controller.updatePrintDraft(draft.copy(contentMode = mode)) }, enabled)
            })
            Text(stringResource(if (draft.contentMode == PrintContentMode.Source)
                R.string.print_source_description else R.string.print_formatted_description),
                style = MaterialTheme.typography.bodySmall)
        }
        Text(stringResource(R.string.excerpt_paper), style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() })
        SingleChoiceButtons(listOf(
            SingleChoiceOption(stringResource(R.string.excerpt_a4), !controller.paperLetter,
                { controller.updatePaperLetter(false) }, enabled),
            SingleChoiceOption(stringResource(R.string.excerpt_letter), controller.paperLetter,
                { controller.updatePaperLetter(true) }, enabled)
        ))
        Text(stringResource(R.string.print_text), style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() })
        SingleChoiceButtons(PrintFontFamily.entries.map { family ->
            SingleChoiceOption(stringResource(when (family) {
                PrintFontFamily.SansSerif -> R.string.print_font_sans
                PrintFontFamily.Serif -> R.string.print_font_serif
                PrintFontFamily.Monospace -> R.string.print_font_mono
            }), draft.fontFamily == family, { controller.updatePrintDraft(draft.copy(fontFamily = family)) }, enabled)
        })
        OutlinedTextField(draft.fontSize, { controller.updatePrintDraft(draft.copy(fontSize = it)) }, enabled = enabled,
            label = { Text(stringResource(R.string.print_text_size)) }, suffix = { Text(stringResource(R.string.print_point_unit)) },
            isError = validation.isFontSizeInvalid,
            supportingText = if (validation.isFontSizeInvalid) ({ Text(stringResource(R.string.print_text_size_error)) }) else null,
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        ExcerptToggle(stringResource(R.string.print_wrap_source_lines), draft.wrapLongLines, enabled) {
            controller.updatePrintDraft(draft.copy(wrapLongLines = it))
        }
        ExcerptToggle(stringResource(R.string.print_file_name_header), draft.showFileName, enabled) {
            controller.updatePrintDraft(draft.copy(showFileName = it))
        }
        ExcerptToggle(stringResource(R.string.print_page_numbers), draft.showPageNumbers, enabled) {
            controller.updatePrintDraft(draft.copy(showPageNumbers = it))
        }
        Text(stringResource(R.string.print_margins), style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() })
        SingleChoiceButtons(PrintMarginUnit.entries.map { unit ->
            SingleChoiceOption(stringResource(if (unit == PrintMarginUnit.Inches) R.string.print_inches else R.string.print_millimetres),
                draft.marginUnit == unit, { controller.updatePrintDraft(convertPrintMarginUnit(draft, unit)) }, enabled)
        })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Triple(PrintMarginField.Top, R.string.print_margin_top, draft.topMargin),
                Triple(PrintMarginField.Bottom, R.string.print_margin_bottom, draft.bottomMargin),
                Triple(PrintMarginField.Left, R.string.print_margin_left, draft.leftMargin),
                Triple(PrintMarginField.Right, R.string.print_margin_right, draft.rightMargin)).forEach { (field, label, value) ->
                val unit = stringResource(if (draft.marginUnit == PrintMarginUnit.Inches) R.string.print_inch_unit else R.string.print_millimetre_unit)
                val invalid = field in validation.invalidMarginFields
                OutlinedTextField(value, { value -> controller.updatePrintDraft(when (field) {
                    PrintMarginField.Top -> draft.copy(topMargin = value)
                    PrintMarginField.Bottom -> draft.copy(bottomMargin = value)
                    PrintMarginField.Left -> draft.copy(leftMargin = value)
                    PrintMarginField.Right -> draft.copy(rightMargin = value)
                }) }, enabled = enabled, label = { Text(stringResource(label)) }, suffix = { Text(unit) }, singleLine = true,
                    isError = invalid, supportingText = if (invalid) ({
                        Text(stringResource(R.string.print_margin_error, maximumPrintMarginText(draft.marginUnit), unit))
                    }) else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.width(130.dp))
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
