/* Coordinates the retained editor session, lifecycle, and document actions. */
package dev.soupslurpr.beautyxt.ui.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.then
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.ui.PredictiveBackMotionHandler
import dev.soupslurpr.beautyxt.ui.predictiveBackMotion
import dev.soupslurpr.beautyxt.ui.rememberPredictiveBackMotionState
import kotlinx.coroutines.delay

private const val SAVE_SUCCESS_TIMEOUT_MILLIS = 3_000L

/** Displays a virtualized, bidirectionally paginated document editor. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DocumentEditor(
    session: EditorSession,
    onClose: () -> Unit,
    closesDocumentTask: Boolean,
    modifier: Modifier = Modifier,
    onCancelSave: () -> Unit = session::cancelSave,
    onReloadSource: () -> Unit = {},
    scanQrEnabled: Boolean = false,
    onScanQr: () -> Unit = {},
    writeNfcEnabled: Boolean = false,
    readNfcEnabled: Boolean = false,
    onReadNfc: () -> Unit = {}
) {
    val predictiveBackState = rememberPredictiveBackMotionState()
    val state = session.state
    val activeDraft = session.activeDraft
    val context = LocalContext.current
    val readyQrShare = session.qrShareStatus as? QrShareStatus.Ready
    val qrImageColors = scanSafeQrCodeColors(MaterialTheme.colorScheme)
    val qrImageExport = session.qrImageExport
    val qrImageDestination =
        rememberLauncherForActivityResult(
            QrImageDestinationContract()
        ) { uri ->
            val generation = qrImageExport.state.generation
            val format = qrImageExport.state.format
            val currentQrShare = session.qrShareStatus as? QrShareStatus.Ready
            if (uri == null) {
                qrImageExport.cancelDestination(generation)
                return@rememberLauncherForActivityResult
            }
            if (currentQrShare?.generation != generation) {
                qrImageExport.failDestination(generation)
                return@rememberLauncherForActivityResult
            }
            val applicationContext = context.applicationContext
            qrImageExport.saveSelectedDestination(generation, uri.toString()) {
                saveExpressiveQrCodeImage(
                    context = applicationContext,
                    destination = uri,
                    grid = currentQrShare.grid,
                    colors = qrImageColors,
                    format = format
                )
            }
        }

    /** Opens one user-selected lossless image destination for the displayed QR code. */
    fun requestQrImageDestination(status: QrShareStatus.Ready) {
        if (!qrImageExport.chooseDestination(status.generation)) {
            return
        }
        try {
            qrImageDestination.launch(
                QrImageDestinationRequest(session.title, qrImageExport.state.format)
            )
        } catch (_: Exception) {
            qrImageExport.failDestination(status.generation)
        }
    }

    val density = LocalDensity.current
    val windowSize = LocalWindowInfo.current.containerSize
    val isImeVisible = WindowInsets.isImeVisible
    val imeBottomInsetPixels = WindowInsets.ime.getBottom(density)
    val imeTargetBottomInsetPixels = WindowInsets.imeAnimationTarget.getBottom(density)
    val imeBackReservation = remember { ImeBackReservation() }
    var isOverflowExpanded by remember { mutableStateOf(false) }
    var activeSheet by retain(session) { mutableStateOf<DocumentToolSheet?>(null) }
    val readyPreview = session.markdownPreviewStatus as? MarkdownPreviewStatus.Ready
    val outlineEnabled = readyPreview != null && readyPreview.scrollRestoration == null &&
        session.canShowTextEditor && !session.isSaveBusy && !session.isShareBusy &&
        !session.isPrintBusy && !session.isQrShareBusy && !session.isNfcWriteBusy
    val sendEnabled = session.canStartShare || session.canStartPrint || session.canStartQrShare ||
        (writeNfcEnabled && session.canStartNfcWrite)
    LaunchedEffect(imeBottomInsetPixels == 0) {
        if (imeBottomInsetPixels == 0) {
            imeBackReservation.release()
        }
    }
    val useLandscapeImeLayout =
        usesImeFocusLayout(
            imeBottomInsetPixels = imeBottomInsetPixels,
            windowWidthPixels = windowSize.width,
            windowHeightPixels = windowSize.height
        )
    val useImeFocusLayout =
        activeDraft != null && useLandscapeImeLayout
    val useInlineFindStatus = session.isFindVisible && useLandscapeImeLayout
    val softwareKeyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val requestSend = {
        if (sendEnabled) {
            session.flushPendingEdit()
            focusManager.clearFocus(force = true)
            softwareKeyboardController?.hide()
            activeSheet = DocumentToolSheet.Send
        }
    }
    val accessibilityManager = LocalAccessibilityManager.current
    val currentOnClose by rememberUpdatedState(onClose)
    val saveSuccess = session.saveStatus as? SaveStatus.Succeeded
    val documentRemovalSuccess =
        session.documentRemovalStatus as? DocumentRemovalStatus.Succeeded
    val requestClose = {
        focusManager.clearFocus(force = true)
        softwareKeyboardController?.hide()
        when (session.requestClose()) {
            CloseRequestResult.CloseNow -> currentOnClose()

            CloseRequestResult.ConfirmationShown,
            CloseRequestResult.Queued -> Unit
        }
    }
    val requestSave = {
        if (session.showSaveFormatSelection()) {
            focusManager.clearFocus(force = true)
            softwareKeyboardController?.hide()
        }
    }
    val requestSaveCopy = {
        if (session.showSaveCopyFormatSelection()) {
            focusManager.clearFocus(force = true)
            softwareKeyboardController?.hide()
        }
    }
    val restartExplicitSave = {
        if (session.restartExplicitSave()) {
            focusManager.clearFocus(force = true)
            softwareKeyboardController?.hide()
        }
    }
    val requestFind = {
        if (session.showFind()) {
            focusManager.clearFocus(force = true)
            softwareKeyboardController?.hide()
        }
    }
    val requestGoToLine = {
        if (session.showGoToLineDialog()) {
            focusManager.clearFocus(force = true)
            softwareKeyboardController?.hide()
        }
    }
    val requestPreview = {
        if (session.showMarkdownPreview()) {
            focusManager.clearFocus(force = true)
            softwareKeyboardController?.hide()
        }
        Unit
    }
    val requestFileInfo = {
        if (session.showFileInfo()) {
            focusManager.clearFocus(force = true)
            softwareKeyboardController?.hide()
        }
        Unit
    }
    val requestShare = {
        if (session.requestShare()) {
            focusManager.clearFocus(force = true)
            softwareKeyboardController?.hide()
        }
    }
    val requestPrint = {
        if (session.showPrintSetup()) {
            focusManager.clearFocus(force = true)
            softwareKeyboardController?.hide()
        }
    }
    val requestQrShare = {
        if (session.requestQrShare()) {
            focusManager.clearFocus(force = true)
            softwareKeyboardController?.hide()
        }
    }
    val requestNfcWrite = {
        if (session.requestNfcWrite()) {
            focusManager.clearFocus(force = true)
            softwareKeyboardController?.hide()
        }
    }
    val requestTextEditor = {
        session.showTextEditor()
        focusManager.clearFocus(force = true)
        softwareKeyboardController?.hide()
        Unit
    }
    val closeFind = {
        session.closeFind()
        focusManager.clearFocus(force = true)
        softwareKeyboardController?.hide()
        Unit
    }
    val requestBack = {
        when {
            activeSheet != null -> activeSheet = null

            isOverflowExpanded -> isOverflowExpanded = false

            session.documentRemovalStatus is DocumentRemovalStatus.Confirming ->
                session.dismissDocumentRemovalConfirmation()

            session.isDiscardConfirmationVisible -> session.dismissDiscardConfirmation()

            session.sourceConflictResolution != null ->
                session.dismissSourceConflictConfirmation()

            session.saveStatus is SaveStatus.ChoosingFormat ->
                session.dismissSaveFormatSelection()

            session.isGoToLineDialogVisible -> session.dismissGoToLineDialog()

            readyQrShare != null -> session.dismissQrShare(readyQrShare.generation)

            session.isFileInfoVisible -> session.dismissFileInfo()

            session.isPrintSetupVisible -> session.dismissPrintSetup()

            session.isFindVisible -> closeFind()

            session.returnsToSourceOnBack -> requestTextEditor()

            session.isSourceReloading -> Unit

            session.isClosePending -> Unit

            session.saveStatus is SaveStatus.Queued -> session.cancelSave()

            session.isShareBusy -> session.cancelShare()

            session.isPrintBusy -> session.cancelPrint()

            session.isQrShareBusy -> session.cancelQrShare()

            session.isNfcWriteBusy -> session.cancelNfcWrite()

            session.hasPendingEditWindowAction -> {
                if (session.canCancelPendingEditWindowAction) {
                    session.cancelPendingEditWindowAction()
                }
            }

            else -> requestClose()
        }
    }
    val navigationContentDescription =
        when {
            session.isSourceReloading -> stringResource(R.string.navigation_reloading)

            session.isClosePending -> stringResource(R.string.navigation_closing)

            session.returnsToSourceOnBack -> stringResource(R.string.navigation_source)

            session.saveStatus is SaveStatus.Queued -> stringResource(
                R.string.navigation_cancel_save
            )

            session.isShareBusy -> stringResource(R.string.navigation_cancel_share)

            session.isPrintBusy -> stringResource(R.string.navigation_cancel_print)

            session.isQrShareBusy -> stringResource(R.string.navigation_cancel_qr)

            session.isNfcWriteBusy -> stringResource(R.string.navigation_cancel_nfc)

            session.canCancelPendingEditWindowAction -> stringResource(
                R.string.navigation_cancel_section
            )

            session.hasPendingEditWindowAction -> stringResource(R.string.navigation_section)

            else -> stringResource(R.string.navigation_back)
        }
    val navigationEnabled =
        !session.isSourceReloading &&
            !session.isClosePending &&
            (!session.hasPendingEditWindowAction || session.canCancelPendingEditWindowAction)
    val backClosesDocument =
        when {
            activeSheet != null -> false
            isOverflowExpanded -> false
            session.isFileInfoVisible -> false
            session.isPrintSetupVisible -> false
            session.isGoToLineDialogVisible -> false
            session.isDiscardConfirmationVisible -> false
            session.sourceConflictResolution != null -> false
            session.saveStatus is SaveStatus.ChoosingFormat -> false
            readyQrShare != null -> false
            session.isFindVisible -> false
            session.returnsToSourceOnBack -> false
            session.isSourceReloading -> false
            session.isClosePending -> false
            session.saveStatus is SaveStatus.Queued -> false
            session.isShareBusy -> false
            session.isPrintBusy -> false
            session.isQrShareBusy -> false
            session.isNfcWriteBusy -> false
            session.hasPendingEditWindowAction -> false
            else -> true
        }
    PredictiveBackMotionHandler(
        state = predictiveBackState,
        interceptBackAtStart = {
            imeBackReservation.tryClaim(
                isImeVisible = isImeVisible,
                imeBottomInsetPixels = imeBottomInsetPixels,
                imeTargetBottomInsetPixels = imeTargetBottomInsetPixels
            )
        },
        onInterceptedBackCancelled = imeBackReservation::release,
        onInterceptedBack = {
            session.checkpointPendingEdit()
            softwareKeyboardController?.hide()
        },
        onBack = requestBack
    )
    LaunchedEffect(
        session.isClosePending,
        session.canCloseSafely,
        session.closeResolutionVersion
    ) {
        when (session.resolvePendingClose()) {
            CloseRequestResult.CloseNow -> currentOnClose()

            CloseRequestResult.ConfirmationShown,
            CloseRequestResult.Queued -> Unit

            null -> Unit
        }
    }
    LaunchedEffect(session, documentRemovalSuccess?.action) {
        if (documentRemovalSuccess != null) {
            currentOnClose()
        }
    }
    LaunchedEffect(session) {
        session.openInitialEditor()
    }
    LaunchedEffect(session, saveSuccess?.request?.generation) {
        val currentSuccess = saveSuccess ?: return@LaunchedEffect
        val timeoutMillis =
            accessibilityManager?.calculateRecommendedTimeoutMillis(
                originalTimeoutMillis = SAVE_SUCCESS_TIMEOUT_MILLIS,
                containsIcons = false,
                containsText = true,
                containsControls = false
            ) ?: SAVE_SUCCESS_TIMEOUT_MILLIS
        delay(timeoutMillis)
        session.retireSaveSuccess(currentSuccess.request.generation)
    }
    Surface(
        modifier =
            modifier
                .fillMaxSize()
                .then(
                    if (
                        usesEditorPredictiveBackMotion(
                            closesDocumentTask = closesDocumentTask,
                            backClosesDocument = backClosesDocument
                        )
                    ) {
                        Modifier.predictiveBackMotion(predictiveBackState)
                    } else {
                        Modifier
                    }
                )
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
                    )
                    .then(
                        if (useImeFocusLayout) {
                            Modifier.windowInsetsPadding(
                                WindowInsets.safeDrawing.only(WindowInsetsSides.Top)
                            )
                        } else {
                            Modifier
                        }
                    )
                    .imePadding()
        ) {
            if (!useImeFocusLayout) {
                EditorChromeSurface {
                    if (session.isFindVisible) {
                        EditorFindChrome(
                            session = session,
                            useInlineStatus = useInlineFindStatus,
                            onClose = closeFind
                        )
                    } else {
                        EditorTopBar(
                            session = session,
                            actions = EditorNavigationActions(
                                onFind = requestFind,
                                onContents = { activeSheet = DocumentToolSheet.Contents },
                                onGoToLine = requestGoToLine,
                                onFileInfo = requestFileInfo,
                                onScanQr = onScanQr,
                                onReadNfc = onReadNfc
                            ),
                            scanQrEnabled = scanQrEnabled && session.canStartQrScan,
                            readNfcEnabled = readNfcEnabled && session.canStartNfcRead,
                            navigationContentDescription = navigationContentDescription,
                            navigationEnabled = navigationEnabled,
                            contentsEnabled = outlineEnabled,
                            onCancelSave = onCancelSave,
                            isOverflowExpanded = isOverflowExpanded,
                            onOverflowExpandedChange = { expanded ->
                                isOverflowExpanded = expanded
                            },
                            onClose = requestBack
                        )
                    }
                }
            }
            EditorBody(
                session = session,
                activeDraft = activeDraft,
                onSaveAsNewFile = requestSave,
                onRestartExplicitSave = restartExplicitSave,
                modifier = Modifier.weight(1f)
            )
            if (!useImeFocusLayout && !session.isFindVisible && state.metrics != null) {
                EditorActionBar(
                    session = session,
                    onPreview = requestPreview,
                    onEdit = requestTextEditor,
                    onSave = requestSave,
                    onShare = requestSend,
                    shareEnabled = sendEnabled
                )
            }
        }
    }
    val choosingFormat = session.saveStatus as? SaveStatus.ChoosingFormat
    LaunchedEffect(
        choosingFormat != null,
        session.isDiscardConfirmationVisible,
        session.isFileInfoVisible,
        session.isPrintSetupVisible,
        session.sourceConflictResolution
    ) {
        if (
            choosingFormat != null ||
            session.isDiscardConfirmationVisible ||
            session.isFileInfoVisible ||
            session.isPrintSetupVisible ||
            session.sourceConflictResolution != null
        ) {
            softwareKeyboardController?.hide()
        }
    }
    if (choosingFormat != null) {
        SaveFormatDialog(
            purpose = choosingFormat.purpose,
            replacesExistingSource = session.hasDocumentSource,
            isViewOnly = session.isViewOnly,
            selectionEnabled = session.canChooseSaveFormat,
            onSelect = session::selectSaveFormat,
            onDismiss = session::dismissSaveFormatSelection
        )
    }
    if (session.isGoToLineDialogVisible) {
        GoToLineDialog(session)
    }
    if (session.isFileInfoVisible) {
        FileInfoSheet(session = session, onDismiss = session::dismissFileInfo)
    }
    if (activeSheet == DocumentToolSheet.Send) {
        DocumentSendSheet(
            session = session,
            nfcAvailable = writeNfcEnabled,
            onShare = requestShare,
            onQrShare = requestQrShare,
            onNfcWrite = requestNfcWrite,
            onPrint = requestPrint,
            onSaveCopy = requestSaveCopy,
            onDismiss = { activeSheet = null }
        )
    }
    if (activeSheet == DocumentToolSheet.Contents && readyPreview != null) {
        DocumentOutlineSheet(
            title = session.title,
            entries = readyPreview.layout.outline,
            documentListState = session.markdownPreviewListState,
            onSelect = { entry ->
                activeSheet = null
                session.navigateToHeading(readyPreview.revision, entry)
            },
            onDismiss = { activeSheet = null }
        )
    }
    if (session.isPrintSetupVisible) {
        PrintSetupSheet(session)
    }
    session.sourceConflictResolution?.let { resolution ->
        SourceConflictConfirmationDialog(
            resolution = resolution,
            onConfirm = {
                when (resolution) {
                    SourceConflictResolution.Reload -> onReloadSource()
                    SourceConflictResolution.Overwrite -> session.confirmSourceOverwrite()
                }
            },
            onDismiss = session::dismissSourceConflictConfirmation
        )
    }
    if (readyQrShare != null) {
        QrShareDialog(
            status = readyQrShare,
            imageFormat = qrImageExport.state.format,
            onFormatChange = { qrImageExport.selectFormat(it) },
            imageSaveStatus =
                if (
                    qrImageExport.state.generation == readyQrShare.generation ||
                    qrImageExport.state.status.isActive()
                ) {
                    qrImageExport.state.status
                } else {
                    QrImageSaveStatus.Idle
                },
            onSaveImage = { requestQrImageDestination(readyQrShare) },
            onDismiss = { session.dismissQrShare(readyQrShare.generation) }
        )
    }
    (session.nfcWriteStatus as? NfcWriteStatus.Configuring)?.let { configuring ->
        NfcTagLabelDialog(
            status = configuring,
            onConfirm = session::confirmNfcWriteConfiguration,
            onDismiss = session::cancelNfcWrite
        )
    }
    if (session.isDiscardConfirmationVisible) {
        DocumentCloseConfirmation(session = session, onClose = onClose)
    }
}
