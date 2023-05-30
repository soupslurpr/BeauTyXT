package dev.soupslurpr.beautyxt.ui

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import dev.soupslurpr.beautyxt.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Composable for settings screen
 */
@Composable
fun SettingsScreen(
    onLicenseIconButtonClicked: () -> Unit,
    onPrivacyPolicyIconButtonClicked: () -> Unit,
    onCreditsIconButtonClicked: () -> Unit,
) {
    val localUriHandler = LocalUriHandler.current
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
    ) {
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

/**
 * An individual settings item
 */
@Composable
fun SettingsItem(
    name: String,
    description: String,
    hasSwitch: Boolean = false,
    hasIconButton: Boolean = false,
    onCheckedChange: () -> Unit = {},
    onClickIconButton: () -> Unit = {},
    iconButtonContent: @Composable () -> Unit = {},
) {
    ListItem(
        headlineContent = { Text(text = name) },
        supportingContent = { Text(text = description) },
        trailingContent = { 
            if (hasSwitch) {
                Switch(
                    checked = true,
                    onCheckedChange = { onCheckedChange() }
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