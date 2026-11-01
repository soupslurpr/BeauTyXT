/* Verifies native snapshot ownership across print handoff and Activity destruction. */
package dev.soupslurpr.beautyxt.document

import android.accessibilityservice.AccessibilityService
import android.app.Instrumentation
import android.content.Intent
import dev.soupslurpr.beautyxt.HomeActivity
import dev.soupslurpr.beautyxt.printing.DocumentPrintAdapter
import dev.soupslurpr.beautyxt.printing.SourcePrintDocumentContent
import dev.soupslurpr.beautyxt.printing.defaultPrintSettings
import dev.soupslurpr.beautyxt.printing.handOffPrintResource
import java.util.concurrent.atomic.AtomicInteger

/** Uses real native snapshots and Activity lifecycle; no printer or synthetic platform policy. */
internal fun Instrumentation.verifyPrintLifecycleOwnership() {
    for (outcome in listOf("null", "throw", "finish", "destroy")) {
        val activity = startActivitySync(
            Intent(targetContext, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ) as HomeActivity
        val document = RustDocument.createEmpty()
        val text = "A tiny immutable print source.\n"
        val metrics = document.replace(0, Utf16Range(0, 0), text)
        val snapshot = document.captureSnapshot(metrics.revision)
        val closes = AtomicInteger()
        val tracked = object : EditorDocumentSnapshot by snapshot {
            override fun close() {
                closes.incrementAndGet()
                snapshot.close()
            }
        }
        val adapter = DocumentPrintAdapter(
            "Print ownership", "Print ownership.pdf",
            SourcePrintDocumentContent(metrics, tracked), defaultPrintSettings(false),
            targetContext.resources
        )
        try {
            runOnMainSync { adapter.closeOnLifecycleDestroy(activity.lifecycle) }
            if (outcome == "null" || outcome == "throw") {
                runOnMainSync {
                    check(runCatching {
                        handOffPrintResource<DocumentPrintAdapter, Any>(adapter) {
                            if (outcome == "throw") error("synthetic rejected launch") else null
                        }
                    }.isFailure)
                }
            } else {
                // Printing may normally pause/stop its Activity while the system dialog is open.
                check(uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
                awaitReadingCondition("print owner did not stop for the lifecycle fixture") {
                    !activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
                }
                check(closes.get() == 0 && snapshot.metrics() == metrics) {
                    "pause/stop released a live print representation"
                }
                if (outcome == "finish") runOnMainSync { adapter.onFinish() }
            }
            if (outcome != "destroy") check(closes.get() == 1)
            runOnMainSync { activity.finish() }
            awaitReadingCondition("destroyed print owner retained its snapshot") {
                activity.isDestroyed && closes.get() == 1
            }
            check(runCatching { snapshot.metrics() }.isFailure)
            runOnMainSync { adapter.onFinish(); adapter.close() }
            check(closes.get() == 1) { "print lifecycle closed its content more than once" }
        } finally {
            runOnMainSync { adapter.close(); activity.finish() }
            document.close()
        }
    }
}
