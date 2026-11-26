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
    mimeType.lowercase(Locale.ROOT) in TEXT_BASED_APPLICATION_MIME_TYPES -> DocumentFormat.PlainText
    else -> null
}

/** Recognizes a filename or the final name of an Android provider document path. */
internal fun documentFormatForSourceName(sourceName: String): DocumentFormat? {
    val extension = sourceName.substringAfterLast('/').substringAfterLast('.', "")
        .lowercase(Locale.ROOT)
    return when {
        extension in MARKDOWN_EXTENSIONS -> DocumentFormat.Markdown
        extension in PLAIN_TEXT_AND_CODE_EXTENSIONS -> DocumentFormat.PlainText
        else -> null
    }
}

private val MARKDOWN_EXTENSIONS = setOf("md", "markdown", "mdown", "mkd")

private val PLAIN_TEXT_AND_CODE_EXTENSIONS = setOf(
    "txt", "text", "log", "asc",
    "rs", "kt", "kts", "java",
    "c", "h", "cpp", "cc", "cxx", "hpp", "hh", "hxx", "cs",
    "go", "swift", "zig",
    "js", "mjs", "cjs", "jsx", "ts", "mts", "cts", "tsx",
    "py", "pyi", "sh", "bash", "zsh", "rb", "lua", "sql",
    "json", "jsonc", "yaml", "yml", "toml",
    "xml", "html", "htm", "xhtml", "css", "scss",
    "diff", "patch",
    "ini", "cfg", "conf", "csv", "tsv", "gradle", "properties"
)

internal val TEXT_BASED_APPLICATION_MIME_TYPES = linkedSetOf(
    "application/json",
    "application/xml",
    "application/xhtml+xml",
    "application/javascript",
    "application/x-javascript",
    "application/ecmascript",
    "application/typescript",
    "application/x-sh",
    "application/x-shellscript",
    "application/yaml",
    "application/x-yaml",
    "application/toml",
    "application/sql"
)
