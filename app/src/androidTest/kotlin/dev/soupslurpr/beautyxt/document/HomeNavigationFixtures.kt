package dev.soupslurpr.beautyxt.document

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import dev.soupslurpr.beautyxt.HomeActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Enters a real Home destination while retaining its owning activity for lifecycle checks. */
internal fun Instrumentation.startHomeDestination(label: String): HomeActivity {
    val home = startActivitySync(
        Intent(targetContext, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    ) as HomeActivity
    requireActionableText(label).performRequiredClick()
    return home
}

/** Returns a synthetic picker result through Android's real activity-result integration. */
internal fun Instrumentation.selectHomeSource(uri: Uri, mimeType: String = "text/plain") {
    val result = Intent().setDataAndType(uri, mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    returnHomePickerResult(Activity.RESULT_OK, result)
}

/** Intercepts only the system picker; all navigation and result delivery remain real. */
internal fun Instrumentation.returnHomePickerResult(resultCode: Int, result: Intent? = null) {
    val launched = CountDownLatch(1)
    val monitor = object : Instrumentation.ActivityMonitor() {
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            if (intent.action != Intent.ACTION_OPEN_DOCUMENT) return null
            launched.countDown()
            return Instrumentation.ActivityResult(resultCode, result)
        }
    }
    addMonitor(monitor)
    try {
        requireActionableText("Open file").performRequiredClick()
        check(launched.await(15L, TimeUnit.SECONDS)) { "Home did not launch the document picker" }
        waitForAccessibilityIdle()
    } finally {
        removeMonitor(monitor)
    }
}
