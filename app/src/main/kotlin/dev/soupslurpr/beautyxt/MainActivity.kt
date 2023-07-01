package dev.soupslurpr.beautyxt

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.soupslurpr.beautyxt.settings.SettingsViewModel
import dev.soupslurpr.beautyxt.ui.FileViewModel
import dev.soupslurpr.beautyxt.ui.theme.BeauTyXTTheme

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settingsViewModel: SettingsViewModel = viewModel(
                factory = SettingsViewModel.SettingsViewModelFactory(dataStore)
            )
            val fileViewModel: FileViewModel = viewModel()
            if (intent.action == Intent.ACTION_VIEW) {
                intent.data?.let { fileViewModel.setUri(it, LocalContext.current) }
            }
            BeauTyXTTheme(
                settingsViewModel = settingsViewModel
            ) {
                BeauTyXTApp(
                    settingsViewModel = settingsViewModel,
                    modifier = Modifier
                )
            }
        }
    }
}