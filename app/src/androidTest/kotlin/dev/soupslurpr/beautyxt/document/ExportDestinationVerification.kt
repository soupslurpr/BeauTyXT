package dev.soupslurpr.beautyxt.document

import android.app.Instrumentation
import android.content.ClipboardManager
import android.view.accessibility.AccessibilityNodeInfo
import dev.soupslurpr.beautyxt.sharing.readSharedTextSnapshot
import dev.soupslurpr.beautyxt.transfer.TransferProtocol
import dev.soupslurpr.beautyxt.transfer.client.IsolatedTransferProcessor
import dev.soupslurpr.beautyxt.ui.editor.*
import kotlinx.coroutines.runBlocking

/** Exercises visible destinations against real captured bytes, scope, and transfer limits. */
internal fun Instrumentation.verifyExportDestinationControls() {
    val selected = "Take a break at the ridge. The return path follows the same route."
    val source = "# Ridge walk\n\n$selected\n\nUNSELECTED TAIL"
    val processor = IsolatedTransferProcessor(targetContext)
    withReadingPage(source, qrProcessor = processor) { activity, session ->
        val export = session.excerptExport
        requireActionableContentDescription("Send and export").performRequiredClick()
        awaitDestination(export)
        requireActionableText("QR code")
        requireActionableText("Copy text")
        requireActionableText("Print")
        val nfc = waitForAccessibilityNode("visible unavailable NFC destination") {
            it.text?.toString() == "Write NFC tag" && it.isVisibleToUser
        }
        var action: AccessibilityNodeInfo? = nfc
        while (action != null && !action.isClickable) action = action.parent
        check(action != null && !action.isEnabled) { "Unavailable NFC remained actionable" }
        captureRecoveryScreen("export-document-nfc-unavailable")
        requireActionableText("QR code").performRequiredClick()
        awaitDestination(export)
        check(export.destination == ExcerptDestination.Qr && export.exportScope == ExportScope.Document)
        check(export.preparedText() == source && export.prepared?.qr != null)
        requireActionableText("Show QR code").performRequiredClick()
        awaitReadingCondition("QR action did not present the captured document") {
            (session.qrShareStatus as? QrShareStatus.Ready)?.textBytes == source.toByteArray().size.toLong()
        }
        captureRecoveryScreen("export-document-qr")
        runOnMainSync { session.cancelQrShare() }
        waitForAccessibilityIdle()

        runOnMainSync { check(session.selectReading(ReadingPoint(1, 0), ReadingPoint(1, selected.length))) }
        requireActionableText("Send/export").performRequiredClick()
        awaitDestination(export)
        waitForAccessibilityNode("selection summary contains the selected text") { it.text?.toString() == selected }
        captureRecoveryScreen("export-selected-text")
        requireActionableText("Copy text").performRequiredClick()
        awaitDestination(export)
        check(export.destination == ExcerptDestination.Copy && export.exportScope == ExportScope.Selection)
        check(export.preparedText() == selected)
        requireActionableText("Copy").performRequiredClick()
        val clipboard = checkNotNull(activity.getSystemService(ClipboardManager::class.java))
        awaitReadingCondition("Copy included text outside the selected scope") {
            clipboard.primaryClip?.getItemAt(0)?.text?.toString() == selected
        }
        runOnMainSync { clipboard.clearPrimaryClip() }
        check(!session.state.hasDocumentChanges)
    }
    withReadingPage(source, nfcProcessor = processor, writeNfcEnabled = true) { _, session ->
        runOnMainSync { check(session.selectReading(ReadingPoint(1, 0), ReadingPoint(1, selected.length))) }
        requireActionableText("Send/export").performRequiredClick()
        val export = session.excerptExport
        awaitDestination(export)
        requireActionableText("Write NFC tag").performRequiredClick()
        awaitDestination(export)
        check(export.destination == ExcerptDestination.Nfc && export.exportScope == ExportScope.Selection)
        check(export.preparedText() == selected && export.prepared?.nfc != null)
        requireActionableText("Write to one tag")
        captureRecoveryScreen("export-selected-nfc-review")
        // Preparation only: writing a physical tag requires an operator-provided spare.
        check(!session.state.hasDocumentChanges)
    }
    val tooLarge = "x".repeat(TransferProtocol.MAX_QR_TEXT_BYTES.toInt() + 1)
    withReadingPage(tooLarge, initialPresentation = EditorPresentation.Text, qrProcessor = processor) { _, session ->
        requireActionableContentDescription("Send and export").performRequiredClick()
        val export = session.excerptExport
        awaitDestination(export)
        requireActionableText("QR code").performRequiredClick()
        awaitDestination(export)
        check(!export.fits && !export.canApply && export.prepared?.qr == null)
        waitForAccessibilityNode("disabled oversized QR action") { node ->
            !node.isEnabled && node.findNode { it.text?.toString() == "Show QR code" } != null
        }
        requireActionableText("Share or save").performRequiredClick()
        awaitDestination(export)
        check(export.exportScope == ExportScope.Document && export.preparedText() == tooLarge)
        requireActionableText("Share text")
        check(!session.state.hasDocumentChanges)
    }
}

private fun Instrumentation.awaitDestination(controller: ExcerptExportController) {
    awaitReadingCondition("export destination did not prepare") {
        controller.prepared != null && !controller.busy && !controller.loadingTextPreview
    }
    check(controller.message == null) { "Export failed: ${controller.message}" }
    waitForAccessibilityIdle()
}

private fun ExcerptExportController.preparedText(): String = runBlocking {
    val text = checkNotNull(prepared).text
    text.snapshot.duplicate().use { readSharedTextSnapshot(it, text.metrics.serializedByteLength) }
}
