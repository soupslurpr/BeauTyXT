package dev.soupslurpr.beautyxt.document

/** Reports a native size rejection that leaves the document and revision unchanged. */
internal class DocumentSizeLimitException(message: String) : RuntimeException(message)
