package dev.soupslurpr.beautyxt.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.retain.RetainObserver
import androidx.compose.runtime.setValue
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.document.DocumentFormat
import dev.soupslurpr.beautyxt.document.EditorDocumentSnapshot
import dev.soupslurpr.beautyxt.importing.client.DocumentImportException
import dev.soupslurpr.beautyxt.importing.client.SelectedDocumentMetadata
import dev.soupslurpr.beautyxt.sharing.IncomingDocumentShare
import dev.soupslurpr.beautyxt.sharing.incomingSharedTextTitle
import dev.soupslurpr.beautyxt.ui.editor.EditorSession
import dev.soupslurpr.beautyxt.ui.editor.SaveDestinationRequest
import dev.soupslurpr.beautyxt.ui.editor.WritableEditorDocumentSource
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private const val FIRST_OPEN_GENERATION = 1L
private val ABANDONED_SAVE_MESSAGE =
    UiText.Resource(R.string.operation_abandoned_save)
private val NEW_DOCUMENT_FAILURE_MESSAGE =
    UiText.Resource(R.string.operation_new_document_failure)
private val SOURCE_RELOAD_FAILURE_MESSAGE =
    UiText.Resource(R.string.operation_source_reload_failure)

/** Binds one system destination-picker result to its exact retained editor request. */
private data class ActiveSaveDestination(
    val editor: EditorSession,
    val request: SaveDestinationRequest
)

/** Describes bounded user-visible state for document selection and import. */
internal sealed interface OpenStatus {
    /** Indicates that no document selection or import is active. */
    data object Idle : OpenStatus

    /** Indicates that the system document picker owns the current selection flow. */
    data object Selecting : OpenStatus

    /** Indicates that one selected document is being imported. */
    data object Opening : OpenStatus

    /** Indicates that cancellation cleanup is still releasing import resources. */
    data object Cancelling : OpenStatus

    /** Contains one sanitized import failure for display. */
    data class Failed(val message: UiText) : OpenStatus
}

/** Owns transient application state across configuration changes in memory. */
@Stable
internal class BeauTyXTSession
internal constructor(
    sessionDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val createEmptyEditor: () -> EditorSession = EditorSession::createEmpty,
    private val createTransientTextEditor: (IncomingDocumentShare.Text) -> EditorSession =
        { share ->
            EditorSession.createTransientText(
                text = share.text,
                title = incomingSharedTextTitle(share.format)
            )
        }
) : AutoCloseable,
    RetainObserver {
    private val closeStarted = AtomicBoolean(false)
    private val sessionScope = CoroutineScope(SupervisorJob() + sessionDispatcher)
    private var nextOpenGeneration = FIRST_OPEN_GENERATION
    private var activeOpenGeneration: Long? = null
    private var activeOpenJob: Job? = null
    private var activeSaveDestination: ActiveSaveDestination? = null
    private var activeSaveDestinationJob: Job? = null
    private var activeSourceReloadEditor: EditorSession? = null
    private var activeSourceReloadJob: Job? = null
    private var editorClosingAfterExit: EditorSession? = null

    var editor by mutableStateOf<EditorSession?>(null)
        private set

    var openStatus by mutableStateOf<OpenStatus>(OpenStatus.Idle)
        private set

    var incomingShare by mutableStateOf<IncomingDocumentShare?>(null)
        private set

    val isOpenBusy: Boolean
        get() =
            openStatus == OpenStatus.Selecting ||
                openStatus == OpenStatus.Opening ||
                openStatus == OpenStatus.Cancelling

    /** Creates one new transient document when no open flow is active. */
    fun startNewDocument() {
        if (closeStarted.get() || editor != null || isOpenBusy) {
            return
        }
        val replacement =
            try {
                createEmptyEditor()
            } catch (_: Exception) {
                openStatus = OpenStatus.Failed(NEW_DOCUMENT_FAILURE_MESSAGE)
                return
            } catch (_: LinkageError) {
                openStatus = OpenStatus.Failed(NEW_DOCUMENT_FAILURE_MESSAGE)
                return
            }
        if (closeStarted.get()) {
            replacement.close()
            return
        }
        editor = replacement
        openStatus = OpenStatus.Idle
    }

    /** Retains the newest Android share offer without opening its source. */
    fun offerIncomingShare(share: IncomingDocumentShare) {
        if (!closeStarted.get()) {
            incomingShare = share
        }
    }

    /** Starts one direct Android view or edit request without an intermediate prompt. */
    fun openIncomingRequestedSource(createEditor: suspend () -> EditorSession): Boolean {
        val share = incomingShare as? IncomingDocumentShare.Source ?: return false
        if (
            !isDirectSourceRequest(share) ||
            closeStarted.get() ||
            editor != null ||
            isOpenBusy
        ) {
            return false
        }
        val opened = startDocumentOpen(createEditor)
        if (opened) {
            incomingShare = null
            return true
        }
        return false
    }

    /** Dismisses the current incoming offer without reading or storing its content. */
    fun dismissIncomingShare() {
        incomingShare = null
    }

    /** Creates a transient editor only after direct shared text is confirmed. */
    fun openIncomingText(): Boolean {
        val share = incomingShare as? IncomingDocumentShare.Text ?: return false
        if (closeStarted.get() || editor != null || isOpenBusy) {
            return false
        }
        val replacement =
            try {
                createTransientTextEditor(share)
            } catch (_: Exception) {
                incomingShare = null
                openStatus = OpenStatus.Failed(NEW_DOCUMENT_FAILURE_MESSAGE)
                return false
            } catch (_: LinkageError) {
                incomingShare = null
                openStatus = OpenStatus.Failed(NEW_DOCUMENT_FAILURE_MESSAGE)
                return false
            }
        if (closeStarted.get()) {
            replacement.close()
            return false
        }
        incomingShare = null
        editor = replacement
        openStatus = OpenStatus.Idle
        return true
    }

    /** Transfers one confirmed provider URI to the existing isolated import flow. */
    fun takeIncomingSource(): IncomingDocumentShare.Source? {
        val share = incomingShare as? IncomingDocumentShare.Source ?: return null
        if (closeStarted.get() || editor != null || isOpenBusy) {
            return null
        }
        incomingShare = null
        return share
    }

    /** Claims one system document-selection flow if the landing state is idle. */
    fun beginDocumentSelection(): Boolean {
        if (closeStarted.get() || editor != null || isOpenBusy) {
            return false
        }
        openStatus = OpenStatus.Selecting
        return true
    }

    /** Returns a cancelled system document-selection flow to the idle state. */
    fun cancelDocumentSelection() {
        if (openStatus == OpenStatus.Selecting) {
            openStatus = OpenStatus.Idle
        }
    }

    /** Reports a sanitized failure to launch or complete document selection. */
    fun failDocumentSelection() {
        if (openStatus == OpenStatus.Selecting) {
            openStatus = OpenStatus.Failed(UiText.Resource(R.string.operation_picker_failed))
        }
    }

    /** Starts creating one editor for a current or process-restored picker result. */
    fun openSelectedDocument(createEditor: suspend () -> EditorSession) {
        startDocumentOpen(createEditor)
    }

    /** Starts one document import only while this application session owns an idle slot. */
    private fun startDocumentOpen(createEditor: suspend () -> EditorSession): Boolean {
        if (
            closeStarted.get() ||
            editor != null ||
            (openStatus != OpenStatus.Selecting && openStatus != OpenStatus.Idle)
        ) {
            return false
        }
        val generation = nextOpenGeneration
        nextOpenGeneration = Math.incrementExact(nextOpenGeneration)
        activeOpenGeneration = generation
        openStatus = OpenStatus.Opening
        val openJob =
            sessionScope.launch(start = CoroutineStart.LAZY) {
                openEditor(generation = generation, createEditor = createEditor)
            }
        activeOpenJob = openJob
        openJob.invokeOnCompletion {
            finishOpen(generation)
        }
        openJob.start()
        return true
    }

    /** Requests cancellation while retaining the slot until cleanup completes. */
    fun cancelDocumentOpen() {
        if (openStatus != OpenStatus.Opening) {
            return
        }
        openStatus = OpenStatus.Cancelling
        activeOpenJob?.cancel()
    }

    /** Claims one exact editor request immediately before its picker launch. */
    fun claimSaveDestination(editor: EditorSession): SaveDestinationRequest? {
        if (
            closeStarted.get() ||
            this.editor !== editor ||
            activeSaveDestination != null
        ) {
            return null
        }
        val request = editor.claimSaveDestination() ?: return null
        activeSaveDestination = ActiveSaveDestination(editor = editor, request = request)
        return request
    }

    /** Returns whether one exact active request targets its editor's retained source. */
    fun isCurrentSourceDestination(request: SaveDestinationRequest, encodedUri: String): Boolean {
        require(encodedUri.isNotBlank()) { "candidate source URI must not be blank" }
        val destination = activeSaveDestination ?: return false
        return !closeStarted.get() &&
            destination.request == request &&
            editor === destination.editor &&
            destination.editor.matchesDocumentSourceUri(encodedUri)
    }

    /** Consumes one cancelled picker result only for its active format and generation. */
    fun cancelSaveDestination(format: DocumentFormat): Boolean =
        consumeSaveDestination(format) { destination ->
            destination.editor.cancelSaveDestination(destination.request)
        }

    /** Processes one picker result in the retained session scope. */
    fun launchSaveDestinationResult(
        format: DocumentFormat,
        processResult: suspend (SaveDestinationRequest) -> Unit
    ): Boolean {
        val destination = activeSaveDestination ?: return false
        if (
            closeStarted.get() ||
            destination.request.format != format ||
            activeSaveDestinationJob != null
        ) {
            return false
        }
        if (!destination.editor.beginSaveDestinationPreparation(destination.request)) {
            activeSaveDestination = null
            destination.editor.rejectSaveDestination(destination.request)
            return false
        }
        lateinit var resultJob: Job
        resultJob =
            sessionScope.launch(start = CoroutineStart.LAZY) {
                try {
                    processResult(destination.request)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    failSaveDestination(
                        format = format,
                        message = UiText.Resource(R.string.operation_destination_unavailable)
                    )
                }
            }
        activeSaveDestinationJob = resultJob
        resultJob.invokeOnCompletion {
            finishSaveDestinationResult(
                completedJob = resultJob,
                destination = destination
            )
        }
        resultJob.start()
        return true
    }

    /** Cancels one selected-destination preparation while retaining cleanup ownership. */
    fun cancelSaveDestinationResult(editor: EditorSession): Boolean {
        val destination = activeSaveDestination ?: return false
        val resultJob = activeSaveDestinationJob ?: return false
        if (
            closeStarted.get() ||
            destination.editor !== editor ||
            !destination.editor.beginSaveDestinationPreparationCancellation(destination.request)
        ) {
            return false
        }
        resultJob.cancel()
        return true
    }

    /** Consumes one selected destination without retaining its URI in application state. */
    fun saveSelectedDestination(
        format: DocumentFormat,
        destinationOwner: AutoCloseable,
        saveRevision: suspend (EditorDocumentSnapshot, Long) -> Unit
    ): Boolean = consumeSaveDestination(format) { destination ->
        destination.editor.saveSelectedDestination(
            request = destination.request,
            destinationOwner = destinationOwner,
            saveRevision = saveRevision
        )
    }

    /** Consumes one selected destination and transfers it as the editor source. */
    fun saveSelectedSource(
        format: DocumentFormat,
        documentSource: WritableEditorDocumentSource,
        sourceDisplayName: String? = null,
        sourceMetadata: SelectedDocumentMetadata? = null
    ): Boolean = consumeSaveDestination(format) { destination ->
        destination.editor.saveSelectedSource(
            request = destination.request,
            documentSource = documentSource,
            sourceDisplayName = sourceDisplayName,
            sourceMetadata = sourceMetadata
        )
    }

    /** Consumes one destination-open failure without retaining provider details. */
    fun failSaveDestination(format: DocumentFormat, message: UiText): Boolean =
        consumeSaveDestination(format) { destination ->
            destination.editor.failSaveDestination(
                request = destination.request,
                message = message
            )
        }

    /** Reports a save result whose transient document session no longer exists. */
    fun reportAbandonedSaveResult() {
        if (
            !closeStarted.get() &&
            editor == null &&
            !isOpenBusy
        ) {
            openStatus = OpenStatus.Failed(ABANDONED_SAVE_MESSAGE)
        }
    }

    /** Consumes one exact picker-launch failure for its retained request. */
    fun failSaveDestinationLaunch(request: SaveDestinationRequest): Boolean {
        val destination = activeSaveDestination ?: return false
        if (destination.request != request) {
            return false
        }
        activeSaveDestination = null
        if (closeStarted.get() || editor !== destination.editor) {
            destination.editor.rejectSaveDestination(destination.request)
            return false
        }
        return destination.editor.failSaveDestinationLaunch(request)
    }

    /** Reopens one conflicted source while preserving the current editor on failure. */
    fun reloadEditorSource(
        editor: EditorSession,
        createEditor: suspend (String) -> EditorSession
    ): Boolean {
        if (
            closeStarted.get() ||
            this.editor !== editor ||
            isOpenBusy ||
            activeSaveDestination != null ||
            activeSourceReloadJob != null
        ) {
            return false
        }
        val encodedUri = editor.beginSourceReload() ?: return false
        lateinit var reloadJob: Job
        reloadJob =
            sessionScope.launch(start = CoroutineStart.LAZY) {
                reloadEditorSource(
                    originalEditor = editor,
                    encodedUri = encodedUri,
                    createEditor = createEditor
                )
            }
        activeSourceReloadEditor = editor
        activeSourceReloadJob = reloadJob
        reloadJob.invokeOnCompletion {
            finishSourceReload(reloadJob, editor)
        }
        reloadJob.start()
        return true
    }

    /** Closes a safe editor, optionally keeping its content alive for the entry's exit. */
    fun closeEditor(afterExit: Boolean = false): Boolean {
        val currentEditor = editor ?: return false
        if (
            !currentEditor.canCloseSafely ||
            activeSaveDestination?.editor === currentEditor
        ) {
            return false
        }
        if (afterExit) {
            editorClosingAfterExit = currentEditor
        } else {
            editor = null
            currentEditor.close()
        }
        return true
    }

    /** Requests immediate synchronization of the current non-composing draft. */
    fun flushPendingEdit() {
        if (closeStarted.get() || editorClosingAfterExit != null) {
            return
        }
        sessionScope.launch {
            if (!closeStarted.get() && editorClosingAfterExit == null) {
                editor?.flushPendingEdit()
            }
        }
    }

    /** Checkpoints visible composing text when the application leaves the foreground. */
    fun checkpointPendingEdit() {
        if (!closeStarted.get() && editorClosingAfterExit == null) {
            editor?.checkpointPendingEdit()
        }
    }

    /** Schedules final cleanup on the session dispatcher exactly once. */
    override fun close() {
        if (!closeStarted.compareAndSet(false, true)) {
            return
        }
        sessionScope.launch {
            closeOwnedResources()
        }
    }

    /** Closes every transient resource and invalidates late import completions. */
    private suspend fun closeOwnedResources() {
        activeOpenGeneration = null
        val openJob = activeOpenJob
        activeOpenJob = null
        val currentEditor = editor
        editor = null
        activeSaveDestination = null
        val saveDestinationJob = activeSaveDestinationJob
        activeSaveDestinationJob = null
        activeSourceReloadEditor = null
        val sourceReloadJob = activeSourceReloadJob
        activeSourceReloadJob = null
        openStatus = OpenStatus.Idle
        incomingShare = null
        openJob?.cancel()
        saveDestinationJob?.cancel()
        sourceReloadJob?.cancel()
        if (currentEditor === editorClosingAfterExit) {
            // This editor already passed its close guard. In particular, discarding
            // must not turn into a fresh checkpoint or source save during retirement.
            currentEditor?.close()
        } else {
            currentEditor?.closeAfterCheckpoint()
        }
        editorClosingAfterExit = null
        sessionScope.cancel()
    }

    /** Accepts transfer into the lifecycle-aware retained-value store. */
    override fun onRetained() = Unit

    /** Accepts entry into an active composition without changing ownership. */
    override fun onEnteredComposition() = Unit

    /** Flushes pending text while retaining every resource across configuration exit. */
    override fun onExitedComposition() {
        flushPendingEdit()
    }

    /** Closes resources when the retained value permanently leaves its store. */
    override fun onRetired() {
        close()
    }

    /** Closes resources when an initial composition never claims the value. */
    override fun onUnused() {
        close()
    }

    /** Owns a candidate editor until current-generation publication succeeds. */
    private suspend fun openEditor(generation: Long, createEditor: suspend () -> EditorSession) {
        var candidate: EditorSession? = null
        try {
            candidate = createEditor()
            currentCoroutineContext().ensureActive()
            if (publishImportedEditor(generation = generation, candidate = candidate)) {
                candidate = null
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: DocumentImportException) {
            publishOpenFailure(generation, failure.failure.userMessage)
        } catch (_: Exception) {
            publishOpenFailure(generation, UiText.Resource(R.string.import_open_failed))
        } catch (_: LinkageError) {
            publishOpenFailure(generation, UiText.Resource(R.string.import_open_failed))
        } finally {
            candidate?.close()
        }
    }

    /** Publishes one candidate only while its import generation still owns the slot. */
    private fun publishImportedEditor(generation: Long, candidate: EditorSession): Boolean {
        if (!ownsOpenGeneration(generation)) {
            return false
        }
        check(editor == null) { "an editor already owns the application session" }
        editor = candidate
        openStatus = OpenStatus.Idle
        return true
    }

    /** Publishes one sanitized failure only for the current import generation. */
    private fun publishOpenFailure(generation: Long, message: UiText) {
        if (ownsOpenGeneration(generation) && openStatus == OpenStatus.Opening) {
            openStatus = OpenStatus.Failed(message)
        }
    }

    /** Releases one current import slot after every owned resource is closed. */
    private fun finishOpen(generation: Long) {
        if (activeOpenGeneration != generation) {
            return
        }
        activeOpenGeneration = null
        activeOpenJob = null
        if (openStatus == OpenStatus.Opening || openStatus == OpenStatus.Cancelling) {
            openStatus = OpenStatus.Idle
        }
    }

    /** Owns a reloaded candidate until it atomically replaces the conflicted editor. */
    private suspend fun reloadEditorSource(
        originalEditor: EditorSession,
        encodedUri: String,
        createEditor: suspend (String) -> EditorSession
    ) {
        var candidate: EditorSession? = null
        try {
            candidate = createEditor(encodedUri)
            currentCoroutineContext().ensureActive()
            if (
                !closeStarted.get() &&
                editor === originalEditor &&
                activeSourceReloadEditor === originalEditor &&
                originalEditor.isSourceReloading
            ) {
                editor = candidate
                candidate = null
                originalEditor.close()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: DocumentImportException) {
            originalEditor.failSourceReload(failure.failure.userMessage)
        } catch (_: Exception) {
            originalEditor.failSourceReload(SOURCE_RELOAD_FAILURE_MESSAGE)
        } catch (_: LinkageError) {
            originalEditor.failSourceReload(SOURCE_RELOAD_FAILURE_MESSAGE)
        } finally {
            candidate?.close()
        }
    }

    /** Releases one completed source-reload slot after all candidate cleanup. */
    private fun finishSourceReload(completedJob: Job, originalEditor: EditorSession) {
        if (activeSourceReloadJob !== completedJob) {
            return
        }
        activeSourceReloadJob = null
        activeSourceReloadEditor = null
        if (!closeStarted.get() && editor === originalEditor && originalEditor.isSourceReloading) {
            originalEditor.failSourceReload(SOURCE_RELOAD_FAILURE_MESSAGE)
        }
    }

    /** Releases one completed picker-result job and rejects an unused result. */
    private fun finishSaveDestinationResult(completedJob: Job, destination: ActiveSaveDestination) {
        if (activeSaveDestinationJob !== completedJob) {
            return
        }
        activeSaveDestinationJob = null
        if (activeSaveDestination !== destination) {
            return
        }
        activeSaveDestination = null
        if (
            destination.editor.completeSaveDestinationPreparationCancellation(
                destination.request
            )
        ) {
            return
        }
        destination.editor.failSaveDestination(
            request = destination.request,
            message = UiText.Resource(R.string.operation_destination_unusable)
        )
    }

    /** Routes one format-specific result to its exact live editor generation. */
    private fun consumeSaveDestination(
        format: DocumentFormat,
        consume: (ActiveSaveDestination) -> Boolean
    ): Boolean {
        val destination = activeSaveDestination ?: return false
        if (destination.request.format != format) {
            return false
        }
        activeSaveDestination = null
        if (closeStarted.get() || editor !== destination.editor) {
            destination.editor.rejectSaveDestination(destination.request)
            return false
        }
        val consumed = consume(destination)
        if (!consumed) {
            destination.editor.rejectSaveDestination(destination.request)
        }
        return consumed
    }

    /** Returns whether one import generation may still publish into this session. */
    private fun ownsOpenGeneration(generation: Long): Boolean =
        !closeStarted.get() && activeOpenGeneration == generation
}
