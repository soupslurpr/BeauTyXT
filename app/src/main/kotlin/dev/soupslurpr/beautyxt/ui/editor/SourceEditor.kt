/* Displays bounded source windows while retaining input and scroll state. */
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.ReceiveContentListener

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.scrollableArea
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.IntOffset
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.horizontalScrollAxisRange
import androidx.compose.ui.semantics.error
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.document.Utf16Range
import dev.soupslurpr.beautyxt.ui.asString
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private const val VIEWPORT_FAILURE_ITEM_KEY = "viewport-failure"

/** Contains one stable visual-line anchor selected for automatic prefetch. */
private data class AutomaticEditWindowRequest(
    val direction: AutomaticEditWindowDirection,
    val localAnchorUtf16Offset: Int,
    val anchorViewportTopPixels: Int
)

/** Stores one block's text layout and its content origin within the lazy item. */
private data class BlockTextMeasurement(
    val generation: Any,
    val owner: Any,
    val layoutResult: TextLayoutResult,
    val contentTopInBlockPixels: Int
) {
    init {
        require(contentTopInBlockPixels >= 0) { "text content top must be nonnegative" }
    }
}

/** A recycled lazy row cannot shrink the horizontal extent of this revision's text. */
internal class SourceHorizontalViewport(val width: Int) {
    var maximum by mutableIntStateOf(0)
        private set
    var offset by mutableFloatStateOf(0f)
        private set
    val state = ScrollableState { delta ->
        val previous = offset
        offset = (offset + delta).coerceIn(0f, maximum.toFloat())
        offset - previous
    }

    fun measure(naturalWidth: Int) {
        maximum = maxOf(maximum, naturalWidth - width)
    }

    fun reveal(left: Float, right: Float, naturalWidth: Int, rtl: Boolean) {
        val target = sourceFindScrollOffset(offset, width, left, right, naturalWidth, rtl)
        offset = target.coerceIn(0f, maximum.toFloat())
    }
}

private fun sourceFindScrollOffset(offset: Float, width: Int, left: Float, right: Float,
    naturalWidth: Int, rtl: Boolean): Float {
    val start = if (rtl) naturalWidth - right else left
    val end = if (rtl) naturalWidth - left else right
    return when {
        start < offset || end - start >= width -> start
        end > offset + width -> end - width
        else -> offset
    }
}

private fun TextLayoutResult.sourceFindRect(range: Utf16Range, sourceStart: Long): Rect {
    val start = (range.start - sourceStart).coerceIn(0, layoutInput.text.length.toLong()).toInt()
    val lineEnd = getLineEnd(getLineForOffset(start))
    val end = (range.end - sourceStart).coerceIn(start.toLong(), lineEnd.toLong()).toInt()
    return if (end == start) getCursorRect(start) else getPathForRange(start, end).getBounds()
}

internal fun sourceBlockIndexForOffset(blocks: List<EditorRenderBlock>, offset: Long): Int {
    val start = blocks.indexOfFirst { it.block.globalUtf16Start == offset }
    if (start >= 0) return start
    val containing = blocks.indexOfFirst { offset in it.block.globalUtf16Start until it.block.globalUtf16End }
    return if (containing >= 0) containing else blocks.indexOfLast { it.block.globalUtf16End == offset }
}

/** Returns the first visible laid-out source point and its vertical viewport inset. */
private fun visibleBlockViewportAnchor(
    revision: Long,
    editorBlock: EditorRenderBlock,
    itemOffset: Int,
    viewportStartOffset: Int,
    viewportEndOffset: Int,
    measurement: BlockTextMeasurement
): SemanticViewportAnchor? {
    require(viewportEndOffset >= viewportStartOffset) {
        "viewport end must not precede its start"
    }
    val block = editorBlock.block
    val textTop = itemOffset.toLong() + measurement.contentTopInBlockPixels
    val textBottom = textTop + measurement.layoutResult.size.height
    if (
        textTop >= viewportEndOffset.toLong() ||
        textBottom <= viewportStartOffset.toLong()
    ) {
        return null
    }
    if (measurement.layoutResult.size.height <= 0) {
        return SemanticViewportAnchor(
            revision = revision,
            sourceUtf16Offset = block.globalUtf16Start,
            viewportTopOffsetPixels = 0
        )
    }
    val visibleY =
        (viewportStartOffset.toLong() - textTop)
            .coerceAtLeast(0L)
            .coerceAtMost(measurement.layoutResult.size.height.toLong() - 1L)
    val visibleLine =
        measurement.layoutResult.getLineForVerticalPosition(visibleY.toFloat())
    val localUtf16Offset = measurement.layoutResult.getLineStart(visibleLine)
    check(localUtf16Offset in 0..block.text.length) {
        "visible text offset exceeds its render block"
    }
    val lineTopInViewportPixels =
        Math.toIntExact(
            Math.subtractExact(
                Math.addExact(
                    textTop,
                    measurement.layoutResult.getLineTop(visibleLine).roundToInt().toLong()
                ),
                viewportStartOffset.toLong()
            )
        )
    return SemanticViewportAnchor(
        revision = revision,
        sourceUtf16Offset =
            Math.addExact(block.globalUtf16Start, localUtf16Offset.toLong()),
        viewportTopOffsetPixels = lineTopInViewportPixels
    )
}

/** Displays an active draft, cached blocks, or a lifecycle-specific empty state. */
@Composable
internal fun EditorContent(
    session: EditorSession,
    activeDraft: ActiveEditDraft?,
    modifier: Modifier = Modifier
) {
    val state = session.state
    if (session.presentation == EditorPresentation.MarkdownPreview) {
        MarkdownPreviewContent(session = session, modifier = modifier)
        return
    }
    if (activeDraft != null) {
        ActiveEditWindowEditor(
            session = session,
            draft = activeDraft,
            modifier = modifier
        )
        return
    }
    if (
        !session.isViewOnly &&
        state.status == EditorDocumentStatus.Ready &&
        session.canRetryEditWindow
    ) {
        CenteredActionMessage(
            message =
                state.editorMessage?.asString() ?: stringResource(R.string.source_window_failed),
            actionLabel = stringResource(R.string.source_retry),
            onAction = session::retryEditWindow,
            modifier = modifier
        )
        return
    }
    if (state.blocks.isEmpty()) {
        when (val status = state.status) {
            EditorDocumentStatus.Idle,
            EditorDocumentStatus.LoadingInitial,
            EditorDocumentStatus.LoadingEditWindow,
            EditorDocumentStatus.ApplyingEdit -> {
                CenteredEditorMessage(
                    message = stringResource(R.string.source_opening),
                    showProgress = true,
                    modifier = modifier
                )
            }

            EditorDocumentStatus.Stale -> {
                StaleDocumentMessage(session = session, modifier = modifier)
            }

            is EditorDocumentStatus.Failed -> {
                FailedViewportMessage(
                    session = session,
                    message = status.message.asString(),
                    modifier = modifier
                )
            }

            EditorDocumentStatus.Closed -> {
                CenteredEditorMessage(
                    message = stringResource(R.string.source_closed),
                    showProgress = false,
                    modifier = modifier
                )
            }

            EditorDocumentStatus.Ready -> {
                CenteredEditorMessage(
                    message = stringResource(R.string.source_empty),
                    showProgress = false,
                    modifier = modifier
                )
            }

            EditorDocumentStatus.LoadingMore -> {
                CenteredEditorMessage(
                    message = stringResource(R.string.source_loading),
                    showProgress = true,
                    modifier = modifier
                )
            }
        }
        return
    }

    EditorBlockList(session = session, modifier = modifier)
}

/** Displays the hard-bounded block cache through a virtualized lazy list. */
@Composable
private fun EditorBlockList(session: EditorSession, modifier: Modifier = Modifier) {
    val appearance = LocalDocumentAppearances.current.source
    val state = session.state
    val logicalLineByBlockKey =
        remember(state.blocks) {
            state.blocks.associate { editorBlock ->
                editorBlock.key to editorBlock.block.logicalLine
            }
        }
    val editorBlockByKey =
        remember(state.blocks) {
            state.blocks.associateBy(EditorRenderBlock::key)
        }
    val blockIndexByKey =
        remember(state.blocks) {
            buildMap(state.blocks.size) {
                state.blocks.forEachIndexed { blockIndex, editorBlock ->
                    put(editorBlock.key, blockIndex)
                }
            }
        }
    val blockTextMeasurements = remember { mutableStateMapOf<String, BlockTextMeasurement>() }
    LaunchedEffect(editorBlockByKey) {
        blockTextMeasurements.keys
            .filterNot(editorBlockByKey::containsKey)
            .forEach(blockTextMeasurements::remove)
    }
    val viewportListState = session.viewportListState
    val restoration = session.readOnlySourceScrollRestoration
    val paginationEnabled =
        !session.isClosePending && !session.isFindVisible && restoration == null
    val canPrefetchPrevious = paginationEnabled && state.canLoadPrevious
    val canPrefetchNext = paginationEnabled && state.canLoadMore
    val shouldPrefetchPrevious by
        remember(viewportListState, blockIndexByKey, canPrefetchPrevious) {
            derivedStateOf {
                canPrefetchPrevious &&
                    shouldPrefetchPreviousViewport(
                        firstVisibleBlockIndex =
                            viewportListState.layoutInfo.visibleItemsInfo
                                .firstNotNullOfOrNull { visibleItem ->
                                    blockIndexByKey[visibleItem.key]
                                },
                        blockCount = blockIndexByKey.size
                    )
            }
        }
    val shouldPrefetchNext by
        remember(viewportListState, blockIndexByKey, canPrefetchNext) {
            derivedStateOf {
                canPrefetchNext &&
                    shouldPrefetchNextViewport(
                        lastVisibleBlockIndex =
                            viewportListState.layoutInfo.visibleItemsInfo.let { visibleItems ->
                                var lastVisibleBlockIndex: Int? = null
                                visibleItems.forEach { visibleItem ->
                                    blockIndexByKey[visibleItem.key]?.let { blockIndex ->
                                        lastVisibleBlockIndex = blockIndex
                                    }
                                }
                                lastVisibleBlockIndex
                            },
                        blockCount = blockIndexByKey.size
                    )
            }
        }
    val previousPaginationKey = state.previousPaginationKey
    val nextPaginationKey = state.paginationKey
    LaunchedEffect(session, previousPaginationKey, shouldPrefetchPrevious) {
        if (previousPaginationKey != null && shouldPrefetchPrevious) {
            session.loadPreviousViewport()
        }
    }
    LaunchedEffect(session, nextPaginationKey, shouldPrefetchNext) {
        if (nextPaginationKey != null && shouldPrefetchNext) {
            session.loadNextViewport()
        }
    }
    val visibleLineRange by
        remember(session.viewportListState, logicalLineByBlockKey) {
            derivedStateOf {
                val layoutInfo = session.viewportListState.layoutInfo
                val viewportHeight = layoutInfo.viewportSize.height
                val visibleViewportStartOffset =
                    layoutInfo.viewportStartOffset.coerceIn(0, viewportHeight)
                val visibleViewportEndOffset =
                    layoutInfo.viewportEndOffset.coerceIn(
                        minimumValue = visibleViewportStartOffset,
                        maximumValue = viewportHeight
                    )
                visibleLogicalLineRange(
                    visibleItems = layoutInfo.visibleItemsInfo,
                    logicalLineForItem = { visibleItem ->
                        if (
                            lazyItemIntersectsViewport(
                                itemOffset = visibleItem.offset,
                                itemSize = visibleItem.size,
                                viewportStartOffset = visibleViewportStartOffset,
                                viewportEndOffset = visibleViewportEndOffset
                            )
                        ) {
                            logicalLineByBlockKey[visibleItem.key]
                        } else {
                            null
                        }
                    }
                )
            }
        }
    val metrics = requireNotNull(state.metrics)
    val visibleViewportAnchor by
        remember(
            session.viewportListState,
            editorBlockByKey,
            blockTextMeasurements,
            metrics.revision
        ) {
            derivedStateOf {
                val layoutInfo = session.viewportListState.layoutInfo
                val viewportHeight = layoutInfo.viewportSize.height
                val viewportStartOffset = layoutInfo.viewportStartOffset.coerceIn(0, viewportHeight)
                val viewportEndOffset =
                    layoutInfo.viewportEndOffset.coerceIn(
                        minimumValue = viewportStartOffset,
                        maximumValue = viewportHeight
                    )
                layoutInfo.visibleItemsInfo.firstNotNullOfOrNull { visibleItem ->
                    if (
                        lazyItemIntersectsViewport(
                            itemOffset = visibleItem.offset,
                            itemSize = visibleItem.size,
                            viewportStartOffset = viewportStartOffset,
                            viewportEndOffset = viewportEndOffset
                        )
                    ) {
                        val editorBlock = editorBlockByKey[visibleItem.key]
                            ?: return@firstNotNullOfOrNull null
                        val measurement = blockTextMeasurements[editorBlock.key]
                            ?: return@firstNotNullOfOrNull null
                        visibleBlockViewportAnchor(
                            revision = metrics.revision,
                            editorBlock = editorBlock,
                            itemOffset = visibleItem.offset,
                            viewportStartOffset = viewportStartOffset,
                            viewportEndOffset = viewportEndOffset,
                            measurement = measurement
                        )
                    } else {
                        null
                    }
                }
            }
        }
    val visibleMatchRange =
        session.findMatch
            ?.takeIf { match -> match.start.revision == metrics.revision }
            ?.range
    val effective = remember(appearance) { appearance.effective(AppearanceGroup.Source) }
    RestoreAfterAppearanceChange(session, EditorPresentation.Text, metrics.revision, effective, visibleViewportAnchor)
    LaunchedEffect(session, metrics.revision, visibleViewportAnchor, restoration) {
        if (session.readOnlySourceScrollRestoration != null) return@LaunchedEffect
        visibleViewportAnchor?.let { anchor ->
            session.observeVisibleViewportAnchor(
                revision = anchor.revision,
                utf16Offset = anchor.sourceUtf16Offset,
                viewportTopOffsetPixels = anchor.viewportTopOffsetPixels
            )
        }
    }
    val previousFailure = state.status as? EditorDocumentStatus.Failed
    val lineCount = metrics.lineCount
    val firstCachedLogicalLine = state.blocks.first().block.logicalLine
    val bottomOverlayState = LocalEditorBottomOverlayState.current
    val bottomOverlayDp = bottomOverlayState?.let { with(LocalDensity.current) { it.heightPx.toDp() } } ?: 0.dp

    Column(modifier = modifier.fillMaxSize()) {
        if (!session.isFindVisible) {
            DocumentViewportFooter(
                visibleRange = visibleLineRange,
                fallbackLogicalLine = firstCachedLogicalLine,
                lineCount = lineCount,
                navigationEnabled = session.canNavigateToLine,
                onNavigate = { logicalLine ->
                    session.showGoToLineDialog(logicalLine)
                }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val lineNumberStyle =
                MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace)
            val textMeasurer = rememberTextMeasurer()
            val density = LocalDensity.current
            val measuredLabelWidth =
                remember(lineCount, lineNumberStyle, textMeasurer, density) {
                    with(density) {
                        textMeasurer
                            .measure(
                                text = "↳ $lineCount",
                                style = lineNumberStyle,
                                maxLines = 1
                            ).size.width
                            .toDp()
                    }
                }
            val showLineNumbers =
                appearance.value(AppearanceOption.LineNumbers, AppearanceGroup.Source) == 1f
            val gutterWidth = if (showLineNumbers) lineNumberGutterWidth(measuredLabelWidth) else 0.dp
            val column = appearance.textColumn(AppearanceGroup.Source, maxWidth, gutterWidth)
            val syntaxLanguageId = remember(session.documentFormat, session.title) {
                resolveSourceSyntaxLanguageId(session.documentFormat, session.title)
            }
            val syntaxEnabled =
                appearance.value(AppearanceOption.SyntaxHighlighting, AppearanceGroup.Source) == 1f
            val startPadding = column.textStart - column.gutter
            val endPadding = (maxWidth - column.textStart - column.textWidth).coerceAtLeast(0.dp)
            val viewportPixels = with(density) {
                (constraints.maxWidth - startPadding.roundToPx() - endPadding.roundToPx() - column.gutter.roundToPx()).coerceAtLeast(0)
            }
            val wraps = appearance.value(AppearanceOption.Wrap, AppearanceGroup.Source) == 1f
            val style = sourceAppearanceStyle()
            val direction = LocalLayoutDirection.current
            val generation = remember(metrics.revision, effective, viewportPixels, column.gutter, style, lineNumberStyle,
                density.density, density.fontScale, direction) { Any() }
            val horizontal = remember(metrics.revision, wraps, style, viewportPixels, direction, density,
                appearance.value(AppearanceOption.TabWidth, AppearanceGroup.Source),
                appearance.value(AppearanceOption.WordSpacing, AppearanceGroup.Source)) {
                if (wraps) null else SourceHorizontalViewport(viewportPixels)
            }
            RestoreReadOnlySourcePosition(session, state.blocks, metrics.revision, restoration, generation,
                blockTextMeasurements, horizontal, direction == LayoutDirection.Rtl)
            val horizontalGestures = if (horizontal == null) Modifier else Modifier
                .semantics {
                    horizontalScrollAxisRange = ScrollAxisRange({ horizontal.offset }, { horizontal.maximum.toFloat() })
                }
                .scrollableArea(horizontal.state, Orientation.Horizontal)
            Box(Modifier.fillMaxSize().then(horizontalGestures)) {
                LazyColumn(
                    state = viewportListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = startPadding,
                        end = endPadding,
                        top = EditorVerticalPadding,
                        bottom = EditorVerticalPadding + bottomOverlayDp
                    )
                ) {
                    items(
                        items = state.blocks,
                        key = EditorRenderBlock::key,
                        contentType = { "document-block" }
                    ) { editorBlock ->
                        ReadOnlyBlock(
                            session = session,
                            revision = metrics.revision,
                            editorBlock = editorBlock,
                            lineNumberStyle = lineNumberStyle,
                            lineNumberWidth = gutterWidth,
                            showLineNumbers = showLineNumbers,
                            useLineNumberGutter = showLineNumbers && column.gutter > 0.dp,
                            syntaxLanguageId = syntaxLanguageId,
                            syntaxEnabled = syntaxEnabled,
                            generation = generation,
                            horizontal = horizontal,
                            matchRange = visibleMatchRange,
                            onTextMeasurement = { measurement ->
                                blockTextMeasurements[editorBlock.key] = measurement
                            },
                            onRelease = { owner ->
                                if (blockTextMeasurements[editorBlock.key]?.owner === owner) blockTextMeasurements.remove(editorBlock.key)
                            }
                        )
                    }

                    val status = state.status
                    if (
                        !session.isClosePending &&
                        !session.isFindVisible &&
                        state.isLoadingNextViewport
                    ) {
                        val loadingPaginationKey = checkNotNull(nextPaginationKey) {
                            "next viewport loading requires a pagination key"
                        }
                        item(key = viewportLoadingItemKey(loadingPaginationKey)) {
                            ViewportProgressMessage(stringResource(R.string.source_loading_later))
                        }
                    } else if (
                        !session.isClosePending &&
                        status is EditorDocumentStatus.Failed &&
                        state.hasNextViewportFailure
                    ) {
                        item(key = VIEWPORT_FAILURE_ITEM_KEY) {
                            FailedViewportRow(
                                message = status.message.asString(),
                                onRetry = session::retryViewport
                            )
                        }
                    }
                }
            }

            val bottomOverlayPadding = PaddingValues(
                start = EditorHorizontalPadding,
                end = EditorHorizontalPadding,
                top = EditorVerticalPadding,
                bottom = EditorVerticalPadding + bottomOverlayDp
            )
            when (
                val overlay =
                    editorViewportOverlay(
                        status = state.status,
                        editorMessage = state.editorMessage,
                        lineViewportStatus = state.lineViewportStatus
                    )
            ) {
                EditorViewportOverlay.OpeningSection -> {
                    ViewportProgressMessage(
                        message = stringResource(R.string.source_opening_section),
                        modifier =
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottomOverlayPadding)
                    )
                }

                is EditorViewportOverlay.OpeningLine -> {
                    val displayLine = Math.incrementExact(overlay.targetLogicalLine)
                    ViewportProgressMessage(
                        message = stringResource(R.string.source_opening_line, displayLine),
                        modifier =
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottomOverlayPadding)
                    )
                }

                is EditorViewportOverlay.LineFailure -> {
                    val displayLine = Math.incrementExact(overlay.targetLogicalLine)
                    FailedViewportRow(
                        message = stringResource(R.string.source_line_failed, displayLine),
                        onRetry = session::retryViewport,
                        onDismiss = { session.dismissLineNavigationFailure() },
                        modifier =
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottomOverlayPadding)
                    )
                }

                is EditorViewportOverlay.Failure -> {
                    EditorNoticeRow(
                        message = overlay.message.asString(),
                        modifier =
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottomOverlayPadding)
                    )
                }

                null -> Unit
            }

            when {
                !session.isClosePending &&
                    state.hasPreviousViewportFailure &&
                    previousFailure != null -> {
                    FailedViewportRow(
                        message = previousFailure.message.asString(),
                        onRetry = session::retryViewport,
                        modifier =
                            Modifier
                                .align(Alignment.TopCenter)
                                .padding(
                                    horizontal = EditorHorizontalPadding,
                                    vertical = EditorVerticalPadding
                                )
                    )
                }

                !session.isClosePending && state.isLoadingPreviousViewport -> {
                    ViewportProgressMessage(
                        message = stringResource(R.string.source_loading_earlier),
                        modifier =
                            Modifier
                                .align(Alignment.TopCenter)
                                .padding(
                                    horizontal = EditorHorizontalPadding,
                                    vertical = EditorVerticalPadding
                                )
                    )
                }
            }
        }
    }
}

/** Resolve the lazy item before waiting for its newly placed text, even at EOF. */
@Composable
private fun RestoreReadOnlySourcePosition(session: EditorSession, blocks: List<EditorRenderBlock>, revision: Long,
    restoration: ViewportRestoration?, generation: Any, measurements: Map<String, BlockTextMeasurement>,
    horizontal: SourceHorizontalViewport?, rtl: Boolean) {
    val viewportListState = session.viewportListState
    LaunchedEffect(session, restoration, generation, blocks) {
        val request = restoration ?: return@LaunchedEffect
        val anchor = request.anchor
        val index = sourceBlockIndexForOffset(blocks, anchor.sourceUtf16Offset)
        if (index >= 0 && anchor.revision == revision) {
            val block = blocks[index]
            try {
                withTimeoutOrNull(2_000L) {
                    val (scrolling, item, measurement) = snapshotFlow {
                        val item = viewportListState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == block.key }
                        val measurement = measurements[block.key]?.takeIf { it.generation === generation }
                        Triple(viewportListState.isScrollInProgress, item, measurement)
                    }.first { (scrolling, item, measurement) ->
                        when {
                            scrolling -> request.reason == RestorationReason.Appearance
                            item != null -> measurement != null
                            else -> {
                                viewportListState.scrollToItem(index)
                                false
                            }
                        }
                    }
                    if (scrolling || item == null || measurement == null ||
                        session.readOnlySourceScrollRestoration !== request) return@withTimeoutOrNull
                    val layout = measurement.layoutResult
                    val local = (anchor.sourceUtf16Offset - block.block.globalUtf16Start).toInt()
                    val line = layout.getLineForOffset(local)
                    val lineTop = item.offset + measurement.contentTopInBlockPixels + layout.getLineTop(line)
                    val inset = clampViewportTopInset(anchor.viewportTopOffsetPixels, layout.getLineBottom(line) - layout.getLineTop(line))
                    val targetTop = viewportListState.layoutInfo.viewportStartOffset.coerceAtLeast(0) + inset
                    viewportListState.scrollBy(lineTop - targetTop)
                    if (session.readOnlySourceScrollRestoration === request && horizontal != null && request.findRange != null) {
                        val rect = layout.sourceFindRect(request.findRange, block.block.globalUtf16Start)
                        horizontal.reveal(rect.left, rect.right, layout.size.width, rtl)
                    }
                }
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
            }
        }
        session.consumeReadOnlySourceScrollRestoration(request)
    }
}

/** Displays the exact visible line range and one direct navigation action. */
@Composable
private fun DocumentViewportFooter(
    visibleRange: VisibleLogicalLineRange?,
    fallbackLogicalLine: Long,
    lineCount: Long,
    navigationEnabled: Boolean,
    onNavigate: (Long) -> Unit
) {
    require(fallbackLogicalLine in 0 until lineCount) {
        "fallback logical line must belong to the document"
    }
    val rangeDescription =
        visibleRange?.let { range ->
            visibleLogicalLineRangeDescription(
                visibleRange = range,
                totalLineCount = lineCount
            ).asString()
        }
            ?: pluralStringResource(
                R.plurals.source_line_count,
                lineCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                lineCount
            )
    val navigationLogicalLine = visibleRange?.firstLogicalLine ?: fallbackLogicalLine
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = EditorHorizontalPadding,
                        end = EditorCompactSpacing
                    ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = rangeDescription,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge
            )
            TextButton(
                onClick = { onNavigate(navigationLogicalLine) },
                enabled = navigationEnabled
            ) {
                Text(stringResource(R.string.source_go_to_line))
            }
        }
    }
}

/** Displays one sanitized editor operation message inside the viewport. */
@Composable
private fun EditorNoticeRow(message: String, modifier: Modifier = Modifier) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                },
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.large
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(EditorSectionSpacing),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/** Displays a compact, non-focusable Markdown formatting strip docked above the IME. */
@Composable
private fun MarkdownFormattingStrip(
    onAction: (MarkdownFormatAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val actions = listOf(
        Triple(MarkdownFormatAction.Bold, "B", R.string.markdown_format_bold),
        Triple(MarkdownFormatAction.Italic, "I", R.string.markdown_format_italic),
        Triple(MarkdownFormatAction.Code, "</>", R.string.markdown_format_code),
        Triple(MarkdownFormatAction.Link, "[]()", R.string.markdown_format_link),
        Triple(MarkdownFormatAction.Heading, "H", R.string.markdown_format_heading),
        Triple(MarkdownFormatAction.BulletList, "•", R.string.markdown_format_bullet),
        Triple(MarkdownFormatAction.Checklist, "☑", R.string.markdown_format_checklist),
        Triple(MarkdownFormatAction.Quote, ">", R.string.markdown_format_quote),
        Triple(MarkdownFormatAction.Indent, "⇥", R.string.markdown_format_indent),
        Triple(MarkdownFormatAction.Outdent, "⇤", R.string.markdown_format_outdent)
    )
    Surface(
        modifier = modifier
            .focusProperties { canFocus = false }
            .registerBottomOverlayObstruction("markdown-format-strip"),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                actions.forEach { (action, glyph, labelRes) ->
                    val label = stringResource(labelRes)
                    TextButton(
                        onClick = { onAction(action) },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .focusProperties { canFocus = false }
                            .semantics { contentDescription = label },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = glyph,
                            style = MaterialTheme.typography.labelLarge,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

/** Displays one selectable Rust render block in the read-only fallback viewport. */
@Composable
private fun ReadOnlyBlock(
    session: EditorSession,
    revision: Long,
    editorBlock: EditorRenderBlock,
    lineNumberStyle: TextStyle,
    lineNumberWidth: Dp,
    showLineNumbers: Boolean,
    useLineNumberGutter: Boolean,
    syntaxLanguageId: Int,
    syntaxEnabled: Boolean,
    matchRange: Utf16Range?,
    generation: Any,
    horizontal: SourceHorizontalViewport?,
    onTextMeasurement: (BlockTextMeasurement) -> Unit,
    onRelease: (Any) -> Unit
) {
    val block = editorBlock.block
    val highlights = rememberFindHighlights(
        session = session,
        revision = revision,
        range = Utf16Range(block.globalUtf16Start, block.globalUtf16End)
    )
    val highlightRange =
        findBlockHighlightRange(
            matchRange = matchRange,
            blockUtf16Start = block.globalUtf16Start,
            blockUtf16End = block.globalUtf16End
        )
    val lineDescription = lineNumberDescription(
        block.logicalLine,
        block.continuesAtStart
    ).asString()
    val text: @Composable (Modifier) -> Unit = { cell ->
        ReadOnlyBlockText(
            session = session,
            text = block.text,
            revision = revision,
            globalStart = block.globalUtf16Start,
            highlights = highlights,
            highlightRange = highlightRange,
            syntaxLanguageId = syntaxLanguageId,
            syntaxEnabled = syntaxEnabled,
            generation = generation,
            horizontal = horizontal,
            onMeasurement = onTextMeasurement,
            onRelease = onRelease,
            modifier = cell
        )
    }
    val blockModifier =
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
    if (!showLineNumbers) {
        Box(modifier = blockModifier) {
            text(Modifier.fillMaxWidth())
        }
    } else if (useLineNumberGutter) {
        Row(modifier = blockModifier, verticalAlignment = Alignment.Top) {
            LineNumber(
                logicalLine = block.logicalLine,
                isContinuation = block.continuesAtStart,
                description = lineDescription,
                lineNumberStyle = lineNumberStyle,
                lineNumberWidth = lineNumberWidth,
                useGutter = true,
                modifier = Modifier.alignByBaseline()
            )
            text(Modifier.weight(1f).alignByBaseline())
        }
    } else {
        Column(modifier = blockModifier) {
            LineNumber(
                logicalLine = block.logicalLine,
                isContinuation = block.continuesAtStart,
                description = lineDescription,
                lineNumberStyle = lineNumberStyle,
                lineNumberWidth = lineNumberWidth,
                useGutter = false
            )
            text(Modifier.fillMaxWidth())
        }
    }
}

/** Displays selectable block text and reports its origin for viewport restoration. */
@Composable
private fun ReadOnlyBlockText(
    session: EditorSession,
    text: String,
    revision: Long,
    globalStart: Long,
    highlights: List<TextRange>,
    highlightRange: TextRange?,
    syntaxLanguageId: Int,
    syntaxEnabled: Boolean,
    generation: Any,
    horizontal: SourceHorizontalViewport?,
    onMeasurement: (BlockTextMeasurement) -> Unit,
    onRelease: (Any) -> Unit,
    modifier: Modifier = Modifier
) {
    val appearance = LocalDocumentAppearances.current.source
    val sourceStyle = sourceAppearanceStyle()
    val owner = remember { Any() }
    var selectionLayout by remember(globalStart, text) { mutableStateOf<TextLayoutResult?>(null) }
    var clip by remember { mutableStateOf<Rect?>(null) }
    val currentRelease by rememberUpdatedState(onRelease)
    DisposableEffect(owner) { onDispose { currentRelease(owner) } }
    val selectionModifier = Modifier.documentSelectionText("source-$globalStart", selectionLayout,
        point = { SelectionPoint.Source(globalStart + it) },
        offset = { (it as? SelectionPoint.Source)?.offset?.takeIf { offset -> offset in globalStart..globalStart + text.length }?.let { (it - globalStart).toInt() } },
        sourceFindDecoration = SourceFindDecoration(revision, text), clipBounds = { clip })
    val styles = findHighlightStyles()
    val syntaxRuns = rememberReadOnlySourceSyntaxRuns(
        session = session,
        revision = revision,
        globalStart = globalStart,
        text = text,
        languageId = syntaxLanguageId,
        enabled = syntaxEnabled
    )
    val syntaxPalette = if (syntaxEnabled && syntaxRuns.isNotEmpty()) {
        rememberSyntaxHighlightPalette()
    } else {
        null
    }
    val renderedText =
        remember(text, highlights, highlightRange, styles, appearance, syntaxRuns, syntaxPalette) {
            sourceDisplayText(
                text = findHighlightedText(text, highlights, highlightRange, styles),
                appearance = appearance,
                syntaxRuns = syntaxRuns,
                syntaxPalette = syntaxPalette
            )
        }
    val content: @Composable () -> Unit = {
        Text(
            text = renderedText,
            modifier = selectionModifier,
            color = MaterialTheme.colorScheme.onSurface,
            softWrap = horizontal == null,
            onTextLayout = { selectionLayout = it },
            style = sourceStyle
        )
    }
    Layout(
        modifier = modifier.clipToBounds().onGloballyPositioned { coordinates ->
            clip = coordinates.boundsInWindow()
            // Placement follows text measurement, including when only the callback's generation changed.
            selectionLayout?.let { layout ->
                onMeasurement(BlockTextMeasurement(generation, owner, layout, coordinates.positionInParent().y.roundToInt()))
            }
        },
        content = {
            if (LocalDocumentSelectionLayout.current == null) ReadingSelectionContainer { content() } else content()
        }
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val child = measurables.single().measure(constraints.copy(minWidth = width,
            maxWidth = if (horizontal == null) width else Constraints.Infinity))
        horizontal?.measure(child.width)
        layout(width, child.height, mapOf(FirstBaseline to child[FirstBaseline])) {
            child.placeRelative(-(horizontal?.offset?.roundToInt() ?: 0), 0)
        }
    }
}

/** Displays one stable multiline text field backed by a bounded Rust window. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ActiveEditWindowEditor(
    session: EditorSession,
    draft: ActiveEditDraft,
    modifier: Modifier = Modifier
) {
    val appearance = LocalDocumentAppearances.current.source
    val sourceStyle = sourceAppearanceStyle()
    val effective = remember(appearance) { appearance.effective(AppearanceGroup.Source) }
    val horizontalScroll = rememberScrollState()
    val direction = LocalLayoutDirection.current
    val wraps = appearance.value(AppearanceOption.Wrap, AppearanceGroup.Source) == 1f
    val state = session.state
    val edit = draft.edit
    val editsCompleteDocument = !edit.snapshot.hasPrevious && !edit.snapshot.hasNext
    val focusRequester = remember(draft) { FocusRequester() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val windowInfo = LocalWindowInfo.current
    var hasFocusedInComposition by remember(draft) { mutableStateOf(false) }
    var textLayoutResult by remember(edit.generation) {
        mutableStateOf<TextLayoutResult?>(null)
    }
    val textFieldState = draft.textFieldState
    val baseClipboard = LocalClipboard.current
    val clipboardSelection = remember(draft) { SourceClipboardSelection() }
    val fieldClipboard = remember(baseClipboard, clipboardSelection) {
        sourceTabPreservingClipboard(baseClipboard, clipboardSelection)
    }
    val hasActiveFocus = draft.isEditorFocused && windowInfo.isWindowFocused
    DisposableEffect(clipboardSelection, hasActiveFocus) {
        if (!hasActiveFocus) {
            clipboardSelection.clear()
        }
        onDispose {
            clipboardSelection.clear()
        }
    }
    val selectionInset = with(LocalDensity.current) { 16.dp.toPx() }
    var viewportClip by remember(draft) { mutableStateOf<Rect?>(null) }
    var bottomClearancePx by remember(draft.scrollState) { mutableFloatStateOf(0f) }
    LaunchedEffect(draft.edit.generation) {
        bottomClearancePx = 0f
    }
    val sourceSelectionModifier = Modifier.documentSelectionText(
        key = "source-edit-${edit.generation}", layout = textLayoutResult,
        nativeSelection = draft.isEditorFocused,
        point = { SelectionPoint.Source(edit.snapshot.range.start + it) },
        offset = { (it as? SelectionPoint.Source)?.offset?.takeIf { offset -> offset in edit.snapshot.range.start..edit.snapshot.range.end }
            ?.let { (it - edit.snapshot.range.start).toInt() } },
        contentOffset = {
            androidx.compose.ui.geometry.Offset(
                0f,
                selectionInset - draft.scrollState.value.toFloat()
            )
        },
        sourceFindDecoration = SourceFindDecoration(edit.snapshot.metrics.revision, edit.snapshot.text),
        sourceIsCurrent = { draft.edit == edit && draft.textFieldState.text.contentEquals(edit.snapshot.text) },
        clipBounds = {
            viewportClip?.let { clip ->
                val visibleTopInset = (selectionInset - bottomClearancePx).coerceAtLeast(0f)
                Rect(
                    left = clip.left,
                    top = (clip.top + visibleTopInset).coerceAtMost(clip.bottom),
                    right = clip.right,
                    bottom = clip.bottom
                )
            }
        }
    )
    val highlights = rememberFindHighlights(
        session = session,
        revision = edit.snapshot.metrics.revision,
        range = edit.snapshot.range
    )
    val currentHighlight = findBlockHighlightRange(
        matchRange = session.findMatch
            ?.takeIf { session.isFindVisible && it.start.revision == edit.snapshot.metrics.revision }
            ?.range,
        blockUtf16Start = edit.snapshot.range.start,
        blockUtf16End = edit.snapshot.range.end
    )
    val highlightStyles = findHighlightStyles()
    val hasChanges = draft.hasChanges
    val hasComposition by
        remember(edit.generation, textFieldState) {
            derivedStateOf { textFieldState.composition != null }
        }
    val syntaxLanguageId = remember(session.documentFormat, session.title) {
        resolveSourceSyntaxLanguageId(session.documentFormat, session.title)
    }
    val syntaxEnabled =
        appearance.value(AppearanceOption.SyntaxHighlighting, AppearanceGroup.Source) == 1f
    val highlightedSyntax = rememberActiveSourceSyntaxRuns(
        session = session,
        revision = edit.snapshot.metrics.revision,
        windowStart = edit.snapshot.range.start,
        text = textFieldState.text.toString(),
        languageId = syntaxLanguageId,
        enabled = syntaxEnabled
    )
    val syntaxPalette = if (syntaxEnabled) rememberSyntaxHighlightPalette() else null
    val appearanceState = rememberUpdatedState(appearance)
    val expectedTextState = rememberUpdatedState(edit.snapshot.text)
    val highlightsState = rememberUpdatedState(highlights)
    val currentHighlightState = rememberUpdatedState(currentHighlight)
    val highlightStylesState = rememberUpdatedState(highlightStyles)
    val highlightedSyntaxState = rememberUpdatedState(highlightedSyntax)
    val syntaxPaletteState = rememberUpdatedState(syntaxPalette)
    val outputTransformation = remember(clipboardSelection, textFieldState) {
        sourceAppearanceTransformation(
            appearanceState = { appearanceState.value },
            clipboardSelection = clipboardSelection,
            expectedTextState = { expectedTextState.value },
            highlightsState = { highlightsState.value },
            currentState = { currentHighlightState.value },
            stylesState = { highlightStylesState.value },
            highlightedSyntaxState = { highlightedSyntaxState.value },
            syntaxPaletteState = { syntaxPaletteState.value },
            hasCompositionState = { textFieldState.composition != null }
        )
    }
    val canAcceptInput = session.canApplyEditorInput(draft)
    val isMarkdownEditable =
        session.documentFormat == dev.soupslurpr.beautyxt.document.DocumentFormat.Markdown &&
            !session.isViewOnly
    val applyLocalEdit: ((CharSequence, TextRange) -> LocalEditResult?) -> Boolean = { transform ->
        if (!canAcceptInput || hasComposition) {
            false
        } else {
            val result = transform(textFieldState.text, textFieldState.selection)
            if (result != null && result.text.length <= EDIT_DRAFT_MAX_UTF16_UNITS) {
                textFieldState.edit {
                    replace(0, length, result.text)
                    selection = result.selection
                }
                true
            } else {
                false
            }
        }
    }
    val assistSuppression = remember(draft) { MarkdownAssistSuppression() }
    val inputTransformation =
        remember(draft, isMarkdownEditable) {
            editorInputTransformation(
                canAcceptInput = { session.canApplyEditorInput(draft) },
                onBulkEdit = { proposal -> session.requestBulkEdit(draft, proposal) },
                onRejection = draft::reportInputRejection,
                clearRejection = draft::clearInputRejection,
                markdownAssistEnabled = { isMarkdownEditable },
                hasComposition = { draft.textFieldState.composition != null },
                hasPreviousWindow = { draft.edit.snapshot.hasPrevious },
                consumeSuppressMarkdownAssist = assistSuppression::consume
            )
        }
    val isBusy =
        state.status == EditorDocumentStatus.LoadingInitial ||
            state.status == EditorDocumentStatus.LoadingEditWindow
    val fieldMessage =
        state.editorMessage?.asString()
            ?: draft.inputRejection?.let { stringResource(it.messageResource) }
            ?: draft.selectionBoundaryMessage?.asString()
    val pendingActionMessage = session.pendingEditWindowActionMessageResource?.let {
        stringResource(it)
    }
    LaunchedEffect(session, draft, hasChanges, hasComposition) {
        if (!hasChanges && !hasComposition) {
            session.prefetchAdjacentEditWindows(draft)
        }
    }
    LaunchedEffect(draft, canAcceptInput, draft.shouldRestoreEditorFocus) {
        if (
            shouldRequestActiveEditFieldFocus(
                canAcceptInput = canAcceptInput,
                shouldRestoreEditorFocus = draft.shouldRestoreEditorFocus,
                isEditorFocused = draft.isEditorFocused
            )
        ) {
            val selectionBeforeFocus = draft.selectionForFocusRestoration()
            focusRequester.requestFocus()
            draft.restoreSelectionCollapsedByFocus(selectionBeforeFocus)
        }
    }
    LaunchedEffect(draft, draft.scrollRestoration, textLayoutResult, wraps, direction) {
        val restoration = draft.scrollRestoration ?: return@LaunchedEffect
        val layoutResult = textLayoutResult ?: return@LaunchedEffect
        if (restoration.anchor.revision != edit.snapshot.metrics.revision) {
            draft.consumeScrollRestoration(restoration)
            return@LaunchedEffect
        }
        val localAnchorUtf16Offset =
            Math.toIntExact(
                restoration.anchor.sourceUtf16Offset - edit.snapshot.range.start
            )
        if (localAnchorUtf16Offset !in 0..edit.snapshot.text.length) {
            draft.consumeScrollRestoration(restoration)
            return@LaunchedEffect
        }
        val anchorLine = layoutResult.getLineForOffset(localAnchorUtf16Offset)
        val anchorContentTopPixels = layoutResult.getLineTop(anchorLine).roundToInt()
        val targetScrollPixels =
            Math.subtractExact(
                anchorContentTopPixels,
                clampViewportTopInset(restoration.anchor.viewportTopOffsetPixels,
                    layoutResult.getLineBottom(anchorLine) - layoutResult.getLineTop(anchorLine))
            ).coerceAtLeast(0)
        withFrameNanos { _ -> }
        draft.restoreScrollPosition(restoration, targetScrollPixels) {
            if (!wraps && restoration.findRange != null) {
                val rect = layoutResult.sourceFindRect(restoration.findRange, edit.snapshot.range.start)
                val target = sourceFindScrollOffset(horizontalScroll.value.toFloat(), horizontalScroll.viewportSize,
                    rect.left, rect.right, layoutResult.size.width, direction == LayoutDirection.Rtl)
                horizontalScroll.scrollTo(target.roundToInt())
            }
        }
    }
    LaunchedEffect(session, draft, edit, textLayoutResult) {
        snapshotFlow {
            val layoutResult = textLayoutResult ?: return@snapshotFlow null
            if (
                draft.edit != edit ||
                !sourcePresentationMatches(layoutResult.layoutInput.text.text, edit.snapshot.text) ||
                draft.hasChanges ||
                textFieldState.composition != null ||
                draft.scrollRestoration != null
            ) {
                return@snapshotFlow null
            }
            val scrollValuePixels = draft.scrollState.value
            val maxContentYPixels = (layoutResult.size.height - 1).coerceAtLeast(0)
            val visibleContentYPixels = scrollValuePixels.coerceAtMost(maxContentYPixels)
            val visibleLine =
                layoutResult.getLineForVerticalPosition(visibleContentYPixels.toFloat())
            val lineTopPixels = layoutResult.getLineTop(visibleLine).roundToInt()
            SemanticViewportAnchor(
                revision = edit.snapshot.metrics.revision,
                sourceUtf16Offset =
                    Math.addExact(
                        edit.snapshot.range.start,
                        layoutResult.getLineStart(visibleLine).toLong()
                    ),
                viewportTopOffsetPixels =
                    Math.subtractExact(lineTopPixels, scrollValuePixels)
            )
        }.collect { anchor ->
            anchor ?: return@collect
            if (draft.edit != edit) return@collect
            draft.retainViewportAnchor(anchor)
            session.observeVisibleViewportAnchor(
                revision = anchor.revision,
                utf16Offset = anchor.sourceUtf16Offset,
                viewportTopOffsetPixels = anchor.viewportTopOffsetPixels
            )
        }
    }
    LaunchedEffect(edit.generation, textFieldState) {
        snapshotFlow {
            ActiveEditFieldValue(
                text = textFieldState.text.toString(),
                selection = textFieldState.selection,
                composition = textFieldState.composition
            )
        }.collect { fieldValue ->
            session.observeActiveEdit(draft = draft, value = fieldValue)
        }
    }
    LaunchedEffect(draft, textLayoutResult, canAcceptInput, fieldMessage) {
        if (!canAcceptInput || fieldMessage != null) {
            return@LaunchedEffect
        }
        snapshotFlow {
            val layoutResult = textLayoutResult ?: return@snapshotFlow null
            if (
                draft.hasChanges ||
                textFieldState.composition != null ||
                draft.scrollRestoration != null ||
                session.hasPendingEditWindowAction
            ) {
                return@snapshotFlow null
            }
            val scrollValuePixels = draft.scrollState.value
            val maxScrollPixels = draft.scrollState.maxValue
            val viewportHeightPixels = draft.scrollState.viewportSize.coerceAtLeast(1)
            val direction =
                automaticEditWindowDirection(
                    scrollValuePixels = scrollValuePixels,
                    maxScrollPixels = maxScrollPixels,
                    viewportHeightPixels = viewportHeightPixels,
                    hasPrevious = edit.snapshot.hasPrevious,
                    hasNext = edit.snapshot.hasNext
                ) ?: return@snapshotFlow null
            val maxContentYPixels = (layoutResult.size.height - 1).coerceAtLeast(0)
            val anchorContentYPixels =
                Math.addExact(
                    scrollValuePixels,
                    viewportHeightPixels / 2
                ).coerceIn(0, maxContentYPixels)
            val anchorLine =
                layoutResult.getLineForVerticalPosition(anchorContentYPixels.toFloat())
            val anchorContentTopPixels = layoutResult.getLineTop(anchorLine).roundToInt()
            AutomaticEditWindowRequest(
                direction = direction,
                localAnchorUtf16Offset = layoutResult.getLineStart(anchorLine),
                anchorViewportTopPixels =
                    Math.subtractExact(anchorContentTopPixels, scrollValuePixels)
            )
        }.collect { request ->
            request ?: return@collect
            session.requestAutomaticEditWindowTransition(
                draft = draft,
                towardNext = request.direction == AutomaticEditWindowDirection.Later,
                localAnchorUtf16Offset = request.localAnchorUtf16Offset,
                anchorViewportTopPixels = request.anchorViewportTopPixels
            )
        }
    }

    val density = LocalDensity.current
    val bottomOverlayState = LocalEditorBottomOverlayState.current
    val bottomOverlayPx = bottomOverlayState?.heightPx ?: 0
    val bottomOverlayDp = with(density) { bottomOverlayPx.toDp() }
    val hasBottomBanners =
        fieldMessage != null ||
            pendingActionMessage != null ||
            state.staleEditRecovery != null ||
            (state.editorMessage != null && hasChanges)
    val caretRevealMarginPx = with(density) { 12.dp.toPx() }
    val maxBottomClearancePx by remember(
        bottomOverlayPx,
        hasBottomBanners,
        textLayoutResult,
        viewportClip,
        selectionInset
    ) {
        derivedStateOf {
            if (hasBottomBanners || bottomOverlayPx <= 0) {
                0f
            } else {
                computeMaxBottomClearancePx(
                    viewportHeightPx = viewportClip?.height ?: 0f,
                    topInsetPx = selectionInset,
                    contentHeightPx = (textLayoutResult?.size?.height ?: 0).toFloat(),
                    bottomOverlayPx = bottomOverlayPx.toFloat()
                )
            }
        }
    }
    val currentMaxClearance by rememberUpdatedState(maxBottomClearancePx)
    LaunchedEffect(draft.scrollState) {
        snapshotFlow {
            Triple(
                currentMaxClearance,
                draft.scrollState.value,
                draft.scrollState.maxValue
            )
        }.collect { (maxClearance, scrollValue, scrollMax) ->
            if (scrollValue < scrollMax && bottomClearancePx > 0f) {
                bottomClearancePx = 0f
            } else if (bottomClearancePx > maxClearance) {
                bottomClearancePx = maxClearance
            }
        }
    }
    val bottomClearanceConnection = remember(draft.scrollState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0f && bottomClearancePx > 0f) {
                    val consumed = minOf(available.y, bottomClearancePx)
                    bottomClearancePx -= consumed
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                val maxClearance = currentMaxClearance
                if (
                    available.y < 0f &&
                    maxClearance > 0f &&
                    draft.scrollState.value >= draft.scrollState.maxValue &&
                    bottomClearancePx < maxClearance
                ) {
                    val delta = minOf(-available.y, maxClearance - bottomClearancePx)
                    bottomClearancePx += delta
                    return Offset(0f, -delta)
                }
                return Offset.Zero
            }
        }
    }
    val capsuleObstructionPx = remember(bottomOverlayState, viewportClip) {
        derivedStateOf {
            val clip = viewportClip
            if (hasBottomBanners || bottomOverlayState == null || clip == null || clip.isEmpty) {
                0f
            } else {
                bottomOverlayState.obstructions.values
                    .filter { rect -> rect.right > clip.left && rect.left < clip.right }
                    .maxOfOrNull { rect -> (clip.bottom - rect.top).coerceAtLeast(0f) }
                    ?: 0f
            }
        }
    }.value
    LaunchedEffect(
        textFieldState.selection,
        textFieldState.text,
        textLayoutResult,
        viewportClip,
        capsuleObstructionPx,
        hasBottomBanners,
        draft.isEditorFocused
    ) {
        val layout = textLayoutResult ?: return@LaunchedEffect
        val viewportPx = viewportClip?.height ?: 0f
        if (
            hasBottomBanners ||
            capsuleObstructionPx <= 0f ||
            !draft.isEditorFocused ||
            viewportPx <= 0f
        ) {
            return@LaunchedEffect
        }
        val textLen = layout.layoutInput.text.length
        val caretOffset = textFieldState.selection.end.coerceIn(0, textLen)
        val caretRect = layout.getCursorRect(caretOffset)
        val adjustment = computeBottomClearanceCaretAdjustment(
            viewportHeightPx = viewportPx,
            topInsetPx = selectionInset,
            caretTopPx = caretRect.top,
            caretBottomPx = caretRect.bottom,
            scrollValuePx = draft.scrollState.value,
            scrollMaxPx = draft.scrollState.maxValue,
            currentBottomClearancePx = bottomClearancePx,
            maxBottomClearancePx = maxBottomClearancePx,
            capsuleObstructionPx = capsuleObstructionPx,
            caretRevealMarginPx = caretRevealMarginPx
        )
        if (adjustment.scrollAdvancePx > 0) {
            draft.scrollState.scrollTo(draft.scrollState.value + adjustment.scrollAdvancePx)
        }
        if (adjustment.nextBottomClearancePx != bottomClearancePx) {
            bottomClearancePx = adjustment.nextBottomClearancePx
        }
    }

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .imePadding()
    ) {
        val controlsMaxHeight = recoveryPanelMaxHeight(maxHeight)
        val showLineNumbers =
            appearance.value(AppearanceOption.LineNumbers, AppearanceGroup.Source) == 1f
        val lineNumberStyle =
            MaterialTheme.typography.labelMedium.copy(
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        val textMeasurer = rememberTextMeasurer()
        val maxLineNumber = remember(
            showLineNumbers,
            edit.snapshot.metrics.lineCount,
            edit.snapshot.start.line,
            textFieldState.text.length
        ) {
            if (!showLineNumbers) {
                1L
            } else {
                var newlines = 0L
                val current = textFieldState.text
                for (i in 0 until current.length) {
                    if (current[i] == '\n') newlines += 1L
                }
                maxOf(edit.snapshot.metrics.lineCount, edit.snapshot.start.line + 1L + newlines)
                    .coerceAtLeast(1L)
            }
        }
        val measuredLabelWidth = remember(showLineNumbers, maxLineNumber, lineNumberStyle, textMeasurer, density) {
            if (!showLineNumbers) {
                0.dp
            } else {
                with(density) {
                    textMeasurer
                        .measure(
                            text = maxLineNumber.toString(),
                            style = lineNumberStyle,
                            maxLines = 1
                        ).size.width
                        .toDp()
                }
            }
        }
        val gutterWidth = if (showLineNumbers) lineNumberGutterWidth(measuredLabelWidth) else 0.dp
        val column = appearance.textColumn(AppearanceGroup.Source, maxWidth, gutterWidth)
        val activeGutterWidth = if (showLineNumbers) column.gutter else 0.dp
        val columnModifier = Modifier.padding(start = column.textStart).width(column.textWidth)
        val resolvedSourceStyle = sourceStyle.copy(
            color = if (sourceStyle.color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else sourceStyle.color
        )
        val placeholderColor = MaterialTheme.colorScheme.onSurfaceVariant
        val cursorColor = if (fieldMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .padding(start = (column.textStart - activeGutterWidth).coerceAtLeast(0.dp))
                    .width(column.textWidth + activeGutterWidth)
                    .weight(1f)
            ) {
                if (activeGutterWidth > 0.dp) {
                    val topPaddingPx = with(density) { 16.dp.toPx() }
                    val gutterEndGapPx = with(density) { 12.dp.toPx() }
                    val startingLogicalLine = edit.snapshot.start.line + 1L
                    androidx.compose.foundation.Canvas(
                        modifier = Modifier
                            .width(activeGutterWidth)
                            .fillMaxHeight()
                            .clipToBounds()
                    ) {
                        val layout = textLayoutResult ?: return@Canvas
                        if (layout.lineCount <= 0) return@Canvas
                        val scrollY = draft.scrollState.value.toFloat() + bottomClearancePx
                        val text = layout.layoutInput.text
                        val firstVisual = layout.getLineForVerticalPosition(
                            (scrollY - topPaddingPx).coerceAtLeast(0f)
                        )
                        val lastVisual = layout.getLineForVerticalPosition(
                            (scrollY - topPaddingPx + size.height).coerceAtLeast(0f)
                        )
                        val firstStart = layout.getLineStart(firstVisual).coerceIn(0, text.length)
                        var logicalLine = startingLogicalLine
                        for (i in 0 until firstStart) {
                            if (text[i] == '\n') logicalLine += 1L
                        }
                        for (visualLine in firstVisual..lastVisual) {
                            val lineStart = layout.getLineStart(visualLine).coerceIn(0, text.length)
                            val isLogicalStart =
                                visualLine == 0 || (lineStart > 0 && text[lineStart - 1] == '\n')
                            if (visualLine > firstVisual && isLogicalStart) {
                                logicalLine += 1L
                            }
                            if (isLogicalStart) {
                                val measured = textMeasurer.measure(
                                    text = logicalLine.toString(),
                                    style = lineNumberStyle,
                                    maxLines = 1
                                )
                                val drawY = topPaddingPx + layout.getLineBaseline(visualLine) -
                                    scrollY - measured.firstBaseline
                                val drawX = (size.width - gutterEndGapPx - measured.size.width)
                                    .coerceAtLeast(0f)
                                drawText(
                                    textLayoutResult = measured,
                                    topLeft = Offset(drawX, drawY)
                                )
                            }
                        }
                    }
                }
                Box(
                    Modifier
                        .width(column.textWidth)
                        .fillMaxHeight()
                        .clipToBounds()
                        .nestedScroll(bottomClearanceConnection)
                        .onGloballyPositioned { viewportClip = it.boundsInWindow() }
                        .then(if (wraps) Modifier else Modifier.horizontalScroll(horizontalScroll))
                ) {
                CompositionLocalProvider(LocalClipboard provides fieldClipboard) {
                BasicTextField(
                    state = textFieldState,
                        modifier =
                            Modifier
                                .widthIn(min = column.textWidth)
                                .fillMaxWidth()
                                .fillMaxHeight()
                                .offset { IntOffset(0, -bottomClearancePx.roundToInt()) }
                                .semantics { if (fieldMessage != null) error(fieldMessage) }
                                .focusRequester(focusRequester)
                                .then(sourceSelectionModifier)
                                .contentReceiver(ReceiveContentListener { content ->
                                    val clip = content.clipEntry.clipData
                                    val text = if (content.source == TransferableContent.Source.Clipboard && clip.itemCount == 1)
                                        clip.getItemAt(0).text else null
                                    if (text == null || text.length <= EDIT_DRAFT_MAX_UTF16_UNITS) {
                                        if (content.source == TransferableContent.Source.Clipboard ||
                                            content.source == TransferableContent.Source.DragAndDrop ||
                                            content.source == TransferableContent.Source.Keyboard
                                        ) {
                                            assistSuppression.arm()
                                        }
                                        content
                                    } else {
                                        if (session.canApplyEditorInput(draft)) {
                                            val proposal = EditorBulkEdit.insertion(textFieldState.text.toString(),
                                                textFieldState.selection, text.toString())
                                            if (proposal == null) draft.reportInputRejection(EditorInputRejection.BulkSize)
                                            else {
                                                draft.clearInputRejection()
                                                if (!session.requestBulkEdit(draft, proposal))
                                                    draft.reportInputRejection(EditorInputRejection.BulkUnavailable)
                                            }
                                        }
                                        null
                                    }
                                })
                                .onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown) {
                                        val isPasteKey =
                                            ((event.isCtrlPressed || event.isMetaPressed) &&
                                                !event.isAltPressed && event.key == Key.V) ||
                                                (event.isShiftPressed && !event.isCtrlPressed &&
                                                    !event.isAltPressed && event.key == Key.Insert) ||
                                                event.key == Key.Paste
                                        if (isPasteKey) {
                                            assistSuppression.arm()
                                        } else if (
                                            event.key != Key.CtrlLeft && event.key != Key.CtrlRight &&
                                            event.key != Key.ShiftLeft && event.key != Key.ShiftRight &&
                                            event.key != Key.AltLeft && event.key != Key.AltRight &&
                                            event.key != Key.MetaLeft && event.key != Key.MetaRight
                                        ) {
                                            assistSuppression.clear()
                                        }
                                    }
                                    if (
                                        handleEditorHistoryShortcut(
                                            event = event,
                                            onUndo = session::requestUndo,
                                            onRedo = session::requestRedo
                                        )
                                    ) {
                                        true
                                    } else if (event.type == KeyEventType.KeyDown && canAcceptInput && !hasComposition) {
                                        when {
                                            event.key == Key.Tab && !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed ->
                                                applyLocalEdit { text, sel ->
                                                    indentSelectedLines(
                                                        text = text,
                                                        start = sel.min,
                                                        end = sel.max,
                                                        outdent = event.isShiftPressed,
                                                        hasPreviousWindow = edit.snapshot.hasPrevious
                                                    )
                                                }
                                            event.isAltPressed && !event.isCtrlPressed && !event.isMetaPressed &&
                                                (event.key == Key.DirectionUp || event.key == Key.DirectionDown) ->
                                                applyLocalEdit { text, sel ->
                                                    moveSelectedLines(
                                                        text = text,
                                                        selection = sel,
                                                        moveUp = event.key == Key.DirectionUp,
                                                        hasPreviousWindow = edit.snapshot.hasPrevious,
                                                        hasNextWindow = edit.snapshot.hasNext
                                                    )
                                                }
                                            (event.isCtrlPressed || event.isMetaPressed) && !event.isAltPressed && !event.isShiftPressed &&
                                                event.key == Key.D ->
                                                applyLocalEdit { text, sel ->
                                                    duplicateSelectedLines(
                                                        text = text,
                                                        selection = sel,
                                                        hasPreviousWindow = edit.snapshot.hasPrevious,
                                                        hasNextWindow = edit.snapshot.hasNext
                                                    )
                                                }
                                            isMarkdownEditable && (event.isCtrlPressed || event.isMetaPressed) &&
                                                !event.isAltPressed && !event.isShiftPressed -> when (event.key) {
                                                Key.B -> applyLocalEdit { text, sel ->
                                                    applyMarkdownFormatAction(text, sel, MarkdownFormatAction.Bold)
                                                }
                                                Key.I -> applyLocalEdit { text, sel ->
                                                    applyMarkdownFormatAction(text, sel, MarkdownFormatAction.Italic)
                                                }
                                                Key.E -> applyLocalEdit { text, sel ->
                                                    applyMarkdownFormatAction(text, sel, MarkdownFormatAction.Code)
                                                }
                                                Key.K -> applyLocalEdit { text, sel ->
                                                    applyMarkdownFormatAction(text, sel, MarkdownFormatAction.Link)
                                                }
                                                else -> false
                                            }
                                            else -> false
                                        }
                                    } else {
                                        false
                                    }
                                }
                                .onFocusChanged { focusState ->
                                    if (focusState.isFocused) {
                                        hasFocusedInComposition = true
                                        draft.updateEditorFocusIntent(
                                            isFocused = true,
                                            canClear = true
                                        )
                                    } else if (hasFocusedInComposition) {
                                        draft.updateEditorFocusIntent(
                                            isFocused = false,
                                            canClear =
                                                lifecycle.currentState.isAtLeast(
                                                    Lifecycle.State.RESUMED
                                                ) && windowInfo.isWindowFocused
                                        )
                                    }
                                },
                        enabled =
                            isActiveEditFieldEnabled(
                                status = state.status,
                                preserveInputSession = session.preservesEditorInputSession
                            ),
                        readOnly =
                            isActiveEditFieldReadOnly(
                                canAcceptInput = canAcceptInput,
                                preserveInputSession = session.preservesEditorInputSession
                            ),
                        inputTransformation = inputTransformation,
                        outputTransformation = outputTransformation,
                        lineLimits = TextFieldLineLimits.MultiLine(),
                        onTextLayout = { getResult ->
                            val result = getResult()
                            if (result != null) {
                                draft.restoreViewportAfterWidthChange(result.size.width, effective)
                            }
                            textLayoutResult = result
                        },
                        scrollState = draft.scrollState,
                        cursorBrush = SolidColor(cursorColor),
                        textStyle = resolvedSourceStyle,
                        decorator = TextFieldDecorator { innerTextField ->
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(top = 16.dp)
                            ) {
                                if (editsCompleteDocument && textFieldState.text.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.source_start_writing),
                                        style = resolvedSourceStyle.copy(color = placeholderColor)
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                }
                }
            }
            val showMarkdownStrip =
                isMarkdownEditable &&
                    WindowInsets.isImeVisible &&
                    !session.isFindVisible &&
                    session.documentSelection == null &&
                    !hasBottomBanners &&
                    this@BoxWithConstraints.maxHeight >= 220.dp
            if (showMarkdownStrip) {
                MarkdownFormattingStrip(
                    onAction = { action ->
                        applyLocalEdit { text, sel ->
                            applyMarkdownFormatAction(
                                text = text,
                                selection = sel,
                                action = action,
                                hasPreviousWindow = edit.snapshot.hasPrevious
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = bottomOverlayDp)
                )
            }
            if (hasBottomBanners) {
                Column(modifier = Modifier.padding(bottom = bottomOverlayDp)) {
                    if (fieldMessage != null) {
                        Text(fieldMessage, columnModifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                    val controlsModifier =
                        Modifier
                            .heightIn(max = controlsMaxHeight)
                            .padding(
                                horizontal = EditorHorizontalPadding,
                                vertical = EditorCompactSpacing
                            )
                    when {
                        pendingActionMessage != null ->
                            PendingEditWindowActionMessage(
                                message = pendingActionMessage,
                                canCancel = session.canCancelPendingEditWindowAction,
                                onCancel = session::cancelPendingEditWindowAction,
                                modifier = controlsModifier
                            )

                        state.staleEditRecovery != null ||
                            (state.editorMessage != null && hasChanges) ->
                            EditWindowRecoveryActions(
                                staleRecovery = state.staleEditRecovery,
                                showRetry = state.editorMessage != null && hasChanges,
                                canRetry = !isBusy && canAcceptInput && !hasComposition,
                                onReloadStale = { session.reloadStaleActiveEdit(draft) },
                                onRetry = { session.retryActiveEdit(draft) },
                                modifier = controlsModifier
                            )
                    }
                }
            }
        }
    }
}

/** Displays recovery actions below a bounded editor. */
@Composable
private fun EditWindowRecoveryActions(
    staleRecovery: StaleEditRecovery?,
    showRetry: Boolean,
    canRetry: Boolean,
    onReloadStale: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier =
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(EditorSectionSpacing),
            verticalArrangement = Arrangement.spacedBy(EditorCompactSpacing)
        ) {
            if (staleRecovery != null) {
                FilledTonalButton(
                    onClick = onReloadStale,
                    modifier = Modifier.fillMaxWidth(),
                    shapes = ButtonDefaults.shapes()
                ) {
                    Text(
                        if (staleRecovery == StaleEditRecovery.VerifyAppliedEdit) {
                            stringResource(R.string.source_reload)
                        } else {
                            stringResource(R.string.source_discard_reload)
                        }
                    )
                }
                return@Column
            }
            if (showRetry) {
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = canRetry
                ) {
                    Text(stringResource(R.string.source_retry_changes))
                }
            }
        }
    }
}

/** Displays one cancellable action waiting for field synchronization. */
@Composable
private fun PendingEditWindowActionMessage(
    message: String,
    canCancel: Boolean,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier =
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(EditorSectionSpacing),
            verticalArrangement = Arrangement.spacedBy(EditorCompactSpacing)
        ) {
            Text(
                text = message,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium
            )
            if (canCancel) {
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.source_stay))
                }
            }
        }
    }
}

/** Computes the maximum bottom clearance needed so trailing lines clear the floating toolbar. */
internal fun computeMaxBottomClearancePx(
    viewportHeightPx: Float,
    topInsetPx: Float,
    contentHeightPx: Float,
    bottomOverlayPx: Float
): Float {
    if (viewportHeightPx <= 0f || bottomOverlayPx <= 0f) return 0f
    val contentBottomPx = topInsetPx + contentHeightPx
    return (contentBottomPx - (viewportHeightPx - bottomOverlayPx)).coerceIn(0f, bottomOverlayPx)
}

/** Describes one bounded scroll and bottom-clearance adjustment to keep the caret visible. */
internal data class BottomClearanceCaretAdjustment(
    val scrollAdvancePx: Int,
    val nextBottomClearancePx: Float
)

/**
 * Keeps the focused caret clear of the floating bottom toolbar using the actual container
 * viewport height rather than BasicTextField's loose content-sized ScrollState viewport.
 */
internal fun computeBottomClearanceCaretAdjustment(
    viewportHeightPx: Float,
    topInsetPx: Float,
    caretTopPx: Float,
    caretBottomPx: Float,
    scrollValuePx: Int,
    scrollMaxPx: Int,
    currentBottomClearancePx: Float,
    maxBottomClearancePx: Float,
    capsuleObstructionPx: Float,
    caretRevealMarginPx: Float
): BottomClearanceCaretAdjustment {
    if (viewportHeightPx <= 0f || capsuleObstructionPx <= 0f) {
        return BottomClearanceCaretAdjustment(0, currentBottomClearancePx)
    }
    val visibleCaretTop = topInsetPx + caretTopPx - scrollValuePx - currentBottomClearancePx
    if (visibleCaretTop < 0f && currentBottomClearancePx > 0f) {
        return BottomClearanceCaretAdjustment(
            scrollAdvancePx = 0,
            nextBottomClearancePx = (currentBottomClearancePx + visibleCaretTop).coerceAtLeast(0f)
        )
    }
    val visibleCaretBottom = topInsetPx + caretBottomPx - scrollValuePx - currentBottomClearancePx
    val safeBottom = viewportHeightPx - capsuleObstructionPx - caretRevealMarginPx
    if (safeBottom <= 0f || visibleCaretBottom <= safeBottom) {
        return BottomClearanceCaretAdjustment(0, currentBottomClearancePx)
    }
    val needed = visibleCaretBottom - safeBottom
    val scrollRoom = (scrollMaxPx - scrollValuePx).coerceAtLeast(0)
    val scrollAdvance = minOf(needed.roundToInt(), scrollRoom)
    val remaining = needed - scrollAdvance
    val topHeadroom = (visibleCaretTop - scrollAdvance).coerceAtLeast(0f)
    val clearanceAdvance = minOf(remaining, topHeadroom)
    val nextClearance =
        if (clearanceAdvance > 0f && maxBottomClearancePx > currentBottomClearancePx) {
            (currentBottomClearancePx + clearanceAdvance).coerceAtMost(maxBottomClearancePx)
        } else {
            currentBottomClearancePx
        }
    return BottomClearanceCaretAdjustment(
        scrollAdvancePx = scrollAdvance,
        nextBottomClearancePx = nextClearance
    )
}

/** Displays one adaptive logical line identity without exposing visual markers. */
@Composable
private fun LineNumber(
    logicalLine: Long,
    isContinuation: Boolean,
    description: String,
    lineNumberStyle: TextStyle,
    lineNumberWidth: Dp,
    useGutter: Boolean,
    modifier: Modifier = Modifier
) {
    val displayLineNumber = logicalLine + 1L
    Text(
        text =
            if (useGutter) {
                if (isContinuation) "↳ $displayLineNumber" else displayLineNumber.toString()
            } else {
                description
            },
        modifier =
            if (useGutter) {
                modifier
                    .width(lineNumberWidth)
                    .padding(end = EditorCompactSpacing)
                    .clearAndSetSemantics { contentDescription = description }
            } else {
                modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics { contentDescription = description }
            },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = lineNumberStyle,
        textAlign = if (useGutter) TextAlign.End else TextAlign.Start
    )
}
