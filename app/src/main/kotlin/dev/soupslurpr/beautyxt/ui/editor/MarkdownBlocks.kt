/* Renders inert Markdown blocks and accessible inline interactions. */
package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.then
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.illustration.IllustrationKind
import dev.soupslurpr.beautyxt.illustration.IllustrationResult
import dev.soupslurpr.beautyxt.illustration.authoredDescription
import dev.soupslurpr.beautyxt.illustration.illustrationFallbackMessages
import dev.soupslurpr.beautyxt.markdown.MARKDOWN_CHECKED_TASK_PREFIX
import dev.soupslurpr.beautyxt.markdown.MARKDOWN_SPAN_STYLE_CODE
import dev.soupslurpr.beautyxt.markdown.MARKDOWN_SPAN_STYLE_EMPHASIS
import dev.soupslurpr.beautyxt.markdown.MARKDOWN_SPAN_STYLE_MATH
import dev.soupslurpr.beautyxt.markdown.MARKDOWN_SPAN_STYLE_STRIKETHROUGH
import dev.soupslurpr.beautyxt.markdown.MARKDOWN_SPAN_STYLE_STRONG
import dev.soupslurpr.beautyxt.markdown.MARKDOWN_SPAN_STYLE_SUBSCRIPT
import dev.soupslurpr.beautyxt.markdown.MARKDOWN_SPAN_STYLE_SUPERSCRIPT
import dev.soupslurpr.beautyxt.markdown.MARKDOWN_UNCHECKED_TASK_PREFIX
import dev.soupslurpr.beautyxt.markdown.MarkdownBlockKind
import dev.soupslurpr.beautyxt.markdown.MarkdownInlineDestinationKind
import dev.soupslurpr.beautyxt.markdown.MarkdownInlineSpan
import dev.soupslurpr.beautyxt.markdown.MarkdownQuoteKind
import dev.soupslurpr.beautyxt.markdown.MarkdownRenderBlock
import dev.soupslurpr.beautyxt.markdown.MarkdownTableAlignment
import dev.soupslurpr.beautyxt.markdown.markdownFootnotePresentation
import dev.soupslurpr.beautyxt.markdown.markdownInlinePresentation
import dev.soupslurpr.beautyxt.markdown.standaloneDisplayIllustration
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private const val MAX_MARKDOWN_TABLE_COLUMNS = 16

private val EmptyMarkdownTableCell = AnnotatedString("")

private val MinimumMarkdownTableCellWidth = 112.dp

private val MarkdownTableDividerWidth = 1.dp

private val MarkdownListMarkerWidth = 24.dp

private val MarkdownMaximumIndent = 60.dp

private val MarkdownNestedIndent = 12.dp

private val MarkdownQuoteBarWidth = 4.dp

/** Displays one bounded GFM alert as a single semantic Material surface. */
@Composable
internal fun MarkdownQuoteAlert(
    blocks: List<MarkdownRenderBlock>,
    firstBlockIndex: Int,
    startsWrappedPreview: Boolean,
    onInlineInteraction: (MarkdownInlineInteraction) -> Unit,
    canReturnToFootnoteReference: (MarkdownRenderBlock) -> Boolean,
    modifier: Modifier = Modifier
) {
    require(blocks.isNotEmpty()) { "Markdown alert must contain at least one block" }
    require(firstBlockIndex >= 0) { "Markdown alert block index must be nonnegative" }
    val kind = requireNotNull(blocks.first().quoteKind)
    require(blocks.all { block -> block.quoteKind == kind }) {
        "Markdown alert blocks must have one kind"
    }
    val baseQuoteDepth = blocks.minOf(MarkdownRenderBlock::quoteDepth)
    val colors = markdownQuoteColors(kind)
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = colors.container,
        contentColor = colors.content
    ) {
        MarkdownQuoteBody(quoteDepth = baseQuoteDepth, accent = colors.accent) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                if (blocks.first().startsQuoteAlert) {
                    Text(
                        text = markdownQuoteLabel(kind),
                        color = colors.accent,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(EditorCompactSpacing))
                }
                blocks.forEachIndexed { blockIndex, block ->
                    if (blockIndex != 0 && !block.continuesPrevious) {
                        Spacer(Modifier.height(EditorSectionSpacing))
                    }
                    val quoteIndent =
                        markdownNestedIndent((block.quoteDepth - baseQuoteDepth).coerceAtLeast(0))
                    val listIndent =
                        markdownNestedIndent((block.listDepth - 1).coerceAtLeast(0))
                    CompositionLocalProvider(
                        LocalMarkdownPreviewBlockIndex provides
                            Math.addExact(firstBlockIndex, blockIndex)
                    ) {
                        MarkdownBlockContent(
                            block = block,
                            showWrappedPreview =
                                if (blockIndex == 0) {
                                    startsWrappedPreview
                                } else {
                                    markdownHasWrappedPreview(
                                        blocks = listOf(block),
                                        precedingBlock = blocks[blockIndex - 1]
                                    )
                                },
                            onInlineInteraction = onInlineInteraction,
                            canReturnToFootnoteReference = canReturnToFootnoteReference(block),
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = quoteIndent + listIndent)
                        )
                    }
                }
            }
        }
    }
}

/** Displays one block with bounded list and quotation indentation. */
@Composable
internal fun MarkdownPreviewBlock(
    block: MarkdownRenderBlock,
    onInlineInteraction: (MarkdownInlineInteraction) -> Unit,
    modifier: Modifier = Modifier,
    canReturnToFootnoteReference: Boolean = false,
    showWrappedPreview: Boolean = false
) {
    val listIndent = markdownNestedIndent((block.listDepth - 1).coerceAtLeast(0))
    val content: @Composable () -> Unit = {
        MarkdownBlockContent(
            block = block,
            showWrappedPreview = showWrappedPreview,
            onInlineInteraction = onInlineInteraction,
            canReturnToFootnoteReference = canReturnToFootnoteReference,
            modifier = Modifier.padding(start = listIndent)
        )
    }
    if (block.quoteDepth == 0) {
        Box(
            modifier = modifier.then(
                if (block.kind == MarkdownBlockKind.Heading) {
                    Modifier.padding(top = EditorCompactSpacing)
                } else {
                    Modifier
                }
            )
        ) {
            content()
        }
        return
    }
    val quoteColors = markdownQuoteColors(block.quoteKind)
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = quoteColors.container,
        contentColor = quoteColors.content
    ) {
        MarkdownQuoteBody(quoteDepth = block.quoteDepth, accent = quoteColors.accent) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(EditorCompactSpacing)
            ) {
                if (block.startsQuoteAlert) {
                    Text(
                        text = markdownQuoteLabel(requireNotNull(block.quoteKind)),
                        color = quoteColors.accent,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
                content()
            }
        }
    }
}

/** Measures content first so the quote rail never queries subcomposed code or table intrinsics. */
@Composable
private fun MarkdownQuoteBody(quoteDepth: Int, accent: Color, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = markdownNestedIndent(quoteDepth - 1))
            .padding(vertical = EditorCompactSpacing)
    ) {
        Box(Modifier.matchParentSize()) {
            Surface(
                modifier = Modifier.width(MarkdownQuoteBarWidth).fillMaxHeight(),
                shape = MaterialTheme.shapes.extraSmall,
                color = accent
            ) {}
        }
        Box(
            modifier = Modifier.padding(
                start = MarkdownQuoteBarWidth + EditorHorizontalPadding,
                end = EditorHorizontalPadding
            )
        ) {
            content()
        }
    }
}

/** Holds semantic Material colors for one ordinary quote or GFM alert. */
private data class MarkdownQuoteColors(val container: Color, val content: Color, val accent: Color)

/** Returns Material color roles for one ordinary quote or GFM alert. */
@Composable
private fun markdownQuoteColors(kind: MarkdownQuoteKind?): MarkdownQuoteColors = when (kind) {
    null ->
        MarkdownQuoteColors(
            container = Color.Transparent,
            content = MaterialTheme.colorScheme.onSurface,
            accent = MaterialTheme.colorScheme.outlineVariant
        )

    MarkdownQuoteKind.Note ->
        MarkdownQuoteColors(
            container = MaterialTheme.colorScheme.surfaceContainer,
            content = MaterialTheme.colorScheme.onSurface,
            accent = MaterialTheme.colorScheme.primary
        )

    MarkdownQuoteKind.Tip ->
        MarkdownQuoteColors(
            container = MaterialTheme.colorScheme.surfaceContainer,
            content = MaterialTheme.colorScheme.onSurface,
            accent = MaterialTheme.colorScheme.tertiary
        )

    MarkdownQuoteKind.Important ->
        MarkdownQuoteColors(
            container = MaterialTheme.colorScheme.surfaceContainer,
            content = MaterialTheme.colorScheme.onSurface,
            accent = MaterialTheme.colorScheme.secondary
        )

    MarkdownQuoteKind.Warning ->
        MarkdownQuoteColors(
            container = MaterialTheme.colorScheme.surfaceContainer,
            content = MaterialTheme.colorScheme.onSurface,
            accent = MaterialTheme.colorScheme.tertiary
        )

    MarkdownQuoteKind.Caution ->
        MarkdownQuoteColors(
            container = MaterialTheme.colorScheme.surfaceContainer,
            content = MaterialTheme.colorScheme.onSurface,
            accent = MaterialTheme.colorScheme.error
        )
}

/** Returns the visible label for one GFM alert kind. */
@Composable
private fun markdownQuoteLabel(kind: MarkdownQuoteKind): String = when (kind) {
    MarkdownQuoteKind.Note -> stringResource(R.string.markdown_alert_note)
    MarkdownQuoteKind.Tip -> stringResource(R.string.markdown_alert_tip)
    MarkdownQuoteKind.Important -> stringResource(R.string.markdown_alert_important)
    MarkdownQuoteKind.Warning -> stringResource(R.string.markdown_alert_warning)
    MarkdownQuoteKind.Caution -> stringResource(R.string.markdown_alert_caution)
}

/** Selects the Material presentation for one Markdown block kind. */
@Composable
private fun MarkdownBlockContent(
    block: MarkdownRenderBlock,
    onInlineInteraction: (MarkdownInlineInteraction) -> Unit,
    canReturnToFootnoteReference: Boolean,
    modifier: Modifier = Modifier,
    showWrappedPreview: Boolean = false
) {
    if (block.illustrationContinuation) return
    when (block.kind) {
        MarkdownBlockKind.Paragraph ->
            MarkdownStyledText(
                block = block,
                style = markdownBodyStyle(),
                onInlineInteraction = onInlineInteraction,
                modifier = modifier.fillMaxWidth()
            )

        MarkdownBlockKind.Heading ->
            MarkdownStyledText(
                block = block,
                style = markdownHeadingStyle(block.headingLevel),
                onInlineInteraction = onInlineInteraction,
                modifier =
                    modifier
                        .fillMaxWidth()
                        .semantics { heading() },
                fontWeight = FontWeight.Bold
            )

        MarkdownBlockKind.Code ->
            MarkdownCodeBlock(
                block = block,
                showWrappedPreview = showWrappedPreview,
                onInlineInteraction = onInlineInteraction,
                modifier = modifier
            )

        MarkdownBlockKind.ListItem ->
            MarkdownListItem(
                block = block,
                onInlineInteraction = onInlineInteraction,
                modifier = modifier
            )

        MarkdownBlockKind.Rule -> MarkdownRule(block = block, modifier = modifier)

        MarkdownBlockKind.HtmlLiteral ->
            MarkdownHtmlLiteral(
                block = block,
                onInlineInteraction = onInlineInteraction,
                modifier = modifier
            )

        MarkdownBlockKind.TableRow ->
            MarkdownTableRow(
                block = block,
                showWrappedPreview = showWrappedPreview,
                onInlineInteraction = onInlineInteraction,
                modifier = modifier
            )

        MarkdownBlockKind.Footnote ->
            MarkdownFootnote(
                block = block,
                onInlineInteraction = onInlineInteraction,
                canReturnToReference = canReturnToFootnoteReference,
                modifier = modifier
            )
    }
}

/** Displays one thematic break as a direct source-edit target. */
@Composable
private fun MarkdownRule(block: MarkdownRenderBlock, modifier: Modifier = Modifier) {
    val navigationState = LocalMarkdownPreviewNavigationState.current
    var topInWindowPixels by remember(block) { mutableStateOf<Int?>(null) }
    val navigationModifier =
        if (navigationState?.isEditable == true) {
            Modifier.clickable(role = Role.Button) {
                val top = topInWindowPixels ?: return@clickable
                navigationState.editSource(
                    block = block,
                    renderedUtf16Offset = 0,
                    lineTopInWindowPixels = top
                )
            }
        } else {
            Modifier
        }
    HorizontalDivider(
        modifier =
            modifier
                .padding(vertical = EditorCompactSpacing)
                .onGloballyPositioned { coordinates ->
                    topInWindowPixels = coordinates.positionInWindow().y.roundToInt()
                }
                .then(navigationModifier),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

/** Returns the Material typography for one Markdown heading level. */
@Composable
private fun markdownHeadingStyle(level: Int): TextStyle = when (level) {
    1 -> MaterialTheme.typography.headlineLarge
    2 -> MaterialTheme.typography.headlineSmall
    3 -> MaterialTheme.typography.titleLarge
    4 -> MaterialTheme.typography.titleMedium
    5 -> MaterialTheme.typography.titleSmall
    else -> MaterialTheme.typography.labelLarge
}.copy(letterSpacing = 0.sp)

/** Returns comfortable paragraph leading without overriding the user's font scale. */
@Composable
private fun markdownBodyStyle(): TextStyle = MaterialTheme.typography.bodyLarge.copy(
    fontSize = 18.sp,
    lineHeight = 28.sp,
    letterSpacing = 0.sp
)

/** Displays one fenced or indented code block without executing it. */
@Composable
private fun MarkdownCodeBlock(
    block: MarkdownRenderBlock,
    showWrappedPreview: Boolean,
    onInlineInteraction: (MarkdownInlineInteraction) -> Unit,
    modifier: Modifier = Modifier
) {
    MarkdownCodeBlocks(
        blocks = listOf(block),
        firstBlockIndex = requireNotNull(LocalMarkdownPreviewBlockIndex.current),
        showWrappedPreview = showWrappedPreview,
        onInlineInteraction = onInlineInteraction,
        modifier = modifier
    )
}

/** Displays bounded chunks from one code block in a shared horizontal viewport. */
@Composable
internal fun MarkdownCodeBlocks(
    blocks: List<MarkdownRenderBlock>,
    firstBlockIndex: Int,
    showWrappedPreview: Boolean,
    onInlineInteraction: (MarkdownInlineInteraction) -> Unit,
    modifier: Modifier = Modifier
) {
    require(blocks.isNotEmpty()) { "Markdown code preview must contain at least one block" }
    require(firstBlockIndex >= 0) { "Markdown code block index must be nonnegative" }
    require(blocks.all { block -> block.kind == MarkdownBlockKind.Code }) {
        "Markdown code preview contains a non-code block"
    }
    val horizontalScrollState = rememberScrollState()
    val isDiagram = blocks.first().spans.singleOrNull()?.illustration.let {
        it is IllustrationResult.Rendered && it.kind == IllustrationKind.Diagram
    } && blocks.drop(1).all { it.illustrationContinuation }
    var fitDiagram by rememberSaveable(blocks.first().source.start) { mutableStateOf(true) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier.padding(EditorHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(EditorCompactSpacing)
        ) {
            MarkdownCodeHeader(
                metadata = blocks.first().metadata,
                copy = LocalMarkdownCodeCopies.current[firstBlockIndex],
                extraAction = if (isDiagram) {
                    {
                        IconButton(onClick = { fitDiagram = !fitDiagram }) {
                            Icon(
                                if (fitDiagram) EditorZoomInIcon else EditorZoomOutIcon,
                                contentDescription = stringResource(
                                    if (fitDiagram) {
                                        R.string.markdown_diagram_text_size
                                    } else {
                                        R.string.markdown_diagram_fit_width
                                    }
                                )
                            )
                        }
                    }
                } else {
                    null
                }
            )
            if (showWrappedPreview && !isDiagram) {
                MarkdownWrappedPreviewNotice()
            }
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val viewportWidth = maxWidth
                val diagramWidth = if (fitDiagram) {
                    with(LocalDensity.current) { viewportWidth.toPx() }.coerceAtLeast(1f)
                } else {
                    Float.POSITIVE_INFINITY
                }
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(horizontalScrollState)
                ) {
                    Column(modifier = Modifier.widthIn(min = viewportWidth)) {
                        blocks.forEachIndexed { blockIndex, block ->
                            if (block.illustrationContinuation) return@forEachIndexed
                            CompositionLocalProvider(
                                LocalMarkdownPreviewBlockIndex provides
                                    Math.addExact(firstBlockIndex, blockIndex),
                                LocalMarkdownDiagramWidth provides diagramWidth
                            ) {
                                MarkdownStyledText(
                                    block = block,
                                    style = if (standaloneDisplayIllustration(
                                            block.text,
                                            block.spans
                                        )
                                    ) {
                                        markdownBodyStyle().let {
                                            if (isDiagram) {
                                                it.copy(
                                                    textAlign = TextAlign.Center
                                                )
                                            } else {
                                                it
                                            }
                                        }
                                    } else {
                                        MaterialTheme.typography.bodyMedium
                                    },
                                    fontFamily = if (standaloneDisplayIllustration(
                                            block.text,
                                            block.spans
                                        )
                                    ) {
                                        FontFamily.Default
                                    } else {
                                        FontFamily.Monospace
                                    },
                                    onInlineInteraction = onInlineInteraction,
                                    modifier = Modifier.widthIn(min = viewportWidth),
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Explains the bounded fallback used for one exceptionally long rendered line. */
@Composable
private fun MarkdownWrappedPreviewNotice(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
    ) {
        Column(
            modifier = Modifier.padding(EditorCompactSpacing),
            verticalArrangement = Arrangement.spacedBy(EditorCompactSpacing / 2)
        ) {
            Text(
                text = stringResource(R.string.markdown_wrapped_title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.markdown_wrapped_message),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/** Displays one ordered, unordered, or task-list item. */
@Composable
private fun MarkdownListItem(
    block: MarkdownRenderBlock,
    onInlineInteraction: (MarkdownInlineInteraction) -> Unit,
    modifier: Modifier = Modifier
) {
    val taskState = markdownTaskState(block)
    val marker = markdownListMarker(block)
    if (taskState != null) {
        val checked = taskState == MarkdownTaskState.Checked
        val taskDescription = stringResource(
            if (checked) R.string.markdown_task_completed else R.string.markdown_task_incomplete
        )
        val contentBlock = remember(block) { markdownTaskContent(block) }
        Surface(
            modifier =
                modifier
                    .fillMaxWidth()
                    .semantics {
                        stateDescription = taskDescription
                    },
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = MaterialTheme.shapes.medium
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            vertical = EditorCompactSpacing / 2
                        ),
                horizontalArrangement = Arrangement.spacedBy(EditorCompactSpacing),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MarkdownTaskIndicator(checked = checked)
                MarkdownStyledText(
                    block = contentBlock,
                    sourceBlock = block,
                    renderedOffsetBase = block.text.length - contentBlock.text.length,
                    style = markdownBodyStyle(),
                    onInlineInteraction = onInlineInteraction,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        return
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(EditorCompactSpacing),
        verticalAlignment = Alignment.Top
    ) {
        when {
            marker != null ->
                Text(
                    text = marker,
                    modifier = Modifier.widthIn(min = MarkdownListMarkerWidth),
                    color = MaterialTheme.colorScheme.primary,
                    style = markdownBodyStyle(),
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End,
                    softWrap = false,
                    maxLines = 1
                )

            block.continuesPrevious || block.continuesListItem ->
                Spacer(Modifier.width(MarkdownListMarkerWidth))
        }
        MarkdownStyledText(
            block = block,
            style = markdownBodyStyle(),
            onInlineInteraction = onInlineInteraction,
            modifier = Modifier.weight(1f)
        )
    }
}

/** Displays an inert circular state marker for one Markdown task. */
@Composable
private fun MarkdownTaskIndicator(checked: Boolean) {
    Surface(
        modifier = Modifier.size(24.dp),
        shape = CircleShape,
        color =
            if (checked) {
                MaterialTheme.colorScheme.primary
            } else {
                Color.Transparent
            },
        contentColor = MaterialTheme.colorScheme.onPrimary,
        border =
            if (checked) {
                null
            } else {
                BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            }
    ) {
        if (checked) {
            Icon(
                imageVector = Icons.Default.Done,
                contentDescription = null,
                modifier = Modifier.padding(4.dp)
            )
        }
    }
}

/** Returns the bounded visual indent for one Markdown nesting depth. */
internal fun markdownNestedIndent(depth: Int): Dp {
    require(depth >= 0) { "Markdown nesting depth must not be negative" }
    return (MarkdownNestedIndent * depth).coerceAtMost(MarkdownMaximumIndent)
}

/** Identifies the task marker rendered for one Markdown list item. */
internal enum class MarkdownTaskState {
    /** Indicates a completed task. */
    Checked,

    /** Indicates an incomplete task. */
    Unchecked
}

/** Returns the explicit task state carried by one Markdown list item. */
internal fun markdownTaskState(block: MarkdownRenderBlock): MarkdownTaskState? {
    require(block.kind == MarkdownBlockKind.ListItem) { "Markdown block is not a list item" }
    return when {
        block.continuesPrevious || block.continuesListItem -> null
        block.isTaskChecked -> MarkdownTaskState.Checked
        block.isTaskUnchecked -> MarkdownTaskState.Unchecked
        else -> null
    }
}

/** Removes one renderer-provided task glyph while preserving inline span positions. */
internal fun markdownTaskContent(block: MarkdownRenderBlock): MarkdownRenderBlock {
    val taskState = requireNotNull(markdownTaskState(block)) { "Markdown block is not a task item" }
    val prefix =
        when (taskState) {
            MarkdownTaskState.Checked -> MARKDOWN_CHECKED_TASK_PREFIX
            MarkdownTaskState.Unchecked -> MARKDOWN_UNCHECKED_TASK_PREFIX
        }
    require(block.text.startsWith(prefix)) { "Markdown task item is missing its marker" }
    val prefixLength = prefix.length
    require(block.spans.all { span -> span.start >= prefixLength }) {
        "Markdown task marker unexpectedly contains inline styling"
    }
    return block.copy(
        text = block.text.drop(prefixLength),
        spans =
            block.spans.map { span ->
                span.copy(
                    start = span.start - prefixLength,
                    end = span.end - prefixLength
                )
            }
    )
}

/** Returns the textual marker rendered for one non-task Markdown list item. */
internal fun markdownListMarker(block: MarkdownRenderBlock): String? {
    require(block.kind == MarkdownBlockKind.ListItem) { "Markdown block is not a list item" }
    return when {
        block.continuesPrevious || block.continuesListItem ||
            block.isTaskChecked || block.isTaskUnchecked -> null

        block.isOrderedListItem -> "${block.listNumber}."

        else -> "•"
    }
}

/** Displays unsupported HTML as labeled, inert source text. */
@Composable
private fun MarkdownHtmlLiteral(
    block: MarkdownRenderBlock,
    onInlineInteraction: (MarkdownInlineInteraction) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier.padding(EditorHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(EditorVerticalPadding)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(EditorVerticalPadding),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "</>",
                    modifier = Modifier.clearAndSetSemantics {},
                    color = MaterialTheme.colorScheme.tertiary,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.markdown_inert_html),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.markdown_unsupported_markup),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHighest
            ) {
                MarkdownStyledText(
                    block = block,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    onInlineInteraction = onInlineInteraction,
                    modifier = Modifier.padding(EditorHorizontalPadding)
                )
            }
        }
    }
}

/** Displays one table row when surrounding rows cannot be grouped. */
@Composable
private fun MarkdownTableRow(
    block: MarkdownRenderBlock,
    showWrappedPreview: Boolean,
    onInlineInteraction: (MarkdownInlineInteraction) -> Unit,
    modifier: Modifier = Modifier
) {
    MarkdownTable(
        blocks = listOf(block),
        firstBlockIndex = requireNotNull(LocalMarkdownPreviewBlockIndex.current),
        showWrappedPreview = showWrappedPreview,
        onInlineInteraction = onInlineInteraction,
        modifier = modifier
    )
}

/** Displays consecutive table rows with bounded, consistently sized cells. */
@Composable
internal fun MarkdownTable(
    blocks: List<MarkdownRenderBlock>,
    firstBlockIndex: Int,
    showWrappedPreview: Boolean,
    onInlineInteraction: (MarkdownInlineInteraction) -> Unit,
    modifier: Modifier = Modifier
) {
    require(blocks.isNotEmpty()) { "Markdown table must contain at least one row" }
    require(firstBlockIndex >= 0) { "Markdown table block index must be nonnegative" }
    require(blocks.all { block -> block.kind == MarkdownBlockKind.TableRow }) {
        "Markdown table contains a non-table block"
    }
    val columnCount = remember(blocks) { blocks.maxOf(::markdownTableColumnCount) }
    if (columnCount > MAX_MARKDOWN_TABLE_COLUMNS || showWrappedPreview) {
        MarkdownWideTable(
            blocks = blocks,
            firstBlockIndex = firstBlockIndex,
            showWrappedPreview = showWrappedPreview,
            onInlineInteraction = onInlineInteraction,
            modifier = modifier
        )
        return
    }
    val rows = remember(blocks) { blocks.map(::splitMarkdownTableRow) }
    check(rows.maxOf(List<MarkdownTableCell>::size) == columnCount) {
        "Markdown table column count changed while splitting rows"
    }
    val horizontalScrollState = rememberScrollState()
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val dividerWidth = MarkdownTableDividerWidth * (columnCount - 1)
            val availableCellWidth = (maxWidth - dividerWidth).coerceAtLeast(0.dp)
            val cellWidth =
                maxOf(
                    MinimumMarkdownTableCellWidth,
                    availableCellWidth / columnCount
                )
            val tableWidth = cellWidth * columnCount + dividerWidth
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(horizontalScrollState)
            ) {
                Column(modifier = Modifier.width(tableWidth)) {
                    rows.forEachIndexed { rowIndex, cells ->
                        if (rowIndex != 0 && !blocks[rowIndex].continuesPrevious) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        Surface(
                            color =
                                if (blocks[rowIndex].isTableHeader) {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                }
                        ) {
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .height(IntrinsicSize.Min)
                            ) {
                                repeat(columnCount) { columnIndex ->
                                    if (columnIndex != 0) {
                                        Surface(
                                            modifier =
                                                Modifier
                                                    .width(MarkdownTableDividerWidth)
                                                    .fillMaxHeight(),
                                            color = MaterialTheme.colorScheme.outlineVariant
                                        ) {}
                                    }
                                    Box(
                                        modifier =
                                            Modifier
                                                .width(cellWidth)
                                                .padding(
                                                    horizontal = EditorHorizontalPadding,
                                                    vertical = EditorCompactSpacing
                                                )
                                    ) {
                                        val cell = cells.getOrNull(columnIndex)
                                        if (cell == null) {
                                            Text(
                                                text = EmptyMarkdownTableCell,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        } else {
                                            CompositionLocalProvider(
                                                LocalMarkdownPreviewBlockIndex provides
                                                    Math.addExact(firstBlockIndex, rowIndex)
                                            ) {
                                                MarkdownPresentationText(
                                                    sourceBlock = blocks[rowIndex],
                                                    sourceText = cell.text,
                                                    sourceSpans = cell.spans,
                                                    renderedOffsetBase = cell.renderedStart,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    textAlign =
                                                        markdownTableTextAlign(cell.alignment),
                                                    onInlineInteraction = onInlineInteraction,
                                                    modifier = Modifier.fillMaxWidth(),
                                                    fontWeight =
                                                        if (blocks[rowIndex].isTableHeader) {
                                                            FontWeight.Bold
                                                        } else {
                                                            null
                                                        }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Displays exceptionally wide table rows as bounded styled source text. */
@Composable
private fun MarkdownWideTable(
    blocks: List<MarkdownRenderBlock>,
    firstBlockIndex: Int,
    showWrappedPreview: Boolean,
    onInlineInteraction: (MarkdownInlineInteraction) -> Unit,
    modifier: Modifier = Modifier
) {
    val horizontalScrollState = rememberScrollState()
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium
    ) {
        Column {
            if (showWrappedPreview) {
                MarkdownWrappedPreviewNotice(
                    modifier = Modifier.padding(EditorCompactSpacing)
                )
            }
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val viewportWidth = maxWidth
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(horizontalScrollState)
                ) {
                    Column(modifier = Modifier.widthIn(min = viewportWidth)) {
                        blocks.forEachIndexed { rowIndex, block ->
                            if (rowIndex != 0 && !block.continuesPrevious) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                            }
                            CompositionLocalProvider(
                                LocalMarkdownPreviewBlockIndex provides
                                    Math.addExact(firstBlockIndex, rowIndex)
                            ) {
                                MarkdownStyledText(
                                    block = block,
                                    style = MaterialTheme.typography.bodyMedium,
                                    onInlineInteraction = onInlineInteraction,
                                    modifier =
                                        Modifier
                                            .widthIn(min = viewportWidth)
                                            .padding(
                                                horizontal = EditorHorizontalPadding,
                                                vertical = EditorCompactSpacing
                                            ),
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight =
                                        if (block.isTableHeader) FontWeight.Bold else null,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Returns one flattened table row's exact number of cells. */
internal fun markdownTableColumnCount(block: MarkdownRenderBlock): Int {
    require(block.kind == MarkdownBlockKind.TableRow) { "Markdown block is not a table row" }
    return Math.addExact(block.text.count { character -> character == '\t' }, 1)
}

/** Splits one protocol table row while rebasing its inline spans per cell. */
internal fun splitMarkdownTableRow(block: MarkdownRenderBlock): List<MarkdownTableCell> {
    require(block.kind == MarkdownBlockKind.TableRow) { "Markdown block is not a table row" }
    val cells = ArrayList<MarkdownTableCell>()
    var cellStart = 0
    while (cellStart <= block.text.length) {
        val separator = block.text.indexOf('\t', cellStart)
        val cellEnd = if (separator >= 0) separator else block.text.length
        val cellSpans =
            buildList {
                block.spans.forEach { span ->
                    val spanStart = maxOf(span.start, cellStart)
                    val spanEnd = minOf(span.end, cellEnd)
                    if (spanStart < spanEnd) {
                        add(
                            span.copy(
                                start = spanStart - cellStart,
                                end = spanEnd - cellStart
                            )
                        )
                    }
                }
            }
        cells +=
            MarkdownTableCell(
                text = block.text.substring(cellStart, cellEnd),
                spans = cellSpans,
                renderedStart = cellStart,
                alignment =
                    block.tableAlignments.getOrElse(cells.size) {
                        MarkdownTableAlignment.None
                    }
            )
        if (separator < 0) {
            break
        }
        cellStart = separator + 1
    }
    return cells
}

/** Contains one table cell and its cell-relative inline spans. */
internal data class MarkdownTableCell(
    val text: String,
    val spans: List<MarkdownInlineSpan>,
    val renderedStart: Int,
    val alignment: MarkdownTableAlignment
)

/** Maps one Markdown table alignment to direction-aware Compose text alignment. */
internal fun markdownTableTextAlign(alignment: MarkdownTableAlignment): TextAlign =
    when (alignment) {
        MarkdownTableAlignment.None,
        MarkdownTableAlignment.Left -> TextAlign.Start

        MarkdownTableAlignment.Center -> TextAlign.Center

        MarkdownTableAlignment.Right -> TextAlign.End
    }

/** Displays one footnote definition and its bounded label. */
@Composable
private fun MarkdownFootnote(
    block: MarkdownRenderBlock,
    onInlineInteraction: (MarkdownInlineInteraction) -> Unit,
    canReturnToReference: Boolean,
    modifier: Modifier = Modifier
) {
    val footnoteNumber = LocalMarkdownFootnoteNumbers.current[block.metadata]
    val referenceDescription = stringResource(R.string.markdown_return_to_reference)
    val labelModifier =
        if (canReturnToReference) {
            Modifier.clickable(role = Role.Button) {
                onInlineInteraction(MarkdownInlineInteraction.FootnoteDefinition(block.metadata))
            }
        } else {
            Modifier
        }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(EditorCompactSpacing),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier =
                labelModifier.sizeIn(
                    minWidth = MinimumBlockHeight,
                    minHeight = MinimumBlockHeight
                ),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Row(
                    modifier =
                        Modifier.padding(
                            horizontal = EditorVerticalPadding,
                            vertical = EditorCompactSpacing / 2
                        ),
                    horizontalArrangement = Arrangement.spacedBy(EditorCompactSpacing / 2),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = footnoteNumber?.toString() ?: block.metadata,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    if (canReturnToReference) {
                        Text(
                            text = "↩",
                            modifier =
                                Modifier.semantics {
                                    contentDescription = referenceDescription
                                },
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
        MarkdownStyledText(
            block = block,
            style = MaterialTheme.typography.bodyMedium,
            onInlineInteraction = onInlineInteraction,
            modifier = Modifier.weight(1f)
        )
    }
}

/** Displays one selectable block with its validated inline styles. */
@Composable
private fun MarkdownStyledText(
    block: MarkdownRenderBlock,
    style: TextStyle,
    onInlineInteraction: (MarkdownInlineInteraction) -> Unit,
    modifier: Modifier = Modifier,
    sourceBlock: MarkdownRenderBlock = block,
    renderedOffsetBase: Int = 0,
    fontFamily: FontFamily? = null,
    fontWeight: FontWeight? = null,
    softWrap: Boolean = true
) {
    if (block.kind != MarkdownBlockKind.Code &&
        standaloneDisplayIllustration(block.text, block.spans)
    ) {
        Column(modifier = modifier) {
            MarkdownCodeHeader(
                stringResource(R.string.markdown_formula),
                remember(block) { MarkdownCodeCopy(listOf(block)) },
                copyDescription = stringResource(R.string.markdown_copy_formula)
            )
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val viewportWidth = maxWidth
                Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    MarkdownPresentationText(
                        sourceBlock, block.text, block.spans, renderedOffsetBase, style,
                        onInlineInteraction, Modifier.widthIn(min = viewportWidth),
                        fontFamily, fontWeight, softWrap = false
                    )
                }
            }
        }
        return
    }
    MarkdownPresentationText(
        sourceBlock = sourceBlock,
        sourceText = block.text,
        sourceSpans = block.spans,
        renderedOffsetBase = renderedOffsetBase,
        style = style,
        onInlineInteraction = onInlineInteraction,
        modifier = modifier,
        fontFamily = fontFamily,
        fontWeight = fontWeight,
        softWrap = softWrap
    )
}

/** Displays one selectable presentation segment with non-consuming source navigation. */
@Composable
private fun MarkdownPresentationText(
    sourceBlock: MarkdownRenderBlock,
    sourceText: String,
    sourceSpans: List<MarkdownInlineSpan>,
    renderedOffsetBase: Int,
    style: TextStyle,
    onInlineInteraction: (MarkdownInlineInteraction) -> Unit,
    modifier: Modifier = Modifier,
    fontFamily: FontFamily? = null,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    softWrap: Boolean = true
) {
    require(renderedOffsetBase >= 0) { "Markdown rendered offset base must be nonnegative" }
    require(renderedOffsetBase + sourceText.length <= sourceBlock.text.length) {
        "Markdown presentation segment exceeds its source block"
    }
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    val footnoteNumbers = LocalMarkdownFootnoteNumbers.current
    val linkInteractionListener = rememberMarkdownLinkInteractionListener(onInlineInteraction)
    val footnotePresentation =
        remember(sourceText, sourceSpans, footnoteNumbers) {
            markdownInlinePresentation(sourceText, sourceSpans, footnoteNumbers)
        }
    val annotated =
        remember(
            sourceText,
            sourceSpans,
            footnotePresentation,
            linkColor,
            codeBackground,
            linkInteractionListener
        ) {
            markdownAnnotatedStringForPresentation(
                text = footnotePresentation?.text ?: sourceText,
                spans = footnotePresentation?.spans ?: sourceSpans,
                linkColor = linkColor,
                codeBackground = codeBackground,
                linkInteractionListener = linkInteractionListener
            )
        }
    val inlineFormulas = markdownIllustrationContent(
        footnotePresentation?.spans ?: sourceSpans,
        style,
        fontFamily,
        fontWeight
    )
    val formulaDescription = if (standaloneDisplayIllustration(sourceText, sourceSpans)) {
        stringResource(
            if (sourceSpans.single().illustration?.kind == IllustrationKind.Diagram) {
                R.string.markdown_diagram_source
            } else {
                R.string.markdown_formula_source
            },
            (sourceSpans.single().illustration as? IllustrationResult.Rendered)
                ?.drawing?.authoredDescription() ?: sourceText
        )
    } else {
        null
    }
    val navigationState = LocalMarkdownPreviewNavigationState.current
    val blockIndex = LocalMarkdownPreviewBlockIndex.current
    val key =
        remember(blockIndex, renderedOffsetBase, sourceText.length) {
            blockIndex?.let { index ->
                MarkdownPreviewTextKey(
                    blockIndex = index,
                    renderedStart = renderedOffsetBase,
                    renderedEnd = Math.addExact(renderedOffsetBase, sourceText.length)
                )
            }
        }
    val owner = remember(key) { Any() }
    var layoutResult by remember(owner) { mutableStateOf<TextLayoutResult?>(null) }
    var topInWindowPixels by remember(owner) { mutableStateOf<Int?>(null) }
    fun publishMeasurement() {
        val navigation = navigationState ?: return
        val measurementKey = key ?: return
        val layout = layoutResult ?: return
        val top = topInWindowPixels ?: return
        navigation.publish(
            MarkdownPreviewTextMeasurement(
                owner = owner,
                key = measurementKey,
                block = sourceBlock,
                sourceText = sourceText,
                sourceSpans = sourceSpans,
                footnotePresentation = footnotePresentation,
                layoutResult = layout,
                topInWindowPixels = top
            )
        )
    }
    DisposableEffect(navigationState, key, owner) {
        onDispose {
            key?.let { measurementKey ->
                navigationState?.release(measurementKey, owner)
            }
        }
    }
    val editSourceLabel = stringResource(R.string.markdown_edit_source)
    val sourceTapModifier =
        if (navigationState?.isEditable == true && key != null) {
            Modifier.semantics {
                customActions = listOf(
                    CustomAccessibilityAction(label = editSourceLabel) {
                        val top = topInWindowPixels ?: return@CustomAccessibilityAction false
                        val layout = layoutResult ?: return@CustomAccessibilityAction false
                        navigationState.editSource(
                            block = sourceBlock,
                            renderedUtf16Offset = renderedOffsetBase,
                            lineTopInWindowPixels = Math.addExact(
                                top,
                                layout.getLineTop(0).roundToInt()
                            )
                        )
                        true
                    }
                )
            }.pointerInput(annotated, owner) {
                awaitEachGesture {
                    awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial
                    )
                    val up =
                        withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            waitForUpOrCancellation(pass = PointerEventPass.Initial)
                        }
                    if (up == null) {
                        waitForUpOrCancellation(pass = PointerEventPass.Initial)
                        return@awaitEachGesture
                    }
                    val layout = layoutResult ?: return@awaitEachGesture
                    val top = topInWindowPixels ?: return@awaitEachGesture
                    val presentationOffset = layout.getOffsetForPosition(up.position)
                    val linkRangeEnd =
                        Math.incrementExact(presentationOffset)
                            .coerceAtMost(annotated.length)
                    if (
                        annotated
                            .getLinkAnnotations(presentationOffset, linkRangeEnd)
                            .isNotEmpty()
                    ) {
                        return@awaitEachGesture
                    }
                    val renderedOffset =
                        if (formulaDescription != null) {
                            // A drawn formula/diagram is one semantic object. Tapping its right
                            // half should open its source, not the next line after the fence.
                            0
                        } else {
                            footnotePresentation
                                ?.sourceUtf16OffsetForPresentation(
                                    presentationUtf16Offset = presentationOffset,
                                    sourceTextLength = sourceText.length,
                                    sourceSpans = sourceSpans
                                ) ?: presentationOffset
                        }
                    val line = layout.getLineForOffset(presentationOffset)
                    navigationState.editSource(
                        block = sourceBlock,
                        renderedUtf16Offset =
                            Math.addExact(renderedOffsetBase, renderedOffset),
                        lineTopInWindowPixels =
                            Math.addExact(
                                top,
                                layout.getLineTop(line).roundToInt()
                            )
                    )
                }
            }
        } else {
            Modifier
        }
    Column(
        modifier = modifier,
        horizontalAlignment = if (formulaDescription != null &&
            sourceSpans.single().illustration?.kind == IllustrationKind.Diagram
        ) {
            Alignment.CenterHorizontally
        } else {
            Alignment.Start
        }
    ) {
        val textContent: @Composable () -> Unit = {
            Text(
                text = annotated,
                inlineContent = inlineFormulas,
                modifier =
                    (if (softWrap) Modifier.fillMaxWidth() else Modifier)
                        .semantics { formulaDescription?.let { contentDescription = it } }
                        .onGloballyPositioned { coordinates ->
                            topInWindowPixels = coordinates.positionInWindow().y.roundToInt()
                            publishMeasurement()
                        }
                        .then(sourceTapModifier),
                style = style,
                fontFamily = fontFamily,
                fontWeight = fontWeight,
                textAlign = textAlign,
                softWrap = softWrap,
                onTextLayout = { result ->
                    layoutResult = result
                    publishMeasurement()
                }
            )
        }
        if (formulaDescription == null) {
            ReadingSelectionContainer { textContent() }
        } else {
            // The atomic display placeholder is not source text; its explicit Copy action is.
            textContent()
        }
        if (sourceSpans.any { it.illustration is IllustrationResult.Pending }) {
            Text(
                stringResource(R.string.markdown_illustration_rendering),
                modifier = Modifier.padding(top = EditorCompactSpacing),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
        illustrationFallbackMessages(sourceSpans).forEach { message ->
            Text(
                stringResource(message),
                modifier = Modifier.padding(top = EditorCompactSpacing).widthIn(max = 360.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/** Builds an interactive presentation string from validated, non-overlapping spans. */
internal fun markdownAnnotatedString(
    text: String,
    spans: List<MarkdownInlineSpan>,
    linkColor: androidx.compose.ui.graphics.Color,
    codeBackground: androidx.compose.ui.graphics.Color,
    linkInteractionListener: LinkInteractionListener,
    footnoteNumbers: Map<String, Int> = emptyMap()
): AnnotatedString {
    val footnotePresentation = markdownInlinePresentation(text, spans, footnoteNumbers)
    return markdownAnnotatedStringForPresentation(
        text = footnotePresentation?.text ?: text,
        spans = footnotePresentation?.spans ?: spans,
        linkColor = linkColor,
        codeBackground = codeBackground,
        linkInteractionListener = linkInteractionListener
    )
}

/** Builds one interactive string after any source-label substitutions are complete. */
private fun markdownAnnotatedStringForPresentation(
    text: String,
    spans: List<MarkdownInlineSpan>,
    linkColor: androidx.compose.ui.graphics.Color,
    codeBackground: androidx.compose.ui.graphics.Color,
    linkInteractionListener: LinkInteractionListener
): AnnotatedString = buildAnnotatedString {
    appendMarkdownIllustrationText(text, spans)
    spans.forEach { span ->
        val literalMath = span.styles and MARKDOWN_SPAN_STYLE_MATH != 0 &&
            span.illustration !is IllustrationResult.Rendered
        val decorations = buildList {
            if (span.styles and MARKDOWN_SPAN_STYLE_STRIKETHROUGH != 0) {
                add(TextDecoration.LineThrough)
            }
            if (span.destination != null) {
                add(TextDecoration.Underline)
            }
        }
        addStyle(
            style =
                SpanStyle(
                    color =
                        if (span.destination != null) {
                            linkColor
                        } else {
                            androidx.compose.ui.graphics.Color.Unspecified
                        },
                    background =
                        if (span.styles and MARKDOWN_SPAN_STYLE_CODE != 0 || literalMath) {
                            codeBackground
                        } else {
                            androidx.compose.ui.graphics.Color.Unspecified
                        },
                    fontWeight =
                        if (span.styles and MARKDOWN_SPAN_STYLE_STRONG != 0) {
                            FontWeight.Bold
                        } else {
                            null
                        },
                    fontStyle =
                        if (span.styles and MARKDOWN_SPAN_STYLE_EMPHASIS != 0) {
                            FontStyle.Italic
                        } else {
                            null
                        },
                    fontFamily =
                        if (span.styles and MARKDOWN_SPAN_STYLE_CODE != 0 || literalMath) {
                            FontFamily.Monospace
                        } else {
                            null
                        },
                    baselineShift =
                        when {
                            span.styles and MARKDOWN_SPAN_STYLE_SUPERSCRIPT != 0 ->
                                BaselineShift.Superscript

                            span.styles and MARKDOWN_SPAN_STYLE_SUBSCRIPT != 0 ->
                                BaselineShift.Subscript

                            else -> null
                        },
                    textDecoration =
                        if (decorations.isEmpty()) {
                            null
                        } else {
                            TextDecoration.combine(decorations)
                        }
                ),
            start = span.start,
            end = span.end
        )
        span.destination?.let { destination ->
            when (span.destinationKind) {
                MarkdownInlineDestinationKind.Link ->
                    addLink(
                        url =
                            LinkAnnotation.Url(
                                url = destination,
                                linkInteractionListener = linkInteractionListener
                            ),
                        start = span.start,
                        end = span.end
                    )

                MarkdownInlineDestinationKind.FootnoteReference ->
                    addLink(
                        clickable =
                            LinkAnnotation.Clickable(
                                tag = destination,
                                linkInteractionListener = linkInteractionListener
                            ),
                        start = span.start,
                        end = span.end
                    )

                null -> error("Markdown destination lacks its validated kind")
            }
        }
    }
}

/** Remembers one listener that forwards typed Markdown interactions. */
@Composable
private fun rememberMarkdownLinkInteractionListener(
    onInlineInteraction: (MarkdownInlineInteraction) -> Unit
): LinkInteractionListener {
    val currentOnInlineInteraction by rememberUpdatedState(onInlineInteraction)
    return remember {
        LinkInteractionListener { annotation ->
            val interaction =
                when (annotation) {
                    is LinkAnnotation.Url -> MarkdownInlineInteraction.Link(annotation.url)

                    is LinkAnnotation.Clickable ->
                        MarkdownInlineInteraction.FootnoteReference(annotation.tag)

                    else -> return@LinkInteractionListener
                }
            currentOnInlineInteraction(interaction)
        }
    }
}
