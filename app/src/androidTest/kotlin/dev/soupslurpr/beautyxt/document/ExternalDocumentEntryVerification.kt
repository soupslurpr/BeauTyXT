package dev.soupslurpr.beautyxt.document

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import dev.soupslurpr.beautyxt.MainActivity
import dev.soupslurpr.beautyxt.testing.DocumentCallerContract

/** Exercises real external intent resolution, URI grants, shares, and Back to the caller. */
internal fun Instrumentation.verifyExternalDocumentEntryPoints() {
    val manager = checkNotNull(targetContext.getSystemService(ActivityManager::class.java))
    for ((action, shareText) in listOf(
        Intent.ACTION_VIEW to false,
        Intent.ACTION_EDIT to false,
        Intent.ACTION_SEND to false,
        Intent.ACTION_SEND to true
    )) {
        val monitor = addMonitor(MainActivity::class.java.name, null, false)
        var document: MainActivity? = null
        try {
            targetContext.startActivity(
                Intent()
                    .setClassName(DocumentCallerContract.PACKAGE, DocumentCallerContract.ACTIVITY)
                    .putExtra(DocumentCallerContract.ACTION_EXTRA, action)
                    .apply {
                        if (shareText) putExtra(Intent.EXTRA_TEXT, DocumentCallerContract.SHARED_TEXT)
                    }
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            document = checkNotNull(waitForMonitorWithTimeout(monitor, 15_000L)) {
                "external $action did not reach a document activity"
            } as MainActivity
            check(document.isTaskRoot) { "external $action did not create a document task" }
            if (action == Intent.ACTION_SEND) requireActionableText("Open").performRequiredClick()
            val expectedText = if (shareText) DocumentCallerContract.SHARED_TEXT
                else DocumentCallerContract.SOURCE_TEXT
            waitForAccessibilityNode("external $action content (shared text: $shareText)") {
                it.text?.toString() == expectedText
            }
            val taskId = document.taskId
            check(manager.appTasks.any { it.taskInfo?.taskId == taskId }) {
                "external $action document was absent from Recents"
            }
            requireActionableContentDescription("Back").performRequiredClick()
            if (shareText) requireActionableText("Close without saving").performRequiredClick()
            waitForAccessibilityNode("external caller after closing its document") {
                it.packageName?.toString() == DocumentCallerContract.PACKAGE
            }
            waitForAccessibilityIdle()
            awaitClosureCondition("external $action document stayed in Recents after closing") {
                document.isDestroyed && manager.appTasks.none { it.taskInfo?.taskId == taskId }
            }
        } catch (failure: Throwable) {
            val visibleText = mutableListOf<String>()
            uiAutomation.rootInActiveWindow?.findNode {
                it.text?.toString()?.takeIf(String::isNotBlank)?.let(visibleText::add)
                false
            }
            throw IllegalStateException(
                "external $action (shared text: $shareText): ${failure.message}; " +
                    "visible text: $visibleText",
                failure
            )
        } finally {
            removeMonitor(monitor)
            document?.let { runOnMainSync(it::finishAndRemoveTask) }
            if (uiAutomation.rootInActiveWindow?.packageName?.toString() ==
                DocumentCallerContract.PACKAGE
            ) {
                check(uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
                awaitExternalCallerClosed()
            }
        }
    }
}

/** Waits for asynchronous global Back before it can accidentally reach the next document. */
private fun Instrumentation.awaitExternalCallerClosed() {
    val deadline = SystemClock.uptimeMillis() + 15_000L
    while (SystemClock.uptimeMillis() < deadline) {
        val activePackage = uiAutomation.rootInActiveWindow?.packageName?.toString()
        if (activePackage != null && activePackage != DocumentCallerContract.PACKAGE) return
        SystemClock.sleep(20L)
    }
    error("the external caller did not finish before the next launch")
}
