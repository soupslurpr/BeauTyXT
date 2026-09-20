package dev.soupslurpr.beautyxt.sharing

import dev.soupslurpr.beautyxt.document.hasWellFormedUtf16

internal const val MAX_SHARED_TEXT_UTF8_BYTES = 128L * 1024L
internal const val MAX_INCOMING_TEXT_UTF16_UNITS = MAX_SHARED_TEXT_UTF8_BYTES.toInt()

/** Bounds direct incoming text by the same UTF-8 budget as outgoing text shares. */
internal fun acceptsDirectSharedText(text: String): Boolean {
    if (text.length > MAX_INCOMING_TEXT_UTF16_UNITS || !text.hasWellFormedUtf16()) {
        return false
    }
    var bytes = 0L
    var index = 0
    while (index < text.length) {
        val character = text[index++]
        bytes += when {
            character.code < 0x80 -> 1L

            character.code < 0x800 -> 2L

            character.isHighSurrogate() -> {
                index += 1
                4L
            }

            else -> 3L
        }
        if (bytes > MAX_SHARED_TEXT_UTF8_BYTES) return false
    }
    return true
}
