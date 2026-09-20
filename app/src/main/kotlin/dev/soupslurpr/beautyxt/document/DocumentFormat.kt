package dev.soupslurpr.beautyxt.document

import java.util.Locale

/** The same source representation is used for opening, saving, sharing, and File info. */
internal enum class DocumentFormat(
    val mimeType: String,
    val filenameExtension: String,
    val fallbackSuggestedName: String
) {
    PlainText("text/plain", ".txt", "untitled.txt"),
    Markdown("text/markdown", ".md", "untitled.md")
}

/** Recognized names take precedence over provider types, which are often generic. */
internal fun resolveDocumentFormat(
    sourceName: String?,
    mimeType: String?,
    alternateSourceName: String? = null
): DocumentFormat? = sourceName?.let(::documentFormatForSourceName)
    ?: alternateSourceName?.let(::documentFormatForSourceName)
    ?: documentFormatForMimeType(mimeType)

/** Recognizes supported text types without interpreting their contents. */
internal fun documentFormatForMimeType(mimeType: String?): DocumentFormat? = when {
    mimeType == null -> null
    mimeType.equals("text/markdown", ignoreCase = true) -> DocumentFormat.Markdown
    mimeType.equals("text/x-markdown", ignoreCase = true) -> DocumentFormat.Markdown
    mimeType.equals("application/markdown", ignoreCase = true) -> DocumentFormat.Markdown
    mimeType.equals("application/x-markdown", ignoreCase = true) -> DocumentFormat.Markdown
    mimeType.startsWith("text/", ignoreCase = true) -> DocumentFormat.PlainText
    else -> null
}

/** Recognizes a filename or the final name of an Android provider document path. */
internal fun documentFormatForSourceName(sourceName: String): DocumentFormat? {
    val extension = sourceName.substringAfterLast('/').substringAfterLast('.', "")
        .lowercase(Locale.ROOT)
    return when (extension) {
        "md", "markdown", "mdown", "mkd" -> DocumentFormat.Markdown
        "txt", "text", "log", "asc" -> DocumentFormat.PlainText
        else -> null
    }
}
