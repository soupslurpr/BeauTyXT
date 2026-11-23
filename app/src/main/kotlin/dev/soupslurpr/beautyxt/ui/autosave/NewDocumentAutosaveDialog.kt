/* Presents the opt-in new-document autosave settings dialog on Home. */
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.soupslurpr.beautyxt.ui.autosave

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.document.DocumentFormat
import dev.soupslurpr.beautyxt.ui.asString
import dev.soupslurpr.beautyxt.ui.designsystem.SingleChoiceButtons
import dev.soupslurpr.beautyxt.ui.designsystem.SingleChoiceOption

/** Result of Android's folder picker including the offered grant mode flags. */
internal data class SelectedAutosaveFolderResult(
    val encodedUri: String,
    val offeredRead: Boolean,
    val offeredWrite: Boolean,
    val offeredPersistable: Boolean,
    val offeredPrefix: Boolean
)

/** Launches SAF folder selection while preserving the returned Intent grant flags. */
internal class SelectAutosaveFolderContract :
    ActivityResultContract<Unit, SelectedAutosaveFolderResult?>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)

    override fun parseResult(resultCode: Int, intent: Intent?): SelectedAutosaveFolderResult? {
        if (resultCode != Activity.RESULT_OK || intent == null) {
            return null
        }
        val uri = intent.data ?: return null
        val flags = intent.flags
        return SelectedAutosaveFolderResult(
            encodedUri = uri.toString(),
            offeredRead = (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0,
            offeredWrite = (flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0,
            offeredPersistable = (flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0,
            offeredPrefix = (flags and Intent.FLAG_GRANT_PREFIX_URI_PERMISSION) != 0
        )
    }
}

/** Displays opt-in new-document autosave controls with explicit consent and recovery actions. */
@Composable
internal fun NewDocumentAutosaveDialog(
    store: NewDocumentAutosaveStore,
    state: NewDocumentAutosaveStoreState,
    pickerErrorMessage: String?,
    onChooseFolder: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var confirmSavedReset by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        store.refresh()
    }
    LaunchedEffect(state) {
        if (state != NewDocumentAutosaveStoreState.LoadFailed) {
            confirmSavedReset = false
        }
    }

    if (confirmSavedReset && state == NewDocumentAutosaveStoreState.LoadFailed) {
        val dismissReset = { confirmSavedReset = false }
        AlertDialog(
            onDismissRequest = dismissReset,
            modifier = modifier,
            title = { Text(stringResource(R.string.autosave_reset_saved_title)) },
            text = { Text(stringResource(R.string.autosave_reset_saved_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        dismissReset()
                        store.resetSaved()
                    }
                ) {
                    Text(stringResource(R.string.autosave_reset_saved_confirm))
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
        title = { Text(stringResource(R.string.autosave_dialog_title)) },
        text = {
            val ready = state as? NewDocumentAutosaveStoreState.Ready
            val errorMessage = when {
                pickerErrorMessage != null -> pickerErrorMessage
                state == NewDocumentAutosaveStoreState.LoadFailed ->
                    stringResource(R.string.autosave_load_failed)
                ready?.errorMessage != null -> ready.errorMessage.asString()
                ready?.saveFailed == true -> stringResource(R.string.autosave_save_failed)
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
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.autosave_dialog_summary),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    when (state) {
                        NewDocumentAutosaveStoreState.Loading -> {
                            Text(
                                text = stringResource(R.string.autosave_loading),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        NewDocumentAutosaveStoreState.LoadFailed -> {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                TextButton(onClick = store::retryLoad) {
                                    Text(stringResource(R.string.autosave_retry_load))
                                }
                                TextButton(onClick = { confirmSavedReset = true }) {
                                    Text(stringResource(R.string.autosave_reset_saved_action))
                                }
                            }
                        }

                        is NewDocumentAutosaveStoreState.Ready -> {
                            NewDocumentAutosaveReadyContent(
                                state = state,
                                onChooseFolder = onChooseFolder,
                                onSelectFormat = store::setFormat,
                                onTurnOff = store::disable
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
private fun NewDocumentAutosaveReadyContent(
    state: NewDocumentAutosaveStoreState.Ready,
    onChooseFolder: () -> Unit,
    onSelectFormat: (DocumentFormat) -> Unit,
    onTurnOff: () -> Unit
) {
    val controlsEnabled = !state.busy
    when (val status = state.status) {
        NewDocumentAutosaveStatus.Disabled -> {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.autosave_status_off),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = stringResource(R.string.autosave_status_off_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = stringResource(R.string.autosave_disclosure),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onChooseFolder,
                enabled = controlsEnabled,
                modifier = Modifier.fillMaxWidth(),
                shapes = ButtonDefaults.shapes()
            ) {
                Text(
                    stringResource(
                        if (state.busy) R.string.autosave_working else R.string.autosave_choose_folder
                    )
                )
            }
        }

        is NewDocumentAutosaveStatus.Enabled -> {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.autosave_status_on_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = status.folderName
                            ?: stringResource(R.string.autosave_selected_folder_fallback),
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.autosave_format_label),
                    style = MaterialTheme.typography.labelLarge
                )
                SingleChoiceButtons(
                    choices = listOf(
                        SingleChoiceOption(
                            label = stringResource(R.string.autosave_format_plain_text),
                            selected = state.format == DocumentFormat.PlainText,
                            enabled = controlsEnabled,
                            onClick = { onSelectFormat(DocumentFormat.PlainText) }
                        ),
                        SingleChoiceOption(
                            label = stringResource(R.string.autosave_format_markdown),
                            selected = state.format == DocumentFormat.Markdown,
                            enabled = controlsEnabled,
                            onClick = { onSelectFormat(DocumentFormat.Markdown) }
                        )
                    )
                )
            }
            Text(
                text = stringResource(R.string.autosave_disclosure),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.hasActiveLeases) {
                Text(
                    text = stringResource(R.string.autosave_lease_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(
                onClick = onChooseFolder,
                enabled = controlsEnabled && !state.hasActiveLeases,
                modifier = Modifier.fillMaxWidth(),
                shapes = ButtonDefaults.shapes()
            ) {
                Text(
                    stringResource(
                        if (state.busy) R.string.autosave_working else R.string.autosave_change_folder
                    )
                )
            }
            TextButton(
                onClick = onTurnOff,
                enabled = controlsEnabled && !state.hasActiveLeases,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.autosave_turn_off))
            }
        }

        is NewDocumentAutosaveStatus.NeedsReselection -> {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ) {
                Text(
                    text = stringResource(
                        when (status.reason) {
                            NewDocumentAutosaveUnavailableReason.RevokedOrMissing ->
                                R.string.autosave_folder_lost

                            NewDocumentAutosaveUnavailableReason.InterruptedChange ->
                                R.string.autosave_folder_interrupted

                            NewDocumentAutosaveUnavailableReason.TransientUnavailable ->
                                R.string.autosave_folder_unavailable
                        }
                    ),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Button(
                onClick = onChooseFolder,
                enabled = controlsEnabled,
                modifier = Modifier.fillMaxWidth(),
                shapes = ButtonDefaults.shapes()
            ) {
                Text(
                    stringResource(
                        if (state.busy) R.string.autosave_working else R.string.autosave_choose_folder
                    )
                )
            }
            TextButton(
                onClick = onTurnOff,
                enabled = controlsEnabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.autosave_turn_off))
            }
        }
    }
}
