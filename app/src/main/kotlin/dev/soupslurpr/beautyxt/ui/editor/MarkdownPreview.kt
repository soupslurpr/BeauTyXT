/* Coordinates rendered Markdown navigation and virtualized preview items. */
package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.markdown.MarkdownBlockKind
import dev.soupslurpr.beautyxt.markdown.MarkdownInlinePresentation
import dev.soupslurpr.beautyxt.markdown.MarkdownInlineSpan
import dev.soupslurpr.beautyxt.markdown.MarkdownLinkAction
import dev.soupslurpr.beautyxt.markdown.MarkdownRenderBlock
import dev.soupslurpr.beautyxt.markdown.markdownLinkAction
import dev.soupslurpr.beautyxt.markdown.markdownRenderedPositionForSourceOffset
import dev.soupslurpr.beautyxt.markdown.renderedUtf16OffsetForSource
import dev.soupslurpr.beautyxt.markdown.sourceUtf16OffsetForRendered
import dev.soupslurpr.beautyxt.ui.asString
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val MARKDOWN_PREVIEW_RESTORATION_TIMEOUT_MILLIS = 2_000L

internal val LocalMarkdownFootnoteNumbers =
    staticCompositionLocalOf<Map<String, Int>> { emptyMap() }

private val MarkdownPreviewMaxWidth = 680.dp
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
    val key: MarkdownPreviewTextKey,
    val block: MarkdownRenderBlock,
    val sourceText: String,
    val sourceSpans: List<MarkdownInlineSpan>,
    val footnotePresentation: MarkdownInlinePresentation?,
    val layoutResult: TextLayoutResult,
    val topInWindowPixels: Int
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
    var hasRootPosition by mutableStateOf(false)
    val measurements = mutableStateMapOf<MarkdownPreviewTextKey, MarkdownPreviewTextMeasurement>()

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
    fun measurementFor(blockIndex: Int, renderedUtf16Offset: Int): MarkdownPreviewTextMeasurement? {
        val candidates =
            measurements.values
                .filter { measurement -> measurement.key.blockIndex == blockIndex }
                .sortedBy { measurement -> measurement.key.renderedStart }
        return candidates.firstOrNull { measurement ->
            renderedUtf16Offset in
                measurement.key.renderedStart..measurement.key.renderedEnd
        } ?: candidates.firstOrNull { measurement ->
            measurement.key.renderedStart > renderedUtf16Offset
        } ?: candidates.lastOrNull()
    }

    /** A joined diagram still owns the original source of every covered transport fragment. */
    fun measurementForSource(sourceOffset: Long): MarkdownPreviewTextMeasurement? =
        measurements.values.firstOrNull { measurement ->
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
internal val LocalMarkdownPreviewBlockIndex = staticCompositionLocalOf<Int?> { null }

/** Displays the newest private Markdown render state. */
@Composable
internal fun MarkdownPreviewContent(session: EditorSession, modifier: Modifier = Modifier) {
    when (val status = session.markdownPreviewStatus) {
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
                onRetry = session::retryMarkdownPreview,
                onEdit = session::showTextEditor,
                modifier = modifier
            )

        is MarkdownPreviewStatus.Ready -> {
            val document = status.document
            val readingPageModifier = modifier.editOnMarkdownBackgroundTap(session, status.revision)
            if (document.blocks.isEmpty()) {
                MarkdownPreviewEmpty(
                    isViewOnly = session.isViewOnly,
                    onEdit = session::showTextEditor,
                    modifier = readingPageModifier
                )
                return
            }
            val previewItems = status.layout.items
            val footnoteTargets = status.layout.footnotes
            val navigationState =
                remember(session, status.revision) {
                    MarkdownPreviewNavigationState(
                        revision = status.revision,
                        isEditable = !session.isViewOnly,
                        onEditSource = { sourceOffset, viewportTopOffsetPixels ->
                            session.showTextEditorAtSource(
                                revision = status.revision,
                                utf16Offset = sourceOffset,
                                viewportTopOffsetPixels = viewportTopOffsetPixels
                            )
                        }
                    )
                }
            val restorationPosition =
                remember(document.blocks, status.scrollRestoration) {
                    status.scrollRestoration?.let { restoration ->
                        markdownRenderedPositionForSourceOffset(
                            blocks = document.blocks,
                            sourceUtf16Offset = restoration.sourceUtf16Offset
                        )
                    }
                }
            val illustrationResults = rememberMarkdownIllustrations(
                status.layout.illustrations,
                status.layout.illustrationCache,
                session.markdownPreviewListState,
                navigationState,
                previewItems
            )
            val restorationItemIndex =
                remember(previewItems, restorationPosition) {
                    restorationPosition?.let { position ->
                        markdownPreviewItemIndexForBlock(
                            items = previewItems,
                            blockIndex = position.blockIndex
                        )
                    }
                }
            var restorationComplete by
                remember(status.revision, status.scrollRestoration) {
                    mutableStateOf(status.scrollRestoration == null)
                }
            LaunchedEffect(
                status.revision,
                status.scrollRestoration,
                restorationPosition,
                restorationItemIndex,
                navigationState
            ) {
                val restoration = status.scrollRestoration
                val position = restorationPosition
                val itemIndex = restorationItemIndex
                if (restoration == null || position == null || itemIndex == null) {
                    if (restoration != null) {
                        session.consumeMarkdownPreviewScrollRestoration(
                            revision = status.revision,
                            restoration = restoration
                        )
                    }
                    restorationComplete = true
                    return@LaunchedEffect
                }
                try {
                    session.markdownPreviewListState.scrollToItem(itemIndex)
                    withFrameNanos { _ -> }
                    val measurement =
                        withTimeoutOrNull(MARKDOWN_PREVIEW_RESTORATION_TIMEOUT_MILLIS) {
                            snapshotFlow {
                                navigationState.measurementForSource(restoration.sourceUtf16Offset)
                                    ?: navigationState.measurementFor(
                                        blockIndex = position.blockIndex,
                                        renderedUtf16Offset = position.renderedUtf16Offset
                                    )
                            }.filterNotNull().first()
                        }
                    // Keep the first heading and its container visible at the document start.
                    val isDocumentStart =
                        restoration.sourceUtf16Offset == 0L &&
                            restoration.viewportTopOffsetPixels >= 0
                    if (
                        !isDocumentStart &&
                        measurement != null &&
                        navigationState.hasRootPosition &&
                        !session.markdownPreviewListState.isScrollInProgress &&
                        measurement.layoutResult.size.height > 0
                    ) {
                        val presentationOffset =
                            measurement.presentationUtf16OffsetForRendered(
                                measurement.block.renderedUtf16OffsetForSource(
                                    restoration.sourceUtf16Offset
                                )
                            )
                        val line =
                            measurement.layoutResult.getLineForOffset(presentationOffset)
                        val currentLineTop =
                            Math.subtractExact(
                                measurement.topInWindowPixels,
                                navigationState.rootTopInWindowPixels
                            ) + measurement.layoutResult.getLineTop(line).roundToInt()
                        val targetLineTop =
                            Math.addExact(
                                session.markdownPreviewListState.layoutInfo
                                    .viewportStartOffset
                                    .coerceAtLeast(0),
                                restoration.viewportTopOffsetPixels
                            )
                        session.markdownPreviewListState.scrollBy(
                            Math.subtractExact(currentLineTop, targetLineTop).toFloat()
                        )
                    }
                } finally {
                    restorationComplete = true
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
                restorationComplete
            ) {
                if (!restorationComplete) {
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
                    if (shouldPublish && anchor != null) {
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
            val onInlineInteraction: (MarkdownInlineInteraction, Int) -> Unit =
                { interaction, sourceItemIndex ->
                    when (interaction) {
                        is MarkdownInlineInteraction.Link -> {
                            when (val action = markdownLinkAction(interaction.destination)) {
                                is MarkdownLinkAction.Heading -> {
                                    val itemIndex =
                                        status.layout.headings.itemIndex(action.fragment)
                                    if (itemIndex == null) {
                                        linkDialogState =
                                            MarkdownLinkDialogState.MissingHeading(action.fragment)
                                    } else {
                                        coroutineScope.launch {
                                            session.markdownPreviewListState.animateScrollToItem(
                                                itemIndex
                                            )
                                        }
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
                            val itemIndex = footnoteTargets.definitions[interaction.label]
                            if (itemIndex == null) {
                                linkDialogState =
                                    MarkdownLinkDialogState.MissingFootnote(interaction.label)
                            } else {
                                coroutineScope.launch {
                                    session.markdownPreviewListState.animateScrollToItem(itemIndex)
                                }
                            }
                        }

                        is MarkdownInlineInteraction.FootnoteDefinition -> {
                            val itemIndex = footnoteTargets.references[interaction.label]
                            if (itemIndex != null && itemIndex != sourceItemIndex) {
                                coroutineScope.launch {
                                    session.markdownPreviewListState.animateScrollToItem(itemIndex)
                                }
                            }
                        }
                    }
                }
            CompositionLocalProvider(
                LocalMarkdownFootnoteNumbers provides footnoteTargets.numbers,
                LocalMarkdownPreviewNavigationState provides navigationState,
                LocalMarkdownCodeCopies provides status.layout.codeCopies
            ) {
                LazyColumn(
                    modifier =
                        readingPageModifier
                            .fillMaxSize()
                            .onGloballyPositioned { coordinates ->
                                navigationState.rootTopInWindowPixels =
                                    coordinates.positionInWindow().y.roundToInt()
                                navigationState.hasRootPosition = true
                            },
                    state = session.markdownPreviewListState,
                    contentPadding =
                        PaddingValues(
                            horizontal = DocumentPageGutter,
                            vertical = EditorSectionSpacing
                        ),
                    verticalArrangement = Arrangement.Top
                ) {
                    items(
                        count = previewItems.size,
                        key = { itemIndex ->
                            "${status.revision}:${previewItems[itemIndex].firstBlockIndex}"
                        }
                    ) { itemIndex ->
                        val originalItem = previewItems[itemIndex]
                        val previewItem = remember(originalItem, illustrationResults) {
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
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        top =
                                            if (previewItem.hasSpacingBefore) {
                                                EditorSectionSpacing
                                            } else {
                                                0.dp
                                            }
                                    )
                        ) {
                            val itemModifier =
                                Modifier
                                    .align(Alignment.TopCenter)
                                    .widthIn(max = MarkdownPreviewMaxWidth)
                                    .fillMaxWidth()
                            when {
                                previewItem.isTable ->
                                    MarkdownTable(
                                        blocks = previewItem.blocks,
                                        firstBlockIndex = previewItem.firstBlockIndex,
                                        showWrappedPreview =
                                            previewItem.showsWrappedPreview,
                                        onInlineInteraction = { interaction ->
                                            onInlineInteraction(interaction, itemIndex)
                                        },
                                        modifier = itemModifier
                                    )

                                previewItem.isCode ->
                                    MarkdownCodeBlocks(
                                        blocks = previewItem.blocks,
                                        firstBlockIndex = previewItem.firstBlockIndex,
                                        showWrappedPreview =
                                            previewItem.showsWrappedPreview,
                                        onInlineInteraction = { interaction ->
                                            onInlineInteraction(interaction, itemIndex)
                                        },
                                        modifier =
                                            itemModifier.padding(
                                                start =
                                                    markdownNestedIndent(
                                                        (previewItem.blocks.first().listDepth - 1)
                                                            .coerceAtLeast(0)
                                                    )
                                            )
                                    )

                                previewItem.isQuoteAlert ->
                                    MarkdownQuoteAlert(
                                        blocks = previewItem.blocks,
                                        firstBlockIndex = previewItem.firstBlockIndex,
                                        startsWrappedPreview =
                                            previewItem.startsWrappedPreview,
                                        onInlineInteraction = { interaction ->
                                            onInlineInteraction(interaction, itemIndex)
                                        },
                                        canReturnToFootnoteReference = { block ->
                                            block.kind == MarkdownBlockKind.Footnote &&
                                                footnoteTargets.references.containsKey(
                                                    block.metadata
                                                )
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
                                            showWrappedPreview =
                                                previewItem.showsWrappedPreview,
                                            onInlineInteraction = { interaction ->
                                                onInlineInteraction(interaction, itemIndex)
                                            },
                                            canReturnToFootnoteReference =
                                                block.kind == MarkdownBlockKind.Footnote &&
                                                    footnoteTargets.references.containsKey(
                                                        block.metadata
                                                    ),
                                            modifier = itemModifier
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
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
    val measurement =
        navigationState.measurements.values
            .asSequence()
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
    data class FootnoteReference(val label: String) : MarkdownInlineInteraction

    /** Requests navigation from one footnote definition to its first reference. */
    data class FootnoteDefinition(val label: String) : MarkdownInlineInteraction
}

/** Displays an empty preview with an action appropriate to the source mode. */
@Composable
private fun MarkdownPreviewEmpty(
    isViewOnly: Boolean,
    onEdit: () -> Unit,
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

/** Displays a sanitized preview failure with retry and source recovery. */
@Composable
private fun MarkdownPreviewFailure(
    message: String,
    isViewOnly: Boolean,
    onRetry: () -> Unit,
    onEdit: () -> Unit,
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
