package dev.soupslurpr.beautyxt.document

import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.key
import androidx.compose.ui.text.input.TextFieldValue
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.ui.UiText
import dev.soupslurpr.beautyxt.ui.designsystem.BeauTyXTTheme
import dev.soupslurpr.beautyxt.ui.editor.DocumentEditor
import dev.soupslurpr.beautyxt.ui.editor.EditorPresentation
import java.io.File
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.runBlocking

/** Reviews and applies a native batch, then continues using real keyboard commands. */
internal fun Instrumentation.verifyReplacementReview() {
    val original = "cat dog cat"
    withReadingPage(original, initialPresentation = EditorPresentation.Text) { activity, session ->
        fun source() = session.activeDraft?.textFieldState?.text?.toString()
        fun awaitFrames() = runBlocking { repeat(2) { awaitFrame() } }
        awaitFrames()
        documentKey(KeyEvent.KEYCODE_H, KeyEvent.META_CTRL_ON)
        awaitReadingCondition("Ctrl+H did not open replacement review") { session.isReplaceVisible && session.isFindResultsExpanded }
        runOnMainSync {
            session.updateFindFieldValue(TextFieldValue("cat"))
            session.updateReplacementFieldValue(TextFieldValue("dog"))
        }
        awaitReadingCondition("replacement review did not complete") { session.canApplyFindReplacements && session.findResults.size == 2 }
        val replacement = waitForAccessibilityNode("replacement input") { it.isEditable && it.text?.toString() == "dog" }
        check(replacement.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
        waitForAccessibilityNode("focused replacement input") { it.isEditable && it.isFocused && it.text?.toString() == "dog" }
        requireActionableText("Apply 2 replacements").performRequiredClick()
        awaitReadingCondition("Apply did not publish its batch") { source() == "dog dog dog" && session.canUndo && session.isFindComplete }
        awaitFrames()
        documentKey(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON)
        awaitReadingCondition("Ctrl+Z could not undo the batch from replacement review") { source() == original && session.canRedo }
        check(session.replacementFieldValue.text == "dog") { "document Undo changed the replacement input" }
        awaitFrames()
        documentKey(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
        awaitReadingCondition("Ctrl+Shift+Z did not redo the batch") { source() == "dog dog dog" && session.canUndo }
        requireActionableText("Undo").performRequiredClick()
        awaitReadingCondition("review Undo button did not undo the batch") { source() == original && session.canRedo }
        awaitFrames()
        documentKey(KeyEvent.KEYCODE_Y, KeyEvent.META_CTRL_ON)
        awaitReadingCondition("Ctrl+Y lost focus after the Undo button") { source() == "dog dog dog" && session.canUndo }
        runOnMainSync {
            activity.setContent {
                key("replacement review after Apply") {
                    BeauTyXTTheme { DocumentEditor(session, activity::finish, closesDocumentTask = false) }
                }
            }
        }
        awaitFrames()
        awaitReadingCondition("recreated replacement review lost its command focus") {
            hasComposeKeyboardFocus(activity.window.decorView, documentOnly = true)
        }
        documentKey(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON)
        awaitReadingCondition("document Undo stopped working after review recreation") { source() == original && session.canRedo }
        val revision = session.state.metrics!!.revision
        val field = waitForAccessibilityNode("replacement field after Undo") { it.isEditable && it.text?.toString() == "dog" }
        check(field.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
        waitForAccessibilityNode("replacement field owns local Undo") { it.isEditable && it.isFocused && it.text?.toString() == "dog" }
        check(field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 3)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 3)
        }))
        awaitFrames()
        sendStringSync("x")
        awaitReadingCondition("hardware typing did not reach replacement input") { session.replacementFieldValue.text == "dogx" }
        awaitFrames()
        documentKey(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON)
        awaitReadingCondition("replacement input lost its own Undo") { session.replacementFieldValue.text == "dog" }
        check(session.state.metrics!!.revision == revision && source() == original) { "input Undo changed the document" }
        requireActionableText("Return to document").performRequiredClick()
        awaitFrames()
        awaitReadingCondition("closing review did not restore the Find query") {
            !session.isFindResultsExpanded && hasComposeKeyboardFocus(activity.window.decorView, editableText = "cat")
        }
        check(activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != true) {
            "applying replacement opened the software keyboard"
        }
    }
    withReadingPage(original, initialPresentation = EditorPresentation.Text) { _, session ->
        runOnMainSync {
            session.showFind(false)
            session.showReplace()
            session.updateFindRegex(true)
            session.updateFindFieldValue(TextFieldValue("(?P<word>cat|dog)"))
            session.updateReplacementFieldValue(TextFieldValue("${'$'}{word}"))
        }
        awaitReadingCondition("capture replacement did not identify unchanged matches") {
            session.isFindComplete && session.findResults.size == 3 && session.unchangedReplacementCount == 3
        }
        check(!session.canApplyFindReplacements)
        runOnMainSync { session.updateReplacementFieldValue(TextFieldValue("dog")) }
        awaitReadingCondition("mixed replacement review did not count its actual changes") {
            session.isFindComplete && session.includedReplacementCount == 2 && session.unchangedReplacementCount == 1
        }
        waitForAccessibilityNode("unchanged match explanation") { it.text?.toString() == "1 match already has the replacement text." }
        waitForAccessibilityIdle()
        val screenshot = checkNotNull(uiAutomation.takeScreenshot())
        try {
            File(targetContext.cacheDir, "replacement-review.png").outputStream().use {
                check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { screenshot.recycle() }
        requireActionableText("Apply 2 replacements").performRequiredClick()
        awaitReadingCondition("mixed batch did not apply exactly two changes") {
            session.activeDraft?.textFieldState?.text?.toString() == "dog dog dog" && session.isFindComplete
        }
        check(session.unchangedReplacementCount == 3 && !session.canApplyFindReplacements)
    }
}

/** Repeated replacement skips inserted matches and advances through zero-width positions. */
internal fun Instrumentation.verifyReplacementProgress() {
    for (zeroWidth in listOf(false, true)) {
        val original = if (zeroWidth) "a\nb\nc" else "cat cat cat"
        val expectedSteps = if (zeroWidth) listOf(original, ">a\nb\nc", ">a\n>b\nc", ">a\n>b\n>c")
            else listOf(original, "catcat cat cat", "catcat catcat cat", "catcat catcat catcat")
        withReadingPage(original, initialPresentation = EditorPresentation.Text) { _, session ->
            fun source() = session.activeDraft?.textFieldState?.text?.toString()
            runOnMainSync {
                session.showFind(false)
                session.showReplace()
                session.updateFindRegex(zeroWidth)
                session.updateFindFieldValue(TextFieldValue(if (zeroWidth) "(?m)^" else "cat"))
                session.updateReplacementFieldValue(TextFieldValue(if (zeroWidth) ">" else "catcat"))
            }
            awaitReadingCondition("Replace current fixture did not complete") { session.isFindComplete && session.findResults.size == 3 }
            runOnMainSync { check(session.selectFindResult(0)) }
            awaitReadingCondition("initial match did not finish navigating") {
                session.canReplaceCurrent && session.findMatch != null && !session.isFindResultsExpanded
            }
            val initialRevision = session.state.metrics!!.revision
            for (step in 1..3) {
                runBlocking { repeat(2) { awaitFrame() } }
                runOnMainSync { session.updateFindResultsExpanded(true) }
                runBlocking { repeat(2) { awaitFrame() } }
                waitForAccessibilityIdle()
                // Compact navigation recreates the pane; discard Android's old virtual-node ids.
                check(uiAutomation.clearCache())
                check(requireActionableText("Replace current").performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    "Replace current click failed at step $step (zero width: $zeroWidth), enabled=${session.canReplaceCurrent}, expanded=${session.isFindResultsExpanded}"
                }
                awaitReadingCondition("Replace current did not commit step $step (zero width: $zeroWidth)") {
                    source() == expectedSteps[step] && session.isFindComplete
                }
                check(session.state.metrics!!.revision == initialRevision + step)
                if (step < 3) {
                    val start = step * if (zeroWidth) 3L else 7L
                    val next = Utf16Range(start, start + if (zeroWidth) 0 else 3)
                    awaitReadingCondition("automatic advancement revisited inserted text at step $step (zero width: $zeroWidth)") {
                        session.findMatch?.range == next && !session.isFindResultsExpanded
                    }
                } else {
                    awaitReadingCondition("Replace current did not report the completed scope boundary") {
                        session.findActionMessage == UiText.Resource(R.string.replace_end_scope)
                    }
                    check(!session.canReplaceCurrent)
                }
            }
            repeat(3) { undone ->
                runBlocking { repeat(2) { awaitFrame() } }
                val revision = session.state.metrics!!.revision
                documentKey(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON)
                awaitReadingCondition("Undo did not reverse one Replace current action") {
                    session.state.metrics!!.revision == revision + 1 && source() == expectedSteps[2 - undone] && session.canRedo
                }
            }
            check(source() == original && !session.canUndo) { "Replace current did not retain exactly one Undo per change" }
        }
    }
}
