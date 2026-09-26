package dev.soupslurpr.beautyxt

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import dev.soupslurpr.beautyxt.sharing.IncomingDocumentShare
import dev.soupslurpr.beautyxt.ui.BeauTyXTApp
import dev.soupslurpr.beautyxt.ui.LegacyCleanupGate
import dev.soupslurpr.beautyxt.ui.RequestKeyboardPrivacy
import dev.soupslurpr.beautyxt.ui.UiText
import dev.soupslurpr.beautyxt.ui.designsystem.BeauTyXTTheme

private const val ACTION_ACTIVE_DOCUMENT =
    "dev.soupslurpr.beautyxt.action.ACTIVE_DOCUMENT"

/** Retains activity-entry state across configuration changes only in memory. */
internal class DocumentActivityModel : ViewModel() {
    val incomingShareState = mutableStateOf<IncomingDocumentShare?>(null)
    val initialDocumentActionState = mutableStateOf(InitialDocumentAction.Incoming)
    var initialized = false
}

/** Hosts one document session while delegating task policy to its concrete entry point. */
abstract class DocumentActivity : ComponentActivity() {
    private val activityModel by viewModels<DocumentActivityModel>()

    /** Returns the process-owned gate for provider access and document workflows. */
    internal val legacyCleanup: LegacyCleanup
        get() = (application as BeauTyXTApplication).legacyCleanup

    /** Returns what Android reveals when this document session finishes. */
    protected abstract val returnDestination: DocumentSessionReturnDestination

    /** Returns whether this manifest entry accepts one explicit activity intent. */
    protected abstract fun acceptsDocumentIntent(intent: Intent): Boolean

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
    }

    /** Replaces retained entry state with one validated activity request. */
    private fun initializeDocumentIntent(intent: Intent, restoredAfterProcessDeath: Boolean) {
        activityModel.incomingShareState.value = null

        if (restoredAfterProcessDeath) {
            activityModel.initialDocumentActionState.value =
                InitialDocumentAction.Unrestorable
            return
        }
        if (!acceptsDocumentIntent(intent)) {
            activityModel.initialDocumentActionState.value = InitialDocumentAction.Incoming
            activityModel.incomingShareState.value =
                IncomingDocumentShare.Rejected(
                    UiText.Resource(R.string.operation_unsupported_content)
                )
            return
        }

        activityModel.initialDocumentActionState.value = InitialDocumentAction.Incoming
        activityModel.incomingShareState.value = documentSessionShare(intent)
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
}

/** Hosts one exported, canonical external document task. */
class MainActivity : DocumentActivity() {
    override val returnDestination = DocumentSessionReturnDestination.Caller

    override fun acceptsDocumentIntent(intent: Intent): Boolean = isExternalDocumentIntent(intent)
}
