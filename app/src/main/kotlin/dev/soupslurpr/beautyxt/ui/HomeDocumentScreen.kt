package dev.soupslurpr.beautyxt.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed
import dev.soupslurpr.beautyxt.DocumentSessionReturnDestination
import dev.soupslurpr.beautyxt.InitialDocumentAction
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.SelectDocumentContract
import dev.soupslurpr.beautyxt.mayHaveAmbientTreeAccessForUri
import dev.soupslurpr.beautyxt.document.resolveDocumentFormat
import dev.soupslurpr.beautyxt.importing.client.querySelectedDocumentMetadata
import dev.soupslurpr.beautyxt.importing.client.sanitizeProviderMimeType
import dev.soupslurpr.beautyxt.sharing.IncomingDocumentShare
import dev.soupslurpr.beautyxt.sharing.IncomingSourcePurpose
import kotlin.coroutines.cancellation.CancellationException

private data class SelectedHomeSource(
    val uri: Uri,
    val mimeType: String?,
    val offersRead: Boolean,
    val offersWrite: Boolean
)

/** Holds provider capabilities only in the live navigation entry's retained memory. */
private class HomeDocumentEntry(initialAction: InitialDocumentAction) {
    var action by mutableStateOf(initialAction)
    var source by mutableStateOf<SelectedHomeSource?>(null)
    var share by mutableStateOf<IncomingDocumentShare?>(null)
    var closed = false
}

/** Hosts Home's editor and provider picker without creating another activity. */
@Composable
internal fun HomeDocumentScreen(
    initialAction: InitialDocumentAction,
    isCurrentEntry: () -> Boolean,
    onClose: () -> Unit
) {
    // These flags contain no document data. A completed selection or live document must
    // become an ended session after process death; a pending system picker may still return.
    var sessionStarted by rememberSaveable { mutableStateOf(false) }
    var selectionLaunched by rememberSaveable { mutableStateOf(false) }
    val entry = retain {
        HomeDocumentEntry(
            if (sessionStarted) InitialDocumentAction.Unrestorable else initialAction
        )
    }
    val currentIsCurrentEntry by rememberUpdatedState(isCurrentEntry)
    val currentOnClose by rememberUpdatedState(onClose)
    val applicationContext = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    fun closeEntry() {
        if (!entry.closed && currentIsCurrentEntry()) {
            entry.closed = true
            entry.source = null
            entry.share = null
            currentOnClose()
        }
    }

    val selectDocument = rememberLauncherForActivityResult(SelectDocumentContract()) { result ->
        if (!entry.closed && currentIsCurrentEntry() && !sessionStarted) {
            val uri = result?.data
            if (uri == null) {
                closeEntry()
            } else {
                sessionStarted = true
                entry.action = InitialDocumentAction.OpeningSelectedDocument
                entry.source = SelectedHomeSource(
                    uri = uri,
                    mimeType = sanitizeProviderMimeType(result.type),
                    offersRead = (result.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0,
                    offersWrite = (result.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0
                )
            }
        }
    }
    SideEffect {
        if (initialAction != InitialDocumentAction.SelectDocument) sessionStarted = true
    }
    LaunchedEffect(entry) {
        if (entry.action == InitialDocumentAction.SelectDocument && !selectionLaunched) {
            lifecycle.withResumed {
                if (!entry.closed && currentIsCurrentEntry()) {
                    selectionLaunched = true
                    try {
                        selectDocument.launch(Unit)
                    } catch (_: Exception) {
                        sessionStarted = true
                        entry.action = InitialDocumentAction.Incoming
                        entry.share = IncomingDocumentShare.Rejected(
                            UiText.Resource(R.string.operation_picker_failed)
                        )
                    }
                }
            }
        }
    }
    val source = entry.source
    LaunchedEffect(source) {
        if (source != null) {
            val share = try {
                require(source.uri.scheme == "content" && !source.uri.authority.isNullOrBlank())
                val ambientTreePossible = mayHaveAmbientTreeAccessForUri(applicationContext, source.uri)
                if (ambientTreePossible && !source.offersRead) {
                    IncomingDocumentShare.Rejected(
                        UiText.Resource(R.string.operation_incoming_content_unavailable)
                    )
                } else {
                    val metadata = querySelectedDocumentMetadata(
                        applicationContext.contentResolver,
                        source.uri
                    )
                    val format = resolveDocumentFormat(
                        sourceName = metadata.displayName,
                        mimeType = metadata.mimeType ?: source.mimeType,
                        alternateSourceName = source.uri.lastPathSegment
                    )
                    if (format == null) {
                        IncomingDocumentShare.Rejected(
                            UiText.Resource(R.string.operation_unsupported_document)
                        )
                    } else {
                        val allowWrite = source.offersWrite || !ambientTreePossible
                        IncomingDocumentShare.Source(
                            encodedUri = source.uri.toString(),
                            format = format,
                            purpose = if (allowWrite) {
                                IncomingSourcePurpose.Open
                            } else {
                                IncomingSourcePurpose.View
                            }
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                IncomingDocumentShare.Rejected(UiText.Resource(R.string.operation_unsupported_content))
            } catch (_: LinkageError) {
                IncomingDocumentShare.Rejected(UiText.Resource(R.string.operation_unsupported_content))
            }
            if (!entry.closed && currentIsCurrentEntry() && entry.source === source) {
                entry.source = null
                entry.action = InitialDocumentAction.Incoming
                entry.share = share
            }
        }
    }
    RequestKeyboardPrivacy {
        BeauTyXTApp(
            initialDocumentAction = entry.action,
            returnDestination = DocumentSessionReturnDestination.Home,
            incomingShare = entry.share,
            onIncomingShareConsumed = { share ->
                if (entry.share === share) entry.share = null
            },
            onDocumentSessionClosed = ::closeEntry
        )
    }
}
