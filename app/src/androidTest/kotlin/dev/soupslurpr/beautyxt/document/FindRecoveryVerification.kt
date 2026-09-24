/* Verifies that inline Find failures can be retried through the actual editor UI. */
package dev.soupslurpr.beautyxt.document

import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.input.TextFieldValue
import dev.soupslurpr.beautyxt.HomeActivity
import dev.soupslurpr.beautyxt.markdown.client.IsolatedMarkdownRenderer
import dev.soupslurpr.beautyxt.ui.designsystem.BeauTyXTTheme
import dev.soupslurpr.beautyxt.ui.editor.DocumentEditor
import dev.soupslurpr.beautyxt.ui.editor.EditorDocumentState
import dev.soupslurpr.beautyxt.ui.editor.EditorSession
import dev.soupslurpr.beautyxt.ui.editor.FindStatus
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Injects one failed search, then retries it without closing Find or changing its query. */
internal fun Instrumentation.verifyFindRetry(capturePreviews: Boolean = false) {
    val originalRotation = Settings.System.getInt(
        targetContext.contentResolver, Settings.System.USER_ROTATION, 0
    )
    val automaticRotation = Settings.System.getInt(
        targetContext.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1
    ) != 0
    val document = RustDocument.createEmpty()
    val metrics = document.replace(0, Utf16Range(0, 0), "alpha beta alpha\n")
    val failNextFind = AtomicBoolean(true)
    val session = EditorSession(
        title = "Find recovery.txt",
        state = EditorDocumentState(
            object : EditorDocument by document {
                override fun find(request: FindRequest): FindBatch {
                    check(!failNextFind.getAndSet(false)) { "injected Find failure" }
                    return document.find(request)
                }
            },
            initialRevision = metrics.revision
        ),
        markdownRenderer = IsolatedMarkdownRenderer(targetContext)
    )
    val intent = Intent(targetContext, HomeActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    // Leave the launcher's NOSENSOR orientation before locking rotation; otherwise
    // Android can revert the requested angle while the first activity is starting.
    startActivitySync(intent)
    check(uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_90))
    runBlocking {
        withTimeout(10_000L) {
            while (targetContext.resources.configuration.orientation !=
                Configuration.ORIENTATION_LANDSCAPE) delay(16L)
        }
    }
    waitForIdleSync()
    val activity = startActivitySync(intent) as HomeActivity
    try {
        runOnMainSync {
            activity.setContent {
                BeauTyXTTheme {
                    DocumentEditor(session, activity::finish, closesDocumentTask = false)
                }
            }
        }
        requireActionableContentDescription("Find in document").performRequiredClick()
        runOnMainSync { session.updateFindFieldValue(TextFieldValue("alpha")) }
        awaitFindRecovery { session.findStatus is FindStatus.Failed && session.canNavigateFind }
        requireActionableContentDescription("Find in document").performRequiredClick()
        waitForAccessibilityNode("inline Find retry beside the query") { node ->
            if (node.text?.toString() != "Retry") return@waitForAccessibilityNode false
            val query = uiAutomation.rootInActiveWindow?.findNode {
                it.isEditable && it.text?.toString() == "alpha"
            } ?: return@waitForAccessibilityNode false
            val retryBounds = Rect().also(node::getBoundsInScreen)
            val queryBounds = Rect().also(query::getBoundsInScreen)
            abs(retryBounds.centerY() - queryBounds.centerY()) <
                24 * activity.resources.displayMetrics.density
        }
        // Keyboard and app insets animate after the matching accessibility nodes appear.
        SystemClock.sleep(500L)
        waitForAccessibilityIdle()
        if (capturePreviews) captureFindRecovery("find-retry-landscape")
        val retry = requireActionableText("Retry")
        val retryBounds = Rect().also(retry::getBoundsInScreen)
        val queryBounds = Rect().also(
            requireActionableContentDescription("Find in document")::getBoundsInScreen
        )
        check(abs(retryBounds.centerY() - queryBounds.centerY()) <
            24 * activity.resources.displayMetrics.density) {
            "Retry did not appear beside the inline query: $retryBounds, $queryBounds"
        }
        retry.performRequiredClick()
        awaitFindRecovery { session.findStatus is FindStatus.Match && session.canNavigateFind }
        runOnMainSync {
            check(session.isFindVisible && session.findFieldValue.text == "alpha") {
                "retry changed the retained query or closed Find"
            }
            check(session.findMatch?.range?.start == 0L) { "retry did not find the first match" }
            check(!session.state.hasDocumentChanges) { "retry changed the document" }
        }
        if (capturePreviews) captureFindRecovery("find-recovered-landscape")
    } catch (failure: Throwable) {
        runCatching { captureFindRecovery("find-retry-failure") }
        val nodes = StringBuilder()
        uiAutomation.rootInActiveWindow?.findNode { node ->
            nodes.appendLine(node.toString())
            false
        }
        File(targetContext.cacheDir, "find-retry-failure.txt").writeText(nodes.toString())
        throw failure
    } finally {
        runOnMainSync {
            activity.finishAndRemoveTask()
            session.close()
        }
        check(uiAutomation.setRotation(originalRotation))
        if (automaticRotation) check(uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE))
    }
}

private fun awaitFindRecovery(predicate: () -> Boolean) = runBlocking {
    withContext(Dispatchers.Main) {
        withTimeout(10_000L) { snapshotFlow(predicate).first { it } }
    }
}

private fun Instrumentation.captureFindRecovery(name: String) {
    waitForAccessibilityIdle()
    val bitmap = checkNotNull(uiAutomation.takeScreenshot())
    try {
        File(targetContext.cacheDir, "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    } finally {
        bitmap.recycle()
    }
}
