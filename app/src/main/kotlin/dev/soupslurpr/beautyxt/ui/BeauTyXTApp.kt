package dev.soupslurpr.beautyxt.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.nfc.NfcManager
import android.print.PrintManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.soupslurpr.beautyxt.DocumentSessionReturnDestination
import dev.soupslurpr.beautyxt.InitialDocumentAction
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.document.DocumentFormat
import dev.soupslurpr.beautyxt.exporting.client.DocumentExportException
import dev.soupslurpr.beautyxt.exporting.client.IsolatedDocumentExporter
import dev.soupslurpr.beautyxt.exporting.client.TransientDestinationSelection
import dev.soupslurpr.beautyxt.importing.client.IsolatedDocumentImporter
import dev.soupslurpr.beautyxt.importing.client.SelectedDocumentSource
import dev.soupslurpr.beautyxt.importing.client.querySelectedDocumentMetadata
import dev.soupslurpr.beautyxt.markdown.client.IsolatedMarkdownRenderer
import dev.soupslurpr.beautyxt.printing.DocumentPrintAdapter
import dev.soupslurpr.beautyxt.sharing.IncomingDocumentShare
import dev.soupslurpr.beautyxt.sharing.IncomingSourcePurpose
import dev.soupslurpr.beautyxt.sharing.IncomingTextOrigin
import dev.soupslurpr.beautyxt.sharing.createDocumentShareChooser
import dev.soupslurpr.beautyxt.sharing.incomingSharedTextTitle
import dev.soupslurpr.beautyxt.transfer.client.IsolatedTransferProcessor
import dev.soupslurpr.beautyxt.transfer.client.NfcTransferProcessor
import dev.soupslurpr.beautyxt.transfer.client.ReceivedTransferText
import dev.soupslurpr.beautyxt.ui.editor.CloseRequestResult
import dev.soupslurpr.beautyxt.ui.editor.DocumentEditor
import dev.soupslurpr.beautyxt.ui.editor.EditorSession
import dev.soupslurpr.beautyxt.ui.editor.ImportedSourcePolicy
import dev.soupslurpr.beautyxt.ui.editor.NfcWriteStatus
import dev.soupslurpr.beautyxt.ui.editor.PrintStatus
import dev.soupslurpr.beautyxt.ui.editor.SaveDestinationPurpose
import dev.soupslurpr.beautyxt.ui.editor.SaveDestinationRequest
import dev.soupslurpr.beautyxt.ui.editor.SelectedEditorDocumentSource
import dev.soupslurpr.beautyxt.ui.editor.ShareStatus
import dev.soupslurpr.beautyxt.ui.editor.createImportedEditorSession
import dev.soupslurpr.beautyxt.ui.transfer.NfcReadScreen
import dev.soupslurpr.beautyxt.ui.transfer.NfcWriteScreen
import dev.soupslurpr.beautyxt.ui.transfer.QrScannerScreen
import kotlin.coroutines.cancellation.CancellationException

private const val PLAIN_TEXT_MIME_TYPE = "text/plain"
private const val MARKDOWN_MIME_TYPE = "text/markdown"
private const val TEXT_MIME_TYPE_PATTERN = "text/*"
private const val APPLICATION_MARKDOWN_MIME_TYPE = "application/markdown"
private const val APPLICATION_X_MARKDOWN_MIME_TYPE = "application/x-markdown"
private const val BINARY_MIME_TYPE = "application/octet-stream"
private val CURRENT_SOURCE_DESTINATION_MESSAGE =
    UiText.Resource(R.string.operation_current_source_destination)

/** Displays one canonical BeauTyXT document session. */
@Composable
internal fun BeauTyXTApp(
    returnDestination: DocumentSessionReturnDestination,
    modifier: Modifier = Modifier,
    initialDocumentAction: InitialDocumentAction = InitialDocumentAction.Incoming,
    incomingShare: IncomingDocumentShare? = null,
    onIncomingShareConsumed: (IncomingDocumentShare) -> Unit = {},
    onDocumentSessionClosed: () -> Unit = {}
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val applicationContext = context.applicationContext
    val importer = remember(applicationContext) { IsolatedDocumentImporter(applicationContext) }
    val exporter = remember(applicationContext) { IsolatedDocumentExporter(applicationContext) }
    val markdownRenderer =
        remember(applicationContext) { IsolatedMarkdownRenderer(applicationContext) }
    val transferProcessor =
        remember(applicationContext) { IsolatedTransferProcessor(applicationContext) }
    val nfcAdapter =
        remember(applicationContext) {
            applicationContext.getSystemService(NfcManager::class.java)?.defaultAdapter
        }
    val printManager = remember(context) { context.getSystemService(PrintManager::class.java) }
    val nfcTransferProcessor: NfcTransferProcessor? =
        if (nfcAdapter == null) null else transferProcessor
    val hasCamera =
        remember(applicationContext) {
            applicationContext.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        }
    var hasCameraPermission by
        remember(applicationContext) {
            mutableStateOf(
                ContextCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            )
        }
    var isQrScannerVisible by retain { mutableStateOf(false) }
    var isNfcReaderVisible by retain { mutableStateOf(false) }
    var initialActionConsumed by retain { mutableStateOf(false) }
    var isDocumentSessionClosing by retain { mutableStateOf(false) }
    val session =
        retain {
            BeauTyXTSession(
                createEmptyEditor = {
                    EditorSession.createEmpty(
                        markdownRenderer = markdownRenderer,
                        qrTransferProcessor = transferProcessor,
                        nfcTransferProcessor = nfcTransferProcessor
                    )
                },
                createTransientTextEditor = { share ->
                    EditorSession.createTransientText(
                        text = share.text,
                        title = incomingSharedTextTitle(share.format),
                        initialPresentation = incomingEditorPresentation(share.format),
                        markdownRenderer = markdownRenderer,
                        qrTransferProcessor = transferProcessor,
                        nfcTransferProcessor = nfcTransferProcessor
                    )
                }
            )
        }
    LaunchedEffect(initialDocumentAction, initialActionConsumed) {
        if (initialActionConsumed) {
            return@LaunchedEffect
        }
        when (initialDocumentAction) {
            InitialDocumentAction.Incoming -> Unit

            InitialDocumentAction.NewDocument -> session.startNewDocument()

            InitialDocumentAction.SelectDocument,
            InitialDocumentAction.OpeningSelectedDocument,
            InitialDocumentAction.Unrestorable -> Unit

            InitialDocumentAction.ScanQr -> isQrScannerVisible = hasCamera

            InitialDocumentAction.ReadNfc -> isNfcReaderVisible = nfcAdapter != null
        }
        initialActionConsumed = true
    }
    LaunchedEffect(incomingShare) {
        val share = incomingShare ?: return@LaunchedEffect
        session.offerIncomingShare(share)
        onIncomingShareConsumed(share)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        session.checkpointPendingEdit()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasCameraPermission =
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
    }
    val cameraPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasCameraPermission = granted
        }
    val plainTextDestination =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(PLAIN_TEXT_MIME_TYPE)
        ) { uri ->
            if (uri == null) {
                session.cancelSaveDestination(DocumentFormat.PlainText)
            } else {
                val accepted =
                    session.launchSaveDestinationResult(DocumentFormat.PlainText) { request ->
                        saveSelectedDestination(
                            session = session,
                            applicationContext = applicationContext,
                            exporter = exporter,
                            request = request,
                            uri = uri
                        )
                    }
                if (!accepted) {
                    session.reportAbandonedSaveResult()
                }
            }
        }
    val markdownDestination =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(MARKDOWN_MIME_TYPE)
        ) { uri ->
            if (uri == null) {
                session.cancelSaveDestination(DocumentFormat.Markdown)
            } else {
                val accepted =
                    session.launchSaveDestinationResult(DocumentFormat.Markdown) { request ->
                        saveSelectedDestination(
                            session = session,
                            applicationContext = applicationContext,
                            exporter = exporter,
                            request = request,
                            uri = uri
                        )
                    }
                if (!accepted) {
                    session.reportAbandonedSaveResult()
                }
            }
        }
    val editor = session.editor
    val saveStatus = editor?.saveStatus
    val readyShare = editor?.shareStatus as? ShareStatus.Ready
    val readyPrint = editor?.printStatus as? PrintStatus.Ready
    LaunchedEffect(editor, saveStatus) {
        val currentEditor = editor ?: return@LaunchedEffect
        val request = session.claimSaveDestination(currentEditor) ?: return@LaunchedEffect
        try {
            when (request.format) {
                DocumentFormat.PlainText ->
                    plainTextDestination.launch(request.suggestedName)

                DocumentFormat.Markdown ->
                    markdownDestination.launch(request.suggestedName)
            }
        } catch (_: Exception) {
            session.failSaveDestinationLaunch(request)
        }
    }
    LaunchedEffect(editor, readyShare?.request?.generation) {
        val currentEditor = editor ?: return@LaunchedEffect
        val request = readyShare?.request ?: return@LaunchedEffect
        try {
            context.startActivity(createDocumentShareChooser(request, resources))
            currentEditor.completeShareLaunch(request.generation)
        } catch (_: Exception) {
            currentEditor.failShareLaunch(request.generation)
        }
    }
    LaunchedEffect(editor, readyPrint?.request?.generation) {
        val currentEditor = editor ?: return@LaunchedEffect
        val request = readyPrint?.request ?: return@LaunchedEffect
        var adapter: DocumentPrintAdapter? = null
        try {
            val claimedAdapter = request.claimAdapter(resources)
            adapter = claimedAdapter
            checkNotNull(printManager) { "print manager is unavailable" }
                .print(claimedAdapter.jobName, claimedAdapter, null)
            adapter = null
            currentEditor.completePrintLaunch(request.generation)
        } catch (_: Exception) {
            currentEditor.failPrintLaunch(request.generation)
        } catch (_: LinkageError) {
            currentEditor.failPrintLaunch(request.generation)
        } finally {
            adapter?.close()
        }
    }
    val openStatus = session.openStatus

    /** Opens one confirmed shared URI through the existing isolated import path. */
    fun openIncomingSource() {
        val share = session.takeIncomingSource() ?: return
        session.openSelectedDocument {
            createIncomingSourceEditor(
                share = share,
                importer = importer,
                exporter = exporter,
                markdownRenderer = markdownRenderer,
                transferProcessor = transferProcessor,
                nfcTransferProcessor = nfcTransferProcessor
            )
        }
    }

    LaunchedEffect(session.incomingShare, editor, openStatus) {
        val source = session.incomingShare as? IncomingDocumentShare.Source
            ?: return@LaunchedEffect
        if (
            source.purpose != IncomingSourcePurpose.Share &&
            editor == null &&
            !session.isOpenBusy
        ) {
            session.openIncomingRequestedSource {
                createIncomingSourceEditor(
                    share = source,
                    importer = importer,
                    exporter = exporter,
                    markdownRenderer = markdownRenderer,
                    transferProcessor = transferProcessor,
                    nfcTransferProcessor = nfcTransferProcessor
                )
            }
        }
    }

    /** Closes the current editor or exposes its existing safe-close recovery. */
    fun closeCurrentEditorForIncomingShare() {
        val currentEditor = session.editor ?: return
        if (currentEditor.requestClose() == CloseRequestResult.CloseNow) {
            session.closeEditor()
        }
    }

    /** Keeps an intentionally closed session neutral while Android finishes the activity. */
    fun closeDocumentSession() {
        isDocumentSessionClosing = true
        onDocumentSessionClosed()
    }

    /** Closes the editor while preserving any incoming content still under review. */
    fun closeCurrentEditor() {
        session.closeEditor()
        if (session.editor == null && session.incomingShare == null) {
            closeDocumentSession()
        }
    }

    /** Dismisses one incoming offer and closes an otherwise empty document task. */
    fun dismissIncomingShare() {
        session.dismissIncomingShare()
        if (session.editor == null) {
            closeDocumentSession()
        }
    }

    /** Closes QR scanning and finishes an otherwise empty document task. */
    fun closeQrScanner() {
        isQrScannerVisible = false
        if (session.editor == null && session.incomingShare == null) {
            closeDocumentSession()
        }
    }

    /** Closes NFC reading and finishes an otherwise empty document task. */
    fun closeNfcReader() {
        isNfcReaderVisible = false
        if (session.editor == null && session.incomingShare == null) {
            closeDocumentSession()
        }
    }

    /** Reopens one explicitly confirmed conflicted source through isolated import. */
    fun reloadCurrentSource() {
        val currentEditor = session.editor ?: return
        session.reloadEditorSource(currentEditor) { encodedUri ->
            createImportedEditorSession(
                importedDocument = importer.open(encodedUri.toUri()),
                sourceFormat = currentEditor.documentFormat,
                exporter = exporter,
                markdownRenderer = markdownRenderer,
                qrTransferProcessor = transferProcessor,
                nfcTransferProcessor = nfcTransferProcessor
            )
        }
    }

    /** Opens the foreground scanner after preserving any live editor draft. */
    fun showQrScanner() {
        if (
            !hasCamera ||
            isNfcReaderVisible ||
            session.isOpenBusy ||
            session.incomingShare != null ||
            session.editor?.canStartQrScan == false
        ) {
            return
        }
        session.flushPendingEdit()
        isQrScannerVisible = true
    }

    /** Opens foreground NFC reading after preserving any live editor draft. */
    fun showNfcReader() {
        if (
            nfcAdapter == null ||
            isQrScannerVisible ||
            session.isOpenBusy ||
            session.incomingShare != null ||
            session.editor?.canStartNfcRead == false
        ) {
            return
        }
        session.flushPendingEdit()
        isNfcReaderVisible = true
    }

    /** Requests Android's camera capability only from the visible scanner. */
    fun requestCameraPermission() {
        if (!isQrScannerVisible || !hasCamera) {
            return
        }
        try {
            cameraPermission.launch(Manifest.permission.CAMERA)
        } catch (_: Exception) {
            hasCameraPermission = false
        }
    }

    /** Offers one verified QR transfer through the existing explicit review flow. */
    fun receiveQrTransfer(transfer: ReceivedTransferText) {
        if (!isQrScannerVisible) {
            return
        }
        isQrScannerVisible = false
        session.offerIncomingShare(
            IncomingDocumentShare.Text(
                text = transfer.text,
                format = transfer.format,
                origin = IncomingTextOrigin.Qr
            )
        )
    }

    /** Offers one verified NFC transfer through the existing explicit review flow. */
    fun receiveNfcTransfer(transfer: ReceivedTransferText) {
        if (!isNfcReaderVisible) {
            return
        }
        isNfcReaderVisible = false
        session.offerIncomingShare(
            IncomingDocumentShare.Text(
                text = transfer.text,
                format = transfer.format,
                nfcMetadata = transfer.nfcMetadata,
                origin = IncomingTextOrigin.Nfc
            )
        )
    }

    val readyNfcWrite = editor?.nfcWriteStatus as? NfcWriteStatus.Ready

    val isTransferScreenVisible =
        isQrScannerVisible ||
            (isNfcReaderVisible && nfcAdapter != null) ||
            (readyNfcWrite != null && nfcAdapter != null)
    val editorScreenModifier =
        Modifier
            .fillMaxSize()
            .then(
                if (isTransferScreenVisible) {
                    Modifier.clearAndSetSemantics {}
                } else {
                    Modifier
                }
            )
    Box(modifier = modifier.fillMaxSize()) {
        if (editor == null) {
            DocumentSessionBackground(
                openStatus = openStatus,
                isClosing = isDocumentSessionClosing,
                recoveryDestination =
                    if (initialDocumentAction == InitialDocumentAction.Unrestorable) {
                        returnDestination
                    } else {
                        null
                    },
                unavailableMessage =
                    when {
                        initialDocumentAction == InitialDocumentAction.ScanQr && !hasCamera ->
                            UiText.Resource(R.string.operation_qr_unavailable)

                        initialDocumentAction == InitialDocumentAction.ReadNfc &&
                            nfcAdapter == null ->
                            UiText.Resource(R.string.operation_nfc_unavailable)

                        initialDocumentAction == InitialDocumentAction.Incoming &&
                            incomingShare == null &&
                            session.incomingShare == null &&
                            openStatus == OpenStatus.Idle ->
                            UiText.Resource(R.string.operation_incoming_unavailable_short)

                        else -> null
                    },
                isPreparingInitialSource =
                    initialDocumentAction == InitialDocumentAction.OpeningSelectedDocument,
                onClose = ::closeDocumentSession,
                modifier = Modifier.fillMaxSize()
            )
        }

        if (editor != null) {
            DocumentEditor(
                session = editor,
                onClose = ::closeCurrentEditor,
                closesDocumentTask = true,
                onCancelSave = {
                    if (!session.cancelSaveDestinationResult(editor)) {
                        editor.cancelSave()
                    }
                },
                onReloadSource = ::reloadCurrentSource,
                scanQrEnabled = hasCamera && session.incomingShare == null,
                onScanQr = ::showQrScanner,
                writeNfcEnabled = nfcAdapter != null,
                readNfcEnabled = nfcAdapter != null && session.incomingShare == null,
                onReadNfc = ::showNfcReader,
                modifier = editorScreenModifier
            )
        }

        when {
            isQrScannerVisible ->
                QrScannerScreen(
                    processor = transferProcessor,
                    hasCameraPermission = hasCameraPermission,
                    onRequestCameraPermission = ::requestCameraPermission,
                    onReceived = ::receiveQrTransfer,
                    onClose = ::closeQrScanner,
                    modifier = Modifier.fillMaxSize()
                )

            isNfcReaderVisible && nfcAdapter != null ->
                NfcReadScreen(
                    adapter = nfcAdapter,
                    processor = transferProcessor,
                    onReceived = ::receiveNfcTransfer,
                    onClose = ::closeNfcReader,
                    modifier = Modifier.fillMaxSize()
                )

            readyNfcWrite != null && nfcAdapter != null ->
                NfcWriteScreen(
                    adapter = nfcAdapter,
                    ready = readyNfcWrite,
                    onWritten = { generation -> editor.completeNfcWrite(generation) },
                    onClose = editor::cancelNfcWrite,
                    modifier = Modifier.fillMaxSize()
                )
        }
    }

    val pendingIncomingShare = session.incomingShare
    val editorOwnsCloseDialog =
        editor?.isDiscardConfirmationVisible == true || editor?.isClosePending == true
    if (
        pendingIncomingShare != null &&
        !editorOwnsCloseDialog &&
        !isQrScannerVisible &&
        !isNfcReaderVisible &&
        readyNfcWrite == null
    ) {
        IncomingShareDialog(
            share = pendingIncomingShare,
            currentDocumentTitle =
                if (pendingIncomingShare is IncomingDocumentShare.Rejected) {
                    null
                } else {
                    editor?.title
                },
            canOpen = editor == null && !session.isOpenBusy,
            onDismiss = ::dismissIncomingShare,
            onCloseCurrentDocument = ::closeCurrentEditorForIncomingShare,
            onOpenText = { session.openIncomingText() },
            onOpenSource = ::openIncomingSource
        )
    }
}

/** Displays bounded opening or failure state behind one document-session overlay. */
@Composable
private fun DocumentSessionBackground(
    openStatus: OpenStatus,
    isClosing: Boolean,
    recoveryDestination: DocumentSessionReturnDestination?,
    unavailableMessage: UiText?,
    isPreparingInitialSource: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.Center
        ) {
            when {
                isClosing -> Unit

                isPreparingInitialSource ||
                    openStatus == OpenStatus.Opening ||
                    openStatus == OpenStatus.Cancelling ->
                    CircularProgressIndicator()

                recoveryDestination != null ->
                    DocumentSessionRecovery(
                        returnDestination = recoveryDestination,
                        onReturn = onClose
                    )

                openStatus is OpenStatus.Failed || unavailableMessage != null ->
                    Column(
                        modifier = Modifier.padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text =
                                (
                                    unavailableMessage
                                        ?: (openStatus as OpenStatus.Failed).message
                                    ).asString(),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Button(onClick = onClose) {
                            Text(stringResource(R.string.action_close))
                        }
                    }
            }
        }
    }
}

/** Returns whether Android asked to open one source directly instead of sharing it. */
internal fun isDirectSourceRequest(share: IncomingDocumentShare?): Boolean =
    share is IncomingDocumentShare.Source && share.purpose != IncomingSourcePurpose.Share

/** Creates one source-backed editor for an Android view, edit, or confirmed share request. */
private suspend fun createIncomingSourceEditor(
    share: IncomingDocumentShare.Source,
    importer: IsolatedDocumentImporter,
    exporter: IsolatedDocumentExporter,
    markdownRenderer: IsolatedMarkdownRenderer,
    transferProcessor: IsolatedTransferProcessor,
    nfcTransferProcessor: NfcTransferProcessor?
): EditorSession = createImportedEditorSession(
    importedDocument =
        importer.open(
            uri = share.encodedUri.toUri(),
            allowSourceWriteAccess = share.purpose == IncomingSourcePurpose.Edit
        ),
    exporter = exporter,
    markdownRenderer = markdownRenderer,
    qrTransferProcessor = transferProcessor,
    nfcTransferProcessor = nfcTransferProcessor,
    sourcePurpose = share.purpose,
    sourceFormat = share.format,
    sourcePolicy =
        if (share.purpose == IncomingSourcePurpose.Edit) {
            ImportedSourcePolicy.PreferWritable
        } else {
            ImportedSourcePolicy.ReadOnly
        }
)

/** Returns discovery hints for provider-reported plain text and Markdown files. */
internal fun openDocumentMimeTypes(): Array<String> = arrayOf(
    TEXT_MIME_TYPE_PATTERN,
    APPLICATION_MARKDOWN_MIME_TYPE,
    APPLICATION_X_MARKDOWN_MIME_TYPE,
    BINARY_MIME_TYPE
)

/** Routes one picker result to a replacement source or a transient copy destination. */
private suspend fun saveSelectedDestination(
    session: BeauTyXTSession,
    applicationContext: Context,
    exporter: IsolatedDocumentExporter,
    request: SaveDestinationRequest,
    uri: Uri
) {
    if (session.isCurrentSourceDestination(request, uri.toString())) {
        session.failSaveDestination(request.format, CURRENT_SOURCE_DESTINATION_MESSAGE)
        return
    }
    if (request.purpose == SaveDestinationPurpose.SourceReplacement) {
        saveSelectedSource(
            session = session,
            applicationContext = applicationContext,
            exporter = exporter,
            format = request.format,
            uri = uri
        )
        return
    }
    val selection = TransientDestinationSelection.from(uri)
    val destination =
        try {
            try {
                exporter.openDestination(selection)
            } finally {
                selection.close()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: DocumentExportException) {
            session.failSaveDestination(request.format, failure.failure.userMessage)
            return
        } catch (_: Exception) {
            session.failSaveDestination(
                request.format,
                UiText.Resource(R.string.operation_destination_unavailable)
            )
            return
        }
    var accepted = false
    try {
        accepted =
            session.saveSelectedDestination(
                format = request.format,
                destinationOwner = destination
            ) { snapshot, expectedBytes ->
                exporter.save(
                    destination = destination,
                    snapshot = snapshot,
                    expectedBytes = expectedBytes
                )
            }
    } finally {
        if (!accepted) {
            destination.close()
        }
    }
}

/** Retains one selected replacement destination as the autosave source. */
private suspend fun saveSelectedSource(
    session: BeauTyXTSession,
    applicationContext: Context,
    exporter: IsolatedDocumentExporter,
    format: DocumentFormat,
    uri: Uri
) {
    val sourceMetadata =
        querySelectedDocumentMetadata(
            contentResolver = applicationContext.contentResolver,
            uri = uri
        )
    val selectedSource =
        try {
            SelectedDocumentSource.takeOwnership(context = applicationContext, uri = uri)
        } catch (_: IllegalArgumentException) {
            session.failSaveDestination(
                format = format,
                message = UiText.Resource(R.string.operation_destination_unavailable)
            )
            return
        }
    var unclaimedSource: SelectedDocumentSource? = selectedSource
    val documentSource =
        try {
            SelectedEditorDocumentSource.takeCreatedOwnership(
                selectedSource = checkNotNull(unclaimedSource),
                exporter = exporter
            ).also { unclaimedSource = null }
        } finally {
            unclaimedSource?.close()
        }
    var accepted = false
    try {
        accepted =
            session.saveSelectedSource(
                format = format,
                documentSource = documentSource,
                sourceDisplayName = sourceMetadata.displayName,
                sourceMetadata = sourceMetadata
            )
    } finally {
        if (!accepted) {
            documentSource.close()
        }
    }
}
