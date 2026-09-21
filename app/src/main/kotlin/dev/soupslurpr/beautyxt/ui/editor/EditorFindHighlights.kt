/* Applies transient Find decoration to both source presentation paths. */
package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import dev.soupslurpr.beautyxt.document.FindHighlightRequest
import dev.soupslurpr.beautyxt.document.Utf16Range

/** Keeps only the coverage belonging to this query, revision, and displayed source. */
@Composable
internal fun rememberFindHighlights(
    session: EditorSession,
    revision: Long,
    range: Utf16Range
): List<TextRange> {
    val query = session.findFieldValue.text.takeIf { session.isFindVisible }.orEmpty()
    val matchCase = session.isFindCaseSensitive
    val request = remember(revision, range, query, matchCase) {
        if (query.isEmpty() || range.start == range.end) null else {
            FindHighlightRequest(revision, query, matchCase, range)
        }
    }
    var highlights by remember(session, request) { mutableStateOf(emptyList<TextRange>()) }
    val ready = session.state.status == EditorDocumentStatus.Ready
    LaunchedEffect(session, request, ready) {
        if (request != null && ready) {
            highlights = session.state.findHighlights(request).map { match ->
                TextRange(
                    Math.toIntExact(match.start - range.start),
                    Math.toIntExact(match.end - range.start)
                )
            }
        }
    }
    return highlights
}

/** Shares the same theme-aware hierarchy between selectable and editable source text. */
internal data class FindHighlightStyles(val other: SpanStyle, val current: SpanStyle)

@Composable
internal fun findHighlightStyles(): FindHighlightStyles {
    val colors = MaterialTheme.colorScheme
    return FindHighlightStyles(
        other = SpanStyle(
            color = colors.onSecondaryContainer,
            background = colors.secondaryContainer
        ),
        current = SpanStyle(
            color = colors.onPrimary,
            background = colors.primary,
            textDecoration = TextDecoration.Underline
        )
    )
}

/** Decorates source without changing characters, offsets, or the current selection. */
internal fun findHighlightedText(
    text: String,
    highlights: List<TextRange>,
    current: TextRange?,
    styles: FindHighlightStyles
): AnnotatedString = buildAnnotatedString {
    append(text)
    highlights.forEach { range -> addStyle(styles.other, range.start, range.end) }
    current?.let { range -> addStyle(styles.current, range.start, range.end) }
}

/** Adds visual spans only; the draft, clipboard, and undo history keep plain source text. */
internal fun findHighlightTransformation(
    expectedText: String,
    highlights: List<TextRange>,
    current: TextRange?,
    styles: FindHighlightStyles
): OutputTransformation = OutputTransformation {
    if (asCharSequence().contentEquals(expectedText)) {
        highlights.forEach { range -> addStyle(styles.other, range.start, range.end) }
        current?.let { range -> addStyle(styles.current, range.start, range.end) }
    }
}
