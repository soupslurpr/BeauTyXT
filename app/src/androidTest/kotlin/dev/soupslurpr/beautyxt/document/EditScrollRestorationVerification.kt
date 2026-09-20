/* Verifies edit-anchor restoration against changing native Compose layout bounds. */
package dev.soupslurpr.beautyxt.document

import android.app.Instrumentation
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.soupslurpr.beautyxt.HomeActivity
import dev.soupslurpr.beautyxt.ui.editor.restoreEditWindowScroll
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val INITIAL_VIEWPORT_HEIGHT_DP = 200
private const val EXPANDED_VIEWPORT_HEIGHT_DP = 450
private const val CONTENT_HEIGHT_DP = 1_000
private const val LAYOUT_TIMEOUT_MILLIS = 5_000L

/** Verifies a pre-layout target remains valid after the measured scroll range shrinks. */
internal fun Instrumentation.verifyEditScrollRestorationAfterResize() {
    val activity = startActivitySync(
        Intent(targetContext, HomeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
    ) as HomeActivity
    try {
        runBlocking {
            withContext(Dispatchers.Main) {
                val scrollState = ScrollState(initial = 0)
                val viewportHeight = mutableIntStateOf(INITIAL_VIEWPORT_HEIGHT_DP)
                activity.setContent {
                    Box(Modifier.height(viewportHeight.intValue.dp)) {
                        Box(Modifier.fillMaxWidth().verticalScroll(scrollState)) {
                            Spacer(Modifier.height(CONTENT_HEIGHT_DP.dp))
                        }
                    }
                }
                val targetBeforeResize = withTimeout(LAYOUT_TIMEOUT_MILLIS) {
                    snapshotFlow { scrollState.maxValue }.first { it != Int.MAX_VALUE }
                }
                check(targetBeforeResize > 0) { "test content must exceed its viewport" }
                viewportHeight.intValue = EXPANDED_VIEWPORT_HEIGHT_DP
                withTimeout(LAYOUT_TIMEOUT_MILLIS) {
                    snapshotFlow { scrollState.maxValue }.first { it < targetBeforeResize }
                }
                for (preserveScrollMomentum in listOf(true, false)) {
                    scrollState.scrollTo(0)
                    val previousMotion = launch(start = CoroutineStart.UNDISPATCHED) {
                        scrollState.scroll { awaitCancellation() }
                    }
                    restoreEditWindowScroll(
                        scrollState = scrollState,
                        targetScrollPixels = targetBeforeResize,
                        preserveScrollMomentum = preserveScrollMomentum
                    )
                    check(scrollState.value == scrollState.maxValue) {
                        "restored anchor did not respect the resized bounds"
                    }
                    check(previousMotion.isActive == preserveScrollMomentum) {
                        "restored anchor changed the requested momentum ownership"
                    }
                    previousMotion.cancelAndJoin()
                }
            }
        }
    } finally {
        runOnMainSync(activity::finishAndRemoveTask)
    }
}
