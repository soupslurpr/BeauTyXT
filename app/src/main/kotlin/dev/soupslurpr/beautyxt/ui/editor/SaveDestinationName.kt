package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.document.DocumentFormat
import dev.soupslurpr.beautyxt.importing.client.recognizedDocumentFilenameStem
import dev.soupslurpr.beautyxt.importing.client.sanitizeSelectedDocumentDisplayName
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.util.Locale

private val TIMESTAMPED_FILENAME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("uuuu-MM-dd HH.mm.ss", Locale.ROOT)
        .withDecimalStyle(DecimalStyle.STANDARD)

/** Formats one filesystem-safe local timestamp filename for an unnamed document. */
internal fun formatTimestampedDocumentName(
    format: DocumentFormat,
    instant: Instant = Instant.now(),
    zoneId: ZoneId = ZoneId.systemDefault()
): String {
    val candidate = TIMESTAMPED_FILENAME_FORMATTER.format(instant.atZone(zoneId)) +
        format.filenameExtension
    return candidate.takeIf { filename ->
        isSafeSaveDestinationName(filename, format)
    } ?: format.fallbackSuggestedName
}

/** Returns one safe picker suggestion derived from the in-memory editor title. */
internal fun suggestSaveDestinationName(
    title: String,
    format: DocumentFormat,
    fallbackName: String = formatTimestampedDocumentName(format)
): String {
    require(title.isNotBlank()) { "editor title must not be blank" }
    val safeFallback = fallbackName.takeIf { candidate ->
        isSafeSaveDestinationName(candidate, format)
    } ?: format.fallbackSuggestedName
    if (isSafeSaveDestinationName(title, format)) {
        return title
    }
    val stem = recognizedDocumentFilenameStem(title) ?: return safeFallback
    val suggestion = stem + format.filenameExtension
    return suggestion.takeIf { filename ->
        isSafeSaveDestinationName(filename, format)
    } ?: safeFallback
}

/** Returns whether one picker suggestion is safe for its selected format. */
internal fun isSafeSaveDestinationName(filename: String, format: DocumentFormat): Boolean {
    val filenameStemLength = filename.length - format.filenameExtension.length
    return filenameStemLength > 0 &&
        filename.endsWith(format.filenameExtension, ignoreCase = true) &&
        sanitizeSelectedDocumentDisplayName(filename) == filename
}
