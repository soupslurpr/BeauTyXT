package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.input.TextFieldValue
import dev.soupslurpr.beautyxt.document.hasWellFormedUtf16

/** Binds results to the actual editable inputs, including before their observer runs. */
internal data class FindInputs(val query: String, val replacement: String) {
    fun matches(queryState: TextFieldState, replacementState: TextFieldState): Boolean =
        queryState.text.contentEquals(query) && replacementState.text.contentEquals(replacement)
}

internal fun validFindInput(text: String, maximumUnits: Int): Boolean =
    text.length <= maximumUnits && '\r' !in text && text.hasWellFormedUtf16()

/** Rejects unsafe input before the state or its local history accepts it. */
internal fun findInputTransformation(
    maximumUnits: Int,
    canAcceptInput: () -> Boolean = { true }
): InputTransformation = InputTransformation {
    if (!canAcceptInput() || length > maximumUnits ||
        !validFindInput(asCharSequence().toString(), maximumUnits)) revertAllChanges()
}

internal fun TextFieldState.captureFindFieldValue(): TextFieldValue =
    TextFieldValue(text.toString(), selection, composition)

/** Applies committed programmatic input without manufacturing an unchanged text edit. */
internal fun TextFieldState.updateCommittedFindInput(value: TextFieldValue) {
    require(value.composition == null) { "IME composition belongs to the text field state" }
    edit {
        if (!asCharSequence().contentEquals(value.text)) replace(0, length, value.text)
        selection = value.selection
    }
}

/** A new input owner must not retain history or composition from an old input session. */
@OptIn(ExperimentalFoundationApi::class)
internal fun TextFieldState.resetFindInput(value: TextFieldValue = TextFieldValue()) {
    updateCommittedFindInput(value.copy(composition = null))
    undoState.clearHistory()
}
