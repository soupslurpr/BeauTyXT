package dev.soupslurpr.beautyxt.settings

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.Preferences

data class SettingsUiState(
    /** Pair of pitch black background preference key and default boolean value */
    var pitchBlackBackground: Pair<Preferences.Key<Boolean>, MutableState<Boolean>> = Pair((booleanPreferencesKey("PITCH_BLACK_BACKGROUND")), mutableStateOf(false))
)