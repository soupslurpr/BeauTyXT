/* Coordinates rendered Markdown navigation and virtualized preview items. */
package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.markdown.MarkdownBlockKind
import dev.soupslurpr.beautyxt.markdown.markdownFootnoteLabel
import dev.soupslurpr.beautyxt.markdown.MarkdownInlinePresentation
import dev.soupslurpr.beautyxt.markdown.MarkdownInlineSpan
import dev.soupslurpr.beautyxt.markdown.MarkdownLinkAction
import dev.soupslurpr.beautyxt.markdown.MarkdownRenderBlock
import dev.soupslurpr.beautyxt.markdown.MarkdownRenderedSourcePosition
import dev.soupslurpr.beautyxt.markdown.markdownLinkAction
import dev.soupslurpr.beautyxt.markdown.markdownRenderedPositionForSourceOffset
import dev.soupslurpr.beautyxt.markdown.renderedUtf16OffsetForSource
import dev.soupslurpr.beautyxt.markdown.sourceUtf16OffsetForRendered
import dev.soupslurpr.beautyxt.ui.asString
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val MARKDOWN_PREVIEW_RESTORATION_TIMEOUT_MILLIS = 2_000L

internal val LocalMarkdownFootnoteNumbers =
    staticCompositionLocalOf<Map<String, Int>> { emptyMap() }

private val MinimumPaddedPreviewRecoveryHeight = 128.dp

/** Identifies one independently laid-out rendered segment within a Markdown block. */
internal data class MarkdownPreviewTextKey(
    val blockIndex: Int,
    val renderedStart: Int,
    val renderedEnd: Int
) {
    init {
        require(blockIndex >= 0) { "Markdown preview block index must be nonnegative" }
        require(renderedStart >= 0) { "Markdown preview segment start must be nonnegative" }
        require(renderedEnd >= renderedStart) {
            "Markdown preview segment end must not precede its start"
        }
    }
}

/** Holds one visible text layout and its mapping to the renderer's block text. */
internal data class MarkdownPreviewTextMeasurement(
    val owner: Any,
    val layoutGeneration: Any,
    val key: MarkdownPreviewTextKey,
    val block: MarkdownRenderBlock,
    val sourceText: String,
    val sourceSpans: List<MarkdownInlineSpan>,
    val footnotePresentation: MarkdownInlinePresentation?,
    val layoutResult: TextLayoutResult,
    val topInWindowPixels: Int,
    val bringIntoViewRequester: BringIntoViewRequester
) {
    /** Maps one displayed layout caret into the renderer's block text. */
    fun renderedUtf16OffsetForPresentation(presentationUtf16Offset: Int): Int {
        val localSourceOffset =
            footnotePresentation?.sourceUtf16OffsetForPresentation(
                presentationUtf16Offset = presentationUtf16Offset,
                sourceTextLength = sourceText.length,
                sourceSpans = sourceSpans
            ) ?: presentationUtf16Offset
        return Math.addExact(key.renderedStart, localSourceOffset)
    }

    /** Maps one renderer block caret into this displayed layout. */
    fun presentationUtf16OffsetForRendered(renderedUtf16Offset: Int): Int {
        val localSourceOffset =
            (renderedUtf16Offset - key.renderedStart).coerceIn(0, sourceText.length)
        return footnotePresentation?.presentationUtf16OffsetForSource(
            sourceUtf16Offset = localSourceOffset,
            sourceTextLength = sourceText.length,
            sourceSpans = sourceSpans
        ) ?: localSourceOffset
    }
}

/** Holds per-table shared horizontal scroll state across lazy table row chunks. */
private class SharedTableScrollRegistry {
    private val gridStates = HashMap<Int, ScrollState>()
    private val wideOffsets = HashMap<Int, MutableIntState>()

    fun gridState(tableId: Int): ScrollState = gridStates.getOrPut(tableId) { ScrollState(0) }
    fun wideOffset(tableId: Int): MutableIntState = wideOffsets.getOrPut(tableId) { mutableIntStateOf(0) }
}

/** Holds one rendered frontmatter property key layout and its reveal handle. */
internal data class FrontmatterKeyMeasurement(
    val topInWindowPixels: Int,
    val layoutResult: TextLayoutResult,
    val bringIntoViewRequester: BringIntoViewRequester
)

/** Retains only the currently composed Markdown text layouts for source navigation. */
internal class MarkdownPreviewNavigationState(
    val revision: Long,
    val isEditable: Boolean,
    private val onEditSource: (Long, Int) -> Boolean
) {
    init {
        require(revision >= 0L) { "Markdown preview revision must be nonnegative" }
    }

    var rootTopInWindowPixels by mutableIntStateOf(0)
    var rootWidthPixels by mutableIntStateOf(0)
    var hasRootPosition by mutableStateOf(false)
    val measurements = mutableStateMapOf<MarkdownPreviewTextKey, MarkdownPreviewTextMeasurement>()
    private val frontmatterKeyMeasurements = mutableStateMapOf<Int, FrontmatterKeyMeasurement>()

    fun recordFrontmatterKeyMeasurement(blockIndex: Int, measurement: FrontmatterKeyMeasurement) {
        frontmatterKeyMeasurements[blockIndex] = measurement
    }

    fun clearFrontmatterKeyMeasurement(blockIndex: Int) {
        frontmatterKeyMeasurements.remove(blockIndex)
    }

    fun frontmatterKeyMeasurement(blockIndex: Int): FrontmatterKeyMeasurement? =
        frontmatterKeyMeasurements[blockIndex]

    /** Publishes one composed text segment only while its owner remains current. */
    fun publish(measurement: MarkdownPreviewTextMeasurement) {
        measurements[measurement.key] = measurement
    }

    /** Releases one segment without deleting a newer composition with the same key. */
    fun release(key: MarkdownPreviewTextKey, owner: Any) {
        if (measurements[key]?.owner === owner) {
            measurements.remove(key)
        }
    }

    /** Returns the composed segment nearest one rendered block caret. */
    fun measurementFor(blockIndex: Int, renderedUtf16Offset: Int, layoutGeneration: Any): MarkdownPreviewTextMeasurement? {
        val candidates =
            measurements.values
                .filter { it.key.blockIndex == blockIndex && it.layoutGeneration === layoutGeneration }
                .sortedBy { measurement -> measurement.key.renderedStart }
        return candidates.firstOrNull { measurement ->
            renderedUtf16Offset in
                measurement.key.renderedStart..measurement.key.renderedEnd
        } ?: candidates.firstOrNull { measurement ->
            measurement.key.renderedStart > renderedUtf16Offset
        } ?: candidates.lastOrNull()
    }

    /** A joined diagram still owns the original source of every covered transport fragment. */
    fun measurementForSource(sourceOffset: Long, layoutGeneration: Any): MarkdownPreviewTextMeasurement? =
        measurements.values.firstOrNull { measurement ->
            if (measurement.layoutGeneration !== layoutGeneration) return@firstOrNull false
            val block = measurement.block
            val offset = block.renderedUtf16OffsetForSource(sourceOffset)
            sourceOffset in block.source.start until block.source.end &&
                offset in measurement.key.renderedStart..measurement.key.renderedEnd
        }

    /** Requests one exact renderer caret in the bounded source editor. */
    fun editSource(
        block: MarkdownRenderBlock,
        renderedUtf16Offset: Int,
        lineTopInWindowPixels: Int
    ): Boolean {
        if (!isEditable || !hasRootPosition) {
            return false
        }
        val sourceOffset = block.sourceUtf16OffsetForRendered(renderedUtf16Offset)
        return onEditSource(
            sourceOffset,
            Math.subtractExact(lineTopInWindowPixels, rootTopInWindowPixels)
        )
    }
}

internal val LocalMarkdownPreviewNavigationState =
    staticCompositionLocalOf<MarkdownPreviewNavigationState?> { null }
internal val LocalMarkdownLayoutGeneration = compositionLocalOf { Any() }
internal val LocalMarkdownListContinuationOwners = staticCompositionLocalOf { emptyMap<Int, MarkdownRenderBlock>() }
internal val LocalMarkdownPreviewBlockIndex = staticCompositionLocalOf<Int?> { null }

/** Displays the newest private Markdown render state. */
@Composable
internal fun MarkdownPreviewContent(
    session: EditorSession,
    modifier: Modifier = Modifier,
    useSplitPreview: Boolean = false
) {
    val activeStatus = if (useSplitPreview) {
        session.splitPreviewStatus
    } else {
        session.markdownPreviewStatus
    }
    when (val status = activeStatus) {
        MarkdownPreviewStatus.Idle,
        is MarkdownPreviewStatus.Rendering ->
            CenteredEditorMessage(
                message = stringResource(R.string.markdown_creating_preview),
                showProgress = true,
                modifier = modifier
            )

        is MarkdownPreviewStatus.Failed ->
            MarkdownPreviewFailure(
                message = status.message.asString(),
                isViewOnly = session.isViewOnly,
                onRetry = {
                    if (useSplitPreview) {
                        session.retrySplitPreview()
                    } else {
                        session.retryMarkdownPreview()
                    }
                },
                onEdit = if (useSplitPreview) null else session::showTextEditor,
                modifier = modifier
            )

        is MarkdownPreviewStatus.Ready -> {
            val document = status.document
            val detailsExpanded = if (useSplitPreview) {
                remember(session) { mutableStateMapOf<String, Boolean>() }
            } else {
                session.readingDetailsExpanded
            }
            val tapToEditModifier = if (useSplitPreview) {
                Modifier
            } else {
                Modifier.editOnMarkdownBackgroundTap(session, status.revision)
            }
            if (document.blocks.isEmpty()) {
                LaunchedEffect(detailsExpanded) {
                    if (detailsExpanded.isNotEmpty()) {
                        detailsExpanded.clear()
                    }
                }
                MarkdownPreviewEmpty(
                    isViewOnly = session.isViewOnly,
                    onEdit = if (useSplitPreview) null else session::showTextEditor,
                    modifier = modifier.then(tapToEditModifier)
                )
                return
            }
            val allPreviewItems = status.layout.items
            var splitFrontmatterExpanded by remember(session) { mutableStateOf(false) }
            val frontmatterExpanded = if (useSplitPreview) {
                splitFrontmatterExpanded
            } else {
                session.readingFrontmatterExpanded
            }
            val setFrontmatterExpanded: (Boolean) -> Unit = { expanded ->
                if (useSplitPreview) {
                    splitFrontmatterExpanded = expanded
                } else {
                    session.readingFrontmatterExpanded = expanded
                    if (!expanded && session.documentSelection is DocumentSelection.Reading) {
                        session.clearDocumentSelection()
                    }
                    if (session.capturedReadingFindScope != null || session.capturedFindScope != null) {
                        session.retryFind()
                    }
                }
            }
            LaunchedEffect(status.layout, detailsExpanded) {
                val activeKeys = status.layout.detailsSummaryKeyById.values.toHashSet()
                val staleKeys = detailsExpanded.keys.filter { it !in activeKeys }
                staleKeys.forEach(detailsExpanded::remove)
            }
            val previewItems by remember(status.layout, detailsExpanded) {
                derivedStateOf { visibleMarkdownPreviewItems(status.layout, detailsExpanded) }
            }
            val footnoteTargets = status.layout.footnotes
            val navigationState =
                remember(session, status.revision, useSplitPreview) {
                    MarkdownPreviewNavigationState(
                        revision = status.revision,
                        isEditable = !session.isViewOnly && !useSplitPreview,
                        onEditSource = { sourceOffset, viewportTopOffsetPixels ->
                            session.showTextEditorAtSource(
                                revision = status.revision,
                                utf16Offset = sourceOffset,
                                viewportTopOffsetPixels = viewportTopOffsetPixels
                            )
                        }
                    )
                }
            val appearance = LocalDocumentAppearances.current.markdown
            val readingListState = if (useSplitPreview) {
                remember(session) { LazyListState() }
            } else {
                session.markdownPreviewListState
            }
            val visibleAnchor by remember(status.revision, navigationState, previewItems, useSplitPreview) {
                derivedStateOf {
                    if (useSplitPreview) {
                        null
                    } else {
                        markdownPreviewVisibleAnchor(
                            status.revision,
                            navigationState,
                            readingListState,
                            previewItems
                        )
                    }
                }
            }
            val effective = remember(appearance) { appearance.effective(AppearanceGroup.Markdown) }
            val density = LocalDensity.current
            val layoutGeneration = remember(effective, MaterialTheme.typography, navigationState.rootWidthPixels,
                density.density, density.fontScale, LocalConfiguration.current.fontWeightAdjustment,
                LocalLayoutDirection.current) { Any() }
            if (!useSplitPreview) {
                RestoreAfterAppearanceChange(session, EditorPresentation.MarkdownPreview, status.revision, effective, visibleAnchor)
            }
            val restorationPosition =
                remember(document.blocks, status.scrollRestoration) {
                    status.scrollRestoration?.let { restoration ->
                        val explicitBlock = restoration.targetBlockIndex
                        if (explicitBlock != null && explicitBlock in document.blocks.indices) {
                            val clamped = restoration.targetRenderedOffset.coerceIn(
                                0,
                                document.blocks[explicitBlock].text.length
                            )
                            MarkdownRenderedSourcePosition(explicitBlock, clamped)
                        } else {
                            markdownRenderedPositionForSourceOffset(
                                blocks = document.blocks,
                                sourceUtf16Offset = restoration.anchor.sourceUtf16Offset
                            )
                        }
                    }
                }
            LaunchedEffect(status.revision, status.layout, restorationPosition) {
                val targetBlockIndex = restorationPosition?.blockIndex ?: return@LaunchedEffect
                if (document.blocks.getOrNull(targetBlockIndex)?.kind == MarkdownBlockKind.Frontmatter) {
                    setFrontmatterExpanded(true)
                }
                var expandedAnyDetails = false
                requiredExpandedDetailsKeysForBlock(
                    layout = status.layout,
                    blocks = document.blocks,
                    blockIndex = targetBlockIndex
                ).forEach { key ->
                    if (detailsExpanded[key] != true) {
                        detailsExpanded[key] = true
                        expandedAnyDetails = true
                    }
                }
                if (
                    expandedAnyDetails &&
                    !useSplitPreview &&
                    (session.capturedReadingFindScope != null || session.capturedFindScope != null)
                ) {
                    session.retryFind()
                }
            }
            val illustrationResults = rememberMarkdownIllustrations(
                status.layout.illustrations,
                status.layout.illustrationCache,
                readingListState,
                navigationState,
                previewItems,
                layoutGeneration = layoutGeneration,
                restoration = {
                    if (useSplitPreview) {
                        null
                    } else {
                        (session.markdownPreviewStatus as? MarkdownPreviewStatus.Ready)?.scrollRestoration
                    }
                },
                searching = !useSplitPreview && session.isFindVisible && session.findFieldValue.text.isNotEmpty()
            )
            LaunchedEffect(status.revision, illustrationResults.results, useSplitPreview) {
                if (!useSplitPreview) {
                    session.onReadingIllustrationsChanged(status.revision)
                }
            }
            val restorationItemIndex =
                remember(previewItems, restorationPosition) {
                    restorationPosition?.let { position ->
                        markdownPreviewItemIndexForBlock(
                            items = previewItems,
                            blockIndex = position.blockIndex
                        )
                    }
                }
            LaunchedEffect(
                status.revision,
                status.scrollRestoration,
                restorationPosition,
                layoutGeneration,
                restorationItemIndex,
                navigationState
            ) {
                val restoration = status.scrollRestoration
                val position = restorationPosition
                val itemIndex = restorationItemIndex
                if (restoration == null || position == null || itemIndex == null) {
                    val requiredKeys =
                        position?.let {
                            requiredExpandedDetailsKeysForBlock(
                                layout = status.layout,
                                blocks = document.blocks,
                                blockIndex = it.blockIndex
                            )
                        }.orEmpty()
                    val waitingForDetailsExpand =
                        position != null && (
                            requiredKeys.any { key -> detailsExpanded[key] != true } ||
                                (requiredKeys.isNotEmpty() &&
                                    markdownPreviewItemIndexForBlock(
                                        items = visibleMarkdownPreviewItems(status.layout, detailsExpanded),
                                        blockIndex = position.blockIndex
                                    ) != null)
                            )
                    if (restoration != null && !waitingForDetailsExpand) {
                        session.consumeMarkdownPreviewScrollRestoration(
                            revision = status.revision,
                            restoration = restoration
                        )
                    }
                    return@LaunchedEffect
                }
                try {
                    session.markdownPreviewListState.scrollToItem(itemIndex)
                    withFrameNanos { _ -> }
                    val matchSegment = session.findResults.getOrNull(session.findResultIndex)
                        ?.takeIf { restoration.findRange != null && session.isFindVisible && it.navigationOffset == restoration.anchor.sourceUtf16Offset }
                        ?.segments?.firstOrNull()
                    val measurement =
                        withTimeoutOrNull(MARKDOWN_PREVIEW_RESTORATION_TIMEOUT_MILLIS) {
                            snapshotFlow {
                                val resolved =
                                    if (restoration.targetBlockIndex != null) {
                                        navigationState.measurementFor(
                                            blockIndex = position.blockIndex,
                                            renderedUtf16Offset = position.renderedUtf16Offset,
                                            layoutGeneration = layoutGeneration
                                        )
                                    } else {
                                        matchSegment?.let { navigationState.measurementFor(it.block, it.blockStart, layoutGeneration) }
                                            ?: navigationState.measurementForSource(restoration.anchor.sourceUtf16Offset, layoutGeneration)
                                            ?: navigationState.measurementFor(
                                                blockIndex = position.blockIndex,
                                                renderedUtf16Offset = position.renderedUtf16Offset,
                                                layoutGeneration = layoutGeneration
                                            )
                                    }
                                if (
                                    resolved != null &&
                                    (matchSegment?.isFrontmatterKey != true ||
                                        navigationState.frontmatterKeyMeasurement(position.blockIndex) != null)
                                ) {
                                    resolved
                                } else {
                                    null
                                }
                            }.filterNotNull().first()
                        }
                    // Keep the first heading and its container visible at the document start.
                    val isDocumentStart =
                        restoration.targetBlockIndex == null &&
                            restoration.anchor.sourceUtf16Offset == 0L &&
                            restoration.anchor.viewportTopOffsetPixels >= 0
                    val presentationOffset = measurement?.let {
                        val part = matchSegment?.takeIf { part -> part.block == it.key.blockIndex }
                        val rendered = when {
                            restoration.targetBlockIndex != null && it.key.blockIndex == position.blockIndex ->
                                position.renderedUtf16Offset
                            part != null -> part.blockStart
                            else -> it.block.renderedUtf16OffsetForSource(restoration.anchor.sourceUtf16Offset)
                        }
                        (it.presentationUtf16OffsetForRendered(rendered) + (part?.markerStart ?: 0))
                            .coerceIn(0, it.layoutResult.layoutInput.text.length)
                    }
                    val keyMeasurement =
                        if (matchSegment?.isFrontmatterKey == true) {
                            navigationState.frontmatterKeyMeasurement(position.blockIndex)
                        } else {
                            null
                        }
                    if (
                        !isDocumentStart &&
                        measurement != null &&
                        presentationOffset != null &&
                        navigationState.hasRootPosition &&
                        !session.markdownPreviewListState.isScrollInProgress &&
                        measurement.layoutResult.size.height > 0
                    ) {
                        val (currentLineTop, lineHeightPx) =
                            if (keyMeasurement != null && keyMeasurement.layoutResult.size.height > 0) {
                                val keyLen = keyMeasurement.layoutResult.layoutInput.text.length
                                val keyOffset = matchSegment!!.blockStart.coerceIn(0, keyLen)
                                val keyLine = keyMeasurement.layoutResult.getLineForOffset(keyOffset)
                                val top = Math.subtractExact(
                                    keyMeasurement.topInWindowPixels,
                                    navigationState.rootTopInWindowPixels
                                ) + keyMeasurement.layoutResult.getLineTop(keyLine).roundToInt()
                                val height =
                                    keyMeasurement.layoutResult.getLineBottom(keyLine) -
                                        keyMeasurement.layoutResult.getLineTop(keyLine)
                                top to height
                            } else {
                                val line = measurement.layoutResult.getLineForOffset(presentationOffset)
                                val top = Math.subtractExact(
                                    measurement.topInWindowPixels,
                                    navigationState.rootTopInWindowPixels
                                ) + measurement.layoutResult.getLineTop(line).roundToInt()
                                val height =
                                    measurement.layoutResult.getLineBottom(line) -
                                        measurement.layoutResult.getLineTop(line)
                                top to height
                            }
                        val targetLineTop =
                            Math.addExact(
                                session.markdownPreviewListState.layoutInfo
                                    .viewportStartOffset
                                    .coerceAtLeast(0),
                                clampViewportTopInset(
                                    restoration.anchor.viewportTopOffsetPixels,
                                    lineHeightPx
                                )
                            )
                        session.markdownPreviewListState.scrollBy(
                            Math.subtractExact(currentLineTop, targetLineTop).toFloat()
                        )
                    }
                    if (matchSegment != null && measurement != null && presentationOffset != null &&
                        !session.markdownPreviewListState.isScrollInProgress) {
                        // Reveal the first displayed line of this match inside every nested
                        // scroller, including wide code, table cells, and wrapped frontmatter keys.
                        val (targetRequester, rectangle) =
                            if (keyMeasurement != null && keyMeasurement.layoutResult.size.height > 0) {
                                val keyLayout = keyMeasurement.layoutResult
                                val keyLen = keyLayout.layoutInput.text.length
                                val keyStart = matchSegment.blockStart.coerceIn(0, keyLen)
                                val keyLine = keyLayout.getLineForOffset(keyStart)
                                val keyEnd = matchSegment.blockEnd.coerceIn(keyStart, keyLayout.getLineEnd(keyLine))
                                val rect = if (keyEnd > keyStart) {
                                    keyLayout.getPathForRange(keyStart, keyEnd).getBounds()
                                } else {
                                    keyLayout.getCursorRect(keyStart)
                                }
                                keyMeasurement.bringIntoViewRequester to rect
                            } else {
                                val layout = measurement.layoutResult
                                val line = layout.getLineForOffset(presentationOffset)
                                val end = matchSegment.takeIf { it.block == measurement.key.blockIndex }?.let {
                                    measurement.presentationUtf16OffsetForRendered(it.blockEnd) + it.markerEnd
                                }?.coerceIn(presentationOffset, layout.getLineEnd(line)) ?: presentationOffset
                                val rect = if (end > presentationOffset) {
                                    layout.getPathForRange(presentationOffset, end).getBounds()
                                } else {
                                    layout.getCursorRect(presentationOffset)
                                }
                                measurement.bringIntoViewRequester to rect
                            }
                        withFrameNanos { }
                        withTimeoutOrNull(MARKDOWN_PREVIEW_RESTORATION_TIMEOUT_MILLIS) {
                            targetRequester.bringIntoView(rectangle)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    // A newer gesture or focus reveal can interrupt the nested scroller.
                    // Consume that interrupted request, but let a replaced composition
                    // continue the same pending restoration in its new layout.
                    currentCoroutineContext().ensureActive()
                }
                session.consumeMarkdownPreviewScrollRestoration(
                    revision = status.revision,
                    restoration = restoration
                )
            }
            LaunchedEffect(
                session,
                status.revision,
                previewItems,
                navigationState,
                status.scrollRestoration,
                useSplitPreview
            ) {
                if (useSplitPreview || status.scrollRestoration != null) {
                    return@LaunchedEffect
                }
                var wasScrolling = false
                snapshotFlow {
                    session.markdownPreviewListState.isScrollInProgress to
                        markdownPreviewVisibleAnchor(
                            revision = status.revision,
                            navigationState = navigationState,
                            listState = session.markdownPreviewListState,
                            items = previewItems
                        )
                }.collect { (isScrolling, anchor) ->
                    val shouldPublish = isScrolling || wasScrolling
                    wasScrolling = isScrolling
                    val live = session.markdownPreviewStatus as? MarkdownPreviewStatus.Ready
                    if (shouldPublish && anchor != null && live?.revision == status.revision && live.scrollRestoration == null) {
                        session.observeMarkdownPreviewViewportAnchor(
                            revision = anchor.revision,
                            utf16Offset = anchor.sourceUtf16Offset,
                            viewportTopOffsetPixels = anchor.viewportTopOffsetPixels
                        )
                    }
                }
            }
            val context = LocalContext.current
            val coroutineScope = rememberCoroutineScope()
            var linkDialogState by remember { mutableStateOf<MarkdownLinkDialogState?>(null) }
            var inspectorTarget by retain(session, status.revision) {
                mutableStateOf<ReadingInspectorTarget?>(null)
            }
            val lastTappedFootnoteReferences = remember(session, status.revision) {
                mutableStateMapOf<String, MarkdownReadingTarget>()
            }
            val tableScrollRegistry = remember(status.revision) { SharedTableScrollRegistry() }
            fun navigateSplitPaneToBlock(blockIndex: Int) {
                if (document.blocks.getOrNull(blockIndex)?.kind == MarkdownBlockKind.Frontmatter) {
                    setFrontmatterExpanded(true)
                }
                requiredExpandedDetailsKeysForBlock(
                    layout = status.layout,
                    blocks = document.blocks,
                    blockIndex = blockIndex
                ).forEach { key ->
                    if (detailsExpanded[key] != true) {
                        detailsExpanded[key] = true
                    }
                }
                val visible = visibleMarkdownPreviewItems(status.layout, detailsExpanded)
                val targetItem = markdownPreviewItemIndexForBlock(visible, blockIndex) ?: return
                coroutineScope.launch {
                    readingListState.scrollToItem(targetItem)
                }
            }
            val onInlineInteraction: (MarkdownInlineInteraction, Int) -> Unit =
                { interaction, sourceItemIndex ->
                    when (interaction) {
                        is MarkdownInlineInteraction.Link -> {
                            when (val action = markdownLinkAction(interaction.destination)) {
                                is MarkdownLinkAction.Heading -> {
                                    val target = status.layout.headings.target(action.fragment)
                                    if (target == null) {
                                        linkDialogState =
                                            MarkdownLinkDialogState.MissingHeading(action.fragment)
                                    } else if (useSplitPreview) {
                                        navigateSplitPaneToBlock(target.blockIndex)
                                    } else {
                                        session.navigateToReadingBlock(status.revision, target.blockIndex)
                                    }
                                }

                                is MarkdownLinkAction.External -> {
                                    if (action.requiresConfirmation) {
                                        linkDialogState =
                                            MarkdownLinkDialogState.ConfirmExternal(action)
                                    } else if (!launchMarkdownExternalLink(context, action)) {
                                        linkDialogState =
                                            MarkdownLinkDialogState.NoHandler(action.destination)
                                    }
                                }

                                is MarkdownLinkAction.Unavailable -> {
                                    linkDialogState = MarkdownLinkDialogState.Unavailable(action)
                                }
                            }
                        }

                        is MarkdownInlineInteraction.FootnoteReference -> {
                            val target = footnoteTargets.definitionTargets[interaction.label]
                            if (target == null) {
                                linkDialogState =
                                    MarkdownLinkDialogState.MissingFootnote(interaction.label)
                            } else {
                                val originBlock = interaction.blockIndex ?: previewItems[sourceItemIndex].firstBlockIndex
                                lastTappedFootnoteReferences[interaction.label] = MarkdownReadingTarget(
                                    itemIndex = sourceItemIndex,
                                    blockIndex = originBlock,
                                    renderedOffset = interaction.renderedOffset
                                )
                                if (useSplitPreview) {
                                    navigateSplitPaneToBlock(target.blockIndex)
                                } else {
                                    session.navigateToReadingBlock(status.revision, target.blockIndex)
                                }
                            }
                        }

                        is MarkdownInlineInteraction.FootnoteDefinition -> {
                            val target = lastTappedFootnoteReferences[interaction.label]
                                ?: footnoteTargets.referenceTargets[interaction.label]
                            val sourceBlock = interaction.blockIndex ?: previewItems[sourceItemIndex].firstBlockIndex
                            val sameDestination = target != null &&
                                target.itemIndex == sourceItemIndex &&
                                target.blockIndex == sourceBlock
                            if (target != null && !sameDestination) {
                                if (useSplitPreview) {
                                    navigateSplitPaneToBlock(target.blockIndex)
                                } else {
                                    session.navigateToReadingLocation(
                                        status.revision,
                                        target.blockIndex,
                                        target.renderedOffset
                                    )
                                }
                            }
                        }
                    }
                }
            val canToggleTask =
                !session.isViewOnly &&
                    session.documentSelection == null &&
                    session.canToggleReadingTask
            val taskToggleHandler: ((Int, MarkdownRenderBlock) -> Unit)? =
                remember(session, status.revision, canToggleTask) {
                    if (canToggleTask) {
                        { blockIndex, _ ->
                            session.toggleMarkdownTask(status.revision, blockIndex)
                        }
                    } else {
                        null
                    }
                }
            val inspectorLauncher: (ReadingInspectorTarget) -> Unit =
                remember(session, status.revision) {
                    { target -> inspectorTarget = target }
                }
            CompositionLocalProvider(
                LocalReadingFindHighlights provides readingFindHighlights(session),
                LocalIllustrationFindHighlights provides session.findResults.mapIndexedNotNull { index, result ->
                    result.illustration?.let { IllustrationFindHighlight(it, index == session.findResultIndex) }
                },
                LocalMarkdownListContinuationOwners provides status.layout.listContinuationOwners,
                LocalMarkdownFootnoteNumbers provides footnoteTargets.numbers,
                LocalMarkdownPreviewNavigationState provides navigationState,
                LocalMarkdownLayoutGeneration provides layoutGeneration,
                LocalMarkdownCodeCopies provides status.layout.codeCopies,
                LocalMarkdownTaskToggle provides taskToggleHandler,
                LocalMarkdownInspectorLauncher provides inspectorLauncher,
                LocalMarkdownDocumentBlocks provides status.document.blocks
            ) {
                val paragraphGap = markdownParagraphGap()
                val bottomOverlayDp = LocalEditorBottomOverlayState.current?.let { overlay ->
                    with(density) { overlay.heightPx.toDp() }
                } ?: 0.dp
                BoxWithConstraints(modifier.fillMaxSize()) {
                    val column = appearance.textColumn(AppearanceGroup.Markdown, maxWidth)
                    LazyColumn(
                        modifier =
                            Modifier
                                .then(tapToEditModifier)
                                .fillMaxSize()
                                .onGloballyPositioned { coordinates ->
                                    navigationState.rootTopInWindowPixels =
                                        coordinates.positionInWindow().y.roundToInt()
                                    navigationState.rootWidthPixels = coordinates.size.width
                                    navigationState.hasRootPosition = true
                                },
                        state = readingListState,
                        contentPadding =
                            PaddingValues(
                                start = column.textStart,
                                end = (maxWidth - column.textStart - column.textWidth).coerceAtLeast(0.dp),
                                top = EditorSectionSpacing,
                                bottom = EditorSectionSpacing + bottomOverlayDp
                            ),
                        verticalArrangement = Arrangement.Top
                    ) {
                        items(
                            count = previewItems.size,
                            key = { itemIndex ->
                                previewItems[itemIndex].stableKey
                            }
                        ) { itemIndex ->
                            val originalItem = previewItems[itemIndex]
                            val previewItem = remember(
                                originalItem,
                                illustrationResults,
                                status.layout.illustrations
                            ) {
                                if (status.layout.illustrations.isEmpty) {
                                    originalItem
                                } else {
                                    originalItem.copy(
                                        blocks = originalItem.blocks.indices.map { localIndex ->
                                            status.layout.illustrations.decorate(
                                                originalItem.firstBlockIndex + localIndex
                                            ) { request ->
                                                illustrationResults.resolve(request)
                                            }
                                        }
                                    )
                                }
                            }
                            val firstItemBlock = previewItem.blocks.first()
                            val detailsIndent = markdownNestedIndent(
                                markdownDetailsDepth(status.layout, firstItemBlock)
                            )
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            start = detailsIndent,
                                            top =
                                                if (previewItem.hasSpacingBefore) {
                                                    paragraphGap
                                                } else {
                                                    0.dp
                                                }
                                        )
                            ) {
                                val itemModifier =
                                    Modifier
                                        .align(Alignment.TopCenter)
                                        .fillMaxWidth()
                                when {
                                    previewItem.isFrontmatter ->
                                        MarkdownFrontmatterCard(
                                            blocks = previewItem.blocks,
                                            firstBlockIndex = previewItem.firstBlockIndex,
                                            expanded = frontmatterExpanded,
                                            onToggleExpanded = {
                                                setFrontmatterExpanded(!frontmatterExpanded)
                                            },
                                            onInlineInteraction = { onInlineInteraction(it, itemIndex) },
                                            modifier = itemModifier
                                        )

                                    firstItemBlock.kind == MarkdownBlockKind.DetailsSummary -> {
                                        val detailsId = firstItemBlock.detailsId
                                        val summaryKey = status.layout.detailsSummaryKeyById[detailsId]
                                        val expanded = isDetailsSectionExpanded(
                                            layout = status.layout,
                                            detailsId = detailsId,
                                            overrides = detailsExpanded
                                        )
                                        CompositionLocalProvider(
                                            LocalMarkdownPreviewBlockIndex provides
                                                previewItem.firstBlockIndex
                                        ) {
                                            MarkdownDetailsSummary(
                                                block = firstItemBlock,
                                                expanded = expanded,
                                                onToggleExpanded = {
                                                    if (summaryKey != null) {
                                                        val nextExpanded = !expanded
                                                        detailsExpanded[summaryKey] = nextExpanded
                                                        if (!useSplitPreview && !nextExpanded &&
                                                            session.documentSelection is DocumentSelection.Reading
                                                        ) {
                                                            session.clearDocumentSelection()
                                                        }
                                                        if (!useSplitPreview && (session.capturedReadingFindScope != null || session.capturedFindScope != null)) {
                                                            session.retryFind()
                                                        }
                                                    }
                                                },
                                                onInlineInteraction = { onInlineInteraction(it, itemIndex) },
                                                modifier = itemModifier
                                            )
                                        }
                                    }

                                    previewItem.isTable ->
                                        MarkdownOwnedPreviewGroup(
                                            block = previewItem.blocks.first(),
                                            references = footnoteTargets.references,
                                            onInlineInteraction = { onInlineInteraction(it, itemIndex) },
                                            modifier = itemModifier
                                        ) { groupModifier ->
                                            val sharedTableScroll = if (!previewItem.tableUsesWideLayout) {
                                                tableScrollRegistry.gridState(previewItem.tableId)
                                            } else {
                                                null
                                            }
                                            val sharedWideOffset = if (previewItem.tableUsesWideLayout) {
                                                tableScrollRegistry.wideOffset(previewItem.tableId)
                                            } else {
                                                null
                                            }
                                            Column(modifier = groupModifier) {
                                                if (previewItem.blocks.first().startsTable) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.End,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        androidx.compose.material3.IconButton(
                                                            onClick = {
                                                                val fullTableBlocks = allPreviewItems
                                                                    .filter { it.isTable && it.tableId == previewItem.tableId }
                                                                    .flatMap { it.blocks }
                                                                inspectorTarget = ReadingInspectorTarget.Table(fullTableBlocks)
                                                            }
                                                        ) {
                                                            androidx.compose.material3.Icon(
                                                                painter = androidx.compose.ui.res.painterResource(R.drawable.ic_open_in_new),
                                                                contentDescription = stringResource(R.string.markdown_inspector_open)
                                                            )
                                                        }
                                                    }
                                                }
                                                MarkdownTable(
                                                    blocks = previewItem.blocks,
                                                    firstBlockIndex = previewItem.firstBlockIndex,
                                                    showWrappedPreview = previewItem.showsWrappedPreview,
                                                    onInlineInteraction = { onInlineInteraction(it, itemIndex) },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    tableColumnCount = previewItem.tableColumnCount.takeIf { it > 0 },
                                                    useWideLayout = previewItem.tableUsesWideLayout,
                                                    totalLogicalRows = previewItem.tableRowCount.takeIf { it > 0 },
                                                    firstLogicalRowIndex = previewItem.tableFirstLogicalRowIndex,
                                                    sharedScrollState = sharedTableScroll,
                                                    sharedWideScrollOffset = sharedWideOffset
                                                )
                                            }
                                        }

                                    previewItem.isCode ->
                                        MarkdownOwnedPreviewGroup(
                                            block = previewItem.blocks.first(),
                                            references = footnoteTargets.references,
                                            onInlineInteraction = { onInlineInteraction(it, itemIndex) },
                                            modifier = itemModifier.padding(
                                                start = markdownNestedIndent(
                                                    (previewItem.blocks.first().listDepth - 1).coerceAtLeast(0)
                                                )
                                            )
                                        ) { groupModifier ->
                                            MarkdownCodeBlocks(
                                                blocks = previewItem.blocks,
                                                firstBlockIndex = previewItem.firstBlockIndex,
                                                showWrappedPreview = previewItem.showsWrappedPreview,
                                                onInlineInteraction = { onInlineInteraction(it, itemIndex) },
                                                modifier = groupModifier
                                            )
                                        }

                                    previewItem.isQuoteAlert ->
                                        MarkdownQuoteAlert(
                                            blocks = previewItem.blocks,
                                            firstBlockIndex = previewItem.firstBlockIndex,
                                            startsWrappedPreview = previewItem.startsWrappedPreview,
                                            onInlineInteraction = { interaction ->
                                                onInlineInteraction(interaction, itemIndex)
                                            },
                                            canReturnToFootnoteReference = { block ->
                                                block.markdownFootnoteLabel()?.let(footnoteTargets.references::containsKey) == true
                                            },
                                            modifier = itemModifier
                                        )

                                    else -> {
                                        val block = previewItem.blocks.single()
                                        CompositionLocalProvider(
                                            LocalMarkdownPreviewBlockIndex provides
                                                previewItem.firstBlockIndex
                                        ) {
                                            MarkdownPreviewBlock(
                                                block = block,
                                                showWrappedPreview = previewItem.showsWrappedPreview,
                                                onInlineInteraction = { interaction ->
                                                    onInlineInteraction(interaction, itemIndex)
                                                },
                                                canReturnToFootnoteReference =
                                                    block.markdownFootnoteLabel()?.let(footnoteTargets.references::containsKey) == true,
                                                modifier = itemModifier
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    ReadingProgressBar(
                        listState = readingListState,
                        itemCount = previewItems.size,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .align(Alignment.TopCenter)
                    )
                }
            }
            when (val activeInspector = inspectorTarget) {
                is ReadingInspectorTarget.Illustration ->
                    IllustrationInspectorDialog(
                        target = activeInspector,
                        documentTitle = session.title,
                        isSavingImage = session.isIllustrationImageSaving,
                        saveImageOutcome = session.illustrationImageSaveOutcome,
                        onRequestSaveImage = { drawing, colors, format, suggestedTitle ->
                            session.requestIllustrationImageSave(
                                drawing = drawing,
                                colors = colors,
                                format = format,
                                suggestedTitle = suggestedTitle
                            )
                        },
                        onRetainShareLease = session::retainIllustrationShareLease,
                        onDismiss = { inspectorTarget = null }
                    )

                is ReadingInspectorTarget.Table ->
                    TableInspectorDialog(
                        target = activeInspector,
                        onDismiss = { inspectorTarget = null }
                    )

                null -> Unit
            }
            linkDialogState?.let { dialogState ->
                MarkdownLinkDialog(
                    state = dialogState,
                    onConfirmExternal = { link ->
                        linkDialogState = null
                        if (!launchMarkdownExternalLink(context, link)) {
                            linkDialogState =
                                MarkdownLinkDialogState.NoHandler(link.destination)
                        }
                    },
                    onDismiss = { linkDialogState = null }
                )
            }
        }
    }
}

/** Resolves the first visible rendered line back to its exact source anchor. */
internal fun markdownPreviewVisibleAnchor(
    revision: Long,
    navigationState: MarkdownPreviewNavigationState,
    listState: LazyListState,
    items: List<MarkdownPreviewItem>
): SemanticViewportAnchor? {
    if (!navigationState.hasRootPosition) {
        return null
    }
    val layoutInfo = listState.layoutInfo
    val viewportHeight = layoutInfo.viewportSize.height
    val viewportStart = layoutInfo.viewportStartOffset.coerceIn(0, viewportHeight)
    val viewportEnd =
        layoutInfo.viewportEndOffset.coerceIn(
            minimumValue = viewportStart,
            maximumValue = viewportHeight
        )
    // Lazy composition can retain measurements for unplaced, cached text nodes. Only
    // currently visible items may supply a reading anchor, including during reflow.
    val visibleBlocks = layoutInfo.visibleItemsInfo.mapNotNull { item ->
        val preview = items.getOrNull(item.index) ?: return@mapNotNull null
        if (!lazyItemIntersectsViewport(item.offset, item.size, viewportStart, viewportEnd)) null
        else preview.firstBlockIndex until preview.firstBlockIndex + preview.blocks.size
    }
    val measurement =
        navigationState.measurements.values
            .asSequence()
            .filter { text -> visibleBlocks.any { text.key.blockIndex in it } }
            .map { textMeasurement ->
                textMeasurement to
                    Math.subtractExact(
                        textMeasurement.topInWindowPixels,
                        navigationState.rootTopInWindowPixels
                    )
            }
            .filter { (textMeasurement, topInViewport) ->
                textMeasurement.layoutResult.size.height > 0 &&
                    topInViewport < viewportEnd &&
                    Math.addExact(
                        topInViewport,
                        textMeasurement.layoutResult.size.height
                    ) > viewportStart
            }
            .minByOrNull { (_, topInViewport) -> topInViewport }
    if (measurement != null) {
        val (textMeasurement, topInViewport) = measurement
        val visibleTextPixels =
            Math.subtractExact(viewportStart, topInViewport)
                .coerceIn(0, textMeasurement.layoutResult.size.height - 1)
        val line =
            textMeasurement.layoutResult.getLineForVerticalPosition(
                visibleTextPixels.toFloat()
            )
        val presentationOffset = textMeasurement.layoutResult.getLineStart(line)
        val renderedOffset =
            textMeasurement.renderedUtf16OffsetForPresentation(presentationOffset)
                .coerceIn(0, textMeasurement.block.text.length)
        return SemanticViewportAnchor(
            revision = revision,
            sourceUtf16Offset =
                textMeasurement.block.sourceUtf16OffsetForRendered(renderedOffset),
            viewportTopOffsetPixels =
                Math.subtractExact(
                    Math.addExact(
                        topInViewport,
                        textMeasurement.layoutResult.getLineTop(line).roundToInt()
                    ),
                    viewportStart
                )
        )
    }
    val visibleItem =
        layoutInfo.visibleItemsInfo.firstOrNull { itemInfo ->
            itemInfo.index in items.indices &&
                lazyItemIntersectsViewport(
                    itemOffset = itemInfo.offset,
                    itemSize = itemInfo.size,
                    viewportStartOffset = viewportStart,
                    viewportEndOffset = viewportEnd
                )
        } ?: return null
    val block = items[visibleItem.index].blocks.firstOrNull() ?: return null
    return SemanticViewportAnchor(
        revision = revision,
        sourceUtf16Offset = block.source.start,
        viewportTopOffsetPixels = Math.subtractExact(visibleItem.offset, viewportStart)
    )
}

/** Describes one typed interaction emitted by rendered Markdown content. */
internal sealed interface MarkdownInlineInteraction {
    /** Requests handling for one ordinary Markdown destination. */
    data class Link(val destination: String) : MarkdownInlineInteraction

    /** Requests navigation from one reference to its local footnote definition. */
    data class FootnoteReference(
        val label: String,
        val blockIndex: Int? = null,
        val renderedOffset: Int = 0
    ) : MarkdownInlineInteraction

    /** Requests navigation from one footnote definition to its first reference. */
    data class FootnoteDefinition(val label: String, val blockIndex: Int? = null) : MarkdownInlineInteraction
}

/** Displays an empty preview with an action appropriate to the source mode. */
@Composable
private fun MarkdownPreviewEmpty(
    isViewOnly: Boolean,
    onEdit: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    MarkdownRecoveryLayout(modifier) {
        Text(
            text = stringResource(R.string.markdown_nothing_to_preview),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(EditorCompactSpacing))
        Text(
            text = if (isViewOnly) {
                stringResource(R.string.markdown_no_rendered_content)
            } else {
                stringResource(R.string.markdown_empty_document)
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        if (onEdit != null) {
            TextButton(onClick = onEdit) {
                Text(
                    if (isViewOnly) {
                        stringResource(
                            R.string.markdown_view_source
                        )
                    } else {
                        stringResource(R.string.markdown_edit_document)
                    }
                )
            }
        }
    }
}

/** Displays a sanitized preview failure with retry and source recovery. */
@Composable
private fun MarkdownPreviewFailure(
    message: String,
    isViewOnly: Boolean,
    onRetry: () -> Unit,
    onEdit: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    MarkdownRecoveryLayout(modifier) {
        Text(
            text = stringResource(R.string.markdown_preview_unavailable),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(EditorCompactSpacing))
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        FlowRow(
            horizontalArrangement =
                Arrangement.spacedBy(EditorCompactSpacing, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(EditorCompactSpacing)
        ) {
            if (onEdit != null) {
                TextButton(onClick = onEdit) {
                    Text(
                        if (isViewOnly) {
                            stringResource(
                                R.string.markdown_view_source
                            )
                        } else {
                            stringResource(R.string.editor_edit)
                        }
                    )
                }
            }
            FilledTonalButton(onClick = onRetry) {
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}

/** Reserves constrained vertical space for readable recovery actions instead of padding. */
@Composable
private fun MarkdownRecoveryLayout(
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val verticalPadding = if (maxHeight >= MinimumPaddedPreviewRecoveryHeight) {
            EditorHorizontalPadding
        } else {
            0.dp
        }
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = EditorHorizontalPadding, vertical = verticalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            content = content
        )
    }
}

/** Grouped tables and code retain the same note number and return action as paragraphs. */
@Composable
private fun MarkdownOwnedPreviewGroup(
    block: MarkdownRenderBlock,
    references: Map<String, Int>,
    onInlineInteraction: (MarkdownInlineInteraction) -> Unit,
    modifier: Modifier,
    content: @Composable (Modifier) -> Unit
) {
    val label = block.markdownFootnoteLabel()
    if (label == null) content(modifier) else MarkdownFootnoteDefinition(label, onInlineInteraction,
        references.containsKey(label), modifier, content)
}

/** Displays a subtle 2 dp top reading progress bar without recomposing the parent LazyColumn. */
@Composable
private fun ReadingProgressBar(
    listState: LazyListState,
    itemCount: Int,
    modifier: Modifier = Modifier
) {
    val showProgress by remember(listState) {
        derivedStateOf {
            listState.canScrollForward || listState.canScrollBackward
        }
    }
    if (!showProgress) return
    val progress = remember(listState, itemCount) {
        derivedStateOf {
            if (itemCount <= 0) return@derivedStateOf 0f
            if (!listState.canScrollForward) return@derivedStateOf 1f
            if (!listState.canScrollBackward) return@derivedStateOf 0f
            val layoutInfo = listState.layoutInfo
            val viewportStart = layoutInfo.viewportStartOffset
            val viewportEnd = layoutInfo.viewportEndOffset
            val viewportHeight = (viewportEnd - viewportStart).coerceAtLeast(1)
            val visibleItems = layoutInfo.visibleItemsInfo
            if (itemCount == 1) {
                val firstInfo = visibleItems.firstOrNull() ?: return@derivedStateOf 0f
                val totalHeight = firstInfo.size + layoutInfo.beforeContentPadding + layoutInfo.afterContentPadding
                val scrollable = (totalHeight - viewportHeight).coerceAtLeast(1)
                return@derivedStateOf (listState.firstVisibleItemScrollOffset.toFloat() / scrollable.toFloat())
                    .coerceIn(0f, 0.995f)
            }
            val firstIndex = listState.firstVisibleItemIndex.coerceIn(0, itemCount - 1)
            val firstInfo = visibleItems.firstOrNull { it.index == firstIndex }
            val itemFraction = if (firstInfo != null && firstInfo.size > 0) {
                (listState.firstVisibleItemScrollOffset.toFloat() / firstInfo.size.toFloat())
                    .coerceIn(0f, 1f)
            } else {
                0f
            }
            var visibleItemSpan = 0f
            for (info in visibleItems) {
                if (info.size <= 0) continue
                val visTop = maxOf(info.offset, viewportStart)
                val visBottom = minOf(info.offset + info.size, viewportEnd)
                if (visBottom > visTop) {
                    visibleItemSpan += (visBottom - visTop).toFloat() / info.size.toFloat()
                }
            }
            val maxFirstPosition = (itemCount.toFloat() - visibleItemSpan).coerceAtLeast(1f)
            ((firstIndex + itemFraction) / maxFirstPosition).coerceIn(0f, 0.995f)
        }
    }
    LinearProgressIndicator(
        progress = { progress.value },
        modifier = modifier.clearAndSetSemantics {},
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f),
        trackColor = Color.Transparent,
        drawStopIndicator = {}
    )
}
