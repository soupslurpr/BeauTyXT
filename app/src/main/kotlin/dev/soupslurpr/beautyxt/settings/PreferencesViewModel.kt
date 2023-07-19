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

class PreferencesViewModel(private val dataStore: DataStore<Preferences>) : ViewModel() {
    /**
     * Settings state
     */
    private val _uiState = MutableStateFlow(PreferencesUiState())
    val uiState: StateFlow<PreferencesUiState> = _uiState.asStateFlow()

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
                    ),
                    acceptedPrivacyPolicyAndLicense = Pair(
                        uiState.value.acceptedPrivacyPolicyAndLicense.first,
                        mutableStateOf(settings[uiState.value.acceptedPrivacyPolicyAndLicense.first] ?: uiState.value.acceptedPrivacyPolicyAndLicense.second.value)
                    ),
                    renderMarkdown = Pair(
                        uiState.value.renderMarkdown.first,
                        mutableStateOf(settings[uiState.value.renderMarkdown.first] ?: uiState.value.renderMarkdown.second.value)
                    ),
                    experimentalFeaturePreviewRenderedMarkdownInFullscreen = Pair(
                        uiState.value.experimentalFeaturePreviewRenderedMarkdownInFullscreen.first,
                        mutableStateOf(settings[uiState.value.experimentalFeaturePreviewRenderedMarkdownInFullscreen.first] ?: uiState.value.experimentalFeaturePreviewRenderedMarkdownInFullscreen.second.value)
                    ),
                )
            }
        }.collect()
    }

    /**
     * Set a preference to a value and save to Preferences DataStore
     */
    suspend fun setSetting(key: Preferences.Key<Boolean>, value: Boolean) {
        _uiState.update { currentState ->
            currentState.copy(
                pitchBlackBackground = if (uiState.value.pitchBlackBackground.first.name == key.name) {Pair(key, mutableStateOf(value))} else {uiState.value.pitchBlackBackground},
                acceptedPrivacyPolicyAndLicense = if (uiState.value.acceptedPrivacyPolicyAndLicense.first.name == key.name) {Pair(key, mutableStateOf(value))} else {uiState.value.acceptedPrivacyPolicyAndLicense},
                renderMarkdown = if (uiState.value.renderMarkdown.first.name == key.name) {Pair(key, mutableStateOf(value))} else {uiState.value.renderMarkdown},
                experimentalFeaturePreviewRenderedMarkdownInFullscreen = if (uiState.value.experimentalFeaturePreviewRenderedMarkdownInFullscreen.first.name == key.name) {Pair(key, mutableStateOf(value))} else {uiState.value.experimentalFeaturePreviewRenderedMarkdownInFullscreen},
            )
        }
        dataStore.edit { settings ->
            settings[key] = value
        }
    }

    class SettingsViewModelFactory(private val dataStore: DataStore<Preferences>) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(PreferencesViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return PreferencesViewModel(dataStore) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class $modelClass")
        }
    }
}