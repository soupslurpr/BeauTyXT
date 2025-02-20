package oldtyxt.dev.soupslurpr.beautyxt.settings

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey

/** Preference pairs, the first is the preference key, and the second is the default value. */
data class PreferencesUiState(
    /** Whether the preferences are loaded. **/
    val isPreferencesLoaded: MutableState<Boolean> = mutableStateOf(false),

    /** Whether NewTyXT will be used instead of OldTyXT. **/
    val newtyxt: Pair<Preferences.Key<Boolean>, MutableState<Boolean>> = Pair(
        booleanPreferencesKey("NEWTYXT"),
        mutableStateOf(false)
    ),

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

    /** Whether to show Typst project warnings and errors below the Typst project preview */
    val typstProjectShowWarningsAndErrors: Pair<Preferences.Key<Boolean>, MutableState<Boolean>> = Pair(
        (booleanPreferencesKey("TYPST_PROJECT_SHOW_WARNINGS_AND_ERRORS")),
        mutableStateOf(true)
    ),

    /** Experimental feature that shows a button when a markdown file is open which will toggle
     * a fullscreen view of the rendered markdown preview.
     */
    val experimentalFeaturePreviewRenderedMarkdownInFullscreen: Pair<Preferences.Key<Boolean>, MutableState<Boolean>> = Pair(
        (booleanPreferencesKey("EXPERIMENTAL_FEATURE_PREVIEW_RENDERED_MARKDOWN_IN_FULLSCREEN")),
        mutableStateOf(false)
    ),

    /** Experimental feature that shows a button when a Typst project is open which will toggle a fullscreen view of
     * the rendered Typst project preview.
     */
    val experimentalFeaturePreviewRenderedTypstProjectInFullscreen: Pair<Preferences.Key<Boolean>,
            MutableState<Boolean>> = Pair(
        (booleanPreferencesKey
            ("EXPERIMENTAL_FEATURE_PREVIEW_RENDERED_TYPST_PROJECT_IN_FULLSCREEN")),
        mutableStateOf(false)
    ),

    /** Experimental feature that shows the open files of any type button. It is an experimental
     * feature as it currently corrupts some files such as but not limited to .pdf's, .odt's, and
     * probably more.
     */
    val experimentalFeatureOpenAnyFileType: Pair<Preferences.Key<Boolean>, MutableState<Boolean>> = Pair(
        (booleanPreferencesKey("EXPERIMENTAL_FEATURE_OPEN_ANY_FILE_TYPE")),
        mutableStateOf(false)
    ),

    /** Experimental feature that shows an export option on markdown files to export to .docx.
     * It does not support all markdown yet and will crash when trying to export a file with unsupported markdown.
     */
    val experimentalFeatureExportMarkdownToDocx: Pair<Preferences.Key<Boolean>, MutableState<Boolean>> = Pair(
        (booleanPreferencesKey("EXPERIMENTAL_FEATURE_EXPORT_MARKDOWN_TO_DOCX")),
        mutableStateOf(false)
    ),
)