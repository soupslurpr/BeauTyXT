/* Coordinates the retained editor session, lifecycle, and document actions. */
package dev.soupslurpr.beautyxt.ui.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.then
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.soupslurpr.beautyxt.BeauTyXTApplication
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.ui.BindDocumentNavigationBack
import dev.soupslurpr.beautyxt.ui.LocalDocumentNavigationBack
import dev.soupslurpr.beautyxt.ui.PredictiveBackMotionHandler
import dev.soupslurpr.beautyxt.ui.designsystem.AppThemeDialog
import dev.soupslurpr.beautyxt.ui.designsystem.SingleChoiceButtons
import dev.soupslurpr.beautyxt.ui.designsystem.SingleChoiceOption
import dev.soupslurpr.beautyxt.ui.predictiveBackMotion
import dev.soupslurpr.beautyxt.ui.rememberPredictiveBackMotionState
import kotlinx.coroutines.delay

private const val SAVE_SUCCESS_TIMEOUT_MILLIS = 3_000L
private const val REPLACEMENT_UNDO_TIMEOUT_MILLIS = 4_000L

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
    val appearanceStore = (context.applicationContext as BeauTyXTApplication).textAppearance
    val storedAppearances by appearanceStore.state.collectAsStateWithLifecycle()
    val appearances = (storedAppearances as? TextAppearanceStoreState.Ready)?.preferences ?: DocumentAppearances()
    val appThemeStore = (context.applicationContext as BeauTyXTApplication).appTheme
    val appThemeState by appThemeStore.state.collectAsStateWithLifecycle()
    var appearanceGroup by retain(session) { mutableStateOf(AppearanceGroup.Source) }
    var appearancePanelState by retain(session) { mutableStateOf(TextAppearancePanelState()) }
    var isAppThemeVisible by retain(session) { mutableStateOf(false) }
    var shortcutsVisible by remember(session) { mutableStateOf(false) }
    if (shortcutsVisible) AlertDialog(onDismissRequest = { shortcutsVisible = false },
        title = { Text(stringResource(R.string.document_keyboard_shortcuts)) },
        text = { Text(stringResource(R.string.document_keyboard_shortcuts_body), Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton({ shortcutsVisible = false }) { Text(stringResource(R.string.action_close)) } })
    val readyQrShare = session.qrShareStatus as? QrShareStatus.Ready
    val qrImageColors = scanSafeQrCodeColors(MaterialTheme.colorScheme)
    val qrImageExport = session.qrImageExport
    val qrImageDestination =
        rememberLauncherForActivityResult(
            AuthorizedQrImageDestinationContract()
        ) { selected ->
            val generation = qrImageExport.state.generation
            val format = qrImageExport.state.format
            val currentQrShare = session.qrShareStatus as? QrShareStatus.Ready
            if (selected == null) {
                qrImageExport.cancelDestination(generation)
                return@rememberLauncherForActivityResult
            }
            val applicationContext = context.applicationContext
            if (
                dev.soupslurpr.beautyxt.mayHaveAmbientTreeAccessForUri(
                    applicationContext,
                    selected.uri
                ) && !selected.offersWrite
            ) {
                qrImageExport.failDestination(generation)
                return@rememberLauncherForActivityResult
            }
            if (currentQrShare?.generation != generation) {
                qrImageExport.failDestination(generation)
                return@rememberLauncherForActivityResult
            }
            val uri = selected.uri
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
    var findOptionsVisible by retain(session) { mutableStateOf(false) }
    var contentsOpenedAtFindReveal by retain(session) { mutableLongStateOf(0L) }
    val softwareKeyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val requestAppearance = {
        session.flushPendingEdit()
        // Keep the field from reclaiming the keyboard as the controls open.
        session.activeDraft?.updateEditorFocusIntent(isFocused = false, canClear = true)
        appearanceGroup = if (session.presentation == EditorPresentation.MarkdownPreview) {
            AppearanceGroup.Markdown
        } else AppearanceGroup.Source
        focusManager.clearFocus(force = true)
        softwareKeyboardController?.hide()
        appearancePanelState = TextAppearancePanelState()
        activeSheet = DocumentToolSheet.Appearance
    }
    val requestContents = {
        contentsOpenedAtFindReveal = session.findRevealRequest
        activeSheet = DocumentToolSheet.Contents
    }
    val readyPreview = session.markdownPreviewStatus as? MarkdownPreviewStatus.Ready
    val outlineEnabled = readyPreview != null && readyPreview.scrollRestoration?.reason != RestorationReason.Navigation &&
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
        activeDraft != null && !session.isFindVisible && useLandscapeImeLayout
    val useInlineFindStatus = session.isFindVisible && useLandscapeImeLayout
    val windowInfo = LocalWindowInfo.current
    val documentFocus = remember(session) { DocumentFocus() }
    val returnFromFindResults = {
        focusManager.clearFocus(force = true)
        softwareKeyboardController?.hide()
        session.focusFindDocument()
        session.updateFindResultsExpanded(false)
    }
    val editFindQuery = {
        focusManager.clearFocus(force = true)
        session.showFind()
        Unit
    }
    val requestFindOptions = {
        focusManager.clearFocus(force = true)
        softwareKeyboardController?.hide()
        findOptionsVisible = true
    }
    LaunchedEffect(session, session.findResultsPage, session.findInputFocus, session.findFocusRequest, windowInfo.isWindowFocused) {
        if (session.isFindVisible && !session.isFindResultsExpanded && session.findInputFocus == FindInputFocus.Document &&
            windowInfo.isWindowFocused) {
            // Menus own a separate window until their exit animation finishes.
            documentFocus.request { session.findInputFocus == FindInputFocus.Document && windowInfo.isWindowFocused }
        }
    }
    var restoreFocusAfterFind by remember(session) { mutableStateOf(false) }
    LaunchedEffect(session, session.isFindVisible) {
        val restore = restoreFocusAfterFind && !session.isFindVisible
        restoreFocusAfterFind = session.isFindVisible
        if (restore) documentFocus.request { !session.isFindVisible }
    }
    val requestSend = {
        if (sendEnabled) {
            focusManager.clearFocus(force = true)
            softwareKeyboardController?.hide()
            session.openDocumentExport(context)
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
    val restartExplicitSave = {
        if (session.restartExplicitSave()) {
            focusManager.clearFocus(force = true)
            softwareKeyboardController?.hide()
        }
    }
    fun openFind(replace: Boolean, keyboard: Boolean): Boolean {
        if (!session.showFind(showKeyboard = keyboard, openReplace = replace)) return false
        activeSheet = null
        return true
    }
    val findShortcut: (Boolean) -> Boolean = { replace -> openFind(replace, keyboard = false) }
    val requestFind = {
        if (openFind(replace = false, keyboard = true)) {
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
    val requestTextEditor = {
        session.showTextEditor()
        focusManager.clearFocus(force = true)
        softwareKeyboardController?.hide()
        Unit
    }
    val closeFind = {
        findOptionsVisible = false
        session.closeFind()
        focusManager.clearFocus(force = true)
        softwareKeyboardController?.hide()
        Unit
    }
    val requestBack = {
        when {
            session.excerptExport.visible -> session.excerptExport.dismiss()

            findOptionsVisible -> {
                findOptionsVisible = false
                session.focusFindDocument()
            }

            activeSheet != null -> activeSheet = null

            isOverflowExpanded -> isOverflowExpanded = false

            session.documentRemovalStatus is DocumentRemovalStatus.Confirming ->
                session.dismissDocumentRemovalConfirmation()

            session.isDiscardConfirmationVisible -> session.dismissDiscardConfirmation()

            session.sourceConflictResolution != null ->
                session.dismissSourceConflictConfirmation()

            session.sourceConflictComparison != null ->
                session.dismissSourceConflictComparison()

            session.saveStatus is SaveStatus.ChoosingFormat ->
                session.dismissSaveFormatSelection()

            session.isGoToLineDialogVisible -> session.dismissGoToLineDialog()

            readyQrShare != null -> session.dismissQrShare(readyQrShare.generation)

            session.isFileInfoVisible -> session.dismissFileInfo()

            session.isPrintSetupVisible -> session.dismissPrintSetup()

            session.isFindVisible && session.isFindResultsExpanded -> returnFromFindResults()

            session.isFindVisible -> closeFind()

            session.documentSelection != null || session.selectionMessage != null ->
                session.clearDocumentSelection()

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
            session.excerptExport.visible -> false
            findOptionsVisible -> false
            activeSheet != null -> false
            isOverflowExpanded -> false
            session.isFileInfoVisible -> false
            session.isPrintSetupVisible -> false
            session.isGoToLineDialogVisible -> false
            session.isDiscardConfirmationVisible -> false
            session.sourceConflictResolution != null -> false
            session.sourceConflictComparison != null -> false
            session.saveStatus is SaveStatus.ChoosingFormat -> false
            readyQrShare != null -> false
            session.isFindVisible -> false
            session.documentSelection != null || session.selectionMessage != null -> false
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
    BindDocumentNavigationBack(requestBack)
    val hasNavigationHost = LocalDocumentNavigationBack.current != null
    val navigationCanClose = hasNavigationHost && backClosesDocument &&
        session.canCloseSafely && !session.hasUnsavedChanges && !isImeVisible &&
        imeBottomInsetPixels == 0 && imeTargetBottomInsetPixels == 0
    PredictiveBackMotionHandler(
        state = predictiveBackState,
        enabled = !navigationCanClose,
        interceptBackAtStart = {
            session.documentSelection == null &&
                session.selectionMessage == null &&
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
    LaunchedEffect(session, session.replacementUndoNoticeRevision) {
        val revision = session.replacementUndoNoticeRevision ?: return@LaunchedEffect
        val timeout = accessibilityManager?.calculateRecommendedTimeoutMillis(
            originalTimeoutMillis = REPLACEMENT_UNDO_TIMEOUT_MILLIS,
            containsIcons = false, containsText = true, containsControls = false
        ) ?: REPLACEMENT_UNDO_TIMEOUT_MILLIS
        delay(timeout)
        session.retireReplacementUndoNotice(revision)
    }
    CompositionLocalProvider(LocalDocumentAppearances provides appearances) {
        Surface(
            modifier =
                modifier
                    .fillMaxSize()
                    .documentFindShortcuts(findShortcut)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) false else when {
                            event.isCtrlPressed && event.isAltPressed && event.key == Key.DirectionLeft -> {
                                session.returnToDocumentLocation(false); true
                            }
                            event.isCtrlPressed && event.isAltPressed && event.key == Key.DirectionRight -> {
                                session.returnToDocumentLocation(true); true
                            }
                            event.isCtrlPressed && event.key == Key.G -> { requestGoToLine(); true }
                            event.key == Key.F3 -> {
                                if (event.isShiftPressed) session.findPrevious() else session.findNext(); true
                            }
                            event.key == Key.Escape && activeSheet != null -> { activeSheet = null; true }
                            event.key == Key.Escape && session.isFindVisible -> { requestBack(); true }
                            event.key == Key.Escape &&
                                (session.documentSelection != null || session.selectionMessage != null) -> {
                                session.clearDocumentSelection()
                                true
                            }
                            else -> false
                        }
                    }
                    .then(
                        if (
                            !hasNavigationHost && usesEditorPredictiveBackMotion(
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
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val layoutDirection = LocalLayoutDirection.current
                val safeDrawing = WindowInsets.safeDrawing
                val horizontalInsets = safeDrawing.only(WindowInsetsSides.Horizontal)
                val usableWidth = with(density) {
                    (
                        constraints.maxWidth -
                            safeDrawing.getLeft(density, layoutDirection) -
                            safeDrawing.getRight(density, layoutDirection)
                        ).coerceAtLeast(0).toDp()
                }
                val availableTopBarHeight = with(density) {
                    (
                        constraints.maxHeight -
                            safeDrawing.getTop(density) -
                            safeDrawing.getBottom(density)
                        ).coerceAtLeast(0).toDp()
                }
                val wideTools = usableWidth >= 840.dp
                val fullFindResults = !wideTools && session.isFindVisible && session.isFindResultsExpanded
                val showChromeDivider by remember(session) {
                    derivedStateOf {
                        if (session.isFindVisible) {
                            true
                        } else if (session.presentation == EditorPresentation.MarkdownPreview) {
                            val preview = session.markdownPreviewStatus as? MarkdownPreviewStatus.Ready
                            preview != null &&
                                preview.document.blocks.isNotEmpty() &&
                                session.markdownPreviewListState.canScrollBackward
                        } else {
                            val draft = session.activeDraft
                            val currentState = session.state
                            when {
                                draft != null ->
                                    draft.edit.snapshot.hasPrevious || draft.scrollState.value > 0

                                !session.isViewOnly &&
                                    currentState.status == EditorDocumentStatus.Ready &&
                                    session.canRetryEditWindow -> false

                                currentState.blocks.isNotEmpty() ->
                                    (currentState.blocks.firstOrNull()?.block?.globalUtf16Start ?: 0L) > 0L ||
                                        session.viewportListState.canScrollBackward

                                else -> false
                            }
                        }
                    }
                }
                LaunchedEffect(session, session.findRevealRequest, session.findFocusRequest) {
                    if (session.consumeFindReveal()) {
                        focusManager.clearFocus(force = true)
                        softwareKeyboardController?.hide()
                        // A later tool choice wins over a match's deferred focus/scroll effect.
                        if (activeSheet == DocumentToolSheet.Contents && contentsOpenedAtFindReveal < session.findRevealRequest) activeSheet = null
                        if (!wideTools) {
                            session.collapseFindResultsForNavigation()
                        }
                        documentFocus.request { session.findInputFocus == FindInputFocus.Document }
                    }
                }
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
                    if (!useImeFocusLayout && !fullFindResults) {
                        EditorChromeSurface(showDivider = showChromeDivider) {
                            if (session.isFindVisible) {
                                EditorFindChrome(
                                    session = session,
                                    useInlineStatus = useInlineFindStatus,
                                    onClose = closeFind,
                                    onOptions = requestFindOptions
                                )
                            } else {
                                EditorTopBar(
                                    session = session,
                                    actions = EditorNavigationActions(
                                        onFind = requestFind,
                                        onContents = requestContents,
                                        onGoToLine = requestGoToLine,
                                        onFileInfo = requestFileInfo,
                                        onScanQr = onScanQr,
                                        onReadNfc = onReadNfc,
                                        onKeyboardShortcuts = { shortcutsVisible = true },
                                        onTextAppearance = requestAppearance,
                                        onAppTheme = { isAppThemeVisible = true }
                                    ),
                                    availableWidth = usableWidth,
                                    availableHeight = availableTopBarHeight,
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
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .windowInsetsPadding(horizontalInsets)
                    ) {
                        if (!session.isFindVisible) EditorLocationFeedback(session)
                        if (fullFindResults) {
                            EditorFindResults(session, wide = false, Modifier.weight(1f),
                                onReturn = returnFromFindResults, onEditSearch = editFindQuery)
                        } else Row(Modifier.weight(1f)) {
                            val bottomOverlayState = remember { EditorBottomOverlayState() }
                            val showsSelectionOverlay =
                                session.documentSelection != null || session.selectionMessage != null
                            val showsActionBarOverlay =
                                !showsSelectionOverlay &&
                                    !useImeFocusLayout &&
                                    !session.isFindVisible &&
                                    state.metrics != null
                            val hasBottomOverlay = showsSelectionOverlay || showsActionBarOverlay
                            LaunchedEffect(hasBottomOverlay) {
                                if (!hasBottomOverlay) bottomOverlayState.clear()
                            }
                            CompositionLocalProvider(LocalEditorBottomOverlayState provides bottomOverlayState) {
                                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                                    val documentPaneHeight = maxHeight
                                    EditorBody(
                                        session,
                                        activeDraft,
                                        requestSave,
                                        restartExplicitSave,
                                        Modifier.fillMaxSize(),
                                        documentFocus
                                    )
                                    if (hasBottomOverlay) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.BottomCenter)
                                                .fillMaxWidth()
                                                .onSizeChanged { size ->
                                                    bottomOverlayState.heightPx = size.height
                                                },
                                            contentAlignment = Alignment.BottomCenter
                                        ) {
                                            if (showsSelectionOverlay) {
                                                DocumentSelectionActions(
                                                    session = session,
                                                    availableHeight = documentPaneHeight
                                                )
                                            } else {
                                                EditorActionBar(
                                                    session = session,
                                                    availableHeight = documentPaneHeight,
                                                    onPreview = requestPreview,
                                                    onEdit = requestTextEditor,
                                                    onSave = requestSave,
                                                    onShare = requestSend,
                                                    shareEnabled = sendEnabled
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            val contentsPane = activeSheet == DocumentToolSheet.Contents && readyPreview != null
                            if (wideTools && activeSheet == DocumentToolSheet.Appearance) {
                                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                TextAppearancePanel(appearancePanelState, appearanceGroup, { appearanceGroup = it },
                                    appearanceStore, storedAppearances, { activeSheet = null },
                                    Modifier.width(400.dp).fillMaxSize().documentToolFocus(appearancePanelState))
                            } else if (wideTools && (contentsPane || session.isFindVisible && session.isFindResultsExpanded)) {
                                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Surface(
                                    modifier = Modifier.width(380.dp).fillMaxHeight(),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow
                                ) {
                                    Column(Modifier.fillMaxSize()) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            SingleChoiceButtons(
                                                choices = listOf(
                                                    SingleChoiceOption(
                                                        label = stringResource(R.string.outline_title),
                                                        selected = contentsPane,
                                                        onClick = requestContents,
                                                        enabled = readyPreview != null
                                                    ),
                                                    SingleChoiceOption(
                                                        label = stringResource(R.string.find_results),
                                                        selected = !contentsPane,
                                                        onClick = {
                                                            activeSheet = null
                                                            if (!session.isFindVisible) session.showFind(showKeyboard = false)
                                                            session.updateFindResultsExpanded(true)
                                                        },
                                                        enabled = true
                                                    )
                                                ),
                                                modifier = Modifier.weight(1f)
                                            )
                                            IconButton(
                                                onClick = {
                                                    activeSheet = null
                                                    session.updateFindResultsExpanded(false)
                                                }
                                            ) {
                                                Icon(
                                                    painterResource(R.drawable.ic_close),
                                                    contentDescription = stringResource(R.string.action_close)
                                                )
                                            }
                                        }
                                        if (contentsPane) {
                                            DocumentOutlineContent(
                                                title = session.title,
                                                entries = readyPreview.layout.outline,
                                                documentListState = session.markdownPreviewListState,
                                                onSelect = { session.navigateToHeading(readyPreview.revision, it) },
                                                modifier = Modifier.weight(1f).documentToolFocus(),
                                                isWidePane = true,
                                                showTitleHeading = false
                                            )
                                        } else {
                                            EditorFindResults(
                                                session = session,
                                                wide = true,
                                                modifier = Modifier.weight(1f),
                                                onReturn = returnFromFindResults,
                                                onEditSearch = editFindQuery
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (!wideTools && activeSheet == DocumentToolSheet.Appearance) {
                    TextAppearanceSheet(appearancePanelState, appearanceGroup, { appearanceGroup = it },
                        appearanceStore, storedAppearances, { activeSheet = null }, findShortcut)
                }
                if (!wideTools && activeSheet == DocumentToolSheet.Contents && readyPreview != null) {
                    DocumentOutlineSheet(
                        title = session.title,
                        entries = readyPreview.layout.outline,
                        documentListState = session.markdownPreviewListState,
                        onSelect = { entry ->
                            activeSheet = null
                            session.navigateToHeading(readyPreview.revision, entry)
                        },
                        onFind = findShortcut,
                        onDismiss = { activeSheet = null }
                    )
                }
                if (activeSheet == DocumentToolSheet.Appearance) {
                    TextAppearanceDialogs(appearancePanelState, appearanceStore, storedAppearances, findShortcut)
                }
            }
        }
    } // Document-only typography provider; export and print settings remain independent.
    val choosingFormat = session.saveStatus as? SaveStatus.ChoosingFormat
    LaunchedEffect(
        choosingFormat != null,
        session.isDiscardConfirmationVisible,
        session.isFileInfoVisible,
        session.isPrintSetupVisible,
        session.excerptExport.visible,
        session.sourceConflictResolution,
        session.sourceConflictComparison != null,
        isAppThemeVisible
    ) {
        if (
            choosingFormat != null ||
            session.isDiscardConfirmationVisible ||
            session.isFileInfoVisible ||
            session.isPrintSetupVisible ||
            session.excerptExport.visible ||
            session.sourceConflictResolution != null ||
            session.sourceConflictComparison != null ||
            isAppThemeVisible
        ) {
            softwareKeyboardController?.hide()
        }
    }
    if (isAppThemeVisible) {
        AppThemeDialog(
            store = appThemeStore,
            state = appThemeState,
            onDismiss = { isAppThemeVisible = false },
            modifier = Modifier.documentFindShortcuts { replace ->
                val opened = findShortcut(replace)
                if (opened) isAppThemeVisible = false
                opened
            }
        )
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
    if (session.isFindVisible && findOptionsVisible) {
        EditorFindOptions(session) {
            findOptionsVisible = false
            session.focusFindDocument()
        }
    }

    if (session.isPrintSetupVisible) {
        PrintSetupSheet(session)
    }
    ExcerptExportSheet(session.excerptExport, writeNfcEnabled, onShareSourceFile = requestShare)
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
    session.sourceConflictComparison?.let { comparisonState ->
        SourceConflictCompareSheet(
            state = comparisonState,
            hasNewerLocalEdits = session.hasNewerLocalEditsSinceComparison,
            canSaveAsNewFile = session.canStartSaveAs,
            canReload = session.canRequestSourceReload,
            canOverwriteReviewed =
                comparisonState is SourceConflictComparisonState.Ready &&
                    !session.hasNewerLocalEditsSinceComparison &&
                    session.canRequestSourceOverwrite,
            onRefresh = { session.requestSourceConflictComparison() },
            onSaveAsNewFile = {
                session.dismissSourceConflictComparison()
                requestSave()
            },
            onReload = { session.requestReloadFromComparison() },
            onOverwriteReviewed = { session.confirmReviewedSourceOverwrite() },
            onDismiss = session::dismissSourceConflictComparison
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

/** Hardware Find has the same owner in the document and its modal tool windows. */
internal fun Modifier.documentFindShortcuts(open: (replace: Boolean) -> Boolean): Modifier =
    onPreviewKeyEvent { event ->
        event.type == KeyEventType.KeyDown && event.isCtrlPressed && when (event.key) {
            Key.F -> open(false)
            Key.H -> open(true)
            else -> false
        }
    }
