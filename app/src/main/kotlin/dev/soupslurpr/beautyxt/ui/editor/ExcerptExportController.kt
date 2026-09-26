package dev.soupslurpr.beautyxt.ui.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintManager
import androidx.compose.runtime.*
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.document.*
import dev.soupslurpr.beautyxt.exporting.ExportProtocol
import dev.soupslurpr.beautyxt.exporting.client.IsolatedDocumentExporter
import dev.soupslurpr.beautyxt.exporting.client.TransientDestinationSelection
import dev.soupslurpr.beautyxt.ipc.SealedInput
import dev.soupslurpr.beautyxt.markdown.MarkdownPreviewDocument
import dev.soupslurpr.beautyxt.markdown.client.MarkdownRenderer
import dev.soupslurpr.beautyxt.printing.*
import dev.soupslurpr.beautyxt.sharing.*
import dev.soupslurpr.beautyxt.transfer.TransferProtocol
import dev.soupslurpr.beautyxt.transfer.canonicalNfcTagLabelOrNull
import dev.soupslurpr.beautyxt.transfer.isValidNfcTagLabelInput
import dev.soupslurpr.beautyxt.transfer.client.*
import dev.soupslurpr.beautyxt.ui.UiText
import kotlinx.coroutines.*

internal enum class ExcerptDestination { Copy, Share, Save, Qr, Nfc, Print }
internal enum class ExcerptFormat { Text, Markdown, Pdf }
private val ExcerptViewportLimits = ViewportLimits(24, 4096, 16 * 1024)

internal class PreparedExcerpt(
    val text: CapturedDocumentRevision,
    val textFormat: DocumentFormat,
    val formatted: MarkdownPreviewDocument?,
    val notices: List<ExcerptNotice>,
    val settings: PrintSettings,
    var pdf: SealedInput? = null,
    var pdfPages: Int = 0,
    var qr: QrCodeGrid? = null,
    var nfc: NfcTransferEnvelope? = null
) : AutoCloseable {
    override fun close() { text.close(); pdf?.close(); nfc?.close() }
}

/** Owns a frozen review and its bounded outputs independently of Compose and provider pickers. */
internal class ExcerptExportController(
    private val scope: CoroutineScope,
    private val captureSelection: suspend () -> ExcerptCapture?,
    private val currentRevision: () -> Long?,
    private val matchesSourceUri: (String) -> Boolean,
    private val renderer: MarkdownRenderer?,
    private val qrProcessor: QrTransferProcessor?,
    private val nfcProcessor: NfcTransferProcessor?,
    private val showQr: (QrCodeGrid, Long, DocumentFormat) -> Unit,
    private val writeNfc: (NfcTransferEnvelope, Long, DocumentFormat, String?) -> Unit
) : AutoCloseable {
    var visible by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var handingOff by mutableStateOf(false); private set
    var destination by mutableStateOf(ExcerptDestination.Share); private set
    var format by mutableStateOf(ExcerptFormat.Text); private set
    var shareAsFile by mutableStateOf(false); private set
    var fileName by mutableStateOf(""); private set
    var tagLabel by mutableStateOf(""); private set
    var printDraft by mutableStateOf(defaultPrintSetupDraft(false)); private set
    var paperLetter by mutableStateOf(false); private set
    var capture by mutableStateOf<ExcerptCapture?>(null); private set
    var prepared by mutableStateOf<PreparedExcerpt?>(null); private set
    var textPreview by mutableStateOf<ViewportSnapshot?>(null); private set
    var loadingTextPreview by mutableStateOf(false); private set
    var textPreviewFailed by mutableStateOf(false); private set
    var message by mutableStateOf<UiText?>(null); private set
    private var title = ""
    private var context: Context? = null
    private var generation = 0L
    private var job: Job? = null
    private var previewGeneration = 0L
    private var previewJob: Job? = null
    private var failedPreviewDirection: Boolean? = null
    private var resetCaptureOptions = true
    private val previewCursors = ArrayDeque<ViewportCursor>()
    private val shares = ExcerptShareCoordinator()
    private var activeShareId: String? = null
    val isStale get() = capture?.revision?.let { it != currentRevision() } == true
    // Option changes can replace derived output only after the selection itself is captured.
    val canConfigure get() = visible && capture != null && !handingOff
    val canFormat get() = capture?.canFormat == true
    val canPreviousPreview get() = previewCursors.size > 1
    val textBytes get() = prepared?.text?.metrics?.serializedByteLength
    val maximumBytes get() = when (destination) {
        ExcerptDestination.Copy -> MAX_SHARED_TEXT_UTF8_BYTES
        ExcerptDestination.Share -> if (shareAsFile || format == ExcerptFormat.Pdf) ExportProtocol.MAX_BYTE_LIMIT else MAX_SHARED_TEXT_UTF8_BYTES
        ExcerptDestination.Qr -> TransferProtocol.MAX_QR_TEXT_BYTES
        ExcerptDestination.Nfc -> TransferProtocol.MAX_NFC_TEXT_BYTES
        else -> ExportProtocol.MAX_BYTE_LIMIT
    }
    val outputBytes get() = if (format == ExcerptFormat.Pdf) prepared?.pdf?.byteCount else textBytes
    val fits get() = outputBytes?.let { it <= maximumBytes } == true
    val requiresFileName get() = destination in listOf(ExcerptDestination.Save, ExcerptDestination.Print) ||
        destination == ExcerptDestination.Share && (shareAsFile || format == ExcerptFormat.Pdf)
    val validFileName get() = fileName.isNotBlank() && '/' !in fileName && '\\' !in fileName && !fileName.any { it.isISOControl() }
    val canApply get() = visible && prepared != null && fits && !isStale && !busy && !handingOff &&
        (destination != ExcerptDestination.Nfc || prepared?.nfc != null) &&
        (destination != ExcerptDestination.Qr || prepared?.qr != null) &&
        (!requiresFileName || validFileName)
    val canRetryPreparation get() = visible && !busy && !handingOff && !isStale && prepared == null &&
        message == UiText.Resource(R.string.selection_output_failed)
    val mimeType get() = if (format == ExcerptFormat.Pdf) "application/pdf" else prepared?.textFormat?.mimeType ?: "text/plain"
    val attributes: PrintAttributes get() = PrintAttributes.Builder()
        .setMediaSize(if (paperLetter) PrintAttributes.MediaSize.NA_LETTER else PrintAttributes.MediaSize.ISO_A4)
        .setResolution(PrintAttributes.Resolution("excerpt", "PDF", 300, 300))
        .setColorMode(PrintAttributes.COLOR_MODE_COLOR).setMinMargins(PrintAttributes.Margins.NO_MARGINS).build()

    fun open(context: Context, title: String) {
        if (handingOff || !scope.isActive) return
        this.context = context.applicationContext
        this.title = title
        destination = ExcerptDestination.Share
        format = ExcerptFormat.Text
        shareAsFile = false
        fileName = ""
        tagLabel = ""
        printDraft = defaultPrintSetupDraft(false)
        paperLetter = false
        visible = true
        recapture(resetOptions = true)
    }

    fun refresh() = recapture(resetOptions = false)

    fun retryPreparation() {
        if (!canRetryPreparation) return
        if (capture == null) recapture(resetCaptureOptions) else prepare()
    }

    private fun recapture(resetOptions: Boolean) {
        if (!visible || handingOff || !scope.isActive) return
        resetCaptureOptions = resetOptions
        cancelPreparation()
        capture?.close(); capture = null
        val request = generation
        busy = true
        // Register ownership before capture can finish inline and start preparation.
        val captureJob = scope.launch(start = CoroutineStart.LAZY) {
            var owned: ExcerptCapture? = null
            try {
                owned = captureSelection() ?: error("Selection is unavailable")
                ensureActive()
                if (request != generation) return@launch
                capture = owned; owned = null
                if (resetOptions) {
                    format = defaultFormat(destination)
                    printDraft = defaultPrintSetupDraft(canFormat && capture?.exactSource != true)
                    updateSuggestedName()
                }
                busy = false
                job = null
                prepare()
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (request == generation) fail(R.string.selection_output_failed) }
            finally { owned?.close() }
        }
        job = captureJob
        captureJob.start()
    }

    fun selectDestination(value: ExcerptDestination) {
        if (!canConfigure || destination == value) return
        destination = value
        format = defaultFormat(value)
        updateSuggestedName()
        prepare()
    }
    fun selectFormat(value: ExcerptFormat) {
        if (!canConfigure || format == value || value == ExcerptFormat.Markdown && !canFormat ||
            value == ExcerptFormat.Pdf && destination in listOf(ExcerptDestination.Copy, ExcerptDestination.Qr, ExcerptDestination.Nfc)) return
        format = value; updateSuggestedName(); prepare()
    }
    fun updateShareAsFile(value: Boolean) { if (canConfigure) { shareAsFile = value; prepare() } }
    fun updateFileName(value: String) {
        if (canConfigure && value.length <= 240) {
            fileName = value
            if (format == ExcerptFormat.Pdf && printDraft.showFileName) prepare()
        }
    }
    fun updateTagLabel(value: String) { if (canConfigure && isValidNfcTagLabelInput(value)) { tagLabel = value; prepare() } }
    fun updatePrintDraft(value: PrintSetupDraft) { if (canConfigure && value.hasBoundedInput) { printDraft = value; prepare() } }
    fun updatePaperLetter(value: Boolean) { if (canConfigure) { paperLetter = value; prepare() } }
    private fun defaultFormat(destination: ExcerptDestination) = when {
        destination == ExcerptDestination.Print -> ExcerptFormat.Pdf
        destination == ExcerptDestination.Save && canFormat && capture?.exactSource != true -> ExcerptFormat.Markdown
        else -> ExcerptFormat.Text
    }
    private fun updateSuggestedName() {
        val base = title.substringAfterLast('/').substringAfterLast('\\')
        val stem = base.substringBeforeLast('.', base).map { if (it.isISOControl()) '_' else it }.joinToString("").ifBlank { "Notes" }
        val extension = when (format) {
            ExcerptFormat.Pdf -> ".pdf"
            ExcerptFormat.Markdown -> ".md"
            ExcerptFormat.Text -> capture?.textFormat?.filenameExtension ?: ".txt"
        }
        val shortened = stem.take(200).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
        fileName = shortened + " excerpt" + extension
    }

    private fun prepare() {
        cancelPreparation()
        val captured = capture ?: return
        val app = context ?: return
        val request = generation
        val outputFormat = format
        val outputDestination = destination
        val outputAttributes = attributes
        val outputName = fileName
        val outputTagLabel = canonicalNfcTagLabelOrNull(tagLabel)
        val printSettings = validatePrintSetup(if (outputFormat == ExcerptFormat.Pdf) printDraft else defaultPrintSetupDraft(false)).settings
            ?: run { fail(R.string.excerpt_invalid_print); return }
        val formatted = outputFormat == ExcerptFormat.Markdown || outputFormat == ExcerptFormat.Pdf && printSettings.contentMode == PrintContentMode.FormattedMarkdown
        // Claim the immutable source before dispatch so dismissing the sheet cannot close work in progress.
        val plain = captured.plain.snapshot.duplicate()
        busy = true
        job = scope.launch {
            var ownedText: CapturedDocumentRevision? = null
            var owned: PreparedExcerpt? = null
            try {
                withContext(Dispatchers.Default) {
                    val transformed = if (formatted) checkNotNull(captured.formatted()) else null
                    ownedText = if (transformed != null) captureGeneratedExcerpt(transformed.markdown)
                        else CapturedDocumentRevision(captured.plain.metrics, plain.duplicate())
                    val text = checkNotNull(ownedText)
                    val model = if (transformed != null) text.snapshot.duplicate().use {
                        checkNotNull(renderer).render(it, text.metrics.serializedByteLength)
                    } else null
                    owned = PreparedExcerpt(text, if (formatted) DocumentFormat.Markdown else captured.textFormat,
                        model, transformed?.notices.orEmpty(), printSettings)
                    ownedText = null
                    val payload = checkNotNull(owned)
                    if (outputFormat == ExcerptFormat.Pdf) {
                        payload.pdf = SealedInput.fromSuspendingStream(ExportProtocol.MAX_BYTE_LIMIT) { output ->
                            val layout = printRasterLayout(outputAttributes, printSettings)
                            val result = if (model != null) renderMarkdownDocumentPdf(output, model, outputName, layout,
                                printSettings, arrayOf(PageRange.ALL_PAGES), app.resources)
                            else renderDocumentTextPdf(output, text.snapshot, text.metrics, outputName, layout,
                                printSettings, arrayOf(PageRange.ALL_PAGES), app.resources)
                            payload.pdfPages = result.totalPageCount
                        }
                    }
                    val bytes = text.metrics.serializedByteLength
                    if (outputDestination == ExcerptDestination.Qr && bytes <= TransferProtocol.MAX_QR_TEXT_BYTES)
                        payload.qr = text.snapshot.duplicate().use { checkNotNull(qrProcessor).encodeQr(it, bytes, payload.textFormat) }
                    if (outputDestination == ExcerptDestination.Nfc && bytes <= TransferProtocol.MAX_NFC_TEXT_BYTES)
                        payload.nfc = text.snapshot.duplicate().use { checkNotNull(nfcProcessor).encodeNfc(it, bytes, payload.textFormat, outputTagLabel) }
                }
                ensureActive()
                if (request != generation) return@launch
                prepared = owned; owned = null
                busy = false
                loadTextPreview(null)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: SelectionLimitException) { if (request == generation) fail(R.string.excerpt_format_limit) }
            catch (_: Exception) { if (request == generation) fail(R.string.selection_output_failed) }
            catch (_: LinkageError) { if (request == generation) fail(R.string.selection_output_failed) }
            finally { plain.close(); ownedText?.close(); owned?.close() }
        }.also { launched -> launched.invokeOnCompletion { plain.close() } }
    }

    fun loadTextPreview(next: Boolean?) {
        if (next != null && loadingTextPreview) return
        val payload = prepared ?: return
        val cursor = when (next) {
            true -> textPreview?.next ?: return
            false -> { if (previewCursors.size <= 1) return; previewCursors.elementAt(previewCursors.size - 2) }
            null -> ViewportCursor(payload.text.metrics.revision, 0, 0)
        }
        cancelTextPreview()
        val request = previewGeneration
        val owned = payload.text.snapshot.duplicate()
        loadingTextPreview = true
        val pageJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val page = withContext(Dispatchers.Default) { owned.viewport(cursor, ExcerptViewportLimits) }
                if (request == previewGeneration && prepared === payload) {
                    if (next == null) previewCursors.clear()
                    if (next == false) previewCursors.removeLast() else previewCursors.addLast(cursor)
                    textPreview = page
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                if (request == previewGeneration && prepared === payload) {
                    textPreviewFailed = true
                    failedPreviewDirection = next
                }
            } finally {
                owned.close()
                if (request == previewGeneration) { loadingTextPreview = false; previewJob = null }
            }
        }
        previewJob = pageJob
        pageJob.invokeOnCompletion { owned.close() }
        pageJob.start()
    }

    fun retryTextPreview() {
        if (visible && textPreviewFailed && !loadingTextPreview) loadTextPreview(failedPreviewDirection)
    }

    fun chooseSaveDestination(): Boolean {
        if (!canApply || destination != ExcerptDestination.Save) return false
        handingOff = true
        return true
    }
    fun destinationReturned(uri: Uri?) {
        if (!handingOff || busy) return
        if (uri == null) { handingOff = false; return }
        val payload = prepared ?: run { handingOff = false; return }
        if (matchesSourceUri(uri.toString())) { handingOff = false; fail(R.string.excerpt_source_destination); return }
        val app = checkNotNull(context)
        val request = generation
        val outputFormat = format
        message = null
        busy = true
        job = scope.launch {
            var ownedPdf: SealedInput? = null
            try {
                val exporter = IsolatedDocumentExporter(app)
                if (outputFormat == ExcerptFormat.Pdf) withContext(Dispatchers.IO) {
                    ownedPdf = checkNotNull(payload.pdf).duplicate()
                }
                TransientDestinationSelection.from(uri).use { selection ->
                    exporter.openDestination(selection).use { destination ->
                        if (outputFormat == ExcerptFormat.Pdf) exporter.save(destination, checkNotNull(ownedPdf))
                        else payload.text.snapshot.duplicate().use { exporter.save(destination, it, payload.text.metrics.serializedByteLength) }
                    }
                }
                if (request == generation) message = UiText.Resource(R.string.excerpt_saved)
            } catch (cancel: CancellationException) {
                if (request == generation) message = UiText.Resource(R.string.excerpt_save_uncertain)
                throw cancel
            } catch (_: Exception) { if (request == generation) message = UiText.Resource(R.string.excerpt_save_uncertain) }
            finally {
                ownedPdf?.close()
                if (request == generation) { busy = false; handingOff = false }
            }
        }
    }

    val canCancelSave get() = handingOff && busy && destination == ExcerptDestination.Save
    fun cancelSave() { if (canCancelSave) job?.cancel() }

    val canEndPreviousShares get() = !handingOff && shares.hasDeliveredFiles &&
        message == UiText.Resource(R.string.excerpt_share_limit)
    fun endPreviousShares() {
        if (!canEndPreviousShares) return
        shares.releaseDeliveredFiles()
        message = UiText.Resource(R.string.excerpt_shares_ended)
    }

    fun apply(activityContext: Context, launchShare: ((ExcerptShareRequest) -> Unit)? = null) {
        if (!canApply || destination == ExcerptDestination.Save) return
        val payload = prepared ?: return
        val request = generation
        message = null
        handingOff = true
        job = scope.launch {
            try {
                when (destination) {
                    ExcerptDestination.Copy -> payload.text.snapshot.duplicate().use {
                        val text = readSharedTextSnapshot(it, payload.text.metrics.serializedByteLength)
                        checkNotNull(activityContext.getSystemService(ClipboardManager::class.java)).setPrimaryClip(ClipData.newPlainText(fileName, text))
                    }
                    ExcerptDestination.Share -> share(activityContext, payload, checkNotNull(launchShare))
                    ExcerptDestination.Qr -> {
                        showQr(checkNotNull(payload.qr), payload.text.metrics.serializedByteLength, payload.textFormat)
                        visible = false; cancelTextPreview()
                    }
                    ExcerptDestination.Nfc -> {
                        val envelope = checkNotNull(payload.nfc)
                        writeNfc(envelope, payload.text.metrics.serializedByteLength, payload.textFormat, canonicalNfcTagLabelOrNull(tagLabel))
                        payload.nfc = null; visible = false; cancelTextPreview()
                    }
                    ExcerptDestination.Print -> {
                        val content = payload.formatted?.let(::MarkdownPrintDocumentContent)
                            ?: SourcePrintDocumentContent(payload.text.metrics, payload.text.snapshot.duplicate())
                        val adapter = DocumentPrintAdapter(fileName, fileName, content, payload.settings, activityContext.resources)
                        try { checkNotNull(activityContext.getSystemService(PrintManager::class.java)).print(fileName, adapter, attributes) }
                        catch (failure: Throwable) { adapter.close(); throw failure }
                    }
                    ExcerptDestination.Save -> Unit
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: ExcerptShareLimitException) { if (request == generation) message = UiText.Resource(R.string.excerpt_share_limit) }
            catch (_: Exception) { if (request == generation) message = UiText.Resource(R.string.selection_output_failed) }
            finally { if (request == generation) handingOff = activeShareId != null }
        }
    }

    private suspend fun share(context: Context, payload: PreparedExcerpt, launch: (ExcerptShareRequest) -> Unit) {
        val intent = Intent(Intent.ACTION_SEND).setType(mimeType).putExtra(Intent.EXTRA_TITLE, fileName)
        var lease: ExcerptShareLease? = null
        try {
            if (shareAsFile || format == ExcerptFormat.Pdf) {
                var input: SealedInput? = null
                try {
                    withContext(Dispatchers.IO) {
                        input = payload.pdf?.duplicate() ?: payload.text.snapshot.duplicate().use {
                            SealedInput.fromSnapshot(it, payload.text.metrics.serializedByteLength, ExportProtocol.MAX_BYTE_LIMIT)
                        }
                    }
                    lease = ExcerptShares.retain(context, fileName, mimeType, checkNotNull(input)); input = null
                } finally { input?.close() }
                val uri = checkNotNull(lease).uri
                intent.putExtra(Intent.EXTRA_STREAM, uri).setClipData(ClipData.newRawUri(fileName, uri))
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else payload.text.snapshot.duplicate().use {
                intent.putExtra(Intent.EXTRA_TEXT, readSharedTextSnapshot(it, payload.text.metrics.serializedByteLength))
            }
            val request = shares.prepare(context, intent, context.getString(R.string.excerpt_share), lease)
            lease = null
            activeShareId = request.id
            try { launch(request) }
            catch (failure: Throwable) {
                activeShareId = null
                shares.failed(request.id)
                throw failure
            }
            message = UiText.Resource(R.string.excerpt_shared)
        } finally { lease?.close() }
    }

    fun shareChooserReturned(id: String) {
        shares.returned(id)
        if (activeShareId == id) {
            activeShareId = null
            handingOff = false
        }
    }

    fun dismiss() { if (!handingOff) { visible = false; cancelPreparation(); capture?.close(); capture = null } }
    private fun fail(resource: Int) { busy = false; message = UiText.Resource(resource) }
    private fun cancelTextPreview() {
        previewGeneration++; previewJob?.cancel(); previewJob = null
        loadingTextPreview = false
        textPreviewFailed = false; failedPreviewDirection = null
    }
    private fun cancelPreparation() {
        generation++; job?.cancel(); job = null
        cancelTextPreview()
        prepared?.close(); prepared = null; textPreview = null; previewCursors.clear()
        busy = false; message = null
    }
    override fun close() {
        visible = false; handingOff = false; cancelPreparation(); capture?.close(); capture = null
        activeShareId = null; shares.close(); context = null
    }
}
