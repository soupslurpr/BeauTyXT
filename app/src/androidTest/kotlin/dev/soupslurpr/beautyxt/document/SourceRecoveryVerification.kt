/* Exercises source-save recovery without granting access to a real destination. */
package dev.soupslurpr.beautyxt.document

import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.core.view.WindowInsetsControllerCompat
import dev.soupslurpr.beautyxt.HomeActivity
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.exporting.client.DocumentExportException
import dev.soupslurpr.beautyxt.exporting.client.DocumentExportFailure
import dev.soupslurpr.beautyxt.ui.designsystem.BeauTyXTTheme
import dev.soupslurpr.beautyxt.ui.editor.ConflictRecoverableEditorDocumentSource
import dev.soupslurpr.beautyxt.ui.editor.DocumentEditor
import dev.soupslurpr.beautyxt.ui.editor.EditorDocumentState
import dev.soupslurpr.beautyxt.ui.editor.EditorSession
import dev.soupslurpr.beautyxt.ui.editor.SaveStatus
import dev.soupslurpr.beautyxt.ui.editor.ShareStatus
import dev.soupslurpr.beautyxt.ui.editor.SourceConflictResolution
import dev.soupslurpr.beautyxt.ui.editor.SourceSaveStatus
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val RECOVERY_LOCAL_TEXT = "A draft worth keeping.\nThese are my latest edits.\n"

/** Checks announcements and explicit recovery choices against injected save failures. */
internal fun Instrumentation.verifySourceRecovery(
    capturePreviews: Boolean = false,
    landscape: Boolean = false
) {
    val resolver = targetContext.contentResolver
    val originalRotation = Settings.System.getInt(resolver, Settings.System.USER_ROTATION, 0)
    val autoRotation = Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 1) != 0
    val intent = Intent(targetContext, HomeActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    // The launcher's NOSENSOR request can undo a rotation lock during app startup.
    startActivitySync(intent)
    val rotation =
        if (landscape) UiAutomation.ROTATION_FREEZE_90 else UiAutomation.ROTATION_FREEZE_0
    check(uiAutomation.setRotation(rotation))
    runBlocking {
        withTimeout(10_000) {
            val orientation =
                if (landscape) {
                    Configuration.ORIENTATION_LANDSCAPE
                } else {
                    Configuration.ORIENTATION_PORTRAIT
                }
            while (targetContext.resources.configuration.orientation != orientation) delay(16)
        }
    }
    waitForIdleSync()
    val activity = startActivitySync(intent) as HomeActivity
    try {
        val failures = listOf(
            DocumentExportFailure.SOURCE_CONFLICT,
            DocumentExportFailure.SERVICE_UNAVAILABLE,
            DocumentExportFailure.SOURCE_UNCERTAIN
        )
        for (failure in failures) {
            val saves = AtomicInteger()
            val overwrites = AtomicInteger()
            val reloads = AtomicInteger()
            val source = object : ConflictRecoverableEditorDocumentSource {
                override fun matchesSourceUri(encodedUri: String) = false
                override fun encodedShareUri() = "content://recovery-test/document"
                override suspend fun saveRevision(
                    snapshot: EditorDocumentSnapshot,
                    expectedBytes: Long
                ) {
                    saves.incrementAndGet()
                    throw DocumentExportException(failure)
                }
                override suspend fun overwriteRevision(
                    snapshot: EditorDocumentSnapshot,
                    expectedBytes: Long
                ) {
                    overwrites.incrementAndGet()
                    error("cancelled recovery must not overwrite a source")
                }
                override fun close() = Unit
            }
            val document = RustDocument.createEmpty()
            val metrics = document.replace(0, Utf16Range(0, 0), "The original version.\n")
            val session = EditorSession(
                title = "Notes.txt",
                state = EditorDocumentState(document, initialRevision = metrics.revision),
                documentSource = source,
                editSynchronizationDelay = {}
            )
            val dark = mutableStateOf(false)
            try {
                runOnMainSync {
                    activity.setContent {
                        BeauTyXTTheme(darkTheme = dark.value) {
                            DocumentEditor(
                                session, activity::finish, closesDocumentTask = false,
                                onReloadSource = { reloads.incrementAndGet() }
                            )
                        }
                    }
                }
                awaitRecoveryCondition { session.activeDraft != null }
                runOnMainSync {
                    val draft = checkNotNull(session.activeDraft)
                    draft.textFieldState.edit { replace(0, length, RECOVERY_LOCAL_TEXT) }
                    session.observeActiveEdit(draft, draft.captureFieldValue())
                }
                awaitRecoveryCondition {
                    when (failure) {
                        DocumentExportFailure.SOURCE_CONFLICT ->
                            session.sourceSaveStatus is SourceSaveStatus.Conflict
                        DocumentExportFailure.SOURCE_UNCERTAIN ->
                            session.sourceSaveStatus is SourceSaveStatus.Uncertain
                        else -> session.sourceSaveStatus is SourceSaveStatus.Failed
                    }
                }
                for (darkTheme in if (capturePreviews) listOf(false, true) else listOf(false)) {
                    runOnMainSync {
                        dark.value = darkTheme
                        WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                            .apply {
                                isAppearanceLightStatusBars = !darkTheme
                                isAppearanceLightNavigationBars = !darkTheme
                            }
                    }
                    waitForEditorText(RECOVERY_LOCAL_TEXT)
                    SystemClock.sleep(300)
                    waitForAccessibilityIdle()
                    val titleId = when (failure) {
                        DocumentExportFailure.SOURCE_CONFLICT -> R.string.feedback_source_changed
                        DocumentExportFailure.SOURCE_UNCERTAIN -> R.string.feedback_check_original
                        else -> R.string.feedback_not_saved
                    }
                    scrollRecoveryToStart(targetContext.getString(titleId))
                    verifyRecoveryAnnouncement()
                    val name = listOf(
                        failure.name.lowercase(),
                        if (darkTheme) "dark" else "light",
                        activity.resources.configuration.fontScale.toString(),
                        if (landscape) "landscape" else "portrait"
                    ).joinToString("-")
                    if (capturePreviews) captureRecovery("$name-start")
                    if (failure == DocumentExportFailure.SOURCE_CONFLICT) {
                        val choices = listOf(
                            R.string.feedback_reload_source to SourceConflictResolution.Reload,
                            R.string.feedback_overwrite_source to SourceConflictResolution.Overwrite
                        )
                        for ((labelId, resolution) in choices) {
                            val action = revealScrollableAction(targetContext.getString(labelId))
                            action.performAction(
                                AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id
                            )
                            SystemClock.sleep(300)
                            waitForAccessibilityIdle()
                            requireNonLiveRecoveryAction(action)
                            val actionName = "$name-${resolution.name.lowercase()}"
                            if (capturePreviews) captureRecovery(actionName)
                            action.performRequiredClick()
                            awaitRecoveryCondition { session.sourceConflictResolution == resolution }
                            SystemClock.sleep(300)
                            if (capturePreviews) captureRecovery("$actionName-confirmation")
                            if (landscape && activity.resources.configuration.fontScale >= 2f) {
                                val warning = waitForAccessibilityNode("scrollable conflict warning") {
                                    it.isScrollable
                                }
                                repeat(5) {
                                    warning.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                                    SystemClock.sleep(150)
                                }
                                if (capturePreviews) {
                                    captureRecovery("$actionName-confirmation-end")
                                }
                            }
                            requireActionableText("Cancel").performRequiredClick()
                            awaitRecoveryCondition { session.sourceConflictResolution == null }
                        }
                    } else if (failure == DocumentExportFailure.SERVICE_UNAVAILABLE) {
                        val before = saves.get()
                        val retry = revealScrollableAction("Retry save")
                        requireNonLiveRecoveryAction(retry)
                        retry.performRequiredClick()
                        awaitRecoveryCondition {
                            saves.get() == before + 1 &&
                                session.sourceSaveStatus is SourceSaveStatus.Failed
                        }
                    }
                    scrollRecoveryToStart(targetContext.getString(titleId))
                    val save = revealScrollableAction("Save as new file")
                    requireNonLiveRecoveryAction(save)
                    save.performRequiredClick()
                    awaitRecoveryCondition { session.saveStatus is SaveStatus.ChoosingFormat }
                    requireActionableText("Cancel").performRequiredClick()
                    awaitRecoveryCondition { session.saveStatus == SaveStatus.Idle }
                    if (failure == DocumentExportFailure.SOURCE_UNCERTAIN) {
                        verifyShareRecovery(session)
                    }
                    runOnMainSync {
                        check(
                            session.activeDraft?.textFieldState?.text?.toString() ==
                                RECOVERY_LOCAL_TEXT
                        )
                        check(session.hasUnsavedChanges)
                        check(overwrites.get() == 0 && reloads.get() == 0)
                    }
                }
            } finally {
                runOnMainSync { session.close() }
            }
        }
    } finally {
        runOnMainSync { activity.finishAndRemoveTask() }
        check(uiAutomation.setRotation(originalRotation))
        if (autoRotation) check(uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE))
    }
}

/** Covers the failure banner shared by save, share, print, QR, and NFC operations. */
private fun Instrumentation.verifyShareRecovery(session: EditorSession) {
    runOnMainSync { check(session.requestShare()) }
    awaitRecoveryCondition { session.shareStatus is ShareStatus.Failed }
    val message = targetContext.getString(R.string.operation_share_source_failure)
    val announcement = revealScrollableNode("share recovery message", allowPartialVisibility = true) {
        it.findNode { node -> node.text?.toString() == message }
    }
    check(announcement.liveRegion == View.ACCESSIBILITY_LIVE_REGION_POLITE)
    check(announcement.findNode { it.isClickable } == null)
    val generation = (session.shareStatus as ShareStatus.Failed).generation
    val retry = revealScrollableAction(targetContext.getString(R.string.feedback_try_again))
    requireNonLiveRecoveryAction(retry)
    retry.performRequiredClick()
    awaitRecoveryCondition {
        (session.shareStatus as? ShareStatus.Failed)?.generation?.let { it > generation } == true
    }
    val dismiss = revealScrollableAction(targetContext.getString(R.string.feedback_dismiss))
    requireNonLiveRecoveryAction(dismiss)
    dismiss.performRequiredClick()
    awaitRecoveryCondition { session.shareStatus == ShareStatus.Idle }
}

/** The changing message is live; buttons must not share its announcement owner. */
private fun Instrumentation.verifyRecoveryAnnouncement() {
    val live = waitForAccessibilityNode("recovery message live region") {
        it.liveRegion == View.ACCESSIBILITY_LIVE_REGION_POLITE && !it.text.isNullOrBlank()
    }
    check(live.findNode { it.isClickable } == null) { "recovery announcement contains actions" }
}

private fun Instrumentation.scrollRecoveryToStart(title: String) {
    repeat(20) {
        uiAutomation.clearCache()
        val scroll = uiAutomation.rootInActiveWindow?.findNode { it.isScrollable }
        val heading = scroll?.findNode { it.text?.toString() == title }
        if (heading != null && heading.isVisibleToUser) {
            val viewport = Rect().also(scroll::getBoundsInScreen)
            val bounds = Rect().also(heading::getBoundsInScreen)
            if (viewport.contains(bounds)) return
        }
        if (scroll?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) != true) return
        SystemClock.sleep(150)
        waitForAccessibilityIdle()
    }
    error("recovery panel did not scroll back to its message")
}

private fun requireNonLiveRecoveryAction(action: AccessibilityNodeInfo) {
    var node: AccessibilityNodeInfo? = action
    while (node != null) {
        check(node.liveRegion == View.ACCESSIBILITY_LIVE_REGION_NONE) {
            "recovery action belongs to a live region"
        }
        node = node.parent
    }
}

private fun Instrumentation.awaitRecoveryCondition(predicate: () -> Boolean) {
    runBlocking {
        withContext(Dispatchers.Main) {
            withTimeout(10_000) { snapshotFlow(predicate).first { it } }
        }
    }
    // A retry can repeat identical text while Compose updates its generation-bound callbacks.
    SystemClock.sleep(100)
    waitForIdleSync()
    waitForAccessibilityIdle()
}

private fun Instrumentation.captureRecovery(name: String) {
    waitForAccessibilityIdle()
    val directory = File(targetContext.cacheDir, "source-recovery").apply { mkdirs() }
    val bitmap = checkNotNull(uiAutomation.takeScreenshot())
    try {
        File(directory, "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    } finally {
        bitmap.recycle()
    }
}
