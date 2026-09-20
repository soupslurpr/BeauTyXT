/* Owns bounded editor input, composition, and viewport restoration. */
package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.ui.UiText

private const val MAX_RETAINED_UNDO_CHANGES = 128

private val EDIT_SELECTION_LIMIT_MESSAGE = UiText.Resource(R.string.source_selection_limit)

/** Captures one atomic bounded field observation. */
internal data class ActiveEditFieldValue(
    val text: String,
    val selection: TextRange,
    val composition: TextRange?
)

/** Returns whether one field selection has consumed the full bounded editor capacity. */
internal fun hasReachedEditSelectionLimit(selection: TextRange): Boolean =
    selection.max - selection.min >= EDIT_DRAFT_MAX_UTF16_UNITS

/** Binds one top-left visible source point and vertical inset to an exact revision. */
internal data class SemanticViewportAnchor(
    val revision: Long,
    val sourceUtf16Offset: Long,
    val viewportTopOffsetPixels: Int
) {
    init {
        require(revision >= 0L) { "semantic viewport revision must be nonnegative" }
        require(sourceUtf16Offset >= 0L) { "semantic viewport offset must be nonnegative" }
    }
}

/** Restores one semantic anchor after an internal bounded-window handoff. */
internal data class EditWindowScrollRestoration(
    val anchor: SemanticViewportAnchor,
    val preserveScrollMomentum: Boolean = false
)

/** Returns whether focus collapsed one retained selection to its active-end caret. */
internal fun shouldRestoreSelectionCollapsedByFocus(
    retainedSelection: TextRange,
    currentSelection: TextRange,
    hasComposition: Boolean
): Boolean = !retainedSelection.collapsed &&
    !hasComposition &&
    currentSelection == TextRange(retainedSelection.end)

/** Owns one stable bounded field state and its current generation's committed baseline. */
@Stable
internal class ActiveEditDraft(
    initialEdit: ActiveEditWindow,
    shouldRestoreEditorFocusInitially: Boolean = false,
    initialScrollRestoration: EditWindowScrollRestoration? = null,
    initialDirectedSelection: TextRange? = null
) {
    var edit by mutableStateOf(initialEdit)
        private set

    var shouldRestoreEditorFocus by mutableStateOf(shouldRestoreEditorFocusInitially)
        private set

    var inputRejection by mutableStateOf<EditorInputRejection?>(null)
        private set

    var selectionBoundaryMessage by mutableStateOf<UiText?>(null)
        private set

    var scrollRestoration by mutableStateOf(initialScrollRestoration)
        private set

    private var viewportAnchor: SemanticViewportAnchor? = null
    private var layoutWidthPixels: Int? = null

    private val initialTextFieldSelection =
        initialDirectedSelection
            ?: TextRange(
                start = initialEdit.localSelection.start.toInt(),
                end = initialEdit.localSelection.end.toInt()
            )

    init {
        require(
            initialTextFieldSelection.min >= 0 &&
                initialTextFieldSelection.max <= initialEdit.snapshot.text.length
        ) {
            "initial directed selection exceeds the edit window"
        }
    }

    val textFieldState =
        TextFieldState(
            initialText = initialEdit.snapshot.text,
            initialSelection = initialTextFieldSelection
        )
    val scrollState = ScrollState(initial = 0)
    private val hasChangesState =
        derivedStateOf { !textFieldState.text.contentEquals(edit.snapshot.text) }
    private var observedTextChanges = 0
    private var lastObservedText = initialEdit.snapshot.text
    private var lastObservedSelection = textFieldState.selection
    private var pendingHistorySelectionBefore: TextRange? = null
    private var lastObservedComposition: TextRange? = null
    private var editorFocused = false
    private var focusRestorationSelection = textFieldState.selection

    val hasChanges: Boolean
        get() = hasChangesState.value

    val isEditorFocused: Boolean
        get() = editorFocused

    /** Records whether a user-focused field should regain focus after recreation. */
    fun updateEditorFocusIntent(isFocused: Boolean, canClear: Boolean) {
        editorFocused = isFocused
        if (isFocused || canClear) {
            shouldRestoreEditorFocus = isFocused
        }
    }

    /** Requests focus restoration without replacing the retained text or selection. */
    fun requestEditorFocusRestoration() {
        focusRestorationSelection = textFieldState.selection
        shouldRestoreEditorFocus = true
    }

    /** Returns the latest selection observed while the editor retained focus. */
    fun selectionForFocusRestoration(): TextRange = focusRestorationSelection

    /** Restores a retained selection only when focus collapsed it to its active end. */
    fun restoreSelectionCollapsedByFocus(retainedSelection: TextRange) {
        if (!shouldRestoreSelectionCollapsedByFocus(
                retainedSelection = retainedSelection,
                currentSelection = textFieldState.selection,
                hasComposition = textFieldState.composition != null
            )
        ) {
            return
        }
        textFieldState.edit {
            selection = retainedSelection
        }
    }

    /** Reports one retained user-input rejection without changing document text. */
    fun reportInputRejection(reason: EditorInputRejection) {
        inputRejection = reason
    }

    /** Clears a prior input rejection after the field accepts another change. */
    fun clearInputRejection() {
        inputRejection = null
    }

    /** Reports that one focused selection cannot expand beyond the bounded field limit. */
    fun reportSelectionBoundary() {
        selectionBoundaryMessage = EDIT_SELECTION_LIMIT_MESSAGE
    }

    /** Consumes one exact scroll restoration after the bounded field applies it. */
    fun consumeScrollRestoration(restoration: EditWindowScrollRestoration): Boolean {
        if (scrollRestoration != restoration) {
            return false
        }
        scrollRestoration = null
        return true
    }

    /** Retains the visible source point independently of a composition's text layout. */
    fun retainViewportAnchor(anchor: SemanticViewportAnchor) {
        require(anchor.revision == edit.snapshot.metrics.revision) {
            "viewport anchor must match the active revision"
        }
        require(anchor.sourceUtf16Offset in edit.snapshot.range.start..edit.snapshot.range.end) {
            "viewport anchor must remain inside the edit window"
        }
        viewportAnchor = anchor
    }

    /** Restores the retained source point when a new width reflows unchanged text. */
    fun restoreViewportAfterWidthChange(widthPixels: Int) {
        require(widthPixels >= 0) { "text layout width must be nonnegative" }
        val previousWidth = layoutWidthPixels
        layoutWidthPixels = widthPixels
        val anchor = viewportAnchor ?: return
        if (
            previousWidth != null && previousWidth != widthPixels &&
            scrollRestoration == null && !hasChanges && textFieldState.composition == null &&
            anchor.revision == edit.snapshot.metrics.revision
        ) {
            scrollRestoration = EditWindowScrollRestoration(anchor)
        }
    }

    /** Records one atomic field emission and enforces the retained undo bound. */
    fun recordFieldObservation(value: ActiveEditFieldValue) {
        if (editorFocused) {
            focusRestorationSelection = value.selection
        }
        if (value.text != lastObservedText) {
            if (
                pendingHistorySelectionBefore == null &&
                lastObservedText.contentEquals(edit.snapshot.text)
            ) {
                pendingHistorySelectionBefore = lastObservedSelection
            }
            if (observedTextChanges < MAX_RETAINED_UNDO_CHANGES) {
                observedTextChanges += 1
            }
            lastObservedText = value.text
        }
        lastObservedSelection = value.selection
        if (!hasReachedEditSelectionLimit(value.selection)) {
            selectionBoundaryMessage = null
        }
        if (value.text.contentEquals(edit.snapshot.text)) {
            pendingHistorySelectionBefore = null
        }
        if (value.composition != lastObservedComposition) {
            lastObservedComposition = value.composition
        }
        clearUndoHistoryWhenReady()
    }

    /** Returns the local selection retained before the current draft first changed. */
    fun historySelectionBefore(): TextRange =
        pendingHistorySelectionBefore ?: edit.localSelection.let { selection ->
            TextRange(start = selection.start.toInt(), end = selection.end.toInt())
        }

    /** Captures the current field value for one bounded Rust synchronization. */
    fun captureFieldValue(): ActiveEditFieldValue = ActiveEditFieldValue(
        text = textFieldState.text.toString(),
        selection = textFieldState.selection,
        composition = textFieldState.composition
    )

    /** Rolls back only the exact rejected submission, never text typed afterward. */
    fun rejectSubmittedChange(submitted: ActiveEditFieldValue): Boolean {
        if (
            textFieldState.composition != null ||
            !textFieldState.text.contentEquals(submitted.text)
        ) {
            return false
        }
        val retainedSelection = historySelectionBefore()
        textFieldState.edit {
            replace(0, length, edit.snapshot.text)
            selection = retainedSelection
        }
        recordFieldObservation(captureFieldValue())
        reportInputRejection(EditorInputRejection.DocumentSize)
        return true
    }

    /** Commits the visible IME word without changing its text, selection, or focus. */
    fun commitComposingText() {
        val composingRange = textFieldState.composition ?: return
        val composingText =
            textFieldState.text.subSequence(composingRange.min, composingRange.max).toString()
        textFieldState.edit {
            val retainedSelection = selection
            // Reapply the same range through Compose to end composition and notify the IME.
            replace(start = composingRange.min, end = composingRange.max, text = composingText)
            selection = retainedSelection
        }
    }

    /** Advances the committed baseline without replacing the Compose field. */
    fun reconcileCommittedEdit(committedEdit: ActiveEditWindow) {
        require(committedEdit.generation == edit.generation) {
            "committed edit generation does not match the active draft"
        }
        if (committedEdit.snapshot.metrics.revision != edit.snapshot.metrics.revision) {
            viewportAnchor = null
        }
        edit = committedEdit
        lastObservedText = textFieldState.text.toString()
        lastObservedSelection = textFieldState.selection
        pendingHistorySelectionBefore = null
    }

    /** Advances one clean automatic handoff without replacing Compose interaction state. */
    fun reconcileAutomaticEditWindow(
        nextEdit: ActiveEditWindow,
        restoration: EditWindowScrollRestoration,
        directedSelection: TextRange?
    ) {
        require(nextEdit.generation != edit.generation) {
            "automatic edit-window handoff must advance the generation"
        }
        require(nextEdit.snapshot.metrics.revision == edit.snapshot.metrics.revision) {
            "automatic edit-window handoff must retain the revision"
        }
        require(!hasChanges) { "automatic edit-window handoff requires a clean draft" }
        require(textFieldState.composition == null) {
            "automatic edit-window handoff cannot replace composing text"
        }
        require(restoration.preserveScrollMomentum) {
            "automatic edit-window handoff must preserve scroll momentum"
        }
        require(restoration.anchor.revision == nextEdit.snapshot.metrics.revision) {
            "automatic edit-window anchor revision does not match the next edit"
        }
        require(
            restoration.anchor.sourceUtf16Offset in
                nextEdit.snapshot.range.start..nextEdit.snapshot.range.end
        ) {
            "automatic edit-window anchor exceeds the next edit"
        }
        val nextSelection =
            directedSelection
                ?: TextRange(
                    start = Math.toIntExact(nextEdit.localSelection.start),
                    end = Math.toIntExact(nextEdit.localSelection.end)
                )
        replaceEditWindow(nextEdit, nextSelection, restoration)
    }

    /** Applies one committed history revision without replacing the focused field or IME. */
    fun reconcileHistoryEditWindow(nextEdit: ActiveEditWindow) {
        require(nextEdit.generation != edit.generation) {
            "history edit-window handoff must advance the generation"
        }
        require(nextEdit.snapshot.metrics.revision > edit.snapshot.metrics.revision) {
            "history edit-window handoff must advance the revision"
        }
        require(!hasChanges) { "history edit-window handoff requires a clean draft" }
        require(textFieldState.composition == null) {
            "history edit-window handoff cannot replace composing text"
        }
        val nextSelection = TextRange(
            start = Math.toIntExact(nextEdit.localSelection.start),
            end = Math.toIntExact(nextEdit.localSelection.end)
        )
        replaceEditWindow(nextEdit, nextSelection, restoration = null)
    }

    /** Replaces a validated clean window while retaining its Compose field and scroll objects. */
    @OptIn(ExperimentalFoundationApi::class)
    private fun replaceEditWindow(
        nextEdit: ActiveEditWindow,
        nextSelection: TextRange,
        restoration: EditWindowScrollRestoration?
    ) {
        require(nextSelection.min >= 0 && nextSelection.max <= nextEdit.snapshot.text.length) {
            "replacement selection exceeds the next edit window"
        }
        edit = nextEdit
        textFieldState.edit {
            replace(start = 0, end = length, text = nextEdit.snapshot.text)
            selection = nextSelection
        }
        inputRejection = null
        selectionBoundaryMessage = null
        scrollRestoration = restoration
        viewportAnchor = null
        observedTextChanges = 0
        lastObservedText = nextEdit.snapshot.text
        lastObservedSelection = nextSelection
        pendingHistorySelectionBefore = null
        lastObservedComposition = null
        focusRestorationSelection = nextSelection
        textFieldState.undoState.clearHistory()
    }

    /** Releases retained undo operations when this bounded field identity is superseded. */
    @OptIn(ExperimentalFoundationApi::class)
    fun release() {
        textFieldState.undoState.clearHistory()
        observedTextChanges = 0
    }

    /** Clears accumulated undo operations only outside active IME composition. */
    @OptIn(ExperimentalFoundationApi::class)
    private fun clearUndoHistoryWhenReady() {
        if (
            observedTextChanges >= MAX_RETAINED_UNDO_CHANGES &&
            textFieldState.composition == null
        ) {
            textFieldState.undoState.clearHistory()
            observedTextChanges = 0
        }
    }
}
