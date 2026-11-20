/* A bounded sample shares the document's actual appearance rendering. */
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.markdown.MARKDOWN_SPAN_STYLE_CODE
import dev.soupslurpr.beautyxt.markdown.MARKDOWN_SPAN_STYLE_EMPHASIS
import dev.soupslurpr.beautyxt.markdown.MARKDOWN_SPAN_STYLE_STRONG
import dev.soupslurpr.beautyxt.markdown.MarkdownInlineSpan

/** Pinned above scrolling phone controls; expanding it never changes a document preference. */
@Composable
internal fun AppearanceSample(group: AppearanceGroup, appearance: TextAppearance, expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit, height: Dp, modifier: Modifier = Modifier) {
    val state = stringResource(if (expanded) R.string.appearance_sample_expanded else R.string.appearance_sample_collapsed)
    val rotation by animateFloatAsState(if (expanded) 180f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec())
    Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column {
            Surface(onClick = { onExpandedChange(!expanded) }, color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().semantics { stateDescription = state }) {
                Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.appearance_sample_title), Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge)
                    Icon(painterResource(R.drawable.ic_keyboard_arrow_down), null, Modifier.graphicsLayer { rotationZ = rotation })
                }
            }
            AnimatedVisibility(expanded,
                enter = expandVertically(animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(), expandFrom = Alignment.Top) +
                    fadeIn(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()),
                exit = shrinkVertically(animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(), shrinkTowards = Alignment.Top) +
                    fadeOut(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec())) {
                key(group) { AppearanceSampleText(group, appearance, height) }
            }
        }
    }
}

/** The sample has its own viewport; widths and margins adapt just as they do in a document. */
@Composable
private fun AppearanceSampleText(group: AppearanceGroup, appearance: TextAppearance, height: Dp) {
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxWidth().height(height)) {
        val source = group == AppearanceGroup.Source
        val column = appearance.textColumn(group, maxWidth)
        val wrap = !source || appearance.value(AppearanceOption.Wrap, group) == 1f
        val viewport = Modifier.padding(start = column.textStart).width(column.textWidth)
            .appearanceScrollActions(verticalScroll, horizontalScroll.takeUnless { wrap })
            .verticalScroll(verticalScroll).padding(vertical = 8.dp)
            .then(if (wrap) Modifier else Modifier.horizontalScroll(horizontalScroll))
        if (source) {
            val text = stringResource(R.string.appearance_sample_source)
            Text(remember(text, appearance) { sourceDisplayText(AnnotatedString(text), appearance) },
                modifier = viewport, style = appearance.sourceStyle(MaterialTheme.typography.bodyLarge), softWrap = wrap)
        } else {
            val gap = with(LocalDensity.current) {
                appearance.value(AppearanceOption.Size, group).sp.toDp() * appearance.value(AppearanceOption.ParagraphSpacing, group)
            }
            Column(viewport, verticalArrangement = Arrangement.spacedBy(gap)) {
                Text(spacedDocumentText(AnnotatedString(stringResource(R.string.appearance_sample_heading)), appearance, group),
                    style = appearance.markdownStyle(MaterialTheme.typography.markdownHeadingStyle(3), MarkdownTextRole.Heading))
                Text(spacedDocumentText(AnnotatedString(stringResource(R.string.appearance_sample_body)), appearance, group),
                    style = appearance.markdownStyle(markdownBodyStyle()))
                MarkdownSampleEmphasis(appearance)
            }
        }
    }
}

/** Uses the production inline renderer, including the selected code font and bold fallback. */
@Composable
private fun MarkdownSampleEmphasis(appearance: TextAppearance) {
    val parts = listOf(
        stringResource(R.string.appearance_sample_strong) to MARKDOWN_SPAN_STYLE_STRONG,
        stringResource(R.string.appearance_sample_italic) to MARKDOWN_SPAN_STYLE_EMPHASIS,
        stringResource(R.string.appearance_sample_code) to MARKDOWN_SPAN_STYLE_CODE
    )
    val (text, spans) = remember(parts) {
        val text = StringBuilder()
        val spans = parts.map { (part, style) ->
            if (text.isNotEmpty()) text.append(" · ")
            val start = text.length
            text.append(part)
            MarkdownInlineSpan(start, text.length, style, null)
        }
        text.toString() to spans
    }
    val style = appearance.markdownStyle(markdownBodyStyle())
    val weight = appearance.value(AppearanceOption.Weight, AppearanceGroup.Markdown).toInt()
    val adjustment = LocalConfiguration.current.fontWeightAdjustment
    val (body, code) = remember(appearance, adjustment) {
        markdownEmphasis(appearance.value(AppearanceOption.Font, AppearanceGroup.Markdown), weight, adjustment) to
            markdownEmphasis(appearance.value(AppearanceOption.CodeFont, AppearanceGroup.Markdown), weight, adjustment)
    }
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    val annotated = remember(text, spans, appearance, body, code, style, linkColor, codeBackground) {
        spacedDocumentText(markdownAnnotatedStringForPresentation(text, spans, linkColor, codeBackground,
            LinkInteractionListener {}, appearance, baseSize = style.fontSize.value,
            bodyEmphasis = body, codeEmphasis = code), appearance, AppearanceGroup.Markdown)
    }
    Text(annotated, style = style)
}
