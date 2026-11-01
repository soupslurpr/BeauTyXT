/* Verifies failed and invalid searches recover without changing source text. */
package dev.soupslurpr.beautyxt.document

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.WindowInsets
import androidx.activity.compose.setContent
import androidx.compose.ui.text.input.TextFieldValue
import dev.soupslurpr.beautyxt.HomeActivity
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.ui.UiText
import dev.soupslurpr.beautyxt.ui.designsystem.BeauTyXTTheme
import dev.soupslurpr.beautyxt.ui.editor.DocumentEditor
import dev.soupslurpr.beautyxt.ui.editor.EditorDocumentState
import dev.soupslurpr.beautyxt.ui.editor.EditorSession
import dev.soupslurpr.beautyxt.ui.editor.FindStatus
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.runBlocking

/** Exercises retry, malformed regex, bad capture references, and correction above the IME. */
internal fun Instrumentation.verifyFindRetry(capturePreviews: Boolean = false) {
    val originalRotation = captureFixtureRotation()
    val intent = Intent(targetContext, HomeActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    var primaryFailure: Throwable? = null
    try {
        for ((rotation, orientation, name) in listOf(
            Triple(UiAutomation.ROTATION_FREEZE_0, Configuration.ORIENTATION_PORTRAIT, "portrait"),
            Triple(UiAutomation.ROTATION_FREEZE_90, Configuration.ORIENTATION_LANDSCAPE, "landscape")
        )) {
            // The launcher requests NOSENSOR; leave it before freezing orientation.
            startActivitySync(intent)
            check(uiAutomation.setRotation(rotation))
            awaitReadingCondition("Find recovery did not rotate to $name") {
                targetContext.resources.configuration.orientation == orientation
            }
            val original = "alpha beta alpha\n"
            val document = RustDocument.createEmpty()
            val metrics = document.replace(0, Utf16Range(0, 0), original)
            val failNextFind = AtomicBoolean(true)
            val session = EditorSession("Find recovery.txt", EditorDocumentState(
                object : EditorDocument by document {
                    override fun compileSearch(query: String, options: SearchOptions): DocumentSearch {
                        check(!failNextFind.getAndSet(false)) { "injected temporary search failure" }
                        return document.compileSearch(query, options)
                    }
                }, initialRevision = metrics.revision))
            val activity = startActivitySync(intent) as HomeActivity
            var iterationFailure: Throwable? = null
            try {
                runOnMainSync {
                    activity.setContent { BeauTyXTTheme {
                        DocumentEditor(session, activity::finish, closesDocumentTask = false)
                    } }
                }
                requireActionableContentDescription("Find in document").performRequiredClick()
                runOnMainSync { session.updateFindFieldValue(TextFieldValue("alpha")) }
                awaitReadingCondition("temporary Find failure was not published") {
                    session.findStatus == FindStatus.Failed(UiText.Resource(R.string.find_failed))
                }
                assertFindRecoveryReachable(activity, "alpha")
                if (capturePreviews) captureRecoveryScreen("find-failed-$name")
                requireActionableText("Retry search").performRequiredClick()
                awaitReadingCondition("Find retry did not preserve and finish the query") {
                    session.isFindComplete && session.findFieldValue.text == "alpha" && session.findResults.size == 2
                }
                check(session.findMatch == null) { "Retry unexpectedly navigated the document" }

                runOnMainSync {
                    session.updateFindRegex(true)
                    session.updateFindFieldValue(TextFieldValue("("))
                }
                awaitReadingCondition("invalid regex did not explain how to recover") {
                    session.findStatus == FindStatus.Failed(UiText.Resource(R.string.find_invalid_query))
                }
                check(!session.canApplyFindReplacements)
                assertFindRecoveryReachable(activity, "(")
                if (capturePreviews) captureRecoveryScreen("find-invalid-regex-$name")
                runOnMainSync {
                    session.updateFindFieldValue(TextFieldValue("(alpha)"))
                    session.showReplace(showKeyboard = true)
                    session.updateReplacementFieldValue(TextFieldValue("${'$'}{missing}"))
                }
                awaitReadingCondition("invalid capture reference did not fail safely") {
                    session.findStatus == FindStatus.Failed(UiText.Resource(R.string.find_invalid_replacement))
                }
                check(!session.canApplyFindReplacements && !session.canUndo)
                assertFindRecoveryReachable(activity, "(alpha)")
                if (capturePreviews) captureRecoveryScreen("find-invalid-replacement-$name")
                runOnMainSync { session.updateReplacementFieldValue(TextFieldValue("${'$'}1!")) }
                awaitReadingCondition("correcting the capture reference did not recover") {
                    session.isFindComplete && session.canApplyFindReplacements && session.findResults.size == 2
                }
                check(session.findResults.all { it.hit.replacement == "alpha!" })
                check(session.activeDraft?.textFieldState?.text?.toString() == original && !session.state.hasDocumentChanges) {
                    "Failed Find or correcting inputs changed the source"
                }
                assertFindRecoveryReachable(activity, "(alpha)", failed = false)
                if (capturePreviews) captureRecoveryScreen("find-recovered-$name")
            } catch (failure: Throwable) {
                iterationFailure = failure
                runCatching {
                    captureRecoveryScreen("find-recovery-failure-$name")
                }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            } finally {
                runFixtureCleanup(
                    iterationFailure,
                    { runOnMainSync(activity::finishAndRemoveTask) },
                    { runOnMainSync(session::close) },
                    { awaitReadingCondition("Find recovery Activity destruction") { activity.isDestroyed } },
                    { waitForAccessibilityIdle() }
                )
            }
        }
    } catch (failure: Throwable) {
        primaryFailure = failure
        throw failure
    } finally {
        runFixtureCleanup(primaryFailure, { restoreFixtureRotation(originalRotation) })
    }
}

private fun Instrumentation.assertFindRecoveryReachable(activity: Activity, query: String, failed: Boolean = true) {
    waitForImeVisibility(activity, visible = true)
    SystemClock.sleep(300) // Wait for the keyboard and chrome to finish their layout transition.
    waitForAccessibilityIdle()
    uiAutomation.clearCache()
    val decor = activity.window.decorView
    val keyboardTop = decor.height - checkNotNull(decor.rootWindowInsets).getInsets(WindowInsets.Type.ime()).bottom
    val controls = listOfNotNull(if (failed) requireActionableText("Retry search") else null,
        waitForAccessibilityNode("retained recovery query") {
        it.isEditable && it.text?.toString() == query
    })
    for (control in controls) {
        val bounds = Rect().also(control::getBoundsInScreen)
        check(bounds.height() >= 48 * activity.resources.displayMetrics.density - 2 && bounds.bottom <= keyboardTop) {
            "Find recovery control was clipped: $bounds; keyboard begins at $keyboardTop"
        }
    }
    val source = waitForAccessibilityNode("source remains visible during failed Find") {
        it.isEditable && it.text?.toString() == "alpha beta alpha\n"
    }
    val bounds = Rect().also(source::getBoundsInScreen)
    // Keyboard heights vary; retain a full document touch target above the IME.
    val minimumDocumentHeight = 48 * activity.resources.displayMetrics.density
    check(bounds.height() >= minimumDocumentHeight &&
        bounds.top + minimumDocumentHeight <= keyboardTop
    ) {
        "Find failure covered the document above the keyboard: $bounds; keyboard begins at $keyboardTop"
    }
}

internal fun Instrumentation.captureRecoveryScreen(name: String) {
    runBlocking { repeat(2) { awaitFrame() } }
    waitForAccessibilityIdle()
    val bitmap = checkNotNull(uiAutomation.takeScreenshot())
    try {
        File(targetContext.cacheDir, "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    } finally { bitmap.recycle() }
}
