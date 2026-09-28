package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.document.DocumentFormat
import dev.soupslurpr.beautyxt.document.DocumentChange
import dev.soupslurpr.beautyxt.document.DocumentInsertion
import dev.soupslurpr.beautyxt.document.inverseChanges
import dev.soupslurpr.beautyxt.document.DocumentPatch
import dev.soupslurpr.beautyxt.document.DocumentSearch
import dev.soupslurpr.beautyxt.document.SearchOptions
import dev.soupslurpr.beautyxt.document.SearchCursor
import dev.soupslurpr.beautyxt.document.SearchCompletion
import dev.soupslurpr.beautyxt.document.MAX_SEARCH_RESULTS
import dev.soupslurpr.beautyxt.document.MAX_REPLACEMENT_REVIEW_UNITS
import dev.soupslurpr.beautyxt.document.DocumentRemovalAction
import dev.soupslurpr.beautyxt.document.DocumentRemovalCapabilities
import dev.soupslurpr.beautyxt.document.EditorDocument
import dev.soupslurpr.beautyxt.document.EditorDocumentSnapshot
import dev.soupslurpr.beautyxt.document.FindDirection
import dev.soupslurpr.beautyxt.document.FindMatch
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
import kotlinx.coroutines.withContext
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
        val tagLabel: String?,
        val armedOnOpen: Boolean = false
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
    private val nfcTransferProcessor: NfcTransferProcessor? = null,
    private val findNanoTime: () -> Long = System::nanoTime
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
    private var reopenFindAfterInitialLoad = false
    private var previewReturnsToSource by mutableStateOf(true)
    private val operationScope =
        CoroutineScope(SupervisorJob() + operationDispatcher)
    val qrImageExport = QrImageExportController(operationScope, ::matchesDocumentSourceUri)
    val excerptExport = ExcerptExportController(operationScope, ::captureSelectionExcerpt,
        { state.metrics?.revision.takeUnless { state.hasActiveDraftChanges || closeStarted.get() } },
        ::matchesDocumentSourceUri, markdownRenderer, qrTransferProcessor, nfcTransferProcessor,
        showQr = { grid, bytes, format ->
            qrShareStatus = QrShareStatus.Ready(nextQrShareGeneration++, grid, bytes, format)
        },
        writeNfc = { envelope, bytes, format, label ->
            nfcWriteStatus = NfcWriteStatus.Ready(nextNfcWriteGeneration++, envelope, bytes, format, label, armedOnOpen = true)
        },
        captureDocument = ::captureWholeDocumentExport,
        documentSaveReminder = {
            when {
                !hasDocumentSource -> UiText.Resource(R.string.export_copy_unsaved_document)
                hasUnsavedChanges -> UiText.Resource(R.string.export_copy_unsaved_changes)
                else -> null
            }
        })
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
    private var findJob: Job? = null
    private var nextMarkdownPreviewGeneration = FIRST_MARKDOWN_PREVIEW_GENERATION
    private var activeMarkdownPreviewGeneration: Long? = null
    private var markdownPreviewJob: Job? = null
    private var markdownPreviewReturnTarget: MarkdownPreviewReturnTarget? = null
    private var pendingReadingLocation: DocumentLocation? = null
    private var pendingReadingSelection: DocumentSelection.Source? = null
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
    var findFocusRequest by mutableLongStateOf(0L)
        private set
    var findRequestsKeyboard by mutableStateOf(true)
        private set
    var findInputFocus by mutableStateOf(FindInputFocus.Query)
        private set
    private var findFocusIntentVersion = 0L

    var isFindCaseSensitive by mutableStateOf(false)
        private set

    var findStatus by mutableStateOf<FindStatus>(FindStatus.Idle)
        private set

    var findMatch by mutableStateOf<FindMatch?>(null)
        private set

    var findResults by mutableStateOf<List<DocumentSearchResult>>(emptyList())
        private set
    var findResultIndex by mutableIntStateOf(-1)
        private set
    var findRevealRequest by mutableLongStateOf(0L)
        private set
    private var handledFindRevealRequest = 0L
    private var findRevealFocusIntent = 0L
    private var findResultsOpenedAtReveal = 0L
    var isFindComplete by mutableStateOf(false)
        private set
    private data class FindContinuation(val unit: Int, val cursor: SearchCursor, val replaceResults: Boolean)
    private var findContinuation by mutableStateOf<FindContinuation?>(null)
    var hasEarlierFindResults by mutableStateOf(false)
        private set
    val canContinueFind get() = findContinuation != null && findStatus != FindStatus.Searching
    var findCoverageMessage by mutableStateOf<UiText?>(null)
        private set
    var isFindRegex by mutableStateOf(false)
        private set
    var isFindWholeWord by mutableStateOf(false)
        private set
    var includeIllustrationSource by mutableStateOf(false)
        private set
    var documentSelection by mutableStateOf<DocumentSelection?>(null)
        private set
    var selectionMessage by mutableStateOf<UiText?>(null)
        private set
    private var sourceFieldSelection: DocumentSelection.Source? = null
    private var observedIllustrationRevision = -1L
    private var observedIllustrationVersion = -1L

    private var selectionReadingCache: Triple<dev.soupslurpr.beautyxt.markdown.MarkdownPreviewDocument, Long, dev.soupslurpr.beautyxt.markdown.MarkdownPreviewDocument>? = null

    fun readingDocumentForSelection(): dev.soupslurpr.beautyxt.markdown.MarkdownPreviewDocument? {
        val ready = (markdownPreviewStatus as? MarkdownPreviewStatus.Ready)
            ?.takeIf { it.revision == state.metrics?.revision } ?: return null
        val version = ready.layout.illustrationCache.version
        selectionReadingCache?.takeIf { it.first === ready.document && it.second == version }?.let { return it.third }
        val cache = ready.layout.illustrationCache.snapshot()
        return ready.document.copy(blocks = ready.document.blocks.indices.map { index ->
            ready.layout.illustrations.decorate(index) { request -> cache[request]
                ?: dev.soupslurpr.beautyxt.illustration.IllustrationResult.Pending(request.kind) }
        }).also { selectionReadingCache = Triple(ready.document, version, it) }
    }

    fun selectReading(anchor: ReadingPoint, focus: ReadingPoint): Boolean {
        val revision = state.metrics?.revision ?: return false
        val document = readingDocumentForSelection() ?: return false
        if (anchor.block !in document.blocks.indices || focus.block !in document.blocks.indices) return false
        val forward = anchor <= focus
        val first = atomicReadingPoint(document, anchor, ending = !forward)
        val last = atomicReadingPoint(document, focus, ending = forward)
        if (first == last) return false
        documentSelection = DocumentSelection.Reading(revision, first, last)
        selectionMessage = null
        return true
    }

    fun selectSource(anchor: Long, focus: Long): Boolean {
        val metrics = state.metrics ?: return false
        if (state.hasActiveDraftChanges || anchor !in 0..metrics.utf16Length || focus !in 0..metrics.utf16Length || anchor == focus) return false
        documentSelection = DocumentSelection.Source(metrics.revision, anchor, focus)
        selectionMessage = null
        return true
    }

    fun selectDiagramLabel(block: Int, start: Int, run: Int): Boolean {
        val document = readingDocumentForSelection() ?: return false
        val span = document.blocks.getOrNull(block)?.spans?.firstOrNull { it.start == start } ?: return false
        val drawing = (span.illustration as? dev.soupslurpr.beautyxt.illustration.IllustrationResult.Rendered)
            ?.takeIf { it.kind == dev.soupslurpr.beautyxt.illustration.IllustrationKind.Diagram }?.drawing ?: return false
        val label = drawing.textRuns.getOrNull(run) ?: return false
        documentSelection = DocumentSelection.Label(state.metrics!!.revision, block, start, run, label.text)
        selectionMessage = null
        return true
    }

    fun updateLabelSelection(range: androidx.compose.ui.text.TextRange) {
        val label = documentSelection as? DocumentSelection.Label ?: return
        if (range.min >= 0 && range.max <= label.text.length && !range.collapsed)
            documentSelection = label.copy(range = range)
    }

    fun clearDocumentSelection() {
        val fieldSelection = sourceFieldSelection
        if (fieldSelection != null && documentSelection == fieldSelection) {
            activeDraft?.let { draft ->
                if (!draft.textFieldState.selection.collapsed) draft.textFieldState.edit {
                    selection = androidx.compose.ui.text.TextRange(selection.end)
                }
            }
        }
        sourceFieldSelection = null
        documentSelection = null
        selectionMessage = null
    }

    val canEditDocumentSelection: Boolean get() = documentSelection is DocumentSelection.Source &&
        documentSelection?.revision == state.metrics?.revision && !isViewOnly &&
        presentation == EditorPresentation.Text && !state.hasActiveDraftChanges &&
        state.status == EditorDocumentStatus.Ready && !isSaveBusy && !isSourceReloading

    /** Edits a global source selection as one validated, undoable document action. */
    fun replaceSelectedSource(inserted: String, copyRemoved: ((String) -> Unit)? = null): Boolean {
        if (!canEditDocumentSelection) return false
        val selected = documentSelection as? DocumentSelection.Source ?: return false
        var outcome: DocumentReplacementResult = DocumentReplacementResult.Unavailable
        launchOperation(operation = { state ->
            try {
                if (copyRemoved != null) {
                    if (selected.range.end - selected.range.start > 128 * 1024) {
                        selectionMessage = UiText.Resource(R.string.selection_output_failed)
                        return@launchOperation
                    }
                    val removed = state.readSourceRange(selected.revision, selected.range)
                    if (selected != documentSelection || !canEditDocumentSelection) return@launchOperation
                    if (removed.toByteArray().size > 128 * 1024) {
                        selectionMessage = UiText.Resource(R.string.selection_output_failed)
                        return@launchOperation
                    }
                    copyRemoved(removed)
                }
                outcome = state.replaceDocumentContent(selected.revision, selected.range,
                    DocumentInsertion(inserted), selected.range)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { selectionMessage = UiText.Resource(R.string.operation_apply_change_failed) }
        }, requestDraftFocus = false, onCompletion = {
            when (val result = outcome) {
                is DocumentReplacementResult.Applied -> {
                    result.delta?.let {
                        recordCommittedEdit(it)
                        invalidateMarkdownPreview()
                        recordSourceSaveRequest()
                    }
                    clearDocumentSelection()
                }
                DocumentReplacementResult.RejectedBySizeLimit -> selectionMessage = UiText.Resource(R.string.operation_save_document_too_large)
                else -> if (selectionMessage == null) selectionMessage = state.editorMessage
                    ?: UiText.Resource(R.string.operation_apply_change_failed)
            }
        })
        return true
    }

    /** Provider reload carries editable inputs only; all content-bound state belongs to the new session. */
    fun inheritFindInputs(previous: EditorSession) {
        findFieldValue = previous.findFieldValue
        replacementFieldValue = previous.replacementFieldValue
        isFindCaseSensitive = previous.isFindCaseSensitive
        isFindWholeWord = previous.isFindWholeWord
        isFindRegex = previous.isFindRegex
        includeIllustrationSource = previous.includeIllustrationSource
        if (previous.isFindVisible) {
            reopenFindAfterInitialLoad = !showFind(showKeyboard = false)
        }
    }

    fun selectWholeDocument(): Boolean {
        val metrics = state.metrics ?: return false
        return if (presentation == EditorPresentation.MarkdownPreview) {
            val blocks = readingDocumentForSelection()?.blocks ?: return false
            val last = blocks.indexOfLast { !it.illustrationContinuation && it.text.isNotEmpty() }
            if (last < 0) false else selectReading(ReadingPoint(0, -readingListPrefix(blocks[0]).length), ReadingPoint(last, blocks[last].text.length))
        } else selectSource(0, metrics.utf16Length)
    }

    fun extendSelectionParagraph(forward: Boolean): Boolean {
        val selection = documentSelection as? DocumentSelection.Reading ?: return false
        val blocks = readingDocumentForSelection()?.blocks ?: return false
        return selectReading(selection.anchor, readingAdjacentParagraphBoundary(blocks, selection.focus, forward))
    }

    fun captureCurrentSourceSelection(): Boolean {
        val draft = activeDraft ?: return false
        val range = draft.textFieldState.selection
        return selectSource(draft.edit.snapshot.range.start + range.start, draft.edit.snapshot.range.start + range.end)
    }

    suspend fun selectedPlainText(maximumBytes: Int): String {
        val selected = documentSelection ?: throw IllegalStateException("No selection")
        check(selected.revision == state.metrics?.revision && !state.hasActiveDraftChanges)
        return when (selected) {
            is DocumentSelection.Source -> {
                if (selected.range.end - selected.range.start > maximumBytes) throw SelectionLimitException()
                val text = state.readSourceRange(selected.revision, selected.range)
                ExcerptText(maximumBytes).apply { append(text) }.toString()
            }
            is DocumentSelection.Reading -> selectedReadingText(checkNotNull(readingDocumentForSelection()), selected, maximumBytes)
            is DocumentSelection.Label -> ExcerptText(maximumBytes).apply {
                append(selected.text.substring(selected.range.min, selected.range.max))
            }.toString()
        }.also { check(selected.revision == state.metrics?.revision) }
    }

    fun copyDocumentSelection(deliver: suspend (String) -> Unit) {
        operationScope.launch {
            try { deliver(selectedPlainText(128 * 1024)); selectionMessage = null }
            catch (cancellation: CancellationException) { throw cancellation }
            catch (_: Exception) { selectionMessage = UiText.Resource(R.string.selection_output_failed) }
        }
    }

    /** Opens the same frozen export review without changing a retained selection. */
    fun openDocumentExport(context: android.content.Context): Boolean {
        if (!(canStartShare || canStartPrint) || excerptExport.visible) return false
        excerptExport.open(context, title, ExportScope.Document, hasDocumentSource)
        return true
    }

    /** Commits the visible IME draft before taking an independently owned source snapshot. */
    private suspend fun captureWholeDocumentExport(): ExcerptCapture? {
        activeDraft?.let { draft ->
            draft.commitComposingText()
            observeActiveEdit(draft, draft.captureFieldValue())
            requestImmediateEditSynchronization(draft)
            editSynchronizationJob?.join()
        }
        if (closeStarted.get() || state.hasActiveDraftChanges || activeDraft?.hasChanges == true) return null
        val captured = state.captureDocumentRevision() ?: return null
        return try {
            ExcerptCapture(captured.metrics.revision, captured, true, documentFormat, null, null,
                wholeDocument = true,
                formatWholeDocument = markdownRenderer != null &&
                    captured.metrics.serializedByteLength <= MarkdownProtocol.MAX_INPUT_BYTES,
                preferFormattedPdf = presentation == EditorPresentation.MarkdownPreview ||
                    documentFormat == DocumentFormat.Markdown)
        } catch (failure: Throwable) {
            captured.close()
            throw failure
        }
    }

    private suspend fun captureSelectionExcerpt(): ExcerptCapture? {
        val selected = documentSelection ?: return null
        if (selected.revision != state.metrics?.revision || state.hasActiveDraftChanges) return null
        var context = readingDocumentForSelection()
        var owned: CapturedDocumentRevision? = null
        try {
            when (selected) {
                is DocumentSelection.Source -> {
                    owned = state.captureSelectedRevision(selected.revision, selected.range) ?: return null
                    if (context == null && documentFormat == DocumentFormat.Markdown && markdownRenderer != null &&
                        (state.metrics?.serializedByteLength ?: Long.MAX_VALUE) <= MAX_EXCERPT_MARKDOWN_BYTES) {
                        try {
                            state.captureDocumentRevision()?.use { full ->
                                if (full.metrics.revision == selected.revision) context = markdownRenderer.renderPreview(full.snapshot, full.metrics.serializedByteLength)
                            }
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { /* Exact source remains exportable without a formatted interpretation. */ }
                    }
                }
                is DocumentSelection.Reading, is DocumentSelection.Label -> {
                    withContext(Dispatchers.Default) {
                        val text = if (selected is DocumentSelection.Reading)
                            selectedReadingText(checkNotNull(context), selected, MAX_EXCERPT_MARKDOWN_BYTES)
                        else (selected as DocumentSelection.Label).text.substring(selected.range.min, selected.range.max)
                        owned = captureGeneratedExcerpt(text)
                    }
                }
            }
            check(selected.revision == state.metrics?.revision && !state.hasActiveDraftChanges)
            val reading = when (selected) {
                is DocumentSelection.Reading -> selected
                is DocumentSelection.Source -> context?.let { readingSelectionForSource(it, selected) }
                else -> null
            }
            return ExcerptCapture(selected.revision, checkNotNull(owned), selected is DocumentSelection.Source,
                if (selected is DocumentSelection.Source) documentFormat else DocumentFormat.PlainText,
                context, reading, (selected as? DocumentSelection.Label)?.let { it.text.substring(it.range.min, it.range.max) })
                .also { owned = null }
        } finally { owned?.close() }
    }

    fun findSelectedText() {
        operationScope.launch {
            try {
                val text = selectedPlainText(MAX_FIND_QUERY_UTF16_UNITS * 4)
                if (text.length > MAX_FIND_QUERY_UTF16_UNITS) throw SelectionLimitException()
                findRequestsKeyboard = false
                findInputFocus = FindInputFocus.Query
                findFocusIntentVersion++
                findFocusRequest++
                if (!isFindVisible && !openFind(documentSelection?.exactSource(readingDocumentForSelection())?.start ?: currentDocumentLocation().offset)) return@launch
                isFindRegex = false
                isFindWholeWord = false
                capturedFindScope = null
                capturedReadingFindScope = null
                isFindScopePaused = false
                findFieldValue = TextFieldValue(text)
                refreshAdvancedFind()
            } catch (cancellation: CancellationException) { throw cancellation }
            catch (_: Exception) { selectionMessage = UiText.Resource(R.string.selection_find_failed) }
        }
    }
    var isReplaceVisible by mutableStateOf(false)
        private set
    var replacementFieldValue by mutableStateOf(TextFieldValue())
        private set
    var capturedFindScope by mutableStateOf<Utf16Range?>(null)
        private set
    var capturedReadingFindScope by mutableStateOf<DocumentSelection?>(null)
        private set
    val isFindSelectionScope get() = capturedFindScope != null || capturedReadingFindScope != null || isFindScopePaused
    var isFindScopePaused by mutableStateOf(false)
        private set
    var excludedFindResults by mutableStateOf<Set<Int>>(emptySet())
        private set
    var findResultsPage by mutableStateOf<FindResultsPage?>(null)
        private set
    val isFindResultsExpanded get() = findResultsPage != null
    var findActionMessage by mutableStateOf<UiText?>(null)
        private set
    private var undoableFindReplacementRevision by mutableStateOf<Long?>(null)
    var replacementUndoNoticeRevision by mutableStateOf<Long?>(null)
        private set

    fun retireReplacementUndoNotice(revision: Long) {
        if (replacementUndoNoticeRevision == revision) replacementUndoNoticeRevision = null
    }

    /** The contextual action must never fall through to typing or another document edit. */
    val canUndoFindReplacement: Boolean
        get() = undoableFindReplacementRevision?.let { revision ->
            isReplaceVisible && canUndo && activeDraft?.hasChanges == false &&
                state.metrics?.revision == revision && history.undoEntry?.revisionAfter == revision
        } == true
    private var compiledFind: DocumentSearch? = null
    private var advancedFindGeneration = 0L
    private var reviewedFindRevision: Long? = null
    private var pendingFindDirection: FindDirection? = null
    private val locations = DocumentLocations()
    private var locationVersion by mutableLongStateOf(0L)
    var locationMessage by mutableStateOf<UiText?>(null)
        private set
    val hasPreviousLocation: Boolean get() { locationVersion; return locations.hasPrevious }
    val hasNextLocation: Boolean get() { locationVersion; return locations.hasNext }

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
        get() = state.isReplacingDocumentTree || pendingEditWindowAction?.let { pending ->
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
        if (reopenFindAfterInitialLoad && showFind(showKeyboard = false)) reopenFindAfterInitialLoad = false
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
        locations.record(currentDocumentLocation(), DocumentLocation(revision, presentation, entry.sourceOffset))
        locationVersion++
        markdownPreviewStatus = ready.copy(
            scrollRestoration = SemanticViewportAnchor(revision, entry.sourceOffset, 0)
        )
        observeMarkdownPreviewViewportAnchor(revision, entry.sourceOffset)
        return true
    }

    /** References and footnotes create independent return locations. */
    fun navigateToReadingBlock(revision: Long, blockIndex: Int): Boolean {
        val ready = markdownPreviewStatus as? MarkdownPreviewStatus.Ready ?: return false
        val block = ready.document.blocks.getOrNull(blockIndex) ?: return false
        if (ready.revision != revision || state.metrics?.revision != revision ||
            presentation != EditorPresentation.MarkdownPreview) return false
        locations.record(currentDocumentLocation(), DocumentLocation(revision, presentation, block.source.start))
        locationVersion++
        markdownPreviewStatus = ready.copy(scrollRestoration = SemanticViewportAnchor(revision, block.source.start, 0))
        observeMarkdownPreviewViewportAnchor(revision, block.source.start)
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
    fun showFind(showKeyboard: Boolean = true): Boolean {
        findResultsPage = null
        findInputFocus = FindInputFocus.Query
        findFocusIntentVersion++
        findRequestsKeyboard = showKeyboard
        findFocusRequest++
        findFieldValue = findFieldValue.copy(selection = androidx.compose.ui.text.TextRange(0, findFieldValue.text.length))
        if (isFindVisible) return true
        if (!canShowFind) {
            return false
        }
        val draft = activeDraft
        if (draft != null) {
            return requestEditWindowAction(draft = draft, action = EditWindowAction.OpenFind)
        }
        return openFind(currentDocumentLocation().offset)
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
        pendingReadingLocation = null
        val selectionInSource = when (val selected = documentSelection) {
            is DocumentSelection.Source -> selected
            is DocumentSelection.Reading -> selected.exactSource(readingDocumentForSelection())?.let {
                if (selected.anchor <= selected.focus) DocumentSelection.Source(selected.revision, it.start, it.end)
                else DocumentSelection.Source(selected.revision, it.end, it.start)
            }
            else -> pendingReadingSelection
        }?.takeIf { it.revision == state.metrics?.revision }
        clearDocumentSelection()
        documentSelection = selectionInSource
        pendingReadingSelection = null
        val selectionFitsEditor = selectionInSource == null ||
            selectionInSource.range.end - selectionInSource.range.start <= EDIT_DRAFT_MAX_UTF16_UNITS
        if (isViewOnly) {
            val anchor = selectionInSource?.let { SemanticViewportAnchor(it.revision, it.range.start, 0) }
                ?: returnTarget?.viewportAnchor
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
            }, onCompletion = { sourceViewportNavigationPending = false; refreshAdvancedFind() })
            return
        }
        markdownPreviewReturnTarget = null
        presentation = EditorPresentation.Text
        invalidateMarkdownPreview()
        if (!isViewOnly) {
            launchOperation(
                operation = { state ->
                    val previousTarget =
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
                    val target = selectionInSource?.let {
                        MarkdownPreviewReturnTarget(SemanticViewportAnchor(it.revision, it.range.start, 0),
                            if (selectionFitsEditor) it.range else Utf16Range(it.range.start, it.range.start))
                    } ?: previousTarget
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
                requestDraftFocus = !isFindVisible && selectionFitsEditor,
                onCompletion = {
                    if (selectionFitsEditor && selectionInSource != null && documentSelection == selectionInSource) {
                        activeDraft?.let { draft ->
                            val window = draft.edit.snapshot
                            if (window.metrics.revision == selectionInSource.revision &&
                                selectionInSource.range.start >= window.range.start && selectionInSource.range.end <= window.range.end) {
                                draft.textFieldState.edit { selection = androidx.compose.ui.text.TextRange(
                                    (selectionInSource.anchor - window.range.start).toInt(),
                                    (selectionInSource.focus - window.range.start).toInt()) }
                            }
                        }
                    }
                    refreshAdvancedFind()
                }
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

    /** Dismisses transient results and scope, retaining live-session inputs. */
    fun closeFind() {
        if (!isFindVisible) return
        findInputFocus = FindInputFocus.Document
        invalidateFindRequest()
        isFindVisible = false
        isReplaceVisible = false
        capturedFindScope = null
        capturedReadingFindScope = null
        isFindScopePaused = false
        findResults = emptyList()
        findResultIndex = -1
        excludedFindResults = emptySet()
        findResultsPage = null
        isFindComplete = false
        findCoverageMessage = null
        findActionMessage = null
        findStatus = FindStatus.Idle
        undoableFindReplacementRevision = null
        findMatch = null
        replacementUndoNoticeRevision = null
        pendingFindDirection = null
        locations.endFindExcursion()
    }

    /** Refreshes matches without moving the passage or changing editable selection. */
    fun updateFindFieldValue(candidate: TextFieldValue): Boolean {
        if (!isFindVisible || candidate.text.length > MAX_FIND_QUERY_UTF16_UNITS ||
            '\r' in candidate.text || !candidate.text.hasWellFormedUtf16()) return false
        val changed = candidate.text != findFieldValue.text
        findFieldValue = candidate
        if (changed) { findActionMessage = null; refreshAdvancedFind(delayed = true) }
        return true
    }

    fun updateFindCaseSensitivity(matchCase: Boolean): Boolean {
        if (!isFindVisible) return false
        if (isFindCaseSensitive != matchCase) {
            isFindCaseSensitive = matchCase
            refreshAdvancedFind()
        }
        return true
    }

    fun updateFindRegex(enabled: Boolean) {
        if (isFindRegex != enabled) { isFindRegex = enabled; refreshAdvancedFind() }
    }

    fun updateFindWholeWord(enabled: Boolean) {
        if (isFindWholeWord != enabled) { isFindWholeWord = enabled; refreshAdvancedFind() }
    }

    fun updateIncludeIllustrationSource(enabled: Boolean) {
        if (includeIllustrationSource != enabled) { includeIllustrationSource = enabled; refreshAdvancedFind() }
    }

    fun updateReplacementFieldValue(value: TextFieldValue) {
        if (value.text.length > MAX_REPLACEMENT_REVIEW_UNITS || '\r' in value.text ||
            !value.text.hasWellFormedUtf16()) return
        val changed = value.text != replacementFieldValue.text
        replacementFieldValue = value
        if (changed) refreshAdvancedFind(retainExclusions = true)
    }

    fun showReplace(showKeyboard: Boolean = findRequestsKeyboard) {
        if (isViewOnly) return
        isReplaceVisible = true
        findResultsPage = null
        findInputFocus = FindInputFocus.Replacement
        findRequestsKeyboard = showKeyboard
        findFocusIntentVersion++
        findFocusRequest++
        if (presentation == EditorPresentation.MarkdownPreview) {
            if (capturedReadingFindScope != null && capturedFindScope == null) isFindScopePaused = true
            showTextEditor()
        } else refreshAdvancedFind()
    }

    fun hideReplace() {
        isReplaceVisible = false
        if (findResultsPage == FindResultsPage.Replacements) findResultsPage = FindResultsPage.Matches
        if (findInputFocus == FindInputFocus.Replacement) focusFindQueryWithoutKeyboard()
        refreshAdvancedFind()
    }
    fun updateFindResultsExpanded(expanded: Boolean, reviewReplacements: Boolean = isReplaceVisible) {
        if (expanded) {
            findResultsOpenedAtReveal = findRevealRequest
            findInputFocus = FindInputFocus.Results
            findFocusIntentVersion++
            findRequestsKeyboard = false
            findFocusRequest++
        }
        else if (findInputFocus == FindInputFocus.Replacement || findInputFocus == FindInputFocus.Results) focusFindQueryWithoutKeyboard()
        findResultsPage = if (!expanded) null else if (reviewReplacements && isReplaceVisible)
            FindResultsPage.Replacements else FindResultsPage.Matches
    }

    fun recordFindInputFocus(target: FindInputFocus) {
        if (!isFindVisible || target == FindInputFocus.Replacement && !isReplaceVisible ||
            target == FindInputFocus.Results && !isFindResultsExpanded || findInputFocus == target) return
        findInputFocus = target
        findFocusIntentVersion++
    }

    /** Restores document commands after a popup releases its separate focus owner. */
    fun focusFindDocument() {
        if (!isFindVisible) return
        findInputFocus = FindInputFocus.Document
        findFocusIntentVersion++
        findRequestsKeyboard = false
        findFocusRequest++
    }

    private fun focusFindQueryWithoutKeyboard() {
        findInputFocus = FindInputFocus.Query
        findFocusIntentVersion++
        findRequestsKeyboard = false
        findFocusRequest++
    }

    /** A recreated layout must not repeat a previous match's focus or dismissal effects. */
    fun consumeFindReveal(): Boolean {
        if (handledFindRevealRequest == findRevealRequest) return false
        handledFindRevealRequest = findRevealRequest
        if (findFocusIntentVersion != findRevealFocusIntent) return false
        findInputFocus = FindInputFocus.Document
        return true
    }

    fun collapseFindResultsForNavigation() {
        if (findResultsOpenedAtReveal < findRevealRequest) findResultsPage = null
    }
    fun toggleFindResultIncluded(index: Int) {
        if (index in findResults.indices) excludedFindResults =
            if (index in excludedFindResults) excludedFindResults - index else excludedFindResults + index
    }

    fun useDocumentFindScope() {
        capturedFindScope = null
        capturedReadingFindScope = null
        isFindScopePaused = false
        refreshAdvancedFind()
    }

    fun captureSelectionFindScope(): Boolean {
        documentSelection?.let { selected ->
            if (presentation == EditorPresentation.MarkdownPreview && selected !is DocumentSelection.Source) {
                capturedReadingFindScope = selected
                capturedFindScope = selected.exactSource(readingDocumentForSelection())
                isFindScopePaused = false
                refreshAdvancedFind()
                return true
            }
            val range = selected.exactSource(readingDocumentForSelection())
            if (range != null && range.start < range.end) {
                capturedReadingFindScope = null
                capturedFindScope = range
                isFindScopePaused = false
                refreshAdvancedFind()
                return true
            }
            isFindScopePaused = true
            refreshAdvancedFind()
            return false
        }
        val draft = activeDraft
        val selection = draft?.textFieldState?.selection
        if (draft == null || selection == null || selection.collapsed || draft.hasChanges) {
            findActionMessage = UiText.Resource(R.string.find_select_scope_first)
            return false
        }
        capturedFindScope = Utf16Range(draft.edit.snapshot.range.start + selection.min,
            draft.edit.snapshot.range.start + selection.max)
        capturedReadingFindScope = null
        isFindScopePaused = false
        refreshAdvancedFind()
        return true
    }

    fun findNext(): Boolean = navigateFindResults(FindDirection.Forward)
    fun findPrevious(): Boolean = navigateFindResults(FindDirection.Backward)
    fun retryFind(): Boolean = refreshAdvancedFind()

    fun continueFind(): Boolean {
        if (!canContinueFind) return false
        return refreshAdvancedFind(continuation = findContinuation)
    }

    /** Starts reloading after a stale native revision invalidates cached ranges. */
    fun reloadStaleViewport() {
        clearSessionHistory(revision = null)
        clearDocumentExperienceReferences()
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

    /** Undoes only the replacement advertised by Find, including repeated or stale taps. */
    fun requestUndoFindReplacement(): Boolean = canUndoFindReplacement && requestUndo()

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
        val range = Utf16Range(start + proposal.oldRange.start, start + proposal.oldRange.end)
        val before = Utf16Range(start + proposal.selectionBefore.start, start + proposal.selectionBefore.end)
        var result: DocumentReplacementResult = DocumentReplacementResult.Unavailable
        launchOperation(
            operation = { state ->
                result = state.replaceDocumentContent(snapshot.metrics.revision, range, proposal.input, before) { length ->
                    val local = proposal.selectionAfter(length)
                    Utf16Range(start + local.start, start + local.end)
                }
            },
            requestDraftFocus = draft.isEditorFocused,
            onCompletion = {
                when (val completed = result) {
                    is DocumentReplacementResult.Applied -> {
                        completed.delta?.let {
                            recordCommittedEdit(it)
                            invalidateMarkdownPreview()
                            recordSourceSaveRequest()
                        }
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
        var result: DocumentReplacementResult = DocumentReplacementResult.Unavailable
        launchOperation(
            operation = { state ->
                result = state.restoreDocumentHistory(currentRevision, entry, action == EditWindowAction.Undo)
            },
            requestDraftFocus = draft.isEditorFocused && !isFindVisible,
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
        val undidReplacement = action == EditWindowAction.Undo &&
            undoableFindReplacementRevision == history.headRevision
        onDocumentPatchesApplied(checkNotNull(history.headRevision), revision,
            if (action == EditWindowAction.Undo) inverseChanges(entry.changes) else entry.changes)
        when (action) {
            EditWindowAction.Undo -> history.completeUndo(entry, revision)
            EditWindowAction.Redo -> history.completeRedo(entry, revision)
            else -> error("history stack move requires undo or redo")
        }
        val selection = if (action == EditWindowAction.Undo) entry.selectionBefore else entry.selectionAfter
        if (selection.end - selection.start > EDIT_WINDOW_UTF16_UNITS) {
            documentSelection = DocumentSelection.Source(revision, selection.start, selection.end)
        }
        historyVersion = Math.incrementExact(historyVersion)
        if (undidReplacement && isFindVisible) replacementUndoNoticeRevision = revision
    }

    /** Records one verified edit and publishes the journal's new availability. */
    private fun recordCommittedEdit(delta: CommittedEditDelta, scopedReplacement: Boolean = false) {
        onDocumentPatchesApplied(delta.revisionBefore, delta.revisionAfter, delta.changes, scopedReplacement)
        history.record(delta, state.oldestUndoRevision)
        historyVersion = Math.incrementExact(historyVersion)
    }

    private fun onDocumentPatchesApplied(before: Long, after: Long, patches: List<DocumentChange>,
        scopedReplacement: Boolean = false) {
        findActionMessage = null
        undoableFindReplacementRevision = null
        replacementUndoNoticeRevision = null
        documentSelection = (documentSelection as? DocumentSelection.Source)?.let { selected ->
            rebaseCapturedRange(selected.range, patches, scopedReplacement = false)?.let { range ->
                DocumentSelection.Source(after, range.start, range.end)
            }
        }
        if (capturedReadingFindScope != null) {
            capturedReadingFindScope = null
            if (capturedFindScope == null) isFindScopePaused = true
        }
        locations.rebase(before, after, patches)
        locationVersion++
        capturedFindScope?.let { scope ->
            val rebased = rebaseCapturedRange(scope, patches, scopedReplacement)
            if (rebased == null) {
                isFindScopePaused = true
                findActionMessage = UiText.Resource(R.string.find_scope_lost)
            } else capturedFindScope = rebased
        }
        if (isFindVisible) refreshAdvancedFind()
    }

    /** Releases session history and publishes its new revision boundary. */
    private fun clearSessionHistory(revision: Long? = state.metrics?.revision) {
        history.clear(revision)
        undoableFindReplacementRevision = null
        replacementUndoNoticeRevision = null
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
        val origin = currentDocumentLocation()
        launchOperation(
            operation = { state ->
                if (state.navigateActiveEditToLine(logicalLine)) {
                    val activeEdit = checkNotNull(state.activeEdit)
                    locations.record(origin, DocumentLocation(activeEdit.snapshot.metrics.revision,
                        presentation, activeEdit.snapshot.selection.start))
                    locationVersion++
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
        clearDocumentExperienceReferences()
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
        if (draft.isEditorFocused && !draft.hasChanges && !value.selection.collapsed) {
            val start = draft.edit.snapshot.range.start
            if (selectSource(start + value.selection.start, start + value.selection.end)) {
                sourceFieldSelection = documentSelection as? DocumentSelection.Source
            }
        } else if (sourceFieldSelection != null && (draft.hasChanges || value.selection.collapsed)) {
            if (documentSelection == sourceFieldSelection) documentSelection = null
            sourceFieldSelection = null
        }
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
        clearDocumentExperienceReferences()
        replacementFieldValue = TextFieldValue()
        excerptExport.close()
        isFindRegex = false
        isFindWholeWord = false
        isFindCaseSensitive = false
        includeIllustrationSource = false
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
        returnToSource: Boolean = true,
        locationFallback: DocumentLocation? = null
    ): Boolean {
        val currentMetrics = state.metrics ?: return false
        val currentRevision = currentMetrics.revision
        if (
            markdownRenderer == null ||
            closeStarted.get() ||
            presentation != EditorPresentation.Text ||
            isGoToLineDialogVisible ||
            state.status != EditorDocumentStatus.Ready ||
            state.activeEdit != null
        ) {
            return false
        }
        val defaultAnchor = resolveVisibleViewportAnchor()
        pendingReadingLocation = locationFallback
        pendingReadingSelection = documentSelection as? DocumentSelection.Source
        clearDocumentSelection()
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
        isReplaceVisible = false
        refreshAdvancedFind()
        val ready = markdownPreviewStatus as? MarkdownPreviewStatus.Ready
        if (ready?.revision == currentRevision) {
            pendingReadingLocation = null
            restoreSelectionInReading(ready)
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
                    if (rendered?.revision == revision) {
                            markdownPreviewStatus = MarkdownPreviewStatus.Ready(
                                revision = rendered.revision,
                                document = rendered.document,
                                layout = rendered.layout,
                                scrollRestoration =
                                    markdownPreviewReturnTarget
                                        ?.viewportAnchor
                                        ?.takeIf { anchor -> anchor.revision == rendered.revision }
                            )
                            pendingReadingLocation = null
                            restoreSelectionInReading(markdownPreviewStatus as MarkdownPreviewStatus.Ready)
                    } else {
                        publishMarkdownPreviewFailure(generation, UiText.Resource(R.string.operation_preview_revision_changed))
                        return@launch
                    }
                    if (isFindVisible) refreshAdvancedFind()
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
            pendingReadingLocation?.let { target ->
                pendingReadingLocation = null
                restoreDocumentLocationInSource(target, readingFallback = true)
            }
        }
    }

    private fun restoreSelectionInReading(ready: MarkdownPreviewStatus.Ready) {
        documentSelection = pendingReadingSelection?.takeIf { it.revision == ready.revision }?.let {
            readingSelectionForSource(ready.document, it)
        }
        pendingReadingSelection = null
    }

    /** Cancels and forgets any revision-bound Markdown preview model. */
    private fun invalidateMarkdownPreview() {
        observedIllustrationRevision = -1L
        observedIllustrationVersion = -1L
        activeMarkdownPreviewGeneration = null
        markdownPreviewJob?.cancel()
        markdownPreviewJob = null
        markdownPreviewStatus = MarkdownPreviewStatus.Idle
        selectionReadingCache = null
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
        findStatus = FindStatus.Idle
        findMatch = null
        // Opening Find transfers input intent even if a window transition prevents
        // the editor's focus-loss callback from clearing its restoration request.
        activeDraft?.updateEditorFocusIntent(isFocused = false, canClear = true)
        isFindVisible = true
        refreshAdvancedFind()
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
        advancedFindGeneration++
        findContinuation = null
        compiledFind?.close()
        compiledFind = null
        reviewedFindRevision = null
        findJob?.cancel()
        findJob = null
    }

    private fun clearDocumentExperienceReferences() {
        selectionReadingCache = null
        pendingReadingLocation = null
        pendingReadingSelection = null
        excerptExport.close()
        invalidateFindRequest()
        clearDocumentSelection()
        locations.clear()
        locationVersion++
        locationMessage = null
        findResults = emptyList()
        findResultIndex = -1
        findMatch = null
        capturedFindScope = null
        capturedReadingFindScope = null
        isFindScopePaused = false
        excludedFindResults = emptySet()
        findCoverageMessage = null
        findActionMessage = null
        pendingFindDirection = null
        isFindComplete = false
    }

    /** Searches one generation progressively; inputs alone never request navigation. */
    fun onReadingIllustrationsChanged(revision: Long) {
        val ready = (markdownPreviewStatus as? MarkdownPreviewStatus.Ready)?.takeIf { it.revision == revision } ?: return
        val version = ready.layout.illustrationCache.version
        if (observedIllustrationRevision == revision && observedIllustrationVersion == version) return
        observedIllustrationRevision = revision
        observedIllustrationVersion = version
        if (state.metrics?.revision == revision && isFindVisible &&
            presentation == EditorPresentation.MarkdownPreview) refreshAdvancedFind(retainNavigation = true)
    }

    private fun refreshAdvancedFind(delayed: Boolean = false, retainExclusions: Boolean = false,
        retainNavigation: Boolean = false, advanceAfterReplacement: Pair<Long, Boolean>? = null,
        continuation: FindContinuation? = null): Boolean {
        val append = continuation != null && !continuation.replaceResults
        val retainedResults = if (append) findResults else emptyList()
        val previousResult = findResults.getOrNull(findResultIndex).takeIf { retainNavigation }
        hasEarlierFindResults = continuation != null && (hasEarlierFindResults || continuation.replaceResults)
        if (!retainNavigation) pendingFindDirection = null
        invalidateFindRequest()
        findResults = retainedResults
        findResultIndex = -1
        findMatch = null
        isFindComplete = false
        findCoverageMessage = null
        if (!retainExclusions && !append) excludedFindResults = emptySet()
        if (!isFindVisible || findFieldValue.text.isEmpty() || isFindScopePaused || closeStarted.get()) {
            findStatus = FindStatus.Idle
            return false
        }
        val revision = state.metrics?.revision ?: return false
        val generation = advancedFindGeneration
        val focusIntentAtRequest = findFocusIntentVersion
        val domain = presentation
        val query = findFieldValue.text
        val options = SearchOptions(isFindRegex, isFindCaseSensitive, isFindWholeWord)
        if (presentation == EditorPresentation.Text && capturedReadingFindScope != null && capturedFindScope == null) {
            isFindScopePaused = true
            findActionMessage = UiText.Resource(R.string.find_scope_lost)
            return false
        }
        val scope = capturedFindScope ?: Utf16Range(0, state.metrics!!.utf16Length)
        val replacement = replacementFieldValue.text.takeIf { isReplaceVisible && domain == EditorPresentation.Text }
        val reading = (markdownPreviewStatus as? MarkdownPreviewStatus.Ready)?.takeIf { it.revision == revision }
        findStatus = FindStatus.Searching
        findOriginRevision = revision
        fun owned() = generation == advancedFindGeneration && isFindVisible &&
            state.metrics?.revision == revision && presentation == domain && !closeStarted.get()
        findJob = operationScope.launch {
            var search: DocumentSearch? = null
            try {
                if (delayed) findDelay()
                search = state.compileSearch(query, options)
                if (!owned()) return@launch
                compiledFind = search
                val accumulated = ArrayList(retainedResults)
                var retained = accumulated.sumOf { it.hit.text.length.toLong() + (it.hit.replacement?.length ?: 0) }
                var complete = true
                var limitMessage: Int? = null
                val started = findNanoTime()
                fun accept(results: List<DocumentSearchResult>, unit: Int): Boolean {
                    for (result in results) {
                        val visible = matchingVisibleIllustration(accumulated, result)
                        if (visible >= 0) {
                            accumulated[visible] = accumulated[visible].copy(alsoMatchesSource = true)
                            continue
                        }
                        val cost = result.hit.text.length.toLong() + (result.hit.replacement?.length ?: 0)
                        if (accumulated.size == MAX_SEARCH_RESULTS || retained + cost > MAX_REPLACEMENT_REVIEW_UNITS) {
                            // Resume at the first unretained match, including a zero-width hit.
                            // Discard this bounded review only when the user asks for the next batch.
                            if (accumulated.isNotEmpty()) {
                                findContinuation = FindContinuation(unit, SearchCursor(result.hit.range.start), true)
                                limitMessage = R.string.find_result_limit
                            } else limitMessage = R.string.find_context_limit
                            return false
                        }
                        if (domain == EditorPresentation.Text && capturedFindScope != null) {
                            val contained = result.source ?: result.ownerSource
                            if (contained == null) { complete = false; continue }
                            if (contained.start < scope.start || contained.end > scope.end) continue
                        }
                        retained += cost
                        accumulated.add(result)
                    }
                    return true
                }
                fun pauseAfterPage(completion: SearchCompletion, cursor: SearchCursor, next: SearchCursor, unit: Int): Boolean {
                    if (completion !in listOf(SearchCompletion.PageLimit, SearchCompletion.WorkLimit) || next == cursor) {
                        limitMessage = R.string.find_context_limit
                        return true
                    }
                    if (findNanoTime() - started > 3_000_000_000L) {
                        findContinuation = FindContinuation(unit, next, false)
                        limitMessage = R.string.find_time_limit
                        return true
                    }
                    return false
                }
                suspend fun publish() {
                    currentCoroutineContext().ensureActive()
                    if (!owned()) return
                    findResults = accumulated.toList()
                    if (previousResult != null) findResultIndex = findResults.indexOfFirst {
                        it.source == previousResult.source && it.illustration == previousResult.illustration &&
                            it.hit.range == previousResult.hit.range && it.segments == previousResult.segments &&
                            it.representation == previousResult.representation
                    }
                    if (pendingFindDirection != null && findResults.isNotEmpty() && findStatus != FindStatus.Searching) {
                        val direction = pendingFindDirection!!
                        pendingFindDirection = null
                        navigateFindResults(direction)
                    }
                    yield()
                }
                if (domain == EditorPresentation.Text) {
                    var cursor = continuation?.cursor ?: SearchCursor(scope.start)
                    while (owned()) {
                        val page = state.searchSource(search, revision, scope, cursor, replacement)
                        if (!owned()) return@launch
                        complete = accept(page.hits.map { hit ->
                            DocumentSearchResult(hit, hit.range, hit.range.start, SearchRepresentation.Source)
                        }, 0)
                        publish()
                        if (!complete || page.completion == SearchCompletion.Complete) break
                        if (pauseAfterPage(page.completion, cursor, page.next, 0)) {
                            complete = false
                            break
                        }
                        cursor = page.next
                    }
                } else if (reading != null) {
                    val cached = reading.layout.illustrationCache.snapshot()
                    val searchDocument = reading.document.copy(blocks = reading.document.blocks.indices.map { index ->
                        reading.layout.illustrations.decorate(index) { request -> cached[request]
                            ?: dev.soupslurpr.beautyxt.illustration.IllustrationResult.Pending(request.kind) }
                    })
                    val units = readingSearchUnits(searchDocument, includeIllustrationSource)
                    val selection = capturedReadingFindScope ?: capturedFindScope?.let {
                        readingSelectionForSource(searchDocument, DocumentSelection.Source(revision, it.start, it.end))
                    }
                    if (capturedFindScope != null && selection == null) {
                        isFindScopePaused = true
                        findActionMessage = UiText.Resource(R.string.find_scope_lost)
                        return@launch
                    }
                    complete = if (selection == null) !units.hasCoverageGaps else units.coverageGaps.none { target ->
                        readingUnitScope(ReadingSearchUnit("", emptyList(), illustration = target), selection) != null
                    }
                    var searchedUnit = false
                    outer@ for ((unitIndex, unit) in units.units.withIndex()) {
                        if (unitIndex < (continuation?.unit ?: 0)) continue
                        val unitScope = if (selection != null) readingUnitScope(unit, selection) ?: continue
                            else Utf16Range(0, unit.text.length.toLong())
                        var cursor = continuation?.takeIf { it.unit == unitIndex }?.cursor ?: SearchCursor(unitScope.start)
                        if (searchedUnit && findNanoTime() - started > 3_000_000_000L) {
                            findContinuation = FindContinuation(unitIndex, cursor, false)
                            limitMessage = R.string.find_time_limit
                            complete = false
                            break
                        }
                        while (owned()) {
                            val page = state.searchText(search, unit.text, unitScope, cursor)
                            searchedUnit = true
                            if (!owned()) return@launch
                            if (!accept(page.hits.map { readingSearchResult(searchDocument, unit, it) }, unitIndex)) {
                                complete = false
                                break@outer
                            }
                            publish()
                            if (page.completion == SearchCompletion.Complete) break
                            if (pauseAfterPage(page.completion, cursor, page.next, unitIndex)) {
                                complete = false
                                break@outer
                            }
                            cursor = page.next
                        }
                    }
                } else complete = false
                if (!owned()) return@launch
                isFindComplete = complete && !hasEarlierFindResults
                reviewedFindRevision = revision.takeIf { isFindComplete && replacement != null }
                findCoverageMessage = when {
                    isFindComplete -> null
                    limitMessage != null -> UiText.Resource(limitMessage)
                    complete && hasEarlierFindResults -> UiText.Resource(R.string.find_later_results_end)
                    else -> UiText.Resource(R.string.find_incomplete)
                }
                findStatus = when {
                    findMatch != null -> FindStatus.Match(findMatch!!, null)
                    findResults.isEmpty() && isFindComplete -> FindStatus.NoMatches
                    else -> FindStatus.Idle
                }
                publish()
                advanceAfterReplacement?.takeIf { findFocusIntentVersion == focusIntentAtRequest }?.let { (offset, strict) ->
                    val next = findResults.indexOfFirst { result ->
                        if (strict) result.navigationOffset > offset else result.navigationOffset >= offset
                    }
                    if (next >= 0) selectFindResult(next)
                    else findActionMessage = replacementAdvanceEndMessage()
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                if (owned()) {
                    findStatus = FindStatus.Failed(UiText.Resource(when {
                        !options.regex -> R.string.find_failed
                        replacement != null && search != null -> R.string.find_invalid_replacement
                        search == null -> R.string.find_invalid_query
                        else -> R.string.find_failed
                    }))
                    findCoverageMessage = UiText.Resource(R.string.find_incomplete)
                }
            } finally {
                search?.close()
                if (owned()) { compiledFind = null; findJob = null }
            }
        }
        return true
    }

    private fun navigateFindResults(direction: FindDirection): Boolean {
        if (!isFindVisible || findFieldValue.text.isEmpty() || isFindScopePaused) return false
        if (findStatus == FindStatus.Searching) {
            pendingFindDirection = direction
            return true
        }
        if (findResults.isEmpty()) {
            if (findStatus == FindStatus.Searching) pendingFindDirection = direction
            return false
        }
        val offset = if (findResultIndex < 0) findOriginUtf16Offset else currentDocumentLocation().offset
        val candidate = if (findResultIndex < 0) {
            if (direction == FindDirection.Forward) findResults.indexOfFirst { it.navigationOffset >= offset }
            else findResults.indexOfLast { it.navigationOffset <= offset }
        } else findResultIndex + if (direction == FindDirection.Forward) 1 else -1
        val wraps = candidate !in findResults.indices
        val target = if (!wraps) candidate else if (direction == FindDirection.Forward) 0 else findResults.lastIndex
        findActionMessage = when {
            !isFindComplete -> UiText.Resource(R.string.find_incomplete)
            isFindComplete && findResults.size == 1 -> UiText.Resource(R.string.find_only_match)
            wraps -> UiText.Resource(if (direction == FindDirection.Forward) R.string.find_wrapped_top else R.string.find_wrapped_bottom)
            else -> null
        }
        return selectFindResult(target, if (!wraps) null else if (direction == FindDirection.Forward) FindWrap.Beginning else FindWrap.End)
    }

    /** Explicit result selection is the only search operation that moves the passage. */
    fun selectFindResult(index: Int, wrappedAt: FindWrap? = null): Boolean {
        val result = findResults.getOrNull(index) ?: return false
        val revision = state.metrics?.revision ?: return false
        if (revision != findOriginRevision || state.hasActiveDraftChanges) return false
        val origin = currentDocumentLocation()
        val target = DocumentLocation(revision, presentation, result.navigationOffset)
        val focusIntentAtNavigation = findFocusIntentVersion
        findResultIndex = index
        if (presentation == EditorPresentation.MarkdownPreview) {
            val ready = markdownPreviewStatus as? MarkdownPreviewStatus.Ready ?: return false
            markdownPreviewStatus = ready.copy(scrollRestoration = SemanticViewportAnchor(revision, target.offset, 0))
            observeMarkdownPreviewViewportAnchor(revision, target.offset)
            locations.record(origin, target, find = true)
            locationVersion++
            findRevealFocusIntent = focusIntentAtNavigation
            findRevealRequest++
        } else {
            val generation = advancedFindGeneration
            launchOperation(operation = { state ->
                val position = try { state.resolvePosition(revision, result.navigationOffset) }
                    catch (_: Exception) {
                        findMatch = null
                        findResults = emptyList()
                        isFindComplete = false
                        findStatus = FindStatus.Failed(UiText.Resource(R.string.operation_find_interrupted))
                        return@launchOperation
                    }
                val canPublish = { generation == advancedFindGeneration && isFindVisible && findResultIndex == index }
                val matched = FindMatch(result.source ?: result.hit.range, position)
                val moved = if (isViewOnly && matched.range.start != matched.range.end)
                    state.navigateToMatch(matched, canPublish) == MatchViewportResult.Published
                else if (isViewOnly) state.navigateToSourceOffset(revision, result.navigationOffset, canPublish)
                else state.openSourceCaret(revision, result.navigationOffset) {
                        generation == advancedFindGeneration && isFindVisible && findResultIndex == index
                    }
                if (moved && generation == advancedFindGeneration && state.metrics?.revision == revision) {
                    findMatch = matched
                    findStatus = FindStatus.Match(findMatch!!, wrappedAt)
                    pendingEditWindowScrollRestoration = EditWindowScrollRestoration(SemanticViewportAnchor(revision, target.offset, 0))
                    viewportListState.requestScrollToItem(0)
                    locations.record(origin, target, find = true)
                    locationVersion++
                    findRevealFocusIntent = focusIntentAtNavigation
                    findRevealRequest++
                }
            }, requestDraftFocus = false)
        }
        return true
    }

    private fun currentDocumentLocation(): DocumentLocation {
        val revision = state.metrics?.revision ?: 0
        val offset = if (presentation == EditorPresentation.MarkdownPreview)
            markdownPreviewReturnTarget?.viewportAnchor?.sourceUtf16Offset ?: resolveVisibleViewportAnchor().sourceUtf16Offset
            else resolveVisibleViewportAnchor().sourceUtf16Offset
        val caret = activeDraft?.let { it.edit.snapshot.range.start + it.textFieldState.selection.end } ?: offset
        return DocumentLocation(revision, presentation, offset, caret)
    }

    fun returnToDocumentLocation(forward: Boolean) {
        if (state.hasActiveDraftChanges || state.status != EditorDocumentStatus.Ready) return
        val (target, skipped) = locations.move(forward)
        locationVersion++
        locationMessage = if (skipped) UiText.Resource(R.string.location_skipped) else null
        target ?: return
        if (target.revision != state.metrics?.revision) return
        clearDocumentSelection()
        pendingReadingSelection = null
        pendingReadingLocation = null
        if (target.presentation == EditorPresentation.MarkdownPreview && markdownRenderer != null) {
            if (presentation == EditorPresentation.Text) {
                launchOperation(operation = { state ->
                    state.activeEdit?.let { state.discardActiveEdit(it.generation) }
                    activeDraft = null
                    openMarkdownPreview(MarkdownPreviewReturnTarget(
                        SemanticViewportAnchor(target.revision, target.offset, 0), Utf16Range(target.caret, target.caret)),
                        locationFallback = target)
                }, requestDraftFocus = false)
            } else {
                val ready = markdownPreviewStatus as? MarkdownPreviewStatus.Ready
                when {
                    ready != null -> {
                        markdownPreviewStatus = ready.copy(scrollRestoration = SemanticViewportAnchor(target.revision, target.offset, 0))
                        observeMarkdownPreviewViewportAnchor(target.revision, target.offset)
                    }
                    markdownPreviewStatus is MarkdownPreviewStatus.Failed -> restoreDocumentLocationInSource(target, readingFallback = true)
                    else -> {
                        pendingReadingLocation = target
                        markdownPreviewReturnTarget = MarkdownPreviewReturnTarget(
                            SemanticViewportAnchor(target.revision, target.offset, 0), Utf16Range(target.caret, target.caret))
                    }
                }
            }
        } else {
            restoreDocumentLocationInSource(target, readingFallback = target.presentation == EditorPresentation.MarkdownPreview)
        }
    }

    private fun restoreDocumentLocationInSource(target: DocumentLocation, readingFallback: Boolean) {
        if (closeStarted.get() || target.revision != state.metrics?.revision) return
        if (readingFallback) locationMessage = UiText.Resource(R.string.location_source_fallback)
        pendingReadingLocation = null
        markdownPreviewReturnTarget = null
        presentation = EditorPresentation.Text
        invalidateMarkdownPreview()
        launchOperation(operation = { state ->
            val moved = if (isViewOnly) state.navigateToSourceOffset(target.revision, target.offset)
                else state.openSourceCaret(target.revision, target.caret)
            if (moved) {
                val anchor = SemanticViewportAnchor(target.revision, target.offset, 0)
                if (isViewOnly) readOnlySourceScrollRestoration = anchor
                else pendingEditWindowScrollRestoration = EditWindowScrollRestoration(anchor)
                viewportListState.requestScrollToItem(0)
            }
        }, requestDraftFocus = false, onCompletion = { refreshAdvancedFind() })
    }

    /** Counts actual included changes separately from matches already equal to their replacement. */
    val includedReplacementCount: Int get() = findResults.indices.count { index ->
        val result = findResults[index]
        index !in excludedFindResults && result.source != null &&
            result.hit.replacement != null && result.hit.replacement != result.hit.text
    }

    val unchangedReplacementCount: Int get() = findResults.indices.count { index ->
        val result = findResults[index]
        index !in excludedFindResults && result.source != null && result.hit.replacement == result.hit.text
    }

    val canApplyFindReplacements: Boolean get() = isReplaceVisible && isFindComplete &&
        reviewedFindRevision == state.metrics?.revision && !isFindScopePaused && !isViewOnly &&
        presentation == EditorPresentation.Text && !state.hasActiveDraftChanges &&
        state.status == EditorDocumentStatus.Ready && !isSaveBusy && !isSourceReloading &&
        includedReplacementCount > 0

    val canReplaceCurrent: Boolean get() = isReplaceVisible && !isFindScopePaused && !isViewOnly &&
        presentation == EditorPresentation.Text && findOriginRevision == state.metrics?.revision &&
        !state.hasActiveDraftChanges && state.status == EditorDocumentStatus.Ready &&
        !isSaveBusy && !isSourceReloading && findResults.getOrNull(findResultIndex)?.let {
            it.source != null && it.hit.replacement != null
        } == true

    private fun replacementAdvanceEndMessage() = UiText.Resource(
        if (isFindComplete) R.string.replace_end_scope else R.string.replace_no_further_verified_match)

    fun applyFindReplacements(currentOnly: Boolean = false): Boolean {
        if (if (currentOnly) !canReplaceCurrent else !canApplyFindReplacements) return false
        val selected = if (currentOnly) listOfNotNull(findResults.getOrNull(findResultIndex))
            else findResults.filterIndexed { index, _ -> index !in excludedFindResults }
        val patches = selected.mapNotNull { result ->
            val source = result.source ?: return@mapNotNull null
            val inserted = result.hit.replacement ?: return@mapNotNull null
            if (inserted == result.hit.text) null else DocumentPatch(source, result.hit.text, inserted)
        }
        if (patches.isEmpty()) {
            if (currentOnly && selected.isNotEmpty()) {
                val end = selected.single().hit.range.end
                val next = findResults.indexOfFirst { it.navigationOffset >= end && it != selected.single() }
                if (next >= 0) selectFindResult(next)
                else findActionMessage = replacementAdvanceEndMessage()
                return true
            }
            return false
        }
        if (patches.sumOf { it.retainedUnits.toLong() } > MAX_REPLACEMENT_REVIEW_UNITS) {
            findActionMessage = UiText.Resource(R.string.replace_review_limit)
            return false
        }
        val revision = if (currentOnly) findOriginRevision else reviewedFindRevision ?: return false
        val findGenerationAtApply = advancedFindGeneration
        val focusIntentAtApply = findFocusIntentVersion
        val before = currentDocumentLocation().caret
        val caret = patches.first().range.start + patches.first().inserted.length
        val selectionAfter = Utf16Range(caret, caret)
        var outcome: DocumentReplacementResult = DocumentReplacementResult.Unavailable
        launchOperation(operation = { state ->
            outcome = state.replaceDocumentBatch(revision, patches, selectionAfter)
        }, requestDraftFocus = false, onCompletion = {
            when (val result = outcome) {
                is DocumentReplacementResult.Applied -> {
                    val first = patches.first()
                    // A committed edit survives a new query, but its automatic jump does not.
                    val advance = currentOnly && isFindVisible && isReplaceVisible &&
                        advancedFindGeneration == findGenerationAtApply && findFocusIntentVersion == focusIntentAtApply
                    // Return only the review that committed this batch to the document.
                    // A newer query or navigation choice must keep its own destination.
                    if (!currentOnly && findResultsPage == FindResultsPage.Replacements &&
                        advancedFindGeneration == findGenerationAtApply && findFocusIntentVersion == focusIntentAtApply) {
                        findResultsPage = null
                        findInputFocus = FindInputFocus.Document
                        findRequestsKeyboard = false
                    }
                    recordCommittedEdit(CommittedEditDelta(revision, result.revision, first.range.start,
                        first.removed, first.inserted, Utf16Range(before, before), selectionAfter, patches), scopedReplacement = true)
                    undoableFindReplacementRevision = result.revision.takeIf { isFindVisible && isReplaceVisible }
                    invalidateMarkdownPreview()
                    recordSourceSaveRequest()
                    findActionMessage = UiText.Quantity(R.plurals.replace_applied, patches.size, listOf(patches.size))
                    refreshAdvancedFind(advanceAfterReplacement =
                        (caret to (first.range.start == first.range.end)).takeIf { advance })
                }
                DocumentReplacementResult.RejectedBySizeLimit -> findActionMessage = UiText.Resource(R.string.operation_save_document_too_large)
                else -> findActionMessage = state.editorMessage ?: UiText.Resource(R.string.operation_apply_change_failed)
            }
        })
        return true
    }

    /** Compatibility entry for document revision observers; refresh never navigates. */
    private fun launchFind(direction: FindDirection, delayed: Boolean): Boolean =
        refreshAdvancedFind(delayed = delayed)

    /** Starts one random-line load and moves only after successful publication. */
    private fun launchLineNavigation(logicalLine: Long) {
        require(logicalLine >= 0L) { "logical line must be nonnegative" }
        if (closeStarted.get()) {
            return
        }
        val origin = currentDocumentLocation()
        operationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (state.navigateToLine(logicalLine) && !closeStarted.get()) {
                state.blocks.firstOrNull()?.block?.globalUtf16Start?.let { offset ->
                    locations.record(origin, DocumentLocation(state.metrics!!.revision, presentation, offset))
                    locationVersion++
                }
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
            currentDraft.reconcileHistoryEditWindow(activeEdit,
                allowUnchangedRevision = historyAction.action == EditWindowAction.BulkInsert)
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
