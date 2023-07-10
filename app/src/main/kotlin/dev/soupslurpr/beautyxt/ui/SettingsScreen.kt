package dev.soupslurpr.beautyxt.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import dev.soupslurpr.beautyxt.settings.SettingsViewModel
import kotlinx.coroutines.launch

/**
 * Composable for settings screen
 */
@Composable
fun SettingsScreen(
    onLicenseIconButtonClicked: () -> Unit,
    onPrivacyPolicyIconButtonClicked: () -> Unit,
    onCreditsIconButtonClicked: () -> Unit,
    settingsViewModel: SettingsViewModel,
) {
    val localUriHandler = LocalUriHandler.current
    val settingsUiState by settingsViewModel.uiState.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column {
            SettingsCategoryText(category = stringResource(id = R.string.theme))
            SettingsItem(
                name = stringResource(id = R.string.pitch_black_background_setting_name),
                description = stringResource(id = R.string.pitch_black_background_setting_description),
                hasSwitch = true,
                checked = settingsUiState.pitchBlackBackground.second.value,
                onCheckedChange = {
                    coroutineScope.launch {
                        settingsViewModel.setSetting(settingsUiState.pitchBlackBackground.first, it)
                    }
                }
            )
        }

        Column {
            SettingsCategoryText(category = stringResource(id = R.string.about))
            SettingsItem(
                name = stringResource(id = R.string.open_beautyxt_website_setting_name),
                description = stringResource(id = R.string.open_beautyxt_website_setting_description),
                hasIconButton = true,
                onClickIconButton = {
                    localUriHandler.openUri("https://beautyxt.soupslurpr.dev")
                },
                iconButtonContent = {
                    Icon(
                        imageVector = Icons.Filled.ExitToApp,
                        contentDescription = stringResource(id = R.string.open_beautyxt_website_setting_description)
                    )
                }
            )
            SettingsItem(
                name = stringResource(id = R.string.view_source_code_setting_name),
                description = stringResource(id = R.string.view_source_code_setting_description),
                hasIconButton = true,
                onClickIconButton = {
                    localUriHandler.openUri("https://github.com/soupslurpr/BeauTyXT")
                },
                iconButtonContent = {
                    Icon(
                        imageVector = Icons.Filled.ExitToApp,
                        contentDescription = stringResource(id = R.string.view_source_code_setting_description)
                    )
                }
            )
            SettingsItem(
                name = stringResource(id = R.string.license_setting_name),
                description = stringResource(id = R.string.license_setting_description),
                hasIconButton = true,
                onClickIconButton = { onLicenseIconButtonClicked() },
                iconButtonContent = {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = stringResource(id = R.string.license_setting_description)
                    )
                }
            )
            SettingsItem(
                name = stringResource(id = R.string.privacy_policy_setting_name),
                description = stringResource(id = R.string.privacy_policy_setting_description),
                hasIconButton = true,
                onClickIconButton = { onPrivacyPolicyIconButtonClicked() },
                iconButtonContent = {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = stringResource(id = R.string.privacy_policy_setting_description)
                    )
                }
            )
            SettingsItem(
                name = stringResource(id = R.string.credits_setting_name),
                description = stringResource(id = R.string.credits_setting_description),
                hasIconButton = true,
                onClickIconButton = { onCreditsIconButtonClicked() },
                iconButtonContent = {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = stringResource(id = R.string.credits_setting_description)
                    )
                }
            )
        }
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
    hasIconButton: Boolean = false,
    onCheckedChange: (Boolean) -> Unit = {},
    checked: Boolean = false,
    onClickIconButton: () -> Unit = {},
    iconButtonContent: @Composable () -> Unit = {},
) {
    ListItem(
        headlineContent = {
            Text(
                text = name,
                fontWeight = FontWeight.SemiBold
            ) },
        supportingContent = { Text(text = description) },
        trailingContent = { 
            if (hasSwitch) {
                Switch(
                    checked = checked,
                    onCheckedChange = { onCheckedChange(it) },
                ) 
            }
            if (hasIconButton) {
                IconButton(
                    onClick = { onClickIconButton() },
                    content = { iconButtonContent() }
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