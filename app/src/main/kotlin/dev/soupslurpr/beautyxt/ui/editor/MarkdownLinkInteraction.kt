package dev.soupslurpr.beautyxt.ui.editor

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.markdown.MarkdownExternalAction
import dev.soupslurpr.beautyxt.markdown.MarkdownLinkAction
import dev.soupslurpr.beautyxt.markdown.MarkdownLinkUnavailableReason
import dev.soupslurpr.beautyxt.ui.UiText
import dev.soupslurpr.beautyxt.ui.asString

/** Describes one Markdown link decision that requires visible user feedback. */
internal sealed interface MarkdownLinkDialogState {
    /** Requests approval before Android receives an unfamiliar URI scheme. */
    data class ConfirmExternal(val link: MarkdownLinkAction.External) : MarkdownLinkDialogState

    /** Reports that no installed activity accepted one external link. */
    data class NoHandler(val destination: String) : MarkdownLinkDialogState

    /** Reports one destination intentionally withheld from Android. */
    data class Unavailable(val link: MarkdownLinkAction.Unavailable) : MarkdownLinkDialogState

    /** Reports one same-document fragment without a matching rendered heading. */
    data class MissingHeading(val fragment: String) : MarkdownLinkDialogState

    /** Reports one footnote reference without a rendered local definition. */
    data class MissingFootnote(val label: String) : MarkdownLinkDialogState
}

/** Opens one already-classified Markdown link without granting URI permissions. */
internal fun launchMarkdownExternalLink(
    context: Context,
    link: MarkdownLinkAction.External
): Boolean {
    val intent = markdownExternalLinkIntent(context, link) ?: return false
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }
}

/** Creates one implicit, permission-free Android intent for a classified Markdown link. */
internal fun markdownExternalLinkIntent(
    context: Context,
    link: MarkdownLinkAction.External
): Intent? {
    val uri = link.destination.toUri()
    if (!uri.scheme.equals(link.scheme, ignoreCase = true)) {
        return null
    }
    val action =
        when (link.action) {
            MarkdownExternalAction.View -> Intent.ACTION_VIEW
            MarkdownExternalAction.Dial -> Intent.ACTION_DIAL
            MarkdownExternalAction.SendTo -> Intent.ACTION_SENDTO
        }
    val intent = Intent(action, uri)
    if (link.action == MarkdownExternalAction.View) {
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
    }
    if (context !is Activity) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return intent
}

/** Displays the exact destination and consequence of one Markdown link decision. */
@Composable
internal fun MarkdownLinkDialog(
    state: MarkdownLinkDialogState,
    onConfirmExternal: (MarkdownLinkAction.External) -> Unit,
    onDismiss: () -> Unit
) {
    val confirmation = state as? MarkdownLinkDialogState.ConfirmExternal
    val title = markdownLinkDialogTitle(state).asString()
    val explanation = markdownLinkDialogExplanation(state).asString()
    val destination = markdownLinkDialogDestination(state).ifEmpty {
        stringResource(R.string.link_empty)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(explanation)
                SelectionContainer {
                    Text(
                        text = destination,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        },
        confirmButton = {
            if (confirmation == null) {
                Button(onClick = onDismiss) {
                    Text(stringResource(R.string.action_done))
                }
            } else {
                Button(onClick = { onConfirmExternal(confirmation.link) }) {
                    Text(stringResource(R.string.action_open))
                }
            }
        },
        dismissButton = {
            if (confirmation != null) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    )
}

/** Returns the concise title for one Markdown link dialog. */
private fun markdownLinkDialogTitle(state: MarkdownLinkDialogState): UiText = when (state) {
    is MarkdownLinkDialogState.ConfirmExternal -> UiText.Resource(R.string.link_open_title)

    is MarkdownLinkDialogState.MissingHeading -> UiText.Resource(
        R.string.link_heading_missing_title
    )

    is MarkdownLinkDialogState.MissingFootnote -> UiText.Resource(
        R.string.link_footnote_missing_title
    )

    is MarkdownLinkDialogState.NoHandler -> UiText.Resource(R.string.link_no_handler_title)

    is MarkdownLinkDialogState.Unavailable ->
        when (state.link.reason) {
            MarkdownLinkUnavailableReason.BlockedScheme -> UiText.Resource(
                R.string.link_blocked_title
            )

            MarkdownLinkUnavailableReason.Invalid -> UiText.Resource(
                R.string.link_unavailable_title
            )

            MarkdownLinkUnavailableReason.RelativeDocument -> UiText.Resource(
                R.string.link_relative_title
            )
        }
}

/** Returns the consequence explanation for one Markdown link dialog. */
private fun markdownLinkDialogExplanation(state: MarkdownLinkDialogState): UiText = when (state) {
    is MarkdownLinkDialogState.ConfirmExternal ->
        UiText.Resource(R.string.link_external_description, listOf(state.link.scheme))

    is MarkdownLinkDialogState.MissingHeading ->
        UiText.Resource(R.string.link_heading_missing_description)

    is MarkdownLinkDialogState.MissingFootnote ->
        UiText.Resource(R.string.link_footnote_missing_description)

    is MarkdownLinkDialogState.NoHandler ->
        UiText.Resource(R.string.link_no_handler_description)

    is MarkdownLinkDialogState.Unavailable ->
        when (state.link.reason) {
            MarkdownLinkUnavailableReason.BlockedScheme ->
                UiText.Resource(
                    R.string.link_blocked_description,
                    listOf(state.link.scheme.orEmpty())
                )

            MarkdownLinkUnavailableReason.Invalid ->
                UiText.Resource(R.string.link_invalid_description)

            MarkdownLinkUnavailableReason.RelativeDocument ->
                UiText.Resource(R.string.link_relative_description)
        }
}

/** Returns the exact destination shown in one Markdown link dialog. */
private fun markdownLinkDialogDestination(state: MarkdownLinkDialogState): String = when (state) {
    is MarkdownLinkDialogState.ConfirmExternal -> state.link.destination
    is MarkdownLinkDialogState.MissingHeading -> "#${state.fragment}"
    is MarkdownLinkDialogState.MissingFootnote -> "[^${state.label}]"
    is MarkdownLinkDialogState.NoHandler -> state.destination
    is MarkdownLinkDialogState.Unavailable -> state.link.destination
}
