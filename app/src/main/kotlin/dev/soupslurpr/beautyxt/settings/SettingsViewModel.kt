package dev.soupslurpr.beautyxt.settings

import androidx.compose.runtime.mutableStateOf
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class SettingsViewModel : ViewModel() {

    /**
     * Settings state
     */
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /**
     * Populate the values of the settings from the Preferences DataStore.
     * This function should only be called in Theme.kt when the app starts.
     */
    suspend fun populateSettingsFromDatastore(dataStore: DataStore<Preferences>) {
        dataStore.data.map { settings ->
            _uiState.update { currentState ->
                currentState.copy(
                    pitchBlackBackground = Pair(
                            uiState.value.pitchBlackBackground.first,
                            mutableStateOf(settings[uiState.value.pitchBlackBackground.first] ?: uiState.value.pitchBlackBackground.second.value)
                    )
                )
            }
        }.collect()
    }

    /**
     * Set a setting to a value and save to Preferences DataStore
     */
    suspend fun setSetting(dataStore: DataStore<Preferences>, key: Preferences.Key<Boolean>, value: Boolean) {
        _uiState.update { currentState ->
            currentState.copy(
                pitchBlackBackground = if (uiState.value.pitchBlackBackground.first.name == key.name) {Pair(key, mutableStateOf(value))} else {uiState.value.pitchBlackBackground}
            )
        }
        dataStore.edit { settings ->
            settings[key] = value
        }
    }
}