package dev.soupslurpr.beautyxt.document

import android.accessibilityservice.AccessibilityService
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.os.Process
import android.view.accessibility.AccessibilityNodeInfo
import dev.soupslurpr.beautyxt.ipc.SealedInput
import dev.soupslurpr.beautyxt.sharing.ExcerptShares
import dev.soupslurpr.beautyxt.ui.editor.EditorPresentation
import dev.soupslurpr.beautyxt.ui.editor.ExcerptFormat
import java.security.MessageDigest

/** Exercises cancellation, actual grants to another UID, delayed reads, and revocation. */
internal fun Instrumentation.verifyExcerptSharing() {
    val selected = "Only this synthetic excerpt is shared."
    fun click(text: String) = awaitReadingCondition("could not click '$text'") {
        uiAutomation.clearCache()
        uiAutomation.rootInActiveWindow?.findNode {
            it.isClickable && it.isEnabled && it.isVisibleToUser &&
                it.findNode { child -> child.text?.toString() == text } != null
        }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }
    fun shareAction() {
        waitForAccessibilityNode("share excerpt action") { node ->
            node.isEnabled && node.isClickable && !node.isCheckable &&
                node.findNode { it.text?.toString() in setOf("Share text", "Share file", "Share PDF") } != null
        }.performRequiredClick()
    }
    fun openChooser() {
        shareAction()
        waitForAccessibilityNode("Android share chooser") { it.packageName?.toString() != targetContext.packageName }
    }
    fun receiverReady(recreated: Boolean = false) {
        val node = waitForAccessibilityNode("separate receiver ready") {
            it.text?.toString()?.let { text -> text.startsWith("Receiver ready; UID ") &&
                text.endsWith("instance ${if (recreated) 1 else 0}") } == true
        }
        check(node.text.toString().substringAfter("UID ").substringBefore(';').toInt() != Process.myUid())
    }
    fun assertBytes(bytes: ByteArray, detail: String) {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        waitForAccessibilityNode("exact excerpt bytes in separate receiver") {
            val text = it.text?.toString().orEmpty()
            "Read ${bytes.size} bytes; SHA-256 $digest;" in text && detail in text
        }
    }
    withReadingPage("$selected\nPRIVATE TAIL", initialPresentation = EditorPresentation.Text) { activity, session ->
        val export = session.excerptExport
        runOnMainSync {
            check(session.selectSource(0, selected.length.toLong()))
            export.open(activity, "Sharing test.txt")
        }
        awaitReadingCondition("sharing excerpt did not prepare") { export.canApply }
        runOnMainSync { export.updateShareAsFile(true) }
        awaitReadingCondition("file sharing did not prepare") { export.canApply }
        repeat(6) {
            runOnMainSync { export.apply(activity) { throw android.content.ActivityNotFoundException() } }
            awaitReadingCondition("failed share launch did not recover") {
                export.canApply && export.message == dev.soupslurpr.beautyxt.ui.UiText.Resource(
                    dev.soupslurpr.beautyxt.R.string.selection_output_failed)
            }
        }
        repeat(6) { attempt ->
            openChooser()
            awaitReadingCondition("share chooser did not open on attempt ${attempt + 1}: ${export.message}") {
                uiAutomation.clearCache()
                uiAutomation.rootInActiveWindow?.packageName?.toString()?.let {
                    it != targetContext.packageName
                } == true
            }
            check(uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            awaitReadingCondition("share cancellation did not return to the editor") {
                uiAutomation.clearCache()
                uiAutomation.rootInActiveWindow?.packageName?.toString() == targetContext.packageName && export.canApply
            }
            check(export.message != dev.soupslurpr.beautyxt.ui.UiText.Resource(
                dev.soupslurpr.beautyxt.R.string.selection_output_failed
            )) { "Canceled sharing exhausted its resources on attempt ${attempt + 1}" }
        }

        // A helper holding the fixture permission must still need the exact URI read grant.
        SealedInput.fromBytes(selected.toByteArray(), 1024).use { input ->
            ExcerptShares.retain(activity, "Ungrant.txt", "text/plain", input.duplicate()).use { lease ->
                runOnMainSync {
                    // ACTION_SEND migrates EXTRA_STREAM into a granted ClipData automatically.
                    activity.startActivity(Intent("dev.soupslurpr.beautyxt.TEST_WITHOUT_GRANT")
                        .setComponent(ComponentName("dev.soupslurpr.beautyxt.debug.test.providers",
                            "dev.soupslurpr.beautyxt.testing.ExcerptReceiverActivity"))
                        .setType("text/plain").putExtra(Intent.EXTRA_STREAM, lease.uri))
                }
                receiverReady()
                click("Read excerpt now")
                waitForAccessibilityNode("missing URI grant rejected") { it.text?.toString() == "Excerpt read denied" }
                click("Finish excerpt receiver")
            }
        }

        openChooser()
        click("Excerpt test receiver")
        receiverReady()
        // Recreating a receiving activity must not revoke its granted, unopened file.
        click("Recreate excerpt receiver")
        receiverReady(recreated = true)
        click("Read excerpt now")
        assertBytes(selected.toByteArray(), "read-only")
        click("Keep excerpt handle open")
        waitForAccessibilityNode("retained receiver descriptor") { it.text?.toString() == "Excerpt handle opened" }
        runOnMainSync { export.close() }
        click("Read excerpt now")
        waitForAccessibilityNode("revoked grant rejects future opens") { it.text?.toString() == "Excerpt read denied" }
        click("Read held excerpt handle")
        assertBytes(selected.toByteArray(), "held")
        click("Finish excerpt receiver")
    }

    withReadingPage("$selected\nPRIVATE TAIL", initialPresentation = EditorPresentation.Text) { activity, session ->
        val export = session.excerptExport
        runOnMainSync {
            check(session.selectSource(0, selected.length.toLong()))
            export.open(activity, "Selected PDF.txt")
        }
        awaitReadingCondition("PDF sharing capture did not prepare") { export.canApply }
        runOnMainSync { export.selectFormat(ExcerptFormat.Pdf) }
        awaitReadingCondition("PDF sharing did not prepare") { export.canApply && export.prepared?.pdf != null }
        val expected = checkNotNull(export.prepared?.pdf).openReadOnly(activity).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { reader -> reader.readBytes() }
        }
        openChooser()
        click("Excerpt test receiver")
        receiverReady()
        click("Read excerpt now")
        assertBytes(expected, "application/pdf")
        click("Finish excerpt receiver")
        awaitReadingCondition("completed PDF sharing did not release chooser state") { export.canApply }
        repeat(3) {
            openChooser()
            click("Excerpt test receiver")
            receiverReady()
            click("Finish excerpt receiver")
            awaitReadingCondition("completed sharing stayed busy") { export.canApply }
        }
        shareAction()
        awaitReadingCondition("share capacity has no recovery") { export.canEndPreviousShares }
        click("End previous file shares")
        openChooser()
        click("Excerpt test receiver")
        receiverReady()
        click("Read excerpt now")
        assertBytes(expected, "application/pdf")
        click("Finish excerpt receiver")
        awaitReadingCondition("recovered sharing stayed busy") { export.canApply }
    }
}
