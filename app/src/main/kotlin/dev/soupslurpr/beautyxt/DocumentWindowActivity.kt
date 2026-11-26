/* Hosts non-exported multi-window and launcher-shortcut document sessions. */
package dev.soupslurpr.beautyxt

import android.app.ComponentCaller
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.soupslurpr.beautyxt.ui.HomeDocumentScreen
import dev.soupslurpr.beautyxt.ui.LegacyCleanupGate
import dev.soupslurpr.beautyxt.ui.designsystem.BeauTyXTTheme
import dev.soupslurpr.beautyxt.ui.designsystem.trueBlackOrDefault

/** Retains non-exported document-window entry state across configuration changes in memory. */
internal class DocumentWindowActivityModel : ViewModel() {
    val initialActionState = mutableStateOf(InitialDocumentAction.NewDocument)
    var initialized = false
}

/**
 * Hosts one isolated multi-window or static launcher-shortcut document task.
 *
 * Kept `android:exported="false"` in the manifest so external apps cannot forge internal
 * shortcut or multi-window actions while Android's launcher and BeauTyXT itself can spawn
 * independent adjacent document tasks cleanly.
 */
class DocumentWindowActivity : ComponentActivity() {
    private val activityModel by viewModels<DocumentWindowActivityModel>()
    private val topResumedLostVersion = mutableLongStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        if (!activityModel.initialized) {
            activityModel.initialized = true
            activityModel.initialActionState.value =
                if (savedInstanceState != null) {
                    InitialDocumentAction.Unrestorable
                } else {
                    resolveDocumentWindowAction(intent)
                }
        }

        val app = application as BeauTyXTApplication
        setContent {
            val appThemeState by app.appTheme.state.collectAsStateWithLifecycle()
            BeauTyXTTheme(trueBlackDarkTheme = appThemeState.trueBlackOrDefault) {
                LegacyCleanupGate(cleanup = app.legacyCleanup, onClose = ::finish) {
                    HomeDocumentScreen(
                        initialAction = activityModel.initialActionState.value,
                        isCurrentEntry = { !isFinishing && !isDestroyed },
                        returnDestination = DocumentSessionReturnDestination.Caller,
                        topResumedLostVersion = topResumedLostVersion.longValue,
                        onClose = ::finish
                    )
                }
            }
        }
    }

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        if (!isTopResumedActivity) {
            topResumedLostVersion.longValue += 1L
        }
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
        caller: ComponentCaller
    ) {
        super.onActivityResult(
            requestCode,
            resultCode,
            sanitizePickerResultIntent(this, resultCode, data, caller),
            caller
        )
    }
}
