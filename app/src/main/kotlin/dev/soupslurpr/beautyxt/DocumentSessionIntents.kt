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
