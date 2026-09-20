package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.document.DocumentFormat
import dev.soupslurpr.beautyxt.importing.client.recognizedDocumentFilenameStem
import dev.soupslurpr.beautyxt.importing.client.sanitizeSelectedDocumentDisplayName

/** Returns one safe picker suggestion derived from the in-memory editor title. */
internal fun suggestSaveDestinationName(title: String, format: DocumentFormat): String {
    require(title.isNotBlank()) { "editor title must not be blank" }
    if (isSafeSaveDestinationName(title, format)) {
        return title
    }
    val stem = recognizedDocumentFilenameStem(title) ?: return format.fallbackSuggestedName
    val suggestion = stem + format.filenameExtension
    return suggestion.takeIf { filename ->
        isSafeSaveDestinationName(filename, format)
    } ?: format.fallbackSuggestedName
}

/** Returns whether one picker suggestion is safe for its selected format. */
internal fun isSafeSaveDestinationName(filename: String, format: DocumentFormat): Boolean {
    val filenameStemLength = filename.length - format.filenameExtension.length
    return filenameStemLength > 0 &&
        filename.endsWith(format.filenameExtension, ignoreCase = true) &&
        sanitizeSelectedDocumentDisplayName(filename) == filename
}
