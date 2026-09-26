package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.document.DocumentPatch
import dev.soupslurpr.beautyxt.document.Utf16Range

internal data class DocumentLocation(
    val revision: Long,
    val presentation: EditorPresentation,
    val offset: Long,
    val caret: Long = offset
)

/** Session-only return positions, independent of Android Back and document Undo. */
internal class DocumentLocations(private val capacity: Int = 128) {
    private val entries = ArrayList<DocumentLocation?>()
    private var index = -1
    private var findExcursion = false
    val hasPrevious: Boolean get() = index > 0
    val hasNext: Boolean get() = index < entries.lastIndex

    fun record(origin: DocumentLocation, destination: DocumentLocation, find: Boolean = false) {
        if (origin == destination) return
        if (find && findExcursion && index == entries.lastIndex && index >= 1) {
            entries[index] = destination
            return
        }
        while (entries.size > index + 1) entries.removeAt(entries.lastIndex)
        if (entries.lastOrNull() != origin) entries.add(origin)
        entries.add(destination)
        while (entries.size > capacity) entries.removeAt(0)
        index = entries.lastIndex
        findExcursion = find
    }

    fun endFindExcursion() { findExcursion = false }

    /** Skips deleted anchors, preserving the current passage if none survives. */
    fun move(forward: Boolean): Pair<DocumentLocation?, Boolean> {
        var next = index + if (forward) 1 else -1
        var skipped = false
        while (next in entries.indices) {
            val location = entries[next]
            if (location != null) {
                index = next
                findExcursion = false
                return location to skipped
            }
            skipped = true
            next += if (forward) 1 else -1
        }
        if (skipped) {
            // Trim only the exhausted side. Equal passages can occur at different
            // history positions, and the current anchor may itself be deleted.
            if (forward) entries.subList(index + 1, entries.size).clear()
            else { entries.subList(0, index).clear(); index = 0 }
        }
        return null to skipped
    }

    fun rebase(before: Long, after: Long, patches: List<DocumentPatch>) {
        entries.indices.forEach { index ->
            val location = entries[index] ?: return@forEach
            entries[index] = if (location.revision != before) null else {
                val offset = rebaseDocumentPoint(location.offset, patches)
                val caret = rebaseDocumentPoint(location.caret, patches)
                if (offset == null || caret == null) null
                else location.copy(revision = after, offset = offset, caret = caret)
            }
        }
    }

    fun clear() { entries.clear(); index = -1; findExcursion = false }
}

/** Right affinity keeps an anchor attached to its original following character. */
internal fun rebaseDocumentPoint(offset: Long, patches: List<DocumentPatch>): Long? {
    var shift = 0L
    for (patch in patches) {
        if (offset < patch.range.start) break
        if (offset < patch.range.end) return null
        shift += patch.inserted.length - (patch.range.end - patch.range.start)
    }
    return Math.addExact(offset, shift)
}

/** Captured ranges exclude ordinary edge insertions and never silently widen. */
internal fun rebaseCapturedRange(range: Utf16Range, patches: List<DocumentPatch>,
    scopedReplacement: Boolean = false): Utf16Range? {
    var start = range.start
    var end = range.end
    for (patch in patches) {
        val change = patch.inserted.length - (patch.range.end - patch.range.start)
        if (patch.range.start == patch.range.end) {
            when {
                patch.range.start < range.start ||
                    (patch.range.start == range.start && !scopedReplacement) -> { start += change; end += change }
                patch.range.start < range.end ||
                    (patch.range.start == range.end && scopedReplacement) -> end += change
            }
        } else when {
            patch.range.end <= range.start -> { start += change; end += change }
            patch.range.start >= range.end -> Unit
            patch.range == range && scopedReplacement -> end += change
            patch.range.start <= range.start && patch.range.end >= range.end -> return null
            patch.range.start < range.start || patch.range.end > range.end -> return null
            else -> end += change
        }
    }
    return if (end > start) Utf16Range(start, end) else null
}
