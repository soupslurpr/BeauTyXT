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
        (booleanPreferencesKey("EXPERIMENTAL_FEATURE_PREVIEW_RENDERED_MARKDOWN_IN_FULLSCREEN")),
        mutableStateOf(false)
    ),

    /** Experimental feature that shows the open files of any type button. It is an experimental
     * feature as it currently corrupts some files such as but not limited to .pdf's, .odt's, and
     * probably more.
     */
    val experimentalFeatureOpenAnyFileType: Pair<Preferences.Key<Boolean>, MutableState<Boolean>> = Pair(
        (booleanPreferencesKey("EXPERIMENTAL_FEATURE_OPEN_ANY_FILE_TYPE")),
        mutableStateOf(false)
    )
)