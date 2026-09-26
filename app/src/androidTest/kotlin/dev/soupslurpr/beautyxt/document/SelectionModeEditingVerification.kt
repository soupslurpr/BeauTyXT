package dev.soupslurpr.beautyxt.document

import android.app.Instrumentation
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import dev.soupslurpr.beautyxt.ui.editor.DocumentSelection
import dev.soupslurpr.beautyxt.ui.editor.EditorPresentation
import dev.soupslurpr.beautyxt.ui.editor.exactSource
import dev.soupslurpr.beautyxt.ui.editor.EDIT_DRAFT_MAX_UTF16_UNITS
import kotlinx.coroutines.runBlocking

/** Selected reading text must become the actual edit range, not just a painted highlight. */
internal fun Instrumentation.verifySelectionModeEditing() {
    val original = "First paragraph.\n\nSecond paragraph with **bold words**."
    withReadingPage(original) { activity, session ->
        val reading = waitForAccessibilityNode("formatted reading paragraph") { it.text?.toString() == "Second paragraph with bold words." }
        val displayedStart = reading.text.toString().indexOf("bold")
        check(reading.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, displayedStart)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, displayedStart + 4)
        }))
        awaitReadingCondition("reading passage did not become selected") { session.documentSelection is DocumentSelection.Reading }
        val sourceStart = original.indexOf("bold").toLong()
        check(session.documentSelection?.exactSource(session.readingDocumentForSelection()) == Utf16Range(sourceStart, sourceStart + 4)) {
            "source mapping for selected bold text: ${session.documentSelection}, ${session.documentSelection?.exactSource(session.readingDocumentForSelection())}"
        }
        waitForAccessibilityIdle()
        requireActionableText("Selection actions").performRequiredClick()
        requireActionableText("Edit source text").performRequiredClick()
        awaitReadingCondition("reading selection did not become the source edit range") {
            session.presentation == EditorPresentation.Text && session.activeDraft?.let {
                it.edit.snapshot.range.start + it.textFieldState.selection.min == sourceStart &&
                    it.edit.snapshot.range.start + it.textFieldState.selection.max == sourceStart + 4
            } == true
        }
        waitForAccessibilityNode("focused source selection") {
            it.isEditable && it.isFocused && it.textSelectionEnd - it.textSelectionStart == 4
        }
        runOnMainSync {
            val editor = checkNotNull(activity.window.decorView.findTextEditorView())
            val connection = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
            check(connection.commitText("strong", 1))
        }
        val expected = original.replace("bold", "strong")
        awaitReadingCondition("typing did not replace exactly the selected source") {
            session.activeDraft?.textFieldState?.text?.toString() == expected
        }
        runOnMainSync { session.flushPendingEdit() }
        awaitReadingCondition("selected edit did not commit") { session.canUndo && !session.state.hasActiveDraftChanges }
        runBlocking { check(session.state.readSourceRange(session.state.metrics!!.revision, Utf16Range(0, expected.length.toLong())) == expected) }
        runOnMainSync { check(session.requestUndo()) }
        awaitReadingCondition("selected edit did not undo in one step") { session.activeDraft?.textFieldState?.text?.toString() == original }
    }
    val large = List(10) { "A long paragraph. ".repeat(230).trimEnd() }.joinToString("\n\n")
    withReadingPage(large) { activity, session ->
        runOnMainSync { check(session.selectWholeDocument()) }
        check(session.documentSelection?.exactSource(session.readingDocumentForSelection()) == Utf16Range(0, large.length.toLong()))
        requireActionableText("Selection actions").performRequiredClick()
        requireActionableText("Edit source text").performRequiredClick()
        awaitReadingCondition("large selection did not transfer beyond the bounded source window") {
            session.presentation == EditorPresentation.Text && session.activeDraft != null &&
                (session.documentSelection as? DocumentSelection.Source)?.range == Utf16Range(0, large.length.toLong())
        }
        check(session.activeDraft!!.edit.snapshot.text.length <= EDIT_DRAFT_MAX_UTF16_UNITS)
        check(!session.activeDraft!!.shouldRestoreEditorFocus)
        check(session.canEditDocumentSelection)
        waitForAccessibilityIdle()
        check(activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) != true)
    }
}
