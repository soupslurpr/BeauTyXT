package dev.soupslurpr.beautyxt.document

/** Returns whether every UTF-16 surrogate belongs to one valid pair. */
internal fun String.hasWellFormedUtf16(): Boolean {
    var offset = 0
    while (offset < length) {
        when {
            this[offset].isHighSurrogate() -> {
                if (offset + 1 >= length || !this[offset + 1].isLowSurrogate()) {
                    return false
                }
                offset += 2
            }

            this[offset].isLowSurrogate() -> return false

            else -> offset += 1
        }
    }
    return true
}

/** Returns the UTF-8 byte length, or null when this string contains an unpaired surrogate. */
internal fun String.utf8LengthOrNull(): Long? {
    var offset = 0
    var utf8Length = 0L
    while (offset < length) {
        val character = this[offset]
        when {
            character.code <= 0x7f -> {
                utf8Length += 1L
                offset += 1
            }

            character.code <= 0x7ff -> {
                utf8Length += 2L
                offset += 1
            }

            character.isHighSurrogate() -> {
                if (offset + 1 >= length || !this[offset + 1].isLowSurrogate()) {
                    return null
                }
                utf8Length += 4L
                offset += 2
            }

            character.isLowSurrogate() -> return null

            else -> {
                utf8Length += 3L
                offset += 1
            }
        }
    }
    return utf8Length
}

/** Returns whether an offset does not divide a surrogate pair. */
internal fun String.isScalarBoundary(offset: Int): Boolean {
    require(offset in 0..length) { "utf-16 offset is outside the string" }
    return offset == 0 ||
        offset == length ||
        !this[offset - 1].isHighSurrogate() ||
        !this[offset].isLowSurrogate()
}
