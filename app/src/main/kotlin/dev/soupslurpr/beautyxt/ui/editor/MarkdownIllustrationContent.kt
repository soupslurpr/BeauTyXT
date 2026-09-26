package dev.soupslurpr.beautyxt.ui.editor

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.dp
import dev.soupslurpr.beautyxt.illustration.IllustrationColors
import dev.soupslurpr.beautyxt.illustration.IllustrationDrawing
import dev.soupslurpr.beautyxt.illustration.IllustrationKind
import dev.soupslurpr.beautyxt.illustration.IllustrationResult
import dev.soupslurpr.beautyxt.illustration.illustrationPreviewGeometry
import dev.soupslurpr.beautyxt.markdown.MarkdownInlineSpan

private fun formulaKey(span: MarkdownInlineSpan) = "formula-${span.start}-${span.end}"

/** Display diagrams can fit their viewport; ordinary formulas retain the surrounding text size. */
internal val LocalMarkdownDiagramWidth = compositionLocalOf { Float.POSITIVE_INFINITY }

/** Native inline content keeps its original source as the alternative, never generated SVG. */
internal fun AnnotatedString.Builder.appendMarkdownIllustrationText(
    text: String,
    spans: List<MarkdownInlineSpan>
) {
    var offset = 0
    spans.forEach { span ->
        if (span.illustration is IllustrationResult.Rendered) {
            append(text, offset, span.start)
            appendInlineContent(formulaKey(span), text.substring(span.start, span.end))
            offset = span.end
        }
    }
    append(text, offset, text.length)
}

/** Aligns real formula baselines, including deep fractions, to the actual surrounding typeface. */
@Composable
internal fun markdownIllustrationContent(
    spans: List<MarkdownInlineSpan>,
    style: TextStyle,
    fontFamily: FontFamily?,
    fontWeight: FontWeight?,
    sourceSpans: List<MarkdownInlineSpan> = spans,
    renderedOffsetBase: Int = 0
): Map<String, InlineTextContent> {
    if (spans.none { it.illustration is IllustrationResult.Rendered }) return emptyMap()
    val typeface = LocalFontFamilyResolver.current.resolve(
        fontFamily ?: style.fontFamily,
        fontWeight ?: style.fontWeight ?: FontWeight.Normal,
        style.fontStyle ?: FontStyle.Normal,
        style.fontSynthesis ?: FontSynthesis.All
    ).value as Typeface
    val fontSize = with(LocalDensity.current) { style.fontSize.toPx() }
    val scheme = MaterialTheme.colorScheme
    val colors = IllustrationColors(
        style.color.takeOrElse { LocalContentColor.current }.toArgb(),
        scheme.surface.toArgb(),
        scheme.primary.toArgb(),
        scheme.secondaryContainer.toArgb()
    )
    val linkColor = scheme.primary.toArgb()
    val diagramSurface = scheme.surfaceContainerLow.toArgb()
    val diagramWidth = LocalMarkdownDiagramWidth.current
    val block = LocalMarkdownPreviewBlockIndex.current
    val highlights = LocalIllustrationFindHighlights.current.filter { it.target.block == block }
    val selection = LocalDocumentSelectionLayout.current?.session?.documentSelection
    val activeHighlight = scheme.primary.copy(alpha = .32f)
    val otherHighlight = scheme.tertiary.copy(alpha = .18f)
    return remember(spans, sourceSpans, renderedOffsetBase, typeface, fontSize, colors, linkColor, diagramSurface, diagramWidth, highlights, selection) {
        val metrics = Paint().apply {
            this.typeface = typeface
            textSize = fontSize
        }.fontMetricsInt
        buildMap {
            spans.forEachIndexed { spanIndex, span ->
                val rendered = span.illustration as? IllustrationResult.Rendered ?: return@forEachIndexed
                val source = sourceSpans[spanIndex]
                val marked = highlights.filter { it.target.start == source.start + renderedOffsetBase &&
                    it.target.end == source.end + renderedOffsetBase }
                val drawing = rendered.drawing
                val start = source.start + renderedOffsetBase
                val end = source.end + renderedOffsetBase
                val wholeSelected = block != null && selection is DocumentSelection.Reading &&
                    selection.start <= ReadingPoint(block, start) && selection.end >= ReadingPoint(block, end)
                val selectedBoxes = if (selection is DocumentSelection.Label && selection.block == block && selection.spanStart == start)
                    drawing.textRuns.getOrNull(selection.run)?.boxes.orEmpty().filter {
                        it.start < selection.range.max && it.end > selection.range.min
                    } else emptyList()
                val diagram = rendered.kind == IllustrationKind.Diagram
                // Edge-label cutouts must match the code card, not the page behind it.
                val spanColors = colors.copy(
                    surface = if (diagram) diagramSurface else colors.surface,
                    ink = if (span.destination != null) linkColor else colors.ink
                )
                val geometry = illustrationPreviewGeometry(
                    drawing,
                    if (diagram) minOf(fontSize, diagramWidth / drawing.width) else fontSize,
                    metrics.ascent.toFloat(),
                    metrics.descent.toFloat(),
                    diagram
                )
                put(
                    formulaKey(span),
                    InlineTextContent(
                        Placeholder(
                            (geometry.width / fontSize).em,
                            (geometry.height / fontSize).em,
                            PlaceholderVerticalAlign.TextCenter
                        )
                    ) {
                        val appearance = remember(drawing) { IllustrationDrawing(drawing) }
                        Canvas(Modifier.fillMaxSize()) {
                            appearance.draw(
                                drawContext.canvas.nativeCanvas,
                                0f,
                                geometry.top,
                                geometry.scale,
                                spanColors
                            )
                            if (wholeSelected) drawRect(activeHighlight,
                                androidx.compose.ui.geometry.Offset(0f, geometry.top),
                                androidx.compose.ui.geometry.Size(drawing.width * geometry.scale, drawing.height * geometry.scale),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                            selectedBoxes.forEach { box ->
                                drawRect(activeHighlight,
                                    androidx.compose.ui.geometry.Offset(box.left * geometry.scale, geometry.top + box.top * geometry.scale),
                                    androidx.compose.ui.geometry.Size((box.right - box.left) * geometry.scale, (box.bottom - box.top) * geometry.scale))
                            }
                            marked.sortedBy { it.current }.forEach { match ->
                                val color = if (match.current) activeHighlight else otherHighlight
                                if (match.target.run == null) {
                                    drawRect(color, androidx.compose.ui.geometry.Offset(0f, geometry.top),
                                        androidx.compose.ui.geometry.Size(drawing.width * geometry.scale, drawing.height * geometry.scale),
                                        style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                                } else match.target.boxes.forEach { box ->
                                    val left = box.left.coerceIn(0f, drawing.width) * geometry.scale
                                    val right = box.right.coerceIn(0f, drawing.width) * geometry.scale
                                    val top = geometry.top + box.top.coerceIn(0f, drawing.height) * geometry.scale
                                    val bottom = geometry.top + box.bottom.coerceIn(0f, drawing.height) * geometry.scale
                                    drawRect(color, androidx.compose.ui.geometry.Offset(left, top),
                                        androidx.compose.ui.geometry.Size(right - left, bottom - top))
                                }
                            }
                        }
                    }
                )
            }
        }
    }
}
