package dev.soupslurpr.beautyxt

import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.soupslurpr.beautyxt.ui.HomeScreen
import dev.soupslurpr.beautyxt.ui.LegacyCleanupGate
import dev.soupslurpr.beautyxt.ui.OpenStatus
import dev.soupslurpr.beautyxt.ui.UiText
import dev.soupslurpr.beautyxt.ui.designsystem.BeauTyXTTheme

private val DOCUMENT_SESSION_FAILURE_MESSAGE = UiText.Resource(
    R.string.operation_document_session_failure
)

/** Hosts BeauTyXT's stable launcher workbench. */
class HomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            BeauTyXTTheme {
                LegacyCleanupGate(
                    cleanup = (application as BeauTyXTApplication).legacyCleanup,
                    onClose = ::finish
                ) {
                    HomeActivityContent()
                }
            }
        }
    }

    /** Displays Home and starts each action in one child document activity. */
    @Composable
    private fun HomeActivityContent() {
        var openStatus by remember { mutableStateOf<OpenStatus>(OpenStatus.Idle) }
        val hasCamera =
            remember {
                packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
            }
        val hasNfc =
            remember {
                getSystemService(NfcManager::class.java)?.defaultAdapter != null
            }
        val canStartAction =
            openStatus == OpenStatus.Idle || openStatus is OpenStatus.Failed

        /** Starts one internal document child while retaining Home underneath it. */
        fun handOff(intent: Intent) {
            try {
                startActivity(intent)
                openStatus = OpenStatus.Idle
            } catch (_: Exception) {
                openStatus = OpenStatus.Failed(DOCUMENT_SESSION_FAILURE_MESSAGE)
            }
        }

        HomeScreen(
            openStatus = openStatus,
            isNewDocumentEnabled = canStartAction,
            onNewDocument = { handOff(createNewDocumentSessionIntent(this)) },
            onOpenDocument = { handOff(createDocumentSelectionIntent(this)) },
            onCancelDocumentOpen = { openStatus = OpenStatus.Idle },
            isCameraAvailable = hasCamera,
            isScanQrEnabled = hasCamera && canStartAction,
            onScanQr = { handOff(createQrDocumentSessionIntent(this)) },
            isNfcAvailable = hasNfc,
            isReadNfcEnabled = hasNfc && canStartAction,
            onReadNfc = { handOff(createNfcDocumentSessionIntent(this)) },
            onAbout = {
                startActivity(Intent(this, AboutActivity::class.java))
            }
        )
    }
}
