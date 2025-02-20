package dev.soupslurpr.beautyxt

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import dagger.hilt.android.AndroidEntryPoint
import dev.soupslurpr.beautyxt.newtyxt.dev.soupslurpr.beautyxt.BeautyxtApp
import dev.soupslurpr.beautyxt.newtyxt.dev.soupslurpr.beautyxt.ui.theme.BeautyxtTheme
import oldtyxt.dev.soupslurpr.beautyxt.oldtyxtDataStore

/**
 * BeauTyXT is currently split up into two parts: OldTyXT and NewTyXT.
 *
 * The version that's shipped is currently OldTyXT.
 *
 * OldTyXT is effectively deprecated. No new features will be added to OldTyXT. OldTyXT will
 * still be maintained until NewTyXT can fully replace it.
 *
 * NewTyXT is a rewrite which aims to adhere to Android development best practices.
 * NewTyXT is work-in-progress. Once basic text file editing is implemented, the setting to switch
 * between OldTyXT and NewTyXT will be exposed in the UI to facilitate public testing.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) && BuildConfig.DEBUG) {
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
        }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val oldtyxtPreferencesViewModel: oldtyxt.dev.soupslurpr.beautyxt.settings.PreferencesViewModel =
                viewModel(
                    factory = oldtyxt.dev.soupslurpr.beautyxt.settings.PreferencesViewModel
                        .PreferencesViewModelFactory(oldtyxtDataStore)
                )

            val oldtyxtPreferencesUiState by oldtyxtPreferencesViewModel.uiState.collectAsState()

            if (oldtyxtPreferencesUiState.isPreferencesLoaded.value) {
                if (oldtyxtPreferencesUiState.newtyxt.second.value) {
                    BeautyxtTheme {
                        BeautyxtApp()
                    }
                } else {
                    val fileViewModel: oldtyxt.dev.soupslurpr.beautyxt.ui.FileViewModel =
                        viewModel()

                    val typstProjectViewModel: oldtyxt.dev.soupslurpr.beautyxt.ui
                    .TypstProjectViewModel = viewModel()

                    val isActionViewOrEdit = (intent.action==Intent.ACTION_VIEW) or
                            (intent.action==Intent.ACTION_EDIT)

                    val isActionSend = intent.action==Intent.ACTION_SEND

                    if (isActionViewOrEdit) {
                        val readOnly = intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION==0

                        fileViewModel.setReadOnly(readOnly)

                        val uri: Uri = intent.data
                            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                            } else {
                                intent.getParcelableExtra(Intent.EXTRA_STREAM)
                            } ?: throw RuntimeException(
                                "intent" +
                                        ".data was" +
                                        " unexpectedly null!"
                            )

                        fileViewModel.bindIsolatedService(
                            uri,
                            oldtyxtPreferencesUiState.renderMarkdown.second.value,
                            false,
                        )
                        fileViewModel.setUri(uri, LocalContext.current)
                    } else if (isActionSend) {
                        val extraText = intent.getStringExtra(Intent.EXTRA_TEXT)
                        val extraStream: Uri? =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                            } else {
                                intent.getParcelableExtra(Intent.EXTRA_STREAM)
                            }

                        if (extraStream!=null) {
                            val readOnly =
                                intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION==0

                            fileViewModel.setReadOnly(readOnly)

                            fileViewModel.bindIsolatedService(
                                extraStream,
                                oldtyxtPreferencesUiState.renderMarkdown.second.value,
                                false,
                            )
                            fileViewModel.setUri(extraStream, LocalContext.current)
                        } else if (extraText!=null) {
                            throw RuntimeException("Sharing text is not supported yet!") // TODO: Handle this
                        }
                    }

                    oldtyxt.dev.soupslurpr.beautyxt.ui.theme.BeauTyXTTheme(
                        preferencesViewModel = oldtyxtPreferencesViewModel
                    ) {
                        if (!oldtyxtPreferencesUiState.acceptedPrivacyPolicyAndLicense.second.value) {
                            oldtyxt.dev.soupslurpr.beautyxt.ui.ReviewPrivacyPolicyAndLicense(
                                preferencesViewModel = oldtyxtPreferencesViewModel
                            )
                        } else if (oldtyxtPreferencesUiState.acceptedPrivacyPolicyAndLicense.second.value) {
                            oldtyxt.dev.soupslurpr.beautyxt.BeauTyXTApp(
                                modifier = Modifier,
                                fileViewModel = fileViewModel,
                                typstProjectViewModel = typstProjectViewModel,
                                preferencesViewModel = oldtyxtPreferencesViewModel,
                                isActionViewOrEdit = isActionViewOrEdit,
                                isActionSend = isActionSend,
                            )
                        }
                    }
                }
            }
        }
    }
}