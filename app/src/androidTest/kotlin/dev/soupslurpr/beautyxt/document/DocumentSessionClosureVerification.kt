/* Verifies explicit session closure and Android document-task lifetime. */
package dev.soupslurpr.beautyxt.document

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.soupslurpr.beautyxt.DocumentSessionReturnDestination
import dev.soupslurpr.beautyxt.HomeActivity
import dev.soupslurpr.beautyxt.MainActivity
import dev.soupslurpr.beautyxt.sharing.IncomingDocumentShare
import dev.soupslurpr.beautyxt.sharing.IncomingSourcePurpose
import dev.soupslurpr.beautyxt.ui.BeauTyXTApp
import dev.soupslurpr.beautyxt.ui.designsystem.BeauTyXTTheme

private const val CLOSURE_SOURCE_TEXT = "Document closure keeps Back distinct from Home."
private const val SECOND_SOURCE_TEXT = "A second document has its own independent session."
private const val CLOSURE_TIMEOUT_MILLIS = 15_000L

/** Verifies a live document resumes from Home but leaves recents when Back closes it. */
internal fun Instrumentation.verifyExternalDocumentTaskClosure() = withClosureSource { source ->
    val activity = startActivitySync(
        Intent(Intent.ACTION_VIEW)
            .setClass(targetContext, MainActivity::class.java)
            .setDataAndType(source, "text/plain")
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                    Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS
            )
    ) as MainActivity
    val manager = checkNotNull(targetContext.getSystemService(ActivityManager::class.java))
    val taskId = activity.taskId
    fun documentTask() = manager.appTasks.firstOrNull { it.taskInfo?.taskId == taskId }
    try {
        waitForClosureSource()
        check(activity.isTaskRoot) { "external document did not own its task" }
        check(uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)) {
            "could not background the external document"
        }
        awaitClosureCondition("Home did not background the document") {
            !activity.hasWindowFocus()
        }
        check(!activity.isFinishing && !activity.isDestroyed) {
            "Home closed the document session"
        }
        val backgroundTask = checkNotNull(documentTask()) {
            "Home removed the live document from recents"
        }
        runOnMainSync(backgroundTask::moveToFront)
        waitForClosureSource()
        awaitClosureCondition("document did not resume from its task") {
            activity.hasWindowFocus()
        }
        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitClosureCondition("Back did not destroy the external document activity") {
            activity.isDestroyed
        }
        awaitClosureCondition("Back left the closed document in recents") {
            documentTask() == null
        }
    } finally {
        documentTask()?.let { task -> runOnMainSync(task::finishAndRemoveTask) }
        waitForAccessibilityIdle()
    }
}

/** Verifies closing either external document preserves the other document and Home. */
internal fun Instrumentation.verifyMultipleDocumentTaskClosure() {
    for (closeFirstOpened in listOf(false, true)) {
        withClosureSource { firstSource ->
            withClosureSource(text = SECOND_SOURCE_TEXT) { secondSource ->
                val manager =
                    checkNotNull(targetContext.getSystemService(ActivityManager::class.java))
                val home = startActivitySync(
                    Intent(targetContext, HomeActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                val documents = mutableMapOf<MainActivity, Int>()
                fun documentTask(activity: MainActivity) =
                    manager.appTasks.firstOrNull {
                        it.taskInfo?.taskId == documents.getValue(activity)
                    }
                fun showDocument(activity: MainActivity, text: String) {
                    val task = checkNotNull(documentTask(activity)) {
                        "live document disappeared from recents"
                    }
                    runOnMainSync(task::moveToFront)
                    waitForClosureSource(text)
                    awaitClosureCondition("document did not regain window focus") {
                        activity.hasWindowFocus()
                    }
                }
                try {
                    for ((source, text) in listOf(
                        firstSource to CLOSURE_SOURCE_TEXT,
                        secondSource to SECOND_SOURCE_TEXT
                    )) {
                        val activity = startActivitySync(
                            Intent(Intent.ACTION_VIEW)
                                .setClass(targetContext, MainActivity::class.java)
                                .setDataAndType(source, "text/plain")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ) as MainActivity
                        documents[activity] = activity.taskId
                        waitForClosureSource(text)
                    }
                    val (first, second) = documents.keys.toList()
                    check(setOf(home.taskId, first.taskId, second.taskId).size == 3) {
                        "Home and the two external documents did not get distinct tasks"
                    }
                    showDocument(first, CLOSURE_SOURCE_TEXT)
                    showDocument(second, SECOND_SOURCE_TEXT)
                    val closing = if (closeFirstOpened) first else second
                    val remaining = if (closeFirstOpened) second else first
                    val closingText =
                        if (closeFirstOpened) CLOSURE_SOURCE_TEXT else SECOND_SOURCE_TEXT
                    val remainingText =
                        if (closeFirstOpened) SECOND_SOURCE_TEXT else CLOSURE_SOURCE_TEXT
                    showDocument(closing, closingText)
                    sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    awaitClosureCondition("closed document remained in recents") {
                        closing.isDestroyed && documentTask(closing) == null
                    }
                    check(!remaining.isFinishing && !remaining.isDestroyed) {
                        "closing one document also closed the other"
                    }
                    showDocument(remaining, remainingText)
                    sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    awaitClosureCondition("last external document remained in recents") {
                        remaining.isDestroyed && documentTask(remaining) == null
                    }
                    check(!home.isFinishing && !home.isDestroyed) {
                        "closing the external documents also closed Home"
                    }
                    val homeTask = checkNotNull(
                        manager.appTasks.firstOrNull { it.taskInfo?.taskId == home.taskId }
                    ) { "closing the external documents removed Home from recents" }
                    runOnMainSync(homeTask::moveToFront)
                    requireActionableText("New document")
                } finally {
                    documents.keys.forEach { activity ->
                        documentTask(activity)?.let { task ->
                            runOnMainSync(task::finishAndRemoveTask)
                        }
                    }
                    runOnMainSync(home::finishAndRemoveTask)
                    waitForAccessibilityIdle()
                }
            }
        }
    }
}

/** Holds the exit frame so a normal close cannot hide an erroneous unavailable message. */
internal fun Instrumentation.verifyDocumentClosingPresentation() = withClosureSource { source ->
    val activity = startActivitySync(
        Intent(targetContext, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    ) as HomeActivity
    var closeRequested = false
    var incomingShare by mutableStateOf<IncomingDocumentShare?>(
        IncomingDocumentShare.Source(
            encodedUri = source.toString(),
            format = DocumentFormat.PlainText,
            purpose = IncomingSourcePurpose.View
        )
    )
    try {
        runOnMainSync {
            activity.setContent {
                BeauTyXTTheme {
                    BeauTyXTApp(
                        returnDestination = DocumentSessionReturnDestination.Caller,
                        incomingShare = incomingShare,
                        onIncomingShareConsumed = { incomingShare = null },
                        onDocumentSessionClosed = { closeRequested = true }
                    )
                }
            }
        }
        waitForClosureSource()
        awaitClosureCondition("document did not receive window focus") {
            activity.hasWindowFocus()
        }
        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitClosureCondition("Back did not request session closure") { closeRequested }
        uiAutomation.waitForIdle(500L, CLOSURE_TIMEOUT_MILLIS)
        check(uiAutomation.clearCache()) { "could not refresh the closing accessibility tree" }
        val root = checkNotNull(uiAutomation.rootInActiveWindow) {
            "closing presentation had no observable window"
        }
        check(root.packageName?.toString() == targetContext.packageName) {
            "closing presentation lost its foreground window"
        }
        val remainingText = root.findNode { !it.text.isNullOrBlank() }
        check(remainingText == null) {
            "normal closure displayed content instead of a neutral exit surface: " +
                remainingText?.text
        }
    } finally {
        runOnMainSync(activity::finishAndRemoveTask)
        waitForAccessibilityIdle()
    }
}

/** Waits for the imported fixture instead of treating activity startup as a ready editor. */
private fun Instrumentation.waitForClosureSource(text: String = CLOSURE_SOURCE_TEXT) {
    waitForAccessibilityNode("document closure source") {
        it.text?.toString() == text
    }
}

/** Observes lifecycle transitions on the main thread with a bounded timeout. */
private fun Instrumentation.awaitClosureCondition(message: String, condition: () -> Boolean) {
    val deadline = SystemClock.uptimeMillis() + CLOSURE_TIMEOUT_MILLIS
    do {
        var ready = false
        runOnMainSync { ready = condition() }
        if (ready) return
        SystemClock.sleep(20L)
    } while (SystemClock.uptimeMillis() < deadline)
    error(message)
}

/** Owns one synthetic provider document for the duration of a closure workflow. */
private fun Instrumentation.withClosureSource(
    text: String = CLOSURE_SOURCE_TEXT,
    verify: (Uri) -> Unit
) {
    val resolver = targetContext.contentResolver
    val source = checkNotNull(
        resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "beautyxt-document-closure.txt")
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        )
    ) { "could not create document closure source" }
    try {
        checkNotNull(resolver.openOutputStream(source, "wt")).use {
            it.write(text.toByteArray(Charsets.UTF_8))
        }
        check(
            resolver.update(
                source,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            ) == 1
        ) { "could not publish document closure source" }
        verify(source)
    } finally {
        check(resolver.delete(source, null, null) == 1) {
            "could not remove document closure source"
        }
    }
}
