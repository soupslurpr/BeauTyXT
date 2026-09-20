package dev.soupslurpr.beautyxt.printing

import dev.soupslurpr.beautyxt.importing.client.recognizedDocumentFilenameStem
import dev.soupslurpr.beautyxt.importing.client.sanitizeSelectedDocumentDisplayName

private const val PDF_FILENAME_EXTENSION = ".pdf"
private const val PRINT_DOCUMENT_FALLBACK_NAME = "BeauTyXT document.pdf"

/** Returns one safe PDF name derived from the bounded editor title. */
internal fun suggestPrintDocumentName(title: String): String {
    require(title.isNotBlank()) { "editor title must not be blank" }
    val safeTitle =
        sanitizeSelectedDocumentDisplayName(title)
            ?.takeIf { candidate -> candidate == title }
            ?: return PRINT_DOCUMENT_FALLBACK_NAME
    val stem = recognizedDocumentFilenameStem(safeTitle) ?: if (
        safeTitle.endsWith(PDF_FILENAME_EXTENSION, ignoreCase = true)
    ) {
        safeTitle.dropLast(PDF_FILENAME_EXTENSION.length)
    } else {
        safeTitle
    }
    val suggestedName = stem + PDF_FILENAME_EXTENSION
    return suggestedName.takeIf { candidate ->
        stem.isNotEmpty() &&
            sanitizeSelectedDocumentDisplayName(candidate) == candidate
    } ?: PRINT_DOCUMENT_FALLBACK_NAME
}
