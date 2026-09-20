/* Verifies document ownership, input and transient state across Android lifecycles. */
package dev.soupslurpr.beautyxt.document

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.compose.setContent
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.LifecycleOwner
import dev.soupslurpr.beautyxt.HomeActivity
import dev.soupslurpr.beautyxt.HomeDocumentActivity
import dev.soupslurpr.beautyxt.LegacyCleanup
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.ThirdPartyNoticesActivity
import dev.soupslurpr.beautyxt.createQrDocumentSessionIntent
import dev.soupslurpr.beautyxt.createSelectedDocumentSessionIntent
import dev.soupslurpr.beautyxt.createSharedDocumentSessionIntent
import dev.soupslurpr.beautyxt.document.DocumentFormat
import dev.soupslurpr.beautyxt.markdown.client.IsolatedMarkdownRenderer
import dev.soupslurpr.beautyxt.markdown.client.MarkdownRenderException
import dev.soupslurpr.beautyxt.markdown.client.MarkdownRenderFailure
import dev.soupslurpr.beautyxt.markdown.client.MarkdownRenderer
import dev.soupslurpr.beautyxt.transfer.client.IsolatedTransferProcessor
import dev.soupslurpr.beautyxt.ui.LegacyCleanupGate
import dev.soupslurpr.beautyxt.ui.designsystem.BeauTyXTTheme
import dev.soupslurpr.beautyxt.ui.editor.DocumentEditor
import dev.soupslurpr.beautyxt.ui.editor.EditorDocumentSource
import dev.soupslurpr.beautyxt.ui.editor.EditorDocumentState
import dev.soupslurpr.beautyxt.ui.editor.EditorDocumentStatus
import dev.soupslurpr.beautyxt.ui.editor.EditorPresentation
import dev.soupslurpr.beautyxt.ui.editor.EditorSession
import dev.soupslurpr.beautyxt.ui.editor.NfcTagLabelDialog
import dev.soupslurpr.beautyxt.ui.editor.NfcWriteStatus
import dev.soupslurpr.beautyxt.ui.editor.QrImageSaveStatus
import dev.soupslurpr.beautyxt.ui.editor.QrShareDialog
import dev.soupslurpr.beautyxt.ui.editor.QrShareStatus
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking

private const val TEST_ACTIVITY_TIMEOUT_MILLIS = 15_000L
private const val TEST_ACCESSIBILITY_QUIET_MILLIS = 100L
private const val TEST_ACCESSIBILITY_POLL_MILLIS = 20L
private const val TEST_ACCESSIBILITY_SCROLL_MILLIS = 350L
private const val TEST_SCROLL_DRAG_STEPS = 12
private const val TEST_SCROLL_DRAG_STEP_MILLIS = 20L
private const val TEST_SCROLL_DRAG_START_FRACTION = 0.8f
private const val TEST_SCROLL_DRAG_DISTANCE_FRACTION = 0.25f
private const val TEST_RECOVERY_LAYOUT_SETTLE_MILLIS = 500L
private const val TEST_MINIMUM_TOUCH_TARGET_DP = 48
private const val TEST_NEW_DOCUMENT_LABEL = "New document"
private const val TEST_OPEN_DOCUMENT_LABEL = "Open file"
private const val TEST_SCAN_QR_LABEL = "Scan QR code"
private const val TEST_SCANNER_TITLE = "Scan BeauTyXT code"
private const val TEST_CAMERA_PERMISSION_LABEL = "Allow camera"
private const val TEST_EDIT_FIELD_CLASS_NAME = "android.widget.EditText"
private const val TEST_SELECTION_START = 9
private const val TEST_SELECTION_END = 23
private const val TEST_RETAINED_DRAFT =
    "rotation-draft-0123456789abcdef-9876543210-fedcba"
private const val TEST_RAPID_BACK_DRAFT = "rapid-back-draft"
private const val TEST_RAPID_BACK_REPETITIONS = 16
private const val TEST_BACK_CONTENT_DESCRIPTION = "Back"
private const val TEST_UNSAVED_CHANGES_TITLE = "Unsaved changes"
private const val TEST_RECEIVED_CLOSE_TITLE = "Close without saving?"
private const val TEST_KEEP_EDITING_LABEL = "Keep editing"
private const val TEST_BACKGROUND_AUTOSAVE_NAME = "beautyxt-background-autosave.txt"
private const val TEST_BACKGROUND_AUTOSAVE_INITIAL_TEXT = "before"
private const val TEST_BACKGROUND_AUTOSAVE_EDITED_TEXT = "after background checkpoint"
private const val TEST_STAGING_PACKAGE_NAME = "dev.soupslurpr.beautyxt.staging"
private const val TEST_TOOLBAR_DRAFT = "# Toolbar check\n\nKeep the original source."
private const val TEST_HISTORY_WORD = "Composing"
private const val TEST_SHARED_UNICODE_TEXT = "😀"
private const val TEST_READING_ENTRY = "# Reading entry\n\nRead without a source detour."
private const val TEST_EDIT_RECOVERY_ORIGINAL = "Before recovery"

// End the fixture outside a word so the installed IME cannot resume composition.
private const val TEST_EDIT_RECOVERY_REPLACEMENT = "After recovery\n"

private const val TEST_NFC_LABEL = "A01"
private const val TEST_STAGING_MAIN_ACTIVITY_NAME = "dev.soupslurpr.beautyxt.MainActivity"
private const val TEST_STAGING_HOME_ACTIVITY_NAME = "dev.soupslurpr.beautyxt.HomeActivity"
private const val TEST_STAGING_BACKGROUND_AUTOSAVE_NAME =
    "beautyxt-staging-background-autosave.txt"
private const val TEST_STAGING_LARGE_BACKGROUND_AUTOSAVE_NAME =
    "beautyxt-staging-large-background-autosave.txt"
private const val TEST_STAGING_LARGE_TEXT_REPETITIONS = 4_096
private val TEST_STAGING_LARGE_BACKGROUND_AUTOSAVE_TEXT =
    "0123456789abcdef".repeat(TEST_STAGING_LARGE_TEXT_REPETITIONS)
private const val TEST_RECREATION_SOURCE_NAME = "beautyxt-recreation-source.txt"
private const val TEST_RECREATION_SOURCE_TEXT = "source identity remains in memory"
private const val TEST_MEDIA_PENDING = 1
private const val TEST_MEDIA_PUBLISHED = 0

/** Verifies responsive startup, hidden document actions, and an explicit cleanup retry. */
internal fun Instrumentation.verifyLegacyCleanupPresentation(capturePreviews: Boolean = false) {
    val release = CountDownLatch(1)
    val attempts = AtomicInteger()
    val closeRequested = AtomicBoolean()
    val cleanup = LegacyCleanup {
        if (attempts.incrementAndGet() == 1) {
            check(release.await(TEST_ACTIVITY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                "startup presentation test was not released"
            }
            throw IOException("synthetic cleanup failure")
        }
    }
    cleanup.start()
    val activity = startActivitySync(
        Intent(targetContext, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ) as HomeActivity
    try {
        runOnMainSync {
            activity.setContent {
                BeauTyXTTheme {
                    LegacyCleanupGate(cleanup, onClose = { closeRequested.set(true) }) {
                        Text("Document access is ready")
                    }
                }
            }
        }
        waitForAccessibilityNode("pending legacy cleanup") { node ->
            node.text?.toString() == "Preparing BeauTyXT"
        }
        check(
            uiAutomation.rootInActiveWindow?.findNode { node ->
                node.text?.toString() == "Document access is ready"
            } == null
        ) { "document UI appeared while legacy cleanup was pending" }
        requireActionableText("Close").performRequiredClick()
        check(closeRequested.get()) { "startup cleanup blocked the Close action" }
        if (capturePreviews) {
            captureStartupPreview("pending")
        }
        release.countDown()
        waitForAccessibilityNode("failed legacy cleanup") { node ->
            node.text?.toString() == "Couldn't prepare BeauTyXT"
        }
        check(
            uiAutomation.rootInActiveWindow?.findNode { node ->
                node.text?.toString() == "Document access is ready"
            } == null
        ) { "document UI appeared after legacy cleanup failed" }
        if (capturePreviews) {
            captureStartupPreview("failed")
        }
        requireActionableText("Retry").performRequiredClick()
        waitForAccessibilityNode("successful cleanup retry") { node ->
            node.text?.toString() == "Document access is ready"
        }
        check(attempts.get() == 2) { "cleanup retry did not run exactly once" }
    } finally {
        release.countDown()
        runOnMainSync(activity::finish)
        waitForAccessibilityIdle()
    }
}

/** Captures synthetic startup UI for local inspection during the opt-in visual phase. */
private fun Instrumentation.captureStartupPreview(state: String) {
    val bitmap = checkNotNull(uiAutomation.takeScreenshot()) { "startup screenshot unavailable" }
    try {
        File(targetContext.cacheDir, "startup-$state.webp").outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, output)) {
                "startup screenshot could not be encoded"
            }
        }
    } finally {
        bitmap.recycle()
    }
}

/** Verifies licenses remain readable and scroll entirely below their navigation controls. */
internal fun Instrumentation.verifyNoticeScrollInsets() {
    val activity = startActivitySync(
        Intent(targetContext, ThirdPartyNoticesActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    try {
        waitForAccessibilityNode("loaded license text") { node ->
            node.text?.startsWith("BeauTyXT third-party notices") == true
        }
        repeat(2) {
            val body = waitForAccessibilityNode("license scroll viewport") { it.isScrollable }
            val back = waitForAccessibilityNode("license navigation") { node ->
                node.contentDescription?.toString() == "Back"
            }
            val bodyBounds = Rect().also(body::getBoundsInScreen)
            val backBounds = Rect().also(back::getBoundsInScreen)
            check(bodyBounds.top >= backBounds.bottom) {
                "license scroll viewport overlaps its navigation controls"
            }
            check(body.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                "license text did not expose an accessible scroll action"
            }
            waitForAccessibilityIdle()
        }
        requireActionableContentDescription("Back").performRequiredClick()
        waitForAccessibilityIdle()
        check(activity.isFinishing) { "license navigation did not finish its screen" }
    } finally {
        runOnMainSync(activity::finish)
        waitForAccessibilityIdle()
    }
}

/** Verifies shared Unicode counts and rejects new unsupported offers without closing text. */
internal fun Instrumentation.verifyIncomingReviewInteractions() {
    val activity = startActivitySync(
        createSharedDocumentSessionIntent(
            targetContext,
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, TEST_SHARED_UNICODE_TEXT)
            }
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    try {
        waitForAccessibilityNode("shared Unicode count") { node ->
            node.text?.toString() == "1 Unicode character"
        }
        requireActionableText("Open").performRequiredClick()
        waitForEditorText(TEST_SHARED_UNICODE_TEXT)
        runOnMainSync {
            callActivityOnNewIntent(
                activity,
                Intent(Intent.ACTION_SEND).apply { type = "image/png" }
            )
        }
        waitForAccessibilityNode("unsupported incoming offer") { node ->
            node.text?.toString() == "Can't open this content"
        }
        check(
            uiAutomation.rootInActiveWindow?.findNode { node ->
                node.text?.toString() == "Close current"
            } == null
        ) { "unsupported incoming content offered to close the current document" }
        requireActionableText("Dismiss").performRequiredClick()
        waitForEditorText(TEST_SHARED_UNICODE_TEXT)
        requireActiveActivity(activity, "unsupported incoming content closed the current document")

        val replacementText = "Incoming content survives the unsaved-document handoff."
        runOnMainSync {
            callActivityOnNewIntent(
                activity,
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, replacementText)
                }
            )
        }
        requireActionableText("Close current").performRequiredClick()
        waitForAccessibilityNode("unsaved document before incoming content") { node ->
            node.text?.toString() == TEST_RECEIVED_CLOSE_TITLE
        }
        requireActionableText("Keep reading").performRequiredClick()
        requireActiveActivity(activity, "cancelling discard closed the incoming session")
        requireActionableText("Close current").performRequiredClick()
        requireActionableText("Close without saving").performRequiredClick()
        waitForAccessibilityNode("incoming review after discarding the previous document") { node ->
            node.text?.toString() == "Review shared text"
        }
        requireActionableText("Open").performRequiredClick()
        waitForEditorText(replacementText)
        requireActiveActivity(activity, "discarding the old document lost incoming content")

        val editedText = "$replacementText Now edited."
        check(
            waitForEditField().performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        editedText
                    )
                }
            )
        ) { "could not edit the received document" }
        waitForEditorText(editedText)
        requireActionableContentDescription(TEST_BACK_CONTENT_DESCRIPTION).performRequiredClick()
        waitForAccessibilityNode("edited received-content confirmation") { node ->
            node.text?.toString() == TEST_UNSAVED_CHANGES_TITLE
        }
        requireActionableText(TEST_KEEP_EDITING_LABEL).performRequiredClick()
        waitForEditorText(editedText)
        requireActiveActivity(activity, "keeping edits closed the received document")
    } finally {
        runOnMainSync(activity::finishAndRemoveTask)
        waitForAccessibilityIdle()
    }
}

/** Verifies history actions remain usable through the Android composing-text protocol. */
internal fun Instrumentation.verifyComposingHistoryInteractions() {
    var currentActivity: Activity? = null
    var imeVisibilityProbe: ImeVisibilityProbe? = null
    try {
        currentActivity = startActivitySync(
            Intent(targetContext, HomeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        )
        waitForAccessibilityIdle()
        val documentMonitor = addMonitor(HomeDocumentActivity::class.java.name, null, false)
        requireActionableText(TEST_NEW_DOCUMENT_LABEL).performRequiredClick()
        val activity = checkNotNull(
            waitForMonitorWithTimeout(documentMonitor, TEST_ACTIVITY_TIMEOUT_MILLIS)
        ) { "new document did not open for the composing-history test" }
        removeMonitor(documentMonitor)
        currentActivity = activity
        waitForImeVisibility(activity, visible = true)

        editThroughInputConnection(activity) { connection ->
            connection.setComposingText(TEST_HISTORY_WORD, 1)
        }
        waitForEditorText(TEST_HISTORY_WORD)
        runOnMainSync {
            imeVisibilityProbe = ImeVisibilityProbe(activity.window.decorView)
        }
        requireActionableContentDescription("Undo").performRequiredClick()
        waitForEditorText("")
        requireActionableContentDescription("Redo").performRequiredClick()
        waitForEditorText(TEST_HISTORY_WORD)
        waitForImeVisibility(activity, visible = true)

        editThroughInputConnection(activity) { connection -> connection.commitText(" ", 1) }
        waitForEditorText("$TEST_HISTORY_WORD ")
        requireActionableContentDescription("Undo").performRequiredClick()
        waitForEditorText(TEST_HISTORY_WORD)
        requireActionableContentDescription("Redo")
        editThroughInputConnection(activity) { connection ->
            connection.setComposingRegion(0, TEST_HISTORY_WORD.length)
        }
        requireActionableContentDescription("Redo").performRequiredClick()
        waitForEditorText("$TEST_HISTORY_WORD ")
        waitForImeVisibility(activity, visible = true)

        requireActionableContentDescription("Undo").performRequiredClick()
        waitForEditorText(TEST_HISTORY_WORD)
        editThroughInputConnection(activity) { connection ->
            connection.setComposingText(" branch", 1)
        }
        waitForEditorText("$TEST_HISTORY_WORD branch")
        // Await the toolbar's semantics update as well as the editor's text update.
        waitForAccessibilityNode("disabled redo after new typing") { node ->
            node.contentDescription?.toString() == "Redo" &&
                node.enabledClickableAncestor() == null
        }
        requireActionableContentDescription("Undo").performRequiredClick()
        waitForEditorText(TEST_HISTORY_WORD)
        requireActionableContentDescription("Redo").performRequiredClick()
        waitForEditorText("$TEST_HISTORY_WORD branch")
        waitForImeVisibility(activity, visible = true)
        injectHistoryShortcut(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON)
        waitForEditorText(TEST_HISTORY_WORD)
        injectHistoryShortcut(
            KeyEvent.KEYCODE_Z,
            KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON
        )
        waitForEditorText("$TEST_HISTORY_WORD branch")
        injectHistoryShortcut(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON)
        waitForEditorText(TEST_HISTORY_WORD)
        injectHistoryShortcut(KeyEvent.KEYCODE_Y, KeyEvent.META_CTRL_ON)
        waitForEditorText("$TEST_HISTORY_WORD branch")
        waitForImeVisibility(activity, visible = true)
        runOnMainSync { imeVisibilityProbe?.close() }
        checkNotNull(imeVisibilityProbe).requireStableVisibility()

        injectBackKey()
        waitForImeVisibility(activity, visible = false)
        runOnMainSync {
            imeVisibilityProbe =
                ImeVisibilityProbe(
                    root = activity.window.decorView,
                    expectedVisible = false
                )
        }
        requireActionableContentDescription("Undo").performRequiredClick()
        waitForEditorText(TEST_HISTORY_WORD)
        requireActionableContentDescription("Redo").performRequiredClick()
        waitForEditorText("$TEST_HISTORY_WORD branch")
        waitForAccessibilityIdle()
        runOnMainSync { imeVisibilityProbe.close() }
        checkNotNull(imeVisibilityProbe).requireStableVisibility()
    } finally {
        runOnMainSync { imeVisibilityProbe?.close() }
        currentActivity?.let { activity ->
            runOnMainSync(activity::finishAndRemoveTask)
            waitForAccessibilityIdle()
        }
    }
}

/** Verifies initial reading closes safely while an explicitly opened preview returns to source. */
internal fun Instrumentation.verifyInitialReadingBack() {
    val activity = startActivitySync(
        createSharedDocumentSessionIntent(
            targetContext,
            Intent(Intent.ACTION_SEND).apply {
                type = "text/markdown"
                putExtra(Intent.EXTRA_TEXT, TEST_READING_ENTRY)
            }
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    try {
        requireActionableText("Open").performRequiredClick()
        waitForAccessibilityNode("initial reading heading") { node ->
            node.text?.toString() == "Reading entry" && !node.isEditable
        }
        waitForImeVisibility(activity, visible = false)
        injectBackKey()
        waitForAccessibilityNode("unsaved reading confirmation") { node ->
            node.text?.toString() == TEST_RECEIVED_CLOSE_TITLE
        }
        requireActionableText("Keep reading").performRequiredClick()
        waitForAccessibilityNode("retained initial reading") { node ->
            node.text?.toString() == "Reading entry" && !node.isEditable
        }
        waitForImeVisibility(activity, visible = false)

        requireActionableContentDescription("Show source text").performRequiredClick()
        waitForEditorText(TEST_READING_ENTRY)
        requireActionableContentDescription("Preview Markdown").performRequiredClick()
        waitForAccessibilityNode("explicitly opened preview") { node ->
            node.text?.toString() == "Reading entry" && !node.isEditable
        }
        waitForImeVisibility(activity, visible = false)
        injectBackKey()
        waitForEditorText(TEST_READING_ENTRY)
        check(!activity.isFinishing) { "nested preview Back closed the document" }
    } finally {
        runOnMainSync(activity::finishAndRemoveTask)
        waitForAccessibilityIdle()
    }
}

/** Verifies whole-document drafts expose retry and unverified-result recovery. */
internal fun Instrumentation.verifyShortDocumentEditRecovery(capturePreviews: Boolean = false) {
    for (unverifiedResult in listOf(false, true)) {
        val nativeDocument = RustDocument.createEmpty()
        val initialMetrics = nativeDocument.replace(
            0L,
            Utf16Range(0L, 0L),
            TEST_EDIT_RECOVERY_ORIGINAL
        )
        val failNextEdit = AtomicBoolean(true)
        val document = object : EditorDocument by nativeDocument {
            override fun replace(
                expectedRevision: Long,
                range: Utf16Range,
                replacement: String
            ): DocumentMetrics {
                val fail = failNextEdit.getAndSet(false)
                if (fail && !unverifiedResult) throw IOException("synthetic edit failure")
                val metrics = nativeDocument.replace(expectedRevision, range, replacement)
                return if (fail) {
                    metrics.copy(byteLength = metrics.byteLength + 1L)
                } else {
                    metrics
                }
            }
        }
        val session = EditorSession(
            "Recovery.txt",
            EditorDocumentState(document, initialRevision = initialMetrics.revision)
        )
        val activity = startActivitySync(
            Intent(targetContext, HomeActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
        ) as HomeActivity
        try {
            runOnMainSync {
                activity.setContent {
                    BeauTyXTTheme {
                        DocumentEditor(session, activity::finish, closesDocumentTask = false)
                    }
                }
            }
            waitForEditorText(TEST_EDIT_RECOVERY_ORIGINAL)
            waitForEditField().performRequiredClick()
            waitForImeVisibility(activity, visible = true)
            editThroughInputConnection(activity) { connection ->
                connection.setSelection(0, TEST_EDIT_RECOVERY_ORIGINAL.length) &&
                    connection.commitText(TEST_EDIT_RECOVERY_REPLACEMENT, 1)
            }
            waitForAccessibilityNode("retained edit failure") { node ->
                node.text?.toString() == if (unverifiedResult) {
                    "The edit was applied, but its result could not be verified. Reload it."
                } else {
                    "Could not apply this edit"
                }
            }
            check(
                session.activeDraft?.textFieldState?.text.toString() ==
                    TEST_EDIT_RECOVERY_REPLACEMENT
            ) { "edit failure discarded the visible draft" }
            val recoveryAction = requireRecoveryActionAboveIme(
                activity,
                if (unverifiedResult) "Reload document" else "Retry changes"
            )
            if (capturePreviews) captureStartupPreview("edit-recovery-$unverifiedResult")
            recoveryAction.performRequiredClick()
            waitForAccessibilityNode("recovered document text") { node ->
                node.text?.toString() == TEST_EDIT_RECOVERY_REPLACEMENT &&
                    session.state.status == EditorDocumentStatus.Ready &&
                    session.state.editorMessage == null
            }
            val revision = checkNotNull(session.state.metrics).revision
            val committed = nativeDocument.editWindow(
                revision,
                Utf16Range(0L, 0L),
                EditWindowLimits(TEST_EDIT_RECOVERY_REPLACEMENT.length)
            )
            check(committed.text == TEST_EDIT_RECOVERY_REPLACEMENT) {
                "edit recovery did not retain the committed native text"
            }
        } finally {
            runOnMainSync {
                activity.finish()
                session.close()
            }
            waitForAccessibilityIdle()
        }
    }
}

/** Waits for recovery controls to settle entirely above the software keyboard. */
private fun Instrumentation.requireRecoveryActionAboveIme(
    activity: Activity,
    text: String
): AccessibilityNodeInfo {
    val deadline = SystemClock.uptimeMillis() + TEST_ACTIVITY_TIMEOUT_MILLIS
    val bounds = Rect()
    val previousBounds = Rect()
    var previousContentBottom = 0
    var settledSince = SystemClock.uptimeMillis()
    var minimumTouchHeight = 0
    runOnMainSync {
        minimumTouchHeight =
            (TEST_MINIMUM_TOUCH_TARGET_DP * activity.resources.displayMetrics.density).toInt()
    }
    while (SystemClock.uptimeMillis() < deadline) {
        val action = uiAutomation.rootInActiveWindow
            ?.findNode { node -> node.text?.toString() == text }
            ?.enabledClickableAncestor()
        val now = SystemClock.uptimeMillis()
        if (action != null) {
            action.getBoundsInScreen(bounds)
            var contentBottom = 0
            runOnMainSync {
                val decor = activity.window.decorView
                val bottomInsets = decor.rootWindowInsets.getInsets(
                    WindowInsets.Type.ime() or WindowInsets.Type.navigationBars()
                )
                contentBottom = decor.height - bottomInsets.bottom
            }
            val fullyVisible = bounds.bottom <= contentBottom &&
                bounds.height() >= minimumTouchHeight && !bounds.isEmpty
            if (
                !fullyVisible || bounds != previousBounds ||
                contentBottom != previousContentBottom
            ) {
                settledSince = now
                previousBounds.set(bounds)
                previousContentBottom = contentBottom
            } else if (now - settledSince >= TEST_RECOVERY_LAYOUT_SETTLE_MILLIS) {
                return action
            }
        } else {
            settledSince = now
        }
        SystemClock.sleep(TEST_ACCESSIBILITY_POLL_MILLIS)
    }
    error(
        "recovery action '$text' is obscured by the keyboard; " +
            "bounds=$bounds, content bottom=$previousContentBottom"
    )
}

/** Verifies empty and failed preview recovery preserves read-only source presentation. */
internal fun Instrumentation.verifyMarkdownRecoveryPresentation(capturePreviews: Boolean = false) {
    for (viewOnly in listOf(true, false)) {
        val failNextRender = AtomicBoolean(true)
        val renderer = IsolatedMarkdownRenderer(targetContext)
        val session = EditorSession(
            title = "Empty document.md",
            state = EditorDocumentState(RustDocument.createEmpty()),
            documentSource = if (viewOnly) PreviewRecoveryReadOnlySource() else null,
            initialPresentation = EditorPresentation.MarkdownPreview,
            markdownRenderer = MarkdownRenderer { snapshot, expectedBytes ->
                if (failNextRender.getAndSet(false)) {
                    throw MarkdownRenderException(MarkdownRenderFailure.ServiceUnavailable)
                }
                renderer.render(snapshot, expectedBytes)
            }
        )
        val activity = startActivitySync(
            Intent(targetContext, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as HomeActivity
        try {
            runOnMainSync {
                activity.setContent {
                    BeauTyXTTheme {
                        DocumentEditor(
                            session,
                            onClose = activity::finish,
                            closesDocumentTask = false
                        )
                    }
                }
            }
            waitForAccessibilityNode("failed preview") { node ->
                node.text?.toString() == "Preview unavailable"
            }
            revealScrollableAction(if (viewOnly) "View source" else "Edit")
            if (capturePreviews) captureStartupPreview("preview-failed-$viewOnly")
            revealScrollableAction("Retry").performRequiredClick()
            waitForAccessibilityNode("empty preview") { node ->
                node.text?.toString() == "Nothing to preview"
            }
            revealScrollableAction(if (viewOnly) "View source" else "Edit document")
            if (capturePreviews) captureStartupPreview("preview-empty-$viewOnly")
            revealScrollableAction(if (viewOnly) "View source" else "Edit document")
                .performRequiredClick()
            waitForAccessibilityNode("source presentation") { node ->
                node.contentDescription?.toString() == "Preview Markdown"
            }
            if (!viewOnly) waitForEditField()
            runOnMainSync {
                check(session.presentation == EditorPresentation.Text) {
                    "preview recovery did not open source presentation"
                }
                check(session.isViewOnly == viewOnly) {
                    "preview recovery changed source editability"
                }
                check((session.activeDraft == null) == viewOnly) {
                    "preview recovery opened the wrong source mode"
                }
            }
        } finally {
            runOnMainSync {
                activity.finish()
                session.close()
            }
            waitForAccessibilityIdle()
        }
    }
}

/** Scrolls constrained content until its requested action is fully exposed. */
internal fun Instrumentation.revealScrollableAction(text: String): AccessibilityNodeInfo =
    revealScrollableNode("action '$text'") { root ->
        root.findNode { node -> node.text?.toString() == text }?.enabledClickableAncestor()
    }

/** Finds a visible field or a fully exposed action using small real scroll gestures. */
internal fun Instrumentation.revealScrollableNode(
    description: String,
    allowPartialVisibility: Boolean = false,
    find: (AccessibilityNodeInfo) -> AccessibilityNodeInfo?
): AccessibilityNodeInfo {
    val deadline = SystemClock.uptimeMillis() + TEST_ACTIVITY_TIMEOUT_MILLIS
    var lastActionBounds: Rect? = null
    var lastViewportBounds: Rect? = null
    while (SystemClock.uptimeMillis() < deadline) {
        // Real scrolling can finish before cached accessibility bounds catch up.
        // Read the current hierarchy instead of repeatedly scrolling past the target.
        uiAutomation.clearCache()
        val root = uiAutomation.rootInActiveWindow
        val action = root?.let(find)
        val scroll = root?.findNode { node -> node.isScrollable && node.isVisibleToUser }
        if (action != null) {
            val bounds = Rect().also(action::getBoundsInScreen)
            val viewport = scroll?.let { Rect().also(it::getBoundsInScreen) }
            lastActionBounds = bounds
            lastViewportBounds = viewport
            if (viewport == null || viewport.contains(bounds) ||
                (
                    allowPartialVisibility && action.isVisibleToUser &&
                        Rect.intersects(viewport, bounds)
                    )
            ) {
                return action
            }
            dragScrollableViewport(viewport, forward = bounds.bottom > viewport.bottom)
        } else {
            scroll?.let { node ->
                val viewport = Rect().also(node::getBoundsInScreen)
                lastViewportBounds = viewport
                dragScrollableViewport(viewport, forward = true)
            }
        }
        SystemClock.sleep(TEST_ACCESSIBILITY_SCROLL_MILLIS)
        waitForAccessibilityIdle()
    }
    captureStartupPreview("recovery-unreachable")
    error(
        "scrollable $description is not fully reachable; " +
            "action=$lastActionBounds, viewport=$lastViewportBounds"
    )
}

/** Drags part of a viewport so a short window cannot skip over a complete action. */
private fun Instrumentation.dragScrollableViewport(viewport: Rect, forward: Boolean) {
    require(!viewport.isEmpty) { "scroll viewport must not be empty" }
    val downTime = SystemClock.uptimeMillis()
    val x = viewport.exactCenterX()
    val startFraction =
        if (forward) TEST_SCROLL_DRAG_START_FRACTION else 1f - TEST_SCROLL_DRAG_START_FRACTION
    val startY = viewport.top + viewport.height() * startFraction
    val distance = viewport.height() * TEST_SCROLL_DRAG_DISTANCE_FRACTION *
        if (forward) 1f else -1f
    for (step in 0..TEST_SCROLL_DRAG_STEPS + 1) {
        val action = when (step) {
            0 -> MotionEvent.ACTION_DOWN
            TEST_SCROLL_DRAG_STEPS + 1 -> MotionEvent.ACTION_UP
            else -> MotionEvent.ACTION_MOVE
        }
        val fraction = step.coerceAtMost(TEST_SCROLL_DRAG_STEPS).toFloat() / TEST_SCROLL_DRAG_STEPS
        val event = MotionEvent.obtain(
            downTime,
            SystemClock.uptimeMillis(),
            action,
            x,
            startY - distance * fraction,
            0
        )
        try {
            check(uiAutomation.injectInputEvent(event, true)) { "scroll drag was rejected" }
        } finally {
            event.recycle()
        }
        SystemClock.sleep(TEST_SCROLL_DRAG_STEP_MILLIS)
    }
}

/** Verifies label input and suggestions remain reachable without NFC hardware. */
internal fun Instrumentation.verifyNfcLabelDialog(capturePreview: Boolean = false) {
    val submitted = AtomicReference<String?>()
    val activity = startActivitySync(
        Intent(targetContext, HomeActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        )
    ) as HomeActivity
    try {
        runOnMainSync {
            activity.setContent {
                BeauTyXTTheme {
                    NfcTagLabelDialog(
                        status = NfcWriteStatus.Configuring(1L),
                        onConfirm = { generation, label ->
                            check(generation == 1L) { "NFC label generation changed" }
                            submitted.set(label)
                            true
                        },
                        onDismiss = activity::finish
                    )
                }
            }
        }
        val field = revealScrollableNode("label field", allowPartialVisibility = true) { root ->
            root.findNode { node ->
                node.className?.toString() == TEST_EDIT_FIELD_CLASS_NAME && node.isEditable
            }
        }
        field.performRequiredClick()
        check(
            field.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        TEST_NFC_LABEL
                    )
                }
            )
        ) { "NFC label input was not editable" }
        waitForEditorText(TEST_NFC_LABEL)
        SystemClock.sleep(TEST_RECOVERY_LAYOUT_SETTLE_MILLIS)
        if (capturePreview) captureStartupPreview("nfc-label-initial")
        revealScrollableAction("Suggest")
        requireRecoveryActionAboveIme(activity, "Suggest")
        if (capturePreview) captureStartupPreview("nfc-label")
        revealScrollableAction("Continue")
        requireRecoveryActionAboveIme(activity, "Continue").performRequiredClick()
        check(submitted.get() == TEST_NFC_LABEL) { "NFC label confirmation changed the input" }
    } finally {
        runOnMainSync(activity::finishAndRemoveTask)
        waitForAccessibilityIdle()
    }
}

/** Requires a complete scan target and reachable export controls, also in short windows. */
internal fun Instrumentation.verifyQrShareDialog(capturePreview: Boolean = false) {
    val status = RustDocument.createEmpty().use { document ->
        val metrics = document.replace(0L, Utf16Range(0L, 0L), "Coastal morning")
        document.captureSnapshot(metrics.revision).use { snapshot ->
            val grid = runBlocking {
                IsolatedTransferProcessor(targetContext).encodeQr(
                    snapshot,
                    metrics.serializedByteLength,
                    DocumentFormat.PlainText
                )
            }
            QrShareStatus.Ready(
                1L,
                grid,
                metrics.serializedByteLength,
                DocumentFormat.PlainText
            )
        }
    }
    val saveStatus = mutableStateOf<QrImageSaveStatus>(QrImageSaveStatus.Idle)
    val imageFormat = mutableStateOf(dev.soupslurpr.beautyxt.ui.editor.QrImageFormat.WebP)
    val saves = AtomicInteger()
    val dismissals = AtomicInteger()
    val dismissed = CountDownLatch(1)
    val activity = startActivitySync(
        Intent(targetContext, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    ) as HomeActivity
    try {
        runOnMainSync {
            activity.setContent {
                BeauTyXTTheme {
                    QrShareDialog(
                        status = status,
                        imageFormat = imageFormat.value,
                        onFormatChange = { imageFormat.value = it },
                        imageSaveStatus = saveStatus.value,
                        onSaveImage = {
                            saves.incrementAndGet()
                            saveStatus.value = QrImageSaveStatus.Saving
                        },
                        onDismiss = {
                            dismissals.incrementAndGet()
                            dismissed.countDown()
                        }
                    )
                }
            }
        }
        val qr = revealScrollableNode("complete QR scan target") { root ->
            root.findNode {
                it.contentDescription?.toString() == "QR code containing this document"
            }
        }
        val bounds = Rect().also(qr::getBoundsInScreen)
        check(kotlin.math.abs(bounds.width() - bounds.height()) <= 1) {
            "QR scan target is clipped: $bounds"
        }
        if (capturePreview) {
            SystemClock.sleep(TEST_RECOVERY_LAYOUT_SETTLE_MILLIS)
            captureStartupPreview("qr-complete")
        }
        revealScrollableAction("PNG").performRequiredClick()
        waitForAccessibilityIdle()
        check(imageFormat.value == dev.soupslurpr.beautyxt.ui.editor.QrImageFormat.Png)
        revealScrollableAction("WebP").performRequiredClick()
        waitForAccessibilityIdle()
        check(imageFormat.value == dev.soupslurpr.beautyxt.ui.editor.QrImageFormat.WebP)
        revealScrollableAction("Save image").performRequiredClick()
        waitForAccessibilityNode("active QR image save") { it.text?.toString() == "Saving image…" }
        // The global action queues Back asynchronously; it could arrive after the test ends Saving.
        injectBackKey()
        waitForAccessibilityIdle()
        check(saves.get() == 1 && dismissals.get() == 0) {
            "QR export lost exclusive dialog ownership"
        }
        runOnMainSync { saveStatus.value = QrImageSaveStatus.Succeeded }
        revealScrollableAction("Save another")
        revealScrollableAction("Done")
        if (capturePreview) {
            SystemClock.sleep(TEST_RECOVERY_LAYOUT_SETTLE_MILLIS)
            captureStartupPreview("qr-actions")
        }
        requireActionableText("Done").performRequiredClick()
        check(dismissed.await(TEST_ACTIVITY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            "QR dialog did not dispatch its Done action"
        }
        waitForAccessibilityIdle()
        check(dismissals.get() == 1) {
            "QR dialog did not finish exactly once: ${dismissals.get()} callbacks"
        }
    } finally {
        runOnMainSync(activity::finishAndRemoveTask)
        waitForAccessibilityIdle()
    }
}

/** Supplies a read-only source capability without opening external data. */
private class PreviewRecoveryReadOnlySource : EditorDocumentSource {
    override fun matchesSourceUri(encodedUri: String): Boolean = false

    override fun encodedShareUri(): String? = null

    override fun close() = Unit
}

/** Verifies reachable toolbar actions preserve draft content and unsaved-work protection. */
internal fun Instrumentation.verifyEditorToolbarInteractions() {
    var currentActivity: Activity? = null
    try {
        currentActivity = startActivitySync(
            Intent(targetContext, HomeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        )
        waitForAccessibilityIdle()
        val documentMonitor = addMonitor(HomeDocumentActivity::class.java.name, null, false)
        requireActionableText(TEST_NEW_DOCUMENT_LABEL).performRequiredClick()
        val activity = checkNotNull(
            waitForMonitorWithTimeout(documentMonitor, TEST_ACTIVITY_TIMEOUT_MILLIS)
        ) { "new document did not open for the toolbar test" }
        removeMonitor(documentMonitor)
        currentActivity = activity
        val editField = waitForEditField()
        editField.performRequiredClick()
        check(
            editField.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        TEST_TOOLBAR_DRAFT
                    )
                }
            )
        ) { "could not enter the toolbar draft" }
        waitForImeVisibility(activity, visible = true)
        waitForAccessibilityNode("enabled toolbar Undo") { node ->
            node.contentDescription?.toString() == "Undo" && node.isEnabled
        }
        requireActionableContentDescription("Undo").performRequiredClick()
        waitForAccessibilityNode("undone toolbar draft") { node ->
            node.className?.toString() == TEST_EDIT_FIELD_CLASS_NAME &&
                node.text.isNullOrEmpty()
        }
        requireActionableContentDescription("Redo").performRequiredClick()
        waitForAccessibilityNode("redone toolbar draft") { node ->
            node.className?.toString() == TEST_EDIT_FIELD_CLASS_NAME &&
                node.text?.toString() == TEST_TOOLBAR_DRAFT
        }
        requireActionableContentDescription("Preview Markdown").performRequiredClick()
        waitForImeVisibility(activity, visible = false)
        waitForAccessibilityNode("rendered toolbar heading") { node ->
            node.text?.toString() == "Toolbar check" && !node.isEditable
        }
        val previewRoot = checkNotNull(uiAutomation.rootInActiveWindow)
        check(
            previewRoot.findNode { node ->
                val description = node.contentDescription?.toString()
                description == "Undo" || description == "Redo"
            } == null
        ) { "reading toolbar contains inactive editing actions" }
        requireActionableText("Edit")
        requireActionableContentDescription("Document contents").performRequiredClick()
        requireActionableText("Toolbar check").performRequiredClick()
        waitForImeVisibility(activity, visible = false)
        requireActionableContentDescription("Save document").performRequiredClick()
        requireActionableText("Markdown (.md)")
        injectBackKey()
        waitForAccessibilityIdle()
        requireActionableContentDescription("Show source text").performRequiredClick()
        waitForAccessibilityNode("source restored by toolbar") { node ->
            node.className?.toString() == TEST_EDIT_FIELD_CLASS_NAME &&
                node.text?.toString() == TEST_TOOLBAR_DRAFT
        }
        requireActionableContentDescription("Preview Markdown").performRequiredClick()
        val paragraph = waitForAccessibilityNode("paragraph edit-source action") { node ->
            node.text?.toString() == "Keep the original source." &&
                node.actionList.any { action -> action.label?.toString() == "Edit source text" }
        }
        val editSource = paragraph.actionList.single { action ->
            action.label?.toString() == "Edit source text"
        }
        check(paragraph.performAction(editSource.id)) {
            "paragraph rejected its edit-source action"
        }
        waitForAccessibilityNode("paragraph source target") { node ->
            node.className?.toString() == TEST_EDIT_FIELD_CLASS_NAME &&
                node.text?.toString() == TEST_TOOLBAR_DRAFT &&
                node.textSelectionStart == TEST_TOOLBAR_DRAFT.indexOf("Keep")
        }
        requireActionableContentDescription("Save document").performRequiredClick()
        requireActionableText("Markdown (.md)")
        injectBackKey()
        waitForAccessibilityIdle()
        requireActionableContentDescription(TEST_BACK_CONTENT_DESCRIPTION).performRequiredClick()
        waitForAccessibilityNode("toolbar draft unsaved-work confirmation") { node ->
            node.text?.toString() == TEST_UNSAVED_CHANGES_TITLE
        }
        requireActionableText(TEST_KEEP_EDITING_LABEL).performRequiredClick()
        waitForAccessibilityNode("retained toolbar draft") { node ->
            node.className?.toString() == TEST_EDIT_FIELD_CLASS_NAME &&
                node.text?.toString() == TEST_TOOLBAR_DRAFT
        }
        requireActiveActivity(activity, "toolbar actions closed the unsaved document")
        requireActionableContentDescription("Send and export").performRequiredClick()
        requireActionableText("Print or PDF").performRequiredClick()
        waitForAccessibilityNode("source print explanation") { node ->
            node.text?.toString() == "Prints the text as written, including any Markdown syntax."
        }
        requireActionableText("Formatted Markdown").performRequiredClick()
        waitForAccessibilityNode("formatted print explanation") { node ->
            node.text?.toString() == activity.getString(R.string.print_formatted_description)
        }
        requireActionableText("Source text").performRequiredClick()
        waitForAccessibilityNode("restored source print explanation") { node ->
            node.text?.toString() == "Prints the text as written, including any Markdown syntax."
        }
        check(requireActionableText("Open print screen").isVisibleToUser) {
            "print action is not visible before scrolling setup"
        }
        val printOptions = uiAutomation.rootInActiveWindow?.findNode { node ->
            node.isScrollable && node.actionList.any { action ->
                action.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
        }
        printOptions?.let { options ->
            check(options.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                "print setup rejected its advertised scroll action"
            }
        }
        waitForAccessibilityIdle()
        check(requireActionableText("Open print screen").isVisibleToUser) {
            "print action scrolled away with the options"
        }
        requireActionableText("Cancel").performRequiredClick()
        requireActiveActivity(activity, "cancelling print setup closed the document")
    } finally {
        currentActivity?.let { activity ->
            runOnMainSync(activity::finishAndRemoveTask)
            waitForAccessibilityIdle()
        }
    }
}

/** Verifies rapid Back events cannot bypass a new document's unsaved-work guard. */
internal fun Instrumentation.verifyRapidBackGuardsUnsavedDraft() {
    var currentActivity: Activity? = null
    try {
        val homeActivity =
            startActivitySync(
                Intent(targetContext, HomeActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
            )
        currentActivity = homeActivity
        waitForAccessibilityIdle()

        val documentMonitor = addMonitor(HomeDocumentActivity::class.java.name, null, false)
        requireActionableText(TEST_NEW_DOCUMENT_LABEL).performRequiredClick()
        val activity =
            checkNotNull(waitForMonitorWithTimeout(documentMonitor, TEST_ACTIVITY_TIMEOUT_MILLIS)) {
                "new document did not open HomeDocumentActivity"
            }
        removeMonitor(documentMonitor)
        currentActivity = activity
        waitForAccessibilityNode("rapid-back editor title") { node ->
            node.text?.toString() == TEST_NEW_DOCUMENT_LABEL
        }
        waitForActivityWindowFocus(activity)
        val editField = waitForEditField()
        check(editField.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            "could not focus the rapid-back editor"
        }
        val setTextArguments =
            Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    TEST_RAPID_BACK_DRAFT
                )
            }
        check(
            editField.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                setTextArguments
            )
        ) {
            "could not enter the rapid-back draft"
        }
        waitForAccessibilityNode("entered rapid-back draft") { node ->
            node.className?.toString() == TEST_EDIT_FIELD_CLASS_NAME &&
                node.text?.toString() == TEST_RAPID_BACK_DRAFT
        }
        waitForImeVisibility(activity = activity, visible = true)

        injectBackKey()
        waitForImeVisibility(activity = activity, visible = false)
        requireActiveActivity(activity, "first Back event closed the document")
        waitForAccessibilityNode("draft retained after IME Back") { node ->
            node.className?.toString() == TEST_EDIT_FIELD_CLASS_NAME &&
                node.text?.toString() == TEST_RAPID_BACK_DRAFT
        }

        repeat(TEST_RAPID_BACK_REPETITIONS) { attemptIndex ->
            // Await dialog dismissal before starting the next independent Back sequence.
            waitForActivityWindowFocus(activity)
            check(waitForEditField().performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                "could not reopen the rapid-back editor IME on attempt $attemptIndex"
            }
            waitForImeVisibility(activity = activity, visible = true)
            injectBackKey()
            injectBackKey()
            waitForImeVisibility(activity = activity, visible = false)
            waitForAccessibilityIdle()
            requireActiveActivity(
                activity,
                "rapid Back attempt $attemptIndex closed the unsaved document"
            )
            val confirmationVisible =
                uiAutomation.rootInActiveWindow?.findNode { node ->
                    node.text?.toString() == TEST_UNSAVED_CHANGES_TITLE
                } != null
            if (!confirmationVisible) {
                waitForAccessibilityNode(
                    "draft retained after consumed Back $attemptIndex"
                ) { node ->
                    node.className?.toString() == TEST_EDIT_FIELD_CLASS_NAME &&
                        node.text?.toString() == TEST_RAPID_BACK_DRAFT
                }
                requireActionableContentDescription(TEST_BACK_CONTENT_DESCRIPTION)
                    .performRequiredClick()
            }

            waitForAccessibilityNode("unsaved-work confirmation $attemptIndex") { node ->
                node.text?.toString() == TEST_UNSAVED_CHANGES_TITLE
            }
            requireActionableText(TEST_KEEP_EDITING_LABEL).performRequiredClick()
            waitForAccessibilityIdle()
            waitForAccessibilityNode("draft retained after rapid Back $attemptIndex") { node ->
                node.className?.toString() == TEST_EDIT_FIELD_CLASS_NAME &&
                    node.text?.toString() == TEST_RAPID_BACK_DRAFT
            }
        }
        requireActiveActivity(activity, "keeping the rapid-back draft closed the document")
    } finally {
        val activityToFinish = currentActivity
        if (activityToFinish != null) {
            runOnMainSync(activityToFinish::finishAndRemoveTask)
            waitForAccessibilityIdle()
        }
    }
}

/** Verifies pausing a real editor checkpoints its latest draft into its provider source. */
internal fun Instrumentation.verifyBackgroundSourceCheckpoint() {
    val application = targetContext.applicationContext as Application
    val resolver = application.contentResolver
    val lifecycle = PauseLifecycleCallbacks()
    application.registerActivityLifecycleCallbacks(lifecycle)
    val document =
        checkNotNull(
            resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, TEST_BACKGROUND_AUTOSAVE_NAME)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.IS_PENDING, TEST_MEDIA_PENDING)
                }
            )
        ) {
            "could not create the background-autosave test document"
        }
    var currentActivity: Activity? = null
    var primaryFailure: Throwable? = null
    try {
        checkNotNull(resolver.openOutputStream(document, "wt")).use { output ->
            output.write(TEST_BACKGROUND_AUTOSAVE_INITIAL_TEXT.toByteArray(Charsets.UTF_8))
        }
        check(
            resolver.update(
                document,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, TEST_MEDIA_PUBLISHED)
                },
                null,
                null
            ) == 1
        ) {
            "could not publish the background-autosave test document"
        }

        val homeActivity =
            startActivitySync(
                Intent(targetContext, HomeActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
            )
        currentActivity = homeActivity
        waitForAccessibilityIdle()

        val documentMonitor = addMonitor(HomeDocumentActivity::class.java.name, null, false)
        runOnMainSync {
            homeActivity.startActivity(
                createSelectedDocumentSessionIntent(
                    context = homeActivity,
                    uri = document,
                    mimeType = "text/plain",
                    resultFlags =
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            )
        }
        val activity =
            checkNotNull(waitForMonitorWithTimeout(documentMonitor, TEST_ACTIVITY_TIMEOUT_MILLIS)) {
                "selected source did not open HomeDocumentActivity"
            }
        removeMonitor(documentMonitor)
        currentActivity = activity
        lifecycle.track(activity)
        waitForAccessibilityNode("background-autosave editor title") { node ->
            node.text?.toString() == TEST_BACKGROUND_AUTOSAVE_NAME
        }
        val editField = waitForEditField()
        check(editField.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            "could not focus the background-autosave editor"
        }
        waitForImeVisibility(activity = activity, visible = true)
        val setTextArguments =
            Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    TEST_BACKGROUND_AUTOSAVE_EDITED_TEXT
                )
            }
        check(
            editField.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                setTextArguments
            )
        ) {
            "could not enter the background-autosave draft"
        }
        check(uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)) {
            "could not background the source-backed editor"
        }
        lifecycle.awaitPause()
        resolver.awaitDocumentText(
            document = document,
            expectedText = TEST_BACKGROUND_AUTOSAVE_EDITED_TEXT
        )
    } catch (failure: Throwable) {
        primaryFailure = failure
        throw failure
    } finally {
        val activityToFinish = currentActivity
        if (activityToFinish != null) {
            try {
                runOnMainSync(activityToFinish::finishAndRemoveTask)
                waitForAccessibilityIdle()
            } catch (cleanupFailure: Throwable) {
                if (primaryFailure == null) {
                    primaryFailure = cleanupFailure
                    throw cleanupFailure
                }
                primaryFailure.addSuppressed(cleanupFailure)
            }
        }
        application.unregisterActivityLifecycleCallbacks(lifecycle)
        try {
            resolver.deleteTestMediaDocument(document)
        } catch (cleanupFailure: Throwable) {
            if (primaryFailure == null) {
                throw cleanupFailure
            }
            primaryFailure.addSuppressed(cleanupFailure)
        }
    }
}

/** Verifies the separately installed minified staging app checkpoints with its IME open. */
internal fun Instrumentation.verifyStagingBackgroundSourceCheckpoint() {
    verifyExternalStagingBackgroundSourceCheckpoint(
        displayName = TEST_STAGING_BACKGROUND_AUTOSAVE_NAME,
        sourceText = TEST_BACKGROUND_AUTOSAVE_INITIAL_TEXT,
        requireBoundedEditWindow = false
    )
}

/** Verifies minified staging checkpoints one bounded large-file edit with its IME open. */
internal fun Instrumentation.verifyStagingLargeBackgroundSourceCheckpoint() {
    verifyExternalStagingBackgroundSourceCheckpoint(
        displayName = TEST_STAGING_LARGE_BACKGROUND_AUTOSAVE_NAME,
        sourceText = TEST_STAGING_LARGE_BACKGROUND_AUTOSAVE_TEXT,
        requireBoundedEditWindow = true
    )
}

/** Exercises one external staging source through its complete provider-backed UI path. */
private fun Instrumentation.verifyExternalStagingBackgroundSourceCheckpoint(
    displayName: String,
    sourceText: String,
    requireBoundedEditWindow: Boolean
) {
    require(displayName.isNotBlank()) { "staging autosave display name must not be blank" }
    require(sourceText.isNotEmpty()) { "staging autosave source text must not be empty" }
    val application = targetContext.applicationContext as Application
    val resolver = application.contentResolver
    val document =
        checkNotNull(
            resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        displayName
                    )
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.IS_PENDING, TEST_MEDIA_PENDING)
                }
            )
        ) {
            "could not create the staging background-autosave test document"
        }
    val sourceFlags =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    var primaryFailure: Throwable? = null
    try {
        checkNotNull(resolver.openOutputStream(document, "wt")).use { output ->
            output.write(sourceText.toByteArray(Charsets.UTF_8))
        }
        check(
            resolver.update(
                document,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, TEST_MEDIA_PUBLISHED)
                },
                null,
                null
            ) == 1
        ) {
            "could not publish the staging background-autosave test document"
        }
        application.grantUriPermission(TEST_STAGING_PACKAGE_NAME, document, sourceFlags)
        application.startActivity(
            Intent(Intent.ACTION_EDIT)
                .setClassName(
                    TEST_STAGING_PACKAGE_NAME,
                    TEST_STAGING_MAIN_ACTIVITY_NAME
                ).setDataAndType(document, "text/plain")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .addFlags(sourceFlags)
        )
        waitForAccessibilityNode("staging background-autosave editor title") { node ->
            node.text?.toString() == displayName
        }
        val editField = waitForEditField()
        val editWindowText = editField.text?.toString().orEmpty()
        val expectedText =
            if (requireBoundedEditWindow) {
                check(editWindowText.isNotEmpty() && editWindowText.length < sourceText.length) {
                    "large staging source did not expose one bounded edit window"
                }
                check(sourceText.startsWith(editWindowText)) {
                    "large staging edit window did not match the source prefix"
                }
                TEST_BACKGROUND_AUTOSAVE_EDITED_TEXT + sourceText.substring(editWindowText.length)
            } else {
                check(editWindowText == sourceText) {
                    "small staging source did not expose its complete edit window"
                }
                TEST_BACKGROUND_AUTOSAVE_EDITED_TEXT
            }
        val editorBottomBeforeIme = editField.bottomInScreen()
        check(editField.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            "could not focus the staging background-autosave editor"
        }
        waitForImeResize(editorBottomBeforeIme)
        val setTextArguments =
            Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    TEST_BACKGROUND_AUTOSAVE_EDITED_TEXT
                )
            }
        check(
            editField.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                setTextArguments
            )
        ) {
            "could not enter the staging background-autosave draft"
        }
        check(uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)) {
            "could not background the staging source-backed editor"
        }
        resolver.awaitDocumentText(
            document = document,
            expectedText = expectedText
        )
    } catch (failure: Throwable) {
        primaryFailure = failure
        throw failure
    } finally {
        try {
            uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            try {
                resolver.deleteTestMediaDocument(document)
            } finally {
                application.revokeUriPermission(document, sourceFlags)
            }
        } catch (cleanupFailure: Throwable) {
            if (primaryFailure == null) {
                throw cleanupFailure
            }
            primaryFailure.addSuppressed(cleanupFailure)
        }
    }
}

/** Verifies that one unsaved draft survives Activity recreation only in memory. */
internal fun Instrumentation.verifyEditorActivityRecreation() {
    val application = targetContext.applicationContext as Application
    val lifecycle = RecreationLifecycleCallbacks()
    application.registerActivityLifecycleCallbacks(lifecycle)
    var currentActivity: Activity? = null
    try {
        val homeActivity =
            startActivitySync(
                Intent(targetContext, HomeActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
            )
        currentActivity = homeActivity
        waitForAccessibilityIdle()

        requireActionableText(TEST_OPEN_DOCUMENT_LABEL)
        val documentMonitor = addMonitor(HomeDocumentActivity::class.java.name, null, false)
        requireActionableText(TEST_NEW_DOCUMENT_LABEL).performRequiredClick()
        val activity =
            checkNotNull(waitForMonitorWithTimeout(documentMonitor, TEST_ACTIVITY_TIMEOUT_MILLIS)) {
                "new document did not open HomeDocumentActivity"
            }
        removeMonitor(documentMonitor)
        currentActivity = activity
        lifecycle.track(activity)
        waitForAccessibilityNode("new-document editor title") { node ->
            node.text?.toString() == TEST_NEW_DOCUMENT_LABEL
        }
        val editField = waitForEditField()
        val setTextArguments =
            Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    TEST_RETAINED_DRAFT
                )
            }
        check(
            editField.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                setTextArguments
            )
        ) {
            "could not enter the recreation-test draft"
        }
        val retainedEditField = waitForAccessibilityNode("entered recreation-test draft") { node ->
            node.className?.toString() == TEST_EDIT_FIELD_CLASS_NAME &&
                node.text?.toString() == TEST_RETAINED_DRAFT
        }
        check(retainedEditField.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
            "could not focus the recreation-test draft"
        }
        val selectionArguments =
            Bundle().apply {
                putInt(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,
                    TEST_SELECTION_START
                )
                putInt(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                    TEST_SELECTION_END
                )
            }
        check(
            retainedEditField.performAction(
                AccessibilityNodeInfo.ACTION_SET_SELECTION,
                selectionArguments
            )
        ) {
            "could not select the recreation-test draft"
        }
        waitForAccessibilityNode("focused recreation-test selection") { node ->
            node.className?.toString() == TEST_EDIT_FIELD_CLASS_NAME &&
                node.isFocused &&
                node.textSelectionStart == TEST_SELECTION_START &&
                node.textSelectionEnd == TEST_SELECTION_END
        }

        val initialOrientation = activity.resources.configuration.orientation
        val requestedOrientation = initialOrientation.oppositeRequestedOrientation()
        lifecycle.beginRecreation(initialOrientation)
        runOnMainSync {
            activity.requestedOrientation = requestedOrientation
        }
        currentActivity = lifecycle.awaitRecreatedActivity()
        waitForAccessibilityIdle()

        waitForAccessibilityNode("recreated retained draft") { node ->
            node.className?.toString() == TEST_EDIT_FIELD_CLASS_NAME &&
                node.text?.toString() == TEST_RETAINED_DRAFT
        }
        waitForAccessibilityNode("recreated retained selection") { node ->
            node.className?.toString() == TEST_EDIT_FIELD_CLASS_NAME &&
                node.text?.toString() == TEST_RETAINED_DRAFT &&
                node.textSelectionStart == TEST_SELECTION_START &&
                node.textSelectionEnd == TEST_SELECTION_END
        }
        waitForAccessibilityNode("recreated focused draft") { node ->
            node.className?.toString() == TEST_EDIT_FIELD_CLASS_NAME &&
                node.text?.toString() == TEST_RETAINED_DRAFT &&
                node.isFocused
        }
        requireDraftAbsentFromSavedState(lifecycle.requireSavedStateBytes())
    } finally {
        val activityToFinish = currentActivity
        if (activityToFinish != null) {
            runOnMainSync(activityToFinish::finishAndRemoveTask)
            waitForAccessibilityIdle()
        }
        application.unregisterActivityLifecycleCallbacks(lifecycle)
    }
}

/** Verifies a scanner neither unbinds nor disposes another activity's camera use cases. */
internal fun Instrumentation.verifyScannerCameraOwnership() {
    check(
        targetContext.checkSelfPermission(android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    ) { "Grant CAMERA to the debug app before running scanner camera ownership" }
    val cameraProvider = ProcessCameraProvider.getInstance(targetContext)
        .get(TEST_ACTIVITY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
    val retainedPreview = Preview.Builder().build()
    val home = startActivitySync(
        Intent(targetContext, HomeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
    )
    var scanner: Activity? = null
    try {
        runOnMainSync {
            cameraProvider.bindToLifecycle(
                home as LifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                retainedPreview
            )
        }
        scanner = startActivitySync(
            createQrDocumentSessionIntent(targetContext).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        waitForAccessibilityNode("camera binding completed") { node ->
            node.findNode { child -> child.text?.toString() == TEST_SCANNER_TITLE } != null &&
                node.findNode { child ->
                    child.className?.toString() == "android.widget.ProgressBar"
                } == null
        }
        val previewBound = AtomicBoolean()
        runOnMainSync { previewBound.set(cameraProvider.isBound(retainedPreview)) }
        check(previewBound.get()) {
            "scanner entry unbound another activity's camera use case"
        }
        runOnMainSync(scanner::finish)
        requireActionableText(TEST_NEW_DOCUMENT_LABEL)
        runOnMainSync { previewBound.set(cameraProvider.isBound(retainedPreview)) }
        check(previewBound.get()) {
            "scanner disposal unbound another activity's camera use case"
        }
    } finally {
        runOnMainSync {
            cameraProvider.unbind(retainedPreview)
            scanner?.finish()
            home.finishAndRemoveTask()
        }
        waitForAccessibilityIdle()
    }
}

/** Verifies a scanner remains open across configuration recreation with its live session. */
internal fun Instrumentation.verifyScannerActivityRecreation() {
    val application = targetContext.applicationContext as Application
    val lifecycle = RecreationLifecycleCallbacks()
    application.registerActivityLifecycleCallbacks(lifecycle)
    var currentActivity: Activity? = null
    try {
        currentActivity = startActivitySync(
            Intent(targetContext, HomeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        )
        val monitor = addMonitor(HomeDocumentActivity::class.java.name, null, false)
        val activity = try {
            requireActionableText(TEST_SCAN_QR_LABEL).performRequiredClick()
            checkNotNull(waitForMonitorWithTimeout(monitor, TEST_ACTIVITY_TIMEOUT_MILLIS)) {
                "scanner Activity did not open"
            }
        } finally {
            removeMonitor(monitor)
        }
        currentActivity = activity
        lifecycle.track(activity)
        waitForScanner()
        val initialOrientation = activity.resources.configuration.orientation
        lifecycle.beginRecreation(initialOrientation)
        runOnMainSync {
            activity.requestedOrientation = initialOrientation.oppositeRequestedOrientation()
        }
        currentActivity = lifecycle.awaitRecreatedActivity()
        waitForScanner()
    } finally {
        currentActivity?.let { activity ->
            runOnMainSync(activity::finishAndRemoveTask)
        }
        application.unregisterActivityLifecycleCallbacks(lifecycle)
    }
}

/** Verifies an ended staging session cannot resurrect a saved scanner after process death. */
internal fun Instrumentation.verifyStagingScannerProcessDeath() {
    val launcherIntent = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setClassName(TEST_STAGING_PACKAGE_NAME, TEST_STAGING_HOME_ACTIVITY_NAME)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    try {
        // Create a launcher task without reusing an earlier ADB-created root intent.
        targetContext.startActivity(
            Intent(launcherIntent).addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        )
        requireActionableText(TEST_SCAN_QR_LABEL).performRequiredClick()
        waitForScanner()
        check(readLifecycleShellOutput("pidof $TEST_STAGING_PACKAGE_NAME").isNotBlank()) {
            "staging process was not running before the scanner checkpoint"
        }
        check(uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)) {
            "could not background the staging scanner"
        }
        waitForAccessibilityIdle()
        val deadline = SystemClock.uptimeMillis() + TEST_ACTIVITY_TIMEOUT_MILLIS
        do {
            readLifecycleShellOutput("am kill $TEST_STAGING_PACKAGE_NAME")
            if (readLifecycleShellOutput("pidof $TEST_STAGING_PACKAGE_NAME").isBlank()) break
            SystemClock.sleep(TEST_ACCESSIBILITY_POLL_MILLIS)
        } while (SystemClock.uptimeMillis() < deadline)
        check(readLifecycleShellOutput("pidof $TEST_STAGING_PACKAGE_NAME").isBlank()) {
            "background staging process did not stop"
        }
        targetContext.startActivity(launcherIntent)
        waitForAccessibilityNode("ended staging scanner session") { node ->
            node.text?.toString() == "Session ended"
        }
        check(uiAutomation.rootInActiveWindow?.findNode(::isScannerNode) == null) {
            "scanner covered process-restart recovery"
        }
        requireActionableText("Return home").performRequiredClick()
        requireActionableText(TEST_NEW_DOCUMENT_LABEL)
        check(uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) {
            "could not close the staging scanner test task"
        }
        waitForAccessibilityIdle()
    } finally {
        uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }
}

/** Returns shell output for fixed lifecycle commands against the opt-in staging fixture. */
private fun Instrumentation.readLifecycleShellOutput(command: String): String =
    ParcelFileDescriptor.AutoCloseInputStream(
        uiAutomation.executeShellCommand(command)
    ).use { input ->
        input.bufferedReader().use { reader -> reader.readText().trim() }
    }

/** Waits for either the scanner or its camera-permission explanation. */
private fun Instrumentation.waitForScanner() {
    waitForAccessibilityNode("QR scanner", ::isScannerNode)
}

/** Identifies a scanner title or its camera-permission action. */
private fun isScannerNode(node: AccessibilityNodeInfo): Boolean =
    node.text?.toString() == TEST_SCANNER_TITLE ||
        node.text?.toString() == TEST_CAMERA_PERMISSION_LABEL

/** Verifies source identity survives rotation without entering Activity saved state. */
internal fun Instrumentation.verifySourceIdentityRecreationPrivacy() {
    val application = targetContext.applicationContext as Application
    val resolver = application.contentResolver
    val lifecycle = RecreationLifecycleCallbacks()
    application.registerActivityLifecycleCallbacks(lifecycle)
    val document =
        checkNotNull(
            resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, TEST_RECREATION_SOURCE_NAME)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.IS_PENDING, TEST_MEDIA_PENDING)
                }
            )
        ) {
            "could not create the source-identity recreation document"
        }
    var currentActivity: Activity? = null
    var primaryFailure: Throwable? = null
    try {
        checkNotNull(resolver.openOutputStream(document, "wt")).use { output ->
            output.write(TEST_RECREATION_SOURCE_TEXT.toByteArray(Charsets.UTF_8))
        }
        check(
            resolver.update(
                document,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, TEST_MEDIA_PUBLISHED)
                },
                null,
                null
            ) == 1
        ) {
            "could not publish the source-identity recreation document"
        }

        val homeActivity =
            startActivitySync(
                Intent(targetContext, HomeActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
            )
        currentActivity = homeActivity
        waitForAccessibilityIdle()

        val documentMonitor = addMonitor(HomeDocumentActivity::class.java.name, null, false)
        runOnMainSync {
            homeActivity.startActivity(
                createSelectedDocumentSessionIntent(
                    context = homeActivity,
                    uri = document,
                    mimeType = "text/plain",
                    resultFlags =
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            )
        }
        val activity =
            checkNotNull(waitForMonitorWithTimeout(documentMonitor, TEST_ACTIVITY_TIMEOUT_MILLIS)) {
                "source-identity document did not open HomeDocumentActivity"
            }
        removeMonitor(documentMonitor)
        currentActivity = activity
        lifecycle.track(activity)
        waitForAccessibilityNode("source-identity editor title") { node ->
            node.text?.toString() == TEST_RECREATION_SOURCE_NAME
        }

        val initialOrientation = activity.resources.configuration.orientation
        lifecycle.beginRecreation(initialOrientation)
        runOnMainSync {
            activity.requestedOrientation = initialOrientation.oppositeRequestedOrientation()
        }
        currentActivity = lifecycle.awaitRecreatedActivity()
        waitForAccessibilityIdle()
        waitForAccessibilityNode("recreated source-identity editor title") { node ->
            node.text?.toString() == TEST_RECREATION_SOURCE_NAME
        }
        requireSourceIdentityAbsentFromSavedState(
            savedStateBytes = lifecycle.requireSavedStateBytes(),
            sourceUri = document
        )
    } catch (failure: Throwable) {
        primaryFailure = failure
        throw failure
    } finally {
        val activityToFinish = currentActivity
        if (activityToFinish != null) {
            try {
                runOnMainSync(activityToFinish::finishAndRemoveTask)
                waitForAccessibilityIdle()
            } catch (cleanupFailure: Throwable) {
                if (primaryFailure == null) {
                    primaryFailure = cleanupFailure
                    throw cleanupFailure
                }
                primaryFailure.addSuppressed(cleanupFailure)
            }
        }
        application.unregisterActivityLifecycleCallbacks(lifecycle)
        try {
            check(resolver.delete(document, null, null) == 1) {
                "could not delete the source-identity recreation document"
            }
        } catch (cleanupFailure: Throwable) {
            if (primaryFailure == null) {
                throw cleanupFailure
            }
            primaryFailure.addSuppressed(cleanupFailure)
        }
    }
}

/** Tracks the exact Activity instance and saved state involved in recreation. */
private class RecreationLifecycleCallbacks : Application.ActivityLifecycleCallbacks {
    private val trackedActivity = AtomicReference<Activity?>()
    private val recreatedActivity = AtomicReference<Activity?>()
    private val savedStateBytes = AtomicReference<ByteArray?>()
    private val recreationStarted = AtomicBoolean(false)
    private val recreationCompleted = CountDownLatch(1)
    private var initialOrientation = Configuration.ORIENTATION_UNDEFINED

    /** Selects the initial Activity whose recreation is under test. */
    fun track(activity: Activity) {
        check(trackedActivity.compareAndSet(null, activity)) {
            "recreation test already tracks an Activity"
        }
    }

    /** Allows the next distinct resumed Activity to satisfy recreation. */
    fun beginRecreation(initialOrientation: Int) {
        require(
            initialOrientation == Configuration.ORIENTATION_PORTRAIT ||
                initialOrientation == Configuration.ORIENTATION_LANDSCAPE
        ) {
            "initial Activity orientation was undefined"
        }
        check(recreationStarted.compareAndSet(false, true)) {
            "Activity recreation already started"
        }
        this.initialOrientation = initialOrientation
    }

    /** Waits for the replacement Activity created by recreation. */
    fun awaitRecreatedActivity(): Activity {
        check(
            recreationCompleted.await(
                TEST_ACTIVITY_TIMEOUT_MILLIS,
                TimeUnit.MILLISECONDS
            )
        ) {
            "replacement Activity did not resume"
        }
        return checkNotNull(recreatedActivity.get()) {
            "replacement Activity was not captured"
        }
    }

    /** Returns the serialized state emitted by the original Activity. */
    fun requireSavedStateBytes(): ByteArray = checkNotNull(savedStateBytes.get()) {
        "original Activity did not emit saved instance state"
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityResumed(activity: Activity) {
        if (
            recreationStarted.get() &&
            activity !== trackedActivity.get() &&
            activity is HomeDocumentActivity &&
            activity.resources.configuration.orientation != initialOrientation &&
            recreatedActivity.compareAndSet(null, activity)
        ) {
            recreationCompleted.countDown()
        }
    }

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
        if (activity === trackedActivity.get()) {
            savedStateBytes.compareAndSet(null, outState.marshall())
        }
    }

    override fun onActivityDestroyed(activity: Activity) = Unit
}

/** Tracks the exact pause that must trigger one background source checkpoint. */
private class PauseLifecycleCallbacks : Application.ActivityLifecycleCallbacks {
    private val trackedActivity = AtomicReference<Activity?>()
    private val pauseCompleted = CountDownLatch(1)

    /** Selects the Activity whose pause must be observed. */
    fun track(activity: Activity) {
        check(trackedActivity.compareAndSet(null, activity)) {
            "background-autosave test already tracks an Activity"
        }
    }

    /** Waits for the selected Activity to leave the foreground. */
    fun awaitPause() {
        check(
            pauseCompleted.await(
                TEST_ACTIVITY_TIMEOUT_MILLIS,
                TimeUnit.MILLISECONDS
            )
        ) {
            "source-backed editor did not pause"
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) {
        if (activity === trackedActivity.get()) {
            pauseCompleted.countDown()
        }
    }

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}

/** Returns the complete serialized form of one Activity state bundle. */
private fun Bundle.marshall(): ByteArray {
    val parcel = Parcel.obtain()
    return try {
        writeToParcel(parcel, 0)
        parcel.marshall()
    } finally {
        parcel.recycle()
    }
}

/** Returns an Activity request guaranteed to oppose this resolved orientation. */
private fun Int.oppositeRequestedOrientation(): Int = when (this) {
    Configuration.ORIENTATION_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    Configuration.ORIENTATION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    else -> error("cannot rotate an Activity with undefined orientation")
}

/** Fails if the unsaved draft was serialized into Activity saved state. */
private fun requireDraftAbsentFromSavedState(savedStateBytes: ByteArray) {
    requireTextAbsentFromSavedState(
        savedStateBytes = savedStateBytes,
        text = TEST_RETAINED_DRAFT,
        description = "unsaved document text"
    )
}

/** Fails if one selected source URI was serialized into Activity saved state. */
private fun requireSourceIdentityAbsentFromSavedState(savedStateBytes: ByteArray, sourceUri: Uri) {
    requireTextAbsentFromSavedState(
        savedStateBytes = savedStateBytes,
        text = sourceUri.toString(),
        description = "selected source URI"
    )
}

/** Fails if one private text value appears in common saved-state encodings. */
private fun requireTextAbsentFromSavedState(
    savedStateBytes: ByteArray,
    text: String,
    description: String
) {
    require(text.isNotEmpty()) { "private saved-state test text must not be empty" }
    require(description.isNotBlank()) { "private saved-state description must not be blank" }
    val encodedValues =
        listOf(
            text.toByteArray(StandardCharsets.UTF_8),
            text.toByteArray(StandardCharsets.UTF_16LE),
            text.toByteArray(StandardCharsets.UTF_16BE)
        )
    check(encodedValues.none(savedStateBytes::containsSubsequence)) {
        "$description was serialized into Activity saved state"
    }
}

/** Returns whether this byte array contains one exact contiguous sequence. */
private fun ByteArray.containsSubsequence(sequence: ByteArray): Boolean {
    require(sequence.isNotEmpty()) { "test byte sequence must not be empty" }
    if (sequence.size > size) {
        return false
    }
    val lastStart = size - sequence.size
    for (start in 0..lastStart) {
        var offset = 0
        while (offset < sequence.size && this[start + offset] == sequence[offset]) {
            offset += 1
        }
        if (offset == sequence.size) {
            return true
        }
    }
    return false
}

/** Deletes one test MediaStore document and waits for asynchronous row cleanup. */
private fun ContentResolver.deleteTestMediaDocument(document: Uri) {
    require(
        document.scheme == ContentResolver.SCHEME_CONTENT &&
            document.authority == MediaStore.AUTHORITY &&
            document.lastPathSegment?.toLongOrNull()?.let { id -> id >= 0L } == true
    ) {
        "test cleanup requires one MediaStore item URI"
    }
    delete(document, null, null)
    val deadline = SystemClock.uptimeMillis() + TEST_ACTIVITY_TIMEOUT_MILLIS
    while (SystemClock.uptimeMillis() < deadline) {
        val absent =
            checkNotNull(
                query(document, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ) {
                "test document cleanup returned no verification cursor"
            }.use { cursor -> !cursor.moveToFirst() }
        if (absent) return
        SystemClock.sleep(TEST_ACCESSIBILITY_POLL_MILLIS)
    }
    error("test document remains visible after deletion")
}

/** Waits until one provider document contains the exact expected text. */
private fun ContentResolver.awaitDocumentText(document: Uri, expectedText: String) {
    require(expectedText.isNotEmpty()) { "expected document text must not be empty" }
    val expectedBytes = expectedText.toByteArray(Charsets.UTF_8)
    val deadline = SystemClock.uptimeMillis() + TEST_ACTIVITY_TIMEOUT_MILLIS
    var observedBytes = ByteArray(0)
    while (SystemClock.uptimeMillis() < deadline) {
        observedBytes =
            checkNotNull(openInputStream(document)) {
                "background-autosave provider returned no input stream"
            }.use { input ->
                input.readBytes()
            }
        if (observedBytes.contentEquals(expectedBytes)) {
            return
        }
        SystemClock.sleep(TEST_ACCESSIBILITY_POLL_MILLIS)
    }
    error(
        "background checkpoint did not save the expected text; " +
            "observed ${observedBytes.size} bytes"
    )
}

/** Waits until the accessibility tree stops changing. */
internal fun Instrumentation.waitForAccessibilityIdle() {
    uiAutomation.waitForIdle(
        TEST_ACCESSIBILITY_QUIET_MILLIS,
        TEST_ACTIVITY_TIMEOUT_MILLIS
    )
}

/** Injects one complete Back key without waiting for Compose to become idle. */
private fun Instrumentation.injectBackKey() {
    val downTime = SystemClock.uptimeMillis()
    check(
        uiAutomation.injectInputEvent(
            KeyEvent(
                downTime,
                downTime,
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_BACK,
                0
            ),
            true
        )
    ) {
        "could not inject Back key down"
    }
    check(
        uiAutomation.injectInputEvent(
            KeyEvent(
                downTime,
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_BACK,
                0
            ),
            true
        )
    ) {
        "could not inject Back key up"
    }
}

/** Injects one complete hardware-keyboard history shortcut into the focused test editor. */
private fun Instrumentation.injectHistoryShortcut(keyCode: Int, modifiers: Int) {
    require(keyCode == KeyEvent.KEYCODE_Z || keyCode == KeyEvent.KEYCODE_Y) {
        "test history shortcut must be Z or Y"
    }
    val downTime = SystemClock.uptimeMillis()
    for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
        check(
            uiAutomation.injectInputEvent(
                KeyEvent(downTime, SystemClock.uptimeMillis(), action, keyCode, 0, modifiers),
                true
            )
        ) { "could not inject test history shortcut" }
    }
}

/** Waits until the editor window can receive input after an Activity or dialog transition. */
private fun Instrumentation.waitForActivityWindowFocus(activity: Activity) {
    val deadline = SystemClock.uptimeMillis() + TEST_ACTIVITY_TIMEOUT_MILLIS
    val focused = AtomicBoolean()
    while (SystemClock.uptimeMillis() < deadline) {
        runOnMainSync { focused.set(activity.hasWindowFocus()) }
        if (focused.get()) return
        SystemClock.sleep(TEST_ACCESSIBILITY_POLL_MILLIS)
    }
    error("editor window did not regain input focus")
}

/** Waits until the selected Activity reports the expected IME visibility. */
private fun Instrumentation.waitForImeVisibility(activity: Activity, visible: Boolean) {
    val deadline = SystemClock.uptimeMillis() + TEST_ACTIVITY_TIMEOUT_MILLIS
    val observedVisibility = AtomicBoolean()
    while (SystemClock.uptimeMillis() < deadline) {
        runOnMainSync {
            observedVisibility.set(
                activity.window.decorView.rootWindowInsets
                    ?.isVisible(WindowInsets.Type.ime()) == true
            )
        }
        if (observedVisibility.get() == visible) {
            return
        }
        SystemClock.sleep(TEST_ACCESSIBILITY_POLL_MILLIS)
    }
    val caller = Throwable().stackTrace.firstOrNull { it.methodName != "waitForImeVisibility" }
    error("expected IME to become ${if (visible) "visible" else "hidden"} at $caller")
}

/** Waits until the external editor viewport shrinks for its visible IME. */
private fun Instrumentation.waitForImeResize(editorBottomBeforeIme: Int) {
    require(editorBottomBeforeIme > 0) { "editor bottom before IME must be positive" }
    waitForAccessibilityNode("editor resized for external IME") { node ->
        node.className?.toString() == TEST_EDIT_FIELD_CLASS_NAME &&
            node.isEditable &&
            node.bottomInScreen() < editorBottomBeforeIme
    }
}

/** Returns one accessibility node's lower screen edge. */
private fun AccessibilityNodeInfo.bottomInScreen(): Int {
    val bounds = Rect()
    getBoundsInScreen(bounds)
    return bounds.bottom
}

/** Fails when one document Activity has begun or completed an unexpected close. */
private fun requireActiveActivity(activity: Activity, message: String) {
    require(message.isNotBlank()) { "activity failure message must not be blank" }
    check(!activity.isFinishing && !activity.isDestroyed) { message }
}

/** Returns one exact text node with a clickable ancestor. */
internal fun Instrumentation.requireActionableText(text: String): AccessibilityNodeInfo {
    val textNode = waitForAccessibilityNode("actionable text '$text'") { node ->
        node.text?.toString() == text && node.enabledClickableAncestor() != null
    }
    return checkNotNull(textNode.enabledClickableAncestor()) {
        "accessibility text '$text' lost its enabled clickable ancestor"
    }
}

/** Returns one exact content-description node with a clickable ancestor. */
internal fun Instrumentation.requireActionableContentDescription(
    description: String
): AccessibilityNodeInfo {
    require(description.isNotBlank()) { "actionable description must not be blank" }
    val descriptionNode =
        waitForAccessibilityNode("actionable description '$description'") { node ->
            node.contentDescription?.toString() == description &&
                node.enabledClickableAncestor() != null
        }
    return checkNotNull(descriptionNode.enabledClickableAncestor()) {
        "accessibility description '$description' lost its enabled clickable ancestor"
    }
}

/** Returns a currently visible, enabled click target for this node. */
private fun AccessibilityNodeInfo.enabledClickableAncestor(): AccessibilityNodeInfo? {
    var actionableNode: AccessibilityNodeInfo? = this
    while (actionableNode != null && !actionableNode.isClickable) {
        actionableNode = actionableNode.parent
    }
    return actionableNode?.takeIf { node -> node.isEnabled && node.isVisibleToUser }
}

/** Returns the caller of an assertion helper for useful test failure reports. */
private fun assertionCaller(): StackTraceElement? = Throwable().stackTrace.firstOrNull { frame ->
    frame.methodName != "assertionCaller" && frame.methodName != "performRequiredClick"
}

/** Performs one required accessibility click. */
internal fun AccessibilityNodeInfo.performRequiredClick() {
    check(performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
        "accessibility node '${text ?: contentDescription}' rejected a click at ${assertionCaller()}"
    }
}

/** Sends one IME command through the current editor's Android input connection. */
private fun Instrumentation.editThroughInputConnection(
    activity: Activity,
    edit: (InputConnection) -> Boolean
) {
    runOnMainSync {
        val editor = checkNotNull(activity.window.decorView.findTextEditorView()) {
            "no active Android text-editor view"
        }
        val connection = checkNotNull(editor.onCreateInputConnection(EditorInfo())) {
            "active editor did not expose an input connection"
        }
        check(edit(connection)) { "input connection rejected the test edit" }
    }
    waitForAccessibilityIdle()
}

/** Finds the active Android view which owns the Compose input connection. */
internal fun View.findTextEditorView(): View? {
    if (onCheckIsTextEditor()) {
        return this
    }
    if (this is ViewGroup) {
        for (childIndex in 0 until childCount) {
            getChildAt(childIndex).findTextEditorView()?.let { editor -> return editor }
        }
    }
    return null
}

/** Waits for the exact visible source text after an input or history command. */
internal fun Instrumentation.waitForEditorText(text: String) {
    waitForAccessibilityNode("source text after an input or history command") { node ->
        node.className?.toString() == TEST_EDIT_FIELD_CLASS_NAME &&
            node.text?.toString().orEmpty() == text
    }
}

/** Waits for the active Compose text field. */
internal fun Instrumentation.waitForEditField(): AccessibilityNodeInfo =
    waitForAccessibilityNode("editable text field") { node ->
        node.className?.toString() == TEST_EDIT_FIELD_CLASS_NAME && node.isEditable
    }

/** Polls the active accessibility tree for one matching node. */
internal fun Instrumentation.waitForAccessibilityNode(
    description: String,
    predicate: (AccessibilityNodeInfo) -> Boolean
): AccessibilityNodeInfo {
    require(description.isNotBlank()) { "accessibility node description must not be blank" }
    val deadline = SystemClock.uptimeMillis() + TEST_ACTIVITY_TIMEOUT_MILLIS
    while (SystemClock.uptimeMillis() < deadline) {
        val root = uiAutomation.rootInActiveWindow
        if (root != null) {
            val match = root.findNode(predicate)
            if (match != null) {
                return match
            }
        }
        SystemClock.sleep(TEST_ACCESSIBILITY_POLL_MILLIS)
    }
    val editField = uiAutomation.rootInActiveWindow?.findNode { node ->
        node.className?.toString() == TEST_EDIT_FIELD_CLASS_NAME && node.isEditable
    }
    val editFieldState =
        editField?.let { node ->
            "; edit field length=${node.text?.length ?: 0}, focused=${node.isFocused}, " +
                "selection=${node.textSelectionStart}..${node.textSelectionEnd}"
        }.orEmpty()
    error(
        "accessibility node '$description' did not appear before the timeout$editFieldState"
    )
}

/** Searches one accessibility subtree without recursion. */
internal fun AccessibilityNodeInfo.findNode(
    predicate: (AccessibilityNodeInfo) -> Boolean
): AccessibilityNodeInfo? {
    val pending = ArrayDeque<AccessibilityNodeInfo>()
    pending.add(this)
    while (pending.isNotEmpty()) {
        val node = pending.removeFirst()
        if (predicate(node)) {
            return node
        }
        repeat(node.childCount) { childIndex ->
            node.getChild(childIndex)?.let(pending::addLast)
        }
    }
    return null
}
