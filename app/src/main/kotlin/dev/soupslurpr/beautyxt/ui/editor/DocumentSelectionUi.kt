@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package dev.soupslurpr.beautyxt.ui.editor

import android.content.ClipData
import android.content.ClipboardManager
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import dev.soupslurpr.beautyxt.illustration.NativeIllustration
import dev.soupslurpr.beautyxt.ui.designsystem.ActionMenuItem
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.ui.asString
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Gives non-editing document commands a focus target after input or a tool is dismissed. */
internal class DocumentFocus {
    val requester = FocusRequester()
    private val owners = mutableStateListOf<Any>()
    fun attach(owner: Any) { owners.add(owner) }
    fun detach(owner: Any) { owners.remove(owner) }
    suspend fun request(canFocus: () -> Boolean = { true }) {
        withTimeoutOrNull(1_000) {
            snapshotFlow { owners.isNotEmpty() }.first { it }
            withFrameNanos { }
            if (owners.isNotEmpty() && canFocus()) requester.requestFocus()
        }
    }
}

internal sealed interface SelectionPoint : Comparable<SelectionPoint> {
    data class Source(val offset: Long) : SelectionPoint
    data class Reading(val point: ReadingPoint) : SelectionPoint
    override fun compareTo(other: SelectionPoint): Int = when {
        this is Source && other is Source -> offset.compareTo(other.offset)
        this is Reading && other is Reading -> point.compareTo(other.point)
        else -> error("Selection domains differ")
    }
}

internal class SelectionTextMeasurement(
    val owner: Any,
    val layout: TextLayoutResult,
    val position: Offset,
    val point: (Int) -> SelectionPoint,
    val offset: (SelectionPoint) -> Int?,
    val sourceFindDecoration: SourceFindDecoration? = null,
    val sourceIsCurrent: () -> Boolean = { true },
    val clipBounds: (() -> Rect?)? = null
) {
    val bounds get() = Rect(position, androidx.compose.ui.geometry.Size(layout.size.width.toFloat(), layout.size.height.toFloat()))
    val visibleBounds: Rect get() = if (clipBounds == null) bounds else bounds.intersect(clipBounds() ?: Rect.Zero)
    fun clipIn(root: Rect): Rect = clipBounds?.let { it()?.intersect(root) ?: Rect.Zero } ?: root
    val graphemeText: String get() = sourceFindDecoration?.text?.takeIf { raw ->
        sourceIsCurrent() && sourcePresentationMatches(layout.layoutInput.text, raw)
    } ?: layout.layoutInput.text.text

    fun at(window: Offset): Int {
        val text = graphemeText
        val visible = visibleBounds
        val x = if (visible.isEmpty) window.x else window.x.coerceIn(visible.left, visible.right)
        val offset = layout.getOffsetForPosition(Offset(x, window.y) - position).coerceIn(0, text.length)
        val characters = android.icu.text.BreakIterator.getCharacterInstance(java.util.Locale.ROOT)
        characters.setText(text)
        if (characters.isBoundary(offset)) return offset
        val start = characters.preceding(offset)
        val end = characters.following(offset)
        return if (offset - start < end - offset) start else end
    }
    fun selected(selection: DocumentSelection?): TextRange? {
        val (first, last) = when (selection) {
            is DocumentSelection.Source -> SelectionPoint.Source(selection.range.start) to SelectionPoint.Source(selection.range.end)
            is DocumentSelection.Reading -> SelectionPoint.Reading(selection.start) to SelectionPoint.Reading(selection.end)
            else -> return null
        }
        val start = point(0)
        val end = point(layout.layoutInput.text.length)
        if (first::class != start::class || last <= start || first >= end) return null
        val from = if (first <= start) 0 else offset(first) ?: return null
        val to = if (last >= end) layout.layoutInput.text.length else offset(last) ?: return null
        return TextRange(from, to).takeUnless { it.collapsed }
    }
}

internal class DocumentSelectionLayout(val session: EditorSession) {
    val texts = mutableStateMapOf<Any, SelectionTextMeasurement>()
    fun release(key: Any, owner: Any) {
        if (texts[key]?.owner === owner) texts.remove(key)
    }

    fun hit(position: Offset, exact: Boolean = false): SelectionTextMeasurement? {
        val candidates = texts.values.asSequence().filter { !it.visibleBounds.isEmpty }
        return candidates.firstOrNull { it.visibleBounds.contains(position) } ?: if (exact) null else candidates.minByOrNull {
            val visible = it.visibleBounds
            val dy = maxOf(visible.top - position.y, position.y - visible.bottom, 0f)
            val dx = maxOf(visible.left - position.x, position.x - visible.right, 0f)
            dy * 10_000f + dx
        }
    }
    fun select(anchor: SelectionPoint, focus: SelectionPoint): Boolean = when {
        anchor is SelectionPoint.Source && focus is SelectionPoint.Source -> session.selectSource(anchor.offset, focus.offset)
        anchor is SelectionPoint.Reading && focus is SelectionPoint.Reading -> {
            val document = session.readingDocumentForSelection()
            val forward = anchor <= focus
            val first = document?.let { snapReadingGrapheme(it, anchor.point, ending = !forward) }
            val last = document?.let { snapReadingGrapheme(it, focus.point, ending = forward) }
            first != null && last != null && session.selectReading(first, last)
        }
        else -> false
    }
    fun handle(
        point: SelectionPoint,
        root: Rect,
        size: Float,
        obstructions: List<Rect> = emptyList()
    ): SelectionHandle? = texts.values.firstNotNullOfOrNull { text ->
        val offset = text.offset(point)?.takeIf { it in 0..text.layout.layoutInput.text.length }
            ?: return@firstNotNullOfOrNull null
        val caret = text.position + text.layout.getCursorRect(offset).bottomLeft
        selectionHandle(caret, text.clipIn(root), root, size, obstructions)
    }

    fun moveEndpoint(first: Boolean, forward: Boolean): Boolean {
        session.validateReadingSelectionProjections()
        val selection = session.documentSelection ?: return false
        val endpoints = when (selection) {
            is DocumentSelection.Source -> SelectionPoint.Source(selection.range.start) to SelectionPoint.Source(selection.range.end)
            is DocumentSelection.Reading -> SelectionPoint.Reading(selection.start) to SelectionPoint.Reading(selection.end)
            else -> return false
        }
        val point = if (first) endpoints.first else endpoints.second
        val ordered = texts.values.sortedBy { it.point(0) }
        val current = ordered.indexOfFirst { it.offset(point) != null }
        if (current < 0) return false
        val text = ordered[current]
        val offset = checkNotNull(text.offset(point))
        val characters = android.icu.text.BreakIterator.getCharacterInstance(java.util.Locale.ROOT)
        characters.setText(text.graphemeText)
        val moved = if (forward) characters.following(offset) else characters.preceding(offset)
        val next = if (moved != android.icu.text.BreakIterator.DONE) text.point(moved)
            else ordered.getOrNull(current + if (forward) 1 else -1)?.let {
                it.point(if (forward) 0 else it.layout.layoutInput.text.length)
            } ?: return false
        return if (first) select(endpoints.second, next) else select(endpoints.first, next)
    }
}

internal data class SelectionHandle(val caret: Offset, val bounds: Rect)

internal fun selectionHandle(
    caret: Offset,
    clip: Rect,
    root: Rect,
    size: Float,
    obstructions: List<Rect> = emptyList()
): SelectionHandle? {
    if (clip.isEmpty || caret.x !in clip.left..clip.right || caret.y !in clip.top..clip.bottom) return null
    if (obstructions.any { it.contains(caret) }) return null
    val width = minOf(size, clip.width)
    val height = minOf(size, root.height)
    val left = (caret.x - width / 2).coerceIn(clip.left, maxOf(clip.left, clip.right - width))
    val effectiveBottom = obstructions
        .filter { left < it.right && left + width > it.left && it.top >= caret.y }
        .minOfOrNull { it.top }
        ?.let { minOf(root.bottom, it) }
        ?: root.bottom
    if (effectiveBottom - root.top < height) return null
    val top = caret.y.coerceIn(root.top, maxOf(root.top, effectiveBottom - height))
    val bounds = Rect(left, top, left + width, top + height)
    if (obstructions.any { it.overlaps(bounds) }) return null
    return SelectionHandle(caret, bounds)
}

internal data class SourceFindMarker(val offset: Int, val cursor: Rect, val clip: Rect)

/** Adjacent blocks can both map a boundary. Prefer its visible starting block. */
internal fun visibleSourceFindMarker(candidates: Sequence<SourceFindMarker>): SourceFindMarker? {
    var fallback: SourceFindMarker? = null
    for (marker in candidates) {
        val (offset, cursor, clip) = marker
        if (clip.isEmpty || cursor.left !in clip.left..clip.right ||
            cursor.top >= clip.bottom || cursor.bottom <= clip.top) continue
        if (offset == 0) return marker
        if (fallback == null) fallback = marker
    }
    return fallback
}

internal val LocalDocumentSelectionLayout = staticCompositionLocalOf<DocumentSelectionLayout?> { null }

/** Transport fragments are not character boundaries, even for an unusually long cluster. */
internal fun snapReadingGrapheme(
    document: dev.soupslurpr.beautyxt.markdown.MarkdownPreviewDocument,
    point: ReadingPoint,
    ending: Boolean
): ReadingPoint? {
    val blocks = document.blocks
    val block = blocks.getOrNull(point.block) ?: return null
    if (point.offset < 0) return point // generated list prefix
    if (point.offset > block.text.length) return null
    if (point.markerOffset > 0) return atomicReadingPoint(document, point, ending)
    if (block.spans.any { point.offset in it.start..it.end && (it.illustration != null ||
        it.styles and dev.soupslurpr.beautyxt.markdown.MARKDOWN_SPAN_STYLE_MATH != 0) }) return point
    var first = point.block
    var last = point.block
    var units = block.text.length
    while (first > 0 && blocks[first].continuesPrevious && units + blocks[first - 1].text.length <= 32 * 1024) {
        units += blocks[--first].text.length
    }
    while (last < blocks.lastIndex && blocks[last + 1].continuesPrevious && units + blocks[last + 1].text.length <= 64 * 1024) {
        units += blocks[++last].text.length
    }
    val text = buildString(units) { for (index in first..last) append(blocks[index].text) }
    val offset = (first until point.block).sumOf { blocks[it].text.length } + point.offset
    val iterator = android.icu.text.BreakIterator.getCharacterInstance(java.util.Locale.ROOT)
    iterator.setText(text)
    val truncatedLeft = blocks[first].continuesPrevious
    val truncatedRight = last < blocks.lastIndex && blocks[last + 1].continuesPrevious
    // Regional-indicator parity can depend on text before an otherwise apparent boundary.
    if (truncatedLeft && text.substring(0, offset).codePoints().allMatch { it in 0x1f1e6..0x1f1ff }) return null
    if (iterator.isBoundary(offset)) {
        if (truncatedLeft && (offset == 0 || iterator.preceding(offset) == 0) ||
            truncatedRight && offset == text.length) return null
        return point
    }
    val start = iterator.preceding(offset)
    val end = iterator.following(offset)
    if (truncatedLeft && start == 0 || truncatedRight && end == text.length) return null
    var target = if (ending) end else start
    for (index in first..last) {
        if (target <= blocks[index].text.length) return ReadingPoint(index, target)
        target -= blocks[index].text.length
    }
    return null
}

private fun selectionEndpoints(selection: DocumentSelection?): Pair<SelectionPoint, SelectionPoint>? = when (selection) {
    is DocumentSelection.Source -> SelectionPoint.Source(selection.range.start) to SelectionPoint.Source(selection.range.end)
    is DocumentSelection.Reading -> SelectionPoint.Reading(selection.start) to SelectionPoint.Reading(selection.end)
    else -> null
}

/** Registers only composed geometry. Selection endpoints themselves live in the retained session. */
@Composable
internal fun Modifier.documentSelectionText(
    key: Any,
    layout: TextLayoutResult?,
    point: (Int) -> SelectionPoint,
    offset: (SelectionPoint) -> Int?,
    nativeSelection: Boolean = false,
    additionalActions: List<CustomAccessibilityAction> = emptyList(),
    contentOffset: () -> Offset = { Offset.Zero },
    sourceFindDecoration: SourceFindDecoration? = null,
    sourceIsCurrent: () -> Boolean = { true },
    clipBounds: (() -> Rect?)? = null
): Modifier {
    val registry = LocalDocumentSelectionLayout.current
        ?: return if (additionalActions.isEmpty()) this else semantics { customActions = additionalActions }
    val owner = remember(registry, key) { Any() }
    var position by remember(key) { mutableStateOf<Offset?>(null) }
    val currentContentOffset by rememberUpdatedState(contentOffset)
    val currentSourceIsCurrent by rememberUpdatedState(sourceIsCurrent)
    LaunchedEffect(registry, key, layout, position, point, offset, sourceFindDecoration, clipBounds) {
        val origin = position ?: return@LaunchedEffect
        if (layout == null) return@LaunchedEffect
        snapshotFlow { currentContentOffset() }.collect { displacement ->
            // A published layout owns its offset mapping, including during inline substitutions.
            registry.texts[key] = SelectionTextMeasurement(owner, layout, origin + displacement,
                point, offset, sourceFindDecoration, { currentSourceIsCurrent() }, clipBounds)
        }
    }
    DisposableEffect(registry, key, owner, layout) { onDispose { registry.release(key, owner) } }
    val label = stringResource(R.string.selection_title)
    return this.onGloballyPositioned { position = it.positionInWindow() }.semantics {
        customActions = additionalActions
        if (layout != null && !nativeSelection) {
            registry.texts[key]?.selected(registry.session.documentSelection)?.let { textSelectionRange = it }
            setSelection { start, end, _ ->
                if (start in 0..layout.layoutInput.text.length && end in 0..layout.layoutInput.text.length)
                    registry.select(point(start), point(end)) else false
            }
            customActions = additionalActions + CustomAccessibilityAction(label) {
                registry.select(point(0), point(layout.layoutInput.text.length))
            }
        }
    }
}

/** Selection gestures and handles can cross lazy items without retaining off-screen text layouts. */
@Composable
internal fun DocumentSelectionHost(session: EditorSession, modifier: Modifier = Modifier,
    documentFocus: DocumentFocus? = null, content: @Composable () -> Unit) {
    val registry = remember(session) { DocumentSelectionLayout(session) }
    val ownFocus = remember(session) { DocumentFocus() }
    val focusTarget = documentFocus ?: ownFocus
    val focusRequester = focusTarget.requester
    val focusOwner = remember(session) { Any() }
    DisposableEffect(focusTarget, focusOwner) {
        focusTarget.attach(focusOwner)
        onDispose { focusTarget.detach(focusOwner) }
    }
    LaunchedEffect(session, focusTarget, session.presentation, session.activeDraft, session.findInputFocus) {
        fun shouldFocusDocument() = (!session.isFindVisible || session.findInputFocus == FindInputFocus.Document) &&
            (session.isViewOnly || session.presentation == EditorPresentation.MarkdownPreview ||
                session.activeDraft?.shouldRestoreEditorFocus == false)
        if (shouldFocusDocument()) {
            focusTarget.request(::shouldFocusDocument)
        }
    }
    val context = LocalContext.current
    val selectionTitle = stringResource(R.string.selection_title)
    fun copy(text: String) {
        checkNotNull(context.getSystemService(ClipboardManager::class.java)).setPrimaryClip(
            ClipData.newPlainText(selectionTitle, text))
    }
    var root by remember { mutableStateOf(Rect.Zero) }
    var dragPosition by remember { mutableStateOf<Offset?>(null) }
    var dragAnchor by remember { mutableStateOf<SelectionPoint?>(null) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val bottomOverlay = LocalEditorBottomOverlayState.current
    val edge = with(density) { 48.dp.toPx() }
    val color = MaterialTheme.colorScheme.primary
    fun extend(position: Offset) {
        dragPosition = position
        val anchor = dragAnchor ?: return
        val measurement = registry.hit(position) ?: return
        registry.select(anchor, measurement.point(measurement.at(position)))
    }
    LaunchedEffect(dragAnchor) {
        if (dragAnchor == null) return@LaunchedEffect
        while (dragAnchor != null) {
            withFrameNanos { }
            val position = dragPosition ?: continue
            val overlayHeight = bottomOverlay?.heightPx?.toFloat() ?: 0f
            val scrollBottomEdge = (root.bottom - overlayHeight - edge).coerceAtLeast(root.top + edge)
            val distance = when {
                position.y < root.top + edge -> -(root.top + edge - position.y).coerceAtMost(edge)
                position.y > scrollBottomEdge -> (position.y - scrollBottomEdge).coerceAtMost(edge)
                else -> 0f
            }
            if (distance != 0f) {
                val delta = distance * .45f
                if (session.presentation == EditorPresentation.MarkdownPreview) session.markdownPreviewListState.scrollBy(delta)
                else session.activeDraft?.scrollState?.scrollBy(delta) ?: session.viewportListState.scrollBy(delta)
                extend(position)
            }
        }
    }
    Box(modifier.focusRequester(focusRequester).onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) false else when {
            event.isCtrlPressed && event.key == Key.A -> session.selectWholeDocument()
            event.isCtrlPressed && event.key == Key.C && session.documentSelection != null -> {
                session.copyDocumentSelection { copy(it) }; true
            }
            event.isCtrlPressed && event.key == Key.X -> session.replaceSelectedSource("", ::copy)
            event.isCtrlPressed && event.key == Key.V && session.canEditDocumentSelection -> {
                val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.let { session.replaceSelectedSource(it.toString()) } ?: false
            }
            event.isShiftPressed && event.key in listOf(Key.DirectionLeft, Key.DirectionRight) && session.documentSelection != null -> {
                val first = when (val selection = session.documentSelection) {
                    is DocumentSelection.Source -> selection.focus < selection.anchor
                    is DocumentSelection.Reading -> selection.focus < selection.anchor
                    else -> false
                }
                registry.moveEndpoint(first, event.key == Key.DirectionRight)
            }
            else -> handleEditorHistoryShortcut(event, session::requestUndo, session::requestRedo)
        }
    }.focusable().onGloballyPositioned { root = Rect(it.positionInWindow(), androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat())) }
        .pointerInput(registry) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val position = down.position + root.topLeft
                val endpoints = selectionEndpoints(session.documentSelection)
                val obstructions = bottomOverlay?.obstructions?.values?.toList().orEmpty()
                if (obstructions.any { it.contains(position) }) return@awaitEachGesture
                if (endpoints != null && listOf(endpoints.first, endpoints.second).any { point ->
                    registry.handle(point, root, edge, obstructions)?.bounds?.contains(position) == true
                }) return@awaitEachGesture
                val measurement = registry.hit(position, exact = true)
                val selected = measurement?.selected(session.documentSelection)
                if (session.documentSelection != null && (measurement == null || selected == null || measurement.at(position) !in selected.min..selected.max)) {
                    val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
                    if (up != null) { session.clearDocumentSelection(); up.consume() }
                    return@awaitEachGesture
                }
                if (measurement == null) return@awaitEachGesture
                val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                val offset = measurement.at(position)
                val word = measurement.layout.getWordBoundary(offset)
                if (word.collapsed) return@awaitEachGesture
                focus.clearFocus(); focusRequester.requestFocus(); keyboard?.hide()
                val anchor = measurement.point(word.min)
                val end = measurement.point(word.max)
                if (!registry.select(anchor, end)) return@awaitEachGesture
                longPress.consume()
                dragAnchor = anchor
                dragPosition = null
                try { drag(longPress.id) { change ->
                    val nextPosition = change.position + root.topLeft
                    if (dragPosition == null && (nextPosition - position).getDistance() < viewConfiguration.touchSlop) return@drag
                    val text = registry.hit(nextPosition)
                    if (text != null) dragAnchor = if (text.point(text.at(nextPosition)) < anchor) end else anchor
                    extend(nextPosition)
                    change.consume()
                } } finally {
                    dragAnchor = null
                    dragPosition = null
                }
            }
        }) {
        CompositionLocalProvider(LocalDocumentSelectionLayout provides registry) { content() }
        Canvas(Modifier.matchParentSize().clipToBounds()) {
            val selection = session.documentSelection
            if (selection != null) registry.texts.values.forEach { text ->
                val range = text.selected(selection) ?: return@forEach
                val clip = text.clipIn(root).translate(-root.topLeft)
                clipRect(clip.left, clip.top, clip.right, clip.bottom) {
                    translate(text.position.x - root.left, text.position.y - root.top) {
                        drawPath(text.layout.getPathForRange(range.min, range.max), color.copy(alpha = .25f))
                    }
                }
            }
            val revision = session.state.metrics?.revision
            if (session.isFindVisible && !session.state.hasActiveDraftChanges && revision != null) {
                val markers = session.findResults.filter {
                    it.representation == SearchRepresentation.Source && it.source?.let { range -> range.start == range.end } == true
                }
                if (markers.isEmpty()) return@Canvas
                val findTexts = registry.texts.values.filter { text ->
                    text.sourceFindDecoration?.matchesSource(revision,
                        text.layout.layoutInput.text, text.sourceIsCurrent()) == true
                }
                markers.forEach { result ->
                    val point = SelectionPoint.Source(result.source!!.start)
                    val candidates = findTexts.asSequence().mapNotNull { text ->
                        text.sourceFindDecoration?.markerOffset(text.offset(point))?.let { offset ->
                            SourceFindMarker(offset, text.layout.getCursorRect(offset).translate(text.position), text.clipIn(root))
                        }
                    }
                    visibleSourceFindMarker(candidates)?.let { marker ->
                        val rect = marker.cursor.translate(-root.topLeft)
                        val clip = marker.clip.translate(-root.topLeft)
                        clipRect(clip.left, clip.top, clip.right, clip.bottom) {
                            drawLine(color, rect.topLeft, rect.bottomLeft, 2.dp.toPx())
                        }
                    }
                }
            }
        }
        val endpoints = selectionEndpoints(session.documentSelection)
        val obstructions = bottomOverlay?.obstructions?.values?.toList().orEmpty()
        var draggingHandleFirst by remember { mutableStateOf<Boolean?>(null) }
        var lastStartHandle by remember { mutableStateOf<SelectionHandle?>(null) }
        var lastEndHandle by remember { mutableStateOf<SelectionHandle?>(null) }
        if (endpoints != null) listOf(true, false).forEach { first ->
            val point = if (first) endpoints.first else endpoints.second
            val liveHandle = registry.handle(point, root, edge, obstructions)
            if (liveHandle != null) {
                if (first) lastStartHandle = liveHandle else lastEndHandle = liveHandle
            }
            val isDraggingThisHandle = draggingHandleFirst == first
            val handle = liveHandle ?: if (isDraggingThisHandle) {
                if (first) lastStartHandle else lastEndHandle
            } else {
                null
            }
            if (handle != null) {
                val handleVisible = liveHandle != null
                val handlePosition = handle.caret
                val bounds = handle.bounds
                val currentEndpoints by rememberUpdatedState(endpoints)
                val currentHandlePosition by rememberUpdatedState(handlePosition)
                val description = stringResource(if (first) R.string.selection_start_handle else R.string.selection_end_handle)
                val previous = stringResource(R.string.selection_boundary_previous)
                val next = stringResource(R.string.selection_boundary_next)
                Box(Modifier.align(androidx.compose.ui.AbsoluteAlignment.TopLeft).absoluteOffset {
                    IntOffset((bounds.left - root.left).roundToInt(), (bounds.top - root.top).roundToInt())
                }.size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() }).clipToBounds().semantics {
                        contentDescription = description
                        customActions = listOf(CustomAccessibilityAction(previous) { registry.moveEndpoint(first, false) },
                            CustomAccessibilityAction(next) { registry.moveEndpoint(first, true) })
                    }.onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key in listOf(Key.DirectionLeft, Key.DirectionRight))
                            registry.moveEndpoint(first, event.key == Key.DirectionRight) else false
                    }.focusable()
                    .pointerInput(first, registry) {
                        var position = handlePosition
                        detectDragGestures(onDragStart = {
                            draggingHandleFirst = first
                            dragAnchor = if (first) currentEndpoints.second else currentEndpoints.first
                            position = currentHandlePosition
                            dragPosition = position
                        }, onDragEnd = { draggingHandleFirst = null; dragAnchor = null; dragPosition = null },
                            onDragCancel = { draggingHandleFirst = null; dragAnchor = null; dragPosition = null }) { change, amount ->
                            position += amount; extend(position); change.consume()
                        }
                    }) {
                    if (handleVisible) {
                        val dot = minOf(edge / 3, bounds.width, bounds.height)
                        Box(Modifier.align(androidx.compose.ui.AbsoluteAlignment.TopLeft).absoluteOffset {
                            IntOffset((handlePosition.x - bounds.left - dot / 2).coerceIn(0f, (bounds.width - dot).coerceAtLeast(0f)).roundToInt(),
                                (handlePosition.y - bounds.top).coerceIn(0f, (bounds.height - dot).coerceAtLeast(0f)).roundToInt())
                        }.size(with(density) { dot.toDp() }).background(color, CircleShape))
                    }
                }
            }
        }
    }
}

private val SelectionActionSize = 48.dp
private val SelectionBarPadding = 8.dp
private val SelectionPrimaryGap = 4.dp
private val SelectionInlineLabelMinHeight = 280.dp

/** Describes which direct selection actions fit before moving into Selection actions. */
internal data class SelectionActionBarPlan(
    val showCopy: Boolean,
    val showSend: Boolean,
    val showClear: Boolean
)

/** Keeps every direct selection control at least 48 dp wide without horizontal clipping. */
internal fun selectionActionBarPlan(availableWidth: Dp): SelectionActionBarPlan {
    require(availableWidth >= 0.dp) { "selection toolbar width must not be negative" }
    val fourDirectWidth = SelectionActionSize * 4 + SelectionPrimaryGap
    val threeDirectWidth = SelectionActionSize * 3 + SelectionPrimaryGap
    val twoDirectWidth = SelectionActionSize * 2 + SelectionPrimaryGap
    return when {
        availableWidth >= fourDirectWidth ->
            SelectionActionBarPlan(showCopy = true, showSend = true, showClear = true)

        availableWidth >= threeDirectWidth ->
            SelectionActionBarPlan(showCopy = true, showSend = false, showClear = true)

        availableWidth >= twoDirectWidth ->
            SelectionActionBarPlan(showCopy = true, showSend = false, showClear = false)

        else ->
            SelectionActionBarPlan(showCopy = false, showSend = false, showClear = false)
    }
}

private data class SelectionMenuEntry(
    val label: String,
    @param:DrawableRes val iconRes: Int? = null,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DocumentSelectionActions(
    session: EditorSession,
    availableHeight: Dp = 480.dp
) {
    if (session.documentSelection == null) {
        session.selectionMessage?.let { message ->
            Box(
                modifier = Modifier.fillMaxWidth().padding(SelectionBarPadding),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier.registerBottomOverlayObstruction("selection-message-card"),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = message.asString(),
                            modifier = Modifier
                                .weight(1f)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(
                            onClick = session::clearDocumentSelection,
                            modifier = Modifier.heightIn(min = SelectionActionSize)
                        ) {
                            Text(stringResource(R.string.action_dismiss))
                        }
                    }
                }
            }
        }
        return
    }
    val context = LocalContext.current
    val selectionTitle = stringResource(R.string.selection_title)
    val copyLabel = stringResource(R.string.markdown_copy)
    val sendLabel = stringResource(R.string.excerpt_send)
    val actionsLabel = stringResource(R.string.selection_actions)
    val clearLabel = stringResource(R.string.selection_clear)
    var expanded by remember { mutableStateOf(false) }
    var labelDialogVisible by remember(session.documentSelection is DocumentSelection.Label) {
        mutableStateOf(false)
    }
    val activeLabelSelection = session.documentSelection as? DocumentSelection.Label
    val showInlineLabelField = activeLabelSelection != null &&
        availableHeight >= SelectionInlineLabelMinHeight
    val labelFieldVisible = showInlineLabelField || labelDialogVisible
    val labelFieldBinding = if (activeLabelSelection != null && labelFieldVisible) {
        remember(
            activeLabelSelection.revision,
            activeLabelSelection.block,
            activeLabelSelection.spanStart,
            activeLabelSelection.run,
            activeLabelSelection.text
        ) {
            TextFieldState(
                initialText = activeLabelSelection.text,
                initialSelection = activeLabelSelection.range
            ) to LabelSelectionSyncState(activeLabelSelection.range)
        }
    } else {
        null
    }
    val syncLabelSelection = {
        val currentSelection = session.documentSelection as? DocumentSelection.Label
        val binding = labelFieldBinding
        if (currentSelection != null && binding != null) {
            val (fieldState, syncState) = binding
            val fieldRange = fieldState.selection
            if (
                currentSelection.range == syncState.lastSynced &&
                !fieldRange.collapsed &&
                fieldRange != syncState.lastSynced
            ) {
                syncState.lastSynced = fieldRange
                session.updateLabelSelection(fieldRange)
            }
        }
    }
    val performCopy = {
        syncLabelSelection()
        session.copyDocumentSelection { text ->
            checkNotNull(context.getSystemService(ClipboardManager::class.java)).setPrimaryClip(
                ClipData.newPlainText(selectionTitle, text)
            )
        }
    }
    val performSend = {
        syncLabelSelection()
        session.excerptExport.open(context, session.title)
    }
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelLarge
    val measuredCopyButtonWidth = remember(copyLabel, labelStyle, density, layoutDirection) {
        val textWidth = with(density) {
            textMeasurer.measure(copyLabel, style = labelStyle, maxLines = 1).size.width.toDp()
        }
        val contentPadding = ButtonDefaults.ContentPadding
        textWidth +
            20.dp +
            8.dp +
            contentPadding.calculateLeftPadding(layoutDirection) +
            contentPadding.calculateRightPadding(layoutDirection) +
            4.dp
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = SelectionBarPadding),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (showInlineLabelField) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
                    .registerBottomOverlayObstruction("selection-label-field"),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                val (fieldState, syncState) = checkNotNull(labelFieldBinding)
                ReadOnlyLabelSelectionField(
                    state = fieldState,
                    syncState = syncState,
                    range = activeLabelSelection.range,
                    onSelectionChange = session::updateLabelSelection,
                    label = { Text(stringResource(R.string.selection_label)) },
                    modifier = Modifier.fillMaxWidth().padding(8.dp)
                )
            }
        }
        session.selectionMessage?.let { message ->
            Surface(
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .registerBottomOverlayObstruction("selection-inline-message"),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Text(
                    text = message.asString(),
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val heightMode = editorToolbarHeightMode(availableHeight)
            val contentPadding = FloatingToolbarDefaults.ContentPadding
            val horizontalInnerPadding = if (heightMode == EditorToolbarHeightMode.Compact) {
                SelectionBarPadding * 2
            } else {
                contentPadding.calculateLeftPadding(layoutDirection) +
                    contentPadding.calculateRightPadding(layoutDirection)
            }
            val controlsWidth = (maxWidth - horizontalInnerPadding).coerceAtLeast(0.dp)
            val plan = selectionActionBarPlan(controlsWidth)
            val otherDirectCount = 1 + (if (plan.showSend) 1 else 0) + (if (plan.showClear) 1 else 0)
            val showCopyLabel = plan.showCopy &&
                measuredCopyButtonWidth + SelectionPrimaryGap +
                SelectionActionSize * otherDirectCount <= controlsWidth

            EditorFloatingCapsule(heightMode = heightMode) {
                if (plan.showCopy) {
                    if (showCopyLabel) {
                        FilledTonalButton(
                            shapes = ButtonDefaults.shapes(),
                            contentPadding = ButtonDefaults.ContentPadding,
                            onClick = performCopy,
                            modifier = Modifier.heightIn(min = SelectionActionSize)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_content_copy),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(copyLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    } else {
                        EditorToolButton(
                            iconRes = R.drawable.ic_content_copy,
                            label = copyLabel,
                            enabled = true,
                            onClick = performCopy,
                            emphasized = true
                        )
                    }
                    Spacer(Modifier.width(SelectionPrimaryGap))
                }
                if (plan.showSend) {
                    EditorToolButton(
                        iconRes = R.drawable.ic_share,
                        label = sendLabel,
                        enabled = true,
                        onClick = performSend
                    )
                }
                Box {
                    EditorToolButton(
                        iconRes = R.drawable.ic_more_vert,
                        label = actionsLabel,
                        enabled = true,
                        onClick = { expanded = true }
                    )
                    DropdownMenuPopup(expanded = expanded, onDismissRequest = { expanded = false }) {
                        val displacedItems = buildList {
                            if (!plan.showCopy) {
                                add(SelectionMenuEntry(copyLabel, R.drawable.ic_content_copy, onClick = performCopy))
                            }
                            if (!plan.showSend) {
                                add(SelectionMenuEntry(sendLabel, R.drawable.ic_share, onClick = performSend))
                            }
                            if (!plan.showClear) {
                                add(
                                    SelectionMenuEntry(
                                        clearLabel,
                                        R.drawable.ic_close,
                                        onClick = session::clearDocumentSelection
                                    )
                                )
                            }
                            if (activeLabelSelection != null && !showInlineLabelField) {
                                add(
                                    SelectionMenuEntry(
                                        label = stringResource(R.string.selection_label),
                                        iconRes = R.drawable.ic_description,
                                        onClick = { labelDialogVisible = true }
                                    )
                                )
                            }
                        }
                        val secondaryItems = buildList {
                            val readingSelection = session.documentSelection as? DocumentSelection.Reading
                            if (readingSelection?.exactSource(session.readingDocumentForSelection()) != null) {
                                add(
                                    SelectionMenuEntry(
                                        label = stringResource(
                                            if (session.isViewOnly) R.string.editor_show_source else R.string.markdown_edit_source
                                        ),
                                        iconRes = if (session.isViewOnly) R.drawable.ic_description else R.drawable.ic_edit,
                                        enabled = session.canShowTextEditor,
                                        onClick = session::showTextEditor
                                    )
                                )
                            }
                            if (session.documentSelection is DocumentSelection.Source) {
                                add(
                                    SelectionMenuEntry(
                                        label = stringResource(R.string.selection_cut),
                                        enabled = session.canEditDocumentSelection,
                                        onClick = {
                                            session.replaceSelectedSource("") { text ->
                                                checkNotNull(context.getSystemService(ClipboardManager::class.java))
                                                    .setPrimaryClip(ClipData.newPlainText(selectionTitle, text))
                                            }
                                        }
                                    )
                                )
                                add(
                                    SelectionMenuEntry(
                                        label = stringResource(R.string.selection_paste),
                                        enabled = session.canEditDocumentSelection,
                                        onClick = {
                                            val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                                            clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.let {
                                                session.replaceSelectedSource(it.toString())
                                            }
                                        }
                                    )
                                )
                            }
                            add(
                                SelectionMenuEntry(
                                    label = stringResource(R.string.selection_find),
                                    iconRes = R.drawable.ic_search,
                                    onClick = {
                                        syncLabelSelection()
                                        session.findSelectedText()
                                    }
                                )
                            )
                            add(
                                SelectionMenuEntry(
                                    label = stringResource(R.string.selection_scope),
                                    iconRes = R.drawable.ic_search,
                                    onClick = {
                                        syncLabelSelection()
                                        session.showFind()
                                        session.captureSelectionFindScope()
                                    }
                                )
                            )
                            add(
                                SelectionMenuEntry(
                                    label = stringResource(R.string.selection_all),
                                    onClick = { session.selectWholeDocument() }
                                )
                            )
                            if (session.documentSelection is DocumentSelection.Reading) {
                                add(
                                    SelectionMenuEntry(
                                        label = stringResource(R.string.selection_extend_previous),
                                        onClick = { session.extendSelectionParagraph(false) }
                                    )
                                )
                                add(
                                    SelectionMenuEntry(
                                        label = stringResource(R.string.selection_extend_next),
                                        onClick = { session.extendSelectionParagraph(true) }
                                    )
                                )
                            }
                        }
                        DropdownMenuGroup(
                            shapes = MenuDefaults.groupShape(index = 0, count = 1),
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                            containerColor = MenuDefaults.groupStandardContainerColor
                        ) {
                            val totalCount = displacedItems.size + secondaryItems.size
                            var itemIndex = 0
                            @Composable
                            fun renderEntry(entry: SelectionMenuEntry) {
                                val shape = when {
                                    totalCount == 1 -> MenuDefaults.leadingItemShape
                                    itemIndex == 0 -> MenuDefaults.leadingItemShape
                                    itemIndex == totalCount - 1 -> MenuDefaults.trailingItemShape
                                    else -> MenuDefaults.middleItemShape
                                }
                                itemIndex += 1
                                ActionMenuItem(
                                    shape = shape,
                                    label = entry.label,
                                    leadingIcon = entry.iconRes?.let { res ->
                                        { Icon(painterResource(res), contentDescription = null) }
                                    },
                                    enabled = entry.enabled,
                                    onClick = {
                                        expanded = false
                                        entry.onClick()
                                    }
                                )
                            }
                            displacedItems.forEach { renderEntry(it) }
                            if (displacedItems.isNotEmpty() && secondaryItems.isNotEmpty()) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(MenuDefaults.HorizontalDividerPadding)
                                )
                            }
                            secondaryItems.forEach { renderEntry(it) }
                        }
                    }
                }
                if (plan.showClear) {
                    EditorToolButton(
                        iconRes = R.drawable.ic_close,
                        label = clearLabel,
                        enabled = true,
                        onClick = session::clearDocumentSelection
                    )
                }
            }
        }
    }
    if (labelDialogVisible && activeLabelSelection != null) {
        val dismissLabelDialog = {
            syncLabelSelection()
            labelDialogVisible = false
        }
        AlertDialog(
            onDismissRequest = dismissLabelDialog,
            title = { Text(stringResource(R.string.selection_label)) },
            text = {
                val (fieldState, syncState) = checkNotNull(labelFieldBinding)
                ReadOnlyLabelSelectionField(
                    state = fieldState,
                    syncState = syncState,
                    range = activeLabelSelection.range,
                    onSelectionChange = session::updateLabelSelection,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = dismissLabelDialog) {
                    Text(stringResource(R.string.action_close))
                }
            }
        )
    }
}

private class LabelSelectionSyncState(initialRange: TextRange) {
    @Volatile
    var lastSynced: TextRange = initialRange
}

@Composable
private fun ReadOnlyLabelSelectionField(
    state: TextFieldState,
    syncState: LabelSelectionSyncState,
    range: TextRange,
    onSelectionChange: (TextRange) -> Unit,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null
) {
    val latestOnSelectionChange by rememberUpdatedState(onSelectionChange)
    DisposableEffect(state) {
        val ownerThread = Thread.currentThread()
        val handle = Snapshot.registerApplyObserver { _, _ ->
            if (Thread.currentThread() === ownerThread) {
                val current = state.selection
                if (!current.collapsed && current != syncState.lastSynced) {
                    syncState.lastSynced = current
                    latestOnSelectionChange(current)
                }
            }
        }
        onDispose { handle.dispose() }
    }
    val currentSelection = state.selection
    if (range != syncState.lastSynced || currentSelection != syncState.lastSynced) {
        SideEffect {
            when {
                range != syncState.lastSynced -> {
                    syncState.lastSynced = range
                    if (state.selection != range) {
                        state.edit { selection = range }
                    }
                }
                !currentSelection.collapsed -> {
                    syncState.lastSynced = currentSelection
                    latestOnSelectionChange(currentSelection)
                }
                !range.collapsed && state.selection != range -> {
                    state.edit { selection = range }
                }
            }
        }
    }
    OutlinedTextField(
        state = state,
        readOnly = true,
        lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 4),
        label = label?.let { labelContent -> { labelContent() } },
        modifier = modifier
    )
}

@Composable
internal fun DiagramLabelSelectionAction(drawing: NativeIllustration, block: Int, spanStart: Int) {
    val session = LocalDocumentSelectionLayout.current?.session ?: return
    var choosing by remember(drawing) { mutableStateOf(false) }
    if (drawing.textRuns.isEmpty()) return
    TextButton(onClick = { choosing = true }) { Text(stringResource(R.string.selection_label_choose)) }
    if (choosing) AlertDialog(onDismissRequest = { choosing = false },
        title = { Text(stringResource(R.string.selection_label_choose)) },
        text = { LazyColumn(Modifier.heightIn(max = 400.dp)) {
            itemsIndexed(drawing.textRuns) { index, run ->
                TextButton(onClick = { if (session.selectDiagramLabel(block, spanStart, index)) choosing = false }, modifier = Modifier.fillMaxWidth()) {
                    Text(run.text)
                }
            }
        } }, confirmButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.find_hide_results)) } })
}
