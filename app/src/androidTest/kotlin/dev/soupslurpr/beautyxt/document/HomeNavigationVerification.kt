/* Verifies the Home document entry's lifetime through Android and Compose navigation. */
package dev.soupslurpr.beautyxt.document

import android.app.Activity
import android.app.ActivityManager
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import dev.soupslurpr.beautyxt.HomeActivity

/** Exercises picker cancellation, guarded close, and independent successive entry state. */
internal fun Instrumentation.verifyHomeDocumentNavigation() {
    val home = startActivitySync(
        Intent(targetContext, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    ) as HomeActivity
    val manager = checkNotNull(targetContext.getSystemService(ActivityManager::class.java))
    fun assertSingleHomeActivity() {
        uiAutomation.waitForIdle(500L, 15_000L)
        // Entries can replace their semantics while the activity's window stays intact.
        uiAutomation.clearCache()
        check(!home.isFinishing && !home.isDestroyed && home.hasWindowFocus()) {
            "Home navigation replaced or finished its activity"
        }
        val task = checkNotNull(manager.appTasks.firstOrNull { it.taskInfo?.taskId == home.taskId })
        check(task.taskInfo?.numActivities == 1) { "Home navigation started another activity" }
    }
    try {
        repeat(2) {
            returnHomePickerResult(Activity.RESULT_CANCELED)
            requireActionableText("New document")
            assertSingleHomeActivity()
        }
        requireActionableText("New document").performRequiredClick()
        waitForEditorText("")
        assertSingleHomeActivity()
        enterHomeNavigationDraft("A draft belongs only to its current navigation entry.")
        requireActionableContentDescription("Back").performRequiredClick()
        requireActionableText("Keep editing").performRequiredClick()
        waitForEditorText("A draft belongs only to its current navigation entry.")
        requireActionableContentDescription("Back").performRequiredClick()
        requireActionableText("Discard and close").performRequiredClick()
        requireActionableText("Open file")
        assertSingleHomeActivity()

        repeat(2) {
            requireActionableText("New document").performRequiredClick()
            waitForEditorText("")
            assertSingleHomeActivity()
            // Focus can open the IME after the page's accessibility tree becomes idle.
            // Hiding it before that request arrives makes the next Back dismiss the IME.
            waitForImeVisibility(home, visible = true)
            runOnMainSync { home.window.insetsController?.hide(WindowInsets.Type.ime()) }
            waitForImeVisibility(home, visible = false)
            // Visibility changes at the start of the IME animation; let its Back guard settle.
            // Accessibility idle can return immediately because placement is not an event.
            SystemClock.sleep(500L)
            uiAutomation.waitForIdle(500L, 15_000L)
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            requireActionableText("Open file")
            assertSingleHomeActivity()
        }
        requireActionableText("About").performRequiredClick()
        requireActionableText("Open-source licenses")
        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        requireActionableText("Open file")
        assertSingleHomeActivity()
    } finally {
        runOnMainSync(home::finishAndRemoveTask)
        waitForAccessibilityIdle()
    }
}

/** Uses the actual editable semantics so checkpoints and dirty-close guards see the draft. */
internal fun Instrumentation.enterHomeNavigationDraft(text: String) {
    check(
        waitForEditField().performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
        )
    ) { "could not enter the Home navigation draft" }
    waitForEditorText(text)
}
