/* Applies document preferences without changing source text or UI typography. */
package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

internal val LocalDocumentAppearances = staticCompositionLocalOf { DocumentAppearances() }

/** Resolved by the Android renderer, kept explicit for pure annotation/layout operations. */
internal data class MarkdownEmphasis(
    val bodyWeight: Int = 400,
    val strongWeight: Int = 700,
    val underline: Boolean = false,
    val underlineItalic: Boolean = false
)

/** Six distinct Material sizes keep the heading hierarchy without shrinking H6. */
internal fun Typography.markdownHeadingStyle(level: Int): TextStyle = when (level) {
    1 -> headlineLarge
    2 -> headlineMedium
    3 -> headlineSmall
    4 -> titleLarge
    5 -> titleMedium
    else -> titleSmall
}.copy(letterSpacing = 0.sp)

internal fun appearanceFont(value: Float): FontFamily = when (value.toInt()) {
    1 -> FontFamily.Serif
    2 -> FontFamily.Monospace
    else -> FontFamily.Default
}

internal fun TextAppearance.sourceStyle(base: TextStyle): TextStyle = base.copy(
    fontFamily = appearanceFont(value(AppearanceOption.Font, AppearanceGroup.Source)),
    fontSize = value(AppearanceOption.Size, AppearanceGroup.Source).sp,
    fontWeight = FontWeight(value(AppearanceOption.Weight, AppearanceGroup.Source).toInt()),
    lineHeight = value(AppearanceOption.LineSpacing, AppearanceGroup.Source).em,
    letterSpacing = (value(AppearanceOption.LetterSpacing, AppearanceGroup.Source) / 100f).em
)

@Composable
internal fun sourceAppearanceStyle(): TextStyle =
    LocalDocumentAppearances.current.source.sourceStyle(MaterialTheme.typography.bodyLarge)

internal enum class MarkdownTextRole(val font: AppearanceOption) {
    Body(AppearanceOption.Font), Heading(AppearanceOption.HeadingFont), Code(AppearanceOption.CodeFont)
}

/** Retains each Material role's proportions while body size and heading scale change. */
internal fun TextAppearance.markdownStyle(base: TextStyle, role: MarkdownTextRole = MarkdownTextRole.Body): TextStyle {
    val group = AppearanceGroup.Markdown
    val bodySize = value(AppearanceOption.Size, group)
    val scale = when (role) {
        MarkdownTextRole.Heading -> value(AppearanceOption.HeadingScale, group) / 100f
        MarkdownTextRole.Code -> value(AppearanceOption.CodeScale, group) / 100f
        MarkdownTextRole.Body -> 1f
    }
    val size = base.fontSize.value * bodySize / 18f * scale
    val weight = value(AppearanceOption.Weight, group).toInt()
    val lineRatio = base.lineHeight.value / base.fontSize.value *
        value(AppearanceOption.LineSpacing, group) / AppearanceOption.LineSpacing.markdownDefault
    return base.copy(
        fontFamily = appearanceFont(value(role.font, group)),
        fontSize = size.sp,
        lineHeight = lineRatio.coerceAtLeast(1f).em,
        fontWeight = FontWeight(if (role == MarkdownTextRole.Heading) (weight + 300).coerceAtMost(1000) else weight),
        letterSpacing = (value(AppearanceOption.LetterSpacing, group) / 100f).em
    )
}

internal fun TextAppearance.columnWidth(group: AppearanceGroup): Dp =
    if (value(AppearanceOption.FullWidth, group) == 1f) Dp.Infinity
    else value(AppearanceOption.ColumnWidth, group).dp

internal data class DocumentTextColumn(val textStart: Dp, val textWidth: Dp, val gutter: Dp)

/** All distances are relative to the layout's start edge, including in RTL. */
internal fun TextAppearance.textColumn(group: AppearanceGroup, width: Dp, lineNumberGutter: Dp = 0.dp): DocumentTextColumn {
    val margin = minOf(value(AppearanceOption.Margin, group).dp, ((width - 96.dp) / 2).coerceAtLeast(0.dp))
    val available = (width - margin * 2).coerceAtLeast(0.dp)
    val gutter = if (usesLineNumberGutter(available, lineNumberGutter)) lineNumberGutter else 0.dp
    val textWidth = minOf(columnWidth(group), (available - gutter).coerceAtLeast(0.dp))
    return DocumentTextColumn(maxOf(margin + gutter, (width - textWidth) / 2), textWidth, gutter)
}

/** Extra advance belongs to existing spaces; offsets and authored characters stay intact. */
internal fun appearanceSpaceStyle(appearance: TextAppearance, group: AppearanceGroup): SpanStyle =
    SpanStyle(letterSpacing = ((appearance.value(AppearanceOption.LetterSpacing, group) +
        appearance.value(AppearanceOption.WordSpacing, group)) / 100f).em)

internal fun spacedDocumentText(text: AnnotatedString, appearance: TextAppearance, group: AppearanceGroup): AnnotatedString {
    if (appearance.value(AppearanceOption.WordSpacing, group) == 0f) return text
    val style = appearanceSpaceStyle(appearance, group)
    return AnnotatedString.Builder(text).apply {
        text.text.forEachIndexed { index, char ->
            if (char == ' ') addStyle(style, index, index + 1)
        }
    }.toAnnotatedString()
}

/** Expands the advance of a single display space, keeping tab offsets one-to-one. */
internal fun tabDisplayStyle(width: Float): SpanStyle =
    SpanStyle(textGeometricTransform = TextGeometricTransform(scaleX = width))

/** Preserves authored grapheme boundaries when replacing a tab with a space would merge a cluster. */
internal fun tabCanDisplayAsSpace(
    text: CharSequence,
    index: Int,
    breakIterator: () -> java.text.BreakIterator = { java.text.BreakIterator.getCharacterInstance(java.util.Locale.ROOT) }
): Boolean {
    val hasPrev = index > 0 && text[index - 1] >= '\u0300'
    val hasNext = index + 1 < text.length && text[index + 1] >= '\u0300'
    if (!hasPrev && !hasNext) return true
    val start = when {
        index >= 2 && Character.isHighSurrogate(text[index - 2]) && Character.isLowSurrogate(text[index - 1]) -> index - 2
        index > 0 -> index - 1
        else -> 0
    }
    val end = when {
        index + 2 < text.length && Character.isHighSurrogate(text[index + 1]) && Character.isLowSurrogate(text[index + 2]) -> index + 3
        index + 1 < text.length -> index + 2
        else -> text.length
    }
    val window = buildString(end - start) {
        append(text, start, index)
        append(' ')
        append(text, index + 1, end)
    }
    val spaceIndex = index - start
    val characters = breakIterator()
    characters.setText(window)
    return characters.isBoundary(spaceIndex) && characters.isBoundary(spaceIndex + 1)
}

internal fun sourceDisplayText(text: AnnotatedString, appearance: TextAppearance): AnnotatedString {
    val raw = text.text
    val wordSpacing = appearance.value(AppearanceOption.WordSpacing, AppearanceGroup.Source) != 0f
    val hasTab = '\t' in raw
    if (!wordSpacing && !hasTab) return text
    val spaceStyle = if (wordSpacing) appearanceSpaceStyle(appearance, AppearanceGroup.Source) else null
    val tabStyle = if (hasTab) tabDisplayStyle(appearance.value(AppearanceOption.TabWidth, AppearanceGroup.Source)) else null
    var display: CharArray? = null
    var cachedBreakIterator: java.text.BreakIterator? = null
    val getBreakIterator = {
        cachedBreakIterator ?: java.text.BreakIterator.getCharacterInstance(java.util.Locale.ROOT)
            .also { cachedBreakIterator = it }
    }
    val spans = ArrayList<AnnotatedString.Range<SpanStyle>>(text.spanStyles.size)
    spans.addAll(text.spanStyles)
    for (index in raw.indices) {
        when (raw[index]) {
            ' ' -> if (spaceStyle != null) spans.add(AnnotatedString.Range(spaceStyle, index, index + 1))
            '\t' -> if (tabStyle != null && tabCanDisplayAsSpace(raw, index, getBreakIterator)) {
                val chars = display ?: raw.toCharArray().also { display = it }
                chars[index] = ' '
                spans.add(AnnotatedString.Range(tabStyle, index, index + 1))
            }
        }
    }
    if (display == null && spans.size == text.spanStyles.size) return text
    return AnnotatedString(
        text = display?.concatToString() ?: raw,
        spanStyles = spans,
        paragraphStyles = text.paragraphStyles
    )
}

/** Output-only styling preserves TextFieldState, undo, clipboard and native source bytes. */
internal fun sourceAppearanceTransformation(
    appearance: TextAppearance,
    clipboardSelection: SourceClipboardSelection,
    expectedText: String,
    highlights: List<TextRange>,
    current: TextRange?,
    styles: FindHighlightStyles
): OutputTransformation = OutputTransformation {
    val raw = originalText
    clipboardSelection.capture(raw, originalSelection)
    val decorateFind = (highlights.isNotEmpty() || current != null) && raw.contentEquals(expectedText)
    val group = AppearanceGroup.Source
    var firstTab = -1
    var lastTab = -1
    var tabCount = 0
    var skippedTabs: HashSet<Int>? = null
    var cachedBreakIterator: java.text.BreakIterator? = null
    val getBreakIterator = {
        cachedBreakIterator ?: java.text.BreakIterator.getCharacterInstance(java.util.Locale.ROOT)
            .also { cachedBreakIterator = it }
    }
    for (index in raw.indices) {
        if (raw[index] == '\t') {
            if (tabCanDisplayAsSpace(raw, index, getBreakIterator)) {
                if (tabCount == 0) firstTab = index
                lastTab = index
                tabCount++
            } else {
                (skippedTabs ?: HashSet<Int>().also { skippedTabs = it }).add(index)
            }
        }
    }
    val skipped = skippedTabs
    if (tabCount > 32) {
        // Adjacent edits merge into one tracked change. Replacing one UTF-16 unit
        // at a time preserves every cursor position, including those between tabs.
        val character = StringBuilder(" ")
        for (index in firstTab..lastTab) {
            val source = raw[index]
            character[0] = if (source == '\t' && (skipped == null || index !in skipped)) ' ' else source
            replace(index, index + 1, character)
        }
    } else if (tabCount > 0) {
        for (index in firstTab..lastTab) {
            if (raw[index] == '\t' && (skipped == null || index !in skipped)) {
                replace(index, index + 1, " ")
            }
        }
    }
    val wordSpacing = appearance.value(AppearanceOption.WordSpacing, group) != 0f
    if (tabCount > 0 || wordSpacing) {
        val spaceStyle = appearanceSpaceStyle(appearance, group)
        val tabStyle = tabDisplayStyle(appearance.value(AppearanceOption.TabWidth, group))
        for (index in raw.indices) {
            when (raw[index]) {
                '\t' -> if (skipped == null || index !in skipped) addStyle(tabStyle, index, index + 1)
                ' ' -> if (wordSpacing) addStyle(spaceStyle, index, index + 1)
            }
        }
    }
    if (decorateFind) {
        highlights.forEach { addStyle(styles.other, it.start, it.end) }
        current?.let { addStyle(styles.current, it.start, it.end) }
    }
}

/** Paragraph gaps track the displayed body size, including system font scaling. */
@Composable
internal fun markdownParagraphGap(): Dp {
    val appearance = LocalDocumentAppearances.current.markdown
    return with(androidx.compose.ui.platform.LocalDensity.current) {
        appearance.value(AppearanceOption.Size, AppearanceGroup.Markdown).sp.toDp() *
            appearance.value(AppearanceOption.ParagraphSpacing, AppearanceGroup.Markdown)
    }
}

/** Tab presentation substitutes one character without changing source-position mapping. */
internal fun sourcePresentationMatches(display: CharSequence, source: CharSequence): Boolean =
    display.length == source.length && source.indices.all { index ->
        display[index] == source[index] || source[index] == '\t' && display[index] == ' '
    }

/** The previous committed anchor belongs to the layout before the next remeasure. */
private class CommittedAppearance(var appearance: TextAppearance, var anchor: SemanticViewportAnchor?)

@Composable
internal fun RestoreAfterAppearanceChange(session: EditorSession, presentation: EditorPresentation, revision: Long,
    effective: TextAppearance, visible: SemanticViewportAnchor?) {
    val committed = remember(session, presentation, revision) { CommittedAppearance(effective, visible) }
    SideEffect {
        if (committed.appearance != effective) {
            (committed.anchor ?: visible)?.let { session.restorePositionAfterAppearanceChange(presentation, it) }
            committed.appearance = effective
        } else if (visible != null) {
            val pending = when (presentation) {
                EditorPresentation.Text -> session.readOnlySourceScrollRestoration
                EditorPresentation.MarkdownPreview -> (session.markdownPreviewStatus as? MarkdownPreviewStatus.Ready)?.scrollRestoration
            }
            if (pending == null) committed.anchor = visible
        }
    }
}
