/* Presents the app-wide theme dialog shared by Home and document sessions. */
package dev.soupslurpr.beautyxt.ui.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.soupslurpr.beautyxt.R

/** Displays app-wide theme controls with live updates and explicit recovery actions. */
@Composable
internal fun AppThemeDialog(
    store: AppThemeStore,
    state: AppThemeStoreState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var confirmSavedReset by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (state != AppThemeStoreState.LoadFailed) {
            confirmSavedReset = false
        }
    }

    if (confirmSavedReset && state == AppThemeStoreState.LoadFailed) {
        val dismissReset = { confirmSavedReset = false }
        AlertDialog(
            onDismissRequest = dismissReset,
            modifier = modifier,
            title = { Text(stringResource(R.string.app_theme_reset_saved_title)) },
            text = { Text(stringResource(R.string.app_theme_reset_saved_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        dismissReset()
                        store.resetSavedTheme()
                    }
                ) {
                    Text(stringResource(R.string.app_theme_reset_saved_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = dismissReset) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(stringResource(R.string.app_theme_title)) },
        text = {
            val errorMessage = when {
                state == AppThemeStoreState.LoadFailed -> stringResource(R.string.app_theme_load_failed)
                state is AppThemeStoreState.Ready && state.saveFailed ->
                    stringResource(R.string.app_theme_save_failed)
                else -> ""
            }
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // Keep one always-placed live-region container so TalkBack announces errors.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 1.dp)
                        .semantics(mergeDescendants = errorMessage.isNotEmpty()) {
                            liveRegion = LiveRegionMode.Polite
                        }
                ) {
                    if (errorMessage.isNotEmpty()) {
                        Text(
                            text = errorMessage,
                            modifier = Modifier.padding(bottom = 12.dp),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (state) {
                        AppThemeStoreState.Loading -> {
                            Text(stringResource(R.string.app_theme_loading))
                        }

                        AppThemeStoreState.LoadFailed -> {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                TextButton(onClick = store::retryLoad) {
                                    Text(stringResource(R.string.app_theme_retry_load))
                                }
                                TextButton(onClick = { confirmSavedReset = true }) {
                                    Text(stringResource(R.string.app_theme_reset_saved_action))
                                }
                            }
                        }

                        is AppThemeStoreState.Ready -> {
                            if (state.saveFailed) {
                                TextButton(onClick = store::retrySave) {
                                    Text(stringResource(R.string.app_theme_retry_save))
                                }
                            }
                            TrueBlackSwitchRow(
                                checked = state.preferences.trueBlack,
                                onCheckedChange = store::setTrueBlack
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

@Composable
private fun TrueBlackSwitchRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    role = Role.Switch,
                    onValueChange = onCheckedChange
                )
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.app_theme_true_black),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = stringResource(R.string.app_theme_true_black_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}
