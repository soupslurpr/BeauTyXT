package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.ui.text.TextRange
import dev.soupslurpr.beautyxt.document.Utf16Range
import dev.soupslurpr.beautyxt.document.hasWellFormedUtf16
import dev.soupslurpr.beautyxt.document.isScalarBoundary

internal const val MAX_BULK_INSERT_UTF16_UNITS = 128 * 1024
internal const val MAX_BULK_FIELD_UTF16_UNITS =
    MAX_BULK_INSERT_UTF16_UNITS + EDIT_DRAFT_MAX_UTF16_UNITS

/** Owns one bounded insertion independently of the small field that originated it. */
internal class EditorBulkEdit private constructor(
    val originalText: String,
    val delta: Utf16MinimalDiff,
    val selectionBefore: Utf16Range,
    val selectionAfter: Utf16Range
) {
    companion object {
        /** Admits a scalar-aligned change that fits both native insertion and undo budgets. */
        fun create(
            original: String,
            updated: String,
            originalSelection: TextRange,
            updatedSelection: TextRange
        ): EditorBulkEdit? {
            if (
                original.length > EDIT_DRAFT_MAX_UTF16_UNITS ||
                updated.length > MAX_BULK_FIELD_UTF16_UNITS ||
                !original.hasWellFormedUtf16() || !updated.hasWellFormedUtf16() ||
                '\r' in original || '\r' in updated ||
                originalSelection.max > original.length || updatedSelection.max > updated.length ||
                !original.isScalarBoundary(originalSelection.min) ||
                !original.isScalarBoundary(originalSelection.max) ||
                !updated.isScalarBoundary(updatedSelection.min) ||
                !updated.isScalarBoundary(updatedSelection.max)
            ) {
                return null
            }
            val delta = findUtf16MinimalDiff(original, updated) ?: return null
            if (delta.replacement.length > MAX_BULK_INSERT_UTF16_UNITS) return null
            val selectionAfter = if (updatedSelection.length > EDIT_WINDOW_UTF16_UNITS) {
                Utf16Range(updatedSelection.end.toLong(), updatedSelection.end.toLong())
            } else {
                Utf16Range(updatedSelection.min.toLong(), updatedSelection.max.toLong())
            }
            return EditorBulkEdit(
                originalText = original,
                delta = delta,
                selectionBefore = Utf16Range(
                    originalSelection.min.toLong(),
                    originalSelection.max.toLong()
                ),
                selectionAfter = selectionAfter
            )
        }
    }
}

/** Routes oversized user input before Compose can publish or lay out an oversized field. */
internal fun editorInputTransformation(
    canAcceptInput: () -> Boolean,
    onBulkEdit: (EditorBulkEdit) -> Boolean,
    onRejection: (EditorInputRejection) -> Unit,
    clearRejection: () -> Unit
): InputTransformation = InputTransformation {
    when {
        !canAcceptInput() -> revertAllChanges()

        length > MAX_BULK_FIELD_UTF16_UNITS -> {
            revertAllChanges()
            onRejection(EditorInputRejection.BulkSize)
        }

        else -> {
            // Only inserted/replaced ranges can add CR to the normalized field.
            val changedRanges = List(changes.changeCount) { changes.getRange(it) }
            for (range in changedRanges.asReversed()) {
                for (index in range.max - 1 downTo range.min) {
                    if (charAt(index) == '\r') {
                        val replacement =
                            if (index + 1 < length && charAt(index + 1) == '\n') "" else "\n"
                        replace(index, index + 1, replacement)
                    }
                }
            }
            if (length <= EDIT_DRAFT_MAX_UTF16_UNITS) {
                clearRejection()
            } else {
                val proposal = EditorBulkEdit.create(
                    original = originalText.toString(),
                    updated = asCharSequence().toString(),
                    originalSelection = originalSelection,
                    updatedSelection = selection
                )
                revertAllChanges()
                if (proposal == null) {
                    onRejection(EditorInputRejection.BulkSize)
                } else {
                    clearRejection()
                    if (!onBulkEdit(proposal)) {
                        onRejection(EditorInputRejection.BulkUnavailable)
                    }
                }
            }
        }
    }
}
