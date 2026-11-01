/* Transfers print ownership only after Android accepts a real print job. */
package dev.soupslurpr.beautyxt.printing

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.print.PrintAttributes
import android.print.PrintJob
import android.print.PrintManager
import androidx.lifecycle.LifecycleOwner

/** Closes a resource when the launch throws or returns no accepted owner. */
internal fun <T : AutoCloseable, R : Any> handOffPrintResource(
    resource: T,
    launch: (T) -> R?
): R {
    var transferred = false
    try {
        return checkNotNull(launch(resource)) { "print job was not accepted" }
            .also { transferred = true }
    } finally {
        if (!transferred) resource.close()
    }
}

/** Keeps the exact source alive through print callbacks, with activity destruction as a fallback. */
internal fun launchDocumentPrint(
    context: Context,
    adapter: DocumentPrintAdapter,
    attributes: PrintAttributes? = null
): PrintJob = handOffPrintResource(adapter) {
    val activity = checkNotNull(context.printActivity()) { "printing requires an activity" }
    check(!activity.isDestroyed) { "print activity is destroyed" }
    val owner = checkNotNull(activity as? LifecycleOwner) { "print activity has no lifecycle" }
    it.closeOnLifecycleDestroy(owner.lifecycle)
    checkNotNull(context.getSystemService(PrintManager::class.java)) {
        "print manager is unavailable"
    }.print(it.jobName, it, attributes)
}

/** Finds the Activity that owns Android's print callback delegate. */
private tailrec fun Context.printActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.printActivity()
    else -> null
}
