package dev.soupslurpr.beautyxt.document

import android.app.Instrumentation
import androidx.compose.ui.text.input.TextFieldValue
import dev.soupslurpr.beautyxt.ui.editor.EditorPresentation

/** Keeps cursor history in overflow while preserving both destinations and document text. */
internal fun Instrumentation.verifyNavigationHistoryControls() {
    val original = "Notes for tomorrow\n\n" + (1..14).joinToString("\n\n") {
        "Passage $it: Keep the document comfortable to read and edit."
    }
    withReadingPage(original, initialPresentation = EditorPresentation.Text) { _, session ->
        fun caret(): Long = checkNotNull(session.activeDraft).let {
            it.edit.snapshot.range.start + it.textFieldState.selection.start
        }
        runOnMainSync {
            session.showFind(false)
            session.updateFindFieldValue(TextFieldValue("Passage 12"))
        }
        awaitReadingCondition("navigation fixture search did not finish") { session.isFindComplete }
        runOnMainSync { check(session.findNext()) }
        awaitReadingCondition("Find did not retain cursor history") { session.findMatch != null && session.hasPreviousLocation }
        val destination = checkNotNull(session.findMatch).range.start
        requireActionableContentDescription("Close Find").performRequiredClick()
        awaitReadingCondition("Find did not close") { !session.isFindVisible }
        waitForAccessibilityIdle()
        uiAutomation.clearCache()
        check(uiAutomation.rootInActiveWindow.findNode {
            it.text?.toString() in listOf("Previous location", "Next location")
        } == null) { "Closing Find added a navigation toolbar" }
        captureRecoveryScreen("navigation-editor")
        requireActionableContentDescription("More options").performRequiredClick()
        requireActionableText("Previous location")
        captureRecoveryScreen("navigation-menu")
        requireActionableText("Previous location").performRequiredClick()
        awaitReadingCondition("Previous location did not restore the original caret") { caret() == 0L && session.hasNextLocation }
        waitForAccessibilityIdle()
        uiAutomation.clearCache()
        requireActionableContentDescription("More options").performRequiredClick()
        requireActionableText("Next location").performRequiredClick()
        awaitReadingCondition("Next location did not restore the found passage") { caret() == destination && !session.hasNextLocation }
        check(session.activeDraft?.textFieldState?.text?.toString() == original && !session.state.hasDocumentChanges) {
            "Cursor navigation changed the document"
        }
    }
}
