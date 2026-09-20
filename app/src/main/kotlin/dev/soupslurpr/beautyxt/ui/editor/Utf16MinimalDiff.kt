package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.document.Utf16Range
import dev.soupslurpr.beautyxt.document.hasWellFormedUtf16
import dev.soupslurpr.beautyxt.document.isScalarBoundary

/** Describes one scalar-aligned minimal diff in window-local UTF-16 offsets. */
internal data class Utf16MinimalDiff(val oldRange: Utf16Range, val replacement: String) {
    init {
        require(replacement.hasWellFormedUtf16()) {
            "replacement contains an unpaired surrogate"
        }
    }
}

/** Returns the smallest scalar-aligned replacement that changes one string into another. */
internal fun findUtf16MinimalDiff(original: String, updated: String): Utf16MinimalDiff? {
    require(original.hasWellFormedUtf16()) {
        "original text contains an unpaired surrogate"
    }
    require(updated.hasWellFormedUtf16()) {
        "updated text contains an unpaired surrogate"
    }
    if (original == updated) {
        return null
    }

    val sharedLength = minOf(original.length, updated.length)
    var sharedPrefixLength = 0
    while (
        sharedPrefixLength < sharedLength &&
        original[sharedPrefixLength] == updated[sharedPrefixLength]
    ) {
        sharedPrefixLength += 1
    }
    if (!original.isScalarBoundary(sharedPrefixLength)) {
        sharedPrefixLength -= 1
    }

    var originalEnd = original.length
    var updatedEnd = updated.length
    while (
        originalEnd > sharedPrefixLength &&
        updatedEnd > sharedPrefixLength &&
        original[originalEnd - 1] == updated[updatedEnd - 1]
    ) {
        originalEnd -= 1
        updatedEnd -= 1
    }
    while (!original.isScalarBoundary(originalEnd) || !updated.isScalarBoundary(updatedEnd)) {
        originalEnd += 1
        updatedEnd += 1
    }

    return Utf16MinimalDiff(
        oldRange =
            Utf16Range(
                start = sharedPrefixLength.toLong(),
                end = originalEnd.toLong()
            ),
        replacement = updated.substring(sharedPrefixLength, updatedEnd)
    )
}
