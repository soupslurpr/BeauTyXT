package dev.soupslurpr.beautyxt.document

import android.app.Instrumentation
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.input.TextFieldValue
import androidx.activity.compose.setContent
import androidx.compose.runtime.key
import dev.soupslurpr.beautyxt.ui.designsystem.BeauTyXTTheme
import dev.soupslurpr.beautyxt.ui.editor.DocumentEditor
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.runBlocking
import dev.soupslurpr.beautyxt.ui.editor.DocumentSelection
import dev.soupslurpr.beautyxt.ui.editor.EditorPresentation
import dev.soupslurpr.beautyxt.ui.editor.MarkdownPreviewStatus

/** Sends real keys through production focus owners, including layouts replaced by Find. */
internal fun Instrumentation.verifyDocumentKeyboardJourney() {
    withReadingPage("Source opened without requesting a keyboard.", initialPresentation = EditorPresentation.Text) { activity, session ->
        awaitReadingCondition("unfocused source did not acquire a document keyboard target") {
            hasComposeKeyboardFocus(activity.window.decorView)
        }
        check(!session.activeDraft!!.shouldRestoreEditorFocus)
        documentKey(KeyEvent.KEYCODE_F, KeyEvent.META_CTRL_ON)
        awaitReadingCondition("Ctrl+F did not open Find from newly opened source") { session.isFindVisible }
        check(activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != true)
    }
    val text = (1..24).joinToString("\n\n") { "Passage $it has a silver lining. " + "Context for the reader. ".repeat(12) }
    withReadingPage(text) { activity, session ->
        fun awaitKeyFocus() {
            // Model/input state changes precede Compose's next layout and focus effects.
            // Accessibility can already be idle while that frame is still queued.
            runBlocking { repeat(2) { awaitFrame() } }
            awaitReadingCondition("document controls did not regain keyboard focus") {
                hasComposeKeyboardFocus(activity.window.decorView)
            }
        }
        awaitKeyFocus()
        documentKey(KeyEvent.KEYCODE_F, KeyEvent.META_CTRL_ON)
        awaitReadingCondition("Ctrl+F did not open Find from the reading page") { session.isFindVisible }
        waitForAccessibilityNode("Find query owns hardware focus") { it.isEditable && it.isFocused }
        runOnMainSync { session.updateFindFieldValue(TextFieldValue("silver lining")) }
        awaitReadingCondition("reading matches did not complete") { session.isFindComplete && session.findResults.size == 24 }
        waitForAccessibilityIdle()
        requireActionableText("Options and results").performRequiredClick()
        awaitReadingCondition("Find options did not open") { session.isFindResultsExpanded }
        waitForAccessibilityIdle()
        requireActionableContentDescription("Next match").performRequiredClick()
        try { awaitReadingCondition("first reading match was not revealed") {
            session.findResultIndex >= 0 && !session.isFindResultsExpanded &&
                (session.markdownPreviewStatus as? MarkdownPreviewStatus.Ready)?.scrollRestoration == null
        } } catch (failure: AssertionError) {
            error("${failure.message}; index=${session.findResultIndex}, expanded=${session.isFindResultsExpanded}, restoration=${(session.markdownPreviewStatus as? MarkdownPreviewStatus.Ready)?.scrollRestoration}")
        }
        waitForAccessibilityIdle()
        val firstMatch = session.findResultIndex
        awaitKeyFocus()
        runOnMainSync {
            activity.setContent {
                key("Find after navigation") {
                    BeauTyXTTheme { DocumentEditor(session, activity::finish, closesDocumentTask = false) }
                }
            }
        }
        runBlocking { repeat(2) { awaitFrame() } }
        awaitReadingCondition("recreating Find stole focus from the revealed document") {
            hasComposeKeyboardFocus(activity.window.decorView, documentOnly = true)
        }
        check(session.findResultIndex == firstMatch)
        documentKey(KeyEvent.KEYCODE_F3)
        awaitReadingCondition("F3 lost document focus after dismissing Find options") { session.findResultIndex == (firstMatch + 1) % 24 }
        waitForAccessibilityIdle()
        awaitKeyFocus()
        documentKey(KeyEvent.KEYCODE_F3)
        awaitReadingCondition("repeated F3 stopped navigating") { session.findResultIndex == (firstMatch + 2) % 24 }
        waitForAccessibilityIdle()
        awaitKeyFocus()
        documentKey(KeyEvent.KEYCODE_F3, KeyEvent.META_SHIFT_ON)
        awaitReadingCondition("Shift+F3 did not navigate backward") { session.findResultIndex == (firstMatch + 1) % 24 }
        waitForAccessibilityIdle()
        awaitKeyFocus()
        documentKey(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
        awaitReadingCondition("previous-location shortcut did not restore the origin") { session.hasNextLocation }
        waitForAccessibilityIdle()
        awaitKeyFocus()
        documentKey(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
        awaitReadingCondition("next-location shortcut did not return to Find") { !session.hasNextLocation }
        waitForAccessibilityIdle()
        awaitKeyFocus()
        documentKey(KeyEvent.KEYCODE_ESCAPE)
        awaitReadingCondition("Escape did not close Find") { !session.isFindVisible }
        waitForAccessibilityIdle()
        check(session.canShowFind) { "Find cannot reopen after location history: ${session.state.status}" }
        awaitKeyFocus()
        documentKey(KeyEvent.KEYCODE_F, KeyEvent.META_CTRL_ON)
        awaitReadingCondition("Ctrl+F did not reopen after closing Find") { session.isFindVisible }
        check(session.findFieldValue.text == "silver lining") { "reopening Find lost the query" }
        waitForAccessibilityIdle()
        awaitKeyFocus()
        documentKey(KeyEvent.KEYCODE_ESCAPE)
        awaitReadingCondition("reopened Find did not close") { !session.isFindVisible }
        waitForAccessibilityIdle()
        awaitKeyFocus()
        documentKey(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON)
        awaitReadingCondition("Ctrl+A did not reach reading selection after closing Find") {
            (session.documentSelection as? DocumentSelection.Reading)?.let { it.start.block == 0 && it.end.block == 23 } == true
        }
        check(activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != true) {
            "hardware document commands opened the software keyboard"
        }
        documentKey(KeyEvent.KEYCODE_ESCAPE)
        awaitReadingCondition("Escape did not clear document selection") { session.documentSelection == null }
        requireActionableContentDescription("Show source text").performRequiredClick()
        awaitReadingCondition("source editor did not open") { session.presentation == EditorPresentation.Text && session.activeDraft != null }
        waitForAccessibilityIdle()
        awaitKeyFocus()
        documentKey(KeyEvent.KEYCODE_F, KeyEvent.META_CTRL_ON)
        awaitReadingCondition("Ctrl+F lost focus when switching to Source") { session.isFindVisible }
        awaitReadingCondition("source matches did not complete") { session.isFindComplete && session.findResults.size == 24 }
        waitForAccessibilityNode("source Find query owns keyboard focus") {
            it.isEditable && it.isFocused && it.text?.toString() == "silver lining"
        }
        awaitKeyFocus()
        documentKey(KeyEvent.KEYCODE_F3)
        awaitReadingCondition("source match was not selected") { session.findResultIndex >= 0 && session.findMatch != null }
        waitForAccessibilityIdle()
        awaitKeyFocus()
        val previous = session.findResultIndex
        documentKey(KeyEvent.KEYCODE_F3)
        awaitReadingCondition("F3 lost focus after source match navigation") { session.findResultIndex == (previous + 1) % 24 }
        awaitKeyFocus()
        documentKey(KeyEvent.KEYCODE_H, KeyEvent.META_CTRL_ON)
        awaitReadingCondition("Ctrl+H did not open Replace") { session.isReplaceVisible && session.isFindResultsExpanded }
        awaitKeyFocus()
        runOnMainSync { session.updateReplacementFieldValue(TextFieldValue("silver light")) }
        val replacement = waitForAccessibilityNode("replacement field") { it.isEditable && it.text?.toString() == "silver light" }
        check(replacement.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS))
        waitForAccessibilityNode("focused replacement field") { it.isEditable && it.isFocused && it.text?.toString() == "silver light" }
        runOnMainSync {
            activity.setContent {
                key("Find while editing replacement") {
                    BeauTyXTTheme { DocumentEditor(session, activity::finish, closesDocumentTask = false) }
                }
            }
        }
        runBlocking { repeat(2) { awaitFrame() } }
        awaitReadingCondition("recreated replacement input lost focus") {
            hasComposeKeyboardFocus(activity.window.decorView, editableText = "silver light")
        }
    }
}

internal fun hasComposeKeyboardFocus(view: View, documentOnly: Boolean = false, editableText: String? = null): Boolean =
    (view is ViewRootForTest && view.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false).any {
        it.config.getOrNull(SemanticsProperties.Focused) == true &&
            (!documentOnly || it.config.getOrNull(SemanticsProperties.EditableText) == null) &&
            (editableText == null || it.config.getOrNull(SemanticsProperties.EditableText)?.text == editableText)
    }) || (view is ViewGroup && (0 until view.childCount).any { hasComposeKeyboardFocus(view.getChildAt(it), documentOnly, editableText) })

internal fun Instrumentation.documentKey(code: Int, modifiers: Int = 0) {
    val down = SystemClock.uptimeMillis()
    sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, code, 0, modifiers))
    sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, modifiers))
}
