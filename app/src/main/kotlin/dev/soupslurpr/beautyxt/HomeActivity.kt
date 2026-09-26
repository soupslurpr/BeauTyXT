package dev.soupslurpr.beautyxt

import android.content.pm.PackageManager
import android.nfc.NfcManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.soupslurpr.beautyxt.ui.HomeNavigation
import dev.soupslurpr.beautyxt.ui.HomeScreen
import dev.soupslurpr.beautyxt.ui.LegacyCleanupGate
import dev.soupslurpr.beautyxt.ui.OpenStatus
import dev.soupslurpr.beautyxt.ui.designsystem.BeauTyXTTheme

/** Hosts BeauTyXT's stable launcher workbench. */
class HomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val packageInfo = packageManager.getPackageInfo(
            packageName,
            PackageManager.PackageInfoFlags.of(0)
        )
        val versionName = packageInfo.versionName ?: packageInfo.longVersionCode.toString()
        setContent {
            BeauTyXTTheme {
                LegacyCleanupGate(
                    cleanup = (application as BeauTyXTApplication).legacyCleanup,
                    onClose = ::finish
                ) {
                    HomeNavigation(versionName = versionName) { onAbout, onDocument ->
                        HomeActivityContent(onAbout = onAbout, onDocument = onDocument)
                    }
                }
            }
        }
    }

    /** Displays Home and starts each action in its own document navigation entry. */
    @Composable
    private fun HomeActivityContent(onAbout: () -> Unit, onDocument: (InitialDocumentAction) -> Unit) {
        val hasCamera =
            remember {
                packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
            }
        val hasNfc =
            remember {
                getSystemService(NfcManager::class.java)?.defaultAdapter != null
            }
        HomeScreen(
            openStatus = OpenStatus.Idle,
            isNewDocumentEnabled = true,
            onNewDocument = { onDocument(InitialDocumentAction.NewDocument) },
            onOpenDocument = { onDocument(InitialDocumentAction.SelectDocument) },
            onCancelDocumentOpen = {},
            isCameraAvailable = hasCamera,
            isScanQrEnabled = hasCamera,
            onScanQr = { onDocument(InitialDocumentAction.ScanQr) },
            isNfcAvailable = hasNfc,
            isReadNfcEnabled = hasNfc,
            onReadNfc = { onDocument(InitialDocumentAction.ReadNfc) },
            onAbout = onAbout
        )
    }
}
