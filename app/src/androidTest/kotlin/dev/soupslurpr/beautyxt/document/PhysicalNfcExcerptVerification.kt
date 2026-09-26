package dev.soupslurpr.beautyxt.document

import android.app.Instrumentation
import android.nfc.NfcAdapter
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import dev.soupslurpr.beautyxt.transfer.client.IsolatedTransferProcessor
import dev.soupslurpr.beautyxt.transfer.client.ReceivedTransferText
import dev.soupslurpr.beautyxt.ui.editor.*
import dev.soupslurpr.beautyxt.ui.designsystem.BeauTyXTTheme
import dev.soupslurpr.beautyxt.ui.transfer.NfcReadScreen
import dev.soupslurpr.beautyxt.ui.transfer.NfcWriteScreen
import java.util.concurrent.atomic.AtomicReference

/** Opt-in only: requires an operator-provided writable spare tag and two deliberate taps. */
internal fun Instrumentation.verifyPhysicalNfcExcerpt() {
    val adapter = checkNotNull(NfcAdapter.getDefaultAdapter(targetContext)) { "NFC hardware is required" }
    check(adapter.isEnabled) { "Enable NFC before starting the physical test" }
    val selected = "BeauTyXT NFC excerpt test."
    val processor = IsolatedTransferProcessor(targetContext)
    fun waitForTap(stage: String, complete: () -> Boolean) {
        sendStatus(2, Bundle().apply { putString("stream", stage) })
        val deadline = SystemClock.uptimeMillis() + 300_000
        while (!complete()) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for the physical tag: $stage" }
            SystemClock.sleep(100)
        }
    }
    withReadingPage("$selected\nPRIVATE UNSELECTED TAIL", initialPresentation = EditorPresentation.Text,
        nfcProcessor = processor) { activity, session ->
        val export = session.excerptExport
        runOnMainSync {
            check(session.selectSource(0, selected.length.toLong()))
            export.open(activity, "Synthetic NFC test.txt")
        }
        awaitReadingCondition("NFC excerpt capture did not prepare") { export.canApply }
        runOnMainSync {
            export.selectDestination(ExcerptDestination.Nfc)
            export.updateTagLabel("TEST")
        }
        awaitReadingCondition("NFC excerpt envelope did not prepare") { export.canApply }
        runOnMainSync { export.apply(activity) }
        awaitReadingCondition("NFC excerpt did not reach the writer") { session.nfcWriteStatus is NfcWriteStatus.Ready }
        val ready = session.nfcWriteStatus as NfcWriteStatus.Ready
        check(ready.textBytes == selected.toByteArray().size.toLong())
        runOnMainSync {
            activity.setContent { BeauTyXTTheme {
                NfcWriteScreen(adapter, ready, { session.completeNfcWrite(it) }, session::cancelNfcWrite)
            } }
        }
        waitForTap("NFC WRITE READY: tap the spare tag to write the synthetic excerpt.") {
            session.nfcWriteStatus is NfcWriteStatus.Succeeded
        }
        val received = AtomicReference<ReceivedTransferText?>()
        runOnMainSync {
            activity.setContent { BeauTyXTTheme {
                NfcReadScreen(adapter, processor, received::set, {})
            } }
        }
        waitForTap("NFC WRITE VERIFIED; READ READY: remove the tag, then tap again.") { received.get() != null }
        check(received.get()?.text == selected) { "Physical NFC read included unexpected content" }
        check(received.get()?.format == DocumentFormat.PlainText)
        check(received.get()?.nfcMetadata?.tagLabel == "TEST")
    }
}
