/* Defines validated entry contracts and content-free document task identities. */
package dev.soupslurpr.beautyxt

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.net.toUri
import dev.soupslurpr.beautyxt.document.DocumentFormat
import dev.soupslurpr.beautyxt.sharing.IncomingDocumentShare
import dev.soupslurpr.beautyxt.sharing.parseIncomingDocumentShare
import dev.soupslurpr.beautyxt.ui.openDocumentMimeTypes
import java.util.UUID

private const val ACTION_NEW_DOCUMENT =
    "dev.soupslurpr.beautyxt.action.NEW_DOCUMENT"
private const val ACTION_SCAN_QR =
    "dev.soupslurpr.beautyxt.action.SCAN_QR"
private const val ACTION_READ_NFC =
    "dev.soupslurpr.beautyxt.action.READ_NFC"
private const val ACTION_SELECT_DOCUMENT =
    "dev.soupslurpr.beautyxt.action.SELECT_DOCUMENT"
private const val ACTION_OPEN_SELECTED_DOCUMENT =
    "dev.soupslurpr.beautyxt.action.OPEN_SELECTED_DOCUMENT"
private const val SESSION_SCHEME = "beautyxt"
private const val SESSION_AUTHORITY = "session"
private const val TEMPORARY_URI_GRANT_FLAGS =
    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

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

    /** Opens the QR scanner before a document exists. */
    ScanQr,

    /** Opens NFC reading before a document exists. */
    ReadNfc,

    /** Reports that Android restored a memory-only session without its content. */
    Unrestorable
}

/** Describes what Android reveals after one document session finishes. */
enum class DocumentSessionReturnDestination {
    /** Returns to BeauTyXT's Home activity. */
    Home,

    /** Returns to the external caller that opened the document task. */
    Caller
}

/** Returns the initial document action encoded by one activity intent. */
internal fun initialDocumentAction(intent: Intent): InitialDocumentAction = when (intent.action) {
    ACTION_NEW_DOCUMENT -> InitialDocumentAction.NewDocument
    ACTION_SELECT_DOCUMENT -> InitialDocumentAction.SelectDocument
    ACTION_OPEN_SELECTED_DOCUMENT -> InitialDocumentAction.OpeningSelectedDocument
    ACTION_SCAN_QR -> InitialDocumentAction.ScanQr
    ACTION_READ_NFC -> InitialDocumentAction.ReadNfc
    else -> InitialDocumentAction.Incoming
}

/** Extracts one external or internally selected document offer. */
internal fun documentSessionShare(intent: Intent): IncomingDocumentShare? =
    if (intent.action != ACTION_OPEN_SELECTED_DOCUMENT) {
        parseIncomingDocumentShare(intent)
    } else {
        null
    }

/** Returns whether one explicit intent belongs to the non-exported Home document host. */
internal fun isHomeDocumentIntent(intent: Intent): Boolean = intent.action == ACTION_NEW_DOCUMENT ||
    intent.action == ACTION_SELECT_DOCUMENT ||
    intent.action == ACTION_OPEN_SELECTED_DOCUMENT ||
    intent.action == ACTION_SCAN_QR ||
    intent.action == ACTION_READ_NFC

/** Returns whether one Home document intent owns the provider-selection screen. */
internal fun isDocumentSelectionIntent(intent: Intent): Boolean =
    intent.action == ACTION_SELECT_DOCUMENT

/** Returns whether one Home document intent carries a selected provider source. */
internal fun isSelectedDocumentIntent(intent: Intent): Boolean =
    intent.action == ACTION_OPEN_SELECTED_DOCUMENT

/** Returns whether one exported document intent carries supported external content. */
internal fun isExternalDocumentIntent(intent: Intent): Boolean =
    intent.action == Intent.ACTION_VIEW ||
        intent.action == Intent.ACTION_EDIT ||
        intent.action == Intent.ACTION_SEND

/** Returns whether process death must replace one document session with an empty notice. */
internal fun isUnrestorableAfterProcessDeath(intent: Intent): Boolean =
    !isDocumentSelectionIntent(intent)

/** Creates one uniquely identified transient document task. */
internal fun createNewDocumentSessionIntent(context: Context): Intent =
    createTransientDocumentSessionIntent(
        context = context,
        action = ACTION_NEW_DOCUMENT,
        kind = "new"
    )

/** Creates the non-exported child that owns one provider-selection attempt. */
internal fun createDocumentSelectionIntent(context: Context): Intent =
    Intent(ACTION_SELECT_DOCUMENT, null, context, HomeDocumentActivity::class.java)

/** Creates one uniquely identified QR receive task. */
internal fun createQrDocumentSessionIntent(context: Context): Intent =
    createTransientDocumentSessionIntent(
        context = context,
        action = ACTION_SCAN_QR,
        kind = "qr"
    )

/** Creates one uniquely identified NFC receive task. */
internal fun createNfcDocumentSessionIntent(context: Context): Intent =
    createTransientDocumentSessionIntent(
        context = context,
        action = ACTION_READ_NFC,
        kind = "nfc"
    )

/** Creates one canonical source-backed document task from a picker result. */
internal fun createSelectedDocumentSessionIntent(
    context: Context,
    uri: Uri,
    mimeType: String?,
    resultFlags: Int
): Intent {
    require(uri.scheme == "content" && !uri.authority.isNullOrBlank()) {
        "selected document must use an authoritative content URI"
    }
    val grantFlags = resultFlags and TEMPORARY_URI_GRANT_FLAGS
    return Intent(
        ACTION_OPEN_SELECTED_DOCUMENT,
        uri,
        context,
        HomeDocumentActivity::class.java
    ).apply {
        setDataAndType(uri, mimeType)
        clipData = ClipData.newRawUri("Selected document", uri)
        addFlags(grantFlags)
    }
}

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

/** Creates one internal task intent whose opaque URI prevents accidental task reuse. */
private fun createTransientDocumentSessionIntent(
    context: Context,
    action: String,
    kind: String
): Intent = Intent(action, newSessionUri(kind), context, HomeDocumentActivity::class.java)

/** Creates the provider picker owned by one short-lived Home document child. */
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
