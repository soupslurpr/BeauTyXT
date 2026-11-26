package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.document.DocumentPatch
import dev.soupslurpr.beautyxt.document.MAX_REPLACEMENT_PATCHES
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

/**
 * Splits a multi-line replacement with an unchanged newline count into per-line [DocumentPatch]es
 * so untouched interior line separators (`\r\n`, `\n`, `\r`) are preserved by `replaceBatch`.
 *
 * Returns `null` when the edit changes line count, modifies only one line, or exceeds the batch cap.
 */
internal fun buildLinePreservingPatches(
    oldText: String,
    newText: String,
    baseGlobalOffset: Long
): List<DocumentPatch>? {
    if ('\n' !in oldText || '\n' !in newText) return null
    val oldNewlines = oldText.count { it == '\n' }
    val newNewlines = newText.count { it == '\n' }
    if (oldNewlines != newNewlines) return null
    val patches = ArrayList<DocumentPatch>()
    var preservesLineStructure = false
    val oldSegments = ArrayList<String>(oldNewlines + 1)
    val newSegments = ArrayList<String>(oldNewlines + 1)
    var oldStart = 0
    var newStart = 0
    while (true) {
        val oldEnd = oldText.indexOf('\n', oldStart).let { if (it < 0) oldText.length else it }
        val newEnd = newText.indexOf('\n', newStart).let { if (it < 0) newText.length else it }
        val oldSegment = oldText.substring(oldStart, oldEnd)
        val newSegment = newText.substring(newStart, newEnd)
        oldSegments += oldSegment
        newSegments += newSegment
        if (oldSegment == newSegment) {
            preservesLineStructure = true
        } else {
            val diff = findUtf16MinimalDiff(oldSegment, newSegment)
            if (diff != null) {
                if (patches.size >= MAX_REPLACEMENT_PATCHES) return null
                val patchStart = Math.addExact(baseGlobalOffset, oldStart.toLong() + diff.oldRange.start)
                val patchEnd = Math.addExact(baseGlobalOffset, oldStart.toLong() + diff.oldRange.end)
                val removed = oldSegment.substring(
                    diff.oldRange.start.toInt(),
                    diff.oldRange.end.toInt()
                )
                if (
                    oldSegment.isEmpty() ||
                    newSegment.isEmpty() ||
                    removed.length < oldSegment.length
                ) {
                    preservesLineStructure = true
                }
                patches += DocumentPatch(
                    range = Utf16Range(patchStart, patchEnd),
                    removed = removed,
                    inserted = diff.replacement
                )
            }
        }
        if (oldEnd == oldText.length) break
        oldStart = oldEnd + 1
        newStart = newEnd + 1
    }
    if (!preservesLineStructure) {
        val newSegmentSet = newSegments.toHashSet()
        preservesLineStructure = oldSegments.any { it.isNotEmpty() && it in newSegmentSet }
    }
    return patches.takeIf { it.size >= 2 && preservesLineStructure }
}
