/* Verifies that Back dismisses a reading selection before navigating. */
package dev.soupslurpr.beautyxt.document

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.BackEventCompat
import dev.soupslurpr.beautyxt.HomeActivity
import dev.soupslurpr.beautyxt.ui.editor.EditorPresentation
import java.io.File

private const val SELECTION_TEXT = "Selection should stay in Read until dismissed."

/** Exercises actual selection, Copy, completed Back, and canceled predictive Back. */
internal fun Instrumentation.verifyReadingSelectionBack() {
    val originalFlags = uiAutomation.serviceInfo.flags
    uiAutomation.serviceInfo = uiAutomation.serviceInfo.apply {
        flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
    }
    try {
        verifyReadingSelectionBackWithWindows()
    } finally {
        uiAutomation.serviceInfo = uiAutomation.serviceInfo.apply { flags = originalFlags }
    }
}

private fun Instrumentation.verifyReadingSelectionBackWithWindows() {
    withReadingPage(SELECTION_TEXT) { activity, session ->
        requireActionableContentDescription("Show source text").performRequiredClick()
        waitForEditField()
        requireActionableContentDescription("Preview Markdown").performRequiredClick()
        awaitReadingCondition("nested preview did not hide the keyboard") {
            session.returnsToSourceOnBack &&
                activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) ==
                false
        }
        selectReadingText()
        captureReadingSelection("01-selected")
        cancelSelectionBack(activity)
        requireStillReading(activity, session, "canceled selection Back")
        waitForReadingCopyMenu()
        captureReadingSelection("02-canceled-back")
        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        requireStillReading(activity, session, "first Back with a selection")
        requireNoReadingSelectionMenu()
        captureReadingSelection("03-selection-cleared")
        check(!session.state.hasDocumentChanges) { "dismissing selection changed the document" }
        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitReadingCondition("second Back did not return to the source") {
            session.presentation == EditorPresentation.Text
        }
        check(!activity.isFinishing) { "returning to the source closed the document" }
        waitForEditField()
        captureReadingSelection("04-second-back-source")
    }

    // A read-first session must not acquire an extra navigation step after selection dismissal.
    for (viewOnly in listOf(false, true)) {
        withReadingPage(SELECTION_TEXT, viewOnly = viewOnly) { activity, session ->
            selectReadingText()
            runOnMainSync {
                activity.onBackPressedDispatcher.dispatchOnBackStarted(selectionBackEvent(0f))
            }
            waitForAccessibilityIdle()
            runOnMainSync {
                activity.onBackPressedDispatcher.dispatchOnBackProgressed(selectionBackEvent(0.7f))
                activity.onBackPressedDispatcher.onBackPressed()
            }
            requireStillReading(activity, session, "completed predictive selection Back")
            requireNoReadingSelectionMenu()
            check(!activity.isFinishing && !session.isDiscardConfirmationVisible)
            check(!session.state.hasDocumentChanges)
            if (viewOnly) check(session.isViewOnly && session.activeDraft == null)
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitReadingCondition("read-first session did not close on the next Back") {
                activity.isFinishing
            }
        }
    }

    // Selection in the read-only source view follows the same rule, without enabling editing.
    withReadingPage(SELECTION_TEXT, viewOnly = true) { activity, session ->
        requireActionableContentDescription("Show source text").performRequiredClick()
        awaitReadingCondition("read-only source did not open") {
            session.presentation == EditorPresentation.Text
        }
        selectReadingText()
        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        waitForAccessibilityIdle()
        requireNoReadingSelectionMenu()
        check(!activity.isFinishing && session.presentation == EditorPresentation.Text)
        check(session.isViewOnly && session.activeDraft == null)
        check(
            activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != true
        )
        captureReadingSelection("05-read-only-source-cleared")
        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitReadingCondition("read-only source did not close after its selection cleared") {
            activity.isFinishing
        }
    }

    // Copy still works, and its own selection dismissal must not leave a stale Back interceptor.
    withReadingPage(SELECTION_TEXT, viewOnly = true) { activity, session ->
        val clipboard = checkNotNull(activity.getSystemService(ClipboardManager::class.java))
        try {
            selectReadingText()
            waitForReadingCopyMenu().performRequiredClick()
            requireStillReading(activity, session, "Copy")
            runOnMainSync {
                check(clipboard.primaryClip?.getItemAt(0)?.text?.toString() == "Selection") {
                    "copying the selected word produced unexpected text"
                }
            }
            requireNoReadingSelectionMenu()
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitReadingCondition("Copy left an extra Back interception behind") {
                activity.isFinishing
            }
        } finally {
            runOnMainSync { clipboard.clearPrimaryClip() }
        }
    }
}

/** Selects the first word with a real long press, not a test-only selection API. */
private fun Instrumentation.selectReadingText() {
    // Let the mode switch and inset animations settle before injecting coordinates.
    SystemClock.sleep(500L)
    val line = waitForAccessibilityNode("selectable reading text") {
        it.text?.toString() == SELECTION_TEXT && !it.isEditable
    }
    val bounds = Rect().also(line::getBoundsInScreen)
    readingPageGesture(
        bounds.left + 30f,
        bounds.top + 25f,
        durationMillis = ViewConfiguration.getLongPressTimeout() + 100L
    )
    waitForReadingCopyMenu()
    waitForAccessibilityIdle()
}

private fun Instrumentation.cancelSelectionBack(activity: HomeActivity) {
    runOnMainSync {
        activity.onBackPressedDispatcher.dispatchOnBackStarted(selectionBackEvent(0f))
    }
    waitForAccessibilityIdle()
    runOnMainSync {
        activity.onBackPressedDispatcher.dispatchOnBackProgressed(selectionBackEvent(0.65f))
        activity.onBackPressedDispatcher.dispatchOnBackCancelled()
    }
    waitForAccessibilityIdle()
}

private fun selectionBackEvent(progress: Float) = BackEventCompat(
    touchX = 250f * progress,
    touchY = 400f,
    progress = progress,
    swipeEdge = BackEventCompat.EDGE_LEFT
)

private fun Instrumentation.requireNoReadingSelectionMenu() {
    val deadline = SystemClock.uptimeMillis() + 5_000L
    while (SystemClock.uptimeMillis() < deadline) {
        val menu = uiAutomation.windows.firstNotNullOfOrNull { window ->
            window.root?.findNode { it.text?.toString() in listOf("Copy", "Select all") }
        }
        if (menu == null) return
        SystemClock.sleep(20L)
    }
    error("selection toolbar remained after dismissing the selection")
}

/** Android's floating selection toolbar lives in a separate accessibility window. */
private fun Instrumentation.findReadingCopyMenu(): AccessibilityNodeInfo? =
    uiAutomation.windows.firstNotNullOfOrNull { window ->
        window.root?.findNode { it.text?.toString() == "Copy" }
            ?.let { generateSequence(it) { node -> node.parent } }
            ?.firstOrNull { it.isClickable && it.isEnabled }
    }

private fun Instrumentation.waitForReadingCopyMenu(): AccessibilityNodeInfo {
    val deadline = SystemClock.uptimeMillis() + 10_000L
    while (SystemClock.uptimeMillis() < deadline) {
        findReadingCopyMenu()?.let { return it }
        SystemClock.sleep(20L)
    }
    captureReadingSelection("missing-copy-menu")
    error("selection Copy menu did not appear")
}

private fun Instrumentation.captureReadingSelection(name: String) {
    SystemClock.sleep(500L)
    val directory = File(targetContext.cacheDir, "reading-selection-back")
    check(directory.isDirectory || directory.mkdirs())
    val bitmap = checkNotNull(uiAutomation.takeScreenshot())
    try {
        File(directory, "$name.png").outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    } finally {
        bitmap.recycle()
    }
}
