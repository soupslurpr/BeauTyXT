package dev.soupslurpr.beautyxt.sharing

import android.content.Intent
import android.net.Uri
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.document.DocumentFormat
import dev.soupslurpr.beautyxt.document.hasWellFormedUtf16
import dev.soupslurpr.beautyxt.document.resolveDocumentFormat
import dev.soupslurpr.beautyxt.transfer.client.ReceivedNfcMetadata
import dev.soupslurpr.beautyxt.ui.UiText

private val INCOMING_TEXT_TOO_LARGE_MESSAGE =
    UiText.Resource(R.string.operation_incoming_text_too_large)
private val INCOMING_CONTENT_UNAVAILABLE_MESSAGE =
    UiText.Resource(R.string.operation_incoming_content_unavailable)

/** Describes why Android delivered one provider-backed text source. */
internal enum class IncomingSourcePurpose {
    /** Indicates that another app shared a file for explicit review. */
    Share,

    /** Indicates that another app requested read-only viewing. */
    View,

    /** Indicates that another app requested editing when write access is available. */
    Edit
}

/** Contains one bounded incoming offer that remains unopened until confirmation. */
internal sealed interface IncomingDocumentShare {
    /** Contains one bounded text extra already delivered by Android. */
    data class Text(
        val text: String,
        val format: DocumentFormat,
        val nfcMetadata: ReceivedNfcMetadata? = null
    ) : IncomingDocumentShare

    /** Retains one temporary provider URI without opening its descriptor. */
    data class Source(
        val encodedUri: String,
        val format: DocumentFormat,
        val purpose: IncomingSourcePurpose = IncomingSourcePurpose.Share
    ) : IncomingDocumentShare {
        init {
            require(encodedUri.isNotBlank()) { "incoming source URI must not be blank" }
        }
    }

    /** Contains one sanitized rejection that can be safely shown to the user. */
    data class Rejected(val message: UiText) : IncomingDocumentShare
}

/** Extracts one bounded text or content-URI offer from a supported Android intent. */
internal fun parseIncomingDocumentShare(intent: Intent): IncomingDocumentShare? {
    val purpose = incomingSourcePurposeForAction(intent.action) ?: return null
    return try {
        when (purpose) {
            IncomingSourcePurpose.Share -> parseIncomingSend(intent)

            IncomingSourcePurpose.View,
            IncomingSourcePurpose.Edit -> parseIncomingSource(intent, purpose)
        }
    } catch (_: Exception) {
        IncomingDocumentShare.Rejected(INCOMING_CONTENT_UNAVAILABLE_MESSAGE)
    }
}

/** Returns the source purpose represented by one supported Android action. */
internal fun incomingSourcePurposeForAction(action: String?): IncomingSourcePurpose? =
    when (action) {
        Intent.ACTION_SEND -> IncomingSourcePurpose.Share
        Intent.ACTION_VIEW -> IncomingSourcePurpose.View
        Intent.ACTION_EDIT -> IncomingSourcePurpose.Edit
        else -> null
    }

/** Returns the in-memory title used for one confirmed direct text share. */
internal fun incomingSharedTextTitle(format: DocumentFormat): String = when (format) {
    DocumentFormat.PlainText -> "Shared text.txt"
    DocumentFormat.Markdown -> "Shared document.md"
}

/** Normalizes Android text extras to the editor's canonical line endings. */
internal fun normalizeIncomingSharedText(text: String): String {
    if ('\r' !in text) {
        return text
    }
    return buildString(text.length) {
        var index = 0
        while (index < text.length) {
            val character = text[index]
            if (character != '\r') {
                append(character)
                index += 1
                continue
            }
            append('\n')
            index += 1
            if (index < text.length && text[index] == '\n') {
                index += 1
            }
        }
    }
}

/** Parses one send intent as either a bounded text extra or a reviewed source. */
private fun parseIncomingSend(intent: Intent): IncomingDocumentShare {
    val sourceUri = incomingSourceUri(intent, includeDataUri = false)
    val format = incomingDocumentFormat(intent.type, sourceUri)
        ?: return IncomingDocumentShare.Rejected(INCOMING_CONTENT_UNAVAILABLE_MESSAGE)
    return if (sourceUri == null) {
        incomingText(intent, format)
    } else {
        incomingSource(sourceUri, format, IncomingSourcePurpose.Share)
    }
}

/** Parses one view or edit intent as exactly one provider-backed source. */
private fun parseIncomingSource(
    intent: Intent,
    purpose: IncomingSourcePurpose
): IncomingDocumentShare {
    require(purpose != IncomingSourcePurpose.Share) {
        "view or edit source purpose required"
    }
    val sourceUri = incomingSourceUri(intent, includeDataUri = true)
        ?: return IncomingDocumentShare.Rejected(INCOMING_CONTENT_UNAVAILABLE_MESSAGE)
    val format = incomingDocumentFormat(intent.type, sourceUri)
        ?: return IncomingDocumentShare.Rejected(INCOMING_CONTENT_UNAVAILABLE_MESSAGE)
    return incomingSource(sourceUri, format, purpose)
}

/** Returns one source offer only for a non-ambiguous content URI. */
private fun incomingSource(
    sourceUri: Uri,
    format: DocumentFormat,
    purpose: IncomingSourcePurpose
): IncomingDocumentShare =
    if (sourceUri.scheme != "content" || sourceUri.authority.isNullOrBlank()) {
        IncomingDocumentShare.Rejected(INCOMING_CONTENT_UNAVAILABLE_MESSAGE)
    } else {
        IncomingDocumentShare.Source(
            encodedUri = sourceUri.toString(),
            format = format,
            purpose = purpose
        )
    }

/** Returns a supported format from MIME data or a recognizable source suffix. */
private fun incomingDocumentFormat(mimeType: String?, sourceUri: Uri?): DocumentFormat? =
    resolveDocumentFormat(sourceUri?.lastPathSegment, mimeType)

/** Returns one URI only when an intent carries one unambiguous stream item. */
private fun incomingSourceUri(intent: Intent, includeDataUri: Boolean): Uri? {
    val extraUri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    val clipData = intent.clipData
    if (clipData != null && clipData.itemCount > 1) {
        throw IllegalArgumentException("incoming share contains multiple items")
    }
    val clipUri = clipData?.takeIf { data -> data.itemCount == 1 }?.getItemAt(0)?.uri
    val dataUri = if (includeDataUri) intent.data else null
    val distinctUris = listOfNotNull(extraUri, clipUri, dataUri).distinct()
    if (distinctUris.size > 1) {
        throw IllegalArgumentException("incoming share URI is ambiguous")
    }
    return distinctUris.singleOrNull()
}

/** Validates and normalizes one direct Android text extra. */
private fun incomingText(intent: Intent, format: DocumentFormat): IncomingDocumentShare {
    val characters = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
        ?: return IncomingDocumentShare.Rejected(INCOMING_CONTENT_UNAVAILABLE_MESSAGE)
    if (characters.length > MAX_INCOMING_TEXT_UTF16_UNITS) {
        return IncomingDocumentShare.Rejected(INCOMING_TEXT_TOO_LARGE_MESSAGE)
    }
    val text = normalizeIncomingSharedText(characters.toString())
    if (!text.hasWellFormedUtf16()) {
        return IncomingDocumentShare.Rejected(INCOMING_CONTENT_UNAVAILABLE_MESSAGE)
    }
    if (!acceptsDirectSharedText(text)) {
        return IncomingDocumentShare.Rejected(INCOMING_TEXT_TOO_LARGE_MESSAGE)
    }
    return IncomingDocumentShare.Text(text = text, format = format)
}
