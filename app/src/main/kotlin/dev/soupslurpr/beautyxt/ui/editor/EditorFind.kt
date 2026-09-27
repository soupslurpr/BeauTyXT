/* Defines Find status, input focus, and document-location controls. */
package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.ui.UiText
import dev.soupslurpr.beautyxt.ui.asString

private val FindSingleRowMinWidth = 360.dp

/** Moves Find actions below the query when its text would be crowded. */
internal fun usesCompactFindLayout(availableWidth: Dp, fontScale: Float): Boolean {
    require(availableWidth >= 0.dp) { "Find width must not be negative" }
    require(fontScale.isFinite() && fontScale > 0f) { "font scale must be positive and finite" }
    return availableWidth < FindSingleRowMinWidth * maxOf(1f, fontScale)
}

/** Returns the concise accessible status for one retained Find result. */
internal fun findStatusMessage(status: FindStatus, matchCase: Boolean = false): UiText =
    when (status) {
        FindStatus.Idle -> UiText.Resource(
            if (matchCase) R.string.find_case_enabled else R.string.find_ignores_case
        )

        FindStatus.Searching -> UiText.Resource(R.string.find_searching)

        FindStatus.NoMatches -> UiText.Resource(R.string.find_no_matches)

        is FindStatus.Failed -> status.message

        is FindStatus.Match -> {
            val displayLine = Math.incrementExact(status.match.start.line)
            UiText.Resource(
                when (status.wrappedAt) {
                    FindWrap.Beginning -> R.string.find_wrapped_beginning
                    FindWrap.End -> R.string.find_wrapped_end
                    null -> R.string.find_match_line
                },
                listOf(displayLine)
            )
        }
    }

internal enum class FindInputFocus { Document, Query, Replacement, Results }

internal enum class FindResultsPage { Matches, Replacements }

@Composable
internal fun findDomainScopeText(session: EditorSession): String = stringResource(R.string.find_domain_scope,
    stringResource(if (session.presentation == EditorPresentation.Text) R.string.find_source_domain else R.string.find_reading_domain),
    stringResource(if (session.isFindSelectionScope) R.string.find_selection_scope else R.string.find_document_scope))

@Composable
internal fun findCountText(session: EditorSession): String = when {
    session.findFieldValue.text.isEmpty() -> pluralStringResource(R.plurals.find_total_matches, 0, 0)
    session.findStatus == FindStatus.Searching && session.findResults.isEmpty() -> stringResource(R.string.find_searching)
    !session.isFindComplete -> pluralStringResource(R.plurals.find_partial_matches, session.findResults.size, session.findResults.size)
    session.findResultIndex >= 0 -> pluralStringResource(R.plurals.find_match_count, session.findResults.size, session.findResultIndex + 1, session.findResults.size)
    else -> pluralStringResource(R.plurals.find_total_matches, session.findResults.size, session.findResults.size)
}

internal fun insertQueryLineBreak(session: EditorSession) {
    val value = session.findFieldValue
    val start = value.selection.min
    val text = value.text.replaceRange(start, value.selection.max, "\n")
    session.updateFindFieldValue(TextFieldValue(text, TextRange(start + 1)))
}

/** Keeps skipped-location feedback visible after the navigation menu closes. */
@Composable
internal fun EditorLocationFeedback(session: EditorSession) {
    session.locationMessage?.let {
        Text(it.asString(), Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall)
    }
}
