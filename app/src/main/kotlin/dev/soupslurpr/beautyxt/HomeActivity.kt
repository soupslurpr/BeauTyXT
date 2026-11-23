package dev.soupslurpr.beautyxt

import android.app.ComponentCaller
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.soupslurpr.beautyxt.ui.HomeNavigation
import dev.soupslurpr.beautyxt.ui.HomeScreen
import dev.soupslurpr.beautyxt.ui.LegacyCleanupGate
import dev.soupslurpr.beautyxt.ui.OpenStatus
import dev.soupslurpr.beautyxt.ui.autosave.NewDocumentAutosaveDialog
import dev.soupslurpr.beautyxt.ui.autosave.NewDocumentAutosaveStore
import dev.soupslurpr.beautyxt.ui.autosave.NewDocumentAutosaveStoreState
import dev.soupslurpr.beautyxt.ui.autosave.SelectAutosaveFolderContract
import dev.soupslurpr.beautyxt.ui.designsystem.AppThemeDialog
import dev.soupslurpr.beautyxt.ui.designsystem.AppThemeStore
import dev.soupslurpr.beautyxt.ui.designsystem.AppThemeStoreState
import dev.soupslurpr.beautyxt.ui.designsystem.BeauTyXTTheme
import dev.soupslurpr.beautyxt.ui.designsystem.trueBlackOrDefault

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
        val app = application as BeauTyXTApplication
        setContent {
            val appThemeState by app.appTheme.state.collectAsStateWithLifecycle()
            val autosaveState by app.newDocumentAutosave.state.collectAsStateWithLifecycle()
            BeauTyXTTheme(trueBlackDarkTheme = appThemeState.trueBlackOrDefault) {
                LegacyCleanupGate(
                    cleanup = app.legacyCleanup,
                    onClose = ::finish
                ) {
                    HomeNavigation(versionName = versionName) { onAbout, onDocument ->
                        HomeActivityContent(
                            appThemeStore = app.appTheme,
                            appThemeState = appThemeState,
                            autosaveStore = app.newDocumentAutosave,
                            autosaveState = autosaveState,
                            onAbout = onAbout,
                            onDocument = onDocument
                        )
                    }
                }
            }
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

    /** Displays Home and starts each action in its own document navigation entry. */
    @Composable
    private fun HomeActivityContent(
        appThemeStore: AppThemeStore,
        appThemeState: AppThemeStoreState,
        autosaveStore: NewDocumentAutosaveStore,
        autosaveState: NewDocumentAutosaveStoreState,
        onAbout: () -> Unit,
        onDocument: (InitialDocumentAction) -> Unit
    ) {
        val hasCamera =
            remember {
                packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
            }
        val hasNfc =
            remember {
                getSystemService(NfcManager::class.java)?.defaultAdapter != null
            }
        var isAppThemeDialogVisible by rememberSaveable { mutableStateOf(false) }
        var isAutosaveDialogVisible by rememberSaveable { mutableStateOf(false) }
        var isFolderPickerPending by rememberSaveable { mutableStateOf(false) }
        var folderPickerFailed by rememberSaveable { mutableStateOf(false) }
        val selectAutosaveFolder =
            rememberLauncherForActivityResult(SelectAutosaveFolderContract()) { result ->
                if (!isFolderPickerPending) return@rememberLauncherForActivityResult
                isFolderPickerPending = false
                if (result != null) {
                    folderPickerFailed = false
                    autosaveStore.selectFolder(
                        encodedUri = result.encodedUri,
                        offeredRead = result.offeredRead,
                        offeredWrite = result.offeredWrite,
                        offeredPersistable = result.offeredPersistable,
                        offeredPrefix = result.offeredPrefix
                    )
                }
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
            onAutosaveSettings = {
                folderPickerFailed = false
                autosaveStore.dismissError()
                isAutosaveDialogVisible = true
            },
            onAppTheme = { isAppThemeDialogVisible = true },
            onAbout = onAbout
        )
        if (isAutosaveDialogVisible) {
            NewDocumentAutosaveDialog(
                store = autosaveStore,
                state = autosaveState,
                pickerErrorMessage =
                    if (folderPickerFailed) stringResource(R.string.autosave_picker_failed) else null,
                onChooseFolder = {
                    folderPickerFailed = false
                    autosaveStore.dismissError()
                    isFolderPickerPending = true
                    try {
                        selectAutosaveFolder.launch(Unit)
                    } catch (_: Exception) {
                        isFolderPickerPending = false
                        folderPickerFailed = true
                    }
                },
                onDismiss = {
                    folderPickerFailed = false
                    isAutosaveDialogVisible = false
                }
            )
        }
        if (isAppThemeDialogVisible) {
            AppThemeDialog(
                store = appThemeStore,
                state = appThemeState,
                onDismiss = { isAppThemeDialogVisible = false }
            )
        }
    }
}
