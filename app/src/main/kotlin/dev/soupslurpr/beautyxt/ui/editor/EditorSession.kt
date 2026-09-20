package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.document.DocumentFormat
import dev.soupslurpr.beautyxt.document.DocumentRemovalAction
import dev.soupslurpr.beautyxt.document.DocumentRemovalCapabilities
import dev.soupslurpr.beautyxt.document.EditorDocument
import dev.soupslurpr.beautyxt.document.EditorDocumentSnapshot
import dev.soupslurpr.beautyxt.document.FindDirection
import dev.soupslurpr.beautyxt.document.FindMatch
import dev.soupslurpr.beautyxt.document.FindRequest
import dev.soupslurpr.beautyxt.document.MAX_FIND_CANDIDATE_UTF16_UNITS
import dev.soupslurpr.beautyxt.document.MAX_FIND_QUERY_UTF16_UNITS
import dev.soupslurpr.beautyxt.document.Utf16Range
import dev.soupslurpr.beautyxt.document.hasWellFormedUtf16
import dev.soupslurpr.beautyxt.document.isScalarBoundary
import dev.soupslurpr.beautyxt.document.resolveDocumentFormat
import dev.soupslurpr.beautyxt.exporting.ExportProtocol
import dev.soupslurpr.beautyxt.exporting.client.DocumentExportException
import dev.soupslurpr.beautyxt.exporting.client.DocumentExportFailure
import dev.soupslurpr.beautyxt.importing.client.SelectedDocumentMetadata
import dev.soupslurpr.beautyxt.importing.client.sanitizeSelectedDocumentDisplayName
import dev.soupslurpr.beautyxt.markdown.MarkdownPreviewDocument
import dev.soupslurpr.beautyxt.markdown.MarkdownProtocol
import dev.soupslurpr.beautyxt.markdown.client.MarkdownRenderException
import dev.soupslurpr.beautyxt.markdown.client.MarkdownRenderer
import dev.soupslurpr.beautyxt.printing.PrintContentMode
import dev.soupslurpr.beautyxt.printing.PrintDocumentContent
import dev.soupslurpr.beautyxt.printing.PrintSettings
import dev.soupslurpr.beautyxt.printing.PrintSetupDraft
import dev.soupslurpr.beautyxt.printing.defaultPrintSettings
import dev.soupslurpr.beautyxt.printing.defaultPrintSetupDraft
import dev.soupslurpr.beautyxt.printing.validatePrintSetup
import dev.soupslurpr.beautyxt.sharing.DocumentShareException
import dev.soupslurpr.beautyxt.sharing.MAX_SHARED_TEXT_UTF8_BYTES
import dev.soupslurpr.beautyxt.sharing.readSharedTextSnapshot
import dev.soupslurpr.beautyxt.transfer.TransferProtocol
import dev.soupslurpr.beautyxt.transfer.client.NfcTransferEnvelope
import dev.soupslurpr.beautyxt.transfer.client.NfcTransferProcessor
import dev.soupslurpr.beautyxt.transfer.client.QrCodeGrid
import dev.soupslurpr.beautyxt.transfer.client.QrTransferProcessor
import dev.soupslurpr.beautyxt.transfer.client.TransferException
import dev.soupslurpr.beautyxt.transfer.isValidNfcTagLabel
import dev.soupslurpr.beautyxt.ui.UiText
import dev.soupslurpr.beautyxt.ui.userMessage
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

private const val FIRST_SAVE_GENERATION = 1L
private const val FIRST_EDIT_WINDOW_ACTION_TOKEN = 1L
private const val FIRST_FIND_REQUEST_GENERATION = 1L
private const val FIRST_MARKDOWN_PREVIEW_GENERATION = 1L
private const val FIRST_SHARE_GENERATION = 1L
private const val FIRST_PRINT_GENERATION = 1L
private const val FIRST_QR_SHARE_GENERATION = 1L
private const val FIRST_NFC_WRITE_GENERATION = 1L
private const val FIRST_DISPLAY_LINE = 1L
private const val FIRST_VIEWPORT_ITEM_INDEX = 0
private const val MAX_GO_TO_LINE_INPUT_CHARACTERS = 19
private const val EDIT_SYNCHRONIZATION_DELAY_MILLIS = 350L
private const val FIND_DELAY_MILLIS = 250L
private const val NEW_DOCUMENT_TITLE = "New document"
private const val OPENED_DOCUMENT_TITLE = "Opened document"

/** Defines how one selected destination participates in the live document session. */
internal enum class SaveDestinationPurpose {
    /** Replaces a missing or unsafe retained source with the selected destination. */
    SourceReplacement,

    /** Writes one transient copy without changing the retained source. */
    Copy
}

/** Describes the immediate outcome of one editor close request. */
internal enum class CloseRequestResult {
    /** Indicates that the editor can close immediately. */
    CloseNow,

    /** Indicates that the retained discard confirmation is visible. */
    ConfirmationShown,

    /** Indicates that closing will resume after current work reaches a safe point. */
    Queued
}

/** Selects one generation-bound action requested from a bounded editing section. */
internal enum class EditWindowAction {
    /** Opens document text preceding the committed section. */
    Earlier,

    /** Opens document text following the committed section. */
    Later,

    /** Synchronizes the bounded field before opening retained Find. */
    OpenFind,

    /** Synchronizes the bounded field before opening one exact logical line. */
    GoToLine,

    /** Closes the bounded field before opening Markdown preview. */
    OpenPreview,

    /** Synchronizes the bounded field before showing live file facts. */
    OpenFileInfo,

    /** Synchronizes the field before applying the newest session history inverse. */
    Undo,

    /** Synchronizes the field before reapplying the newest undone session edit. */
    Redo,

    /** Commits a bounded bulk insertion without enlarging the Compose field. */
    BulkInsert
}

/** Selects the retained document presentation shown by the editor. */
internal enum class EditorPresentation {
    Text,
    MarkdownPreview
}

/** Describes the newest retained Markdown preview attempt. */
internal sealed interface MarkdownPreviewStatus {
    /** Indicates that no preview model is currently retained. */
    data object Idle : MarkdownPreviewStatus

    /** Indicates that one immutable revision is rendering privately. */
    data class Rendering(val revision: Long) : MarkdownPreviewStatus

    /** Contains one verified model and source restoration for an exact immutable revision. */
    data class Ready(
        val revision: Long,
        val document: MarkdownPreviewDocument,
        val layout: MarkdownPreviewLayout,
        val scrollRestoration: SemanticViewportAnchor?
    ) : MarkdownPreviewStatus {
        init {
            require(scrollRestoration == null || scrollRestoration.revision == revision) {
                "Markdown preview restoration revision must match its model"
            }
        }
    }

    /** Contains one sanitized retryable preview failure. */
    data class Failed(val message: UiText) : MarkdownPreviewStatus
}

/** Identifies the document edge crossed by one circular Find result. */
internal enum class FindWrap {
    /** Indicates that forward search resumed at the document beginning. */
    Beginning,

    /** Indicates that backward search resumed at the document end. */
    End
}

/** Describes the retained outcome of the newest Find request generation. */
internal sealed interface FindStatus {
    /** Indicates that the query has not produced a terminal result. */
    data object Idle : FindStatus

    /** Indicates that bounded native batches are being searched. */
    data object Searching : FindStatus

    /** Contains one published match and its optional circular boundary. */
    data class Match(val match: FindMatch, val wrappedAt: FindWrap?) : FindStatus

    /** Indicates that one complete circular traversal found no match. */
    data object NoMatches : FindStatus

    /** Contains one sanitized retryable Find failure. */
    data class Failed(val message: UiText) : FindStatus
}

/** Tracks whether one queued section action is waiting or running. */
private enum class EditWindowActionPhase {
    Waiting,
    Executing
}

/** Owns one exact draft generation's first accepted section action. */
private data class PendingEditWindowAction(
    val token: Long,
    val generation: Long,
    val action: EditWindowAction,
    val phase: EditWindowActionPhase,
    val restoreEditorFocusOnCancel: Boolean,
    val automaticTransition: AutomaticEditWindowTransition?,
    val lineNavigationTarget: Long?,
    val bulkEdit: EditorBulkEdit? = null,
    val readyToExecute: Boolean = true
) {
    init {
        require((action == EditWindowAction.BulkInsert) == (bulkEdit != null)) {
            "bulk input does not match its edit-window action"
        }
        require((action == EditWindowAction.GoToLine) == (lineNavigationTarget != null)) {
            "line navigation target does not match its edit-window action"
        }
        require(lineNavigationTarget == null || lineNavigationTarget >= 0L) {
            "line navigation target must be nonnegative"
        }
    }
}

/** Retains the anchor and active end of one global selection without reordering them. */
private data class DirectedUtf16Selection(val start: Long, val end: Long) {
    init {
        require(start >= 0L) { "directed selection start must be nonnegative" }
        require(end >= 0L) { "directed selection end must be nonnegative" }
    }

    /** Returns the same selection as an ordered native document range. */
    val orderedRange: Utf16Range
        get() = Utf16Range(start = minOf(start, end), end = maxOf(start, end))
}

/** Retains one visual scroll anchor for an automatic neighboring-window request. */
private data class AutomaticEditWindowTransition(
    val anchor: SemanticViewportAnchor,
    val selection: DirectedUtf16Selection,
    val preserveSelection: Boolean
)

/** Stores the source position and selection to restore from one exact preview revision. */
private data class MarkdownPreviewReturnTarget(
    val viewportAnchor: SemanticViewportAnchor,
    val selection: Utf16Range
) {
    init {
        require(selection.start <= selection.end) {
            "Markdown preview return selection must be ordered"
        }
    }
}

/** Stores one source replacement's editor selection across the system picker. */
private data class SourceReplacementEditTarget(val selection: Utf16Range)

/** Binds one retained edit target to its exact destination-picker generation. */
private data class PendingSourceReplacementEditTarget(
    val saveGeneration: Long,
    val target: SourceReplacementEditTarget
) {
    init {
        require(saveGeneration > 0L) { "source replacement generation must be positive" }
    }
}

/** Stores one directional candidate range and whether reaching it wrapped. */
private data class FindCandidatePhase(val range: Utf16Range, val wrappedAt: FindWrap?)

/** Describes one terminal traversal across a single Find candidate phase. */
private sealed interface FindPhaseResult {
    /** Contains one exact match returned by a bounded batch. */
    data class Matched(val match: FindMatch) : FindPhaseResult

    /** Indicates that this candidate phase contains no match. */
    data object Exhausted : FindPhaseResult

    /** Indicates that the current editor state no longer accepts the request. */
    data object Unavailable : FindPhaseResult

    /** Contains one sanitized bounded-search failure. */
    data class Failed(val message: UiText) : FindPhaseResult
}

/** Identifies one exact system destination-picker launch. */
internal data class SaveDestinationRequest(
    val generation: Long,
    val format: DocumentFormat,
    val suggestedName: String,
    val purpose: SaveDestinationPurpose
) {
    init {
        require(isSafeSaveDestinationName(suggestedName, format)) {
            "suggested save name is invalid"
        }
    }
}

/** Describes retained user-visible Save As state without owning a destination URI. */
internal sealed interface SaveStatus {
    /** Indicates that no Save As flow is active. */
    data object Idle : SaveStatus

    /** Retains the first explicit save while current text or source output settles. */
    data class Queued(val purpose: SaveDestinationPurpose, val draftGeneration: Long?) : SaveStatus

    /** Indicates that the user is choosing plain text or Markdown. */
    data class ChoosingFormat(val purpose: SaveDestinationPurpose) : SaveStatus

    /** Exposes one generation for exactly one system-picker launch. */
    data class DestinationReady(val request: SaveDestinationRequest) : SaveStatus

    /** Indicates that the system picker owns one destination selection. */
    data class SelectingDestination(val request: SaveDestinationRequest) : SaveStatus

    /** Indicates that one picker result is opening transient provider capabilities. */
    data class PreparingDestination(val request: SaveDestinationRequest) : SaveStatus

    /** Indicates that destination preparation cancellation is releasing capabilities. */
    data class CancellingDestinationPreparation(val request: SaveDestinationRequest) : SaveStatus

    /** Indicates that one selected destination is receiving a document revision. */
    data class Exporting(val request: SaveDestinationRequest) : SaveStatus

    /** Indicates that export cancellation is still releasing owned resources. */
    data class CancellingExport(val request: SaveDestinationRequest) : SaveStatus

    /** Contains one capability-free request and sanitized Save As failure for display. */
    data class Failed(val request: SaveDestinationRequest, val message: UiText) : SaveStatus

    /** Retains one user-requested cancellation after every selected-file resource is released. */
    data class Cancelled(val request: SaveDestinationRequest) : SaveStatus

    /** Indicates that one exact request wrote a complete revision. */
    data class Succeeded(val request: SaveDestinationRequest, val hasNewerChanges: Boolean) :
        SaveStatus
}

/** Contains one bounded payload ready for an explicit Android share grant. */
internal sealed interface DocumentSharePayload {
    val title: String
    val format: DocumentFormat

    /** References the retained provider source without creating a private copy. */
    data class Source(
        val encodedUri: String,
        override val title: String,
        override val format: DocumentFormat
    ) : DocumentSharePayload {
        init {
            require(encodedUri.isNotBlank()) { "shared source URI must not be blank" }
            require(title.isNotBlank()) { "shared source title must not be blank" }
        }
    }

    /** Contains one bounded transient document as an Android text extra. */
    data class Text(
        val text: String,
        override val title: String,
        override val format: DocumentFormat
    ) : DocumentSharePayload {
        init {
            require(title.isNotBlank()) { "shared text title must not be blank" }
        }
    }
}

/** Identifies one exact payload that the application may send to Android. */
internal data class DocumentShareRequest(val generation: Long, val payload: DocumentSharePayload) {
    init {
        require(generation > 0L) { "share generation must be positive" }
    }
}

/** Describes retained preparation and launch state for explicit document sharing. */
internal sealed interface ShareStatus {
    /** Indicates that no share is active. */
    data object Idle : ShareStatus

    /** Waits for the exact visible draft and retained source save to settle. */
    data class Queued(val generation: Long, val draftGeneration: Long?) : ShareStatus

    /** Streams one bounded transient revision into an anonymous in-memory payload. */
    data class Preparing(val generation: Long) : ShareStatus

    /** Waits for cancelled preparation to release every snapshot capability. */
    data class Cancelling(val generation: Long) : ShareStatus

    /** Contains one exact payload until the Android chooser launch is acknowledged. */
    data class Ready(val request: DocumentShareRequest) : ShareStatus

    /** Contains one sanitized retryable share failure. */
    data class Failed(val generation: Long, val message: UiText) : ShareStatus
}

/** Describes retained preparation and launch state for native Android printing. */
internal sealed interface PrintStatus {
    /** Indicates that no print request is active. */
    data object Idle : PrintStatus

    /** Waits for the exact visible draft to settle into the native document. */
    data class Queued(
        val generation: Long,
        val draftGeneration: Long?,
        val settings: PrintSettings
    ) : PrintStatus

    /** Captures one immutable native revision without materializing its text. */
    data class Preparing(val generation: Long, val settings: PrintSettings) : PrintStatus

    /** Waits for cancelled capture to release its snapshot capability. */
    data class Cancelling(val generation: Long) : PrintStatus

    /** Owns one exact revision until the Android print screen accepts it. */
    data class Ready(val request: DocumentPrintRequest) : PrintStatus

    /** Contains one sanitized retryable print preparation or launch failure. */
    data class Failed(val generation: Long, val message: UiText, val settings: PrintSettings) :
        PrintStatus {
        init {
            require(generation > 0L) { "print generation must be positive" }
        }
    }
}

/** Describes whether one exact document revision fits a bounded transfer. */
internal data class DocumentTransferCapacity(val textBytes: Long?, val maxTextBytes: Long) {
    init {
        require(textBytes == null || textBytes >= 0L) {
            "transfer text byte count must be nonnegative"
        }
        require(maxTextBytes > 0L) { "transfer text byte limit must be positive" }
    }

    /** Returns true or false for an exact revision and null while text is settling. */
    val fits: Boolean?
        get() = textBytes?.let { bytes -> bytes <= maxTextBytes }
}

/** Describes retained preparation and display state for one bounded QR share. */
internal sealed interface QrShareStatus {
    /** Indicates that no QR share is active. */
    data object Idle : QrShareStatus

    /** Waits for the exact visible draft and retained source save to settle. */
    data class Queued(val generation: Long, val draftGeneration: Long?) : QrShareStatus

    /** Encodes one exact revision in the private transfer process. */
    data class Preparing(val generation: Long) : QrShareStatus

    /** Waits for cancelled QR preparation to release its capabilities. */
    data class Cancelling(val generation: Long) : QrShareStatus

    /** Contains one bounded module grid until its disclosure is dismissed. */
    data class Ready(
        val generation: Long,
        val grid: QrCodeGrid,
        val textBytes: Long,
        val format: DocumentFormat
    ) : QrShareStatus {
        init {
            require(generation > 0L) { "QR share generation must be positive" }
            require(textBytes in 0L..TransferProtocol.MAX_QR_TEXT_BYTES) {
                "QR share text byte count exceeds its limit"
            }
        }
    }

    /** Contains one sanitized retryable QR preparation failure. */
    data class Failed(val generation: Long, val message: UiText) : QrShareStatus {
        init {
            require(generation > 0L) { "QR share generation must be positive" }
        }
    }
}

/** Describes retained preparation and tag-write state for one bounded NFC transfer. */
internal sealed interface NfcWriteStatus {
    /** Indicates that no NFC write is active. */
    data object Idle : NfcWriteStatus

    /** Waits for optional physical-tag label configuration. */
    data class Configuring(val generation: Long) : NfcWriteStatus {
        init {
            require(generation > 0L) { "NFC write generation must be positive" }
        }
    }

    /** Waits for the exact visible draft and retained source save to settle. */
    data class Queued(val generation: Long, val draftGeneration: Long?, val tagLabel: String?) :
        NfcWriteStatus {
        init {
            require(generation > 0L) { "NFC write generation must be positive" }
            require(isValidNfcTagLabel(tagLabel)) { "NFC tag label is invalid" }
        }
    }

    /** Encodes one exact revision in the private transfer process. */
    data class Preparing(val generation: Long, val tagLabel: String?) : NfcWriteStatus {
        init {
            require(generation > 0L) { "NFC write generation must be positive" }
            require(isValidNfcTagLabel(tagLabel)) { "NFC tag label is invalid" }
        }
    }

    /** Waits for cancelled NFC preparation to release its capabilities. */
    data class Cancelling(val generation: Long) : NfcWriteStatus

    /** Contains one bounded envelope until an explicit foreground tag write finishes. */
    data class Ready(
        val generation: Long,
        val envelope: NfcTransferEnvelope,
        val textBytes: Long,
        val format: DocumentFormat,
        val tagLabel: String?
    ) : NfcWriteStatus {
        init {
            require(generation > 0L) { "NFC write generation must be positive" }
            require(textBytes in 0L..TransferProtocol.MAX_NFC_TEXT_BYTES) {
                "NFC text byte count exceeds its limit"
            }
            require(isValidNfcTagLabel(tagLabel)) { "NFC tag label is invalid" }
        }
    }

    /** Contains one sanitized retryable NFC preparation failure. */
    data class Failed(val generation: Long, val message: UiText) : NfcWriteStatus {
        init {
            require(generation > 0L) { "NFC write generation must be positive" }
        }
    }

    /** Confirms that one exact prepared envelope was written and verified. */
    data class Succeeded(val generation: Long) : NfcWriteStatus {
        init {
            require(generation > 0L) { "NFC write generation must be positive" }
        }
    }
}

/** Describes retained source-autosave state without exposing source identity. */
internal sealed interface SourceSaveStatus {
    /** Indicates that this document has no writable source yet. */
    data object NoSource : SourceSaveStatus

    /** Indicates that the retained source contains the latest native revision. */
    data object Saved : SourceSaveStatus

    /** Indicates that the newest native revision is waiting to be saved. */
    data object Pending : SourceSaveStatus

    /** Indicates that one immutable revision is being written to the source. */
    data object Saving : SourceSaveStatus

    /** Indicates that the source is being reopened after explicit confirmation. */
    data object Reloading : SourceSaveStatus

    /** Contains one sanitized source-save failure requiring an explicit retry. */
    data class Failed(val message: UiText) : SourceSaveStatus

    /** Indicates that source bytes changed before BeauTyXT began writing. */
    data class Conflict(val message: UiText) : SourceSaveStatus

    /** Indicates that a source write began but could not be verified. */
    data class Uncertain(val message: UiText) : SourceSaveStatus
}

/** Identifies one destructive source-conflict choice awaiting confirmation. */
internal enum class SourceConflictResolution {
    /** Discards local edits and reopens the source's current contents. */
    Reload,

    /** Replaces the source's current contents with the local revision. */
    Overwrite
}

/** Owns one live document, bounded draft, and configuration-stable operations. */
@Stable
internal class EditorSession
internal constructor(
    title: String,
    val state: EditorDocumentState,
    documentSource: EditorDocumentSource? = null,
    sourceMetadata: SelectedDocumentMetadata? = null,
    sourceFormat: DocumentFormat = DocumentFormat.PlainText,
    private val shouldFocusInitialEditor: Boolean = false,
    initialPresentation: EditorPresentation = EditorPresentation.Text,
    val viewportListState: LazyListState = LazyListState(),
    val markdownPreviewListState: LazyListState = LazyListState(),
    operationDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val editSynchronizationDelay: suspend () -> Unit = {
        delay(EDIT_SYNCHRONIZATION_DELAY_MILLIS)
    },
    private val findDelay: suspend () -> Unit = {
        delay(FIND_DELAY_MILLIS)
    },
    private val markdownRenderer: MarkdownRenderer? = null,
    private val sharedTextReader: suspend (EditorDocumentSnapshot, Long) -> String =
        ::readSharedTextSnapshot,
    private val qrTransferProcessor: QrTransferProcessor? = null,
    private val nfcTransferProcessor: NfcTransferProcessor? = null
) : AutoCloseable {
    init {
        require(
            initialPresentation != EditorPresentation.MarkdownPreview || markdownRenderer != null
        ) {
            "initial Markdown preview requires a renderer"
        }
    }

    private val closeStarted = AtomicBoolean(false)
    private var shouldOpenInitialPreview = initialPresentation == EditorPresentation.MarkdownPreview
    private var previewReturnsToSource by mutableStateOf(true)
    private val operationScope =
        CoroutineScope(SupervisorJob() + operationDispatcher)
    val qrImageExport = QrImageExportController(operationScope, ::matchesDocumentSourceUri)
    private var nextSaveGeneration = FIRST_SAVE_GENERATION
    private var activeSaveGeneration: Long? = null
    private var activeSaveJob: Job? = null
    private var nextEditWindowActionToken = FIRST_EDIT_WINDOW_ACTION_TOKEN
    private var pendingEditWindowAction by mutableStateOf<PendingEditWindowAction?>(null)
    private var pendingEditWindowScrollRestoration: EditWindowScrollRestoration? = null
    private var pendingFindAnchor: SemanticViewportAnchor? = null
    private var pendingMarkdownPreviewReturnTarget: MarkdownPreviewReturnTarget? = null
    private val history = EditorHistory()
    private var historyVersion by mutableLongStateOf(0L)
    private var nextFindRequestGeneration = FIRST_FIND_REQUEST_GENERATION
    private var activeFindRequestGeneration: Long? = null
    private var findJob: Job? = null
    private var nextMarkdownPreviewGeneration = FIRST_MARKDOWN_PREVIEW_GENERATION
    private var activeMarkdownPreviewGeneration: Long? = null
    private var markdownPreviewJob: Job? = null
    private var markdownPreviewReturnTarget: MarkdownPreviewReturnTarget? = null
    private var sourceViewportNavigationPending by mutableStateOf(false)
    private var nextShareGeneration = FIRST_SHARE_GENERATION
    private var sharePreparationJob: Job? = null
    private var nextPrintGeneration = FIRST_PRINT_GENERATION
    private var printPreparationJob: Job? = null
    private var nextQrShareGeneration = FIRST_QR_SHARE_GENERATION
    private var qrSharePreparationJob: Job? = null
    private var nextNfcWriteGeneration = FIRST_NFC_WRITE_GENERATION
    private var nfcWritePreparationJob: Job? = null
    private var findOriginRevision = 0L
    private var findOriginUtf16Offset = 0L
    private var visibleViewportAnchor: SemanticViewportAnchor? = null
    private var lastFindDirection = FindDirection.Forward
    private var editObservationVersion = 0L
    private var editSynchronizationJob: Job? = null
    private var editSynchronizationDelayJob: Job? = null
    private var editSynchronizationFlushRequested = false
    private var latestObservedDraft: ActiveEditDraft? = null
    private var latestFieldValue: ActiveEditFieldValue? = null
    private var ownedDocumentSource = documentSource
    private var sourceSaveRequestVersion = 0L
    private var sourceSaveJob: Job? = null
    private var documentRemovalJob: Job? = null
    private var overwriteSourceOnNextSave = false
    private var unlockEditingAfterSourceSave = false
    private var queuedSourceReplacementEditTarget: SourceReplacementEditTarget? = null
    private var pendingSourceReplacementEditTarget: PendingSourceReplacementEditTarget? = null
    private var sourceSaveEditTarget: SourceReplacementEditTarget? = null

    var title by mutableStateOf(title)
        private set

    private var retainedSourceFormat by mutableStateOf(sourceFormat)

    /** Retains an incoming format hint only when the live filename/type are inconclusive. */
    val documentFormat: DocumentFormat
        get() = resolveDocumentFormat(title, sourceMetadata?.mimeType) ?: retainedSourceFormat

    var sourceMetadata by mutableStateOf(sourceMetadata)
        private set

    var isViewOnly by mutableStateOf(
        documentSource != null && documentSource !is WritableEditorDocumentSource
    )
        private set

    var activeDraft by mutableStateOf<ActiveEditDraft?>(null)
        private set

    var readOnlySourceScrollRestoration by mutableStateOf<SemanticViewportAnchor?>(null)
        private set

    var isFindVisible by mutableStateOf(false)
        private set

    var findFieldValue by mutableStateOf(TextFieldValue())
        private set

    var isFindCaseSensitive by mutableStateOf(false)
        private set

    var findStatus by mutableStateOf<FindStatus>(FindStatus.Idle)
        private set

    var findMatch by mutableStateOf<FindMatch?>(null)
        private set

    var presentation by mutableStateOf(EditorPresentation.Text)
        private set

    /** Returns whether Back should leave a nested preview for its source editor. */
    val returnsToSourceOnBack: Boolean
        get() = presentation == EditorPresentation.MarkdownPreview && previewReturnsToSource

    var markdownPreviewStatus by mutableStateOf<MarkdownPreviewStatus>(
        MarkdownPreviewStatus.Idle
    )
        private set

    var isGoToLineDialogVisible by mutableStateOf(false)
        private set

    var isFileInfoVisible by mutableStateOf(false)
        private set

    var documentRemovalStatus by mutableStateOf<DocumentRemovalStatus>(
        DocumentRemovalStatus.Idle
    )
        private set

    var goToLineInput by mutableStateOf("")
        private set

    var goToLineErrorMessage by mutableStateOf<UiText?>(null)
        private set

    var isDiscardConfirmationVisible by mutableStateOf(false)
        private set

    var isClosePending by mutableStateOf(false)
        private set

    var isSaveBeforeClosePending by mutableStateOf(false)
        private set

    var closeResolutionVersion by mutableLongStateOf(0L)
        private set

    var saveStatus by mutableStateOf<SaveStatus>(SaveStatus.Idle)
        private set

    var sourceSaveStatus by mutableStateOf<SourceSaveStatus>(
        if (documentSource == null) SourceSaveStatus.NoSource else SourceSaveStatus.Saved
    )
        private set

    var sourceConflictResolution by mutableStateOf<SourceConflictResolution?>(null)
        private set

    var shareStatus by mutableStateOf<ShareStatus>(ShareStatus.Idle)
        private set

    var printStatus by mutableStateOf<PrintStatus>(PrintStatus.Idle)
        private set

    var printSetupDraft by mutableStateOf<PrintSetupDraft?>(null)
        private set

    var qrShareStatus by mutableStateOf<QrShareStatus>(QrShareStatus.Idle)
        private set

    var nfcWriteStatus by mutableStateOf<NfcWriteStatus>(NfcWriteStatus.Idle)
        private set

    init {
        require(title.isNotBlank()) { "editor title must not be blank" }
        require(sourceMetadata == null || documentSource != null) {
            "source metadata requires a document source"
        }
    }

    val hasUnsavedChanges: Boolean
        get() =
            state.hasDocumentChanges ||
                activeDraft?.hasChanges == true ||
                (!isViewOnly && sourceSaveStatus.isTerminalFailure())

    val hasDocumentSource: Boolean
        get() = ownedDocumentSource != null

    /** Keeps received-content wording separate from save eligibility and close ownership. */
    val isUneditedReceivedContent: Boolean
        get() = sourceSaveStatus == SourceSaveStatus.NoSource &&
            state.isUneditedReceivedContent && activeDraft?.hasChanges != true

    val documentSourceUri: String?
        get() = ownedDocumentSource?.encodedShareUri()

    val hasPendingEditWindowAction: Boolean
        get() = pendingEditWindowAction != null

    /** Retains the IME contract during an in-place field handoff while input remains gated. */
    val preservesEditorInputSession: Boolean
        get() = pendingEditWindowAction?.let { pending ->
            pending.automaticTransition != null ||
                pending.action == EditWindowAction.Undo ||
                pending.action == EditWindowAction.Redo ||
                pending.action == EditWindowAction.BulkInsert
        } == true

    val canCancelPendingEditWindowAction: Boolean
        get() = pendingEditWindowAction?.let { pending ->
            pending.phase == EditWindowActionPhase.Waiting &&
                pending.action != EditWindowAction.BulkInsert
        } == true

    val pendingEditWindowActionMessageResource: Int?
        get() {
            val pending = pendingEditWindowAction ?: return null
            return when (pending.phase) {
                EditWindowActionPhase.Waiting -> R.string.editor_action_finishing_changes

                EditWindowActionPhase.Executing ->
                    when (pending.action) {
                        EditWindowAction.Earlier -> R.string.editor_action_earlier
                        EditWindowAction.Later -> R.string.editor_action_later
                        EditWindowAction.OpenFind -> R.string.editor_action_find
                        EditWindowAction.GoToLine -> R.string.editor_action_line
                        EditWindowAction.OpenPreview -> R.string.editor_action_preview
                        EditWindowAction.OpenFileInfo -> R.string.editor_action_file_info
                        EditWindowAction.Undo -> R.string.editor_action_undo
                        EditWindowAction.Redo -> R.string.editor_action_redo
                        EditWindowAction.BulkInsert -> R.string.editor_action_bulk_insert
                    }
            }
        }

    val canUndo: Boolean
        get() {
            historyVersion
            val draft = activeDraft ?: return false
            return canRequestHistoryAction(draft) &&
                (draft.hasChanges || (history.undoEntry != null))
        }

    val canRedo: Boolean
        get() {
            historyVersion
            val draft = activeDraft ?: return false
            return canRequestHistoryAction(draft) &&
                !draft.hasChanges &&
                (history.redoEntry != null)
        }

    val canRetryEditWindow: Boolean
        get() =
            !isViewOnly &&
                presentation == EditorPresentation.Text &&
                !closeStarted.get() &&
                state.canRetryEditWindow

    /** Returns whether the retained source has the exact encoded content URI. */
    fun matchesDocumentSourceUri(encodedUri: String): Boolean {
        require(encodedUri.isNotBlank()) { "candidate source URI must not be blank" }
        return !closeStarted.get() &&
            ownedDocumentSource?.matchesSourceUri(encodedUri) == true
    }

    /** Returns whether the next selected destination should become the live source. */
    val shouldSelectNewDocumentSource: Boolean
        get() = isViewOnly ||
            sourceSaveStatus == SourceSaveStatus.NoSource ||
            sourceSaveStatus.isTerminalFailure()

    val isSaveBusy: Boolean
        get() =
            saveStatus is SaveStatus.Queued ||
                saveStatus is SaveStatus.ChoosingFormat ||
                saveStatus is SaveStatus.DestinationReady ||
                saveStatus is SaveStatus.SelectingDestination ||
                saveStatus is SaveStatus.PreparingDestination ||
                saveStatus is SaveStatus.CancellingDestinationPreparation ||
                saveStatus is SaveStatus.Exporting ||
                saveStatus is SaveStatus.CancellingExport

    val isSourceSaveBusy: Boolean
        get() =
            sourceSaveStatus == SourceSaveStatus.Pending ||
                sourceSaveStatus == SourceSaveStatus.Saving ||
                sourceSaveStatus == SourceSaveStatus.Reloading

    val isSourceReloading: Boolean
        get() = sourceSaveStatus == SourceSaveStatus.Reloading

    val canAcceptEditorInput: Boolean
        get() =
            !isSourceReloading &&
                documentRemovalStatus !is DocumentRemovalStatus.Removing &&
                !closeStarted.get()

    /** Checks the live draft transaction before applying an individual user-input change. */
    fun canApplyEditorInput(draft: ActiveEditDraft): Boolean = activeDraft === draft &&
        canApplyActiveEditFieldInput(
            isClosePending = isClosePending,
            canAcceptEditorInput = canAcceptEditorInput,
            hasPendingEditWindowAction = hasPendingEditWindowAction,
            documentCanAcceptInput = state.canAcceptActiveDraftInput(draft.edit.generation)
        )

    val canRequestSourceReload: Boolean
        get() = canRequestSourceConflictResolution() &&
            !ownedDocumentSource?.encodedShareUri().isNullOrBlank()

    val canRequestSourceOverwrite: Boolean
        get() = canRequestSourceConflictResolution() &&
            ownedDocumentSource is ConflictRecoverableEditorDocumentSource &&
            canAcceptExplicitSaveRequest()

    val isShareBusy: Boolean
        get() =
            shareStatus is ShareStatus.Queued ||
                shareStatus is ShareStatus.Preparing ||
                shareStatus is ShareStatus.Cancelling ||
                shareStatus is ShareStatus.Ready

    val isPrintBusy: Boolean
        get() =
            printStatus is PrintStatus.Queued ||
                printStatus is PrintStatus.Preparing ||
                printStatus is PrintStatus.Cancelling ||
                printStatus is PrintStatus.Ready

    val isPrintSetupVisible: Boolean
        get() = printSetupDraft != null

    val canPrintFormattedMarkdown: Boolean
        get() =
            markdownRenderer != null &&
                state.metrics?.serializedByteLength?.let { bytes ->
                    bytes <= MarkdownProtocol.MAX_INPUT_BYTES
                } == true

    val isQrShareBusy: Boolean
        get() =
            qrShareStatus is QrShareStatus.Queued ||
                qrShareStatus is QrShareStatus.Preparing ||
                qrShareStatus is QrShareStatus.Cancelling ||
                qrShareStatus is QrShareStatus.Ready

    val isNfcWriteBusy: Boolean
        get() =
            nfcWriteStatus is NfcWriteStatus.Configuring ||
                nfcWriteStatus is NfcWriteStatus.Queued ||
                nfcWriteStatus is NfcWriteStatus.Preparing ||
                nfcWriteStatus is NfcWriteStatus.Cancelling ||
                nfcWriteStatus is NfcWriteStatus.Ready

    val qrShareCapacity: DocumentTransferCapacity
        get() = transferCapacity(TransferProtocol.MAX_QR_TEXT_BYTES)

    val nfcWriteCapacity: DocumentTransferCapacity
        get() = transferCapacity(TransferProtocol.MAX_NFC_TEXT_BYTES)

    val canCloseSafely: Boolean
        get() =
            state.canCloseSafely &&
                pendingEditWindowAction?.action != EditWindowAction.BulkInsert &&
                (activeDraft?.hasChanges != true || state.editorMessage != null) &&
                !isSaveBusy &&
                !isSourceSaveBusy &&
                !isShareBusy &&
                !isPrintBusy &&
                !isQrShareBusy &&
                !isNfcWriteBusy &&
                documentRemovalJob == null

    /** Returns why the live source cannot currently enter a destructive operation. */
    val documentRemovalUnavailableReason: UiText?
        get() =
            when {
                closeStarted.get() || ownedDocumentSource !is RemovableEditorDocumentSource ->
                    UiText.Resource(R.string.operation_remove_unsupported)

                sourceSaveStatus.isTerminalFailure() ->
                    UiText.Resource(R.string.operation_remove_resolve_save)

                activeDraft?.hasChanges == true || state.hasDocumentChanges ->
                    UiText.Resource(R.string.operation_remove_wait_save)

                isSourceSaveBusy || sourceSaveJob != null ->
                    UiText.Resource(R.string.operation_remove_wait_save)

                state.status != EditorDocumentStatus.Ready ||
                    state.lineViewportStatus != LineViewportStatus.Idle ->
                    UiText.Resource(R.string.operation_wait_operation)

                isSaveBusy ||
                    isShareBusy ||
                    isPrintBusy ||
                    isQrShareBusy ||
                    isNfcWriteBusy ||
                    pendingEditWindowAction != null ->
                    UiText.Resource(R.string.operation_wait_operation)

                else -> null
            }

    val canStartSaveAs: Boolean
        get() =
            !closeStarted.get() &&
                !isClosePending &&
                !isFindVisible &&
                !isShareBusy &&
                !isPrintBusy &&
                !isQrShareBusy &&
                !isNfcWriteBusy &&
                pendingEditWindowAction == null &&
                !(isViewOnly && ownedDocumentSource != null && isSourceSaveBusy) &&
                (
                    saveStatus == SaveStatus.Idle ||
                        saveStatus is SaveStatus.Failed ||
                        saveStatus is SaveStatus.Cancelled ||
                        saveStatus is SaveStatus.Succeeded
                    ) &&
                canAcceptExplicitSaveRequest()

    val canSaveBeforeClose: Boolean
        get() =
            isDiscardConfirmationVisible &&
                canCloseSafely &&
                hasUnsavedChanges &&
                canStartSaveAs

    val canStartShare: Boolean
        get() =
            !closeStarted.get() &&
                !isClosePending &&
                !isFindVisible &&
                !isGoToLineDialogVisible &&
                !isDiscardConfirmationVisible &&
                !isSaveBusy &&
                !isShareBusy &&
                !isPrintBusy &&
                !isQrShareBusy &&
                !isNfcWriteBusy &&
                pendingEditWindowAction == null &&
                (shareStatus == ShareStatus.Idle || shareStatus is ShareStatus.Failed) &&
                canAcceptExplicitSaveRequest()

    val canStartPrint: Boolean
        get() =
            printSetupDraft == null &&
                canQueuePrint()

    private fun canQueuePrint(): Boolean = !closeStarted.get() &&
        !isClosePending &&
        !isFindVisible &&
        !isGoToLineDialogVisible &&
        !isDiscardConfirmationVisible &&
        !isSaveBusy &&
        !isShareBusy &&
        !isPrintBusy &&
        !isQrShareBusy &&
        !isNfcWriteBusy &&
        pendingEditWindowAction == null &&
        (printStatus == PrintStatus.Idle || printStatus is PrintStatus.Failed) &&
        canAcceptExplicitSaveRequest()

    val canStartQrShare: Boolean
        get() =
            qrTransferProcessor != null &&
                !closeStarted.get() &&
                !isClosePending &&
                !isFindVisible &&
                !isGoToLineDialogVisible &&
                !isDiscardConfirmationVisible &&
                !isSaveBusy &&
                !isShareBusy &&
                !isPrintBusy &&
                !isQrShareBusy &&
                !isNfcWriteBusy &&
                pendingEditWindowAction == null &&
                (qrShareStatus == QrShareStatus.Idle || qrShareStatus is QrShareStatus.Failed) &&
                qrShareCapacity.fits != false &&
                canAcceptExplicitSaveRequest()

    val canStartNfcWrite: Boolean
        get() =
            nfcTransferProcessor != null &&
                !closeStarted.get() &&
                !isClosePending &&
                !isFindVisible &&
                !isGoToLineDialogVisible &&
                !isDiscardConfirmationVisible &&
                !isSaveBusy &&
                !isShareBusy &&
                !isPrintBusy &&
                !isQrShareBusy &&
                !isNfcWriteBusy &&
                pendingEditWindowAction == null &&
                (
                    nfcWriteStatus == NfcWriteStatus.Idle ||
                        nfcWriteStatus is NfcWriteStatus.Failed ||
                        nfcWriteStatus is NfcWriteStatus.Succeeded
                    ) &&
                nfcWriteCapacity.fits != false &&
                canAcceptExplicitSaveRequest()

    val canStartQrScan: Boolean
        get() =
            !closeStarted.get() &&
                !isSourceReloading &&
                !isClosePending &&
                !isFindVisible &&
                !isGoToLineDialogVisible &&
                !isDiscardConfirmationVisible &&
                !isSaveBusy &&
                !isShareBusy &&
                !isPrintBusy &&
                !isQrShareBusy &&
                !isNfcWriteBusy &&
                pendingEditWindowAction == null

    val canStartNfcRead: Boolean
        get() = canStartQrScan

    val canNavigateToLine: Boolean
        get() {
            val hasExpectedTextSurface =
                (activeDraft == null && state.activeEdit == null) ||
                    (activeDraft != null && state.activeEdit != null)
            return !closeStarted.get() &&
                !isSourceReloading &&
                !isClosePending &&
                !isFindVisible &&
                presentation == EditorPresentation.Text &&
                !isDiscardConfirmationVisible &&
                !isSaveBusy &&
                !isShareBusy &&
                !isPrintBusy &&
                !isQrShareBusy &&
                !isNfcWriteBusy &&
                !isSourceSaveBusy &&
                pendingEditWindowAction == null &&
                hasExpectedTextSurface &&
                state.status == EditorDocumentStatus.Ready &&
                state.lineViewportStatus == LineViewportStatus.Idle &&
                state.metrics?.lineCount?.let { lineCount ->
                    lineCount >= FIRST_DISPLAY_LINE
                } == true
        }

    val canShowFind: Boolean
        get() =
            !closeStarted.get() &&
                !isSourceReloading &&
                !isClosePending &&
                !isFindVisible &&
                presentation == EditorPresentation.Text &&
                !isGoToLineDialogVisible &&
                !isDiscardConfirmationVisible &&
                !isSaveBusy &&
                !isShareBusy &&
                !isPrintBusy &&
                !isQrShareBusy &&
                !isNfcWriteBusy &&
                pendingEditWindowAction == null &&
                state.status == EditorDocumentStatus.Ready &&
                state.lineViewportStatus == LineViewportStatus.Idle &&
                state.metrics != null

    val canShowMarkdownPreview: Boolean
        get() =
            markdownRenderer != null &&
                !closeStarted.get() &&
                !isSourceReloading &&
                !isClosePending &&
                presentation == EditorPresentation.Text &&
                !isFindVisible &&
                !isGoToLineDialogVisible &&
                !isDiscardConfirmationVisible &&
                !isSaveBusy &&
                !isShareBusy &&
                !isPrintBusy &&
                !isQrShareBusy &&
                !isNfcWriteBusy &&
                pendingEditWindowAction == null &&
                state.status == EditorDocumentStatus.Ready &&
                state.lineViewportStatus == LineViewportStatus.Idle &&
                state.metrics != null

    val canShowTextEditor: Boolean
        get() =
            !closeStarted.get() &&
                !isSourceReloading &&
                presentation == EditorPresentation.MarkdownPreview &&
                !sourceViewportNavigationPending &&
                !isClosePending

    val canShowFileInfo: Boolean
        get() =
            !closeStarted.get() &&
                !isSourceReloading &&
                !isClosePending &&
                !isFileInfoVisible &&
                !isFindVisible &&
                !isGoToLineDialogVisible &&
                !isDiscardConfirmationVisible &&
                pendingEditWindowAction == null &&
                state.status == EditorDocumentStatus.Ready &&
                state.lineViewportStatus == LineViewportStatus.Idle &&
                state.metrics != null

    val canNavigateFind: Boolean
        get() =
            isFindVisible &&
                findFieldValue.text.isNotEmpty() &&
                findStatus != FindStatus.Searching &&
                !isClosePending &&
                !isSaveBusy &&
                state.status == EditorDocumentStatus.Ready &&
                state.lineViewportStatus == LineViewportStatus.Idle &&
                !state.hasActiveDraftChanges

    val canChooseSaveFormat: Boolean
        get() =
            !isClosePending &&
                saveStatus is SaveStatus.ChoosingFormat &&
                sourceSaveJob == null &&
                isDocumentReadyForSave()

    val saveUnavailableReason: UiText?
        get() = when {
            state.status == EditorDocumentStatus.Stale ->
                UiText.Resource(R.string.operation_reload_to_continue)

            pendingEditWindowAction != null ->
                UiText.Resource(R.string.operation_save_wait_section)

            state.metrics?.serializedByteLength?.let { bytes ->
                bytes > ExportProtocol.MAX_BYTE_LIMIT
            } == true && activeDraft?.hasChanges != true ->
                UiText.Resource(R.string.operation_save_document_too_large)

            !isSaveBusy &&
                state.status != EditorDocumentStatus.Ready &&
                state.status != EditorDocumentStatus.ApplyingEdit ->
                UiText.Resource(R.string.operation_wait_operation_short)

            else -> null
        }

    /** Opens the requested presentation without focusing a source field before reading. */
    fun openInitialEditor() {
        if (isFindVisible || state.status != EditorDocumentStatus.Idle) {
            return
        }
        launchOperation(
            operation = { state ->
                state.loadInitialViewport()
                if (!isViewOnly && !shouldOpenInitialPreview) {
                    state.activateDocumentAt(Utf16Range(start = 0L, end = 0L))
                }
            },
            requestDraftFocus =
                shouldFocusInitialEditor && !isViewOnly && !shouldOpenInitialPreview,
            onCompletion = ::openInitialPreviewIfReady
        )
    }

    /** Consumes the reading default once, retaining it across an initial viewport retry. */
    private fun openInitialPreviewIfReady() {
        if (shouldOpenInitialPreview && openMarkdownPreview(returnToSource = false)) {
            shouldOpenInitialPreview = false
        }
    }

    /** Starts loading the next bounded viewport page. */
    fun loadNextViewport() {
        if (isFindVisible || presentation != EditorPresentation.Text) {
            return
        }
        launchOperation(EditorDocumentState::loadNextViewport)
    }

    /** Starts loading the immediately preceding bounded viewport page. */
    fun loadPreviousViewport() {
        if (isFindVisible || presentation != EditorPresentation.Text) {
            return
        }
        launchOperation(EditorDocumentState::loadPreviousViewport)
    }

    /** Starts retrying the most recent bounded viewport request. */
    fun retryViewport() {
        if (isFindVisible || presentation != EditorPresentation.Text) {
            return
        }
        if (
            state.lineViewportStatus is LineViewportStatus.Failed &&
            activeDraft != null &&
            state.canRetryEditWindow
        ) {
            launchOperation(
                operation = { state ->
                    state.retryEditWindow()
                    val activeEdit = state.activeEdit
                    if (
                        state.lineViewportStatus == LineViewportStatus.Idle &&
                        activeEdit != null
                    ) {
                        pendingEditWindowScrollRestoration =
                            EditWindowScrollRestoration(
                                anchor =
                                    SemanticViewportAnchor(
                                        revision = activeEdit.snapshot.metrics.revision,
                                        sourceUtf16Offset = activeEdit.snapshot.selection.start,
                                        viewportTopOffsetPixels = 0
                                    )
                            )
                    }
                },
                requestDraftFocus = true
            )
            return
        }
        val failureToken = state.viewportFailureToken ?: return
        launchOperation(
            operation = { state ->
                if (
                    state.retryViewport(failureToken) ==
                    ViewportRetryResult.LineViewportPublished
                ) {
                    viewportListState.requestScrollToItem(FIRST_VIEWPORT_ITEM_INDEX)
                }
            },
            onCompletion = ::openInitialPreviewIfReady
        )
    }

    /** Records one actually visible revision-bound source point for navigation. */
    fun observeVisibleViewportAnchor(
        revision: Long,
        utf16Offset: Long,
        viewportTopOffsetPixels: Int = 0
    ) {
        require(revision >= 0L) { "visible viewport revision must be nonnegative" }
        require(utf16Offset >= 0L) { "visible viewport offset must be nonnegative" }
        val currentMetrics = state.metrics ?: return
        if (
            !closeStarted.get() &&
            revision == currentMetrics.revision &&
            utf16Offset <= currentMetrics.utf16Length
        ) {
            visibleViewportAnchor =
                SemanticViewportAnchor(
                    revision = revision,
                    sourceUtf16Offset = utf16Offset,
                    viewportTopOffsetPixels = viewportTopOffsetPixels
                )
            if (isFindVisible && findOriginRevision != revision) {
                invalidateFindRequest()
                findOriginRevision = revision
                findOriginUtf16Offset = utf16Offset
                findMatch = null
                findStatus = FindStatus.Idle
                if (findFieldValue.text.isNotEmpty()) {
                    launchFind(direction = FindDirection.Forward, delayed = false)
                }
            }
        }
    }

    /** Records one actually visible Markdown source point for returning to the editor. */
    fun observeMarkdownPreviewViewportAnchor(
        revision: Long,
        utf16Offset: Long,
        viewportTopOffsetPixels: Int = 0
    ) {
        require(revision >= 0L) { "Markdown preview revision must be nonnegative" }
        require(utf16Offset >= 0L) { "Markdown preview offset must be nonnegative" }
        val currentMetrics = state.metrics ?: return
        val ready = markdownPreviewStatus as? MarkdownPreviewStatus.Ready ?: return
        if (
            !closeStarted.get() &&
            presentation == EditorPresentation.MarkdownPreview &&
            revision == ready.revision &&
            revision == currentMetrics.revision &&
            utf16Offset <= currentMetrics.utf16Length
        ) {
            val anchor =
                SemanticViewportAnchor(
                    revision = revision,
                    sourceUtf16Offset = utf16Offset,
                    viewportTopOffsetPixels = viewportTopOffsetPixels
                )
            markdownPreviewReturnTarget =
                MarkdownPreviewReturnTarget(
                    viewportAnchor = anchor,
                    selection = Utf16Range(start = utf16Offset, end = utf16Offset)
                )
        }
    }

    /** Navigates to a heading in the current revision using its semantic source position. */
    fun navigateToHeading(revision: Long, entry: DocumentOutlineEntry): Boolean {
        require(revision >= 0L) { "preview revision must be nonnegative" }
        val ready = markdownPreviewStatus as? MarkdownPreviewStatus.Ready ?: return false
        if (
            closeStarted.get() || presentation != EditorPresentation.MarkdownPreview ||
            ready.revision != revision || state.metrics?.revision != revision ||
            entry !in ready.layout.outline
        ) {
            return false
        }
        markdownPreviewStatus = ready.copy(
            scrollRestoration = SemanticViewportAnchor(revision, entry.sourceOffset, 0)
        )
        observeMarkdownPreviewViewportAnchor(revision, entry.sourceOffset)
        return true
    }

    /** Consumes one exact entry anchor after Compose restores the preview viewport. */
    fun consumeMarkdownPreviewScrollRestoration(
        revision: Long,
        restoration: SemanticViewportAnchor
    ): Boolean {
        require(revision >= 0L) { "Markdown preview revision must be nonnegative" }
        require(restoration.revision == revision) {
            "Markdown preview restoration revision must match its model"
        }
        val ready = markdownPreviewStatus as? MarkdownPreviewStatus.Ready ?: return false
        if (
            closeStarted.get() ||
            presentation != EditorPresentation.MarkdownPreview ||
            ready.revision != revision ||
            ready.scrollRestoration != restoration
        ) {
            return false
        }
        markdownPreviewStatus = ready.copy(scrollRestoration = null)
        return true
    }

    /** Opens retained Find now or after synchronizing one active edit field. */
    fun showFind(): Boolean {
        if (!canShowFind) {
            return false
        }
        val draft = activeDraft
        if (draft != null) {
            return requestEditWindowAction(draft = draft, action = EditWindowAction.OpenFind)
        }
        return openFind(resolveVisibleViewportAnchor().sourceUtf16Offset)
    }

    /** Opens Markdown preview now or after synchronizing one active edit field. */
    fun showMarkdownPreview(): Boolean {
        if (!canShowMarkdownPreview) {
            return false
        }
        val draft = activeDraft
        if (draft != null) {
            return requestEditWindowAction(
                draft = draft,
                action = EditWindowAction.OpenPreview
            )
        }
        return openMarkdownPreview()
    }

    /** Shows live file facts now or after synchronizing one active edit field. */
    fun showFileInfo(): Boolean {
        if (!canShowFileInfo) {
            return false
        }
        val draft = activeDraft
        if (draft != null) {
            return requestEditWindowAction(
                draft = draft,
                action = EditWindowAction.OpenFileInfo
            )
        }
        openFileInfo()
        return true
    }

    /** Dismisses live file facts without changing the document or cursor. */
    fun dismissFileInfo() {
        if (
            documentRemovalStatus is DocumentRemovalStatus.Removing ||
            documentRemovalStatus is DocumentRemovalStatus.Succeeded
        ) {
            return
        }
        val capabilityJob =
            documentRemovalJob.takeIf {
                documentRemovalStatus == DocumentRemovalStatus.Checking
            }
        if (capabilityJob != null) {
            documentRemovalJob = null
            capabilityJob.cancel()
        }
        documentRemovalStatus = DocumentRemovalStatus.Idle
        isFileInfoVisible = false
    }

    /** Refreshes live provider removal flags while File info is visible. */
    fun refreshDocumentRemovalCapabilities(): Boolean {
        if (
            closeStarted.get() ||
            !isFileInfoVisible ||
            documentRemovalJob != null ||
            documentRemovalStatus is DocumentRemovalStatus.Confirming ||
            documentRemovalStatus is DocumentRemovalStatus.Removing ||
            documentRemovalStatus is DocumentRemovalStatus.Succeeded
        ) {
            return false
        }
        val documentSource = ownedDocumentSource as? RemovableEditorDocumentSource
        if (documentSource == null) {
            documentRemovalStatus =
                DocumentRemovalStatus.Ready(DocumentRemovalCapabilities.None)
            return true
        }
        documentRemovalStatus = DocumentRemovalStatus.Checking
        lateinit var capabilityJob: Job
        capabilityJob =
            operationScope.launch(start = CoroutineStart.LAZY) {
                queryDocumentRemovalCapabilities(documentSource)
            }
        documentRemovalJob = capabilityJob
        capabilityJob.invokeOnCompletion {
            finishDocumentRemovalJob(capabilityJob)
        }
        capabilityJob.start()
        return true
    }

    /** Shows confirmation for one currently advertised provider operation. */
    fun requestDocumentRemovalConfirmation(action: DocumentRemovalAction): Boolean {
        val ready = documentRemovalStatus as? DocumentRemovalStatus.Ready ?: return false
        if (
            !isFileInfoVisible ||
            documentRemovalJob != null ||
            !ready.capabilities.supports(action) ||
            documentRemovalUnavailableReason != null
        ) {
            return false
        }
        documentRemovalStatus =
            DocumentRemovalStatus.Confirming(
                action = action,
                capabilities = ready.capabilities
            )
        return true
    }

    /** Dismisses one destructive confirmation without changing provider state. */
    fun dismissDocumentRemovalConfirmation() {
        val confirming =
            documentRemovalStatus as? DocumentRemovalStatus.Confirming ?: return
        documentRemovalStatus = DocumentRemovalStatus.Ready(confirming.capabilities)
    }

    /** Starts one confirmed provider operation only from a clean source revision. */
    fun confirmDocumentRemoval(): Boolean {
        val confirming =
            documentRemovalStatus as? DocumentRemovalStatus.Confirming ?: return false
        val documentSource = ownedDocumentSource as? RemovableEditorDocumentSource
            ?: return false
        if (
            documentRemovalJob != null ||
            documentRemovalUnavailableReason != null
        ) {
            documentRemovalStatus = DocumentRemovalStatus.Ready(confirming.capabilities)
            return false
        }
        documentRemovalStatus = DocumentRemovalStatus.Removing(confirming.action)
        lateinit var removalJob: Job
        removalJob =
            operationScope.launch(start = CoroutineStart.LAZY) {
                removeDocumentSource(
                    documentSource = documentSource,
                    action = confirming.action
                )
            }
        documentRemovalJob = removalJob
        removalJob.invokeOnCompletion {
            finishDocumentRemovalJob(removalJob)
        }
        removalJob.start()
        return true
    }

    /** Opens File info with no removal capability retained from an earlier visit. */
    private fun openFileInfo() {
        documentRemovalStatus = DocumentRemovalStatus.Idle
        isFileInfoVisible = true
    }

    /** Publishes one bounded live provider capability query. */
    private suspend fun queryDocumentRemovalCapabilities(
        documentSource: RemovableEditorDocumentSource
    ) {
        try {
            val capabilities = documentSource.queryRemovalCapabilities()
            if (
                canPublishDocumentRemoval(documentSource) &&
                documentRemovalStatus == DocumentRemovalStatus.Checking
            ) {
                documentRemovalStatus = DocumentRemovalStatus.Ready(capabilities)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            publishDocumentRemovalFailure(
                documentSource = documentSource,
                message = DOCUMENT_REMOVAL_CAPABILITY_FAILURE_MESSAGE
            )
        } catch (_: LinkageError) {
            publishDocumentRemovalFailure(
                documentSource = documentSource,
                message = DOCUMENT_REMOVAL_CAPABILITY_FAILURE_MESSAGE
            )
        }
    }

    /** Publishes one confirmed provider removal outcome without retaining a trash URI. */
    private suspend fun removeDocumentSource(
        documentSource: RemovableEditorDocumentSource,
        action: DocumentRemovalAction
    ) {
        try {
            documentSource.remove(action)
            if (
                canPublishDocumentRemoval(documentSource) &&
                documentRemovalStatus == DocumentRemovalStatus.Removing(action)
            ) {
                documentRemovalStatus = DocumentRemovalStatus.Succeeded(action)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            publishDocumentRemovalFailure(
                documentSource = documentSource,
                message = documentRemovalFailureMessage(action)
            )
        } catch (_: LinkageError) {
            publishDocumentRemovalFailure(
                documentSource = documentSource,
                message = documentRemovalFailureMessage(action)
            )
        }
    }

    /** Returns from preview and restores one bounded source editor at its retained position. */
    fun showTextEditor() {
        if (!canShowTextEditor) {
            return
        }
        val returnTarget = markdownPreviewReturnTarget
        if (isViewOnly) {
            val anchor = returnTarget?.viewportAnchor
                ?.takeIf { it.revision == state.metrics?.revision }
                ?: resolveVisibleViewportAnchor()
            sourceViewportNavigationPending = true
            launchOperation(operation = { state ->
                if (state.navigateToSourceOffset(anchor.revision, anchor.sourceUtf16Offset)) {
                    readOnlySourceScrollRestoration = anchor
                    viewportListState.requestScrollToItem(0)
                    markdownPreviewReturnTarget = null
                    presentation = EditorPresentation.Text
                    invalidateMarkdownPreview()
                } else if (!closeStarted.get()) {
                    markdownPreviewStatus = MarkdownPreviewStatus.Failed(
                        UiText.Resource(R.string.operation_source_position_failed)
                    )
                }
            }, onCompletion = { sourceViewportNavigationPending = false })
            return
        }
        markdownPreviewReturnTarget = null
        presentation = EditorPresentation.Text
        invalidateMarkdownPreview()
        if (!isViewOnly) {
            launchOperation(
                operation = { state ->
                    val target =
                        if (
                            returnTarget != null &&
                            returnTarget.viewportAnchor.revision == state.metrics?.revision
                        ) {
                            returnTarget
                        } else {
                            MarkdownPreviewReturnTarget(
                                viewportAnchor =
                                    SemanticViewportAnchor(
                                        revision = state.metrics?.revision ?: 0L,
                                        sourceUtf16Offset = 0L,
                                        viewportTopOffsetPixels = 0
                                    ),
                                selection = Utf16Range(start = 0L, end = 0L)
                            )
                        }
                    state.activateDocumentAt(target.selection)
                    val activeEdit = state.activeEdit
                    pendingEditWindowScrollRestoration =
                        if (
                            activeEdit != null &&
                            target.viewportAnchor.revision ==
                            activeEdit.snapshot.metrics.revision &&
                            target.viewportAnchor.sourceUtf16Offset in
                            activeEdit.snapshot.range.start..activeEdit.snapshot.range.end
                        ) {
                            EditWindowScrollRestoration(target.viewportAnchor)
                        } else {
                            null
                        }
                },
                requestDraftFocus = true
            )
        }
    }

    /** Publishes one sanitized provider-removal failure for the live source only. */
    private fun publishDocumentRemovalFailure(
        documentSource: RemovableEditorDocumentSource,
        message: UiText
    ) {
        if (canPublishDocumentRemoval(documentSource)) {
            documentRemovalStatus = DocumentRemovalStatus.Failed(message)
        }
    }

    /** Releases one exact capability or destructive operation slot. */
    private fun finishDocumentRemovalJob(completedJob: Job) {
        if (documentRemovalJob === completedJob) {
            documentRemovalJob = null
            signalPendingCloseResolution()
        }
    }

    /** Returns whether one provider result still belongs to the visible live source. */
    private fun canPublishDocumentRemoval(documentSource: RemovableEditorDocumentSource): Boolean =
        !closeStarted.get() &&
            isFileInfoVisible &&
            ownedDocumentSource === documentSource

    /** Consumes only the exact read-only restoration applied by the current source layout. */
    fun consumeReadOnlySourceScrollRestoration(anchor: SemanticViewportAnchor): Boolean {
        if (readOnlySourceScrollRestoration !== anchor) return false
        readOnlySourceScrollRestoration = null
        return true
    }

    /** Returns from preview to one exact source caret on the same immutable revision. */
    fun showTextEditorAtSource(
        revision: Long,
        utf16Offset: Long,
        viewportTopOffsetPixels: Int = 0
    ): Boolean {
        require(revision >= 0L) { "Markdown preview revision must be nonnegative" }
        require(utf16Offset >= 0L) { "Markdown source offset must be nonnegative" }
        val currentMetrics = state.metrics ?: return false
        val ready = markdownPreviewStatus as? MarkdownPreviewStatus.Ready ?: return false
        if (
            isViewOnly ||
            !canShowTextEditor ||
            ready.revision != revision ||
            currentMetrics.revision != revision ||
            utf16Offset > currentMetrics.utf16Length
        ) {
            return false
        }
        markdownPreviewReturnTarget =
            MarkdownPreviewReturnTarget(
                viewportAnchor =
                    SemanticViewportAnchor(
                        revision = revision,
                        sourceUtf16Offset = utf16Offset,
                        viewportTopOffsetPixels = viewportTopOffsetPixels
                    ),
                selection = Utf16Range(start = utf16Offset, end = utf16Offset)
            )
        showTextEditor()
        return true
    }

    /** Retries the newest failed Markdown preview against the current revision. */
    fun retryMarkdownPreview(): Boolean {
        if (
            presentation != EditorPresentation.MarkdownPreview ||
            markdownPreviewStatus !is MarkdownPreviewStatus.Failed ||
            closeStarted.get()
        ) {
            return false
        }
        launchMarkdownPreview()
        return true
    }

    /** Closes Find and forgets every retained query and match value. */
    fun closeFind() {
        if (!isFindVisible) {
            return
        }
        val currentMetrics = state.metrics
        val editorOffset =
            findMatch
                ?.takeIf { match -> match.start.revision == currentMetrics?.revision }
                ?.range
                ?.start
                ?: findOriginUtf16Offset
        invalidateFindRequest()
        isFindVisible = false
        findFieldValue = TextFieldValue()
        isFindCaseSensitive = false
        findStatus = FindStatus.Idle
        findMatch = null
        findOriginRevision = 0L
        findOriginUtf16Offset = 0L
        lastFindDirection = FindDirection.Forward
        val draft = activeDraft
        if (draft != null) {
            draft.requestEditorFocusRestoration()
        } else if (
            !isViewOnly &&
            presentation == EditorPresentation.Text &&
            state.activeEdit == null &&
            state.status == EditorDocumentStatus.Ready &&
            currentMetrics != null
        ) {
            val boundedOffset = editorOffset.coerceIn(0L, currentMetrics.utf16Length)
            launchOperation(
                operation = { state ->
                    state.activateDocumentAt(
                        Utf16Range(start = boundedOffset, end = boundedOffset)
                    )
                }
            )
        }
    }

    /** Updates the retained single-line substring query and schedules a fresh search. */
    fun updateFindFieldValue(candidate: TextFieldValue): Boolean {
        if (
            !isFindVisible ||
            candidate.text.length > MAX_FIND_QUERY_UTF16_UNITS ||
            '\r' in candidate.text ||
            '\n' in candidate.text ||
            !candidate.text.hasWellFormedUtf16()
        ) {
            return false
        }
        val previous = findFieldValue
        findFieldValue = candidate
        if (candidate.text == previous.text) {
            return true
        }
        invalidateFindRequest()
        findStatus = FindStatus.Idle
        findMatch = null
        if (candidate.text.isNotEmpty()) {
            launchFind(direction = FindDirection.Forward, delayed = true)
        }
        return true
    }

    /** Updates case sensitivity and reruns the current committed query immediately. */
    fun updateFindCaseSensitivity(matchCase: Boolean): Boolean {
        if (!isFindVisible) {
            return false
        }
        if (isFindCaseSensitive == matchCase) {
            return true
        }
        invalidateFindRequest()
        isFindCaseSensitive = matchCase
        findStatus = FindStatus.Idle
        findMatch = null
        if (findFieldValue.text.isNotEmpty()) {
            launchFind(direction = FindDirection.Forward, delayed = false)
        }
        return true
    }

    /** Searches immediately for the next literal substring match. */
    fun findNext(): Boolean = launchFind(direction = FindDirection.Forward, delayed = false)

    /** Searches immediately for the previous literal substring match. */
    fun findPrevious(): Boolean = launchFind(direction = FindDirection.Backward, delayed = false)

    /** Retries the latest failed Find direction without changing its query. */
    fun retryFind(): Boolean {
        if (findStatus !is FindStatus.Failed) {
            return false
        }
        return launchFind(direction = lastFindDirection, delayed = false)
    }

    /** Starts reloading after a stale native revision invalidates cached ranges. */
    fun reloadStaleViewport() {
        clearSessionHistory(revision = null)
        launchOperation(EditorDocumentState::reloadStaleViewport)
    }

    /** Opens retained line navigation at one currently visible logical line. */
    fun showGoToLineDialog(currentVisibleLogicalLine: Long): Boolean {
        require(currentVisibleLogicalLine >= 0L) {
            "current visible logical line must be nonnegative"
        }
        val lineCount = state.metrics?.lineCount ?: return false
        if (
            !canNavigateToLine ||
            currentVisibleLogicalLine >= lineCount
        ) {
            return false
        }
        goToLineInput = Math.addExact(currentVisibleLogicalLine, FIRST_DISPLAY_LINE).toString()
        goToLineErrorMessage = null
        isGoToLineDialogVisible = true
        return true
    }

    /** Opens retained line navigation at the current bounded editor position. */
    fun showGoToLineDialog(): Boolean {
        val currentLogicalLine =
            activeDraft?.let(::activeDraftLogicalLine)
                ?: state.blocks.firstOrNull()?.block?.logicalLine
                ?: return false
        return showGoToLineDialog(currentLogicalLine)
    }

    /** Dismisses retained line navigation without changing the document viewport. */
    fun dismissGoToLineDialog() {
        isGoToLineDialogVisible = false
        goToLineErrorMessage = null
    }

    /** Replaces retained line input with at most 19 ASCII decimal digits. */
    fun updateGoToLineInput(input: String) {
        if (
            !isGoToLineDialogVisible ||
            input.length > MAX_GO_TO_LINE_INPUT_CHARACTERS ||
            input.any { character -> character !in '0'..'9' }
        ) {
            return
        }
        goToLineInput = input
        goToLineErrorMessage = null
    }

    /** Validates and starts navigation to the retained one-based line number. */
    fun confirmGoToLine(): Boolean {
        val lineCount = state.metrics?.lineCount
        if (
            !isGoToLineDialogVisible ||
            !canNavigateToLine ||
            lineCount == null
        ) {
            return false
        }
        val displayLine = goToLineInput.toLongOrNull()
        if (
            displayLine == null ||
            displayLine < FIRST_DISPLAY_LINE ||
            displayLine > lineCount
        ) {
            goToLineErrorMessage =
                UiText.Resource(
                    R.string.operation_line_range,
                    listOf(FIRST_DISPLAY_LINE, lineCount)
                )
            return false
        }
        val logicalLine = displayLine - FIRST_DISPLAY_LINE
        val draft = activeDraft
        val queued =
            draft?.let {
                requestLineNavigation(draft = draft, logicalLine = logicalLine)
            } ?: true
        if (!queued) {
            return false
        }
        isGoToLineDialogVisible = false
        goToLineErrorMessage = null
        if (draft == null) {
            launchLineNavigation(logicalLine)
        }
        return true
    }

    /** Returns the logical line containing the freshest bounded-editor viewport anchor. */
    private fun activeDraftLogicalLine(draft: ActiveEditDraft): Long {
        val snapshot = draft.edit.snapshot
        val fallbackUtf16Offset =
            Math.addExact(
                snapshot.range.start,
                draft.textFieldState.selection.start.toLong()
            )
        val anchor =
            resolveVisibleViewportAnchor(
                revision = snapshot.metrics.revision,
                fallbackUtf16Offset = fallbackUtf16Offset
            )
        val localUtf16Offset =
            Math.toIntExact(anchor.sourceUtf16Offset - snapshot.range.start)
                .coerceIn(0, snapshot.text.length)
        var logicalLine = snapshot.start.line
        for (utf16Index in 0 until localUtf16Offset) {
            if (snapshot.text[utf16Index] == '\n') {
                logicalLine = Math.incrementExact(logicalLine)
            }
        }
        return logicalLine
    }

    /** Dismisses one retained line-navigation failure without moving the viewport. */
    fun dismissLineNavigationFailure(): Boolean {
        if (
            closeStarted.get() ||
            state.status != EditorDocumentStatus.Ready ||
            state.lineViewportStatus !is LineViewportStatus.Failed
        ) {
            return false
        }
        launchOperation(
            operation = { state -> state.dismissLineNavigationFailure() }
        )
        return true
    }

    /** Queues one section action after synchronizing the exact retained field value. */
    fun requestEditWindowAction(draft: ActiveEditDraft, action: EditWindowAction): Boolean =
        requestEditWindowAction(
            draft = draft,
            action = action,
            automaticTransition = null,
            lineNavigationTarget = null
        )

    /** Queues one exact logical-line destination after synchronizing the active field. */
    private fun requestLineNavigation(draft: ActiveEditDraft, logicalLine: Long): Boolean {
        require(logicalLine >= 0L) { "logical line must be nonnegative" }
        return requestEditWindowAction(
            draft = draft,
            action = EditWindowAction.GoToLine,
            automaticTransition = null,
            lineNavigationTarget = logicalLine
        )
    }

    /** Queues one automatic neighboring window around a stable visible text anchor. */
    fun requestAutomaticEditWindowTransition(
        draft: ActiveEditDraft,
        towardNext: Boolean,
        localAnchorUtf16Offset: Int,
        anchorViewportTopPixels: Int
    ): Boolean {
        require(localAnchorUtf16Offset in 0..draft.textFieldState.text.length) {
            "automatic edit-window anchor exceeds the field"
        }
        require(
            draft.edit.snapshot.text.isScalarBoundary(localAnchorUtf16Offset)
        ) {
            "automatic edit-window anchor divides a Unicode character"
        }
        if (draft.hasChanges || draft.textFieldState.composition != null) {
            return false
        }
        val localSelection = draft.textFieldState.selection
        val preserveSelection = draft.isEditorFocused
        if (preserveSelection && hasReachedEditSelectionLimit(localSelection)) {
            draft.reportSelectionBoundary()
            return false
        }
        val globalAnchorUtf16Offset =
            Math.addExact(
                draft.edit.snapshot.range.start,
                localAnchorUtf16Offset.toLong()
            )
        val globalSelection =
            DirectedUtf16Selection(
                start =
                    Math.addExact(
                        draft.edit.snapshot.range.start,
                        localSelection.start.toLong()
                    ),
                end =
                    Math.addExact(
                        draft.edit.snapshot.range.start,
                        localSelection.end.toLong()
                    )
            )
        return requestEditWindowAction(
            draft = draft,
            action =
                if (towardNext) {
                    EditWindowAction.Later
                } else {
                    EditWindowAction.Earlier
                },
            automaticTransition =
                AutomaticEditWindowTransition(
                    anchor =
                        SemanticViewportAnchor(
                            revision = draft.edit.snapshot.metrics.revision,
                            sourceUtf16Offset = globalAnchorUtf16Offset,
                            viewportTopOffsetPixels = anchorViewportTopPixels
                        ),
                    selection = globalSelection,
                    preserveSelection = preserveSelection
                ),
            lineNavigationTarget = null
        )
    }

    /** Prefetches clean adjacent windows for one exact active draft generation. */
    fun prefetchAdjacentEditWindows(draft: ActiveEditDraft) {
        if (
            closeStarted.get() ||
            isViewOnly ||
            presentation != EditorPresentation.Text ||
            activeDraft !== draft ||
            draft.hasChanges ||
            draft.textFieldState.composition != null ||
            pendingEditWindowAction != null ||
            state.status != EditorDocumentStatus.Ready
        ) {
            return
        }
        launchOperation(
            operation = { state ->
                state.prefetchAdjacentEditWindows(draft.edit.generation)
            }
        )
    }

    /** Queues one session-wide undo after synchronizing the active bounded field. */
    fun requestUndo(): Boolean {
        val draft = activeDraft ?: return false
        if (!canUndo) {
            return false
        }
        return requestEditWindowAction(draft = draft, action = EditWindowAction.Undo)
    }

    /** Queues one session-wide redo after synchronizing the active bounded field. */
    fun requestRedo(): Boolean {
        val draft = activeDraft ?: return false
        if (!canRedo) {
            return false
        }
        return requestEditWindowAction(draft = draft, action = EditWindowAction.Redo)
    }

    /** Retries the last failed bounded editor window without changing its document. */
    fun retryEditWindow() {
        if (!canRetryEditWindow) {
            return
        }
        launchOperation(operation = EditorDocumentState::retryEditWindow)
    }

    /** Reserves one bulk edit before its originating input transaction returns. */
    fun requestBulkEdit(draft: ActiveEditDraft, proposal: EditorBulkEdit): Boolean {
        if (
            !canApplyEditorInput(draft) || isViewOnly || presentation != EditorPresentation.Text ||
            isSaveBusy || isShareBusy || isPrintBusy || isQrShareBusy || isNfcWriteBusy
        ) {
            return false
        }
        val token = nextEditWindowActionToken
        nextEditWindowActionToken = Math.incrementExact(token)
        val pending = PendingEditWindowAction(
            token = token,
            generation = draft.edit.generation,
            action = EditWindowAction.BulkInsert,
            phase = EditWindowActionPhase.Waiting,
            restoreEditorFocusOnCancel = draft.shouldRestoreEditorFocus,
            automaticTransition = null,
            lineNavigationTarget = null,
            bulkEdit = proposal,
            readyToExecute = false
        )
        pendingEditWindowAction = pending
        operationScope.launch {
            // Finish the input transaction before ending composition or editing field state.
            yield()
            if (closeStarted.get() || pendingEditWindowAction?.token != token) return@launch
            if (activeDraft !== draft ||
                !draft.textFieldState.text.contentEquals(proposal.originalText)
            ) {
                cancelWaitingEditWindowAction(pending.generation)
                draft.reportInputRejection(EditorInputRejection.BulkUnavailable)
                return@launch
            }
            draft.commitComposingText()
            observeActiveEdit(draft, draft.captureFieldValue())
            if (pendingEditWindowAction?.token != token) return@launch
            pendingEditWindowAction = pending.copy(readyToExecute = true)
            requestImmediateEditSynchronization(draft)
            resolvePendingEditWindowAction()
        }
        return true
    }

    /** Applies bulk input through the same atomic native range replacement as history. */
    private fun applyBulkEdit(draft: ActiveEditDraft, pending: PendingEditWindowAction) {
        val proposal = checkNotNull(pending.bulkEdit)
        val snapshot = draft.edit.snapshot
        if (snapshot.text != proposal.originalText) {
            draft.reportInputRejection(EditorInputRejection.BulkUnavailable)
            completeExecutingEditWindowAction(pending.token)
            return
        }
        val start = snapshot.range.start
        val delta = proposal.delta
        val removed = proposal.originalText.substring(
            delta.oldRange.start.toInt(),
            delta.oldRange.end.toInt()
        )
        val selectionBefore = Utf16Range(
            Math.addExact(start, proposal.selectionBefore.start),
            Math.addExact(start, proposal.selectionBefore.end)
        )
        val request = DocumentReplacementRequest(
            expectedRevision = snapshot.metrics.revision,
            range = Utf16Range(
                Math.addExact(start, delta.oldRange.start),
                Math.addExact(start, delta.oldRange.end)
            ),
            expectedRemovedText = removed,
            replacement = delta.replacement,
            selectionAfter = Utf16Range(
                Math.addExact(start, proposal.selectionAfter.start),
                Math.addExact(start, proposal.selectionAfter.end)
            )
        )
        var result: DocumentReplacementResult = DocumentReplacementResult.Unavailable
        launchOperation(
            operation = { state ->
                result =
                    state.replaceDocumentRange(draft.edit.generation, request)
            },
            requestDraftFocus = draft.isEditorFocused,
            onCompletion = {
                when (val completed = result) {
                    is DocumentReplacementResult.Applied -> {
                        recordCommittedEdit(
                            CommittedEditDelta(
                                revisionBefore = request.expectedRevision,
                                revisionAfter = completed.revision,
                                rangeStart = request.range.start,
                                removedText = removed,
                                insertedText = request.replacement,
                                selectionBefore = selectionBefore,
                                selectionAfter = request.selectionAfter
                            )
                        )
                        invalidateMarkdownPreview()
                        recordSourceSaveRequest()
                    }

                    DocumentReplacementResult.RejectedBySizeLimit ->
                        draft.reportInputRejection(EditorInputRejection.DocumentSize)

                    DocumentReplacementResult.Failed,
                    DocumentReplacementResult.Unavailable ->
                        draft.reportInputRejection(EditorInputRejection.BulkUnavailable)
                }
                completeExecutingEditWindowAction(pending.token)
            }
        )
    }

    /** Returns whether one active draft may enter the bounded history pipeline. */
    private fun canRequestHistoryAction(draft: ActiveEditDraft): Boolean = !closeStarted.get() &&
        !isViewOnly &&
        !isSourceReloading &&
        !isClosePending &&
        presentation == EditorPresentation.Text &&
        activeDraft === draft &&
        pendingEditWindowAction == null &&
        !isSaveBusy &&
        !isShareBusy &&
        !isPrintBusy &&
        !isQrShareBusy &&
        !isNfcWriteBusy &&
        state.status == EditorDocumentStatus.Ready &&
        (
            history.headRevision == null ||
                state.metrics?.revision == history.headRevision
            )

    /** Applies the newest inverse or forward history entry through the native document. */
    private fun applyHistoryAction(
        draft: ActiveEditDraft,
        action: EditWindowAction,
        actionToken: Long
    ) {
        require(action == EditWindowAction.Undo || action == EditWindowAction.Redo) {
            "history application requires undo or redo"
        }
        val entry =
            if (action == EditWindowAction.Undo) {
                history.undoEntry
            } else {
                history.redoEntry
            }
        val currentRevision = state.metrics?.revision
        if (entry == null || currentRevision == null || currentRevision != history.headRevision) {
            completeExecutingEditWindowAction(actionToken)
            return
        }
        val request =
            if (action == EditWindowAction.Undo) {
                DocumentReplacementRequest(
                    expectedRevision = currentRevision,
                    range =
                        Utf16Range(
                            start = entry.rangeStart,
                            end =
                                Math.addExact(
                                    entry.rangeStart,
                                    entry.insertedText.length.toLong()
                                )
                        ),
                    expectedRemovedText = entry.insertedText,
                    replacement = entry.removedText,
                    selectionAfter = entry.selectionBefore
                )
            } else {
                DocumentReplacementRequest(
                    expectedRevision = currentRevision,
                    range =
                        Utf16Range(
                            start = entry.rangeStart,
                            end = Math.addExact(entry.rangeStart, entry.removedText.length.toLong())
                        ),
                    expectedRemovedText = entry.removedText,
                    replacement = entry.insertedText,
                    selectionAfter = entry.selectionAfter
                )
            }
        var result: DocumentReplacementResult = DocumentReplacementResult.Unavailable
        launchOperation(
            operation = { state ->
                result =
                    state.replaceDocumentRange(
                        generation = draft.edit.generation,
                        request = request
                    )
            },
            requestDraftFocus = draft.isEditorFocused,
            onCompletion = {
                when (val completed = result) {
                    is DocumentReplacementResult.Applied -> {
                        completeHistoryStackMove(action, entry, completed.revision)
                        invalidateMarkdownPreview()
                        recordSourceSaveRequest()
                    }

                    DocumentReplacementResult.Failed -> {
                        if (state.status == EditorDocumentStatus.Stale) {
                            clearSessionHistory()
                        }
                    }

                    DocumentReplacementResult.Unavailable -> Unit

                    DocumentReplacementResult.RejectedBySizeLimit ->
                        draft.reportInputRejection(EditorInputRejection.DocumentSize)
                }
                completeExecutingEditWindowAction(actionToken)
            }
        )
    }

    /** Publishes one successful native history edit to the retained journal and UI. */
    private fun completeHistoryStackMove(
        action: EditWindowAction,
        entry: CommittedEditDelta,
        revision: Long
    ) {
        when (action) {
            EditWindowAction.Undo -> history.completeUndo(entry, revision)
            EditWindowAction.Redo -> history.completeRedo(entry, revision)
            else -> error("history stack move requires undo or redo")
        }
        historyVersion = Math.incrementExact(historyVersion)
    }

    /** Records one verified edit and publishes the journal's new availability. */
    private fun recordCommittedEdit(delta: CommittedEditDelta) {
        history.record(delta)
        historyVersion = Math.incrementExact(historyVersion)
    }

    /** Releases session history and publishes its new revision boundary. */
    private fun clearSessionHistory(revision: Long? = state.metrics?.revision) {
        history.clear(revision)
        historyVersion = Math.incrementExact(historyVersion)
    }

    /** Retains one exact action until its clean draft can cross the window boundary. */
    private fun requestEditWindowAction(
        draft: ActiveEditDraft,
        action: EditWindowAction,
        automaticTransition: AutomaticEditWindowTransition?,
        lineNavigationTarget: Long?
    ): Boolean {
        val generation = draft.edit.generation
        if (
            closeStarted.get() ||
            isViewOnly ||
            isSourceReloading ||
            isClosePending ||
            presentation != EditorPresentation.Text ||
            activeDraft !== draft ||
            pendingEditWindowAction != null ||
            isSaveBusy ||
            !state.canAcceptActiveDraftInput(generation)
        ) {
            return false
        }
        val snapshot = draft.edit.snapshot
        val isAvailable =
            when (action) {
                EditWindowAction.Earlier -> snapshot.hasPrevious

                EditWindowAction.Later -> snapshot.hasNext

                EditWindowAction.OpenFind,
                EditWindowAction.GoToLine,
                EditWindowAction.OpenPreview,
                EditWindowAction.OpenFileInfo -> true

                EditWindowAction.Undo -> draft.hasChanges || (history.undoEntry != null)

                EditWindowAction.Redo -> !draft.hasChanges && (history.redoEntry != null)

                // Bulk input requires its dedicated bounded proposal path.
                EditWindowAction.BulkInsert -> false
            }
        if (!isAvailable) {
            return false
        }
        if (action == EditWindowAction.Undo || action == EditWindowAction.Redo) {
            draft.commitComposingText()
        }
        val fieldValue = draft.captureFieldValue()
        val token = nextEditWindowActionToken
        nextEditWindowActionToken = Math.incrementExact(nextEditWindowActionToken)
        pendingEditWindowAction =
            PendingEditWindowAction(
                token = token,
                generation = generation,
                action = action,
                phase = EditWindowActionPhase.Waiting,
                restoreEditorFocusOnCancel = draft.shouldRestoreEditorFocus,
                automaticTransition = automaticTransition,
                lineNavigationTarget = lineNavigationTarget
            )
        observeActiveEdit(draft = draft, value = fieldValue)
        if (fieldValue.composition == null) {
            requestImmediateEditSynchronization(draft)
            resolvePendingEditWindowAction()
        }
        return true
    }

    /** Cancels one waiting section action without cancelling its document edit. */
    fun cancelPendingEditWindowAction() {
        val pending = pendingEditWindowAction ?: return
        if (pending.phase != EditWindowActionPhase.Waiting ||
            pending.action == EditWindowAction.BulkInsert
        ) {
            return
        }
        cancelWaitingEditWindowAction(pending.generation)
    }

    /** Drops a waiting action while restoring its retained field-focus intent. */
    private fun cancelWaitingEditWindowAction(generation: Long) {
        val pending = pendingEditWindowAction
        if (
            pending?.generation != generation ||
            pending.phase != EditWindowActionPhase.Waiting
        ) {
            return
        }
        pendingEditWindowAction = null
        pendingEditWindowScrollRestoration = null
        pendingFindAnchor = null
        pendingMarkdownPreviewReturnTarget = null
        if (pending.restoreEditorFocusOnCancel) {
            activeDraft
                ?.takeIf { draft -> draft.edit.generation == pending.generation }
                ?.updateEditorFocusIntent(isFocused = true, canClear = true)
        }
    }

    /** Executes one queued action only after its exact draft is committed and stable. */
    private fun resolvePendingEditWindowAction() {
        val pending = pendingEditWindowAction ?: return
        if (pending.phase != EditWindowActionPhase.Waiting || !pending.readyToExecute) {
            return
        }
        val draft = activeDraft
        if (draft?.edit?.generation != pending.generation) {
            pendingEditWindowAction = null
            pendingEditWindowScrollRestoration = null
            pendingFindAnchor = null
            pendingMarkdownPreviewReturnTarget = null
            return
        }
        val fieldValue =
            if (latestObservedDraft === draft) {
                checkNotNull(latestFieldValue)
            } else {
                draft.captureFieldValue()
            }
        if (
            fieldValue.composition != null ||
            draft.hasChanges ||
            state.status != EditorDocumentStatus.Ready
        ) {
            return
        }
        val snapshot = draft.edit.snapshot
        val isAvailable =
            when (pending.action) {
                EditWindowAction.Earlier -> snapshot.hasPrevious

                EditWindowAction.Later -> snapshot.hasNext

                EditWindowAction.OpenFind,
                EditWindowAction.GoToLine,
                EditWindowAction.OpenPreview,
                EditWindowAction.OpenFileInfo -> true

                EditWindowAction.Undo -> (history.undoEntry != null)

                EditWindowAction.Redo -> (history.redoEntry != null)

                EditWindowAction.BulkInsert -> true
            }
        if (!isAvailable) {
            pendingEditWindowAction = null
            pendingEditWindowScrollRestoration = null
            pendingFindAnchor = null
            pendingMarkdownPreviewReturnTarget = null
            return
        }
        if (pending.action == EditWindowAction.OpenFind) {
            check(fieldValue.selection.start in 0..snapshot.text.length) {
                "find anchor selection exceeds the edit window"
            }
            pendingFindAnchor =
                SemanticViewportAnchor(
                    revision = snapshot.metrics.revision,
                    sourceUtf16Offset =
                        Math.addExact(
                            snapshot.range.start,
                            fieldValue.selection.start.toLong()
                        ),
                    viewportTopOffsetPixels = 0
                )
        } else if (pending.action == EditWindowAction.OpenPreview) {
            val selection =
                Utf16Range(
                    start =
                        Math.addExact(
                            snapshot.range.start,
                            fieldValue.selection.min.toLong()
                        ),
                    end =
                        Math.addExact(
                            snapshot.range.start,
                            fieldValue.selection.max.toLong()
                        )
                )
            pendingMarkdownPreviewReturnTarget =
                MarkdownPreviewReturnTarget(
                    viewportAnchor =
                        resolveVisibleViewportAnchor(
                            revision = snapshot.metrics.revision,
                            fallbackUtf16Offset = selection.start
                        ),
                    selection = selection
                )
        }
        if (pending.action == EditWindowAction.OpenFind) {
            val findAnchor = pendingFindAnchor
            pendingEditWindowAction = null
            pendingFindAnchor = null
            if (
                findAnchor == null ||
                findAnchor.revision != state.metrics?.revision ||
                !openFind(findAnchor.sourceUtf16Offset)
            ) {
                activeDraft?.requestEditorFocusRestoration()
            }
            return
        }
        if (pending.action == EditWindowAction.OpenFileInfo) {
            pendingEditWindowAction = null
            openFileInfo()
            return
        }
        pendingEditWindowAction = pending.copy(phase = EditWindowActionPhase.Executing)
        when (pending.action) {
            EditWindowAction.Earlier ->
                moveEditWindow(
                    draft = draft,
                    towardNext = false,
                    automaticTransition = pending.automaticTransition,
                    actionToken = pending.token
                )

            EditWindowAction.Later ->
                moveEditWindow(
                    draft = draft,
                    towardNext = true,
                    automaticTransition = pending.automaticTransition,
                    actionToken = pending.token
                )

            EditWindowAction.GoToLine ->
                moveActiveEditToLine(
                    draft = draft,
                    logicalLine = checkNotNull(pending.lineNavigationTarget),
                    actionToken = pending.token
                )

            EditWindowAction.OpenPreview ->
                finishActiveEdit(
                    draft = draft,
                    actionToken = pending.token
                )

            EditWindowAction.OpenFind,
            EditWindowAction.OpenFileInfo ->
                error("overlay action must not close its edit window")

            EditWindowAction.Undo,
            EditWindowAction.Redo ->
                applyHistoryAction(
                    draft = draft,
                    action = pending.action,
                    actionToken = pending.token
                )

            EditWindowAction.BulkInsert -> applyBulkEdit(draft, pending)
        }
    }

    /** Clears one executing action only when its immutable token still owns the slot. */
    private fun completeExecutingEditWindowAction(actionToken: Long) {
        val pending = pendingEditWindowAction
        if (
            pending?.token != actionToken ||
            pending.phase != EditWindowActionPhase.Executing
        ) {
            return
        }
        pendingEditWindowAction = null
        pendingEditWindowScrollRestoration = null
        pendingFindAnchor = null
        val previewReturnTarget = pendingMarkdownPreviewReturnTarget
        pendingMarkdownPreviewReturnTarget = null
        if (pending.action == EditWindowAction.OpenPreview) {
            if (
                state.activeEdit == null &&
                state.status == EditorDocumentStatus.Ready
            ) {
                openMarkdownPreview(previewReturnTarget)
            } else {
                activeDraft?.updateEditorFocusIntent(isFocused = true, canClear = true)
            }
        }
    }

    /** Replaces one clean bounded field with an exact logical-line destination. */
    private fun moveActiveEditToLine(draft: ActiveEditDraft, logicalLine: Long, actionToken: Long) {
        launchOperation(
            operation = { state ->
                if (state.navigateActiveEditToLine(logicalLine)) {
                    val activeEdit = checkNotNull(state.activeEdit)
                    pendingEditWindowScrollRestoration =
                        EditWindowScrollRestoration(
                            anchor =
                                SemanticViewportAnchor(
                                    revision = activeEdit.snapshot.metrics.revision,
                                    sourceUtf16Offset = activeEdit.snapshot.selection.start,
                                    viewportTopOffsetPixels = 0
                                )
                        )
                }
            },
            requestDraftFocus = true,
            onCompletion = {
                completeExecutingEditWindowAction(actionToken)
                activeDraft?.requestEditorFocusRestoration()
            }
        )
    }

    /** Opens a clean neighboring section for an already validated action. */
    private fun moveEditWindow(
        draft: ActiveEditDraft,
        towardNext: Boolean,
        automaticTransition: AutomaticEditWindowTransition?,
        actionToken: Long
    ) {
        val automaticSelection = automaticTransition?.selection?.orderedRange
        pendingEditWindowScrollRestoration =
            automaticTransition?.let { transition ->
                EditWindowScrollRestoration(
                    anchor = transition.anchor,
                    preserveScrollMomentum = true
                )
            }
        if (automaticTransition?.preserveSelection == false) {
            draft.updateEditorFocusIntent(isFocused = false, canClear = true)
        }
        launchOperation(
            operation = { state ->
                if (automaticTransition != null && automaticSelection != null) {
                    state.moveEditWindowToAnchor(
                        generation = draft.edit.generation,
                        towardNext = towardNext,
                        anchorUtf16Offset = automaticTransition.anchor.sourceUtf16Offset,
                        selection = automaticSelection,
                        preserveSelection = automaticTransition.preserveSelection,
                        hasDraftChanges = false
                    )
                } else {
                    state.moveEditWindow(
                        generation = draft.edit.generation,
                        towardNext = towardNext,
                        hasDraftChanges = false
                    )
                }
            },
            requestDraftFocus = automaticTransition?.preserveSelection ?: true,
            onCompletion = { completeExecutingEditWindowAction(actionToken) }
        )
    }

    /** Returns one clean bounded field to its current document viewport. */
    private fun finishActiveEdit(draft: ActiveEditDraft, actionToken: Long) {
        launchOperation(
            operation = { state ->
                state.discardActiveEdit(draft.edit.generation)
            },
            onCompletion = { completeExecutingEditWindowAction(actionToken) }
        )
    }

    /** Crosses one stale field boundary and reloads the authoritative Rust revision. */
    fun reloadStaleActiveEdit(draft: ActiveEditDraft) {
        if (
            state.status != EditorDocumentStatus.Stale ||
            activeDraft !== draft ||
            closeStarted.get()
        ) {
            return
        }
        clearSessionHistory(revision = null)
        launchOperation(
            operation = { state -> state.discardActiveEdit(draft.edit.generation) }
        )
    }

    /** Retries one failed automatic synchronization from its committed baseline. */
    fun retryActiveEdit(draft: ActiveEditDraft) {
        if (
            state.status != EditorDocumentStatus.Ready ||
            isViewOnly ||
            state.editorMessage == null ||
            activeDraft !== draft ||
            draft.textFieldState.composition != null ||
            !draft.hasChanges
        ) {
            return
        }
        observeActiveEdit(draft = draft, value = draft.captureFieldValue())
    }

    /** Retries the newest pending source revision after a latched failure. */
    fun retrySourceSave() {
        if (
            closeStarted.get() ||
            ownedDocumentSource !is WritableEditorDocumentSource ||
            sourceSaveStatus !is SourceSaveStatus.Failed
        ) {
            return
        }
        sourceSaveStatus = SourceSaveStatus.Pending
        ensureSourceSave()
    }

    /** Shows confirmation before discarding local edits and reopening the source. */
    fun requestSourceReloadConfirmation(): Boolean {
        if (!canRequestSourceReload) {
            return false
        }
        sourceConflictResolution = SourceConflictResolution.Reload
        return true
    }

    /** Shows confirmation before replacing external changes with local edits. */
    fun requestSourceOverwriteConfirmation(): Boolean {
        if (!canRequestSourceOverwrite) {
            return false
        }
        sourceConflictResolution = SourceConflictResolution.Overwrite
        return true
    }

    /** Dismisses one source-conflict confirmation without changing either version. */
    fun dismissSourceConflictConfirmation() {
        sourceConflictResolution = null
    }

    /** Claims the current source URI and locks editing for one confirmed reload. */
    fun beginSourceReload(): String? {
        if (
            sourceConflictResolution != SourceConflictResolution.Reload ||
            !hasAvailableSourceConflict()
        ) {
            return null
        }
        val encodedUri = ownedDocumentSource?.encodedShareUri()
            ?.takeUnless(String::isBlank)
            ?: return null
        sourceConflictResolution = null
        sourceSaveStatus = SourceSaveStatus.Reloading
        return encodedUri
    }

    /** Restores conflict recovery after a confirmed source reload could not finish. */
    fun failSourceReload(message: UiText) {
        if (!closeStarted.get() && sourceSaveStatus == SourceSaveStatus.Reloading) {
            sourceSaveStatus = SourceSaveStatus.Conflict(message)
            signalPendingCloseResolution()
        }
    }

    /** Queues the newest settled local revision for one confirmed safe overwrite. */
    fun confirmSourceOverwrite(): Boolean {
        if (
            sourceConflictResolution != SourceConflictResolution.Overwrite ||
            !hasAvailableSourceConflict() ||
            ownedDocumentSource !is ConflictRecoverableEditorDocumentSource
        ) {
            return false
        }
        sourceConflictResolution = null
        overwriteSourceOnNextSave = true
        sourceSaveRequestVersion = Math.incrementExact(sourceSaveRequestVersion)
        sourceMetadata
            ?.takeIf { metadata -> metadata.lastModifiedEpochMillis != null }
            ?.let { metadata ->
                sourceMetadata = metadata.copy(lastModifiedEpochMillis = null)
            }
        sourceSaveStatus = SourceSaveStatus.Pending
        flushPendingEdit()
        ensureSourceSave()
        return true
    }

    /** Flushes the newest non-composing draft without cancelling a native mutation. */
    fun flushPendingEdit() {
        flushPendingEdit(checkpointComposition = false)
    }

    /** Checkpoints the visible draft even while the IME still owns a composition. */
    fun checkpointPendingEdit() {
        flushPendingEdit(checkpointComposition = true)
    }

    /** Queues one immediate synchronization of the newest visible draft. */
    private fun flushPendingEdit(checkpointComposition: Boolean) {
        val draft = activeDraft ?: return
        if (closeStarted.get() || isViewOnly || !draft.hasChanges) {
            return
        }
        val capturedValue = draft.captureFieldValue()
        val fieldValue =
            if (checkpointComposition) {
                capturedValue.copy(composition = null)
            } else {
                capturedValue
            }
        if (fieldValue.composition != null) {
            return
        }
        draft.recordFieldObservation(fieldValue)
        updateActiveDraftStatus(draft)
        latestObservedDraft = draft
        latestFieldValue = fieldValue
        editObservationVersion = Math.incrementExact(editObservationVersion)
        requestImmediateEditSynchronization(draft)
    }

    /** Queues one exact share after the current draft and source save settle. */
    fun requestShare(): Boolean {
        if (!canStartShare) {
            return false
        }
        val generation = nextShareGeneration
        nextShareGeneration = Math.incrementExact(nextShareGeneration)
        val draft = activeDraft
        shareStatus =
            ShareStatus.Queued(
                generation = generation,
                draftGeneration = draft?.edit?.generation
            )
        if (draft != null) {
            val fieldValue = draft.captureFieldValue()
            observeActiveEdit(draft = draft, value = fieldValue)
            if (fieldValue.composition == null) {
                requestImmediateEditSynchronization(draft)
            }
        }
        ensureSourceSave()
        resolveQueuedShare()
        return true
    }

    /** Cancels queued or active share preparation without touching source output. */
    fun cancelShare() {
        when (val status = shareStatus) {
            is ShareStatus.Queued,
            is ShareStatus.Ready -> {
                shareStatus = ShareStatus.Idle
                ensureSourceSave()
            }

            is ShareStatus.Preparing -> {
                shareStatus = ShareStatus.Cancelling(status.generation)
                sharePreparationJob?.cancel()
            }

            is ShareStatus.Cancelling,
            is ShareStatus.Failed,
            ShareStatus.Idle -> Unit
        }
    }

    /** Acknowledges one exact chooser launch and releases its transient payload. */
    fun completeShareLaunch(generation: Long): Boolean {
        val ready = shareStatus as? ShareStatus.Ready ?: return false
        if (ready.request.generation != generation) {
            return false
        }
        shareStatus = ShareStatus.Idle
        ensureSourceSave()
        return true
    }

    /** Reports one sanitized chooser-launch failure for an exact ready payload. */
    fun failShareLaunch(generation: Long): Boolean {
        val ready = shareStatus as? ShareStatus.Ready ?: return false
        if (ready.request.generation != generation) {
            return false
        }
        shareStatus =
            ShareStatus.Failed(
                generation = generation,
                message = UiText.Resource(R.string.operation_share_sheet_failed)
            )
        ensureSourceSave()
        return true
    }

    /** Dismisses one retained share failure without changing document state. */
    fun dismissShareFailure(generation: Long) {
        val failed = shareStatus as? ShareStatus.Failed ?: return
        if (failed.generation == generation) {
            shareStatus = ShareStatus.Idle
            ensureSourceSave()
        }
    }

    /** Shows one in-memory print setup for the current document. */
    fun showPrintSetup(): Boolean {
        if (!canStartPrint) {
            return false
        }
        printSetupDraft =
            defaultPrintSetupDraft(
                formattedMarkdown =
                    canPrintFormattedMarkdown &&
                        (
                            presentation == EditorPresentation.MarkdownPreview ||
                                documentFormat == DocumentFormat.Markdown
                            )
            )
        return true
    }

    /** Replaces the visible transient print fields without starting an operation. */
    fun updatePrintSetup(draft: PrintSetupDraft) {
        if (!draft.hasBoundedInput || printSetupDraft == null || isPrintBusy ||
            closeStarted.get()
        ) {
            return
        }
        printSetupDraft = draft
    }

    /** Dismisses the transient print setup without retaining its values. */
    fun dismissPrintSetup() {
        printSetupDraft = null
    }

    /** Validates the visible setup and queues its exact configuration. */
    fun confirmPrintSetup(): Boolean {
        val draft = printSetupDraft ?: return false
        val settings = validatePrintSetup(draft).settings ?: return false
        if (
            settings.contentMode == PrintContentMode.FormattedMarkdown &&
            !canPrintFormattedMarkdown
        ) {
            return false
        }
        if (!canQueuePrint()) {
            return false
        }
        printSetupDraft = null
        return requestPrint(settings)
    }

    /** Queues native printing after the exact visible draft settles. */
    fun requestPrint(
        settings: PrintSettings = defaultPrintSettings(formattedMarkdown = false)
    ): Boolean {
        if (
            !canQueuePrint() ||
            (
                settings.contentMode == PrintContentMode.FormattedMarkdown &&
                    !canPrintFormattedMarkdown
                )
        ) {
            return false
        }
        val generation = nextPrintGeneration
        nextPrintGeneration = Math.incrementExact(nextPrintGeneration)
        val draft = activeDraft
        printStatus =
            PrintStatus.Queued(
                generation = generation,
                draftGeneration = draft?.edit?.generation,
                settings = settings
            )
        if (draft != null) {
            val fieldValue = draft.captureFieldValue()
            observeActiveEdit(draft = draft, value = fieldValue)
            if (fieldValue.composition == null) {
                requestImmediateEditSynchronization(draft)
            }
        }
        ensureSourceSave()
        resolveQueuedPrint()
        return true
    }

    /** Retries the exact transient settings retained by one print failure. */
    fun retryPrint(): Boolean {
        val failed = printStatus as? PrintStatus.Failed ?: return false
        printStatus = PrintStatus.Idle
        if (requestPrint(failed.settings)) {
            return true
        }
        printStatus = failed
        return false
    }

    /** Cancels queued, active, or not-yet-launched native printing. */
    fun cancelPrint() {
        when (val status = printStatus) {
            is PrintStatus.Queued -> {
                printStatus = PrintStatus.Idle
                ensureSourceSave()
            }

            is PrintStatus.Ready -> {
                printStatus = PrintStatus.Idle
                status.request.close()
                ensureSourceSave()
            }

            is PrintStatus.Preparing -> {
                printStatus = PrintStatus.Cancelling(status.generation)
                printPreparationJob?.cancel()
            }

            is PrintStatus.Cancelling,
            is PrintStatus.Failed,
            PrintStatus.Idle -> Unit
        }
    }

    /** Acknowledges one exact Android print-screen launch. */
    fun completePrintLaunch(generation: Long): Boolean {
        val ready = printStatus as? PrintStatus.Ready ?: return false
        if (ready.request.generation != generation) {
            return false
        }
        printStatus = PrintStatus.Idle
        ready.request.close()
        ensureSourceSave()
        return true
    }

    /** Reports one sanitized Android print-screen launch failure. */
    fun failPrintLaunch(generation: Long): Boolean {
        val ready = printStatus as? PrintStatus.Ready ?: return false
        if (ready.request.generation != generation) {
            return false
        }
        ready.request.close()
        printStatus =
            PrintStatus.Failed(
                generation = generation,
                message = PRINT_LAUNCH_FAILURE_MESSAGE,
                settings = ready.request.settings
            )
        ensureSourceSave()
        return true
    }

    /** Dismisses one retained print failure without changing document state. */
    fun dismissPrintFailure(generation: Long) {
        val failed = printStatus as? PrintStatus.Failed ?: return
        if (failed.generation == generation) {
            printStatus = PrintStatus.Idle
            ensureSourceSave()
        }
    }

    /** Queues one bounded QR share after the draft and source save settle. */
    fun requestQrShare(): Boolean {
        if (!canStartQrShare) {
            return false
        }
        val generation = nextQrShareGeneration
        nextQrShareGeneration = Math.incrementExact(nextQrShareGeneration)
        val draft = activeDraft
        qrShareStatus =
            QrShareStatus.Queued(
                generation = generation,
                draftGeneration = draft?.edit?.generation
            )
        if (draft != null) {
            val fieldValue = draft.captureFieldValue()
            observeActiveEdit(draft = draft, value = fieldValue)
            if (fieldValue.composition == null) {
                requestImmediateEditSynchronization(draft)
            }
        }
        ensureSourceSave()
        resolveQueuedQrShare()
        return true
    }

    /** Cancels queued, active, or displayed QR sharing. */
    fun cancelQrShare() {
        when (val status = qrShareStatus) {
            is QrShareStatus.Queued,
            is QrShareStatus.Ready -> {
                qrShareStatus = QrShareStatus.Idle
                ensureSourceSave()
            }

            is QrShareStatus.Preparing -> {
                qrShareStatus = QrShareStatus.Cancelling(status.generation)
                qrSharePreparationJob?.cancel()
            }

            is QrShareStatus.Cancelling,
            is QrShareStatus.Failed,
            QrShareStatus.Idle -> Unit
        }
    }

    /** Dismisses one displayed QR grid without changing document state. */
    fun dismissQrShare(generation: Long) {
        val ready = qrShareStatus as? QrShareStatus.Ready ?: return
        if (ready.generation == generation) {
            qrShareStatus = QrShareStatus.Idle
            ensureSourceSave()
        }
    }

    /** Dismisses one retained QR failure without changing document state. */
    fun dismissQrShareFailure(generation: Long) {
        val failed = qrShareStatus as? QrShareStatus.Failed ?: return
        if (failed.generation == generation) {
            qrShareStatus = QrShareStatus.Idle
            ensureSourceSave()
        }
    }

    /** Opens optional physical-tag label configuration for one bounded NFC write. */
    fun requestNfcWrite(): Boolean {
        if (!canStartNfcWrite) {
            return false
        }
        val generation = nextNfcWriteGeneration
        nextNfcWriteGeneration = Math.incrementExact(nextNfcWriteGeneration)
        nfcWriteStatus = NfcWriteStatus.Configuring(generation)
        return true
    }

    /** Queues one configured NFC write after the draft and source save settle. */
    fun confirmNfcWriteConfiguration(generation: Long, tagLabel: String?): Boolean {
        require(isValidNfcTagLabel(tagLabel)) { "NFC tag label is invalid" }
        val configuring = nfcWriteStatus as? NfcWriteStatus.Configuring ?: return false
        if (configuring.generation != generation) {
            return false
        }
        val draft = activeDraft
        nfcWriteStatus =
            NfcWriteStatus.Queued(
                generation = generation,
                draftGeneration = draft?.edit?.generation,
                tagLabel = tagLabel
            )
        if (draft != null) {
            val fieldValue = draft.captureFieldValue()
            observeActiveEdit(draft = draft, value = fieldValue)
            if (fieldValue.composition == null) {
                requestImmediateEditSynchronization(draft)
            }
        }
        ensureSourceSave()
        resolveQueuedNfcWrite()
        return true
    }

    /** Cancels queued, active, or waiting NFC tag preparation. */
    fun cancelNfcWrite() {
        when (val status = nfcWriteStatus) {
            is NfcWriteStatus.Configuring -> {
                nfcWriteStatus = NfcWriteStatus.Idle
                ensureSourceSave()
            }

            is NfcWriteStatus.Queued -> {
                nfcWriteStatus = NfcWriteStatus.Idle
                ensureSourceSave()
            }

            is NfcWriteStatus.Ready -> {
                nfcWriteStatus = NfcWriteStatus.Idle
                status.envelope.close()
                ensureSourceSave()
            }

            is NfcWriteStatus.Preparing -> {
                nfcWriteStatus = NfcWriteStatus.Cancelling(status.generation)
                nfcWritePreparationJob?.cancel()
            }

            is NfcWriteStatus.Cancelling,
            is NfcWriteStatus.Failed,
            is NfcWriteStatus.Succeeded,
            NfcWriteStatus.Idle -> Unit
        }
    }

    /** Records one exact foreground tag write after Android verifies its NDEF message. */
    fun completeNfcWrite(generation: Long): Boolean {
        val ready = nfcWriteStatus as? NfcWriteStatus.Ready ?: return false
        if (ready.generation != generation) {
            return false
        }
        nfcWriteStatus = NfcWriteStatus.Succeeded(generation)
        ready.envelope.close()
        ensureSourceSave()
        return true
    }

    /** Dismisses one retained NFC preparation result without changing document state. */
    fun dismissNfcWriteResult(generation: Long) {
        val matches =
            when (val status = nfcWriteStatus) {
                is NfcWriteStatus.Failed -> status.generation == generation
                is NfcWriteStatus.Succeeded -> status.generation == generation
                else -> false
            }
        if (matches) {
            nfcWriteStatus = NfcWriteStatus.Idle
            ensureSourceSave()
        }
    }

    /** Conflates one atomic field observation into the automatic Rust pipeline. */
    fun observeActiveEdit(draft: ActiveEditDraft, value: ActiveEditFieldValue) {
        if (closeStarted.get() || isViewOnly || activeDraft !== draft) {
            return
        }
        draft.recordFieldObservation(value)
        updateActiveDraftStatus(draft)
        latestObservedDraft = draft
        latestFieldValue = value
        val hasPendingSynchronization = editSynchronizationJob != null
        if (!draft.hasChanges && !hasPendingSynchronization) {
            resolvePendingEditWindowAction()
            resolveQueuedExplicitSave()
            resolveQueuedShare()
            resolveQueuedPrint()
            resolveQueuedQrShare()
            resolveQueuedNfcWrite()
            return
        }
        editObservationVersion = Math.incrementExact(editObservationVersion)
        if (value.composition != null && !hasPendingSynchronization) {
            return
        }
        if (
            value.composition == null &&
            (
                pendingEditWindowAction?.generation == draft.edit.generation ||
                    (saveStatus as? SaveStatus.Queued)?.draftGeneration ==
                    draft.edit.generation ||
                    (printStatus as? PrintStatus.Queued)?.draftGeneration ==
                    draft.edit.generation ||
                    (qrShareStatus as? QrShareStatus.Queued)?.draftGeneration ==
                    draft.edit.generation ||
                    (nfcWriteStatus as? NfcWriteStatus.Queued)?.draftGeneration ==
                    draft.edit.generation
                )
        ) {
            requestImmediateEditSynchronization(draft)
            return
        }
        ensureEditSynchronization()
    }

    /** Queues retained format selection for the document's appropriate destination purpose. */
    fun showSaveFormatSelection(): Boolean = requestSaveFormatSelection(
        if (shouldSelectNewDocumentSource) {
            SaveDestinationPurpose.SourceReplacement
        } else {
            SaveDestinationPurpose.Copy
        }
    )

    /** Queues transient-copy selection without changing the retained source. */
    fun showSaveCopyFormatSelection(): Boolean =
        requestSaveFormatSelection(SaveDestinationPurpose.Copy)

    /** Freezes the first accepted destination purpose before current work settles. */
    private fun requestSaveFormatSelection(purpose: SaveDestinationPurpose): Boolean {
        if (!canStartSaveAs) {
            return false
        }
        val draft = activeDraft
        queuedSourceReplacementEditTarget =
            if (
                purpose == SaveDestinationPurpose.SourceReplacement &&
                (presentation == EditorPresentation.Text || isViewOnly)
            ) {
                captureSourceReplacementEditTarget(draft)
            } else {
                null
            }
        saveStatus =
            SaveStatus.Queued(
                purpose = purpose,
                draftGeneration = draft?.edit?.generation
            )
        if (draft != null) {
            val fieldValue = draft.captureFieldValue()
            observeActiveEdit(draft = draft, value = fieldValue)
            if (fieldValue.composition == null) {
                requestImmediateEditSynchronization(draft)
            }
        }
        resolveQueuedExplicitSave()
        return true
    }

    /** Dismisses format selection without changing the document's dirty state. */
    fun dismissSaveFormatSelection() {
        if (saveStatus is SaveStatus.ChoosingFormat) {
            queuedSourceReplacementEditTarget = null
            saveStatus = SaveStatus.Idle
            cancelSaveBeforeClose()
            ensureSourceSave()
        }
    }

    /** Publishes one exact picker request for the selected document format. */
    fun selectSaveFormat(format: DocumentFormat) {
        if (closeStarted.get() || !canChooseSaveFormat) {
            return
        }
        val choosing = saveStatus as SaveStatus.ChoosingFormat
        activeDraft?.let(::updateActiveDraftStatus)
        val generation = nextSaveGeneration
        nextSaveGeneration = Math.incrementExact(nextSaveGeneration)
        pendingSourceReplacementEditTarget =
            queuedSourceReplacementEditTarget?.let { target ->
                PendingSourceReplacementEditTarget(
                    saveGeneration = generation,
                    target = target
                )
            }
        queuedSourceReplacementEditTarget = null
        val request =
            SaveDestinationRequest(
                generation = generation,
                format = format,
                suggestedName = suggestSaveDestinationName(title = title, format = format),
                purpose = choosing.purpose
            )
        activeSaveGeneration = generation
        saveStatus = SaveStatus.DestinationReady(request)
        if (isSaveBeforeClosePending) {
            isClosePending = true
        }
    }

    /** Claims one retained request immediately before launching the system picker. */
    fun claimSaveDestination(): SaveDestinationRequest? {
        val ready = saveStatus as? SaveStatus.DestinationReady ?: return null
        if (closeStarted.get() || activeSaveGeneration != ready.request.generation) {
            return null
        }
        saveStatus = SaveStatus.SelectingDestination(ready.request)
        return ready.request
    }

    /** Consumes one exact cancelled destination selection. */
    fun cancelSaveDestination(request: SaveDestinationRequest): Boolean {
        val selecting = saveStatus as? SaveStatus.SelectingDestination ?: return false
        if (selecting.request != request || !ownsSaveGeneration(request.generation)) {
            return false
        }
        cancelSaveBeforeClose()
        finishSaveSelection(request.generation, SaveStatus.Idle)
        return true
    }

    /** Claims one selected picker result before transient provider work begins. */
    fun beginSaveDestinationPreparation(request: SaveDestinationRequest): Boolean {
        val selecting = saveStatus as? SaveStatus.SelectingDestination ?: return false
        if (
            closeStarted.get() ||
            selecting.request != request ||
            !ownsSaveGeneration(request.generation)
        ) {
            return false
        }
        saveStatus = SaveStatus.PreparingDestination(request)
        return true
    }

    /** Shows cancellation while selected-destination resources are released. */
    fun beginSaveDestinationPreparationCancellation(request: SaveDestinationRequest): Boolean {
        val preparing = saveStatus as? SaveStatus.PreparingDestination ?: return false
        if (preparing.request != request || !ownsSaveGeneration(request.generation)) {
            return false
        }
        saveStatus = SaveStatus.CancellingDestinationPreparation(request)
        return true
    }

    /** Publishes cancellation only after destination preparation releases its resources. */
    fun completeSaveDestinationPreparationCancellation(request: SaveDestinationRequest): Boolean {
        val cancelling =
            saveStatus as? SaveStatus.CancellingDestinationPreparation ?: return false
        if (cancelling.request != request || !ownsSaveGeneration(request.generation)) {
            return false
        }
        finishSaveSelection(
            request.generation,
            SaveStatus.Cancelled(request)
        )
        return true
    }

    /** Reports one sanitized system-picker launch failure. */
    fun failSaveDestinationLaunch(request: SaveDestinationRequest): Boolean {
        val selecting = saveStatus as? SaveStatus.SelectingDestination ?: return false
        if (selecting.request != request || !ownsSaveGeneration(request.generation)) {
            return false
        }
        finishSaveSelection(
            request.generation,
            SaveStatus.Failed(
                request = request,
                message = UiText.Resource(R.string.operation_picker_failed)
            )
        )
        return true
    }

    /** Rejects one exact picker result that cannot be safely consumed. */
    fun rejectSaveDestination(request: SaveDestinationRequest): Boolean {
        val activeRequest =
            when (val status = saveStatus) {
                is SaveStatus.SelectingDestination -> status.request
                is SaveStatus.PreparingDestination -> status.request
                else -> return false
            }
        if (activeRequest != request || !ownsSaveGeneration(request.generation)) {
            return false
        }
        finishSaveSelection(
            request.generation,
            SaveStatus.Failed(
                request = request,
                message = UiText.Resource(R.string.operation_destination_unusable)
            )
        )
        return true
    }

    /** Reports one selected destination that could not be opened. */
    fun failSaveDestination(request: SaveDestinationRequest, message: UiText): Boolean {
        val preparing = saveStatus as? SaveStatus.PreparingDestination ?: return false
        if (preparing.request != request || !ownsSaveGeneration(request.generation)) {
            return false
        }
        finishSaveSelection(
            request.generation,
            SaveStatus.Failed(request = request, message = message)
        )
        return true
    }

    /** Saves through one selected destination without retaining its URI. */
    fun saveSelectedDestination(
        request: SaveDestinationRequest,
        destinationOwner: AutoCloseable,
        saveRevision: suspend (EditorDocumentSnapshot, Long) -> Unit
    ): Boolean {
        val preparing = saveStatus as? SaveStatus.PreparingDestination ?: return false
        if (
            closeStarted.get() ||
            preparing.request != request ||
            activeSaveGeneration != request.generation ||
            request.purpose != SaveDestinationPurpose.Copy
        ) {
            return false
        }
        if (!isDocumentReadyForSave()) {
            closeDestinationOwner(destinationOwner)
            finishSaveSelection(
                request.generation,
                SaveStatus.Failed(
                    request = request,
                    message = UiText.Resource(R.string.operation_not_ready_save)
                )
            )
            return true
        }
        saveStatus = SaveStatus.Exporting(request)
        val saveJob =
            operationScope.launch(start = CoroutineStart.LAZY) {
                saveDocument(request = request, saveRevision = saveRevision)
            }
        activeSaveJob = saveJob
        saveJob.invokeOnCompletion {
            try {
                closeDestinationOwner(destinationOwner)
            } finally {
                finishSaveJob(request.generation)
            }
        }
        saveJob.start()
        return true
    }

    /** Adopts one first destination as the source and starts its initial save. */
    fun saveSelectedSource(
        request: SaveDestinationRequest,
        documentSource: WritableEditorDocumentSource,
        sourceDisplayName: String? = null,
        sourceMetadata: SelectedDocumentMetadata? = null
    ): Boolean {
        require(
            sourceDisplayName == null ||
                sanitizeSelectedDocumentDisplayName(sourceDisplayName) == sourceDisplayName
        ) {
            "source display name is not sanitized"
        }
        require(
            sourceMetadata == null ||
                sourceDisplayName == null ||
                sourceMetadata.displayName == sourceDisplayName
        ) {
            "source metadata conflicts with its display name"
        }
        val preparing = saveStatus as? SaveStatus.PreparingDestination ?: return false
        if (
            closeStarted.get() ||
            preparing.request != request ||
            activeSaveGeneration != request.generation ||
            request.purpose != SaveDestinationPurpose.SourceReplacement ||
            sourceSaveJob != null
        ) {
            return false
        }
        if (!isDocumentReadyForSave()) {
            closeDocumentSource(documentSource)
            finishSaveSelection(
                request.generation,
                SaveStatus.Failed(
                    request = request,
                    message = UiText.Resource(R.string.operation_not_ready_save)
                )
            )
            return true
        }
        val previousDocumentSource = ownedDocumentSource
        sourceSaveEditTarget =
            pendingSourceReplacementEditTarget
                ?.takeIf { pending -> pending.saveGeneration == request.generation }
                ?.target
        pendingSourceReplacementEditTarget = null
        ownedDocumentSource = documentSource
        overwriteSourceOnNextSave = false
        sourceConflictResolution = null
        unlockEditingAfterSourceSave = isViewOnly
        val selectedMetadata =
            sourceMetadata ?: SelectedDocumentMetadata(displayName = sourceDisplayName)
        this.sourceMetadata = selectedMetadata.copy(lastModifiedEpochMillis = null)
        title = selectedMetadata.displayName ?: request.suggestedName
        retainedSourceFormat = request.format
        sourceSaveStatus = SourceSaveStatus.Pending
        requestSourceSave()
        closeDocumentSource(previousDocumentSource)
        finishSaveSelection(request.generation, SaveStatus.Idle)
        return true
    }

    /** Cancels one queued or exporting explicit save without cancelling its edit. */
    fun cancelSave() {
        when (val status = saveStatus) {
            is SaveStatus.Queued -> cancelQueuedExplicitSave()

            is SaveStatus.Exporting -> {
                saveStatus = SaveStatus.CancellingExport(status.request)
                activeSaveJob?.cancel()
            }

            else -> Unit
        }
    }

    /** Restarts one failed or cancelled save with a fresh destination selection. */
    fun restartExplicitSave(): Boolean {
        val purpose =
            when (val status = saveStatus) {
                is SaveStatus.Failed -> status.request.purpose
                is SaveStatus.Cancelled -> status.request.purpose
                else -> return false
            }
        return requestSaveFormatSelection(purpose)
    }

    /** Dismisses one exact failed or cancelled result without changing document state. */
    fun dismissExplicitSaveResult(generation: Long) {
        val resultGeneration =
            when (val status = saveStatus) {
                is SaveStatus.Failed -> status.request.generation
                is SaveStatus.Cancelled -> status.request.generation
                else -> return
            }
        if (resultGeneration == generation) {
            saveStatus = SaveStatus.Idle
            ensureSourceSave()
        }
    }

    /** Retires one matching copy-success confirmation after its accessible timeout. */
    fun retireSaveSuccess(generation: Long) {
        val succeeded = saveStatus as? SaveStatus.Succeeded ?: return
        if (succeeded.request.generation == generation) {
            saveStatus = SaveStatus.Idle
            ensureSourceSave()
        }
    }

    /** Records retained dirty status for one matching draft generation. */
    fun updateActiveDraftStatus(draft: ActiveEditDraft) {
        state.updateActiveDraftStatus(
            generation = draft.edit.generation,
            hasChanges = draft.hasChanges
        )
        if (draft.hasChanges) {
            markSaveSuccessHasNewerChanges()
        }
    }

    /** Shows the explicit confirmation required for a dirty session close. */
    fun showDiscardConfirmation() {
        isDiscardConfirmationVisible = true
    }

    /** Requests closing now or retains the request until current work becomes safe. */
    fun requestClose(): CloseRequestResult {
        if (closeStarted.get()) {
            return CloseRequestResult.CloseNow
        }
        cancelQueuedExplicitSave()
        cancelShare()
        cancelPrint()
        cancelQrShare()
        cancelNfcWrite()
        cancelPendingEditWindowAction()
        dismissSourceConflictConfirmation()
        checkpointPendingEdit()
        if (!canCloseSafely) {
            isClosePending = true
            return CloseRequestResult.Queued
        }
        return completeCloseRequest()
    }

    /** Resolves one queued close after current work reaches a safe point. */
    fun resolvePendingClose(): CloseRequestResult? {
        if (!isClosePending || !canCloseSafely) {
            return null
        }
        if (saveStatus is SaveStatus.Failed || saveStatus is SaveStatus.Cancelled) {
            isClosePending = false
            return null
        }
        return completeCloseRequest()
    }

    /** Cancels one queued close without cancelling current document work. */
    fun cancelPendingClose() {
        isClosePending = false
    }

    /** Dismisses the dirty-session close confirmation. */
    fun dismissDiscardConfirmation() {
        isDiscardConfirmationVisible = false
    }

    /** Starts a source-replacement save and closes only after provider confirmation. */
    fun saveBeforeClose(): Boolean {
        if (!canSaveBeforeClose) {
            return false
        }
        isDiscardConfirmationVisible = false
        isSaveBeforeClosePending = true
        if (!showSaveFormatSelection()) {
            cancelSaveBeforeClose()
            isDiscardConfirmationVisible = true
            return false
        }
        if (!isSaveBeforeClosePending) {
            isDiscardConfirmationVisible = true
            return false
        }
        return true
    }

    /** Checkpoints source-backed text before permanently releasing this editor. */
    suspend fun closeAfterCheckpoint() {
        if (closeStarted.get()) {
            return
        }
        checkpointPendingEdit()
        while (!closeStarted.get()) {
            val editJob = editSynchronizationJob
            if (editJob != null) {
                editJob.join()
                continue
            }
            ensureSourceSave()
            val saveJob = sourceSaveJob
            if (saveJob != null) {
                saveJob.join()
                continue
            }
            break
        }
        close()
    }

    /** Closes the document and cancels every retained operation exactly once. */
    override fun close() {
        if (!closeStarted.compareAndSet(false, true)) {
            return
        }
        activeSaveGeneration = null
        val saveJob = activeSaveJob
        activeSaveJob = null
        val documentSource = ownedDocumentSource
        ownedDocumentSource = null
        sourceMetadata = null
        isFileInfoVisible = false
        printSetupDraft = null
        val activeSourceSaveJob = sourceSaveJob
        sourceSaveJob = null
        val activeDocumentRemovalJob = documentRemovalJob
        val activeDestructiveRemovalJob =
            activeDocumentRemovalJob.takeIf {
                documentRemovalStatus is DocumentRemovalStatus.Removing
            }
        documentRemovalJob = null
        documentRemovalStatus = DocumentRemovalStatus.Idle
        val activeSharePreparationJob = sharePreparationJob
        sharePreparationJob = null
        val activePrintPreparationJob = printPreparationJob
        printPreparationJob = null
        val activeQrSharePreparationJob = qrSharePreparationJob
        qrSharePreparationJob = null
        val activeNfcWritePreparationJob = nfcWritePreparationJob
        nfcWritePreparationJob = null
        val preparedNfcEnvelope = (nfcWriteStatus as? NfcWriteStatus.Ready)?.envelope
        val preparedPrintRequest = (printStatus as? PrintStatus.Ready)?.request
        unlockEditingAfterSourceSave = false
        overwriteSourceOnNextSave = false
        sourceConflictResolution = null
        queuedSourceReplacementEditTarget = null
        pendingSourceReplacementEditTarget = null
        sourceSaveEditTarget = null
        sourceSaveStatus = SourceSaveStatus.NoSource
        shareStatus = ShareStatus.Idle
        printStatus = PrintStatus.Idle
        qrShareStatus = QrShareStatus.Idle
        nfcWriteStatus = NfcWriteStatus.Idle
        editSynchronizationJob = null
        editSynchronizationDelayJob = null
        editSynchronizationFlushRequested = false
        latestObservedDraft = null
        latestFieldValue = null
        pendingEditWindowAction = null
        pendingEditWindowScrollRestoration = null
        pendingFindAnchor = null
        pendingMarkdownPreviewReturnTarget = null
        clearSessionHistory(revision = null)
        activeFindRequestGeneration = null
        findJob = null
        activeMarkdownPreviewGeneration = null
        markdownPreviewJob = null
        presentation = EditorPresentation.Text
        markdownPreviewStatus = MarkdownPreviewStatus.Idle
        markdownPreviewReturnTarget = null
        isFindVisible = false
        findFieldValue = TextFieldValue()
        findStatus = FindStatus.Idle
        findMatch = null
        findOriginRevision = 0L
        findOriginUtf16Offset = 0L
        lastFindDirection = FindDirection.Forward
        visibleViewportAnchor = null
        readOnlySourceScrollRestoration = null
        isGoToLineDialogVisible = false
        goToLineInput = ""
        goToLineErrorMessage = null
        operationScope.cancel()
        saveJob?.cancel()
        activeSharePreparationJob?.cancel()
        activePrintPreparationJob?.cancel()
        activeQrSharePreparationJob?.cancel()
        activeNfcWritePreparationJob?.cancel()
        activeDocumentRemovalJob?.cancel()
        preparedNfcEnvelope?.close()
        check(activeSourceSaveJob == null || activeDestructiveRemovalJob == null) {
            "source save and destructive removal overlapped"
        }
        val sourceOwnerJob = activeSourceSaveJob ?: activeDestructiveRemovalJob
        sourceOwnerJob?.invokeOnCompletion {
            closeDocumentSource(documentSource)
        }
        isDiscardConfirmationVisible = false
        isClosePending = false
        isSaveBeforeClosePending = false
        saveStatus = SaveStatus.Idle
        val draft = activeDraft
        activeDraft = null
        try {
            preparedPrintRequest?.close()
        } finally {
            try {
                draft?.release()
            } finally {
                try {
                    state.close()
                } finally {
                    if (sourceOwnerJob == null) {
                        closeDocumentSource(documentSource)
                    }
                }
            }
        }
    }

    /** Launches one serialized state operation in the retained session scope. */
    private fun launchOperation(
        operation: suspend (EditorDocumentState) -> Unit,
        requestDraftFocus: Boolean = false,
        onCompletion: () -> Unit = {}
    ) {
        if (closeStarted.get()) {
            return
        }
        operationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                operation(state)
            } finally {
                if (!closeStarted.get()) {
                    reconcileActiveDraft(requestFocusForReplacement = requestDraftFocus)
                    if (state.hasDocumentChanges) {
                        markSaveSuccessHasNewerChanges()
                    }
                    try {
                        onCompletion()
                    } finally {
                        resolveQueuedExplicitSave()
                        resolveQueuedShare()
                        resolveQueuedPrint()
                        resolveQueuedQrShare()
                        resolveQueuedNfcWrite()
                        ensureSourceSave()
                    }
                } else {
                    onCompletion()
                }
            }
        }
    }

    /** Enters preview and reuses a model only when its revision is still exact. */
    private fun openMarkdownPreview(
        returnTarget: MarkdownPreviewReturnTarget? = null,
        returnToSource: Boolean = true
    ): Boolean {
        val currentMetrics = state.metrics ?: return false
        val currentRevision = currentMetrics.revision
        if (
            markdownRenderer == null ||
            closeStarted.get() ||
            isFindVisible ||
            presentation != EditorPresentation.Text ||
            isGoToLineDialogVisible ||
            state.status != EditorDocumentStatus.Ready ||
            state.activeEdit != null
        ) {
            return false
        }
        val defaultAnchor = resolveVisibleViewportAnchor()
        readOnlySourceScrollRestoration = null
        markdownPreviewReturnTarget =
            returnTarget
                ?.takeIf { target ->
                    target.viewportAnchor.revision == currentRevision &&
                        target.selection.end <= currentMetrics.utf16Length
                }
                ?: MarkdownPreviewReturnTarget(
                    viewportAnchor = defaultAnchor,
                    selection =
                        Utf16Range(
                            start = defaultAnchor.sourceUtf16Offset,
                            end = defaultAnchor.sourceUtf16Offset
                        )
                )
        previewReturnsToSource = returnToSource
        presentation = EditorPresentation.MarkdownPreview
        val ready = markdownPreviewStatus as? MarkdownPreviewStatus.Ready
        if (ready?.revision == currentRevision) {
            return true
        }
        launchMarkdownPreview()
        return true
    }

    /** Starts one cancellable isolated render for the current immutable revision. */
    private fun launchMarkdownPreview() {
        val renderer = markdownRenderer ?: return
        val revision = state.metrics?.revision ?: return
        invalidateMarkdownPreview()
        val generation = nextMarkdownPreviewGeneration
        nextMarkdownPreviewGeneration = Math.incrementExact(nextMarkdownPreviewGeneration)
        activeMarkdownPreviewGeneration = generation
        markdownPreviewStatus = MarkdownPreviewStatus.Rendering(revision)
        val operation =
            operationScope.launch(start = CoroutineStart.LAZY) {
                try {
                    val rendered = state.renderMarkdown(renderer)
                    if (
                        activeMarkdownPreviewGeneration != generation ||
                        presentation != EditorPresentation.MarkdownPreview ||
                        closeStarted.get()
                    ) {
                        return@launch
                    }
                    markdownPreviewStatus =
                        if (rendered?.revision == revision) {
                            MarkdownPreviewStatus.Ready(
                                revision = rendered.revision,
                                document = rendered.document,
                                layout = rendered.layout,
                                scrollRestoration =
                                    markdownPreviewReturnTarget
                                        ?.viewportAnchor
                                        ?.takeIf { anchor -> anchor.revision == rendered.revision }
                            )
                        } else {
                            MarkdownPreviewStatus.Failed(
                                UiText.Resource(R.string.operation_preview_revision_changed)
                            )
                        }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: MarkdownRenderException) {
                    publishMarkdownPreviewFailure(
                        generation = generation,
                        message = markdownPreviewFailureMessage(failure.failure)
                    )
                } catch (_: Exception) {
                    publishMarkdownPreviewFailure(
                        generation = generation,
                        message = MARKDOWN_PREVIEW_FAILURE_MESSAGE
                    )
                } finally {
                    if (activeMarkdownPreviewGeneration == generation) {
                        activeMarkdownPreviewGeneration = null
                        markdownPreviewJob = null
                    }
                }
            }
        markdownPreviewJob = operation
        operation.start()
    }

    /** Publishes one sanitized failure only for the active preview generation. */
    private fun publishMarkdownPreviewFailure(generation: Long, message: UiText) {
        if (
            activeMarkdownPreviewGeneration == generation &&
            presentation == EditorPresentation.MarkdownPreview &&
            !closeStarted.get()
        ) {
            markdownPreviewStatus = MarkdownPreviewStatus.Failed(message)
        }
    }

    /** Cancels and forgets any revision-bound Markdown preview model. */
    private fun invalidateMarkdownPreview() {
        activeMarkdownPreviewGeneration = null
        markdownPreviewJob?.cancel()
        markdownPreviewJob = null
        markdownPreviewStatus = MarkdownPreviewStatus.Idle
    }

    /** Opens an empty retained Find field at one synchronized document offset. */
    private fun openFind(anchorUtf16Offset: Long): Boolean {
        val currentMetrics = state.metrics ?: return false
        if (
            closeStarted.get() ||
            isFindVisible ||
            isGoToLineDialogVisible ||
            state.status != EditorDocumentStatus.Ready ||
            state.hasActiveDraftChanges ||
            anchorUtf16Offset !in 0L..currentMetrics.utf16Length
        ) {
            return false
        }
        invalidateFindRequest()
        findOriginRevision = currentMetrics.revision
        findOriginUtf16Offset = anchorUtf16Offset
        findFieldValue = TextFieldValue()
        findStatus = FindStatus.Idle
        findMatch = null
        // Opening Find transfers input intent even if a window transition prevents
        // the editor's focus-loss callback from clearing its restoration request.
        activeDraft?.updateEditorFocusIntent(isFocused = false, canClear = true)
        isFindVisible = true
        return true
    }

    /** Returns the freshest semantic viewport point belonging to the current revision. */
    private fun resolveVisibleViewportAnchor(): SemanticViewportAnchor {
        val currentMetrics = state.metrics
            ?: return SemanticViewportAnchor(
                revision = 0L,
                sourceUtf16Offset = 0L,
                viewportTopOffsetPixels = 0
            )
        val fallback =
            state.blocks.firstOrNull()?.block?.globalUtf16Start
                ?.coerceIn(0L, currentMetrics.utf16Length) ?: 0L
        return resolveVisibleViewportAnchor(currentMetrics.revision, fallback)
    }

    /** Resolves one revision-bound viewport point with an explicit source fallback. */
    private fun resolveVisibleViewportAnchor(
        revision: Long,
        fallbackUtf16Offset: Long
    ): SemanticViewportAnchor {
        require(revision >= 0L) { "semantic viewport revision must be nonnegative" }
        require(fallbackUtf16Offset >= 0L) {
            "semantic viewport fallback must be nonnegative"
        }
        val currentMetrics = state.metrics
        val maximumOffset =
            currentMetrics
                ?.takeIf { metrics -> metrics.revision == revision }
                ?.utf16Length ?: fallbackUtf16Offset
        val visibleAnchor = visibleViewportAnchor
        if (
            visibleAnchor != null &&
            visibleAnchor.revision == revision &&
            visibleAnchor.sourceUtf16Offset <= maximumOffset
        ) {
            return visibleAnchor
        }
        return SemanticViewportAnchor(
            revision = revision,
            sourceUtf16Offset = fallbackUtf16Offset.coerceAtMost(maximumOffset),
            viewportTopOffsetPixels = 0
        )
    }

    /** Captures the editor position that a successful source replacement must resume. */
    private fun captureSourceReplacementEditTarget(
        draft: ActiveEditDraft?
    ): SourceReplacementEditTarget {
        if (presentation == EditorPresentation.MarkdownPreview) {
            markdownPreviewReturnTarget?.takeIf {
                it.viewportAnchor.revision == state.metrics?.revision
            }?.let { return SourceReplacementEditTarget(it.selection) }
        }
        val selection =
            if (draft == null) {
                val visibleOffset = resolveVisibleViewportAnchor().sourceUtf16Offset
                Utf16Range(start = visibleOffset, end = visibleOffset)
            } else {
                val localSelection = draft.textFieldState.selection
                val globalStart =
                    Math.addExact(
                        draft.edit.snapshot.range.start,
                        localSelection.min.toLong()
                    )
                val globalEnd =
                    Math.addExact(
                        draft.edit.snapshot.range.start,
                        localSelection.max.toLong()
                    )
                Utf16Range(start = globalStart, end = globalEnd)
            }
        return SourceReplacementEditTarget(selection)
    }

    /** Cancels one superseded Find generation without publishing its result. */
    private fun invalidateFindRequest() {
        activeFindRequestGeneration = null
        findJob?.cancel()
        findJob = null
    }

    /** Starts one delayed or immediate circular literal-text traversal. */
    private fun launchFind(direction: FindDirection, delayed: Boolean): Boolean {
        val query = findFieldValue.text
        val matchCase = isFindCaseSensitive
        if (
            !isFindVisible ||
            query.isEmpty() ||
            closeStarted.get() ||
            isClosePending ||
            isSaveBusy ||
            !state.canQueueFind
        ) {
            return false
        }
        invalidateFindRequest()
        val generation = nextFindRequestGeneration
        nextFindRequestGeneration = Math.incrementExact(nextFindRequestGeneration)
        activeFindRequestGeneration = generation
        lastFindDirection = direction
        findStatus = FindStatus.Searching
        val operation =
            operationScope.launch(start = CoroutineStart.LAZY) {
                try {
                    if (delayed) {
                        findDelay()
                    }
                    performFind(
                        generation = generation,
                        query = query,
                        matchCase = matchCase,
                        direction = direction
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } finally {
                    if (activeFindRequestGeneration == generation) {
                        activeFindRequestGeneration = null
                        findJob = null
                    }
                }
            }
        findJob = operation
        operation.start()
        return true
    }

    /** Traverses at most one circular revision and publishes only its owned generation. */
    private suspend fun performFind(
        generation: Long,
        query: String,
        matchCase: Boolean,
        direction: FindDirection
    ) {
        currentCoroutineContext().ensureActive()
        if (!ownsFindRequest(generation = generation, query = query, matchCase = matchCase)) {
            return
        }
        val currentMetrics = state.metrics ?: return publishFindUnavailable(generation)
        if (findOriginRevision != currentMetrics.revision) {
            findOriginRevision = currentMetrics.revision
            findOriginUtf16Offset = resolveVisibleViewportAnchor().sourceUtf16Offset
            findMatch = null
        } else if (findMatch?.start?.revision != currentMetrics.revision) {
            findMatch = null
        }
        val anchor = findAnchor(direction = direction, query = query, currentMetrics.utf16Length)
        val phases =
            findCandidatePhases(
                direction = direction,
                anchorUtf16Offset = anchor,
                documentUtf16Length = currentMetrics.utf16Length
            )
        for (phase in phases) {
            when (
                val phaseResult =
                    searchFindPhase(
                        generation = generation,
                        query = query,
                        matchCase = matchCase,
                        revision = currentMetrics.revision,
                        direction = direction,
                        initialRange = phase.range
                    )
            ) {
                FindPhaseResult.Exhausted -> Unit

                FindPhaseResult.Unavailable -> {
                    publishFindUnavailable(generation)
                    return
                }

                is FindPhaseResult.Failed -> {
                    publishFindFailure(generation, phaseResult.message)
                    return
                }

                is FindPhaseResult.Matched -> {
                    if (state.activeEdit == null) {
                        when (val navigation = state.navigateToMatch(phaseResult.match)) {
                            MatchViewportResult.Published -> {
                                if (
                                    publishFindMatch(
                                        generation = generation,
                                        query = query,
                                        matchCase = matchCase,
                                        match = phaseResult.match,
                                        wrappedAt = phase.wrappedAt
                                    )
                                ) {
                                    viewportListState.requestScrollToItem(
                                        FIRST_VIEWPORT_ITEM_INDEX
                                    )
                                }
                            }

                            MatchViewportResult.Unavailable -> publishFindUnavailable(generation)

                            is MatchViewportResult.Failed ->
                                publishFindFailure(generation, navigation.message)
                        }
                    } else {
                        when (
                            val navigation =
                                state.navigateActiveEditToMatch(phaseResult.match)
                        ) {
                            ActiveEditNavigationResult.Published -> {
                                pendingEditWindowScrollRestoration =
                                    EditWindowScrollRestoration(
                                        anchor =
                                            SemanticViewportAnchor(
                                                revision =
                                                    phaseResult.match.start.revision,
                                                sourceUtf16Offset =
                                                    phaseResult.match.range.start,
                                                viewportTopOffsetPixels = 0
                                            )
                                    )
                                reconcileActiveDraft()
                                publishFindMatch(
                                    generation = generation,
                                    query = query,
                                    matchCase = matchCase,
                                    match = phaseResult.match,
                                    wrappedAt = phase.wrappedAt
                                )
                            }

                            ActiveEditNavigationResult.Unavailable ->
                                publishFindUnavailable(generation)

                            is ActiveEditNavigationResult.Failed ->
                                publishFindFailure(generation, navigation.message)
                        }
                    }
                    return
                }
            }
        }
        if (ownsFindRequest(generation = generation, query = query, matchCase = matchCase)) {
            findMatch = null
            findStatus = FindStatus.NoMatches
        }
    }

    /** Publishes one exact owned Find result after its destination becomes visible. */
    private fun publishFindMatch(
        generation: Long,
        query: String,
        matchCase: Boolean,
        match: FindMatch,
        wrappedAt: FindWrap?
    ): Boolean {
        if (!ownsFindRequest(generation = generation, query = query, matchCase = matchCase)) {
            return false
        }
        findMatch = match
        findStatus = FindStatus.Match(match = match, wrappedAt = wrappedAt)
        return true
    }

    /** Exhausts one candidate phase through strictly shrinking bounded native batches. */
    private suspend fun searchFindPhase(
        generation: Long,
        query: String,
        matchCase: Boolean,
        revision: Long,
        direction: FindDirection,
        initialRange: Utf16Range
    ): FindPhaseResult {
        var candidateRange = initialRange
        while (candidateRange.start < candidateRange.end) {
            currentCoroutineContext().ensureActive()
            if (!ownsFindRequest(generation = generation, query = query, matchCase = matchCase)) {
                return FindPhaseResult.Unavailable
            }
            val request =
                FindRequest(
                    revision = revision,
                    query = query,
                    matchCase = matchCase,
                    candidateRange = candidateRange,
                    direction = direction,
                    maxCandidateUtf16Units = MAX_FIND_CANDIDATE_UTF16_UNITS
                )
            when (val result = state.findBatch(request)) {
                FindBatchResult.Unavailable -> return FindPhaseResult.Unavailable

                is FindBatchResult.Failed -> return FindPhaseResult.Failed(result.message)

                is FindBatchResult.Batch -> {
                    val match = result.batch.match
                    if (match != null) {
                        return FindPhaseResult.Matched(match)
                    }
                    val remaining = result.batch.remainingCandidateRange
                        ?: return FindPhaseResult.Exhausted
                    candidateRange = remaining
                }
            }
        }
        return FindPhaseResult.Exhausted
    }

    /** Returns the first candidate offset for this query and direction. */
    private fun findAnchor(direction: FindDirection, query: String, documentLength: Long): Long {
        val currentMatch = findMatch
        if (currentMatch == null) {
            return findOriginUtf16Offset.coerceIn(0L, documentLength)
        }
        return when (direction) {
            FindDirection.Forward ->
                Math.addExact(
                    currentMatch.range.start,
                    (
                        state.findMatchStartScalarUtf16Units(currentMatch)
                            ?: Character.charCount(query.codePointAt(0))
                        ).toLong()
                ).coerceAtMost(documentLength)

            FindDirection.Backward -> currentMatch.range.start
        }
    }

    /** Returns the ordered nonempty phases for one circular Find traversal. */
    private fun findCandidatePhases(
        direction: FindDirection,
        anchorUtf16Offset: Long,
        documentUtf16Length: Long
    ): List<FindCandidatePhase> {
        require(anchorUtf16Offset in 0L..documentUtf16Length) {
            "find anchor must belong to the document"
        }
        return buildList(capacity = 2) {
            when (direction) {
                FindDirection.Forward -> {
                    if (anchorUtf16Offset < documentUtf16Length) {
                        add(
                            FindCandidatePhase(
                                range = Utf16Range(anchorUtf16Offset, documentUtf16Length),
                                wrappedAt = null
                            )
                        )
                    }
                    if (anchorUtf16Offset > 0L) {
                        add(
                            FindCandidatePhase(
                                range = Utf16Range(0L, anchorUtf16Offset),
                                wrappedAt = FindWrap.Beginning
                            )
                        )
                    }
                }

                FindDirection.Backward -> {
                    if (anchorUtf16Offset > 0L) {
                        add(
                            FindCandidatePhase(
                                range = Utf16Range(0L, anchorUtf16Offset),
                                wrappedAt = null
                            )
                        )
                    }
                    if (anchorUtf16Offset < documentUtf16Length) {
                        add(
                            FindCandidatePhase(
                                range = Utf16Range(anchorUtf16Offset, documentUtf16Length),
                                wrappedAt = FindWrap.End
                            )
                        )
                    }
                }
            }
        }
    }

    /** Returns whether one Find generation still owns publication. */
    private fun ownsFindRequest(generation: Long, query: String, matchCase: Boolean): Boolean =
        !closeStarted.get() &&
            isFindVisible &&
            activeFindRequestGeneration == generation &&
            findFieldValue.text == query &&
            isFindCaseSensitive == matchCase

    /** Publishes one sanitized unavailable result only for its exact generation. */
    private fun publishFindUnavailable(generation: Long) {
        if (activeFindRequestGeneration == generation && isFindVisible) {
            if (findMatch?.start?.revision != state.metrics?.revision) {
                findMatch = null
            }
            findStatus = FindStatus.Failed(UiText.Resource(R.string.operation_find_interrupted))
        }
    }

    /** Publishes one sanitized Find failure only for its exact generation. */
    private fun publishFindFailure(generation: Long, message: UiText) {
        if (activeFindRequestGeneration == generation && isFindVisible) {
            if (findMatch?.start?.revision != state.metrics?.revision) {
                findMatch = null
            }
            findStatus = FindStatus.Failed(message)
        }
    }

    /** Starts one random-line load and moves only after successful publication. */
    private fun launchLineNavigation(logicalLine: Long) {
        require(logicalLine >= 0L) { "logical line must be nonnegative" }
        if (closeStarted.get()) {
            return
        }
        operationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (state.navigateToLine(logicalLine) && !closeStarted.get()) {
                viewportListState.requestScrollToItem(FIRST_VIEWPORT_ITEM_INDEX)
            }
        }
    }

    /** Forces the current non-composing draft past its debounce when possible. */
    private fun requestImmediateEditSynchronization(draft: ActiveEditDraft) {
        if (
            closeStarted.get() ||
            activeDraft !== draft ||
            !draft.hasChanges ||
            state.status == EditorDocumentStatus.ApplyingEdit ||
            !state.canAcceptActiveDraftInput(draft.edit.generation)
        ) {
            return
        }
        editSynchronizationFlushRequested = true
        editSynchronizationDelayJob?.cancel()
        ensureEditSynchronization()
    }

    /** Starts one delay-aware, conflated edit coordinator when none is active. */
    private fun ensureEditSynchronization() {
        if (closeStarted.get() || editSynchronizationJob != null) {
            return
        }
        val synchronizationJob =
            operationScope.launch(start = CoroutineStart.LAZY) {
                synchronizeActiveEditChanges()
            }
        editSynchronizationJob = synchronizationJob
        synchronizationJob.start()
    }

    /** Synchronizes the newest bounded field value without cancelling native mutations. */
    private suspend fun synchronizeActiveEditChanges() {
        var delayBeforeSynchronization = true
        var processedObservationVersion = -1L
        try {
            while (!closeStarted.get()) {
                val draft = activeDraft ?: return
                if (delayBeforeSynchronization) {
                    val versionBeforeDelay = editObservationVersion
                    val flushRequested = awaitEditSynchronizationDelay()
                    if (!flushRequested && versionBeforeDelay != editObservationVersion) {
                        continue
                    }
                }

                val submissionVersion = editObservationVersion
                val fieldValue =
                    if (latestObservedDraft === draft) {
                        checkNotNull(latestFieldValue)
                    } else {
                        draft.captureFieldValue()
                    }
                if (fieldValue.composition != null) {
                    processedObservationVersion = submissionVersion
                    return
                }
                val selection = fieldValue.selection
                val selectionBefore = draft.historySelectionBefore()
                val result =
                    state.commitActiveEdit(
                        generation = draft.edit.generation,
                        text = fieldValue.text,
                        selection =
                            Utf16Range(
                                start = selection.min.toLong(),
                                end = selection.max.toLong()
                            ),
                        selectionBefore =
                            Utf16Range(
                                start = selectionBefore.min.toLong(),
                                end = selectionBefore.max.toLong()
                            )
                    )
                processedObservationVersion = submissionVersion
                if (result == EditSynchronizationResult.RejectedBySizeLimit) {
                    if (draft.rejectSubmittedChange(fieldValue)) {
                        latestObservedDraft = null
                        latestFieldValue = null
                    }
                    isClosePending = false
                }
                reconcileActiveDraft()
                val currentDraft = activeDraft
                if (currentDraft != null) {
                    updateActiveDraftStatus(currentDraft)
                }
                if (result is EditSynchronizationResult.Applied) {
                    recordCommittedEdit(result.delta)
                    invalidateMarkdownPreview()
                    recordSourceSaveRequest()
                }
                if (
                    result == EditSynchronizationResult.RejectedBySizeLimit ||
                    result == EditSynchronizationResult.Failed ||
                    result == EditSynchronizationResult.Unavailable
                ) {
                    cancelWaitingEditWindowAction(draft.edit.generation)
                    cancelQueuedExplicitSave(draft.edit.generation)
                    failQueuedShare(draft.edit.generation)
                    failQueuedPrint(draft.edit.generation)
                    failQueuedQrShare(draft.edit.generation)
                    failQueuedNfcWrite(draft.edit.generation)
                    return
                }
                if (currentDraft !== draft) {
                    cancelWaitingEditWindowAction(draft.edit.generation)
                    cancelQueuedExplicitSave(draft.edit.generation)
                    failQueuedShare(draft.edit.generation)
                    failQueuedPrint(draft.edit.generation)
                    failQueuedQrShare(draft.edit.generation)
                    failQueuedNfcWrite(draft.edit.generation)
                    return
                }
                if (!currentDraft.hasChanges) {
                    resolvePendingEditWindowAction()
                    resolveQueuedExplicitSave()
                    resolveQueuedShare()
                    resolveQueuedPrint()
                    resolveQueuedQrShare()
                    resolveQueuedNfcWrite()
                    ensureSourceSave()
                    return
                }
                ensureSourceSave()
                if (editObservationVersion == submissionVersion) {
                    return
                }
                delayBeforeSynchronization = false
            }
        } finally {
            editSynchronizationJob = null
            val currentDraft = activeDraft
            if (
                !closeStarted.get() &&
                currentDraft?.hasChanges == true &&
                editObservationVersion != processedObservationVersion &&
                state.status == EditorDocumentStatus.Ready
            ) {
                ensureEditSynchronization()
            }
        }
    }

    /** Waits for the debounce child or consumes one explicit flush request. */
    private suspend fun awaitEditSynchronizationDelay(): Boolean {
        if (editSynchronizationFlushRequested) {
            editSynchronizationFlushRequested = false
            return true
        }
        lateinit var delayJob: Job
        coroutineScope {
            delayJob = launch(start = CoroutineStart.LAZY) { editSynchronizationDelay() }
            editSynchronizationDelayJob = delayJob
            delayJob.start()
            try {
                delayJob.join()
            } finally {
                if (editSynchronizationDelayJob === delayJob) {
                    editSynchronizationDelayJob = null
                }
            }
        }
        return editSynchronizationFlushRequested.also {
            editSynchronizationFlushRequested = false
        }
    }

    /** Saves one serialized revision and publishes only current-generation status. */
    private suspend fun saveDocument(
        request: SaveDestinationRequest,
        saveRevision: suspend (EditorDocumentSnapshot, Long) -> Unit
    ) {
        try {
            val savedRevision = state.saveDocument(
                purpose = DocumentSavePurpose.Copy,
                saveRevision = saveRevision
            )
            if (savedRevision == null) {
                publishSaveFailure(request, UiText.Resource(R.string.operation_not_ready_save))
                return
            }
            if (
                ownsSaveGeneration(request.generation) &&
                (saveStatus is SaveStatus.Exporting || saveStatus is SaveStatus.CancellingExport)
            ) {
                saveStatus =
                    SaveStatus.Succeeded(
                        request = request,
                        hasNewerChanges =
                            state.metrics?.revision != savedRevision ||
                                activeDraft?.hasChanges == true
                    )
            }
        } catch (cancellation: CancellationException) {
            publishSaveCancellation(request)
            throw cancellation
        } catch (failure: DocumentExportException) {
            publishSaveFailure(request, failure.failure.userMessage)
        } catch (_: Exception) {
            publishSaveFailure(
                request,
                UiText.Resource(R.string.operation_save_failed)
            )
        }
    }

    /** Publishes one sanitized failure only for the active export generation. */
    private fun publishSaveFailure(request: SaveDestinationRequest, message: UiText) {
        if (
            activeExportRequest() == request &&
            ownsSaveGeneration(request.generation)
        ) {
            saveStatus = SaveStatus.Failed(request = request, message = message)
        }
    }

    /** Publishes the provider caveat after user-requested cancellation. */
    private fun publishSaveCancellation(request: SaveDestinationRequest) {
        if (
            activeExportRequest() == request &&
            ownsSaveGeneration(request.generation)
        ) {
            saveStatus = SaveStatus.Cancelled(request)
        }
    }

    /** Releases one picker-only generation without an export job. */
    private fun finishSaveSelection(generation: Long, terminalStatus: SaveStatus) {
        if (!ownsSaveGeneration(generation)) {
            return
        }
        if (pendingSourceReplacementEditTarget?.saveGeneration == generation) {
            pendingSourceReplacementEditTarget = null
        }
        activeSaveGeneration = null
        saveStatus = terminalStatus
        if (terminalStatus is SaveStatus.Failed || terminalStatus is SaveStatus.Cancelled) {
            cancelSaveBeforeClose()
        }
        ensureSourceSave()
    }

    /** Releases one export slot after its cancellation cleanup or terminal result. */
    private fun finishSaveJob(generation: Long) {
        if (activeSaveGeneration != generation) {
            return
        }
        activeSaveGeneration = null
        activeSaveJob = null
        if (saveStatus is SaveStatus.Exporting || saveStatus is SaveStatus.CancellingExport) {
            saveStatus = SaveStatus.Idle
        }
        ensureSourceSave()
    }

    /** Records the newest source-save request without cancelling active output. */
    private fun requestSourceSave() {
        recordSourceSaveRequest()
        ensureSourceSave()
    }

    /** Records the newest source revision without starting ahead of queued navigation. */
    private fun recordSourceSaveRequest() {
        if (closeStarted.get() || ownedDocumentSource !is WritableEditorDocumentSource) {
            return
        }
        sourceSaveRequestVersion = Math.incrementExact(sourceSaveRequestVersion)
        sourceMetadata
            ?.takeIf { metadata -> metadata.lastModifiedEpochMillis != null }
            ?.let { metadata ->
                sourceMetadata = metadata.copy(lastModifiedEpochMillis = null)
            }
        if (!sourceSaveStatus.isTerminalFailure() && sourceSaveJob == null) {
            sourceSaveStatus = SourceSaveStatus.Pending
        }
    }

    /** Starts one source save when no conflicting document operation owns the slot. */
    private fun ensureSourceSave() {
        val documentSource = ownedDocumentSource as? WritableEditorDocumentSource
        if (
            closeStarted.get() ||
            documentSource == null ||
            sourceSaveStatus.isTerminalFailure() ||
            sourceSaveJob != null
        ) {
            return
        }
        if (
            sourceSaveStatus == SourceSaveStatus.Saved &&
            state.hasDocumentChanges &&
            isDocumentStableForSave()
        ) {
            sourceSaveRequestVersion = Math.incrementExact(sourceSaveRequestVersion)
            sourceSaveStatus = SourceSaveStatus.Pending
        }
        if (sourceSaveStatus != SourceSaveStatus.Pending) {
            return
        }
        if (
            state.metrics?.serializedByteLength?.let { bytes ->
                bytes > ExportProtocol.MAX_BYTE_LIMIT
            } == true
        ) {
            overwriteSourceOnNextSave = false
            publishSourceSaveFailure(SOURCE_SAVE_TOO_LARGE_MESSAGE)
            return
        }
        if (isSaveBusy || !isDocumentReadyForSave()) {
            return
        }
        val requestedVersion = sourceSaveRequestVersion
        val overwriteSource = overwriteSourceOnNextSave
        if (overwriteSource && documentSource !is ConflictRecoverableEditorDocumentSource) {
            overwriteSourceOnNextSave = false
            publishSourceSaveConflict(DocumentExportFailure.SOURCE_CONFLICT.userMessage)
            return
        }
        overwriteSourceOnNextSave = false
        sourceSaveStatus = SourceSaveStatus.Saving
        lateinit var saveJob: Job
        saveJob =
            operationScope.launch(start = CoroutineStart.LAZY) {
                saveSourceRevision(
                    documentSource = documentSource,
                    requestedVersion = requestedVersion,
                    overwriteSource = overwriteSource
                )
            }
        sourceSaveJob = saveJob
        saveJob.invokeOnCompletion {
            finishSourceSave(saveJob)
        }
        saveJob.start()
    }

    /** Saves one captured source revision and leaves newer requests pending. */
    private suspend fun saveSourceRevision(
        documentSource: WritableEditorDocumentSource,
        requestedVersion: Long,
        overwriteSource: Boolean
    ) {
        try {
            val saveRevision =
                if (overwriteSource) {
                    val recoverableSource =
                        documentSource as? ConflictRecoverableEditorDocumentSource
                            ?: error("source overwrite capability changed")
                    recoverableSource::overwriteRevision
                } else {
                    documentSource::saveRevision
                }
            val savedRevision =
                state.saveDocument(
                    purpose = DocumentSavePurpose.Source,
                    saveRevision = saveRevision
                )
            if (savedRevision == null) {
                if (canPublishSourceSave(documentSource)) {
                    overwriteSourceOnNextSave = overwriteSource
                    sourceSaveStatus = SourceSaveStatus.Pending
                }
                return
            }
            if (canPublishSourceSave(documentSource)) {
                val completedStatus =
                    if (
                        requestedVersion == sourceSaveRequestVersion &&
                        !state.hasDocumentChanges
                    ) {
                        SourceSaveStatus.Saved
                    } else {
                        SourceSaveStatus.Pending
                    }
                sourceSaveStatus = completedStatus
                if (
                    completedStatus == SourceSaveStatus.Saved &&
                    unlockEditingAfterSourceSave
                ) {
                    unlockEditingAfterSourceSave = false
                    isViewOnly = false
                }
                if (completedStatus == SourceSaveStatus.Saved) {
                    sourceSaveEditTarget?.let { target ->
                        sourceSaveEditTarget = null
                        restoreEditingAfterSourceReplacement(target)
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: DocumentExportException) {
            if (!canPublishSourceSave(documentSource)) {
                return
            }
            when (failure.failure) {
                DocumentExportFailure.SOURCE_CONFLICT ->
                    publishSourceSaveConflict(failure.failure.userMessage)

                DocumentExportFailure.SOURCE_UNCERTAIN ->
                    publishSourceSaveUncertainty(failure.failure.userMessage)

                DocumentExportFailure.TOO_LARGE ->
                    publishSourceSaveFailure(
                        message =
                            if (overwriteSource) {
                                SOURCE_OVERWRITE_TARGET_TOO_LARGE_MESSAGE
                            } else {
                                SOURCE_SAVE_TOO_LARGE_MESSAGE
                            },
                        retryOverwrite = overwriteSource
                    )

                else ->
                    publishSourceSaveFailure(
                        message = SOURCE_SAVE_FAILURE_MESSAGE,
                        retryOverwrite = overwriteSource
                    )
            }
        } catch (_: Exception) {
            if (canPublishSourceSave(documentSource)) {
                publishSourceSaveFailure(
                    message = SOURCE_SAVE_FAILURE_MESSAGE,
                    retryOverwrite = overwriteSource
                )
            }
        } catch (_: LinkageError) {
            if (canPublishSourceSave(documentSource)) {
                publishSourceSaveFailure(
                    message = SOURCE_SAVE_FAILURE_MESSAGE,
                    retryOverwrite = overwriteSource
                )
            }
        }
    }

    /** Returns whether one completed write still belongs to the live source session. */
    private fun canPublishSourceSave(documentSource: WritableEditorDocumentSource): Boolean =
        !closeStarted.get() && ownedDocumentSource === documentSource

    /** Restores editing and cursor intent after the replacement source is verified. */
    private suspend fun restoreEditingAfterSourceReplacement(target: SourceReplacementEditTarget) {
        if (closeStarted.get() || isClosePending) {
            return
        }
        presentation = EditorPresentation.Text
        reconcileActiveDraft(requestFocusForReplacement = true)
        var draft = activeDraft
        if (draft == null) {
            val utf16Length = state.metrics?.utf16Length ?: return
            val selection =
                Utf16Range(
                    start = target.selection.start.coerceAtMost(utf16Length),
                    end = target.selection.end.coerceAtMost(utf16Length)
                )
            state.activateDocumentAt(selection)
            reconcileActiveDraft(requestFocusForReplacement = true)
            draft = activeDraft
        }
        draft?.requestEditorFocusRestoration()
    }

    /** Releases one completed source-save job and starts the newest pending request. */
    private fun finishSourceSave(completedJob: Job) {
        if (sourceSaveJob !== completedJob) {
            return
        }
        sourceSaveJob = null
        resolveQueuedExplicitSave()
        resolveQueuedShare()
        resolveQueuedPrint()
        resolveQueuedQrShare()
        resolveQueuedNfcWrite()
        if (
            !closeStarted.get() &&
            !sourceSaveStatus.isTerminalFailure() &&
            sourceSaveStatus == SourceSaveStatus.Pending
        ) {
            ensureSourceSave()
        }
        if (
            isSaveBeforeClosePending &&
            sourceSaveJob == null &&
            sourceSaveStatus == SourceSaveStatus.Saved &&
            !hasUnsavedChanges
        ) {
            isSaveBeforeClosePending = false
        }
        signalPendingCloseResolution()
    }

    /** Notifies the UI that retained close readiness may have changed. */
    private fun signalPendingCloseResolution() {
        if (isClosePending) {
            closeResolutionVersion = Math.incrementExact(closeResolutionVersion)
        }
    }

    /** Latches one sanitized source failure until the user explicitly retries. */
    private fun publishSourceSaveFailure(message: UiText, retryOverwrite: Boolean = false) {
        overwriteSourceOnNextSave = retryOverwrite
        sourceConflictResolution = null
        sourceSaveStatus = SourceSaveStatus.Failed(message)
        cancelSaveBeforeClose()
    }

    /** Latches one external source conflict without offering a destructive retry. */
    private fun publishSourceSaveConflict(message: UiText) {
        overwriteSourceOnNextSave = false
        sourceConflictResolution = null
        sourceSaveStatus = SourceSaveStatus.Conflict(message)
        cancelSaveBeforeClose()
    }

    /** Latches one unverifiable write without offering an unconditional retry. */
    private fun publishSourceSaveUncertainty(message: UiText) {
        overwriteSourceOnNextSave = false
        sourceConflictResolution = null
        sourceSaveStatus = SourceSaveStatus.Uncertain(message)
        cancelSaveBeforeClose()
    }

    /** Closes one descriptor-only destination owner without blocking state cleanup. */
    private fun closeDestinationOwner(destinationOwner: AutoCloseable) {
        try {
            destinationOwner.close()
        } catch (_: Exception) {
            // Destination ownership has already ended.
        }
    }

    /** Closes one retained source owner without masking document cleanup. */
    private fun closeDocumentSource(documentSource: EditorDocumentSource?) {
        try {
            documentSource?.close()
        } catch (_: Exception) {
            // Source ownership has already ended.
        }
    }

    /** Returns exact transfer usage after the active field matches its native revision. */
    private fun transferCapacity(maxTextBytes: Long): DocumentTransferCapacity {
        require(maxTextBytes > 0L) { "transfer text byte limit must be positive" }
        val textBytes =
            if (activeDraft?.hasChanges == true) {
                null
            } else {
                state.metrics?.serializedByteLength
            }
        return DocumentTransferCapacity(
            textBytes = textBytes,
            maxTextBytes = maxTextBytes
        )
    }

    /** Returns whether the current document can produce one complete revision. */
    private fun isDocumentReadyForSave(): Boolean = !closeStarted.get() &&
        isDocumentStableForSave() &&
        state.metrics?.serializedByteLength?.let { bytes ->
            bytes <= ExportProtocol.MAX_BYTE_LIMIT
        } == true

    /** Returns whether one explicit save request can be retained until text settles. */
    private fun canAcceptExplicitSaveRequest(): Boolean {
        if (isSourceReloading) {
            return false
        }
        val metrics = state.metrics ?: return false
        val draft = activeDraft
        if (
            state.status != EditorDocumentStatus.Ready &&
            state.status != EditorDocumentStatus.ApplyingEdit
        ) {
            return false
        }
        if (
            draft != null &&
            !state.canAcceptActiveDraftInput(draft.edit.generation)
        ) {
            return false
        }
        return metrics.serializedByteLength <= ExportProtocol.MAX_BYTE_LIMIT ||
            draft?.hasChanges == true
    }

    /** Returns whether a source conflict can accept a new explicit recovery choice. */
    private fun canRequestSourceConflictResolution(): Boolean =
        sourceConflictResolution == null && hasAvailableSourceConflict()

    /** Returns whether the retained editor still owns one idle source conflict. */
    private fun hasAvailableSourceConflict(): Boolean = !closeStarted.get() &&
        !isClosePending &&
        !isDiscardConfirmationVisible &&
        !isSaveBusy &&
        !isShareBusy &&
        !isPrintBusy &&
        !isQrShareBusy &&
        !isNfcWriteBusy &&
        sourceSaveJob == null &&
        sourceSaveStatus is SourceSaveStatus.Conflict

    /** Opens retained format selection after its exact draft and source write settle. */
    private fun resolveQueuedExplicitSave() {
        val queued = saveStatus as? SaveStatus.Queued ?: return
        if (closeStarted.get() || pendingEditWindowAction != null) {
            cancelQueuedExplicitSave()
            return
        }
        if (state.status == EditorDocumentStatus.ApplyingEdit) {
            return
        }
        val metrics = state.metrics
        if (
            state.status != EditorDocumentStatus.Ready ||
            metrics == null ||
            metrics.serializedByteLength > ExportProtocol.MAX_BYTE_LIMIT
        ) {
            cancelQueuedExplicitSave()
            return
        }
        val draftGeneration = queued.draftGeneration
        if (draftGeneration != null) {
            val draft = activeDraft
            if (draft?.edit?.generation != draftGeneration) {
                cancelQueuedExplicitSave()
                return
            }
            val fieldValue =
                if (latestObservedDraft === draft) {
                    checkNotNull(latestFieldValue)
                } else {
                    draft.captureFieldValue()
                }
            if (fieldValue.composition != null || draft.hasChanges) {
                return
            }
        } else if (activeDraft != null) {
            cancelQueuedExplicitSave()
            return
        }
        if (sourceSaveJob != null) {
            return
        }
        saveStatus = SaveStatus.ChoosingFormat(queued.purpose)
    }

    /** Resolves one share only after its exact draft and source revision are stable. */
    private fun resolveQueuedShare() {
        val queued = shareStatus as? ShareStatus.Queued ?: return
        if (closeStarted.get() || pendingEditWindowAction != null) {
            publishShareFailure(queued.generation, SHARE_PREPARATION_FAILURE_MESSAGE)
            return
        }
        if (state.status == EditorDocumentStatus.ApplyingEdit) {
            return
        }
        val metrics = state.metrics
        if (state.status != EditorDocumentStatus.Ready || metrics == null) {
            publishShareFailure(queued.generation, SHARE_PREPARATION_FAILURE_MESSAGE)
            return
        }
        val draftGeneration = queued.draftGeneration
        if (draftGeneration != null) {
            val draft = activeDraft
            if (draft?.edit?.generation != draftGeneration) {
                publishShareFailure(queued.generation, SHARE_PREPARATION_FAILURE_MESSAGE)
                return
            }
            val fieldValue =
                if (latestObservedDraft === draft) {
                    checkNotNull(latestFieldValue)
                } else {
                    draft.captureFieldValue()
                }
            if (fieldValue.composition != null || draft.hasChanges) {
                return
            }
        } else if (activeDraft != null) {
            publishShareFailure(queued.generation, SHARE_PREPARATION_FAILURE_MESSAGE)
            return
        }

        val documentSource = ownedDocumentSource
        if (documentSource != null) {
            if (sourceSaveStatus.isTerminalFailure()) {
                publishShareFailure(queued.generation, SHARE_SOURCE_FAILURE_MESSAGE)
                return
            }
            if (
                documentSource is WritableEditorDocumentSource &&
                (state.hasDocumentChanges || isSourceSaveBusy || sourceSaveJob != null)
            ) {
                ensureSourceSave()
                return
            }
            val encodedUri = documentSource.encodedShareUri()
            if (encodedUri.isNullOrBlank()) {
                publishShareFailure(queued.generation, SHARE_PREPARATION_FAILURE_MESSAGE)
                return
            }
            shareStatus =
                ShareStatus.Ready(
                    DocumentShareRequest(
                        generation = queued.generation,
                        payload =
                            DocumentSharePayload.Source(
                                encodedUri = encodedUri,
                                title = title,
                                format = documentFormat
                            )
                    )
                )
            return
        }

        if (metrics.serializedByteLength > MAX_SHARED_TEXT_UTF8_BYTES) {
            publishShareFailure(queued.generation, SHARE_TEXT_TOO_LARGE_MESSAGE)
            return
        }
        startSharePreparation(queued.generation)
    }

    /** Starts one bounded transient-text snapshot for an exact share generation. */
    private fun startSharePreparation(generation: Long) {
        check(sharePreparationJob == null) { "share preparation is already active" }
        shareStatus = ShareStatus.Preparing(generation)
        lateinit var preparationJob: Job
        preparationJob =
            operationScope.launch(start = CoroutineStart.LAZY) {
                prepareTransientShare(generation)
            }
        sharePreparationJob = preparationJob
        preparationJob.invokeOnCompletion {
            finishSharePreparation(preparationJob, generation)
        }
        preparationJob.start()
    }

    /** Captures one exact small revision as a transient Android text payload. */
    private suspend fun prepareTransientShare(generation: Long) {
        var text: String? = null
        try {
            val sharedRevision =
                state.saveDocument(DocumentSavePurpose.Copy) { snapshot, expectedBytes ->
                    text = sharedTextReader(snapshot, expectedBytes)
                }
            if (sharedRevision == null || !ownsSharePreparation(generation)) {
                if (ownsSharePreparation(generation)) {
                    publishShareFailure(generation, SHARE_PREPARATION_FAILURE_MESSAGE)
                }
                return
            }
            shareStatus =
                ShareStatus.Ready(
                    DocumentShareRequest(
                        generation = generation,
                        payload =
                            DocumentSharePayload.Text(
                                text = checkNotNull(text),
                                title = title,
                                format = documentFormat
                            )
                    )
                )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: DocumentShareException) {
            if (ownsSharePreparation(generation)) {
                publishShareFailure(generation, SHARE_PREPARATION_FAILURE_MESSAGE)
            }
        } catch (_: Exception) {
            if (ownsSharePreparation(generation)) {
                publishShareFailure(generation, SHARE_PREPARATION_FAILURE_MESSAGE)
            }
        } catch (_: LinkageError) {
            if (ownsSharePreparation(generation)) {
                publishShareFailure(generation, SHARE_PREPARATION_FAILURE_MESSAGE)
            }
        }
    }

    /** Releases one completed preparation slot and resolves cancellation. */
    private fun finishSharePreparation(completedJob: Job, generation: Long) {
        if (sharePreparationJob !== completedJob) {
            return
        }
        sharePreparationJob = null
        when (val status = shareStatus) {
            is ShareStatus.Cancelling ->
                if (status.generation == generation) {
                    shareStatus = ShareStatus.Idle
                }

            is ShareStatus.Preparing ->
                if (status.generation == generation) {
                    publishShareFailure(generation, SHARE_PREPARATION_FAILURE_MESSAGE)
                }

            else -> Unit
        }
        ensureSourceSave()
    }

    /** Returns whether one active preparation still owns its generation. */
    private fun ownsSharePreparation(generation: Long): Boolean = !closeStarted.get() &&
        (shareStatus as? ShareStatus.Preparing)?.generation == generation

    /** Fails one queued share only when it still owns the supplied draft. */
    private fun failQueuedShare(draftGeneration: Long) {
        val queued = shareStatus as? ShareStatus.Queued ?: return
        if (queued.draftGeneration == draftGeneration) {
            publishShareFailure(queued.generation, SHARE_PREPARATION_FAILURE_MESSAGE)
        }
    }

    /** Publishes one sanitized failure for the current share generation. */
    private fun publishShareFailure(generation: Long, message: UiText) {
        require(generation > 0L) { "share generation must be positive" }

        shareStatus = ShareStatus.Failed(generation = generation, message = message)
    }

    /** Resolves native printing after its exact bounded draft becomes stable. */
    private fun resolveQueuedPrint() {
        val queued = printStatus as? PrintStatus.Queued ?: return
        if (closeStarted.get() || pendingEditWindowAction != null) {
            publishPrintFailure(
                queued.generation,
                PRINT_PREPARATION_FAILURE_MESSAGE,
                queued.settings
            )
            return
        }
        if (state.status == EditorDocumentStatus.ApplyingEdit) {
            return
        }
        val metrics = state.metrics
        if (state.status != EditorDocumentStatus.Ready || metrics == null) {
            publishPrintFailure(
                queued.generation,
                PRINT_PREPARATION_FAILURE_MESSAGE,
                queued.settings
            )
            return
        }
        val draftGeneration = queued.draftGeneration
        if (draftGeneration != null) {
            val draft = activeDraft
            if (draft?.edit?.generation != draftGeneration) {
                publishPrintFailure(
                    queued.generation,
                    PRINT_PREPARATION_FAILURE_MESSAGE,
                    queued.settings
                )
                return
            }
            val fieldValue =
                if (latestObservedDraft === draft) {
                    checkNotNull(latestFieldValue)
                } else {
                    draft.captureFieldValue()
                }
            if (fieldValue.composition != null || draft.hasChanges) {
                return
            }
        } else if (activeDraft != null) {
            publishPrintFailure(
                queued.generation,
                PRINT_PREPARATION_FAILURE_MESSAGE,
                queued.settings
            )
            return
        }
        if (metrics.serializedByteLength > ExportProtocol.MAX_BYTE_LIMIT) {
            publishPrintFailure(
                queued.generation,
                PRINT_PREPARATION_FAILURE_MESSAGE,
                queued.settings
            )
            return
        }
        startPrintPreparation(queued.generation, queued.settings)
    }

    /** Captures one immutable revision for an exact print generation. */
    private fun startPrintPreparation(generation: Long, settings: PrintSettings) {
        check(printPreparationJob == null) { "print preparation is already active" }
        printStatus = PrintStatus.Preparing(generation, settings)
        lateinit var preparationJob: Job
        preparationJob =
            operationScope.launch(start = CoroutineStart.LAZY) {
                preparePrint(generation, settings)
            }
        printPreparationJob = preparationJob
        preparationJob.invokeOnCompletion {
            finishPrintPreparation(preparationJob, generation)
        }
        preparationJob.start()
    }

    /** Transfers one exact current snapshot into a retained print request. */
    private suspend fun preparePrint(generation: Long, settings: PrintSettings) {
        var captured: CapturedDocumentRevision? = null
        var content: PrintDocumentContent? = null
        var request: DocumentPrintRequest? = null
        try {
            captured = state.captureDocumentRevision()
            if (captured == null || !ownsPrintPreparation(generation)) {
                if (ownsPrintPreparation(generation)) {
                    publishPrintFailure(
                        generation,
                        PRINT_PREPARATION_FAILURE_MESSAGE,
                        settings
                    )
                }
                return
            }
            val exactCapture = checkNotNull(captured)
            // The conversion consumes ownership even if rendering or release throws.
            captured = null
            content =
                prepareEditorPrintContent(exactCapture, settings.contentMode, markdownRenderer)
            request =
                DocumentPrintRequest(
                    generation = generation,
                    title = title,
                    settings = settings,
                    content = checkNotNull(content)
                )
            content = null
            if (!ownsPrintPreparation(generation)) {
                return
            }
            printStatus = PrintStatus.Ready(request)
            request = null
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            if (ownsPrintPreparation(generation)) {
                publishPrintFailure(generation, PRINT_PREPARATION_FAILURE_MESSAGE, settings)
            }
        } catch (_: LinkageError) {
            if (ownsPrintPreparation(generation)) {
                publishPrintFailure(generation, PRINT_PREPARATION_FAILURE_MESSAGE, settings)
            }
        } finally {
            request?.close()
            content?.close()
            captured?.close()
        }
    }

    /** Releases one completed print-preparation slot and resolves cancellation. */
    private fun finishPrintPreparation(completedJob: Job, generation: Long) {
        if (printPreparationJob !== completedJob) {
            return
        }
        printPreparationJob = null
        when (val status = printStatus) {
            is PrintStatus.Cancelling ->
                if (status.generation == generation) {
                    printStatus = PrintStatus.Idle
                }

            is PrintStatus.Preparing ->
                if (status.generation == generation) {
                    publishPrintFailure(
                        generation,
                        PRINT_PREPARATION_FAILURE_MESSAGE,
                        status.settings
                    )
                }

            else -> Unit
        }
        ensureSourceSave()
        signalPendingCloseResolution()
    }

    /** Returns whether one active print capture still owns its generation. */
    private fun ownsPrintPreparation(generation: Long): Boolean = !closeStarted.get() &&
        (printStatus as? PrintStatus.Preparing)?.generation == generation

    /** Fails queued printing only when it still owns the supplied draft. */
    private fun failQueuedPrint(draftGeneration: Long) {
        val queued = printStatus as? PrintStatus.Queued ?: return
        if (queued.draftGeneration == draftGeneration) {
            publishPrintFailure(
                queued.generation,
                PRINT_PREPARATION_FAILURE_MESSAGE,
                queued.settings
            )
        }
    }

    /** Publishes one sanitized failure for the current print generation. */
    private fun publishPrintFailure(generation: Long, message: UiText, settings: PrintSettings) {
        require(generation > 0L) { "print generation must be positive" }

        printStatus =
            PrintStatus.Failed(
                generation = generation,
                message = message,
                settings = settings
            )
    }

    /** Resolves one QR share only after its exact draft and source save are stable. */
    private fun resolveQueuedQrShare() {
        val queued = qrShareStatus as? QrShareStatus.Queued ?: return
        if (closeStarted.get() || pendingEditWindowAction != null) {
            publishQrShareFailure(queued.generation, QR_SHARE_FAILURE_MESSAGE)
            return
        }
        if (state.status == EditorDocumentStatus.ApplyingEdit) {
            return
        }
        val metrics = state.metrics
        if (state.status != EditorDocumentStatus.Ready || metrics == null) {
            publishQrShareFailure(queued.generation, QR_SHARE_FAILURE_MESSAGE)
            return
        }
        val draftGeneration = queued.draftGeneration
        if (draftGeneration != null) {
            val draft = activeDraft
            if (draft?.edit?.generation != draftGeneration) {
                publishQrShareFailure(queued.generation, QR_SHARE_FAILURE_MESSAGE)
                return
            }
            val fieldValue =
                if (latestObservedDraft === draft) {
                    checkNotNull(latestFieldValue)
                } else {
                    draft.captureFieldValue()
                }
            if (fieldValue.composition != null || draft.hasChanges) {
                return
            }
        } else if (activeDraft != null) {
            publishQrShareFailure(queued.generation, QR_SHARE_FAILURE_MESSAGE)
            return
        }
        if (ownedDocumentSource is WritableEditorDocumentSource) {
            if (sourceSaveStatus.isTerminalFailure()) {
                publishQrShareFailure(queued.generation, SHARE_SOURCE_FAILURE_MESSAGE)
                return
            }
            if (state.hasDocumentChanges || isSourceSaveBusy || sourceSaveJob != null) {
                ensureSourceSave()
                return
            }
        }
        if (metrics.serializedByteLength > TransferProtocol.MAX_QR_TEXT_BYTES) {
            publishQrShareFailure(queued.generation, QR_SHARE_TOO_LARGE_MESSAGE)
            return
        }
        startQrSharePreparation(queued.generation)
    }

    /** Starts isolated QR encoding for one exact share generation. */
    private fun startQrSharePreparation(generation: Long) {
        check(qrSharePreparationJob == null) { "QR share preparation is already active" }
        val processor = checkNotNull(qrTransferProcessor) { "QR transfer processor is unavailable" }
        qrShareStatus = QrShareStatus.Preparing(generation)
        lateinit var preparationJob: Job
        preparationJob =
            operationScope.launch(start = CoroutineStart.LAZY) {
                prepareQrShare(generation = generation, processor = processor)
            }
        qrSharePreparationJob = preparationJob
        preparationJob.invokeOnCompletion {
            finishQrSharePreparation(preparationJob, generation)
        }
        preparationJob.start()
    }

    /** Captures and encodes one exact small revision as a QR module grid. */
    private suspend fun prepareQrShare(generation: Long, processor: QrTransferProcessor) {
        val format = documentFormat
        try {
            val captured = state.captureDocumentRevision()
            if (captured == null) {
                if (ownsQrSharePreparation(generation)) {
                    publishQrShareFailure(generation, QR_SHARE_FAILURE_MESSAGE)
                }
                return
            }
            val grid = captured.use {
                processor.encodeQr(
                    snapshot = it.snapshot,
                    expectedBytes = it.metrics.serializedByteLength,
                    format = format
                )
            }
            if (!ownsQrSharePreparation(generation)) {
                return
            }
            qrShareStatus =
                QrShareStatus.Ready(
                    generation = generation,
                    grid = grid,
                    textBytes = captured.metrics.serializedByteLength,
                    format = format
                )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: TransferException) {
            if (ownsQrSharePreparation(generation)) {
                publishQrShareFailure(
                    generation = generation,
                    message = qrShareFailureMessage(failure.failure)
                )
            }
        } catch (_: Exception) {
            if (ownsQrSharePreparation(generation)) {
                publishQrShareFailure(generation, QR_SHARE_FAILURE_MESSAGE)
            }
        } catch (_: LinkageError) {
            if (ownsQrSharePreparation(generation)) {
                publishQrShareFailure(generation, QR_SHARE_SERVICE_MESSAGE)
            }
        }
    }

    /** Releases one completed QR preparation slot and resolves cancellation. */
    private fun finishQrSharePreparation(completedJob: Job, generation: Long) {
        if (qrSharePreparationJob !== completedJob) {
            return
        }
        qrSharePreparationJob = null
        when (val status = qrShareStatus) {
            is QrShareStatus.Cancelling ->
                if (status.generation == generation) {
                    qrShareStatus = QrShareStatus.Idle
                }

            is QrShareStatus.Preparing ->
                if (status.generation == generation) {
                    publishQrShareFailure(generation, QR_SHARE_FAILURE_MESSAGE)
                }

            else -> Unit
        }
        ensureSourceSave()
    }

    /** Returns whether one active QR preparation still owns its generation. */
    private fun ownsQrSharePreparation(generation: Long): Boolean = !closeStarted.get() &&
        (qrShareStatus as? QrShareStatus.Preparing)?.generation == generation

    /** Fails one queued QR share only when it still owns the supplied draft. */
    private fun failQueuedQrShare(draftGeneration: Long) {
        val queued = qrShareStatus as? QrShareStatus.Queued ?: return
        if (queued.draftGeneration == draftGeneration) {
            publishQrShareFailure(queued.generation, QR_SHARE_FAILURE_MESSAGE)
        }
    }

    /** Publishes one sanitized failure for the current QR generation. */
    private fun publishQrShareFailure(generation: Long, message: UiText) {
        require(generation > 0L) { "QR share generation must be positive" }

        qrShareStatus = QrShareStatus.Failed(generation = generation, message = message)
    }

    /** Resolves one NFC write only after its exact draft and source save are stable. */
    private fun resolveQueuedNfcWrite() {
        val queued = nfcWriteStatus as? NfcWriteStatus.Queued ?: return
        if (closeStarted.get() || pendingEditWindowAction != null) {
            publishNfcWriteFailure(queued.generation, NFC_WRITE_FAILURE_MESSAGE)
            return
        }
        if (state.status == EditorDocumentStatus.ApplyingEdit) {
            return
        }
        val metrics = state.metrics
        if (state.status != EditorDocumentStatus.Ready || metrics == null) {
            publishNfcWriteFailure(queued.generation, NFC_WRITE_FAILURE_MESSAGE)
            return
        }
        val draftGeneration = queued.draftGeneration
        if (draftGeneration != null) {
            val draft = activeDraft
            if (draft?.edit?.generation != draftGeneration) {
                publishNfcWriteFailure(queued.generation, NFC_WRITE_FAILURE_MESSAGE)
                return
            }
            val fieldValue =
                if (latestObservedDraft === draft) {
                    checkNotNull(latestFieldValue)
                } else {
                    draft.captureFieldValue()
                }
            if (fieldValue.composition != null || draft.hasChanges) {
                return
            }
        } else if (activeDraft != null) {
            publishNfcWriteFailure(queued.generation, NFC_WRITE_FAILURE_MESSAGE)
            return
        }
        if (ownedDocumentSource is WritableEditorDocumentSource) {
            if (sourceSaveStatus.isTerminalFailure()) {
                publishNfcWriteFailure(queued.generation, SHARE_SOURCE_FAILURE_MESSAGE)
                return
            }
            if (state.hasDocumentChanges || isSourceSaveBusy || sourceSaveJob != null) {
                ensureSourceSave()
                return
            }
        }
        if (metrics.serializedByteLength > TransferProtocol.MAX_NFC_TEXT_BYTES) {
            publishNfcWriteFailure(queued.generation, NFC_WRITE_TOO_LARGE_MESSAGE)
            return
        }
        startNfcWritePreparation(queued.generation, queued.tagLabel)
    }

    /** Starts isolated NFC envelope encoding for one exact generation. */
    private fun startNfcWritePreparation(generation: Long, tagLabel: String?) {
        require(isValidNfcTagLabel(tagLabel)) { "NFC tag label is invalid" }
        check(nfcWritePreparationJob == null) { "NFC write preparation is already active" }
        val processor = checkNotNull(nfcTransferProcessor) {
            "NFC transfer processor is unavailable"
        }
        nfcWriteStatus = NfcWriteStatus.Preparing(generation, tagLabel)
        lateinit var preparationJob: Job
        preparationJob =
            operationScope.launch(start = CoroutineStart.LAZY) {
                prepareNfcWrite(
                    generation = generation,
                    tagLabel = tagLabel,
                    processor = processor
                )
            }
        nfcWritePreparationJob = preparationJob
        preparationJob.invokeOnCompletion {
            finishNfcWritePreparation(preparationJob, generation)
        }
        preparationJob.start()
    }

    /** Captures and encodes one exact bounded revision as an NFC envelope. */
    private suspend fun prepareNfcWrite(
        generation: Long,
        tagLabel: String?,
        processor: NfcTransferProcessor
    ) {
        var envelope: NfcTransferEnvelope? = null
        val format = documentFormat
        try {
            val captured = state.captureDocumentRevision()
            if (captured == null) {
                if (ownsNfcWritePreparation(generation)) {
                    publishNfcWriteFailure(generation, NFC_WRITE_FAILURE_MESSAGE)
                }
                return
            }
            captured.use {
                envelope = processor.encodeNfc(
                    snapshot = it.snapshot,
                    expectedBytes = it.metrics.serializedByteLength,
                    format = format,
                    tagLabel = tagLabel
                )
            }
            if (!ownsNfcWritePreparation(generation)) {
                return
            }
            val preparedEnvelope = checkNotNull(envelope)
            nfcWriteStatus =
                NfcWriteStatus.Ready(
                    generation = generation,
                    envelope = preparedEnvelope,
                    textBytes = captured.metrics.serializedByteLength,
                    format = format,
                    tagLabel = tagLabel
                )
            envelope = null
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: TransferException) {
            if (ownsNfcWritePreparation(generation)) {
                publishNfcWriteFailure(
                    generation = generation,
                    message = nfcWriteFailureMessage(failure.failure)
                )
            }
        } catch (_: Exception) {
            if (ownsNfcWritePreparation(generation)) {
                publishNfcWriteFailure(generation, NFC_WRITE_FAILURE_MESSAGE)
            }
        } catch (_: LinkageError) {
            if (ownsNfcWritePreparation(generation)) {
                publishNfcWriteFailure(generation, NFC_WRITE_SERVICE_MESSAGE)
            }
        } finally {
            envelope?.close()
        }
    }

    /** Releases one completed NFC preparation slot and resolves cancellation. */
    private fun finishNfcWritePreparation(completedJob: Job, generation: Long) {
        if (nfcWritePreparationJob !== completedJob) {
            return
        }
        nfcWritePreparationJob = null
        when (val status = nfcWriteStatus) {
            is NfcWriteStatus.Cancelling ->
                if (status.generation == generation) {
                    nfcWriteStatus = NfcWriteStatus.Idle
                }

            is NfcWriteStatus.Preparing ->
                if (status.generation == generation) {
                    publishNfcWriteFailure(generation, NFC_WRITE_FAILURE_MESSAGE)
                }

            else -> Unit
        }
        ensureSourceSave()
    }

    /** Returns whether one active NFC preparation still owns its generation. */
    private fun ownsNfcWritePreparation(generation: Long): Boolean = !closeStarted.get() &&
        (nfcWriteStatus as? NfcWriteStatus.Preparing)?.generation == generation

    /** Fails one queued NFC write only when it still owns the supplied draft. */
    private fun failQueuedNfcWrite(draftGeneration: Long) {
        val queued = nfcWriteStatus as? NfcWriteStatus.Queued ?: return
        if (queued.draftGeneration == draftGeneration) {
            publishNfcWriteFailure(queued.generation, NFC_WRITE_FAILURE_MESSAGE)
        }
    }

    /** Publishes one sanitized failure for the current NFC generation. */
    private fun publishNfcWriteFailure(generation: Long, message: UiText) {
        require(generation > 0L) { "NFC write generation must be positive" }

        nfcWriteStatus = NfcWriteStatus.Failed(generation = generation, message = message)
    }

    /** Cancels one queued explicit save without cancelling text or source output. */
    private fun cancelQueuedExplicitSave() {
        if (saveStatus !is SaveStatus.Queued) {
            return
        }
        queuedSourceReplacementEditTarget = null
        saveStatus = SaveStatus.Idle
        cancelSaveBeforeClose()
        ensureSourceSave()
    }

    /** Cancels one save-before-close route without affecting an ordinary close request. */
    private fun cancelSaveBeforeClose() {
        if (!isSaveBeforeClosePending) {
            return
        }
        isSaveBeforeClosePending = false
        isClosePending = false
    }

    /** Cancels one queued save only when it still owns the failed draft generation. */
    private fun cancelQueuedExplicitSave(draftGeneration: Long) {
        val queued = saveStatus as? SaveStatus.Queued ?: return
        if (queued.draftGeneration == draftGeneration) {
            cancelQueuedExplicitSave()
        }
    }

    /** Returns whether the current native revision and bounded draft text agree. */
    private fun isDocumentStableForSave(): Boolean = state.status == EditorDocumentStatus.Ready &&
        state.metrics != null &&
        activeDraft?.hasChanges != true

    /** Returns whether one Save As generation still owns the retained slot. */
    private fun ownsSaveGeneration(generation: Long): Boolean =
        !closeStarted.get() && activeSaveGeneration == generation

    /** Returns the exact request currently exporting or releasing cancellation resources. */
    private fun activeExportRequest(): SaveDestinationRequest? = when (val status = saveStatus) {
        is SaveStatus.Exporting -> status.request
        is SaveStatus.CancellingExport -> status.request
        else -> null
    }

    /** Marks one visible copy success when the live document advances afterward. */
    private fun markSaveSuccessHasNewerChanges() {
        val succeeded = saveStatus as? SaveStatus.Succeeded ?: return
        if (!succeeded.hasNewerChanges) {
            saveStatus = succeeded.copy(hasNewerChanges = true)
        }
    }

    /** Completes one safe close request or shows its retained discard confirmation. */
    private fun completeCloseRequest(): CloseRequestResult {
        check(canCloseSafely) { "close request is not safe to complete" }
        isClosePending = false
        return if (hasUnsavedChanges) {
            showDiscardConfirmation()
            CloseRequestResult.ConfirmationShown
        } else {
            CloseRequestResult.CloseNow
        }
    }

    /** Reconciles the retained draft with the state's current edit generation. */
    private fun reconcileActiveDraft(requestFocusForReplacement: Boolean = false) {
        val activeEdit = state.activeEdit
        val queuedSave = saveStatus as? SaveStatus.Queued
        if (queuedSave != null && queuedSave.draftGeneration != activeEdit?.generation) {
            cancelQueuedExplicitSave()
        }
        val currentDraft = activeDraft
        if (activeEdit == null) {
            currentDraft?.let { draft ->
                cancelWaitingEditWindowAction(draft.edit.generation)
            }
            activeDraft = null
            latestObservedDraft = null
            latestFieldValue = null
            currentDraft?.release()
            return
        }
        if (currentDraft?.edit?.generation == activeEdit.generation) {
            if (currentDraft.edit != activeEdit) {
                currentDraft.reconcileCommittedEdit(activeEdit)
            }
            return
        }
        currentDraft?.let { draft ->
            cancelWaitingEditWindowAction(draft.edit.generation)
        }
        val historyAction = pendingEditWindowAction?.takeIf { pending ->
            pending.phase == EditWindowActionPhase.Executing &&
                pending.generation == currentDraft?.edit?.generation &&
                (
                    pending.action == EditWindowAction.Undo ||
                        pending.action == EditWindowAction.Redo ||
                        pending.action == EditWindowAction.BulkInsert
                    )
        }
        if (currentDraft != null && historyAction != null) {
            currentDraft.reconcileHistoryEditWindow(activeEdit)
            latestObservedDraft = null
            latestFieldValue = null
            return
        }
        val scrollRestoration =
            pendingEditWindowScrollRestoration?.takeIf { restoration ->
                restoration.anchor.revision == activeEdit.snapshot.metrics.revision &&
                    restoration.anchor.sourceUtf16Offset in
                    activeEdit.snapshot.range.start..activeEdit.snapshot.range.end
            }
        val directedSelection =
            pendingEditWindowAction
                ?.automaticTransition
                ?.takeIf { transition ->
                    val orderedSelection = transition.selection.orderedRange
                    transition.preserveSelection &&
                        transition.anchor.revision == activeEdit.snapshot.metrics.revision &&
                        orderedSelection.start >= activeEdit.snapshot.range.start &&
                        orderedSelection.end <= activeEdit.snapshot.range.end
                }?.selection
                ?.let { selection ->
                    TextRange(
                        start =
                            Math.toIntExact(
                                selection.start - activeEdit.snapshot.range.start
                            ),
                        end =
                            Math.toIntExact(
                                selection.end - activeEdit.snapshot.range.start
                            )
                    )
                }
        val automaticAction =
            pendingEditWindowAction?.takeIf { pending ->
                pending.phase == EditWindowActionPhase.Executing &&
                    pending.generation == currentDraft?.edit?.generation &&
                    (
                        pending.action == EditWindowAction.Earlier ||
                            pending.action == EditWindowAction.Later
                        )
            }
        val automaticTransition = automaticAction?.automaticTransition
        if (
            currentDraft != null &&
            automaticTransition != null &&
            scrollRestoration?.preserveScrollMomentum == true &&
            currentDraft.edit.snapshot.metrics.revision == activeEdit.snapshot.metrics.revision &&
            !currentDraft.hasChanges &&
            currentDraft.textFieldState.composition == null &&
            (!automaticTransition.preserveSelection || directedSelection != null)
        ) {
            currentDraft.reconcileAutomaticEditWindow(
                nextEdit = activeEdit,
                restoration = scrollRestoration,
                directedSelection = directedSelection
            )
            pendingEditWindowScrollRestoration = null
            latestObservedDraft = null
            latestFieldValue = null
            return
        }
        val replacement =
            ActiveEditDraft(
                initialEdit = activeEdit,
                shouldRestoreEditorFocusInitially =
                    requestFocusForReplacement ||
                        currentDraft?.shouldRestoreEditorFocus == true,
                initialScrollRestoration = scrollRestoration,
                initialDirectedSelection = directedSelection
            )
        if (scrollRestoration != null) {
            pendingEditWindowScrollRestoration = null
        }
        activeDraft = replacement
        latestObservedDraft = null
        latestFieldValue = null
        currentDraft?.release()
    }

    companion object {
        /** Creates a session that owns one new empty Rust document. */
        fun createEmpty(
            markdownRenderer: MarkdownRenderer? = null,
            qrTransferProcessor: QrTransferProcessor? = null,
            nfcTransferProcessor: NfcTransferProcessor? = null
        ): EditorSession = EditorSession(
            title = NEW_DOCUMENT_TITLE,
            state = EditorDocumentState.createEmpty(),
            shouldFocusInitialEditor = true,
            markdownRenderer = markdownRenderer,
            qrTransferProcessor = qrTransferProcessor,
            nfcTransferProcessor = nfcTransferProcessor
        )

        /** Creates a keyboard-closed session for one bounded, unsaved received text value. */
        fun createTransientText(
            text: String,
            title: String,
            markdownRenderer: MarkdownRenderer? = null,
            qrTransferProcessor: QrTransferProcessor? = null,
            nfcTransferProcessor: NfcTransferProcessor? = null,
            initialPresentation: EditorPresentation = EditorPresentation.Text
        ): EditorSession {
            require(title.isNotBlank()) { "received document title must not be blank" }
            return EditorSession(
                title = title,
                state = EditorDocumentState.createTransientText(text),
                initialPresentation = initialPresentation,
                markdownRenderer = markdownRenderer,
                qrTransferProcessor = qrTransferProcessor,
                nfcTransferProcessor = nfcTransferProcessor
            )
        }

        /** Takes exclusive ownership of one already opened document. */
        fun takeOwnership(
            document: EditorDocument,
            documentSource: EditorDocumentSource? = null,
            title: String? = null,
            sourceMetadata: SelectedDocumentMetadata? = null,
            sourceFormat: DocumentFormat = DocumentFormat.PlainText,
            markdownRenderer: MarkdownRenderer? = null,
            qrTransferProcessor: QrTransferProcessor? = null,
            nfcTransferProcessor: NfcTransferProcessor? = null,
            initialPresentation: EditorPresentation = EditorPresentation.Text
        ): EditorSession = EditorSession(
            title = title ?: OPENED_DOCUMENT_TITLE,
            state = EditorDocumentState.takeOwnership(document),
            documentSource = documentSource,
            sourceMetadata = sourceMetadata,
            sourceFormat = sourceFormat,
            initialPresentation = initialPresentation,
            markdownRenderer = markdownRenderer,
            qrTransferProcessor = qrTransferProcessor,
            nfcTransferProcessor = nfcTransferProcessor
        )
    }
}

/** Returns whether autosave stopped until the user chooses a safe recovery. */
private fun SourceSaveStatus.isTerminalFailure(): Boolean = this is SourceSaveStatus.Failed ||
    this is SourceSaveStatus.Conflict ||
    this is SourceSaveStatus.Uncertain
