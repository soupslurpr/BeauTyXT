package dev.soupslurpr.beautyxt.settings

import androidx.compose.runtime.mutableStateOf
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(private val dataStore: DataStore<Preferences>) : ViewModel() {
    /**
     * Settings state
     */
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            populateSettingsFromDatastore()
        }
    }

    /**
     * Populate the values of the settings from the Preferences DataStore.
     * This function is only called from this ViewModel's init
     */
    private suspend fun populateSettingsFromDatastore() {
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
    suspend fun setSetting(key: Preferences.Key<Boolean>, value: Boolean) {
        _uiState.update { currentState ->
            currentState.copy(
                pitchBlackBackground = if (uiState.value.pitchBlackBackground.first.name == key.name) {Pair(key, mutableStateOf(value))} else {uiState.value.pitchBlackBackground}
            )
        }
        dataStore.edit { settings ->
            settings[key] = value
        }
    }

    class SettingsViewModelFactory(private val dataStore: DataStore<Preferences>) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return SettingsViewModel(dataStore) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class $modelClass")
        }
    }
}