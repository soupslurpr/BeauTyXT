package oldtyxt.dev.soupslurpr.beautyxt.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.soupslurpr.beautyxt.R
import kotlinx.coroutines.launch
import oldtyxt.dev.soupslurpr.beautyxt.settings.PreferencesViewModel

/**
 * Composable for settings screen
 */
@Composable
fun SettingsScreen(
    onLicenseIconButtonClicked: () -> Unit,
    onPrivacyPolicyIconButtonClicked: () -> Unit,
    onCreditsIconButtonClicked: () -> Unit,
    preferencesViewModel: PreferencesViewModel,
    onDonationSettingsItemClicked: () -> Unit,
) {
    val localUriHandler = LocalUriHandler.current
    val preferencesUiState by preferencesViewModel.uiState.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column {
            SettingsCategoryText(category = stringResource(id = R.string.oldtyxt_theme))
            SettingsItem(
                name = stringResource(id = R.string.oldtyxt_pitch_black_background_setting_name),
                description = stringResource(id = R.string.oldtyxt_pitch_black_background_setting_description),
                hasSwitch = true,
                checked = preferencesUiState.pitchBlackBackground.second.value,
                onCheckedChange = {
                    coroutineScope.launch {
                        preferencesViewModel.setPreference(
                            preferencesUiState.pitchBlackBackground.first,
                            it
                        )
                    }
                }
            )
        }

        Column {
            SettingsCategoryText(category = stringResource(id = R.string.oldtyxt_md))
            SettingsItem(
                name = stringResource(id = R.string.oldtyxt_render_markdown_setting_name),
                description = stringResource(
                    id = R.string.oldtyxt_render_markdown_setting_description,
                    stringResource(R.string.oldtyxt_md)
                ),
                hasSwitch = true,
                checked = preferencesUiState.renderMarkdown.second.value,
                onCheckedChange = {
                    coroutineScope.launch {
                        preferencesViewModel.setPreference(
                            preferencesUiState.renderMarkdown.first,
                            it
                        )
                    }
                }
            )
        }

        Column {
            SettingsCategoryText(category = stringResource(id = R.string.oldtyxt_typst_project))
            SettingsItem(
                name = stringResource(R.string.oldtyxt_typst_project_show_warnings_and_errors_setting_name),
                description = stringResource(
                    R.string.oldtyxt_typst_project_show_warnings_and_errors_setting_description,
                    stringResource(R.string.oldtyxt_typst_project)
                ),
                hasSwitch = true,
                checked = preferencesUiState.typstProjectShowWarningsAndErrors.second.value,
                onCheckedChange = {
                    coroutineScope.launch {
                        preferencesViewModel.setPreference(
                            preferencesUiState.typstProjectShowWarningsAndErrors
                                .first, it
                        )
                    }
                }
            )
        }

        Column {
            SettingsCategoryText(category = stringResource(id = R.string.oldtyxt_about))
            SettingsItem(
                name = stringResource(id = R.string.oldtyxt_open_beautyxt_website_setting_name),
                description = stringResource(id = R.string.oldtyxt_open_beautyxt_website_setting_description),
                hasIcon = true,
                onClickIconSetting = {
                    localUriHandler.openUri("https://beautyxt.app")
                },
                icon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                        contentDescription = null
                    )
                }
            )
            SettingsItem(
                name = stringResource(id = R.string.oldtyxt_view_source_code_setting_name),
                description = stringResource(id = R.string.oldtyxt_view_source_code_setting_description),
                hasIcon = true,
                onClickIconSetting = {
                    localUriHandler.openUri("https://github.com/soupslurpr/BeauTyXT")
                },
                icon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                        contentDescription = null
                    )
                }
            )
            SettingsItem(
                name = stringResource(id = R.string.oldtyxt_license_setting_name),
                description = stringResource(id = R.string.oldtyxt_license_setting_description),
                hasIcon = true,
                onClickIconSetting = { onLicenseIconButtonClicked() },
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null
                    )
                }
            )
            SettingsItem(
                name = stringResource(id = R.string.oldtyxt_privacy_policy_setting_name),
                description = stringResource(id = R.string.oldtyxt_privacy_policy_setting_description),
                hasIcon = true,
                onClickIconSetting = { onPrivacyPolicyIconButtonClicked() },
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                    )
                }
            )
            SettingsItem(
                name = stringResource(id = R.string.oldtyxt_credits_setting_name),
                description = stringResource(id = R.string.oldtyxt_credits_setting_description),
                hasIcon = true,
                onClickIconSetting = { onCreditsIconButtonClicked() },
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null
                    )
                }
            )
            SettingsItem(
                stringResource(R.string.oldtyxt_donation_setting_name),
                stringResource(R.string.oldtyxt_donation_setting_description),
                hasIcon = true,
                onClickIconSetting = { onDonationSettingsItemClicked() },
                icon = {
                    Icon(
                        Icons.Filled.Info,
                        null
                    )
                }
            )
        }

        Column {
            SettingsCategoryText(category = stringResource(R.string.oldtyxt_experimental_features))

            SettingsItem(
                name = stringResource(R.string.oldtyxt_fullscreen_markdown_render_preview_setting_name),
                description = stringResource(R.string.oldtyxt_fullscreen_markdown_render_preview_setting_description),
                hasSwitch = true,
                checked = preferencesUiState.experimentalFeaturePreviewRenderedMarkdownInFullscreen.second.value,
                onCheckedChange = {
                    coroutineScope.launch {
                        preferencesViewModel.setPreference(
                            preferencesUiState
                                .experimentalFeaturePreviewRenderedMarkdownInFullscreen.first, it
                        )
                    }
                }
            )
            SettingsItem(
                name = stringResource(R.string.oldtyxt_fullscreen_typst_preview_button_setting_name),
                description = stringResource(R.string.oldtyxt_fullscreen_typst_preview_button_setting_description),
                hasSwitch = true,
                checked = preferencesUiState.experimentalFeaturePreviewRenderedTypstProjectInFullscreen.second.value,
                onCheckedChange = {
                    coroutineScope.launch {
                        preferencesViewModel.setPreference(
                            preferencesUiState
                                .experimentalFeaturePreviewRenderedTypstProjectInFullscreen.first,
                            it
                        )
                    }
                }
            )
            SettingsItem(
                name = stringResource(R.string.oldtyxt_open_any_file_type_setting_name),
                description = stringResource(R.string.oldtyxt_open_any_file_type_setting_description),
                hasSwitch = true,
                checked = preferencesUiState.experimentalFeatureOpenAnyFileType.second.value,
                onCheckedChange = {
                    coroutineScope.launch {
                        preferencesViewModel.setPreference(
                            preferencesUiState.experimentalFeatureOpenAnyFileType.first,
                            it
                        )
                    }
                }
            )
            SettingsItem(
                name = stringResource(
                    R.string.oldtyxt_export_markdown_to_docx_setting_name,
                    stringResource(R.string.oldtyxt_md),
                    stringResource(R.string.oldtyxt_docx)
                ),
                description = stringResource(
                    R.string.oldtyxt_export_markdown_to_docx_setting_description, stringResource(
                        R.string.oldtyxt_md
                    ),
                    stringResource(R.string.oldtyxt_docx)
                ),
                hasSwitch = true,
                checked = preferencesUiState.experimentalFeatureExportMarkdownToDocx.second.value,
                onCheckedChange = {
                    coroutineScope.launch {
                        preferencesViewModel.setPreference(
                            preferencesUiState.experimentalFeatureExportMarkdownToDocx
                                .first, it
                        )
                    }
                }
            )
        }

        Spacer(Modifier.padding(WindowInsets.navigationBars.asPaddingValues()))
    }
}

/**
 * An individual settings item
 */
@Composable
fun SettingsItem(
    name: String,
    description: String,
    hasSwitch: Boolean = false,
    hasIcon: Boolean = false,
    onCheckedChange: (Boolean) -> Unit = {},
    checked: Boolean = false,
    onClickIconSetting: () -> Unit = {},
    icon: @Composable () -> Unit = {},
) {
    ListItem(
        modifier = when {
            hasIcon -> Modifier.clickable(onClick = { onClickIconSetting() })
            hasSwitch -> Modifier.toggleable(
                value = checked,
                onValueChange = { onCheckedChange(it) }
            )

            else -> Modifier
        },
        headlineContent = {
            Text(
                text = name,
                fontWeight = FontWeight.SemiBold
            )
        },
        supportingContent = { Text(text = description) },
        trailingContent = {
            when {
                hasIcon -> icon()
                hasSwitch -> Switch(
                    checked = checked,
                    onCheckedChange = null,
                )
            }
        }
    )
}

@Composable
fun SettingsCategoryText(category: String) {
    Text(
        text = category,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(8.dp),
        style = typography.bodyMedium,
        fontWeight = FontWeight.Bold
    )
}