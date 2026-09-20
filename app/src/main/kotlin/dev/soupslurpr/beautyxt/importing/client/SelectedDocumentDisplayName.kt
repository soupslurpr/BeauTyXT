package dev.soupslurpr.beautyxt.importing.client

import dev.soupslurpr.beautyxt.document.documentFormatForSourceName

internal const val MAX_SELECTED_DOCUMENT_DISPLAY_NAME_UTF16_UNITS = 255

private const val ARABIC_LETTER_MARK = 0x061C
private const val LEFT_TO_RIGHT_MARK = 0x200E
private const val RIGHT_TO_LEFT_MARK = 0x200F
private const val FIRST_BIDIRECTIONAL_EMBEDDING_OR_OVERRIDE = 0x202A
private const val LAST_BIDIRECTIONAL_EMBEDDING_OR_OVERRIDE = 0x202E
private const val FIRST_BIDIRECTIONAL_ISOLATE = 0x2066
private const val LAST_BIDIRECTIONAL_ISOLATE = 0x2069

/** Returns the stem of a structurally safe, recognized document filename. */
internal fun recognizedDocumentFilenameStem(filename: String): String? {
    if (sanitizeSelectedDocumentDisplayName(filename) != filename) return null
    if (documentFormatForSourceName(filename) == null) return null
    return filename.substringBeforeLast('.')
}

/** Returns a bounded title for one provider-supplied display name. */
internal fun sanitizeSelectedDocumentDisplayName(displayName: String?): String? {
    if (
        displayName == null ||
        displayName.length > MAX_SELECTED_DOCUMENT_DISPLAY_NAME_UTF16_UNITS
    ) {
        return null
    }

    var utf16Offset = 0
    while (utf16Offset < displayName.length) {
        val codePoint = Character.codePointAt(displayName, utf16Offset)
        if (isUnsafeDisplayNameCodePoint(codePoint)) {
            return null
        }
        utf16Offset += Character.charCount(codePoint)
    }

    return displayName
        .trim { character ->
            Character.isWhitespace(character) || Character.isSpaceChar(character)
        }
        .takeIf(String::isNotEmpty)
}

/** Returns whether one code point could obscure or restructure a displayed filename. */
private fun isUnsafeDisplayNameCodePoint(codePoint: Int): Boolean {
    val characterType = Character.getType(codePoint)
    return Character.isISOControl(codePoint) ||
        characterType == Character.SURROGATE.toInt() ||
        characterType == Character.LINE_SEPARATOR.toInt() ||
        characterType == Character.PARAGRAPH_SEPARATOR.toInt() ||
        codePoint == '/'.code ||
        codePoint == '\\'.code ||
        codePoint == ARABIC_LETTER_MARK ||
        codePoint == LEFT_TO_RIGHT_MARK ||
        codePoint == RIGHT_TO_LEFT_MARK ||
        codePoint in
        FIRST_BIDIRECTIONAL_EMBEDDING_OR_OVERRIDE..LAST_BIDIRECTIONAL_EMBEDDING_OR_OVERRIDE ||
        codePoint in FIRST_BIDIRECTIONAL_ISOLATE..LAST_BIDIRECTIONAL_ISOLATE
}
