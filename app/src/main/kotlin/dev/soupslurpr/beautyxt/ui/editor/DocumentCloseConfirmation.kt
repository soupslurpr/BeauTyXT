package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.soupslurpr.beautyxt.R

/** Presents close choices without owning the session's save or navigation policy. */
@Composable
internal fun DocumentCloseConfirmation(session: EditorSession, onClose: () -> Unit) {
    val untouched = session.isUneditedReceivedContent
    val message = when {
        untouched -> R.string.close_received_message
        session.sourceSaveStatus is SourceSaveStatus.Uncertain -> R.string.close_uncertain_message
        session.sourceSaveStatus is SourceSaveStatus.Conflict -> R.string.close_conflict_message
        session.sourceSaveStatus == SourceSaveStatus.NoSource -> R.string.close_new_message
        else -> R.string.close_unsaved_message
    }
    AlertDialog(
        onDismissRequest = session::dismissDiscardConfirmation,
        title = {
            Text(
                stringResource(
                    if (untouched) R.string.close_received_title else R.string.close_unsaved_title
                )
            )
        },
        text = { Text(stringResource(message), Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { session.saveBeforeClose() },
                    enabled = session.canSaveBeforeClose
                ) {
                    Text(stringResource(R.string.action_save))
                }
                Button(onClick = session::dismissDiscardConfirmation) {
                    Text(
                        stringResource(
                            if (untouched ||
                                session.presentation == EditorPresentation.MarkdownPreview
                            ) {
                                R.string.action_keep_reading
                            } else {
                                R.string.action_keep_editing
                            }
                        )
                    )
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onClose,
                enabled = session.canCloseSafely,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(
                    stringResource(
                        if (untouched) {
                            R.string.action_close_without_saving
                        } else {
                            R.string.action_discard_and_close
                        }
                    )
                )
            }
        }
    )
}
