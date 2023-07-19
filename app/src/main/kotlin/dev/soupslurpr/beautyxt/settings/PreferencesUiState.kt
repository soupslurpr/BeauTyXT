package dev.soupslurpr.beautyxt.settings

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.Preferences

/** Preference pairs, the first is the preference key, and the second is the default value. */
data class PreferencesUiState(
    /** Pitch black background. */
    val pitchBlackBackground: Pair<Preferences.Key<Boolean>, MutableState<Boolean>> = Pair(
        (booleanPreferencesKey("PITCH_BLACK_BACKGROUND")),
        mutableStateOf(false)
    ),

    /** Whether the user has accepted the privacy policy and license. */
    val acceptedPrivacyPolicyAndLicense: Pair<Preferences.Key<Boolean>, MutableState<Boolean>> = Pair(
        (booleanPreferencesKey("ACCEPTED_PRIVACY_POLICY_AND_LICENSE")),
        mutableStateOf(false)
    ),

    /** Render Markdown (.md) files on the bottom side of the screen. */
    val renderMarkdown: Pair<Preferences.Key<Boolean>, MutableState<Boolean>> = Pair(
        (booleanPreferencesKey("RENDER_MARKDOWN")),
        mutableStateOf(true)
    ),

    /** Experimental feature that shows a button when a markdown file is open which will toggle
     * a fullscreen view of the rendered markdown preview.
     */
    val experimentalFeaturePreviewRenderedMarkdownInFullscreen: Pair<Preferences.Key<Boolean>, MutableState<Boolean>> = Pair(
        (booleanPreferencesKey("EXPERIMENTAL_FEATURES")),
        mutableStateOf(false)
    ),
)