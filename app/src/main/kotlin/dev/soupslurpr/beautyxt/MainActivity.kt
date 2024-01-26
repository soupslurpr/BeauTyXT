package dev.soupslurpr.beautyxt

import android.content.Context
import android.content.Intent
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

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val fileViewModel: FileViewModel = viewModel()

            val typstProjectViewModel: TypstProjectViewModel = viewModel()

            val preferencesViewModel: PreferencesViewModel = viewModel(
                factory = PreferencesViewModel.PreferencesViewModelFactory(dataStore)
            )

            val isActionViewOrEdit = (intent.action == Intent.ACTION_VIEW) or (intent.action == Intent.ACTION_EDIT)

            if (isActionViewOrEdit) {
                val readOnly = intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0

                fileViewModel.setReadOnly(readOnly)

                intent.data?.let {
                    fileViewModel.bindIsolatedService(
                        it,
                        preferencesViewModel.uiState.value.renderMarkdown.second.value,
                        false,
                    )
                    fileViewModel.setUri(it, LocalContext.current)
                }
            }

            val preferencesUiState by preferencesViewModel.uiState.collectAsState()

            BeauTyXTTheme(
                preferencesViewModel = preferencesViewModel
            ) {
                if (!preferencesUiState.acceptedPrivacyPolicyAndLicense.second.value) {
                    ReviewPrivacyPolicyAndLicense(preferencesViewModel = preferencesViewModel)
                } else if (preferencesUiState.acceptedPrivacyPolicyAndLicense.second.value) {
                    BeauTyXTApp(
                        modifier = Modifier,
                        fileViewModel = fileViewModel,
                        typstProjectViewModel = typstProjectViewModel,
                        preferencesViewModel = preferencesViewModel,
                        isActionViewOrEdit = isActionViewOrEdit,
                    )
                }
            }
        }
    }
}