package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.ui.text.TextRange
import dev.soupslurpr.beautyxt.document.DocumentInsertion
import dev.soupslurpr.beautyxt.document.Utf16Range
import dev.soupslurpr.beautyxt.document.hasWellFormedUtf16
import dev.soupslurpr.beautyxt.document.isScalarBoundary

// Bounds an incoming platform buffer before copying it. Native admission separately
// accounts exact shared UTF-8 allocations and guarantees that the edit has Undo.
internal const val MAX_BULK_INPUT_UTF16_UNITS = 64 * 1024 * 1024

/** Owns raw input once; native transfer normalizes it in bounded worker chunks. */
internal class EditorBulkEdit private constructor(
    val originalText: String,
    val oldRange: Utf16Range,
    val input: DocumentInsertion,
    val selectionBefore: Utf16Range,
    private val rawSelectionAfter: TextRange
) {
    /** Maps raw positions after normalization; called only on the document worker. */
    fun selectionAfter(normalizedLength: Long): Utf16Range {
        fun map(offset: Int): Long = when {
            offset <= oldRange.start -> offset.toLong()
            offset < oldRange.start + input.rawLength ->
                oldRange.start + input.normalizedOffset(offset - oldRange.start.toInt())
            else -> offset - input.rawLength + normalizedLength
        }
        val start = map(rawSelectionAfter.min)
        val end = map(rawSelectionAfter.max)
        return if (end - start > EDIT_WINDOW_UTF16_UNITS) {
            val caret = map(rawSelectionAfter.end)
            Utf16Range(caret, caret)
        } else Utf16Range(start, end)
    }

    companion object {
        /** Captures clipboard text directly, before Compose makes an oversized buffer. */
        fun insertion(original: String, selection: TextRange, inserted: String): EditorBulkEdit? {
            if (!validOriginal(original, selection) || inserted.length > MAX_BULK_INPUT_UTF16_UNITS) return null
            val end = selection.min + inserted.length
            return EditorBulkEdit(original, Utf16Range(selection.min.toLong(), selection.max.toLong()),
                DocumentInsertion(inserted, followingLineFeed = original.getOrNull(selection.max) == '\n'),
                Utf16Range(selection.min.toLong(), selection.max.toLong()), TextRange(end))
        }

        /** Diffs only the bounded original prefix/suffix; does not copy the insertion. */
        fun create(original: String, updated: String, originalSelection: TextRange,
            updatedSelection: TextRange): EditorBulkEdit? {
            if (!validOriginal(original, originalSelection) ||
                updated.length > MAX_BULK_INPUT_UTF16_UNITS + EDIT_DRAFT_MAX_UTF16_UNITS ||
                updatedSelection.max > updated.length ||
                !updated.isScalarBoundary(updatedSelection.min) || !updated.isScalarBoundary(updatedSelection.max)
            ) return null
            if (original == updated) return null
            var prefix = 0
            while (prefix < minOf(original.length, updated.length) && original[prefix] == updated[prefix]) prefix++
            if (!original.isScalarBoundary(prefix) || !updated.isScalarBoundary(prefix)) prefix--
            var oldEnd = original.length
            var newEnd = updated.length
            while (oldEnd > prefix && newEnd > prefix && original[oldEnd - 1] == updated[newEnd - 1]) {
                oldEnd--; newEnd--
            }
            while (!original.isScalarBoundary(oldEnd) || !updated.isScalarBoundary(newEnd)) { oldEnd++; newEnd++ }
            return EditorBulkEdit(original, Utf16Range(prefix.toLong(), oldEnd.toLong()),
                DocumentInsertion(updated, prefix, newEnd, updated.getOrNull(newEnd) == '\n'),
                Utf16Range(originalSelection.min.toLong(), originalSelection.max.toLong()), updatedSelection)
        }

        private fun validOriginal(text: String, selection: TextRange): Boolean =
            text.length <= EDIT_DRAFT_MAX_UTF16_UNITS && text.hasWellFormedUtf16() && '\r' !in text &&
                selection.max <= text.length && text.isScalarBoundary(selection.min) && text.isScalarBoundary(selection.max)
    }
}

/** Routes large IME/accessibility input before publishing or laying out the field. */
internal fun editorInputTransformation(
    canAcceptInput: () -> Boolean,
    onBulkEdit: (EditorBulkEdit) -> Boolean,
    onRejection: (EditorInputRejection) -> Unit,
    clearRejection: () -> Unit,
    markdownAssistEnabled: () -> Boolean = { false },
    hasComposition: () -> Boolean = { false },
    hasPreviousWindow: () -> Boolean = { false },
    consumeSuppressMarkdownAssist: () -> Boolean = { false }
): InputTransformation = InputTransformation {
    when {
        !canAcceptInput() -> {
            consumeSuppressMarkdownAssist()
            revertAllChanges()
        }
        length > MAX_BULK_INPUT_UTF16_UNITS + EDIT_DRAFT_MAX_UTF16_UNITS -> {
            consumeSuppressMarkdownAssist()
            revertAllChanges()
            onRejection(EditorInputRejection.BulkSize)
        }
        length > EDIT_DRAFT_MAX_UTF16_UNITS -> {
            consumeSuppressMarkdownAssist()
            val proposal = EditorBulkEdit.create(originalText.toString(), asCharSequence().toString(),
                originalSelection, selection)
            revertAllChanges()
            if (proposal == null) onRejection(EditorInputRejection.BulkUnavailable)
            else {
                clearRejection()
                if (!onBulkEdit(proposal)) onRejection(EditorInputRejection.BulkUnavailable)
            }
        }
        else -> {
            val suppressAssist = consumeSuppressMarkdownAssist()
            // Only small field edits are normalized on the UI thread.
            val changedRanges = List(changes.changeCount) { changes.getRange(it) }
            for (range in changedRanges.asReversed()) {
                for (index in range.max - 1 downTo range.min) {
                    if (charAt(index) == '\r') {
                        replace(index, index + 1, if (index + 1 < length && charAt(index + 1) == '\n') "" else "\n")
                    }
                }
            }
            if (!suppressAssist && markdownAssistEnabled() && !hasComposition() && changes.changeCount == 1) {
                val afterText = asCharSequence()
                val assisted =
                    computeMarkdownWrapSelection(
                        textBefore = originalText,
                        selectionBefore = originalSelection,
                        textAfter = afterText,
                        selectionAfter = selection
                    ) ?: computeMarkdownEnterContinuation(
                        textBefore = originalText,
                        selectionBefore = originalSelection,
                        textAfter = afterText,
                        selectionAfter = selection,
                        hasPreviousWindow = hasPreviousWindow()
                    )
                if (assisted != null && assisted.text.length <= EDIT_DRAFT_MAX_UTF16_UNITS) {
                    replace(0, length, assisted.text)
                    selection = assisted.selection
                }
            }
            clearRejection()
        }
    }
}

/** One-shot transient gate suppressing Markdown typing assistance during paste or drop insertions. */
internal class MarkdownAssistSuppression(
    private val nanoTime: () -> Long = System::nanoTime
) {
    private var armedUntilNanos: Long = Long.MIN_VALUE

    fun arm() {
        armedUntilNanos = nanoTime() + PASTE_ASSIST_SUPPRESSION_WINDOW_NANOS
    }

    fun clear() {
        armedUntilNanos = Long.MIN_VALUE
    }

    fun consume(): Boolean {
        val deadline = armedUntilNanos
        armedUntilNanos = Long.MIN_VALUE
        return deadline != Long.MIN_VALUE && nanoTime() <= deadline
    }

    private companion object {
        const val PASTE_ASSIST_SUPPRESSION_WINDOW_NANOS = 250_000_000L
    }
}
