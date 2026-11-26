/* Defines validated entry contracts and content-free document task identities. */
package dev.soupslurpr.beautyxt

import android.app.Activity
import android.app.ComponentCaller
import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.net.toUri
import dev.soupslurpr.beautyxt.document.DocumentFormat
import dev.soupslurpr.beautyxt.sharing.IncomingDocumentShare
import dev.soupslurpr.beautyxt.sharing.IncomingSourcePurpose
import dev.soupslurpr.beautyxt.sharing.parseIncomingDocumentShare
import dev.soupslurpr.beautyxt.ui.UiText
import dev.soupslurpr.beautyxt.ui.openDocumentMimeTypes
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val SESSION_SCHEME = "beautyxt"
private const val SESSION_AUTHORITY = "session"
private const val TEMPORARY_URI_GRANT_FLAGS =
    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

internal const val ACTION_WINDOW_NEW = "dev.soupslurpr.beautyxt.action.WINDOW_NEW"
internal const val ACTION_WINDOW_OPEN = "dev.soupslurpr.beautyxt.action.WINDOW_OPEN"
internal const val ACTION_WINDOW_PASTE = "dev.soupslurpr.beautyxt.action.WINDOW_PASTE"
internal const val ACTION_WINDOW_SCAN_QR = "dev.soupslurpr.beautyxt.action.WINDOW_SCAN_QR"

/** Describes one action that starts an otherwise empty document session. */
internal enum class InitialDocumentAction {
    /** Waits for an Android source or share intent. */
    Incoming,

    /** Creates a new transient document. */
    NewDocument,

    /** Opens Android's provider picker before a document exists. */
    SelectDocument,

    /** Resolves one selected source before isolated import begins. */
    OpeningSelectedDocument,

    /** Reads plain clipboard text explicitly once the window is focused. */
    PasteClipboard,

    /** Opens the QR scanner before a document exists. */
    ScanQr,

    /** Opens NFC reading before a document exists. */
    ReadNfc,

    /** Reports that Android restored a memory-only session without its content. */
    Unrestorable
}

/** Maps one non-exported multi-window or static launcher-shortcut intent to its start action. */
internal fun resolveDocumentWindowAction(intent: Intent?): InitialDocumentAction =
    when (intent?.action) {
        ACTION_WINDOW_OPEN -> InitialDocumentAction.SelectDocument
        ACTION_WINDOW_PASTE -> InitialDocumentAction.PasteClipboard
        ACTION_WINDOW_SCAN_QR -> InitialDocumentAction.ScanQr
        ACTION_WINDOW_NEW, null -> InitialDocumentAction.NewDocument
        else -> InitialDocumentAction.NewDocument
    }

/** Creates an explicit, content-free intent that launches a separate BeauTyXT document window. */
internal fun createNewDocumentWindowIntent(
    context: Context,
    action: String = ACTION_WINDOW_NEW,
    launchAdjacent: Boolean = true
): Intent = Intent(action, newSessionUri("window"), context, DocumentWindowActivity::class.java).apply {
    addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
    if (launchAdjacent) {
        addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
    }
}

/** Describes what Android reveals after one document session finishes. */
enum class DocumentSessionReturnDestination {
    /** Returns to BeauTyXT's Home screen. */
    Home,

    /** Returns to the external caller that opened the document task. */
    Caller
}

/** Extracts an external document offer while preserving its exact capabilities. */
internal fun documentSessionShare(intent: Intent): IncomingDocumentShare? =
    parseIncomingDocumentShare(intent)

/** Returns whether one exported document intent carries supported external content. */
internal fun isExternalDocumentIntent(intent: Intent): Boolean =
    intent.action == Intent.ACTION_VIEW ||
        intent.action == Intent.ACTION_EDIT ||
        intent.action == Intent.ACTION_SEND

/** Normalizes one external share into a uniquely identified document task. */
internal fun createSharedDocumentSessionIntent(context: Context, source: Intent): Intent {
    require(source.action == Intent.ACTION_SEND) { "send action required" }
    val share = checkNotNull(parseIncomingDocumentShare(source)) {
        "send action did not produce a share offer"
    }
    val mimeType =
        when (share) {
            is IncomingDocumentShare.Text -> share.format.mimeType
            is IncomingDocumentShare.Source -> share.format.mimeType
            is IncomingDocumentShare.Rejected -> DocumentFormat.PlainText.mimeType
        }
    return Intent(
        Intent.ACTION_SEND,
        null,
        context,
        MainActivity::class.java
    ).apply {
        setDataAndType(newSessionUri("share"), mimeType)
        addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        when (share) {
            is IncomingDocumentShare.Text -> {
                putExtra(Intent.EXTRA_TEXT, share.text)
            }

            is IncomingDocumentShare.Source -> {
                val uri = share.encodedUri.toUri()
                clipData = ClipData.newRawUri("Shared document", uri)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(source.flags and TEMPORARY_URI_GRANT_FLAGS)
            }

            is IncomingDocumentShare.Rejected -> Unit
        }
    }
}

/** Creates the provider picker owned by one Home document navigation entry. */
internal fun createOpenDocumentPickerIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
    .addCategory(Intent.CATEGORY_OPENABLE)
    .setType("*/*")
    .putExtra(Intent.EXTRA_MIME_TYPES, openDocumentMimeTypes())

/** Retains provider MIME and grant metadata only when document selection succeeds. */
internal class SelectDocumentContract : ActivityResultContract<Unit, Intent?>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        createOpenDocumentPickerIntent()

    override fun parseResult(resultCode: Int, intent: Intent?): Intent? =
        intent.takeIf { resultCode == Activity.RESULT_OK }
}

/** Captures one destination picker URI along with the temporary URI grant flags offered. */
internal data class SelectedDestinationResult(
    val uri: Uri,
    val offersRead: Boolean,
    val offersWrite: Boolean
)

/** Creates one openable destination while preserving the picker's offered URI grant flags. */
internal class CreateDocumentDestinationContract(
    private val mimeType: String
) : ActivityResultContract<String, SelectedDestinationResult?>() {
    override fun createIntent(context: Context, input: String): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mimeType)
            .putExtra(Intent.EXTRA_TITLE, input)

    override fun parseResult(resultCode: Int, intent: Intent?): SelectedDestinationResult? {
        if (resultCode != Activity.RESULT_OK || intent == null) return null
        val uri = intent.data ?: return null
        return SelectedDestinationResult(
            uri = uri,
            offersRead = (intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0,
            offersWrite = (intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0
        )
    }
}

/**
 * Strips any URI grant flags from an activity result that the returning [caller] does not
 * actually hold when [data] points into a provider where BeauTyXT may hold ambient tree access.
 */
internal fun sanitizePickerResultIntent(
    context: Context,
    resultCode: Int,
    data: Intent?,
    caller: ComponentCaller
): Intent? {
    if (resultCode != Activity.RESULT_OK || data == null) return data
    val uri = data.data ?: return data
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) return data
    if (!mayHaveAmbientTreeAccessForUri(context, uri)) return data
    if (caller.uid == Process.myUid()) return data
    val callerHasRead = try {
        caller.checkContentUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) {
        false
    }
    val callerHasWrite = try {
        caller.checkContentUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) {
        false
    }
    var allowedFlags = data.flags
    if (!callerHasRead) {
        allowedFlags = allowedFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION.inv()
    }
    if (!callerHasWrite) {
        allowedFlags = allowedFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION.inv()
    }
    if (!callerHasRead || !callerHasWrite) {
        allowedFlags = allowedFlags and
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION.inv() and
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION.inv()
    }
    return if (allowedFlags == data.flags) data else Intent(data).setFlags(allowedFlags)
}

private val observedTreeGrantAuthorities: MutableSet<String> = ConcurrentHashMap.newKeySet()

/** Normalizes Android user-qualified authorities (`0@authority`) to the provider authority. */
internal fun normalizedProviderAuthority(uri: Uri): String? =
    uri.authority?.substringAfterLast('@')?.takeIf(String::isNotBlank)

/** Records any provider authority for which this process held a tree grant (even after release). */
internal fun recordObservedTreeGrantAuthority(uri: Uri) {
    normalizedProviderAuthority(uri)?.let(observedTreeGrantAuthorities::add)
}

/**
 * Returns whether BeauTyXT may hold ambient access to [uri] via a persisted or previously held
 * SAF tree grant in this process (including user-qualified authority aliases and temporary
 * picker grants that remain active after releasing persistable permission).
 */
internal fun mayHaveAmbientTreeAccessForUri(
    context: Context,
    uri: Uri
): Boolean {
    val targetAuthority = normalizedProviderAuthority(uri) ?: return false
    if (targetAuthority in observedTreeGrantAuthorities) {
        return true
    }
    val hasPersistedMatch = context.contentResolver.persistedUriPermissions.any { permission ->
        val authority = normalizedProviderAuthority(permission.uri)
        if (authority != null) {
            observedTreeGrantAuthorities.add(authority)
        }
        authority == targetAuthority
    }
    if (hasPersistedMatch) {
        return true
    }
    return try {
        val lookupUri = dev.soupslurpr.beautyxt.ui.editor.stripUserQualifierFromContentUri(uri)
        if (DocumentsContract.isTreeUri(lookupUri) || DocumentsContract.isDocumentUri(context, lookupUri)) {
            return true
        }
        val pathDocId = dev.soupslurpr.beautyxt.ui.editor.safDocumentIdFromPath(lookupUri)
        val rawAuth = lookupUri.authority
        pathDocId != null && !rawAuth.isNullOrBlank() &&
            DocumentsContract.isDocumentUri(context, DocumentsContract.buildDocumentUri(rawAuth, pathDocId))
    } catch (_: Exception) {
        false
    }
}

private fun intentCoversUriForGrantFlags(intent: Intent, uri: Uri): Boolean {
    if (intent.data == uri) return true
    val clip = intent.clipData ?: return false
    for (index in 0 until clip.itemCount) {
        if (clip.getItemAt(index).uri == uri) return true
    }
    return false
}

/**
 * Prevents an external caller from leveraging BeauTyXT's opt-in persisted or residual tree grant
 * as a confused deputy. Enforces both delegated intent grant flags bound to [share]'s exact URI
 * and caller URI permission on any DocumentsProvider URI or authority where this process
 * holds/held a tree grant, while preserving providers like MediaStore that enforce ownership
 * outside UriGrantsManagerService.
 */
internal fun enforceIncomingCallerUriAccess(
    context: Context,
    intent: Intent,
    caller: ComponentCaller,
    share: IncomingDocumentShare
): IncomingDocumentShare {
    if (share !is IncomingDocumentShare.Source) return share
    if (caller.uid == Process.myUid()) return share
    val uri = share.encodedUri.toUri()
    if (!mayHaveAmbientTreeAccessForUri(context, uri)) {
        return share
    }
    val uriCoveredByGrantFlags = intentCoversUriForGrantFlags(intent, uri)
    val delegatedRead = uriCoveredByGrantFlags &&
        (intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0
    val hasRead = delegatedRead && try {
        caller.checkContentUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) {
        false
    }
    if (!hasRead) {
        return IncomingDocumentShare.Rejected(
            UiText.Resource(R.string.operation_incoming_content_unavailable)
        )
    }
    if (share.purpose == IncomingSourcePurpose.Edit) {
        val delegatedWrite = uriCoveredByGrantFlags &&
            (intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0
        val hasWrite = delegatedWrite && try {
            caller.checkContentUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
        if (!hasWrite) {
            return share.copy(purpose = IncomingSourcePurpose.View)
        }
    }
    return share
}

/** Returns one opaque, content-free task identity. */
private fun newSessionUri(kind: String): Uri {
    require(kind.isNotBlank()) { "session kind must not be blank" }
    return Uri.Builder()
        .scheme(SESSION_SCHEME)
        .authority(SESSION_AUTHORITY)
        .appendPath(kind)
        .appendPath(UUID.randomUUID().toString())
        .build()
}
