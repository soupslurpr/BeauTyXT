package dev.soupslurpr.beautyxt

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import dev.soupslurpr.beautyxt.document.resolveDocumentFormat
import dev.soupslurpr.beautyxt.importing.client.querySelectedDocumentMetadata
import dev.soupslurpr.beautyxt.importing.client.sanitizeProviderMimeType
import dev.soupslurpr.beautyxt.sharing.IncomingDocumentShare
import dev.soupslurpr.beautyxt.sharing.IncomingSourcePurpose
import dev.soupslurpr.beautyxt.ui.BeauTyXTApp
import dev.soupslurpr.beautyxt.ui.LegacyCleanupGate
import dev.soupslurpr.beautyxt.ui.RequestKeyboardPrivacy
import dev.soupslurpr.beautyxt.ui.UiText
import dev.soupslurpr.beautyxt.ui.designsystem.BeauTyXTTheme
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val ACTION_ACTIVE_DOCUMENT =
    "dev.soupslurpr.beautyxt.action.ACTIVE_DOCUMENT"
private const val STATE_DOCUMENT_SELECTION_LAUNCHED =
    "dev.soupslurpr.beautyxt.state.DOCUMENT_SELECTION_LAUNCHED"
private val DOCUMENT_PICKER_FAILURE_MESSAGE = UiText.Resource(R.string.operation_picker_failed)
private val DOCUMENT_SESSION_FAILURE_MESSAGE = UiText.Resource(
    R.string.operation_document_session_failure
)
private val UNSUPPORTED_DOCUMENT_MESSAGE = UiText.Resource(R.string.operation_unsupported_document)
private val UNAVAILABLE_DOCUMENT_MESSAGE = UiText.Resource(R.string.operation_unsupported_content)

/** Describes one provider source awaiting bounded metadata validation. */
internal data class PendingDocumentSource(
    val uri: Uri,
    val mimeType: String?,
    val purpose: IncomingSourcePurpose
)

/** Retains activity-entry state across configuration changes only in memory. */
internal class DocumentActivityModel : ViewModel() {
    val incomingShareState = mutableStateOf<IncomingDocumentShare?>(null)
    val initialDocumentActionState = mutableStateOf(InitialDocumentAction.Incoming)
    var initialized = false
    var selectionLaunched = false
    var pendingSource: PendingDocumentSource? = null
    var sourcePreparationGeneration = 0L
}

/** Hosts one document session while delegating task policy to its concrete entry point. */
abstract class DocumentActivity : ComponentActivity() {
    private val activityModel by viewModels<DocumentActivityModel>()
    private var sourcePreparationJob: Job? = null

    /** Returns the process-owned gate for provider access and document workflows. */
    internal val legacyCleanup: LegacyCleanup
        get() = (application as BeauTyXTApplication).legacyCleanup

    /** Returns what Android reveals when this document session finishes. */
    protected abstract val returnDestination: DocumentSessionReturnDestination

    /** Returns whether this manifest entry accepts one explicit activity intent. */
    protected abstract fun acceptsDocumentIntent(intent: Intent): Boolean

    /** Restores whether Android already owns the picker flow after process recreation. */
    protected fun restoreDocumentSelectionLaunched(selectionLaunched: Boolean) {
        activityModel.selectionLaunched = selectionLaunched
    }

    /** Returns whether Android already owns the picker flow. */
    protected fun hasLaunchedDocumentSelection(): Boolean = activityModel.selectionLaunched

    /** Records whether Android owns the picker flow. */
    protected fun setDocumentSelectionLaunched(selectionLaunched: Boolean) {
        activityModel.selectionLaunched = selectionLaunched
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        if (!activityModel.initialized) {
            val restoredAfterProcessDeath = savedInstanceState != null
            activityModel.initialized = true
            initializeDocumentIntent(
                intent = intent,
                restoredAfterProcessDeath = restoredAfterProcessDeath
            )
        }
        preparePendingSource()

        setContent {
            RequestKeyboardPrivacy {
                BeauTyXTTheme {
                    LegacyCleanupGate(cleanup = legacyCleanup, onClose = ::finish) {
                        BeauTyXTApp(
                            initialDocumentAction =
                                activityModel.initialDocumentActionState.value,
                            returnDestination = returnDestination,
                            incomingShare = activityModel.incomingShareState.value,
                            onIncomingShareConsumed = ::consumeIncomingShare,
                            onDocumentSessionClosed = ::finish
                        )
                    }
                }
            }
        }
    }

    /** Publishes one new external document intent into the retained session. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        initializeDocumentIntent(
            intent = intent,
            restoredAfterProcessDeath = false
        )
        preparePendingSource()
    }

    /** Replaces retained entry state with one validated activity request. */
    private fun initializeDocumentIntent(intent: Intent, restoredAfterProcessDeath: Boolean) {
        sourcePreparationJob?.cancel()
        sourcePreparationJob = null
        activityModel.sourcePreparationGeneration =
            Math.incrementExact(activityModel.sourcePreparationGeneration)
        activityModel.pendingSource = null
        activityModel.incomingShareState.value = null

        if (restoredAfterProcessDeath && isUnrestorableAfterProcessDeath(intent)) {
            activityModel.initialDocumentActionState.value =
                InitialDocumentAction.Unrestorable
            return
        }
        if (!acceptsDocumentIntent(intent)) {
            publishUnavailableDocument()
            return
        }

        val share = documentSessionShare(intent)
        if (isSelectedDocumentIntent(intent)) {
            queueSourcePreparation(
                uri = intent.data,
                mimeType = sanitizeProviderMimeType(intent.type),
                purpose = IncomingSourcePurpose.Edit
            )
            return
        }

        activityModel.initialDocumentActionState.value = initialDocumentAction(intent)
        activityModel.incomingShareState.value = share
    }

    /** Queues one source URI for bounded provider metadata discovery. */
    private fun queueSourcePreparation(
        uri: Uri?,
        mimeType: String?,
        purpose: IncomingSourcePurpose
    ) {
        if (uri?.scheme != "content" || uri.authority.isNullOrBlank()) {
            publishUnavailableDocument()
            return
        }
        activityModel.pendingSource =
            PendingDocumentSource(uri = uri, mimeType = mimeType, purpose = purpose)
        activityModel.initialDocumentActionState.value =
            InitialDocumentAction.OpeningSelectedDocument
    }

    /** Resolves the newest pending source without retaining provider metadata in saved state. */
    private fun preparePendingSource() {
        val pendingSource = activityModel.pendingSource ?: return
        val generation = Math.incrementExact(activityModel.sourcePreparationGeneration)
        activityModel.sourcePreparationGeneration = generation
        sourcePreparationJob?.cancel()
        lateinit var job: Job
        job =
            lifecycleScope.launch {
                legacyCleanup.awaitReady()
                val share = resolveSelectedDocumentShare(pendingSource)
                if (
                    activityModel.sourcePreparationGeneration == generation &&
                    activityModel.pendingSource == pendingSource
                ) {
                    activityModel.pendingSource = null
                    activityModel.initialDocumentActionState.value =
                        InitialDocumentAction.Incoming
                    activityModel.incomingShareState.value = share
                }
            }
        sourcePreparationJob = job
        job.invokeOnCompletion {
            if (sourcePreparationJob === job) {
                sourcePreparationJob = null
            }
        }
    }

    /** Resolves one provider source into an exact supported text offer. */
    private suspend fun resolveSelectedDocumentShare(
        pendingSource: PendingDocumentSource
    ): IncomingDocumentShare = try {
        val metadata = querySelectedDocumentMetadata(contentResolver, pendingSource.uri)
        val mimeType = metadata.mimeType ?: pendingSource.mimeType
        val format =
            resolveDocumentFormat(
                sourceName = metadata.displayName,
                mimeType = mimeType,
                alternateSourceName = pendingSource.uri.lastPathSegment
            )
        if (format == null) {
            IncomingDocumentShare.Rejected(UNSUPPORTED_DOCUMENT_MESSAGE)
        } else {
            IncomingDocumentShare.Source(
                encodedUri = pendingSource.uri.toString(),
                format = format,
                purpose = pendingSource.purpose
            )
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        IncomingDocumentShare.Rejected(UNAVAILABLE_DOCUMENT_MESSAGE)
    } catch (_: LinkageError) {
        IncomingDocumentShare.Rejected(UNAVAILABLE_DOCUMENT_MESSAGE)
    }

    /** Clears one offer only after Compose transfers it to retained memory. */
    private fun consumeIncomingShare(share: IncomingDocumentShare) {
        if (activityModel.incomingShareState.value != share) {
            return
        }
        activityModel.incomingShareState.value = null
        setIntent(
            Intent(this, this::class.java)
                .setAction(ACTION_ACTIVE_DOCUMENT)
        )
    }

    /** Publishes one sanitized rejection for an invalid activity request. */
    internal fun publishUnavailableDocument(message: UiText = UNAVAILABLE_DOCUMENT_MESSAGE) {
        activityModel.pendingSource = null
        activityModel.initialDocumentActionState.value = InitialDocumentAction.Incoming
        activityModel.incomingShareState.value = IncomingDocumentShare.Rejected(message)
    }
}

/** Hosts the stable workbench's non-exported document child. */
class HomeDocumentActivity : DocumentActivity() {
    override val returnDestination = DocumentSessionReturnDestination.Home

    private val selectDocument =
        registerForActivityResult(SelectDocumentContract()) { resultIntent ->
            val uri = resultIntent?.data
            if (uri == null) {
                finish()
                return@registerForActivityResult
            }
            try {
                startActivity(
                    createSelectedDocumentSessionIntent(
                        context = this,
                        uri = uri,
                        mimeType = resultIntent.type,
                        resultFlags = resultIntent.flags
                    )
                )
                finish()
            } catch (_: Exception) {
                publishUnavailableDocument(DOCUMENT_SESSION_FAILURE_MESSAGE)
            }
        }

    override fun acceptsDocumentIntent(intent: Intent): Boolean = isHomeDocumentIntent(intent)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isDocumentSelectionIntent(intent)) {
            return
        }
        if (savedInstanceState != null) {
            restoreDocumentSelectionLaunched(
                savedInstanceState.getBoolean(STATE_DOCUMENT_SELECTION_LAUNCHED)
            )
        }
        if (hasLaunchedDocumentSelection()) {
            return
        }
        lifecycleScope.launch {
            legacyCleanup.awaitReady()
            lifecycle.withResumed {
                if (!hasLaunchedDocumentSelection()) {
                    setDocumentSelectionLaunched(true)
                    try {
                        selectDocument.launch(Unit)
                    } catch (_: Exception) {
                        setDocumentSelectionLaunched(false)
                        publishUnavailableDocument(DOCUMENT_PICKER_FAILURE_MESSAGE)
                    }
                }
            }
        }
    }

    /** Retains only whether Android already owns the current picker flow. */
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(
            STATE_DOCUMENT_SELECTION_LAUNCHED,
            hasLaunchedDocumentSelection()
        )
        super.onSaveInstanceState(outState)
    }
}

/** Hosts one exported, canonical external document task. */
class MainActivity : DocumentActivity() {
    override val returnDestination = DocumentSessionReturnDestination.Caller

    override fun acceptsDocumentIntent(intent: Intent): Boolean = isExternalDocumentIntent(intent)
}
