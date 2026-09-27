package dev.soupslurpr.beautyxt.document

import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.runBlocking

/** Keeps a new document's actual typing separate from current and reviewed replacements. */
internal fun Instrumentation.verifyNewDocumentReplacement() {
    val original = "The small cat sleeps. Another cat stays."
    fun settle() {
        runBlocking { repeat(2) { awaitFrame() } }
        waitForAccessibilityIdle()
        uiAutomation.clearCache()
    }
    fun clickText(text: String) { settle(); requireActionableText(text).performRequiredClick() }
    fun clickIcon(label: String) { settle(); requireActionableContentDescription(label).performRequiredClick() }
    fun capture(name: String) {
        settle()
        val screenshot = checkNotNull(uiAutomation.takeScreenshot())
        try {
            File(targetContext.cacheDir, "new-document-$name.png").outputStream().use {
                check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { screenshot.recycle() }
    }
    fun checkNoReplacementUndo() {
        settle()
        check(uiAutomation.rootInActiveWindow.findNode {
            it.text?.toString() in listOf("Undo", "Undo replacement")
        } == null) { "Find offered Undo without an applied replacement" }
    }
    for (scenario in listOf("current", "batch", "review-before-apply", "unchanged")) {
        val reviewAll = scenario in listOf("batch", "review-before-apply")
        val home = startHomeDestination("New document")
        try {
            waitForEditField().performRequiredClick()
            sendStringSync(original)
            waitForEditorText(original)
            clickIcon("Find in document")
            val query = waitForAccessibilityNode("new document Find input") {
                it.isEditable && it.text.isNullOrEmpty()
            }
            check(query.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "cat")
            }))
            clickText("Replace")
            val replacement = waitForAccessibilityNode("empty replacement field") {
                it.isEditable && it.text.isNullOrEmpty()
            }
            check(replacement.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    if (scenario == "unchanged") "cat" else "dog")
            }))
            val changed = if (reviewAll) original.replace("cat", "dog") else original.replaceFirst("cat", "dog")
            if (reviewAll) {
                clickText("Review all")
                if (scenario == "review-before-apply") {
                    checkNoReplacementUndo()
                    capture("review-before-apply")
                    clickIcon("Return to document")
                    waitForEditorText(original)
                    continue
                }
                clickText("Apply 2 replacements")
            } else {
                clickText("Go to first match")
                checkNoReplacementUndo()
                clickText("Replace current")
                if (scenario == "unchanged") {
                    checkNoReplacementUndo()
                    waitForEditorText(original)
                    continue
                }
            }
            waitForEditorText(changed)
            capture("$scenario-replacement")
            clickText("Undo replacement")
            waitForEditorText(original)
            waitForAccessibilityNode("replacement Undo confirmation") { it.text?.toString() == "Replacement undone" }
            checkNoReplacementUndo()
            capture("$scenario-undone")
            if (scenario == "current") awaitReadingCondition("replacement Undo confirmation did not retire") {
                uiAutomation.clearCache()
                uiAutomation.rootInActiveWindow?.findNode { it.text?.toString() == "Replacement undone" } == null
            }
            clickIcon("More Find actions")
            clickText("Redo")
            waitForEditorText(changed)
            clickIcon("Close Find")
            clickIcon("Undo")
            waitForEditorText(original)
            if (scenario == "current") capture("navigation-history")
        } catch (failure: Throwable) {
            throw AssertionError("new document replacement ($scenario): ${failure.stackTraceToString()}", failure)
        } finally {
            runOnMainSync { home.finishAndRemoveTask() }
            waitForAccessibilityIdle()
        }
    }
}
