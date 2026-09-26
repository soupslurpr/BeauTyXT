/* Applies transient Find decoration to both source presentation paths. */
package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import dev.soupslurpr.beautyxt.document.Utf16Range

/** Keeps only the coverage belonging to this query, revision, and displayed source. */
@Composable
internal fun rememberFindHighlights(
    session: EditorSession,
    revision: Long,
    range: Utf16Range
): List<TextRange> {
    if (!session.isFindVisible || session.state.metrics?.revision != revision) return emptyList()
    return session.findResults.asSequence().filter { it.representation == SearchRepresentation.Source }
        .mapNotNull { result ->
            val matched = result.source ?: return@mapNotNull null
            val start = maxOf(matched.start, range.start)
            val end = minOf(matched.end, range.end)
            if (end <= start) null else TextRange(Math.toIntExact(start - range.start), Math.toIntExact(end - range.start))
        }.toList()
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
