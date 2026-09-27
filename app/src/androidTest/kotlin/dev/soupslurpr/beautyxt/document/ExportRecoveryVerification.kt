package dev.soupslurpr.beautyxt.document

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.exporting.client.StatelessExportTestSinks
import dev.soupslurpr.beautyxt.ui.UiText
import dev.soupslurpr.beautyxt.ui.editor.EditorPresentation
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Drives the summary through picker/share cancellation, an interrupted write, failure, and retry. */
internal fun Instrumentation.verifyExportRecoveryControls() {
    val paragraph = "Keep this source unchanged while saving a copy.\n\n"
    // Exceeds the failing sink's 64 KiB prefix and the pipe buffer, so failure happens during writing.
    val original = "# Export recovery\n\n" + paragraph.repeat(8192)
    val bytes = original.toByteArray()
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    val authority = context.packageName
    val resolver = targetContext.contentResolver
    val token = UUID.randomUUID().toString().replace("-", "")
    fun awaitSink(predicate: (dev.soupslurpr.beautyxt.exporting.client.ExportSinkStatus) -> Boolean) = runBlocking {
        withTimeout(10_000) {
            while (true) {
                val status = StatelessExportTestSinks.status(resolver, authority, token)
                if (predicate(status)) return@withTimeout status
                delay(10)
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
    }
    fun pickDestination(uri: Uri?) {
        val launched = AtomicBoolean(false)
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CREATE_DOCUMENT) return null
                launched.set(true)
                return Instrumentation.ActivityResult(if (uri == null) Activity.RESULT_CANCELED else Activity.RESULT_OK,
                    uri?.let { Intent().setData(it).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) })
            }
        }
        addMonitor(monitor)
        try {
            clickRecoveryText("Save a copy")
            awaitReadingCondition("Save a copy did not launch Android's destination picker") { launched.get() }
            waitForAccessibilityIdle()
        } finally { removeMonitor(monitor) }
    }

    withReadingPage(original, initialPresentation = EditorPresentation.Text) { _, session ->
        val export = session.excerptExport
        try {
            StatelessExportTestSinks.reset(resolver, authority, token)
            requireActionableContentDescription("Send and export").performRequiredClick()
            awaitReadingCondition("whole-document summary did not prepare") { export.prepared != null && !export.busy }
            clickRecoveryText("Send as file")
            awaitReadingCondition("whole-document file sharing did not prepare") { export.canApply && export.shareAsFile }
            val frozen = checkNotNull(export.prepared)
            pickDestination(null)
            awaitReadingCondition("canceling the picker stranded the export") { !export.handingOff && export.canApply }
            check(export.prepared === frozen && export.message == null)

            clickRecoveryText("Share file")
            waitForAccessibilityNode("Android share chooser") { it.packageName?.toString() != targetContext.packageName }
            check(uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            awaitReadingCondition("canceling Share did not return to the same export") {
                uiAutomation.clearCache()
                uiAutomation.rootInActiveWindow?.packageName?.toString() == targetContext.packageName && export.canApply
            }
            check(export.prepared === frozen)

            pickDestination(StatelessExportTestSinks.blocked(authority, token))
            awaitSink { it.paused }
            awaitReadingCondition("blocked save did not expose cancellation") { export.canCancelSave }
            captureRecoveryScreen("export-saving")
            clickRecoveryText("Cancel save")
            awaitReadingCondition("Cancel save did not restore export actions") { !export.busy && !export.handingOff && export.canApply }
            check(export.message == UiText.Resource(R.string.excerpt_save_uncertain) && export.prepared === frozen)
            StatelessExportTestSinks.releasePaused(resolver, authority, token)
            check(awaitSink { it.terminal }.let { it.hasError && it.byteCount < bytes.size })
            captureRecoveryScreen("export-interrupted")

            StatelessExportTestSinks.reset(resolver, authority, token)
            pickDestination(StatelessExportTestSinks.failedAfterPrefix(authority, token))
            check(awaitSink { it.terminal }.let { it.hasError && it.byteCount in 1 until bytes.size }) {
                "The failing destination did not stop during the write"
            }
            awaitReadingCondition("provider failure did not return to a retryable summary") {
                !export.busy && !export.handingOff && export.canApply &&
                    export.message == UiText.Resource(R.string.excerpt_save_uncertain)
            }
            check(export.prepared === frozen) { "Provider failure replaced the prepared output" }

            StatelessExportTestSinks.reset(resolver, authority, token)
            pickDestination(StatelessExportTestSinks.normal(authority, token))
            awaitReadingCondition("retry did not report a completed save") {
                export.message == UiText.Resource(R.string.excerpt_saved) && export.canApply
            }
            check(awaitSink { it.terminal }.let { !it.hasError && it.byteCount == bytes.size.toLong() && it.sha256Hex == digest }) {
                "Retry did not save the exact frozen bytes"
            }
            check(export.prepared === frozen && !session.state.hasDocumentChanges)
            check(!session.hasDocumentSource) { "Saving a copy attached an autosave source" }
            // This fixture has no dirty revision, but its document still has no source file.
            // The copy confirmation must not mistake that clean revision for a saved session.
            check(export.savedCopyReminder == UiText.Resource(R.string.export_copy_unsaved_document))
            waitForAccessibilityNode("copy confirmation explains the unsaved document") {
                it.text?.toString() == targetContext.getString(R.string.export_copy_unsaved_document)
            }
            captureRecoveryScreen("export-retried")
        } catch (failure: Throwable) {
            runCatching { captureRecoveryScreen("export-recovery-failure") }
            throw AssertionError("Export recovery: ${failure.stackTraceToString()}", failure)
        } finally {
            runOnMainSync { export.close() }
            StatelessExportTestSinks.releasePaused(resolver, authority, token)
            StatelessExportTestSinks.reset(resolver, authority, token)
        }
    }
}

/** Scrolls recovery actions into view without assuming a fixed sheet or font height. */
private fun Instrumentation.clickRecoveryText(text: String) {
    val deadline = SystemClock.uptimeMillis() + 10_000
    while (SystemClock.uptimeMillis() < deadline) {
        waitForAccessibilityIdle()
        uiAutomation.clearCache()
        val root = uiAutomation.rootInActiveWindow
        var node = root?.findNode { it.text?.toString() == text && it.isVisibleToUser }
        while (node != null && !node.isClickable) node = node.parent
        if (node?.isEnabled == true && node.isVisibleToUser && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        root?.findNode { it.isVisibleToUser && it.isScrollable && it.actionList.any { action ->
            action.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        SystemClock.sleep(50)
    }
    error("Recovery action '$text' was not reachable")
}
