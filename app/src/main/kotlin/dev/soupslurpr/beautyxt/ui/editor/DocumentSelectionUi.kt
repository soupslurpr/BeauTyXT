package dev.soupslurpr.beautyxt.ui.editor

import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.text.input.TextFieldValue
import dev.soupslurpr.beautyxt.illustration.NativeIllustration
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
    val layout: TextLayoutResult,
    val position: Offset,
    val point: (Int) -> SelectionPoint,
    val offset: (SelectionPoint) -> Int?
) {
    val bounds get() = Rect(position, androidx.compose.ui.geometry.Size(layout.size.width.toFloat(), layout.size.height.toFloat()))
    fun at(window: Offset): Int {
        val text = layout.layoutInput.text.text
        val offset = layout.getOffsetForPosition(window - position).coerceIn(0, text.length)
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
    fun hit(position: Offset, exact: Boolean = false): SelectionTextMeasurement? =
        texts.values.firstOrNull { it.bounds.contains(position) } ?: if (exact) null else texts.values.minByOrNull {
            val dy = maxOf(it.bounds.top - position.y, position.y - it.bounds.bottom, 0f)
            val dx = maxOf(it.bounds.left - position.x, position.x - it.bounds.right, 0f)
            dy * 10_000f + dx
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
    fun cursor(point: SelectionPoint): Offset? = texts.values.firstNotNullOfOrNull { text ->
        text.offset(point)?.takeIf { it in 0..text.layout.layoutInput.text.length }?.let {
            val cursor = text.layout.getCursorRect(it)
            text.position + cursor.bottomLeft
        }
    }

    fun moveEndpoint(first: Boolean, forward: Boolean): Boolean {
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
        characters.setText(text.layout.layoutInput.text.text)
        val moved = if (forward) characters.following(offset) else characters.preceding(offset)
        val next = if (moved != android.icu.text.BreakIterator.DONE) text.point(moved)
            else ordered.getOrNull(current + if (forward) 1 else -1)?.let {
                it.point(if (forward) 0 else it.layout.layoutInput.text.length)
            } ?: return false
        return if (first) select(endpoints.second, next) else select(endpoints.first, next)
    }
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
    contentOffset: () -> Offset = { Offset.Zero }
): Modifier {
    val registry = LocalDocumentSelectionLayout.current
        ?: return if (additionalActions.isEmpty()) this else semantics { customActions = additionalActions }
    var position by remember(key) { mutableStateOf<Offset?>(null) }
    val currentContentOffset by rememberUpdatedState(contentOffset)
    val currentPoint by rememberUpdatedState(point)
    val currentOffset by rememberUpdatedState(offset)
    LaunchedEffect(registry, key, layout, position) {
        val origin = position ?: return@LaunchedEffect
        if (layout == null) return@LaunchedEffect
        snapshotFlow { currentContentOffset() }.collect { displacement ->
            registry.texts[key] = SelectionTextMeasurement(layout, origin + displacement, currentPoint, currentOffset)
        }
    }
    DisposableEffect(registry, key) { onDispose { registry.texts.remove(key) } }
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
            val distance = when {
                position.y < root.top + edge -> -(root.top + edge - position.y).coerceAtMost(edge)
                position.y > root.bottom - edge -> (position.y - root.bottom + edge).coerceAtMost(edge)
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
                if (endpoints != null && listOf(endpoints.first, endpoints.second).any { point ->
                    registry.cursor(point)?.let { cursor ->
                        Rect(cursor.x - edge / 2, cursor.y, cursor.x + edge / 2, cursor.y + edge).contains(position)
                    } == true
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
            registry.texts.values.forEach { text ->
                val range = text.selected(session.documentSelection) ?: return@forEach
                translate(text.position.x - root.left, text.position.y - root.top) {
                    drawPath(text.layout.getPathForRange(range.min, range.max), color.copy(alpha = .25f))
                }
            }
            if (session.isFindVisible) session.findResults.filter { it.source?.let { range -> range.start == range.end } == true }.forEach { result ->
                val point = SelectionPoint.Source(result.source!!.start)
                registry.texts.values.firstNotNullOfOrNull { text -> text.offset(point)?.let { offset -> text to offset } }?.let { (text, offset) ->
                    val rect = text.layout.getCursorRect(offset)
                    val origin = text.position - root.topLeft
                    drawLine(color, origin + rect.topLeft, origin + rect.bottomLeft, 2.dp.toPx())
                }
            }
        }
        val endpoints = selectionEndpoints(session.documentSelection)
        if (endpoints != null) listOf(true, false).forEach { first ->
            val point = if (first) endpoints.first else endpoints.second
            val handlePosition = registry.cursor(point)
            if (handlePosition != null && handlePosition.y in root.top..root.bottom) {
                val currentEndpoints by rememberUpdatedState(endpoints)
                val currentHandlePosition by rememberUpdatedState(handlePosition)
                val description = stringResource(if (first) R.string.selection_start_handle else R.string.selection_end_handle)
                val previous = stringResource(R.string.selection_boundary_previous)
                val next = stringResource(R.string.selection_boundary_next)
                Box(Modifier.offset { IntOffset((handlePosition.x - root.left - edge / 2).roundToInt(), (handlePosition.y - root.top).roundToInt()) }
                    .size(48.dp).semantics {
                        contentDescription = description
                        customActions = listOf(CustomAccessibilityAction(previous) { registry.moveEndpoint(first, false) },
                            CustomAccessibilityAction(next) { registry.moveEndpoint(first, true) })
                    }.onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key in listOf(Key.DirectionLeft, Key.DirectionRight))
                            registry.moveEndpoint(first, event.key == Key.DirectionRight) else false
                    }.focusable()
                    .pointerInput(first, registry) {
                        var position: Offset = checkNotNull(handlePosition)
                        detectDragGestures(onDragStart = {
                            dragAnchor = if (first) currentEndpoints.second else currentEndpoints.first
                            position = checkNotNull(currentHandlePosition)
                            dragPosition = position
                        }, onDragEnd = { dragAnchor = null; dragPosition = null },
                            onDragCancel = { dragAnchor = null; dragPosition = null }) { change, amount ->
                            position += amount; extend(position); change.consume()
                        }
                    }) {
                    Box(Modifier.padding(start = 16.dp).size(16.dp).background(color, CircleShape))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DocumentSelectionActions(session: EditorSession) {
    if (session.documentSelection == null) return
    val context = LocalContext.current
    val selectionTitle = stringResource(R.string.selection_title)
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = { session.copyDocumentSelection { text ->
                checkNotNull(context.getSystemService(ClipboardManager::class.java)).setPrimaryClip(
                    ClipData.newPlainText(selectionTitle, text))
            } }) { Text(stringResource(R.string.markdown_copy)) }
            TextButton(onClick = { session.excerptExport.open(context, session.title) }) { Text(stringResource(R.string.excerpt_send)) }
            Box {
                TextButton(onClick = { expanded = true }) { Text(stringResource(R.string.selection_actions)) }
                DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                    fun action(perform: () -> Unit) { expanded = false; perform() }
                    val readingSelection = session.documentSelection as? DocumentSelection.Reading
                    if (readingSelection?.exactSource(session.readingDocumentForSelection()) != null) {
                        DropdownMenuItem(text = { Text(stringResource(if (session.isViewOnly) R.string.editor_show_source else R.string.markdown_edit_source)) },
                            enabled = session.canShowTextEditor, onClick = { action(session::showTextEditor) })
                    }
                    if (session.documentSelection is DocumentSelection.Source) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.selection_cut)) }, enabled = session.canEditDocumentSelection,
                            onClick = { action { session.replaceSelectedSource("") { text ->
                                checkNotNull(context.getSystemService(ClipboardManager::class.java)).setPrimaryClip(
                                    ClipData.newPlainText(selectionTitle, text))
                            } } })
                        DropdownMenuItem(text = { Text(stringResource(R.string.selection_paste)) }, enabled = session.canEditDocumentSelection,
                            onClick = { action {
                                val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                                clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.let { session.replaceSelectedSource(it.toString()) }
                            } })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.selection_find)) }, onClick = { action(session::findSelectedText) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.selection_scope)) }, onClick = { action { session.showFind(); session.captureSelectionFindScope() } })
                    DropdownMenuItem(text = { Text(stringResource(R.string.selection_all)) }, onClick = { action { session.selectWholeDocument() } })
                    if (session.documentSelection is DocumentSelection.Reading) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.selection_extend_previous)) }, onClick = { action { session.extendSelectionParagraph(false) } })
                        DropdownMenuItem(text = { Text(stringResource(R.string.selection_extend_next)) }, onClick = { action { session.extendSelectionParagraph(true) } })
                    }
                }
            }
            TextButton(onClick = session::clearDocumentSelection) { Text(stringResource(R.string.selection_clear)) }
        }
        (session.documentSelection as? DocumentSelection.Label)?.let { label ->
            OutlinedTextField(value = TextFieldValue(label.text, label.range), onValueChange = { session.updateLabelSelection(it.selection) },
                readOnly = true, maxLines = 4, label = { Text(stringResource(R.string.selection_label)) }, modifier = Modifier.fillMaxWidth())
        }
        session.selectionMessage?.let { Text(it.asString(), Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    }
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
