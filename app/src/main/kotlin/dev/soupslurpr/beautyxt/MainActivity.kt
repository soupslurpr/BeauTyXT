package dev.soupslurpr.beautyxt

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.soupslurpr.beautyxt.settings.PreferencesViewModel
import dev.soupslurpr.beautyxt.ui.FileViewModel
import dev.soupslurpr.beautyxt.ui.ReviewPrivacyPolicyAndLicense
import dev.soupslurpr.beautyxt.ui.TypstProjectViewModel
import dev.soupslurpr.beautyxt.ui.theme.BeauTyXTTheme
import com.anggrayudi.storage.SimpleStorageHelper

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class MainActivity : ComponentActivity() {
    val storageHelper = SimpleStorageHelper(this) // for scoped storage permission management on Android 10+
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val fileViewModel: FileViewModel = viewModel()

            val typstProjectViewModel: TypstProjectViewModel = viewModel()

            val preferencesViewModel: PreferencesViewModel = viewModel(
                factory = PreferencesViewModel.PreferencesViewModelFactory(dataStore)
            )

            val preferencesUiState by preferencesViewModel.uiState.collectAsState()

            val isActionViewOrEdit = (intent.action == Intent.ACTION_VIEW) or
                    (intent.action == Intent.ACTION_EDIT)

            val isActionSend = intent.action == Intent.ACTION_SEND

            if (isActionViewOrEdit) {
                val readOnly = intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0

                fileViewModel.setReadOnly(readOnly)

                val uri: Uri = intent.data ?: intent.getParcelableExtra(Intent.EXTRA_STREAM) ?: throw RuntimeException(
                    "intent" +
                            ".data was" +
                            " unexpectedly null!"
                )

                fileViewModel.bindIsolatedService(
                    uri,
                    preferencesUiState.renderMarkdown.second.value,
                    false,
                )
                fileViewModel.setUri(uri, LocalContext.current)
            } else if (isActionSend) {
                val extraText = intent.getStringExtra(Intent.EXTRA_TEXT)
                val extraStream: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)

                if (extraStream != null) {
                    val readOnly = intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0

                    fileViewModel.setReadOnly(readOnly)

                    fileViewModel.bindIsolatedService(
                        extraStream,
                        preferencesUiState.renderMarkdown.second.value,
                        false,
                    )
                    fileViewModel.setUri(extraStream, LocalContext.current)
                } else if (extraText != null) {
                    throw RuntimeException("Sharing text is not supported yet!") // TODO: Handle this
                }
            }

            BeauTyXTTheme(
                preferencesViewModel = preferencesViewModel
            ) {
                if (!preferencesUiState.acceptedPrivacyPolicyAndLicense.second.value) {
                    ReviewPrivacyPolicyAndLicense(preferencesViewModel = preferencesViewModel)
                } else if (preferencesUiState.acceptedPrivacyPolicyAndLicense.second.value) {
                    BeauTyXTApp(
                        activity = this@MainActivity, // provide the parent, MainActivity
                        modifier = Modifier,
                        fileViewModel = fileViewModel,
                        typstProjectViewModel = typstProjectViewModel,
                        preferencesViewModel = preferencesViewModel,
                        isActionViewOrEdit = isActionViewOrEdit,
                        isActionSend = isActionSend,
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // Save scoped storage permission on Android 10+
        storageHelper.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        // Restore scoped storage permission on Android 10+
        super.onRestoreInstanceState(savedInstanceState)
        storageHelper.onRestoreInstanceState(savedInstanceState)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        // Restore scoped storage permission on Android 10+
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Mandatory for Activity, but not for Fragment & ComponentActivity
        storageHelper.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
}