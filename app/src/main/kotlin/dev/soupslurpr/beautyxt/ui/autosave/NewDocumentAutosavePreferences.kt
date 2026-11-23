/* Defines content-free, canonical preferences for opt-in new-document autosave. */
package dev.soupslurpr.beautyxt.ui.autosave

import dev.soupslurpr.beautyxt.document.DocumentFormat
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

internal const val NEW_DOCUMENT_AUTOSAVE_FILE_NAME = "new-document-autosave.properties"
internal const val MAX_NEW_DOCUMENT_AUTOSAVE_BYTES = 1_024
private const val STATE_KEY = "state"
private const val FORMAT_KEY = "format"
private const val GRANT_TIME_KEY = "grant_time"
private const val STATE_ENABLED = "enabled"
private const val STATE_CHANGING = "changing"
private const val FORMAT_PLAIN_TEXT = "txt"
private const val FORMAT_MARKDOWN = "md"

/** Identifies the durable opt-in state without storing folder URIs or paths on disk. */
internal enum class NewDocumentAutosaveMarkerState {
    Disabled,
    Enabled,
    Changing
}

/** Holds content-free opt-in autosave state; Android holds the persisted tree URI grant. */
internal data class NewDocumentAutosaveMarker(
    val state: NewDocumentAutosaveMarkerState = NewDocumentAutosaveMarkerState.Disabled,
    val format: DocumentFormat = DocumentFormat.PlainText,
    val grantTime: Long? = null
) {
    val isDefault: Boolean
        get() = state == NewDocumentAutosaveMarkerState.Disabled
}

/** Emits a deterministic, content-free ASCII representation when non-default. */
internal fun formatNewDocumentAutosaveMarker(marker: NewDocumentAutosaveMarker): String {
    val stateToken = when (marker.state) {
        NewDocumentAutosaveMarkerState.Disabled -> return ""
        NewDocumentAutosaveMarkerState.Enabled -> STATE_ENABLED
        NewDocumentAutosaveMarkerState.Changing -> STATE_CHANGING
    }
    val formatToken = when (marker.format) {
        DocumentFormat.PlainText -> FORMAT_PLAIN_TEXT
        DocumentFormat.Markdown -> FORMAT_MARKDOWN
    }
    val grantTimeLine = if (
        marker.state == NewDocumentAutosaveMarkerState.Enabled &&
        marker.grantTime != null &&
        marker.grantTime > 0L
    ) {
        "$GRANT_TIME_KEY=${marker.grantTime}\n"
    } else {
        ""
    }
    return "$STATE_KEY=$stateToken\n$FORMAT_KEY=$formatToken\n$grantTimeLine"
}

/** Parses bounded marker properties strictly, rejecting malformed, partial, or unknown entries. */
internal fun readNewDocumentAutosaveMarker(input: InputStream): NewDocumentAutosaveMarker {
    val buffer = ByteArrayOutputStream()
    val chunk = ByteArray(256)
    while (true) {
        val read = input.read(chunk)
        if (read < 0) break
        if (buffer.size() + read > MAX_NEW_DOCUMENT_AUTOSAVE_BYTES) {
            throw IllegalArgumentException(
                "New-document autosave marker exceeds $MAX_NEW_DOCUMENT_AUTOSAVE_BYTES bytes"
            )
        }
        buffer.write(chunk, 0, read)
    }
    val bytes = buffer.toByteArray()
    if (bytes.any { it == 0.toByte() }) {
        throw IllegalArgumentException("New-document autosave marker contains NUL bytes")
    }
    val text = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (failure: CharacterCodingException) {
        throw IllegalArgumentException("New-document autosave marker is not valid UTF-8", failure)
    }

    var state: NewDocumentAutosaveMarkerState? = null
    var format: DocumentFormat? = null
    var grantTime: Long? = null
    for (rawLine in text.split('\n')) {
        val line = rawLine.removeSuffix("\r")
        if (line.isEmpty()) continue
        val separator = line.indexOf('=')
        if (separator <= 0) {
            throw IllegalArgumentException("Malformed new-document autosave marker line")
        }
        val key = line.substring(0, separator)
        val value = line.substring(separator + 1)
        when (key) {
            STATE_KEY -> {
                if (state != null) {
                    throw IllegalArgumentException("Duplicate $STATE_KEY entry")
                }
                state = when (value) {
                    STATE_ENABLED -> NewDocumentAutosaveMarkerState.Enabled
                    STATE_CHANGING -> NewDocumentAutosaveMarkerState.Changing
                    else -> throw IllegalArgumentException("Invalid value for $STATE_KEY")
                }
            }

            FORMAT_KEY -> {
                if (format != null) {
                    throw IllegalArgumentException("Duplicate $FORMAT_KEY entry")
                }
                format = when (value) {
                    FORMAT_PLAIN_TEXT -> DocumentFormat.PlainText
                    FORMAT_MARKDOWN -> DocumentFormat.Markdown
                    else -> throw IllegalArgumentException("Invalid value for $FORMAT_KEY")
                }
            }

            GRANT_TIME_KEY -> {
                if (grantTime != null) {
                    throw IllegalArgumentException("Duplicate $GRANT_TIME_KEY entry")
                }
                val parsed = value.toLongOrNull()
                    ?: throw IllegalArgumentException("Invalid value for $GRANT_TIME_KEY")
                if (parsed <= 0L) {
                    throw IllegalArgumentException("Non-positive value for $GRANT_TIME_KEY")
                }
                grantTime = parsed
            }

            else -> throw IllegalArgumentException("Unknown new-document autosave key")
        }
    }
    if (state == null && format == null && grantTime == null) {
        return NewDocumentAutosaveMarker()
    }
    val resolvedState = state
        ?: throw IllegalArgumentException("Missing $STATE_KEY in new-document autosave marker")
    if (resolvedState != NewDocumentAutosaveMarkerState.Enabled && grantTime != null) {
        throw IllegalArgumentException("Unexpected $GRANT_TIME_KEY for non-enabled marker")
    }
    return NewDocumentAutosaveMarker(
        state = resolvedState,
        format = format ?: DocumentFormat.PlainText,
        grantTime = grantTime
    )
}
