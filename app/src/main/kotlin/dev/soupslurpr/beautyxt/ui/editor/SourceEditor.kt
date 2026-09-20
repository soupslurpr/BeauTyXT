/* Displays bounded source windows while retaining input and scroll state. */
package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
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
import kotlinx.coroutines.flow.first

private const val VIEWPORT_FAILURE_ITEM_KEY = "viewport-failure"

private val SourceEditorMaxWidth = 960.dp

/** Contains one stable visual-line anchor selected for automatic prefetch. */
private data class AutomaticEditWindowRequest(
    val direction: AutomaticEditWindowDirection,
    val localAnchorUtf16Offset: Int,
    val anchorViewportTopPixels: Int
)

/** Stores one block's text layout and its content origin within the lazy item. */
private data class BlockTextMeasurement(
    val layoutResult: TextLayoutResult,
    val contentTopInBlockPixels: Int
) {
    init {
        require(contentTopInBlockPixels >= 0) { "text content top must be nonnegative" }
    }
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
    LaunchedEffect(session, restoration) {
        val anchor = restoration ?: return@LaunchedEffect
        if (anchor.revision != state.metrics?.revision) {
            session.consumeReadOnlySourceScrollRestoration(anchor)
            return@LaunchedEffect
        }
        val target = snapshotFlow {
            val index = state.blocks.indexOfFirst {
                it.block.globalUtf16Start == anchor.sourceUtf16Offset
            }
            val block = state.blocks.getOrNull(index) ?: return@snapshotFlow null
            val measurement = blockTextMeasurements[block.key] ?: return@snapshotFlow null
            index to (
                measurement.contentTopInBlockPixels +
                    measurement.layoutResult.getLineTop(0).roundToInt() -
                    anchor.viewportTopOffsetPixels
                )
        }.first { it != null } ?: return@LaunchedEffect
        viewportListState.scrollToItem(target.first, target.second)
        session.consumeReadOnlySourceScrollRestoration(anchor)
    }
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
    LaunchedEffect(session, metrics.revision, visibleViewportAnchor, restoration) {
        if (restoration != null) return@LaunchedEffect
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

    Column(modifier = modifier.fillMaxSize()) {
        BoxWithConstraints(modifier = Modifier.weight(1f)) {
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
            val gutterWidth = lineNumberGutterWidth(measuredLabelWidth)
            val documentContentWidth =
                (maxWidth - EditorHorizontalPadding * 2).coerceAtLeast(0.dp)
            val useLineNumberGutter =
                usesLineNumberGutter(
                    availableWidth = documentContentWidth,
                    gutterWidth = gutterWidth
                )
            LazyColumn(
                state = session.viewportListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding =
                    PaddingValues(
                        horizontal = EditorHorizontalPadding,
                        vertical = EditorVerticalPadding
                    )
            ) {
                items(
                    items = state.blocks,
                    key = EditorRenderBlock::key,
                    contentType = { "document-block" }
                ) { editorBlock ->
                    ReadOnlyBlock(
                        editorBlock = editorBlock,
                        lineNumberStyle = lineNumberStyle,
                        lineNumberWidth = gutterWidth,
                        useLineNumberGutter = useLineNumberGutter,
                        matchRange = visibleMatchRange,
                        onTextMeasurement = { measurement ->
                            blockTextMeasurements[editorBlock.key] = measurement
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
                                .padding(
                                    horizontal = EditorHorizontalPadding,
                                    vertical = EditorVerticalPadding
                                )
                    )
                }

                is EditorViewportOverlay.OpeningLine -> {
                    val displayLine = Math.incrementExact(overlay.targetLogicalLine)
                    ViewportProgressMessage(
                        message = stringResource(R.string.source_opening_line, displayLine),
                        modifier =
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(
                                    horizontal = EditorHorizontalPadding,
                                    vertical = EditorVerticalPadding
                                )
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
                                .padding(
                                    horizontal = EditorHorizontalPadding,
                                    vertical = EditorVerticalPadding
                                )
                    )
                }

                is EditorViewportOverlay.Failure -> {
                    EditorNoticeRow(
                        message = overlay.message.asString(),
                        modifier =
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(
                                    horizontal = EditorHorizontalPadding,
                                    vertical = EditorVerticalPadding
                                )
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
        if (!session.isFindVisible) {
            HorizontalDivider()
            DocumentViewportFooter(
                visibleRange = visibleLineRange,
                fallbackLogicalLine = firstCachedLogicalLine,
                lineCount = lineCount,
                navigationEnabled = session.canNavigateToLine,
                onNavigate = { logicalLine ->
                    session.showGoToLineDialog(logicalLine)
                }
            )
        }
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
        color = MaterialTheme.colorScheme.surfaceContainer,
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

/** Displays one selectable Rust render block in the read-only fallback viewport. */
@Composable
private fun ReadOnlyBlock(
    editorBlock: EditorRenderBlock,
    lineNumberStyle: TextStyle,
    lineNumberWidth: Dp,
    useLineNumberGutter: Boolean,
    matchRange: Utf16Range?,
    onTextMeasurement: (BlockTextMeasurement) -> Unit
) {
    val block = editorBlock.block
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
    var textLayoutResult by remember(editorBlock.key) { mutableStateOf<TextLayoutResult?>(null) }
    var textContentTopInBlockPixels by
        remember(editorBlock.key) { mutableStateOf<Int?>(null) }
    val currentOnTextMeasurement by rememberUpdatedState(onTextMeasurement)
    LaunchedEffect(editorBlock.key, textLayoutResult, textContentTopInBlockPixels) {
        val layoutResult = textLayoutResult ?: return@LaunchedEffect
        val contentTop = textContentTopInBlockPixels ?: return@LaunchedEffect
        currentOnTextMeasurement(
            BlockTextMeasurement(
                layoutResult = layoutResult,
                contentTopInBlockPixels = contentTop
            )
        )
    }
    val blockModifier =
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
    if (useLineNumberGutter) {
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
            ReadOnlyBlockText(
                text = block.text,
                highlightRange = highlightRange,
                onTextLayout = { result -> textLayoutResult = result },
                onTextContentPositioned = { top -> textContentTopInBlockPixels = top },
                modifier = Modifier.weight(1f).alignByBaseline()
            )
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
            ReadOnlyBlockText(
                text = block.text,
                highlightRange = highlightRange,
                onTextLayout = { result -> textLayoutResult = result },
                onTextContentPositioned = { top -> textContentTopInBlockPixels = top },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** Displays selectable block text and reports its origin for viewport restoration. */
@Composable
private fun ReadOnlyBlockText(
    text: String,
    highlightRange: TextRange?,
    onTextLayout: (TextLayoutResult) -> Unit,
    onTextContentPositioned: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentOnTextContentPositioned by rememberUpdatedState(onTextContentPositioned)
    val highlightBackground = MaterialTheme.colorScheme.secondaryContainer
    val highlightForeground = MaterialTheme.colorScheme.onSecondaryContainer
    val renderedText =
        remember(text, highlightRange, highlightBackground, highlightForeground) {
            require(highlightRange == null || highlightRange.end <= text.length) {
                "find highlight exceeds its render block"
            }
            if (highlightRange == null) {
                AnnotatedString(text)
            } else {
                buildAnnotatedString {
                    append(text)
                    addStyle(
                        style =
                            SpanStyle(
                                color = highlightForeground,
                                background = highlightBackground
                            ),
                        start = highlightRange.start,
                        end = highlightRange.end
                    )
                }
            }
        }
    val positionedModifier =
        modifier.onGloballyPositioned { coordinates ->
            val contentTopInBlockPixels = coordinates.positionInParent().y
            check(contentTopInBlockPixels >= 0f && contentTopInBlockPixels.isFinite()) {
                "text content position is invalid"
            }
            currentOnTextContentPositioned(contentTopInBlockPixels.roundToInt())
        }
    ReadingSelectionContainer(modifier = positionedModifier) {
        Text(
            text = renderedText,
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            onTextLayout = onTextLayout,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

/** Displays one stable multiline text field backed by a bounded Rust window. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActiveEditWindowEditor(
    session: EditorSession,
    draft: ActiveEditDraft,
    modifier: Modifier = Modifier
) {
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
    val hasChanges = draft.hasChanges
    val hasComposition by
        remember(edit.generation, textFieldState) {
            derivedStateOf { textFieldState.composition != null }
        }
    val canAcceptInput = session.canApplyEditorInput(draft)
    val inputTransformation =
        remember(draft) {
            editorInputTransformation(
                canAcceptInput = { session.canApplyEditorInput(draft) },
                onBulkEdit = { proposal -> session.requestBulkEdit(draft, proposal) },
                onRejection = draft::reportInputRejection,
                clearRejection = draft::clearInputRejection
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
    LaunchedEffect(draft, draft.scrollRestoration, textLayoutResult) {
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
                restoration.anchor.viewportTopOffsetPixels
            ).coerceAtLeast(0)
        withFrameNanos { _ -> }
        restoreEditWindowScroll(
            scrollState = draft.scrollState,
            targetScrollPixels = targetScrollPixels,
            preserveScrollMomentum = restoration.preserveScrollMomentum
        )
        draft.consumeScrollRestoration(restoration)
    }
    LaunchedEffect(session, draft, edit, textLayoutResult) {
        snapshotFlow {
            val layoutResult = textLayoutResult ?: return@snapshotFlow null
            if (
                draft.edit != edit ||
                !layoutResult.layoutInput.text.text.contentEquals(edit.snapshot.text) ||
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
            val viewportHeightPixels =
                (layoutResult.size.height - maxScrollPixels).coerceAtLeast(1)
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

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .imePadding()
    ) {
        val controlsMaxHeight = recoveryPanelMaxHeight(maxHeight)
        Column(modifier = Modifier.fillMaxSize()) {
            TextField(
                state = textFieldState,
                modifier =
                    Modifier
                        .weight(1f)
                        .align(Alignment.CenterHorizontally)
                        .widthIn(max = SourceEditorMaxWidth)
                        .fillMaxWidth()
                        .padding(horizontal = DocumentPageGutter - EditorHorizontalPadding)
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { event ->
                            handleEditorHistoryShortcut(
                                event = event,
                                onUndo = session::requestUndo,
                                onRedo = session::requestRedo
                            )
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
                isError = fieldMessage != null,
                placeholder =
                    if (editsCompleteDocument) {
                        { Text(stringResource(R.string.source_start_writing)) }
                    } else {
                        null
                    },
                supportingText =
                    if (fieldMessage != null) {
                        {
                            Text(
                                text = fieldMessage,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                            )
                        }
                    } else {
                        null
                    },
                inputTransformation = inputTransformation,
                lineLimits = TextFieldLineLimits.MultiLine(),
                onTextLayout = { getResult ->
                    val result = getResult()
                    if (result != null) {
                        draft.restoreViewportAfterWidthChange(result.size.width)
                    }
                    textLayoutResult = result
                },
                scrollState = draft.scrollState,
                colors =
                    TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        errorContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        errorIndicatorColor = Color.Transparent
                    ),
                shape = MaterialTheme.shapes.medium,
                textStyle =
                    MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.sp
                    )
            )
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
                    modifier = Modifier.fillMaxWidth()
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
